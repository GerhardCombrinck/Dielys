/**
 * FCM HTTP v1, over `fetch` and WebCrypto. No storage access and no knowledge
 * of lists or membership (D1) — it is handed a set of tokens and a payload and
 * reports which tokens are gone.
 *
 * There is no Firebase Admin SDK here on purpose. It is a Node library that
 * wants `crypto`, `http` and a filesystem, and the whole of what it does for
 * this app is: sign a JWT, swap it for an access token, POST a small JSON body.
 * That is the file below (N1).
 *
 * The one payload rule this module exists to keep is M1: the body it builds
 * carries `data` and never `notification`, and the only three keys in `data`
 * are `type`, `listId` and `seq`. Nothing a user typed goes to Google.
 */
import { PUSH_TYPE_SYNC, type SyncPushPayload } from "@dielys/protocol";
import { encodeBase64Url, encodeUtf8Base64Url } from "../lib/base64url.js";
import { log } from "../lib/log.js";

/** The scope FCM's send endpoint requires, and the only one asked for. */
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

const DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

/** Google rejects an assertion valid for longer than an hour. */
const ASSERTION_TTL_SECONDS = 3600;

/**
 * How early to re-mint. A token used at the moment it expires is a request
 * wasted on a 401, and minting one costs a signature and a round trip.
 */
const TOKEN_REFRESH_SKEW_MS = 60_000;

/**
 * A wake is only useful while the app has not already caught up by other means.
 * Past an hour the half-hourly `WorkManager` sync (H3.12) has run twice, so a
 * push delivered later would wake the phone to do nothing.
 */
const MESSAGE_TTL = "3600s";

/**
 * One collapse key for the whole app, not one per list.
 *
 * A wake carries no instruction — the client drains its outbox and catches up
 * every list it knows about, whatever the payload says — so a second queued
 * wake adds nothing to the first and may as well replace it. FCM also caps a
 * device at four distinct collapse keys and evicts arbitrarily past that, which
 * a per-list key would eventually hit.
 */
const COLLAPSE_KEY = "dielys-sync";

/** The fields of a service-account JSON that are actually used. */
export interface ServiceAccount {
  projectId: string;
  clientEmail: string;
  privateKey: string;
  tokenUri: string;
}

/** A device to wake: which one, and the token FCM knows it by. */
export interface WakeTarget {
  deviceId: string;
  fcmToken: string;
}

/**
 * Reads `FCM_SERVICE_ACCOUNT_JSON`, or answers null when there is nothing
 * usable there.
 *
 * Null is a normal state, not an error: a deployment without FCM configured
 * still syncs — over the socket when the app is open, and on the half-hourly
 * worker when it is not. This is deliberately unlike `JWT_SIGNING_KEY`, which
 * fails the whole Worker closed: an absent signing key is a security hole,
 * whereas an absent push credential just makes the app slower to notice things.
 */
export function parseServiceAccount(raw: string | undefined): ServiceAccount | null {
  if (typeof raw !== "string" || raw.trim().length === 0) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    // Never log the value — it contains a private key.
    log("warn", "fcm.service-account.unparseable", {});
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;

  const record = parsed as Record<string, unknown>;
  const projectId = record.project_id;
  const clientEmail = record.client_email;
  const privateKey = record.private_key;
  const tokenUri = record.token_uri;

  if (
    typeof projectId !== "string" ||
    typeof clientEmail !== "string" ||
    typeof privateKey !== "string" ||
    projectId.length === 0 ||
    clientEmail.length === 0 ||
    privateKey.length === 0
  ) {
    log("warn", "fcm.service-account.incomplete", {});
    return null;
  }

  return {
    projectId,
    clientEmail,
    privateKey,
    tokenUri: typeof tokenUri === "string" && tokenUri.length > 0 ? tokenUri : DEFAULT_TOKEN_URI,
  };
}

/** The complete `data` map of a wake push (M1). Built in one place, so the
 * rule about what may be in it has one place to be broken. */
export function wakeData(listId: string, seq: number): SyncPushPayload {
  return { type: PUSH_TYPE_SYNC, listId, seq: String(seq) };
}

/**
 * The OAuth2 assertion Google swaps for an access token: a JWT signed with the
 * service account's RSA key.
 *
 * Exported for its own test. This is a contract with somebody else's server —
 * the claim names, the scope and the signature algorithm are all things that
 * fail at 3 a.m. rather than at build time if they are wrong, and testing them
 * through a mocked send would prove much less.
 */
