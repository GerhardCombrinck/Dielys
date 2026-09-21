#!/bin/sh
# Builds web/ and assembles server/public/ from it plus the static pages
# Google Play links to (server/public-static/ — privacy policy, account
# deletion, untouched by this script). server/public/ is gitignored and
# regenerated here every time; server/wrangler.jsonc serves it as the
# Worker's static assets, which is how the web client and those Play pages
# both end up on dielys.com.
#
# POSIX sh (A2).
#
#   scripts/build-web-public.sh

set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ "${CLEAN:-0}" = "1" ] || [ ! -d "$REPO_ROOT/web/node_modules" ] ||
  [ "$REPO_ROOT/web/package-lock.json" -nt "$REPO_ROOT/web/node_modules/.package-lock.json" ]; then
  (cd "$REPO_ROOT/web" && npm ci --no-audit --no-fund)
fi

# Every real deployment serves web/ and the API from the same origin (one
# Worker, one domain) — an empty base makes web/src/api/client.ts build
# relative request URLs instead of pointing at local dev's localhost:8787.
: "${VITE_API_BASE_URL:=}"
export VITE_API_BASE_URL

# Shown small at the foot of Settings, so a bug report can name the build it
# came from. Derived, never hand-bumped: the release workflow passes the tag
# it is building, and a local or dev build falls back to `git describe` (or
# "dev" in a checkout with no tags, e.g. CI's shallow clone).
: "${VITE_APP_VERSION:=$(git -C "$REPO_ROOT" describe --tags --match 'web-v*' --dirty 2>/dev/null || true)}"
# No matching tag (a shallow CI clone has none): fall back to the commit, so a
# dev build still names itself. Neither command exists outside a checkout.
: "${VITE_APP_VERSION:=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || true)}"
: "${VITE_APP_VERSION:=dev}"
# Strip the tag prefix last, so it applies to a value passed in by the release
# workflow (which passes the raw tag name) as well as to `git describe`'s.
VITE_APP_VERSION=${VITE_APP_VERSION#web-v}
export VITE_APP_VERSION

(cd "$REPO_ROOT/web" && npm run build)

rm -rf "$REPO_ROOT/server/public"
cp -r "$REPO_ROOT/server/public-static" "$REPO_ROOT/server/public"
cp -r "$REPO_ROOT/web/dist/." "$REPO_ROOT/server/public/"
