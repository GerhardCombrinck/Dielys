# Handoff: guest mode (#67)

Issue: https://github.com/GerhardCombrinck/Dielys/issues/67 (`gh issue view 67`) — "email usage". Let people
use Die Lys without registering, everything on the phone, and only ask for an email when they want
sync or sharing.

**Status:** scoped, not started. **Target:** 0.5.0, built *after* v0.4.0 (the first Play release)
ships. Do not start this until v0.4.0 is tagged — see "Before you start".

Delete this file when the work is merged (its content should by then live in an ADR and the code).

## Decisions already made (by the product owner — don't relitigate)

1. **First launch no longer requires an account.** A person can start using lists straight away.
2. **Sharing as a guest triggers sign-in.** Tapping share (or members, or opening an invite link)
   as a guest nudges the person to sign in, explaining that sharing needs an account, and **resumes
   the action they were doing** once signed in — the share dialog for that list opens, the invite
   is offered.
3. **Signing in with guest data merges.** The phone's lists are uploaded into the account and the
   account's existing lists come down alongside them. No prompt, nothing lost.
4. **Signing out clears the phone.** Local lists, tasks and outbox are wiped, returning the app to
   an empty guest. If there are unsent edits (outbox pending or dead rows), warn first. This
   replaces today's behaviour, where sign-out keeps the replica and outbox
   (`data/SessionRepository.kt` `signOut()` doc comment) — which also means account B can inherit
   account A's lists and queued edits on a shared phone. Related: #66.

## Why this is mostly Android work

The app is already offline-first, which does most of the heavy lifting:

- List IDs are client-generated UUIDv7s; `DielysRepository.createList` queues a **claim** plus the
  first patch in the outbox (F5.1, F5.7). A list made while a guest becomes the account's list
  when the outbox drains after sign-in.
- With no session, `SyncEngine` maps 401 to `SyncOutcome.SessionExpired` and `SyncWorker` returns
  `Result.failure()` without retrying; a login re-enqueues sync. `SyncSockets` only opens sockets
  when `signedIn`.
- Server and protocol should need **no changes**. Confirm there is no per-request or per-device
  cap that a large first upload would hit (a quick grep of `server/src` found no batch limit, but
  check the push path and rate limits in `server/src/auth/ratelimit.ts`).

Read `AGENTS.md`, `docs/SYNC.md`, `android/app/src/main/java/za/co/dielys/data/sync/AGENTS.md`
and ADRs 0004–0008 in `docs/adr/` first.

## Work items

1. **App gate** — `ui/DielysApp.kt:38` renders `AuthScreen` whenever there is no session. Replace
   with a guest/signed-in mode. First launch shows a small welcome with *Start without an account*
   and *Sign in* (reinstalling users must still find sign-in); remember the choice in
   `data/local/SessionStore.kt`.
2. **Sign-in as a destination** — `AuthScreen` (and the email/code flow in
   `ui/auth/SessionViewModel.kt`) becomes something you open from Settings, the header avatar, and
   gated actions, with back/cancel. Magic-link deep link and code entry must still work from there.
3. **Sign-in nudge with resume** (decision 2) — gate these for guests:
   - share / invite: `ListsViewModel.invite(list)` and `ui/lists/ShareDialogs.kt`
   - members: `ui/lists/MembersDialog.kt` / `MembersViewModel.kt`
   - invite links: `data/PendingInvite` already holds a tapped link and `ListsViewModel.invitation`
     offers it — verify it survives the sign-in round trip (including the magic link bouncing out
     to the mail app and back) and is offered afterwards.
   Remember the intended action (e.g. "share list X") across the sign-in flow, including process
   death, and replay it once signed in.
4. **Nothing networked for guests** — no `SyncScheduler.requestSync()` work, no sockets, no FCM
   token registration (`data/push/PushHandler`, `DielysMessagingService.onNewToken`) until signed
   in; register the token at sign-in.
