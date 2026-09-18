/**
 * `dielys.com/admin` (#84) — total accounts, lists and items, for the one
 * person who runs this service to check on out of curiosity. Gated by the
 * `ADMIN_TOKEN` secret (the same one `scripts/create-user.ts` uses), not a
 * user session — App.tsx checks this route ahead of the sign-in gate, same
 * as `/account/delete`, since there is no reason the one person allowed
 * here would need to be signed in on this browser as an app user too.
 *
 * The token is kept in `localStorage` so it survives a reload, the same
 * trade-off `SessionContext` already makes for the refresh token: a bearer
 * credential worth not re-typing, not something meant to be secret from the
 * browser it is stored in.
 */
import { useCallback, useEffect, useState } from "react";
import { API_BASE_URL } from "../api/client.js";

const ADMIN_TOKEN_KEY = "dielys.adminToken";

interface Stats {
  users: number;
  lists: number;
  items: number;
}

export function AdminPage() {
  const [token, setToken] = useState(() => localStorage.getItem(ADMIN_TOKEN_KEY) ?? "");
  const [stats, setStats] = useState<Stats | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const load = useCallback(async (withToken: string): Promise<void> => {
    if (withToken.trim() === "") return;
    setBusy(true);
    setProblem(null);
    try {
      const response = await fetch(`${API_BASE_URL}/admin/stats`, {
        headers: { Authorization: `Bearer ${withToken.trim()}` },
      });
      if (!response.ok) {
        setStats(null);
        setProblem(response.status === 401 ? "Wrong admin token." : "Something went wrong.");
        return;
      }
      setStats((await response.json()) as Stats);
      localStorage.setItem(ADMIN_TOKEN_KEY, withToken.trim());
    } catch {
      setStats(null);
      setProblem("Could not reach the server. Check your connection.");
    } finally {
      setBusy(false);
    }
  }, []);

  // A token already in localStorage from a previous visit loads straight
  // away — a new one the visitor types is submitted by the form below, not
  // this effect, so it reads localStorage directly rather than depending on
  // `token` state (which changes on every keystroke), and so only ever runs
  // once, on mount.
  useEffect(() => {
    const stored = localStorage.getItem(ADMIN_TOKEN_KEY);
    if (stored !== null && stored.trim() !== "") void load(stored);
  }, [load]);

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <h1>Admin</h1>
        </div>

        <div className="auth-stage">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              void load(token);
            }}
          >
            <input
              className="text-input"
              type="password"
              placeholder="Admin token"
              value={token}
              onChange={(event) => setToken(event.target.value)}
              disabled={busy}
            />
            {problem !== null && <p className="error-text">{problem}</p>}
            <button
              className="pill-button full-width"
              type="submit"
              style={{ marginTop: "1.25rem" }}
              disabled={busy || token.trim() === ""}
            >
              {busy ? "Loading…" : "Load stats"}
            </button>
          </form>

          {stats !== null && (
            <div className="settings-section">
              <StatRow label="Users" value={stats.users} />
              <StatRow label="Lists" value={stats.lists} />
              <StatRow label="Items" value={stats.items} />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function StatRow({ label, value }: { label: string; value: number }) {
  return (
    <p style={{ display: "flex", justifyContent: "space-between", margin: "0.5rem 0" }}>
      <span>{label}</span>
      <strong>{value.toLocaleString()}</strong>
    </p>
  );
}
