#!/bin/sh
# End-to-end smoke test against a running Dielys server: local, dev or prod.
#
# Creates two disposable accounts, then drives the whole contract — registration,
# login, list claim, mutation, idempotent retry, catch-up, invite, accept,
# push-token registration, refresh rotation and replay detection — asserting the
# response at each step.
#
# POSIX sh (A2). Needs curl and node.
#
#   scripts/smoke.sh http://127.0.0.1:8787
#   scripts/smoke.sh https://dielys-dev.dielys.workers.dev
#
# ONCE PER HOUR against a deployed environment. Registration is rate limited to
# three per client per hour (ADR 0004) and a run spends all three, so a second
# run inside the hour fails on the first registration and means nothing. Against
# a local `wrangler dev` the counter lives in a throwaway DO, so restarting it
# clears them.
#
# No admin token needed since registration opened; the one check that still
# concerns `/admin/users` sends no token and expects to be refused. Nothing here
# should ever be given a real secret as an argument — argv lands in shell history
# and in the process list.

set -eu

BASE="${1:-${DIELYS_URL:-http://127.0.0.1:8787}}"
JSON='content-type: application/json'

GREEN=''
RED=''
DIM=''
RESET=''
if [ -t 1 ]; then
  GREEN=$(printf '\033[32m')
  RED=$(printf '\033[31m')
  DIM=$(printf '\033[2m')
  RESET=$(printf '\033[0m')
fi

FAILED=0
STEP=0

# Prints the body only when the status disagrees, so a passing run stays
# readable and a failing one says enough to act on.
check() {
  STEP=$((STEP + 1))
  if [ "$3" = "$2" ]; then
    printf '%s ok %s%2d. %s\n' "$GREEN" "$RESET" "$STEP" "$1"
  else
    FAILED=$((FAILED + 1))
    printf '%sFAIL%s %2d. %s %s(expected %s, got %s)%s\n' \
      "$RED" "$RESET" "$STEP" "$1" "$DIM" "$2" "$3" "$RESET"
    printf '     %s%s%s\n' "$DIM" "$4" "$RESET"
  fi
}

# An assertion about the body rather than the status.
expect() {
  if [ "$2" != "$3" ]; then
    FAILED=$((FAILED + 1))
    printf '%sFAIL%s     %s: expected %s, got %s\n' "$RED" "$RESET" "$1" "$3" "$2"
  fi
}

req() {
  curl -s -w '\n%{http_code}' "$@"
}
body_of() { printf '%s' "$1" | sed '$d'; }
code_of() { printf '%s' "$1" | tail -n1; }
field() {
  printf '%s' "$1" | node -e '
    let raw = "";
    process.stdin.on("data", (c) => { raw += c; });
    process.stdin.on("end", () => {
      try { console.log(String(eval("(JSON.parse(raw))" + process.argv[1]))); }
      catch { console.log(""); }
    });
  ' "$2"
}
uuid() { node -pe 'crypto.randomUUID()'; }

SUFFIX=$(uuid)
EMAIL_A="smoke-a-$SUFFIX@dielys.test"
EMAIL_B="smoke-b-$SUFFIX@dielys.test"
# Disposable and never reused. Long enough to clear MIN_PASSWORD_LENGTH.
PASS=$(node -pe 'require("crypto").randomBytes(24).toString("base64url")')
LIST=$(uuid)
TASK=$(uuid)
KEY=$(uuid)

printf '%ssmoke: %s%s\n\n' "$DIM" "$BASE" "$RESET"

R=$(req "$BASE/health")
check "health" 200 "$(code_of "$R")" "$(body_of "$R")"

# L2 / ADR 0004: registration is public, and it answers with a session rather
# than making a brand new account log in again for what is one intent.
R=$(req -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS\",\"deviceId\":\"smoke-a\"}")
check "register (owner)" 201 "$(code_of "$R")" "$(body_of "$R")"
expect "registering signs you in" "$(field "$(body_of "$R")" '.accessToken.length > 0')" "true"

R=$(req -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASS\",\"deviceId\":\"smoke-b\"}")
check "register (partner)" 201 "$(code_of "$R")" "$(body_of "$R")"

# A knowingly accepted enumeration oracle, bounded by the limiter rather than
# hidden behind a lie the client would then have to keep telling (ADR 0004).
R=$(req -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS\",\"deviceId\":\"smoke-a\"}")
check "registering a taken email says so" 409 "$(code_of "$R")" "$(body_of "$R")"
expect "taken email error code" "$(field "$(body_of "$R")" '.code')" "already-exists"