5. **First upload** (decision 3) — a long-lived guest can queue thousands of outbox rows. Prefer
   rebuilding the outbox from current state at sign-in (one claim + one full patch per live list
   and task; drop rows for entities that were created and deleted without ever syncing) over
   replaying history. Keep F5.2 idempotency keys and the F5.7 single-transaction rule. If you
   decide replay is fine, write down why in the ADR.
6. **Sign-out clears** (decision 4) — sign-out wipes Room (`db.clearAllTables()`, as
   `data/AccountRepository.kt` already does for account deletion) and the session, after a confirm
   that warns when `observePendingCount()` / `observeStuckCount()` are non-zero. Cancel pending
   sync work and close sockets.
7. **Settings for guests** — `ui/settings/SettingsScreen.kt`: account section reads "Not signed
   in — sign in to sync and share" with a sign-in button; *Delete account* becomes *Delete all
   data* (local wipe only, with confirm). Language and new-item placement settings stay available.
8. **Strings** in all 11 locales (`res/values*/strings.xml`). en and af written properly; the other
   nine are machine-assisted drafts, as the existing ones are (see the DRAFT header comments). Escape
   apostrophes (`\'`) — an unescaped one in `values-ts` has broken aapt before.
9. **Tests** (Robolectric/unit, same fakes as today: `FakeAuthApi`, `FakeSyncApi`, `SessionStack`):
   - guest creates/edits/deletes lists and tasks; no network calls made
   - guest signs in → lists uploaded and owned; account's server lists merge in
   - guest taps share → sign-in → share dialog for that list opens
   - guest opens invite link → sign-in → invitation offered → join works
   - sign-out with pending edits warns; confirmed sign-out leaves an empty guest
   - delete-all-data as guest
10. **Docs**
    - New `docs/adr/0009-guest-mode.md` (the decisions above, the upload strategy, sign-out wipe).
    - `server/public/privacy.html`: lists stay on the phone and nothing is collected until you sign
      in; update "last updated".
    - `docs/play/README.md`: Data safety — email, user-generated content and device IDs become
      **Optional** collection; App access stays "restricted" (sharing needs sign-in) with the same
      review account; update the store listing ("No account needed to start") and add 0.5.0 release
      notes in en-GB and af.
    - `android/app/build.gradle.kts`: `versionName = "0.5.0"`.

Rough size: B (account deletion) and D (sign-in code) together; nearly all Android.

## Before you start

- v0.4.0 must be tagged and released first. At handoff time: the version bump and
  `docs/play/README.md` were committed locally (`83ea582`) but **not pushed**, and the
  `GOOGLE_SERVICES_JSON_BASE64` secret was still missing from the GitHub `prod` environment (the
  release job fails without it). Check `git log origin/main` and `gh release list` to see where
  that got to.
- Work on a branch; the user pushes per feature, not per commit.
- Run `scripts/verify.sh` green locally before pushing (protocol, server, Android). Toolchain is in
  `~/.dielys-toolchain`.
- Android lint gotchas seen recently: detekt `TooManyFunctions` (11 per interface — split
  interfaces rather than suppress), ktlint chain wrapping (`./gradlew ktlintFormat`).
- Test on the phone over Wi-Fi ADB, not USB. Debug package is `za.co.dielys.debug`.

## Open questions to confirm with the user if they come up

- Welcome screen copy and whether it shows once or until an explicit choice.
- Whether the header avatar shows a "sign in" state for guests.
- Whether sign-out should offer "try to send unsent edits first" (wait for the outbox to drain)
  or only warn.

## Suggested skills

- `grill-with-docs` — stress-test the plan against `docs/adr/` and `docs/SYNC.md` before coding,
  and draft ADR 0009 as decisions settle.
- `run` — launch the debug build and walk the guest → share → sign-in → resume flow on a device.
- `code-review` — review the branch before pushing (sync and outbox rules are where bugs hide).
- `simplify` — after it works, trim the mode/resume plumbing.
