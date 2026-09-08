# server/migrations/AGENTS.md

DO SQLite migrations for `ListRoom`. See CODE_STANDARD.md G1.

- Four-digit zero-padded sequence, underscore, snake_case description:
  `0002_add_task_notes.sql`.
- Forward-only. A merged migration is never edited — fix forward with a new file.
- Each DO tracks its applied version in its own `_migrations` table (created in
  `0001_initial.sql`) and applies pending migrations inside `blockConcurrencyWhile` on first
  access after deploy.
- A migration that drops or renames a column ships across two releases: stop writing the old
  column, deploy, then drop it in a later migration — because DOs migrate lazily, both shapes
  can coexist in production for as long as a list goes untouched, which can be months.
- A destructive migration states in the PR body what data is lost.

`UsersRoom` shares this same pattern with its own `_migrations` table, in its own migration
files once that schema exists.
