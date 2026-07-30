#!/usr/bin/env bash
set -euo pipefail

# Sobe o app desktop (Electron). Ele cuida do resto: Postgres, Redis, Ollama, backend e Vite.
#
# O Vite sobe em HTTPS por padrao — e o que o iPhone exige para abrir a camera no visor. Use --http
# para o caso raro de precisar do front sem TLS.
#
#   ./scripts/dev-electron.sh          # HTTPS: Mac e visor no iPhone
#   ./scripts/dev-electron.sh --http   # sem TLS (o visor nao abre a camera)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"

export AVENTO_PROJECT_ROOT="$ROOT"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

SCRIPT='dev:electron'
for arg in "$@"; do
  case "$arg" in
    --http|--no-https)
      SCRIPT='dev:electron:http'
      ;;
    *)
      printf 'ERROR opcao desconhecida: %s (use --http)\n' "$arg" >&2
      exit 1
      ;;
  esac
done

printf 'INFO npm run %s\n' "$SCRIPT"
exec npm --prefix "$ROOT/front" run "$SCRIPT"
