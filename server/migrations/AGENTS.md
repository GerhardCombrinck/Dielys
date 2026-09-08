# server/migrations/AGENTS.md

DO SQLite migrations. See CODE_STANDARD.md G1.

```
migrations/
├── list/     ← ListRoom's schema
└── users/    ← UsersRoom's schema
```

**Each DO class has its own directory and its own `_migrations` table.** They are separate
schemas that share a runner (`src/storage/migrations.ts`); a version number means nothing
across the two. `list/0002` and `users/0002` are unrelated.

- Four-digit zero-padded sequence, underscore, snake_case description:
  `0002_add_task_notes.sql`.
- Forward-only. A merged migration is never edited — fix forward with a new file.
- Each DO tracks its applied version in its own `_migrations` table and applies pending
  migrations inside `blockConcurrencyWhile` on first access after deploy, in one transaction.
- Files are imported as **text modules** (the `Text` rule in `wrangler.jsonc`), so the `.sql`
  file stays the single source of truth instead of being copied into a TypeScript literal.
  A new file must be added to `LIST_MIGRATIONS` or `USERS_MIGRATIONS` or it never runs.
- A migration that drops or renames a column ships across two releases: stop writing the old
  column, deploy, then drop it in a later migration — because DOs migrate lazily, both shapes
  can coexist in production for as long as a list goes untouched, which can be months.
- A destructive migration states in the PR body what data is lost.

The runner gates on the recorded version, so a statement that is not itself idempotent
(`ALTER TABLE ADD COLUMN`, `INSERT INTO _migrations`) is fine — it runs exactly once. Keep
`CREATE TABLE IF NOT EXISTS` anyway; it costs nothing and makes a file readable on its own.
