#!/usr/bin/env bash
set -euo pipefail

# Sobe so o servidor de desenvolvimento do front (Vite). Backend e Ollama ficam por sua conta —
# para subir tudo junto use ./scripts/dev-up.sh ou ./scripts/dev-electron.sh.
#
# HTTPS e o padrao. O iPhone so abre a camera em contexto seguro, e localhost — a unica excecao —
# nao vale para o telefone, que chega pelo IP da rede. Use --http para o caso raro de precisar do
# front sem TLS; o visor nao funciona assim.
#
#   ./scripts/dev-web.sh          # https://localhost:5173
#   ./scripts/dev-web.sh --http   # http://localhost:5173

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"

export AVENTO_PROJECT_ROOT="$ROOT"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

SCRIPT='dev:web'
HTTPS=1
for arg in "$@"; do
  case "$arg" in
    --http|--no-https)
      SCRIPT='dev:web:http'
      HTTPS=0
      ;;
    *)
      printf 'ERROR opcao desconhecida: %s (use --http)\n' "$arg" >&2
      exit 1
      ;;
  esac
done

if [ "$HTTPS" -eq 1 ]; then
  LAN_IP="${AVENTO_LAN_IP:-$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)}"
  if [ -n "$LAN_IP" ]; then
    printf 'INFO no iPhone, abra https://%s:5173/remote\n' "$LAN_IP"
  fi
  if [ ! -f "$ROOT/front/certs/dev-cert.pem" ]; then
    printf 'INFO sem certificado local: o Safari vai avisar "conexao nao privada".\n'
    printf 'INFO para remover o aviso, rode ./scripts/setup-dev-cert.sh\n'
  fi
fi

printf 'INFO npm run %s\n' "$SCRIPT"
exec npm --prefix "$ROOT/front" run "$SCRIPT"