# Refused before the limiter is consumed, so a fat-fingered password costs
# nothing but the round trip.
R=$(req -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"email\":\"short-$SUFFIX@dielys.test\",\"password\":\"short\",\"deviceId\":\"smoke-a\"}")
check "a short password is refused" 400 "$(code_of "$R")" "$(body_of "$R")"

# The admin route stays: it is how the first account on a fresh deployment gets
# made. No token is sent here on purpose — this asserts it is still guarded
# without this script ever needing to hold one.
R=$(req -X POST "$BASE/admin/users" -H "$JSON" \
  -d "{\"email\":\"nope-$SUFFIX@dielys.test\",\"password\":\"$PASS\"}")
check "the admin route still needs its token" 401 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/auth/login" -H "$JSON" \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASS\",\"deviceId\":\"smoke-a\"}")
check "login (owner)" 200 "$(code_of "$R")" "$(body_of "$R")"
TOKEN_A=$(field "$(body_of "$R")" '.accessToken')
REFRESH_A=$(field "$(body_of "$R")" '.refreshToken')

R=$(req -X POST "$BASE/auth/login" -H "$JSON" \
  -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASS\",\"deviceId\":\"smoke-b\"}")
check "login (partner)" 200 "$(code_of "$R")" "$(body_of "$R")"
TOKEN_B=$(field "$(body_of "$R")" '.accessToken')

R=$(req -X POST "$BASE/auth/login" -H "$JSON" \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"definitely-the-wrong-one\",\"deviceId\":\"smoke-a\"}")
check "wrong password rejected" 401 "$(code_of "$R")" "$(body_of "$R")"

MUTATION="{\"type\":\"mutate\",\"protocolVersion\":2,\"listId\":\"$LIST\",\"entityType\":\"task\",\"entityId\":\"$TASK\",\"idempotencyKey\":\"$KEY\",\"deviceId\":\"smoke-a\",\"patch\":{\"title\":\"Melk\",\"position\":\"a0\"}}"

R=$(req -X POST "$BASE/lists/$LIST/mutate" -H "Authorization: Bearer $TOKEN_A" -H "$JSON" -d "$MUTATION")
check "mutate before claiming is refused" 403 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/lists/$LIST" -H "Authorization: Bearer $TOKEN_A")
check "claim list as owner" 200 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/lists/$LIST" -H "Authorization: Bearer $TOKEN_B")
check "someone else cannot claim it" 403 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/lists/$LIST/mutate" -H "Authorization: Bearer $TOKEN_A" -H "$JSON" -d "$MUTATION")
check "mutate" 200 "$(code_of "$R")" "$(body_of "$R")"
expect "first mutation seq" "$(field "$(body_of "$R")" '.change.seq')" "1"
expect "first mutation duplicate" "$(field "$(body_of "$R")" '.duplicate')" "false"

# F5.2 / H3.10 — the whole answer to "the response was lost after the server
# committed". Same key, same result, no second changelog row.
R=$(req -X POST "$BASE/lists/$LIST/mutate" -H "Authorization: Bearer $TOKEN_A" -H "$JSON" -d "$MUTATION")
check "retry with the same idempotency key" 200 "$(code_of "$R")" "$(body_of "$R")"
expect "retry duplicate flag" "$(field "$(body_of "$R")" '.duplicate')" "true"
expect "retry seq unchanged" "$(field "$(body_of "$R")" '.change.seq')" "1"

R=$(req "$BASE/lists/$LIST/changes?since=0" -H "Authorization: Bearer $TOKEN_A")
check "catch-up from 0" 200 "$(code_of "$R")" "$(body_of "$R")"
expect "retry added no changelog row" "$(field "$(body_of "$R")" '.changes.length')" "1"

R=$(req "$BASE/lists/$LIST/changes?since=0" -H "Authorization: Bearer $TOKEN_B")
check "partner cannot read the list yet" 403 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/lists/$LIST/invite" -H "Authorization: Bearer $TOKEN_A")
check "owner mints an invite" 200 "$(code_of "$R")" "$(body_of "$R")"
INVITE=$(field "$(body_of "$R")" '.inviteToken')

