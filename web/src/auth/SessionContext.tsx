/**
 * Where a session lives on the web client. Online-first (web/AGENTS.md): the
 * access token is never persisted, only held in memory — a reload always
 * goes through one refresh. The refresh token is device-scoped and
 * long-lived (30 days, L1), so it is the one thing kept in `localStorage`;
 * losing it just means signing in again, the same as it would on a phone
 * that had its app data cleared.
 */

import type { TokenPair } from "@dielys/protocol";
import { createContext, type ReactNode, useCallback, useContext, useEffect, useState } from "react";
import { type AuthTokens, onTokensRefreshed, refreshTokens, setAuthTokens } from "../api/client.js";
import { deviceId } from "../domain/deviceId.js";

const REFRESH_TOKEN_KEY = "dielys.refreshToken";

type SessionState =
  | { status: "loading" }
  | { status: "signed-out" }
  | { status: "signed-in"; userId: string };

type SessionContextValue = SessionState & {
  deviceId: string;
  signIn(pair: TokenPair): void;
  signOut(): void;
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
      setAuthTokens({
        accessToken: pair.accessToken,
        refreshToken: pair.refreshToken,
        deviceId: device,
        userId: pair.userId,
      });
      setState({ status: "signed-in", userId: pair.userId });
    },
    [device],
  );

  const signOut = useCallback(() => {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    setAuthTokens(null);
    setState({ status: "signed-out" });
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
    let cancelled = false;
    setAuthTokens({ accessToken: "", refreshToken: stored, deviceId: device, userId: "" });
    refreshTokens()
      .then((refreshed) => {
        if (cancelled) return;
        if (refreshed === null) {
          localStorage.removeItem(REFRESH_TOKEN_KEY);
          setState({ status: "signed-out" });
          return;
        }
        localStorage.setItem(REFRESH_TOKEN_KEY, refreshed.refreshToken);
        setState({ status: "signed-in", userId: refreshed.userId });
      })
      .catch(() => {
        if (!cancelled) setState({ status: "signed-out" });
      });
    return () => {
      cancelled = true;
    };
  }, [device]);

  useEffect(() => {
    onTokensRefreshed((refreshed: AuthTokens) => {
      localStorage.setItem(REFRESH_TOKEN_KEY, refreshed.refreshToken);
    });
  }, []);

  return (
    <SessionContext.Provider value={{ ...state, deviceId: device, signIn, signOut }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (value === null) throw new Error("useSession() outside SessionProvider");
  return value;
}
