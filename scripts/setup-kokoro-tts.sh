#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${AVENTO_KOKORO_RUNTIME_DIR:-$HOME/.avento/tools/kokoro-tts}"
PYTHON_VERSION="${AVENTO_KOKORO_PYTHON_VERSION:-3.12}"

info() { printf 'INFO %s\n' "$1"; }

if ! command -v uv >/dev/null 2>&1; then
  printf 'ERROR uv is required to prepare the isolated Kokoro runtime.\n' >&2
  exit 1
fi

if ! command -v espeak-ng >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    info 'installing espeak-ng (phoneme engine; it is not a system voice)'
    brew install espeak-ng
  else
    printf 'ERROR espeak-ng is required by Kokoro for Brazilian Portuguese phonemes.\n' >&2
    exit 1
  fi
fi

if [ ! -x "$RUNTIME_DIR/bin/python" ]; then
  info "creating isolated Python $PYTHON_VERSION runtime at $RUNTIME_DIR"
  uv venv --python "$PYTHON_VERSION" "$RUNTIME_DIR"
fi

info 'installing the local Kokoro neural TTS runtime'
uv pip install --python "$RUNTIME_DIR/bin/python" 'kokoro>=0.9.4,<1.0.0' 'misaki[en]>=0.9.4' 'soundfile>=0.13.1'

"$RUNTIME_DIR/bin/python" -c 'from kokoro import KPipeline; import soundfile; print("Kokoro neural TTS runtime ready")'
info "done. Start it with: $RUNTIME_DIR/bin/python $ROOT/scripts/kokoro_tts_server.py"
