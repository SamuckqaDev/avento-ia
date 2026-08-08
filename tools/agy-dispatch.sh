#!/usr/bin/env bash
#
# Despacha uma feature pro Antigravity e espera a resposta — sem clique.
#
# Usa a CLI interna `agentapi`, que vem embutida no Antigravity.app:
#   new-conversation [--model=<flash_lite|flash|pro>] [--title=<t>] <prompt>
#   get-conversation-metadata <conversation_id>
#   send-message <recipient_id> <content>
#
# O endereço do language server e o token CSRF MUDAM a cada restart do app,
# então são descobertos do processo em execução — nunca fixe esses valores.
#
# Uso:
#   ./tools/agy-dispatch.sh features/<slug>/SPEC.md            # despacha e espera
#   ./tools/agy-dispatch.sh features/<slug>/SPEC.md --no-wait  # só despacha
#   ./tools/agy-dispatch.sh --status <conversation_id>         # consulta progresso
#
# Pré-requisito: o Antigravity.app precisa estar ABERTO (ele hospeda o
# language server). Se não estiver, o script abre e aguarda.

set -euo pipefail

HARNESS_APP="/Applications/Antigravity.app"
AGENTAPI="$HOME/.gemini/antigravity/bin/agentapi"
CONVO_DIR="$HOME/.gemini/antigravity/conversations"
MODEL="${AGY_MODEL:-pro}"
POLL_INTERVAL="${AGY_POLL_INTERVAL:-20}"
IDLE_ROUNDS_TO_FINISH="${AGY_IDLE_ROUNDS:-3}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$REPO_ROOT" ]]; then
  REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

die() { echo "erro: $*" >&2; exit 1; }

# ── Descoberta do language server do harness ──────────────────────────────
# Porta e token são efêmeros: mudam a cada restart. Sempre releia do processo.
discover_ls() {
  local pid
  pid="$(pgrep -f "Antigravity.app/Contents/Resources/bin/language_server" | head -1 || true)"
  [[ -n "$pid" ]] || return 1

  LS_TOKEN="$(ps -o command= -p "$pid" | tr ' ' '\n' \
    | grep -A1 -x -- "--csrf_token" | tail -1)"
  [[ -n "$LS_TOKEN" ]] || return 1

  # O LS abre duas portas; a gRPC é a que responde "missing CSRF token" sem auth.
  local ports
  ports="$(lsof -nP -iTCP -sTCP:LISTEN -a -p "$pid" 2>/dev/null \
    | awk 'NR>1 {print $9}' | sed 's/.*://')"

  # O LS abre duas portas e só uma fala gRPC. A que não fala responde
  # "error reading server preface". Capturamos a saída em variável em vez de
  # usar pipe: o agentapi sai com código != 0 em erro e o `pipefail` deste
  # script descartaria as duas portas silenciosamente.
  local p out
  for p in $ports; do
    out="$(ANTIGRAVITY_LS_ADDRESS="127.0.0.1:$p" ANTIGRAVITY_CSRF_TOKEN="$LS_TOKEN" \
      "$AGENTAPI" get-conversation-metadata "probe" 2>&1 || true)"
    case "$out" in
      *"server preface"*) continue ;;
      *) LS_ADDRESS="127.0.0.1:$p"; return 0 ;;
    esac
  done
  return 1
}

ensure_harness() {
  if ! pgrep -f "Antigravity.app/Contents/MacOS/Antigravity" >/dev/null 2>&1; then
    echo "Antigravity não está aberto — abrindo..."
    open -a "$HARNESS_APP"
    local i
    for i in $(seq 1 30); do
      sleep 2
      discover_ls && return 0
    done
    die "o harness não subiu a tempo. Abra o Antigravity manualmente e rode de novo."
  fi
  discover_ls || die "não achei o language server do harness. O Antigravity está aberto?"
}

