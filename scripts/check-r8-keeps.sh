#!/bin/sh
# Fails if R8 dropped something the release build only reaches by reflection.
#
# No unit test runs against a shrunk build, so a missing keep rule compiles,
# passes every test, and breaks at runtime. 0.5.6 shipped exactly that:
# WorkManager's OverwritingInputMerger lost its no-arg constructor under R8
# full mode, every sync job failed before doWork(), and the outbox never
# drained. This reads R8's seeds.txt (everything a keep rule matched) after
# `./gradlew assembleRelease` and checks the entries below are still in it.
#
# Add a line whenever something new is created by name: a Worker, an
# InputMerger, a serializer looked up at runtime.
#
#   scripts/check-r8-keeps.sh [path/to/seeds.txt]
#
# POSIX sh (A2).

set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SEEDS="${1:-$REPO_ROOT/android/app/build/outputs/mapping/release/seeds.txt}"

if [ ! -f "$SEEDS" ]; then
  printf 'no seeds.txt at %s — run ./gradlew assembleRelease first\n' "$SEEDS" >&2
  exit 2
fi

missing=0
# One exact seeds.txt line per entry: `Class` for a kept class, or
# `Class: member` for a kept member.
while IFS= read -r entry; do
  case "$entry" in '' | '#'*) continue ;; esac
  if ! grep -qxF -- "$entry" "$SEEDS"; then
    printf 'R8 dropped: %s\n' "$entry" >&2
    missing=1
  fi
done <<'EOF'
# WorkerWrapper instantiates the merger by name before every job.
androidx.work.OverwritingInputMerger: OverwritingInputMerger()
androidx.work.ArrayCreatingInputMerger: ArrayCreatingInputMerger()
# WorkSpec stores the worker's class name; HiltWorkerFactory looks it up by it.
za.co.dielys.data.sync.SyncWorker
za.co.dielys.data.sync.SyncWorker: SyncWorker(android.content.Context,androidx.work.WorkerParameters,za.co.dielys.data.sync.SyncEngine)
# data/remote's polymorphic wire types (proguard-rules.pro).
za.co.dielys.data.remote.ChangeEnvelope$Companion
za.co.dielys.data.remote.Mutation$Companion
za.co.dielys.data.remote.ServerMessage$Companion
za.co.dielys.data.remote.TaskMutation$$serializer: TaskMutation$$serializer()
za.co.dielys.data.remote.ListMutation$$serializer: ListMutation$$serializer()
EOF

if [ "$missing" -ne 0 ]; then
  printf 'Add a keep rule to android/app/proguard-rules.pro (see android/AGENTS.md "R8").\n' >&2
  exit 1
fi
printf 'R8 keeps ok\n'
