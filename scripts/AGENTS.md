# scripts/AGENTS.md

Utility and maintenance scripts. POSIX `sh` or Node only — CI runs on Linux
([A2](../docs/CODE_STANDARD.md#standard-a2)).

| Script            | What                                                        | Touches prod data |
|-------------------|--------------------------------------------------------------|--------------------|
| `create-user.ts`  | Creates one Dielys account in `UsersRoom`. See [L2](../docs/CODE_STANDARD.md#standard-l2) — there is no public registration endpoint, this is the only way an account gets created. | Yes |

A script that touches production data MUST print what it is about to do and prompt for
confirmation before doing it. Anything run by hand more than twice becomes a script here.
