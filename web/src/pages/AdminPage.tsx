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
 *
 * Each account can be deleted from here — the same erasure as the app's own
 * "delete account" (ADR 0007). It exists for the test accounts `smoke.sh` and
 * `push-probe.sh` used to leave behind, which have no other way out: random
 * passwords nobody kept, and `@dielys.test` addresses that get no mail.
 */
import { useCallback, useEffect, useState } from "react";
import { API_BASE_URL } from "../api/client.js";

const ADMIN_TOKEN_KEY = "dielys.adminToken";

interface Stats {
  users: number;
  lists: number;
  items: number;
  accounts: Account[];
}

interface Account {
  userId: string;
  /** Local part only — the server never sends the domain (#84). */
  email: string;
}

export function AdminPage() {
  const [token, setToken] = useState(() => localStorage.getItem(ADMIN_TOKEN_KEY) ?? "");
  const [stats, setStats] = useState<Stats | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);

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

  const remove = useCallback(
    async (account: Account): Promise<void> => {
      if (
        !window.confirm(
          `Delete ${account.email} and every list only they are on? This can't be undone.`,
        )
      ) {
        return;
      }
      setDeleting(account.userId);
      setProblem(null);
      try {
        const response = await fetch(
          `${API_BASE_URL}/admin/users/${encodeURIComponent(account.userId)}`,
          { method: "DELETE", headers: { Authorization: `Bearer ${token.trim()}` } },
        );
        if (!response.ok) {
          setProblem(
            response.status === 401 ? "Wrong admin token." : "Could not delete that account.",
          );
          return;
        }
        await load(token);
      } catch {
        setProblem("Could not reach the server. Check your connection.");
      } finally {
        setDeleting(null);
      }
    },
    [load, token],
  );

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
            <>
              <div className="settings-section">
                <StatRow label="Users" value={stats.users} />
                <StatRow label="Lists" value={stats.lists} />
                <StatRow label="Items" value={stats.items} />
              </div>

              <div className="settings-section">
                <h2>Who signed up</h2>
                <ul style={{ margin: 0, padding: 0, listStyle: "none" }}>
                  {stats.accounts.map((account) => (
                    <li
                      key={account.userId}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "space-between",
                        gap: "0.5rem",
                        padding: "0.125rem 0",
                      }}
                    >
                      <span style={{ overflowWrap: "anywhere" }}>{account.email}</span>
                      <button
                        className="text-button menu-danger"
                        type="button"
                        onClick={() => void remove(account)}
                        disabled={busy || deleting !== null}
                      >
                        {deleting === account.userId ? "Deleting…" : "Delete"}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            </>
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
