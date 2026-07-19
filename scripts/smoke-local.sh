#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_URL="${AVENTO_BACKEND_URL:-http://127.0.0.1:8000}"
FRONTEND_URL="${AVENTO_FRONTEND_URL:-http://127.0.0.1:5173}"
WORKSPACE="${1:-$ROOT}"
SMOKE_EMAIL="${AVENTO_SMOKE_EMAIL:-admin@avento.local}"
SMOKE_PASSWORD="${AVENTO_SMOKE_PASSWORD:?Set AVENTO_SMOKE_PASSWORD to the same value used for AVENTO_AUTH_ROOT_PASSWORD}"
COOKIE_JAR="$(mktemp)"

cleanup() {
  rm -f "$COOKIE_JAR"
}

trap cleanup EXIT

json_string() {
  node -e 'process.stdout.write(JSON.stringify(process.argv[1]))' "$1"
}

post_json() {
  local url="$1"
  local payload="$2"
  curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H 'Content-Type: application/json' -d "$payload" "$url"
}

ok() {
  printf 'OK   %s\n' "$1"
}

printf 'Running Avento smoke test...\n\n'

curl -fsS "$BACKEND_URL/api/health" >/dev/null
ok "backend health"

curl -fsS "$FRONTEND_URL" >/dev/null
ok "frontend index"

email_json="$(json_string "$SMOKE_EMAIL")"
password_json="$(json_string "$SMOKE_PASSWORD")"
bootstrap_payload="{\"email\":$email_json,\"password\":$password_json,\"displayName\":\"Avento Dev\"}"
login_payload="{\"email\":$email_json,\"password\":$password_json}"
if curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H 'Content-Type: application/json' -d "$bootstrap_payload" "$BACKEND_URL/api/auth/bootstrap" >/dev/null 2>&1; then
  ok "auth bootstrap"
else
  curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H 'Content-Type: application/json' -d "$login_payload" "$BACKEND_URL/api/auth/login" >/dev/null
  ok "auth login"
fi

curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BACKEND_URL/api/auth/me" >/dev/null
ok "auth session"

workspace_json="$(json_string "$WORKSPACE")"
post_json "$BACKEND_URL/api/fs/authorize" "{\"path\":$workspace_json}" >/dev/null
ok "workspace authorized: $WORKSPACE"

analysis="$(post_json "$BACKEND_URL/api/projects/analyze" "{\"path\":$workspace_json}")"
printf '%s' "$analysis" | node -e '
const chunks = [];
process.stdin.on("data", chunk => chunks.push(chunk));
process.stdin.on("end", () => {
  const data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  const stacks = Array.isArray(data.technologies) ? data.technologies.join(", ") : "unknown";
  const scripts = Array.isArray(data.scripts) ? data.scripts.length : 0;
  console.log(`OK   project analysis: stacks=[${stacks}], scripts=${scripts}`);
});
'

if curl -fsS -b "$COOKIE_JAR" "$BACKEND_URL/api/ai/models" >/dev/null 2>&1; then
  ok "Ollama model endpoint"
else
  printf 'WARN Ollama model endpoint unavailable; start Ollama natively with: ollama serve\n'
fi

if [ "${AVENTO_SMOKE_RUN_VALIDATION:-0}" = "1" ]; then
  web_path_json="$(json_string "$ROOT/front")"
  result="$(post_json "$BACKEND_URL/api/projects/run" "{\"path\":$web_path_json,\"runner\":\"npm\",\"name\":\"typecheck\"}")"
  printf '%s' "$result" | node -e '
const chunks = [];
process.stdin.on("data", chunk => chunks.push(chunk));
process.stdin.on("end", () => {
  const data = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (data.exitCode !== 0 || data.timedOut) {
    console.error(`FAIL npm typecheck exit=${data.exitCode} timedOut=${data.timedOut}`);
    process.exit(1);
  }
  console.log(`OK   command runner: ${data.command}`);
});
'
fi

printf '\nSmoke test finished.\n'
