/**
 * `dielys.com/admin` (#84) — total accounts, lists (open/archived) and items
 * (open/done), overall and per account, for the one
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

interface Counts {
  openLists: number;
  archivedLists: number;
  openItems: number;
  doneItems: number;
}

interface Stats extends Counts {
  users: number;
  accounts: Account[];
}

interface Account extends Counts {
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
  const [sort, setSort] = useState<{ key: SortKey | null; dir: "asc" | "desc" }>({
    key: null,
    dir: "desc",
  });

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

  const rows = sortAccounts(stats?.accounts ?? [], sort);

  function toggleSort(key: SortKey): void {
    setSort((current) =>
      current.key === key
        ? { key, dir: current.dir === "asc" ? "desc" : "asc" }
        : { key, dir: key === "email" ? "asc" : "desc" },
    );
  }

  return (
    <div className="admin-page">
      <header className="admin-header">
        <h1>Admin</h1>
        <form
          className="admin-token-form"
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
          <button className="pill-button" type="submit" disabled={busy || token.trim() === ""}>
            {busy ? "Loading…" : "Load stats"}
          </button>
        </form>
      </header>
      {problem !== null && <p className="error-text">{problem}</p>}

      {stats !== null && (
        <>
          <div className="admin-summary">
            <SummaryTile label="Users" value={stats.users} />
            <SummaryTile label="Open lists" value={stats.openLists} />
            <SummaryTile label="Archived lists" value={stats.archivedLists} />
            <SummaryTile label="Open items" value={stats.openItems} />
            <SummaryTile label="Done items" value={stats.doneItems} />
          </div>

          <table className="admin-table">
            <thead>
              <tr>
                {COLUMNS.map((column) => (
                  <th
                    key={column.key}
                    className={column.key === "email" ? undefined : "num"}
                    aria-sort={
                      sort.key === column.key
                        ? sort.dir === "asc"
                          ? "ascending"
                          : "descending"
                        : "none"
                    }
                  >
                    <button type="button" onClick={() => toggleSort(column.key)}>
                      {column.label}
                      {sort.key === column.key && (sort.dir === "asc" ? " ▲" : " ▼")}
                    </button>
                  </th>
                ))}
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {rows.map((account) => (
                <tr key={account.userId}>
                  <td>{account.email}</td>
                  <td className="num">{account.openLists}</td>
                  <td className="num">{account.archivedLists}</td>
                  <td className="num">{account.openItems}</td>
                  <td className="num">{account.doneItems}</td>
                  <td className="actions">
                    <button
                      className="text-button menu-danger"
                      type="button"
                      onClick={() => void remove(account)}
                      disabled={busy || deleting !== null}
                    >
                      {deleting === account.userId ? "Deleting…" : "Delete"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="admin-note">
            Newest accounts first until you sort. A list shared between accounts counts for each
            member, so the rows can add up to more than the totals above.
          </p>
        </>
      )}
    </div>
  );
}

type SortKey = "email" | "openLists" | "archivedLists" | "openItems" | "doneItems";

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: "email", label: "Account" },
  { key: "openLists", label: "Open lists" },
  { key: "archivedLists", label: "Archived lists" },
  { key: "openItems", label: "Open items" },
  { key: "doneItems", label: "Done items" },
];

/** `key: null` is the server's order, newest account first. */
function sortAccounts(
  accounts: Account[],
  sort: { key: SortKey | null; dir: "asc" | "desc" },
): Account[] {
  const { key, dir } = sort;
  if (key === null) return accounts;
  const sign = dir === "asc" ? 1 : -1;
  return [...accounts].sort((a, b) =>
    key === "email" ? sign * a.email.localeCompare(b.email) : sign * (a[key] - b[key]),
  );
}

function SummaryTile({ label, value }: { label: string; value: number }) {
  return (
    <div className="admin-tile">
      <span>{label}</span>
      <strong>{value.toLocaleString()}</strong>
    </div>
  );
}
