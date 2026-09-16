/**
 * Thin fetch wrapper: attaches `Authorization`, retries once through a
 * refresh on a 401. No offline queue, no retry-with-backoff beyond that one
 * refresh — this client is online-first (web/AGENTS.md); a failed request
 * surfaces as an error for the caller to show, not something queued for
 * later.
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

export function setAuthTokens(next: AuthTokens | null): void {
  tokens = next;
}

/** `SessionContext` registers this so a background refresh (triggered by a
 * 401 on some unrelated request) still updates React state and storage. */
export function onTokensRefreshed(callback: (tokens: AuthTokens) => void): void {
  onRefreshed = callback;
}

/**
 * Exchanges the stored refresh token for a new pair, rotating it (L1).
 * Exported so `SessionContext` can also call this once at startup to turn a
 * `deviceId` + refresh token recovered from `localStorage` back into a
 * working access token — the same operation a 401 triggers mid-session.
 *
 * A refresh token is single-use (L1): presenting an already-rotated one is
 * treated as theft and revokes every session for that user. Two callers
 * racing each other — React's StrictMode double-invoking an effect in dev,
 * or two requests each hitting a 401 at once — would otherwise both spend
 * the same token and the loser would revoke the winner's brand new session.
 * `refreshInFlight` makes every caller during one refresh share its result
 * instead of racing.
 */
let refreshInFlight: Promise<AuthTokens | null> | null = null;

export function refreshTokens(): Promise<AuthTokens | null> {
  if (refreshInFlight === null) {
    refreshInFlight = doRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function doRefresh(): Promise<AuthTokens | null> {
  if (tokens === null) return null;
  const body: RefreshRequest = { refreshToken: tokens.refreshToken, deviceId: tokens.deviceId };
  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    tokens = null;
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
  method?: "GET" | "POST" | "DELETE";
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