# ── Progresso: a trajetória fica num SQLite por conversa ───────────────────
steps_count() {
  local cid="$1" db="$CONVO_DIR/$1.db"
  [[ -f "$db" ]] || { echo 0; return; }
  sqlite3 "$db" "SELECT COUNT(*) FROM steps;" 2>/dev/null || echo 0
}

wait_for_completion() {
  local cid="$1" last=-1 idle=0 now
  echo
  echo "aguardando o agente... (Ctrl+C interrompe o acompanhamento, não o agente)"
  while true; do
    sleep "$POLL_INTERVAL"
    now="$(steps_count "$cid")"
    if [[ "$now" == "$last" ]]; then
      idle=$((idle + 1))
      if [[ $idle -ge $IDLE_ROUNDS_TO_FINISH ]]; then
        echo "sem novos passos há $((POLL_INTERVAL * idle))s — provavelmente terminou ou está aguardando permissão."
        return 0
      fi
    else
      idle=0
      echo "  passos: $now"
    fi
    last="$now"
  done
}

# ── Modo --status ─────────────────────────────────────────────────────────
if [[ "${1:-}" == "--status" ]]; then
  [[ -n "${2:-}" ]] || die "uso: $0 --status <conversation_id>"
  ensure_harness
  ANTIGRAVITY_LS_ADDRESS="$LS_ADDRESS" ANTIGRAVITY_CSRF_TOKEN="$LS_TOKEN" \
    "$AGENTAPI" get-conversation-metadata "$2"
  echo "passos registrados: $(steps_count "$2")"
  exit 0
fi

# ── Despacho ──────────────────────────────────────────────────────────────
SPEC_REL="${1:-}"
[[ -n "$SPEC_REL" ]] || die "uso: $0 <caminho-da-spec> [--no-wait]"

SPEC_PATH="$REPO_ROOT/$SPEC_REL"
[[ -f "$SPEC_PATH" ]] || SPEC_PATH="$SPEC_REL"
[[ -f "$SPEC_PATH" ]] || die "spec não encontrada: $SPEC_REL"
SPEC_ABS="$(cd "$(dirname "$SPEC_PATH")" && pwd)/$(basename "$SPEC_PATH")"

[[ -x "$AGENTAPI" ]] || die "agentapi não encontrado em $AGENTAPI"
command -v sqlite3 >/dev/null || die "sqlite3 é necessário pro acompanhamento"

ensure_harness

TITLE="$(basename "$(dirname "$SPEC_ABS")")"

read -r -d '' PROMPT <<EOF || true
Use a skill "execute-feature-spec".

Spec: $SPEC_ABS

Execute as tarefas na ordem descrita. Reporte os resultados reais de cada
comando de validação, incluindo falhas. Não declare pronto sem build e testes
passando.
EOF

echo "despachando: $SPEC_REL"
echo "     modelo: $MODEL"
echo "         LS: $LS_ADDRESS"
echo

RESPONSE="$(ANTIGRAVITY_LS_ADDRESS="$LS_ADDRESS" ANTIGRAVITY_CSRF_TOKEN="$LS_TOKEN" \
  "$AGENTAPI" new-conversation --model="$MODEL" --title="$TITLE" "$PROMPT" 2>&1)"

echo "$RESPONSE"

CID="$(printf '%s' "$RESPONSE" | grep -oE '"conversationId"[^"]*"[^"]+"' | grep -oE '[0-9a-f-]{36}' | head -1 || true)"
if [[ -z "$CID" ]]; then
  echo
  echo "não consegui extrair o conversationId da resposta acima."
  echo "acompanhe pelo app; a conversa se chama: $TITLE"
  exit 0
fi

echo
echo "conversationId: $CID"
echo "acompanhar depois:  $0 --status $CID"

if [[ "${2:-}" == "--no-wait" ]]; then
  exit 0
fi

wait_for_completion "$CID"

echo
echo "o que mudou no repo:"
git -C "$REPO_ROOT" status -s | head -30
