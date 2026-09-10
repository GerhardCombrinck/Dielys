/**
 * Sends the magic-link email through Brevo's transactional API, over `fetch`.
 * No storage access and no knowledge of tokens or accounts (D1) — it is
 * handed an address and a link and reports whether Brevo accepted the send.
 *
 * Unlike `push/fcm.ts`, a failed send here is not best-effort: a wake push
 * that never arrives is caught up by the next sync, but a magic link that
 * never left this Worker is a login the caller cannot complete. The caller
 * (`UsersRoom.requestMagicLink`) surfaces a failed send as an error rather
 * than swallowing it the way a dead FCM token is swallowed.
 */
import { log } from "../lib/log.js";

const BREVO_SEND_URL = "https://api.brevo.com/v3/smtp/email";
const BREVO_EVENTS_URL = "https://api.brevo.com/v3/smtp/statistics/events";

/** The `From` identity every magic-link email is sent as. */
export interface EmailSender {
  email: string;
  name: string;
}

/**
 * Reads `EMAIL_FROM` / `EMAIL_FROM_NAME`, or answers null when there is
 * nothing usable there. Null is what makes `/auth/magic/*` fail closed (ADR
 * 0005) — checked by the Worker before this module is ever reached, the same
 * shape as `isUsableSigningKey`.
 */
export function parseEmailSender(
  rawFrom: string | undefined,
  rawName: string | undefined,
): EmailSender | null {
  if (typeof rawFrom !== "string" || rawFrom.trim().length === 0) return null;
  const name = typeof rawName === "string" && rawName.trim().length > 0 ? rawName.trim() : "Dielys";
  return { email: rawFrom.trim(), name };
}

export type SendResult = { sent: true; messageId: string | null } | { sent: false };

/**
 * Sends one sign-in link. Never throws — a network failure and a rejection
 * from Brevo answer the same way, and the caller decides what "not sent"
 * means for the request in front of it.
 *
 * `messageId` is null when Brevo accepts the send but omits it — the send
 * itself still counts as successful; it is only delivery-status polling
 * (`isEmailDelivered`) that loses the ability to ever report "delivered".
 */
export async function sendMagicLinkEmail(
  apiKey: string,
  sender: EmailSender,
  to: string,
  link: string,
  ttlMinutes: number,
): Promise<SendResult> {
  let response: Response;
  try {
    response = await fetch(BREVO_SEND_URL, {
      method: "POST",
      headers: {
        "api-key": apiKey,
        "content-type": "application/json",
        accept: "application/json",
      },
      body: JSON.stringify({
        sender: { email: sender.email, name: sender.name },
        to: [{ email: to }],
        subject: "Sign in to Dielys",
        textContent: `Tap this link on your phone to sign in to Dielys:\n\n${link}\n\nIt expires in ${ttlMinutes} minutes and works once. If you did not request this, ignore this email.`,
      }),
    });
  } catch (error) {
    log("warn", "brevo.send.failed", { error: String(error) });
    return { sent: false };
  }

  if (!response.ok) {
    // Never log the body: a Brevo error response echoes the recipient (D4).
    log("warn", "brevo.send.rejected", { status: response.status });
    return { sent: false };
  }

  const body = await response
    .json<{ messageId?: string }>()
    .catch(() => ({}) as { messageId?: string });
  return { sent: true, messageId: typeof body.messageId === "string" ? body.messageId : null };
}

/**
 * Whether Brevo has recorded a `delivered` event for this message yet.
 * Answers `false` on any failure to reach Brevo or parse its response —
 * this only ever gates an optimistic "delivered" label the caller polls
 * for, so the safe direction on doubt is to keep saying "not yet" rather
 * than to guess.
 */
export async function isEmailDelivered(apiKey: string, messageId: string): Promise<boolean> {
  let response: Response;
  try {
    response = await fetch(
      `${BREVO_EVENTS_URL}?messageId=${encodeURIComponent(messageId)}&event=delivered`,
      { headers: { "api-key": apiKey, accept: "application/json" } },
    );
  } catch (error) {
    log("warn", "brevo.events.failed", { error: String(error) });
    return false;
  }

  if (!response.ok) {
    log("warn", "brevo.events.rejected", { status: response.status });
    return false;
  }

  const body = await response.json<{ events?: unknown[] }>().catch(() => ({ events: [] }));
  return Array.isArray(body.events) && body.events.length > 0;
}
