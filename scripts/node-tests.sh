#!/bin/sh
# Runs one node component's test suite, bounded and retried.
#
# POSIX sh (A2) — works in Git Bash on Windows and on a Linux runner.
#
#   scripts/node-tests.sh server
#   DIELYS_TEST_TIMEOUT=300 scripts/node-tests.sh server
#
# Why this is not just `npm test`: the Workers test pool starts one workerd per
# test file and sometimes starts one it never stops
# (cloudflare/workers-sdk#15498). Every test passes in seconds, then the run
# holds that child process open and waits forever, printing nothing — a green
# run and a hung one produce identical test output. `fileParallelism: false` in
# server/vitest.config.ts takes that from 42% of runs to about 7%; this is the
# net under the remainder. `scripts/verify.sh` and `ci.yml` both go through
# here, so neither can drift from the other.

set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ $# -ne 1 ]; then
  printf 'usage: %s <component>   (e.g. server)\n' "$0" >&2
  exit 2
fi

name=$1
dir="$REPO_ROOT/$name"
if [ ! -d "$dir" ]; then
  printf 'no such component: %s\n' "$name" >&2
  exit 2
fi

# 45s is a little over twice the slowest honest run measured here (server, the
# big one, is 19-20s warm and the whole point of the bound is to be well clear
# of a cold one). A hung attempt costs exactly this much, so it is kept tight;
# ci.yml raises it, because a slow runner flaking red is worse than waiting.
TEST_TIMEOUT="${DIELYS_TEST_TIMEOUT:-45}"
TEST_ATTEMPTS="${DIELYS_TEST_ATTEMPTS:-3}"

YELLOW=''
RED=''
RESET=''
if [ -t 1 ]; then
  YELLOW=$(printf '\033[33m')
  RED=$(printf '\033[31m')
  RESET=$(printf '\033[0m')
fi

# Only ever kills workerd processes one of our own hung attempts started. A
# `wrangler dev` in another window is somebody's running server, not litter.
workerd_pids() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      powershell.exe -NoProfile -Command \
        'Get-Process workerd -ErrorAction SilentlyContinue | ForEach-Object { $_.Id }' \
        2>/dev/null | tr -d '\r'
      ;;
    *)
      pgrep -x workerd 2>/dev/null || true
      ;;
  esac
}

kill_pid() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      powershell.exe -NoProfile -Command \
        "Stop-Process -Id $1 -Force -ErrorAction SilentlyContinue" >/dev/null 2>&1
      ;;
    *)
      kill -9 "$1" 2>/dev/null || true
      ;;
  esac
}

# Kills every workerd that appeared since $1, a space-separated PID list.
reap_new_workerd() {
  before=" $1 "
  for pid in $(workerd_pids); do
    case "$before" in
      *" $pid "*) ;;
      *) kill_pid "$pid" ;;
    esac
  done
}

# `timeout` is coreutils: present in Git Bash and on the runner. If it ever is
# not, run unbounded rather than quietly skipping the tests.
bounded() {
  if command -v timeout >/dev/null 2>&1; then
    timeout -k 10 "$TEST_TIMEOUT" "$@"
  else
    "$@"
  fi
}

before=$(workerd_pids | tr '\n' ' ')
attempt=1
while :; do
  started=$(date +%s)
  if (cd "$dir" && bounded npm test --silent); then
    status=0
  else
    status=$?
  fi
  elapsed=$(($(date +%s) - started))
  # Vitest traps the SIGTERM timeout sends, prints a summary for the tests that
  # had already passed, and exits 0 — so the exit status alone cannot tell a
  # hung run from a good one. Anything that reached the limit is a hang,
  # whatever it exited with. 124 is timeout's own status, 137 what it reports
  # once -k had to escalate to SIGKILL.
  if [ "$status" -ne 124 ] && [ "$status" -ne 137 ] &&
    [ "$elapsed" -lt "$TEST_TIMEOUT" ]; then
    exit "$status"
  fi
  reap_new_workerd "$before"
  if [ "$attempt" -ge "$TEST_ATTEMPTS" ]; then
    printf '%sFAIL%s %s tests hung %s times at %ss. Not a test failure — see\n' \
      "$RED" "$RESET" "$name" "$TEST_ATTEMPTS" "$TEST_TIMEOUT"
    printf '     cloudflare/workers-sdk#15498 and scripts/AGENTS.md.\n'
    exit 1
  fi
  printf '%swarn%s %s tests hung at %ss (workers-sdk#15498), attempt %s of %s.\n' \
    "$YELLOW" "$RESET" "$name" "$TEST_TIMEOUT" "$((attempt + 1))" "$TEST_ATTEMPTS"
  attempt=$((attempt + 1))
done