export async function buildAssertion(account: ServiceAccount, now: number): Promise<string> {
  const issued = Math.floor(now / 1000);
  const header = encodeUtf8Base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = encodeUtf8Base64Url(
    JSON.stringify({
      iss: account.clientEmail,
      scope: SCOPE,
      aud: account.tokenUri,
      iat: issued,
      exp: issued + ASSERTION_TTL_SECONDS,
    }),
  );

  const body = `${header}.${claims}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    await importPrivateKey(account.privateKey),
    new TextEncoder().encode(body),
  );
  return `${body}.${encodeBase64Url(new Uint8Array(signature))}`;
}

/**
 * Sends wake pushes and caches the access token between them.
 *
 * Instance state, never module-level (D3): a Durable Object owns one of these
 * and it dies with the object. A fresh instance after hibernation mints a new
 * token, which costs one signature and one round trip.
 */
export class FcmSender {
  private accessToken: { value: string; expiresAt: number } | null = null;

  constructor(private readonly account: ServiceAccount) {}

  /**
   * Wakes each target. Returns the device ids FCM says it has never heard of,
   * so the caller can drop the rows — an install that was wiped keeps its
   * database row forever otherwise.
   *
   * One failed send never fails another: they go out together and are settled
   * independently. A push is best-effort by construction (H3.12), so nothing
   * here throws at the caller.
   */
  async wake(
    targets: readonly WakeTarget[],
    payload: SyncPushPayload,
    now: number,
  ): Promise<string[]> {
    if (targets.length === 0) return [];

    let token: string;
    try {
      token = await this.token(now);
    } catch (error) {
      log("error", "fcm.token.failed", { error: String(error) });
      return [];
    }

    const results = await Promise.allSettled(
      targets.map((target) => this.send(target, payload, token)),
    );

    const gone: string[] = [];
    results.forEach((result, index) => {
      const target = targets[index];
      if (target === undefined) return;
      if (result.status === "fulfilled") {
        if (result.value === "gone") gone.push(target.deviceId);
        return;
      }
      log("warn", "fcm.send.failed", { deviceId: target.deviceId, error: String(result.reason) });
    });
    return gone;
  }

  private async send(
    target: WakeTarget,
    payload: SyncPushPayload,
    accessToken: string,
  ): Promise<"sent" | "gone" | "failed"> {
    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(this.account.projectId)}/messages:send`,
      {
        method: "POST",
        headers: {
          authorization: `Bearer ${accessToken}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: target.fcmToken,
            // Data only. There is deliberately no `notification` key here and
            // there must never be one (M1) — it would put server-composed text
            // on somebody's lock screen.
            data: payload,
            android: { priority: "high", ttl: MESSAGE_TTL, collapse_key: COLLAPSE_KEY },
          },
        }),
      },
    );

    if (response.ok) return "sent";

    // 404 is FCM's UNREGISTERED: the app was uninstalled or its data cleared,
    // and this token will never work again. Only 404 — a 400 can be our own
    // malformed request, and deleting a good token over our bug would silently
    // stop waking a phone that is working fine.
    if (response.status === 404) {
      log("info", "fcm.send.unregistered", { deviceId: target.deviceId });
      return "gone";
    }

    if (response.status === 401 || response.status === 403) {
      // The credential is wrong or was revoked. Drop the cache so the next
      // attempt mints a fresh one rather than replaying a dead token.
      this.accessToken = null;
    }

    // Never log the body: it echoes the request, which contains a device token.
    log("warn", "fcm.send.rejected", { deviceId: target.deviceId, status: response.status });
    return "failed";
  }

  private async token(now: number): Promise<string> {
    const cached = this.accessToken;
    if (cached !== null && cached.expiresAt - TOKEN_REFRESH_SKEW_MS > now) return cached.value;

    const response = await fetch(this.account.tokenUri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: await buildAssertion(this.account, now),
      }).toString(),
    });

    if (!response.ok) {
      // The body of a failed token exchange can echo the assertion. Status only.
      throw new Error(`token exchange failed: ${response.status}`);
    }

    const body = (await response.json()) as { access_token?: unknown; expires_in?: unknown };
    if (typeof body.access_token !== "string" || body.access_token.length === 0) {
      throw new Error("token exchange returned no access_token");
    }

    const lifetime = typeof body.expires_in === "number" ? body.expires_in : ASSERTION_TTL_SECONDS;
    this.accessToken = { value: body.access_token, expiresAt: now + lifetime * 1000 };
    return body.access_token;
  }
}

/**
 * PKCS#8, which is what a Google service account's `private_key` is: a PEM
 * whose body is standard base64 (not base64url, so `lib/base64url.ts` is the
 * wrong decoder for it).
 */
async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const base64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const binary = atob(base64);
  const der = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) der[i] = binary.charCodeAt(i);

  return crypto.subtle.importKey(
    "pkcs8",
    der,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}
