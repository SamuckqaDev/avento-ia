#!/usr/bin/env bash
set -euo pipefail

# Gera o certificado HTTPS do servidor de desenvolvimento com mkcert.
#
# Por que existe: o iPhone so abre a camera em contexto seguro. `getUserMedia` e bloqueado em
# http://192.168.x.x, e localhost e a unica excecao — que nao vale para o telefone, que chega pelo
# IP da rede. Sem HTTPS a tela /remote carrega e a camera nunca liga.
#
# O @vitejs/plugin-basic-ssl resolve o contexto seguro, mas com certificado autoassinado: o Safari
# mostra "conexao nao privada" a cada sessao e o Electron loga erro de handshake. O mkcert emite pela
# mesma CA local que o sistema confia, entao o aviso some dos dois lados.
#
# O certificado inclui o IP atual da rede. Trocar de Wi-Fi troca o IP — rode de novo quando isso
# acontecer, ou o telefone volta a ver o aviso.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$ROOT/front/certs"
CERT_FILE="$CERT_DIR/dev-cert.pem"
KEY_FILE="$CERT_DIR/dev-key.pem"

info() {
  printf 'INFO %s\n' "$1"
}

if ! command -v mkcert >/dev/null 2>&1; then
  printf 'ERROR mkcert nao encontrado. Instale com: brew install mkcert\n' >&2
  exit 1
fi

# A CA local precisa estar no chaveiro do macOS. `mkcert -install` pede a senha do usuario, entao
# nao da para faze-lo aqui dentro sem interacao.
if [ ! -f "$(mkcert -CAROOT)/rootCA.pem" ]; then
  printf 'ERROR CA local ausente. Rode uma vez: mkcert -install\n' >&2
  exit 1
fi

LAN_IP="${AVENTO_LAN_IP:-$(ipconfig getifaddr en0 2>/dev/null || true)}"
if [ -z "$LAN_IP" ]; then
  LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
fi

HOSTS=(localhost 127.0.0.1 ::1)
if [ -n "$LAN_IP" ]; then
  HOSTS+=("$LAN_IP")
  info "IP da rede local: $LAN_IP"
else
  info 'nenhum IP de rede local detectado; o certificado cobrira apenas localhost'
fi

mkdir -p "$CERT_DIR"

info "emitindo certificado para: ${HOSTS[*]}"
mkcert -cert-file "$CERT_FILE" -key-file "$KEY_FILE" "${HOSTS[@]}"

info "certificado em $CERT_FILE"
info 'suba o front (./scripts/dev-web.sh) e abra no iPhone pelo IP acima'

if [ -n "$LAN_IP" ]; then
  cat <<EOF

Para o iPhone confiar sem aviso, instale a CA local nele uma vez:

  1. mkcert -CAROOT           # mostra a pasta da CA
  2. envie o rootCA.pem para o iPhone (AirDrop e o caminho mais curto)
  3. Ajustes > Geral > VPN e Gerenciamento de Dispositivo > instalar o perfil
  4. Ajustes > Geral > Sobre > Ajustes de Confianca em Certificados > habilite a CA

Depois disso, abra https://$LAN_IP:5173/remote no Safari.
EOF
fi
