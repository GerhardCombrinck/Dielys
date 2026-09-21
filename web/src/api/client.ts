/**
 * Thin fetch wrapper: attaches `Authorization`, retries once through a
 * refresh on a 401, and otherwise throws. Queuing and retrying list edits is
 * not done here — that is the outbox's job (`data/syncEngine.ts`); this layer
 * only reports what happened to one request.
 */
import type { RefreshRequest, TokenPair } from "@dielys/protocol";

/**
 * Where the API lives. Production hosting for `web/` itself (same origin as
 * the API, or a separate one) is not decided yet — this only has to be right
 * for local development against `wrangler dev` until it is.
 */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8787";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
  ) {
    super(`${status} ${code}`);
  }
}

async function parseError(response: Response): Promise<ApiError> {
  const body = (await response.json().catch(() => null)) as { code?: string } | null;
  return new ApiError(response.status, body?.code ?? "internal");
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  deviceId: string;
  userId: string;
}

/** Set once at startup by `SessionContext` — the one place tokens live. */
let tokens: AuthTokens | null = null;
let onRefreshed: ((tokens: AuthTokens) => void) | null = null;
let getPersistedRefreshToken: (() => string | null) | null = null;

export function setAuthTokens(next: AuthTokens | null): void {
  tokens = next;
}

/**
 * True once a refresh token has been definitively rejected (401) rather than
 * `refreshTokens()` merely returning null for a transient reason — a rate
 * limit or a network blip. `SessionContext`'s startup path uses this to
 * decide whether a failed refresh really means "sign out and forget this
 * device" or just "could not reach the server just now, try again later"
 * (#83) — only the former should discard the stored 30-day refresh token.
 */
export function isDefinitelySignedOut(): boolean {
  return tokens === null;
}

/** `SessionContext` registers this so a background refresh (triggered by a
 * 401 on some unrelated request) still updates React state and storage. */
export function onTokensRefreshed(callback: (tokens: AuthTokens) => void): void {
  onRefreshed = callback;
}

/**
 * `SessionContext` registers this so a refresh can always start from the
 * freshest value in `localStorage` — see the cross-tab note on
 * `REFRESH_LOCK_NAME` below for why that matters.
 */
export function onRefreshTokenRequested(callback: () => string | null): void {
  getPersistedRefreshToken = callback;
}

/**
 * Exchanges the stored refresh token for a new pair, rotating it (L1).
 * Exported so `SessionContext` can also call this once at startup to turn a
 * `deviceId` + refresh token recovered from `localStorage` back into a
 * working access token — the same operation a 401 triggers mid-session.
 *
 * A refresh token is single-use (L1): presenting an already-rotated one is
 * treated by the server as theft and revokes every session for that user —
 * every device, not just this tab (#83). Two callers racing each other would
 * otherwise both spend the same token and the loser would trigger exactly
 * that. `refreshInFlight` dedupes callers within one tab (two requests each
 * hitting a 401 at once, or React StrictMode's double-invoked effect), but
 * it is a module-level variable — a second *tab* is a separate module
 * instance and races right past it. `REFRESH_LOCK_NAME` covers that: only
 * one tab's network call runs at a time, and each one re-reads
 * `localStorage` after acquiring the lock, in case another tab already
 * rotated the token while this one was waiting.
 */
let refreshInFlight: Promise<AuthTokens | null> | null = null;
const REFRESH_LOCK_NAME = "dielys.refreshToken.lock";

export function refreshTokens(): Promise<AuthTokens | null> {
  if (refreshInFlight === null) {
    refreshInFlight = withRefreshLock(doRefresh).finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

function withRefreshLock(fn: () => Promise<AuthTokens | null>): Promise<AuthTokens | null> {
  if (typeof navigator === "undefined" || !("locks" in navigator)) return fn();
  // lib.dom.d.ts types LockGrantedCallback's return as `T` rather than
  // `T | PromiseLike<T>`, even though the spec awaits a returned promise
  // before releasing the lock (MDN: LockManager.request()) — the cast below
  // is for that typing gap, not a runtime mismatch.
  return navigator.locks.request(REFRESH_LOCK_NAME, () =>
    fn(),
  ) as unknown as Promise<AuthTokens | null>;
}

async function doRefresh(): Promise<AuthTokens | null> {
  if (tokens === null) return null;
  const refreshToken = getPersistedRefreshToken?.() ?? tokens.refreshToken;
  const body: RefreshRequest = { refreshToken, deviceId: tokens.deviceId };
  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    // Only a token the server has definitively rejected (expired, reused, or
    // otherwise invalid — always a 401 here) means the session is actually
    // over. A rate limit or a transient server error must not throw away an
    // otherwise-good 30-day refresh token (#83).
    if (response.status === 401) tokens = null;
    return null;
  }
  const pair = (await response.json()) as TokenPair;
  const next: AuthTokens = {
    accessToken: pair.accessToken,
    refreshToken: pair.refreshToken,
    deviceId: tokens.deviceId,
    userId: pair.userId,
  };
  tokens = next;
  onRefreshed?.(next);
  return next;
}

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  auth?: boolean; // default true
}

/**
 * `auth: false` for the handful of routes that take no bearer token
 * (login, magic link, register) — everything else defaults to attaching one.
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const auth = options.auth ?? true;
  const doFetch = () => {
    const headers: Record<string, string> = {};
    if (options.body !== undefined) headers["content-type"] = "application/json";
    if (auth && tokens !== null) headers.Authorization = `Bearer ${tokens.accessToken}`;
    const init: RequestInit = {
      method: options.method ?? (options.body !== undefined ? "POST" : "GET"),
      headers,
    };
    if (options.body !== undefined) init.body = JSON.stringify(options.body);
    return fetch(`${API_BASE_URL}${path}`, init);
  };

  let response = await doFetch();
  if (response.status === 401 && auth && tokens !== null) {
    const refreshed = await refreshTokens();
    if (refreshed !== null) response = await doFetch();
  }
  if (!response.ok) throw await parseError(response);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
