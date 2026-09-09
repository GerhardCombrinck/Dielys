#!/bin/sh
# Makes a deployed Dielys server attempt one real FCM send, so the credential
# behind FCM_SERVICE_ACCOUNT_JSON is exercised rather than assumed.
#
# smoke.sh proves /devices/token stores a token. It cannot prove the server can
# then authenticate to Google and be answered, because the send is deliberately
# best-effort and off the response path (M2) — a mutation acks whether or not the
# push went anywhere. This drives that path on purpose and tells you what the log
# should say.
#
# It registers a deliberately invalid FCM token, and the answer to look for is
# fcm.send.rejected with status 400. That is FCM saying "this token is not a
# token" — which it can only say after it has authenticated the request, matched
# the project and accepted the body. A credential problem fails earlier and
# differently, and that is the whole question being asked.
#
# It cannot reach UNREGISTERED (404). That needs a token FCM recognises as
# well-formed but no longer registered, which belongs to a real device that has
# been wiped; a string cannot be made into one. The 404 path is covered by the
# server suite instead.
#
# POSIX sh (A2). Needs curl and node. Run `npx wrangler tail --env dev` first.
#
#   scripts/push-probe.sh https://dielys-dev.dielys.workers.dev
#
# Spends one of the three registrations a client gets in an hour (ADR 0004), so
# it shares that budget with smoke.sh.

set -eu

BASE="${1:-${DIELYS_URL:-http://127.0.0.1:8787}}"
JSON='content-type: application/json'

DIM=''
BOLD=''
RESET=''
if [ -t 1 ]; then
  DIM=$(printf '\033[2m')
  BOLD=$(printf '\033[1m')
  RESET=$(printf '\033[0m')
fi

req() { curl -s -w '\n%{http_code}' "$@"; }
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

# Stops at the first disagreement: every later step depends on this one, and a
# cascade of failures says less than the one that started it.
must() {
  if [ "$3" != "$2" ]; then
    printf 'FAILED at %s: expected %s, got %s\n%s\n' "$1" "$2" "$3" "$4" >&2
    exit 1
  fi
  printf '%s  %s%s\n' "$DIM" "$1" "$RESET"
}

SUFFIX=$(uuid)
EMAIL="probe-$SUFFIX@dielys.test"
PASS=$(node -pe 'require("crypto").randomBytes(24).toString("base64url")')
LIST=$(uuid)
TASK=$(uuid)

printf '%spush probe: %s%s\n\n' "$DIM" "$BASE" "$RESET"

R=$(req -X POST "$BASE/auth/register" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"deviceId\":\"probe-writer\"}")
must "register the probe account" 201 "$(code_of "$R")" "$(body_of "$R")"

# Two devices on one account, because the fan-out excludes the device that made
# the write. One phone talking to itself would correctly send nothing at all.
R=$(req -X POST "$BASE/auth/login" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"deviceId\":\"probe-sleeper\"}")
must "sign in the sleeping device" 200 "$(code_of "$R")" "$(body_of "$R")"
SLEEPER=$(field "$(body_of "$R")" '.accessToken')

R=$(req -X POST "$BASE/auth/login" -H "$JSON" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"deviceId\":\"probe-writer\"}")
must "sign in the writing device" 200 "$(code_of "$R")" "$(body_of "$R")"
WRITER=$(field "$(body_of "$R")" '.accessToken')

# Invalid on purpose. A real token would wake a real phone, and this script has
# no business doing that; "that is not a token" is the answer being asked for,
# and it is only reachable once the credential has already worked.
R=$(req -X POST "$BASE/devices/token" -H "Authorization: Bearer $SLEEPER" -H "$JSON" \
  -d "{\"fcmToken\":\"probe-not-a-real-fcm-token-$SUFFIX\"}")
must "register a deliberately dead token" 204 "$(code_of "$R")" "$(body_of "$R")"

R=$(req -X POST "$BASE/lists/$LIST" -H "Authorization: Bearer $WRITER")
must "claim a list" 200 "$(code_of "$R")" "$(body_of "$R")"

# Neither device holds a socket, so the sleeper is exactly the case a wake push
# exists for.
R=$(req -X POST "$BASE/lists/$LIST/mutate" -H "Authorization: Bearer $WRITER" -H "$JSON" \
  -d "{\"type\":\"mutate\",\"protocolVersion\":2,\"listId\":\"$LIST\",\"entityType\":\"task\",\"entityId\":\"$TASK\",\"idempotencyKey\":\"$(uuid)\",\"deviceId\":\"probe-writer\",\"patch\":{\"title\":\"probe\",\"position\":\"a0\"}}")
must "write, which should trigger the wake" 200 "$(code_of "$R")" "$(body_of "$R")"

cat <<BANNER

${BOLD}Now read the wrangler tail.${RESET} The mutation acked either way — the send is
best-effort and off the response path, so this script cannot tell you the answer.

  ${BOLD}fcm.send.rejected${RESET} with status ${BOLD}400${RESET}
      Working. FCM authenticated the request, matched the project, read the body
      and objected only to the token — which is the furthest this script can get
      without a real device. No usersroom.device.dropped follows, deliberately:
      a row is deleted on 404 and never on 400, because a 400 can be our bug.

  ${BOLD}fcm.send.unregistered${RESET} + ${BOLD}usersroom.device.dropped${RESET}
      Also working, and better. Only reachable with a token FCM recognises but
      no longer knows — a real device that was wiped, not the string below.

  ${BOLD}usersroom.push.disabled${RESET}
      FCM_SERVICE_ACCOUNT_JSON is unset or did not parse. Look for
      fcm.service-account.unparseable or .incomplete just before it.

  ${BOLD}fcm.token.failed${RESET}
      The OAuth exchange with Google failed. A bad private_key or client_email,
      or the service account was deleted.

  ${BOLD}fcm.send.rejected${RESET} with status ${BOLD}401 or 403${RESET}
      Authenticated, then refused. Usually the service account belongs to a
      different project than the one in project_id, or the Cloud Messaging API
      is not enabled on it.

  ${DIM}nothing at all${RESET}
      The wake never ran. Check the deployed version is current.

${DIM}left behind: $EMAIL, list $LIST${RESET}
BANNER
