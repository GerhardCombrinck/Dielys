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

(cd "$REPO_ROOT/web" && npm run build)

rm -rf "$REPO_ROOT/server/public"
cp -r "$REPO_ROOT/server/public-static" "$REPO_ROOT/server/public"
cp -r "$REPO_ROOT/web/dist/." "$REPO_ROOT/server/public/"
