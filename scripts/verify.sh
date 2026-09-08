#!/bin/sh
# Runs locally exactly what .github/workflows/ci.yml runs, so CI is a sense
# check on a clean machine rather than the place bugs are found. Pushing to
# find out whether something compiles burns runner minutes and takes minutes
# per round trip; this takes seconds for the TypeScript half.
#
# POSIX sh (A2) — works in Git Bash on Windows and on a Linux runner.
#
#   scripts/verify.sh              # everything
#   scripts/verify.sh protocol     # one component
#   scripts/verify.sh server android
#   FAST=1 scripts/verify.sh       # skip android (the slow one)
#   CLEAN=1 scripts/verify.sh      # reinstall node deps from the lockfile
#
# Toolchain: JAVA_HOME/ANDROID_HOME are used when already set, otherwise the
# script falls back to ~/.dielys-toolchain (see scripts/AGENTS.md).

set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOLCHAIN="${DIELYS_TOOLCHAIN:-$HOME/.dielys-toolchain}"

RED=''
GREEN=''
YELLOW=''
BOLD=''
RESET=''
if [ -t 1 ]; then
  RED=$(printf '\033[31m')
  GREEN=$(printf '\033[32m')
  YELLOW=$(printf '\033[33m')
  BOLD=$(printf '\033[1m')
  RESET=$(printf '\033[0m')
fi

FAILURES=''
SKIPPED=''

step() {
  printf '%s>>> %s%s\n' "$BOLD" "$1" "$RESET"
}

fail() {
  FAILURES="$FAILURES $1"
  printf '%sFAIL%s %s\n' "$RED" "$RESET" "$1"
}

# Runs a command, records the component as failed if it exits non-zero, and
# keeps going — one run should report every broken thing, not just the first.
run() {
  label=$1
  shift
  step "$label"
  if "$@"; then
    printf '%sok%s   %s\n\n' "$GREEN" "$RESET" "$label"
  else
    fail "$label"
    printf '\n'
    return 1
  fi
}

# npm ci on every run costs more than it catches. Reinstall when the lockfile
# is newer than what was installed from it, or when CLEAN=1 asks for the full
# CI behaviour.
ensure_node_deps() {
  dir=$1
  if [ "${CLEAN:-0}" = "1" ] || [ ! -d "$dir/node_modules" ] ||
    [ "$dir/package-lock.json" -nt "$dir/node_modules/.package-lock.json" ]; then
    step "$(basename "$dir"): npm ci"
    (cd "$dir" && npm ci --no-audit --no-fund)
  fi
}

verify_node_component() {
  name=$1
  dir="$REPO_ROOT/$name"
  ensure_node_deps "$dir"
  ok=0
  # Same three commands as the workflow, in the same order.
  (cd "$dir" && npx biome ci .) || ok=1
  (cd "$dir" && npx tsc --noEmit) || ok=1
  (cd "$dir" && npm test --silent) || ok=1
  return $ok
}

resolve_java_home() {
  # An existing JAVA_HOME wins only if it is actually 21 — AGP 9 fails with
  # "invalid source release: 21" on anything older, which is not an error that
  # points at the real cause.
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    if "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
      printf '%s' "$JAVA_HOME"
      return 0
    fi
  fi
  if [ -x "$TOOLCHAIN/jdk21/bin/java" ]; then
    printf '%s' "$TOOLCHAIN/jdk21"
    return 0
  fi
  return 1
}

resolve_android_home() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then
    printf '%s' "$ANDROID_HOME"
    return 0
  fi
  if [ -d "$TOOLCHAIN/android-sdk/platforms" ]; then
    printf '%s' "$TOOLCHAIN/android-sdk"
    return 0
  fi
  return 1
}

verify_android() {
  if ! java_home=$(resolve_java_home); then
    printf '%sskip%s android: no JDK 21 (set JAVA_HOME or install to %s/jdk21)\n\n' \
      "$YELLOW" "$RESET" "$TOOLCHAIN"
    SKIPPED="$SKIPPED android"
    return 0
  fi
  if ! android_home=$(resolve_android_home); then
    printf '%sskip%s android: no SDK (set ANDROID_HOME or install to %s/android-sdk)\n\n' \
      "$YELLOW" "$RESET" "$TOOLCHAIN"
    SKIPPED="$SKIPPED android"
    return 0
  fi

  JAVA_HOME="$java_home"
  ANDROID_HOME="$android_home"
  export JAVA_HOME ANDROID_HOME
  (cd "$REPO_ROOT/android" && ./gradlew ktlintCheck detekt testDebugUnitTest)
}

TARGETS=$*
if [ -z "$TARGETS" ]; then
  if [ "${FAST:-0}" = "1" ]; then
    TARGETS="protocol server"
  else
    TARGETS="protocol server android"
  fi
fi

START=$(date +%s)
for target in $TARGETS; do
  case "$target" in
    protocol | server)
      run "$target" verify_node_component "$target" || true
      ;;
    android)
      run "android" verify_android || true
      ;;
    *)
      printf '%sunknown target:%s %s (expected protocol, server or android)\n' \
        "$RED" "$RESET" "$target"
      exit 2
      ;;
  esac
done
ELAPSED=$(($(date +%s) - START))

printf '%s--- %ss ---%s\n' "$BOLD" "$ELAPSED" "$RESET"
[ -n "$SKIPPED" ] && printf '%sskipped:%s%s\n' "$YELLOW" "$RESET" "$SKIPPED"
if [ -n "$FAILURES" ]; then
  printf '%sfailed:%s%s\n' "$RED" "$RESET" "$FAILURES"
  exit 1
fi
printf '%sall green%s\n' "$GREEN" "$RESET"