R=$(req -X POST "$BASE/invites/accept" -H "Authorization: Bearer $TOKEN_B" -H "$JSON" \
  -d "{\"inviteToken\":\"$INVITE\"}")
check "partner accepts" 200 "$(code_of "$R")" "$(body_of "$R")"
expect "first accept is not a repeat" "$(field "$(body_of "$R")" '.alreadyMember')" "false"

R=$(req -X POST "$BASE/invites/accept" -H "Authorization: Bearer $TOKEN_B" -H "$JSON" \
  -d "{\"inviteToken\":\"$INVITE\"}")
check "accepting twice is a no-op" 200 "$(code_of "$R")" "$(body_of "$R")"
expect "second accept reports alreadyMember" "$(field "$(body_of "$R")" '.alreadyMember')" "true"

R=$(req "$BASE/lists/$LIST/changes?since=0" -H "Authorization: Bearer $TOKEN_B")
check "partner now sees the list" 200 "$(code_of "$R")" "$(body_of "$R")"
expect "partner sees the task" "$(field "$(body_of "$R")" '.changes[0].entity.title')" "Melk"

R=$(req -X POST "$BASE/lists/$LIST/invite" -H "Authorization: Bearer $TOKEN_B")
check "a member cannot mint further invites" 403 "$(code_of "$R")" "$(body_of "$R")"

# L3: signed with the same key, so only the distinct claim shape stops this.
R=$(req -X POST "$BASE/invites/accept" -H "Authorization: Bearer $TOKEN_B" -H "$JSON" \
  -d "{\"inviteToken\":\"$TOKEN_B\"}")
check "an access token cannot be redeemed as an invite" 401 "$(code_of "$R")" "$(body_of "$R")"

# M2. The token is filed under the device id in the caller's own access token,
# so there is nothing in the body that could aim it at somebody else's phone.
# 204 and no body: the server either filed it or said why it would not.
R=$(req -X POST "$BASE/devices/token" -H "Authorization: Bearer $TOKEN_A" -H "$JSON" \
  -d "{\"fcmToken\":\"smoke-fcm-$SUFFIX\"}")
check "register a push token" 204 "$(code_of "$R")" "$(body_of "$R")"

# FCM re-issues tokens. The second replaces the first rather than adding a row,
# or a phone keeps being woken through a registration it has already dropped.
R=$(req -X POST "$BASE/devices/token" -H "Authorization: Bearer $TOKEN_A" -H "$JSON" \
  -d "{\"fcmToken\":\"smoke-fcm-rotated-$SUFFIX\"}")
check "a rotated push token replaces the first" 204 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/devices/token" -H "Authorization: Bearer $TOKEN_A" -H "$JSON" \
  -d '{"fcmToken":""}')
check "an empty push token is refused" 400 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/devices/token" -H "$JSON" \
  -d "{\"fcmToken\":\"smoke-fcm-$SUFFIX\"}")
check "registering a push token needs a session" 401 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/auth/refresh" -H "$JSON" \
  -d "{\"refreshToken\":\"$REFRESH_A\",\"deviceId\":\"smoke-a\"}")
check "refresh rotates" 200 "$(code_of "$R")" "$(body_of "$R")"
ROTATED=$(field "$(body_of "$R")" '.refreshToken')

# L1: a spent token coming back means it is loose. Every session for that user
# goes, including the one the legitimate client is holding.
R=$(req -X POST "$BASE/auth/refresh" -H "$JSON" \
  -d "{\"refreshToken\":\"$REFRESH_A\",\"deviceId\":\"smoke-a\"}")
check "replayed refresh token is refused" 401 "$(code_of "$R")" "$(body_of "$R")"
expect "replay error code" "$(field "$(body_of "$R")" '.code')" "token-reused"

R=$(req -X POST "$BASE/auth/refresh" -H "$JSON" \
  -d "{\"refreshToken\":\"$ROTATED\",\"deviceId\":\"smoke-a\"}")
check "the replay revoked every session for that user" 401 "$(code_of "$R")" "$(body_of "$R")"

printf '\n%sleft behind: %s, %s, list %s%s\n' "$DIM" "$EMAIL_A" "$EMAIL_B" "$LIST" "$RESET"
if [ "$FAILED" -gt 0 ]; then
  printf '%s%s check(s) failed%s\n' "$RED" "$FAILED" "$RESET"
  exit 1
fi
printf '%sall %s checks passed%s\n' "$GREEN" "$STEP" "$RESET"
