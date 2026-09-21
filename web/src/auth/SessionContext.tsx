/**
 * Where a session lives on the web client. The access token is never
 * persisted, only held in memory — a reload always goes through one refresh.
 * The refresh token is device-scoped and long-lived (30 days, L1), so it is
 * kept in `localStorage` with the user id it belongs to; losing it just
 * means signing in again, the same as it would on a phone that had its app
 * data cleared.
 *
 * A reload with both on hand is signed in at once, before that refresh has
 * answered, so the lists paint straight from the local replica — offline
 * too — the way the Android app opens without asking the server first. Only
 * a refresh the server actually rejects signs the browser out.
 */

import type { TokenPair } from "@dielys/protocol";
import { createContext, type ReactNode, useCallback, useContext, useEffect, useState } from "react";
import {
  type AuthTokens,
  isDefinitelySignedOut,
  onRefreshTokenRequested,
  onTokensRefreshed,
  refreshTokens,
  setAuthTokens,
} from "../api/client.js";
import { closeStore } from "../data/store.js";
import { deviceId } from "../domain/deviceId.js";

const REFRESH_TOKEN_KEY = "dielys.refreshToken";
const USER_ID_KEY = "dielys.userId";
// The server never hands the email back (a `TokenPair` carries only a user
// id) — this is whatever was last typed into the sign-in form, best-effort,
// same gap `android/.../data/local/SessionStore.kt`'s `email` has.
const EMAIL_KEY = "dielys.email";

type SessionState =
  | { status: "loading" }
  | { status: "signed-out" }
  | { status: "signed-in"; userId: string; email: string | null };

type SessionContextValue = SessionState & {
  deviceId: string;
  signIn(pair: TokenPair): void;
  signOut(): void;
  /** Called as soon as an email is typed into the sign-in form — before a
   *  magic link is even sent, so it is already on hand by the time that
   *  link is opened, in this tab or a new one (`localStorage` is per-origin,
   *  not per-tab). */
  rememberEmail(email: string): void;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ status: "loading" });
  // Computed once, not per render — deviceId() is a stable per-browser value
  // (domain/deviceId.ts), so this never changes after mount.
  const [device] = useState(deviceId);

  const signIn = useCallback(
    (pair: TokenPair) => {
      localStorage.setItem(REFRESH_TOKEN_KEY, pair.refreshToken);
      localStorage.setItem(USER_ID_KEY, pair.userId);
      setAuthTokens({
        accessToken: pair.accessToken,
        refreshToken: pair.refreshToken,
        deviceId: device,
        userId: pair.userId,
      });
      setState({
        status: "signed-in",
        userId: pair.userId,
        email: localStorage.getItem(EMAIL_KEY),
      });
    },
    [device],
  );

  const signOut = useCallback(() => {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_ID_KEY);
    localStorage.removeItem(EMAIL_KEY);
    setAuthTokens(null);
    // This account's lists and anything still queued leave the browser with it.
    void closeStore(true);
    setState({ status: "signed-out" });
  }, []);

  const rememberEmail = useCallback((email: string) => {
    localStorage.setItem(EMAIL_KEY, email);
    setState((prev) => (prev.status === "signed-in" ? { ...prev, email } : prev));
  }, []);

  // Startup: a stored refresh token is turned back into a working session by
  // spending it once — the same 401 → refresh path a mid-session token
  // expiry takes, just triggered directly instead of by a failed request.
  useEffect(() => {
    const stored = localStorage.getItem(REFRESH_TOKEN_KEY);
    if (stored === null) {
      setState({ status: "signed-out" });
      return;
    }
    const storedUserId = localStorage.getItem(USER_ID_KEY);
    let cancelled = false;
    setAuthTokens({
      accessToken: "",
      refreshToken: stored,
      deviceId: device,
      userId: storedUserId ?? "",
    });
    // Known account: show its local data now, refresh behind it.
    if (storedUserId !== null) {
      setState({
        status: "signed-in",
        userId: storedUserId,
        email: localStorage.getItem(EMAIL_KEY),
      });
    }
    refreshTokens()
      .then((refreshed) => {
        if (cancelled) return;
        if (refreshed === null) {
          // Only forget this device when the server actually rejected the
          // token — a rate limit or a dropped request must not throw away an
          // otherwise-good 30-day refresh token (#83); the next request
          // retries with the same one still in localStorage.
          if (isDefinitelySignedOut()) {
            signOut();
          } else if (storedUserId === null) {
            setState({ status: "signed-out" });
          }
          return;
        }
        localStorage.setItem(REFRESH_TOKEN_KEY, refreshed.refreshToken);
        localStorage.setItem(USER_ID_KEY, refreshed.userId);
        setState({
          status: "signed-in",
          userId: refreshed.userId,
          email: localStorage.getItem(EMAIL_KEY),
        });
      })
      .catch(() => {
        // Never reached the server. With a known account, stay signed in on
        // local data; without one there is nothing to show yet.
        if (!cancelled && storedUserId === null) setState({ status: "signed-out" });
      });
    return () => {
      cancelled = true;
    };
  }, [device, signOut]);

  useEffect(() => {
    onTokensRefreshed((refreshed: AuthTokens) => {
      localStorage.setItem(REFRESH_TOKEN_KEY, refreshed.refreshToken);
    });
    onRefreshTokenRequested(() => localStorage.getItem(REFRESH_TOKEN_KEY));
  }, []);

  return (
    <SessionContext.Provider value={{ ...state, deviceId: device, signIn, signOut, rememberEmail }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (value === null) throw new Error("useSession() outside SessionProvider");
  return value;
}
