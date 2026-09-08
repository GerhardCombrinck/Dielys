/**
 * Migration runner for DO SQLite (G1). No business rules here (D1) — it
 * decides nothing except which files have not run yet.
 *
 * The SQL itself stays in `migrations/*.sql` and arrives here as text modules
 * (see the `Text` rule in wrangler.jsonc), so the files on disk remain the
 * single source of truth and are not duplicated as string literals.
 *
 * Each DO class has its own directory and its own `_migrations` table. They
 * are separate schemas that happen to share a runner; a version number means
 * nothing across the two.
 */
import listV1 from "../../migrations/list/0001_initial.sql";
import listV2 from "../../migrations/list/0002_list_state.sql";
import usersV1 from "../../migrations/users/0001_initial.sql";

export interface Migration {
  version: number;
  name: string;
  sql: string;
}

/** Ordered, forward-only. Never edit an entry once merged — add a new one. */
export const LIST_MIGRATIONS: readonly Migration[] = [
  { version: 1, name: "0001_initial", sql: listV1 },
  { version: 2, name: "0002_list_state", sql: listV2 },
];

export const USERS_MIGRATIONS: readonly Migration[] = [
  { version: 1, name: "0001_initial", sql: usersV1 },
];

/**
 * Applies every migration newer than the recorded version and returns the
 * version now in effect.
 *
 * The caller runs this inside `blockConcurrencyWhile` (D3) so no request can
 * observe a half-migrated schema, and inside one transaction so a migration
 * that throws halfway leaves the DO on the old version rather than on a
 * shape no file describes.
 */
export function applyPendingMigrations(sql: SqlStorage, migrations: readonly Migration[]): number {
  // Bootstrap: on a fresh DO this table does not exist yet, and 0001 cannot
  // create it before we are able to ask which version is applied.
  sql.exec("CREATE TABLE IF NOT EXISTS _migrations (version INTEGER PRIMARY KEY)");

  const current = currentVersion(sql);

  for (const migration of migrations) {
    if (migration.version <= current) continue;
    sql.exec(migration.sql);
  }

  return currentVersion(sql);
}

export function currentVersion(sql: SqlStorage): number {
  const row = sql.exec("SELECT MAX(version) AS version FROM _migrations").one();
  return typeof row.version === "number" ? row.version : 0;
}
