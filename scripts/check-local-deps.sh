#!/usr/bin/env bash
set -u

missing_required=0
missing_optional=0

ok() {
  printf 'OK   %s\n' "$1"
}

warn() {
  printf 'WARN %s\n' "$1"
}

require_command() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    ok "$name: $(command -v "$name")"
  else
    warn "$name not found"
    missing_required=$((missing_required + 1))
  fi
}

optional_command() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    ok "$name: $(command -v "$name")"
  else
    warn "$name not found (optional)"
    missing_optional=$((missing_optional + 1))
  fi
}

optional_file() {
  local path="$1"
  local label="$2"
  if [ -e "$path" ]; then
    ok "$label: $path"
  else
    warn "$label missing at $path (optional)"
    missing_optional=$((missing_optional + 1))
  fi
}

printf 'Checking Avento local dependencies...\n\n'

printf 'Required commands:\n'
require_command java
require_command mvn
require_command node
require_command npm
require_command npx
require_command docker
require_command ollama

printf '\nContainer runtime:\n'
if command -v colima >/dev/null 2>&1; then
  ok "colima: $(command -v colima)"
  colima status 2>/dev/null | sed 's/^/     /' || warn "colima is installed but not running"
else
  warn "colima not found (fine if you use Docker Desktop or another Docker runtime)"
fi

printf '\nOptional voice commands:\n'
if command -v ffmpeg >/dev/null 2>&1; then
  ok "ffmpeg: $(command -v ffmpeg)"
elif [ -x /opt/homebrew/bin/ffmpeg ]; then
  ok "ffmpeg: /opt/homebrew/bin/ffmpeg"
else
  warn "ffmpeg not found (optional)"
  missing_optional=$((missing_optional + 1))
fi

printf '\nLocal MCP runtime:\n'
optional_command uvx
LOCAL_MCP_TOOLS_DIR="${AVENTO_LOCAL_MCP_TOOLS_DIR:-$PWD/.avento-tools/mcp}"
if [ -x "$LOCAL_MCP_TOOLS_DIR/bin/python" ] \
  && [ -x "$LOCAL_MCP_TOOLS_DIR/bin/markitdown" ] \
  && [ -x "$LOCAL_MCP_TOOLS_DIR/bin/markitdown-mcp" ] \
  && "$LOCAL_MCP_TOOLS_DIR/bin/python" -c 'import markitdown' >/dev/null 2>&1 \
  && "$LOCAL_MCP_TOOLS_DIR/bin/markitdown" --help >/dev/null 2>&1; then
  ok "MarkItDown reader: $LOCAL_MCP_TOOLS_DIR/bin/markitdown"
  ok "MarkItDown MCP server: $LOCAL_MCP_TOOLS_DIR/bin/markitdown-mcp"
else
  warn "MarkItDown local runtime is missing or broken (run ./scripts/setup-local-mcps.sh)"
  missing_optional=$((missing_optional + 1))
fi

printf '\nOptional image generation:\n'
if command -v comfyui >/dev/null 2>&1; then
  ok "comfyui command: $(command -v comfyui)"
elif [ -f "${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}/main.py" ]; then
  ok "ComfyUI installation: ${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}"
else
  warn "ComfyUI not found (optional; Ollama image generation remains available)"
  missing_optional=$((missing_optional + 1))
fi

if [ -x "scripts/setup-comfyui-video.sh" ] && scripts/setup-comfyui-video.sh --check >/dev/null 2>&1; then
  ok "ComfyUI WAN video models"
else
  warn "ComfyUI WAN video models missing (run ./scripts/setup-comfyui-video.sh)"
  missing_optional=$((missing_optional + 1))
fi

if [ -x "scripts/setup-comfyui-image.sh" ] && scripts/setup-comfyui-image.sh --check >/dev/null 2>&1; then
  ok "ComfyUI image refinement pipeline"
else
  warn "ComfyUI image refinement pipeline missing (run ./scripts/setup-comfyui-image.sh)"
  missing_optional=$((missing_optional + 1))
fi

if [ -x "scripts/setup-comfyui-sdxl.sh" ] && scripts/setup-comfyui-sdxl.sh --check >/dev/null 2>&1; then
  ok "ComfyUI RealVisXL SDXL fidelity pipeline"
else
  warn "ComfyUI SDXL fidelity models missing (run ./scripts/setup-comfyui-sdxl.sh)"
  missing_optional=$((missing_optional + 1))
fi

if [ -x "scripts/setup-comfyui-flux2.sh" ] && scripts/setup-comfyui-flux2.sh --check >/dev/null 2>&1; then
  ok "ComfyUI FLUX.2 Klein 4B pipeline"
else
  warn "ComfyUI FLUX.2 Klein models missing (run ./scripts/setup-comfyui-flux2.sh)"
  missing_optional=$((missing_optional + 1))
fi

printf '\nOptional MCP packages are launched through npx when enabled:\n'
printf '     @wonderwhy-er/desktop-commander@0.2.46\n'
printf '     @steipete/macos-automator-mcp@0.4.5 (macOS only)\n'
printf '     @playwright/mcp@0.0.78\n'
printf '     @modelcontextprotocol/server-puppeteer@2025.5.12\n'
printf '     @modelcontextprotocol/server-memory@2026.7.4\n'
printf '     @modelcontextprotocol/server-sequential-thinking@2026.7.4\n'
printf '     mcp-searxng@1.11.1 (requires AVENTO_MCP_SEARXNG_URL)\n'
printf '     @bytebase/dbhub@0.23.0 (requires AVENTO_MCP_DBHUB_DSN or project database config)\n'

printf '\nOptional local voice files:\n'
WHISPER_BINARY="back/whisper.cpp/build/bin/whisper-cli"
WHISPER_LIBRARY_DIR="$(dirname "$WHISPER_BINARY")"
if [ ! -x "$WHISPER_BINARY" ]; then
  warn "Whisper.cpp binary missing at $WHISPER_BINARY (optional)"
  missing_optional=$((missing_optional + 1))
elif DYLD_LIBRARY_PATH="$WHISPER_LIBRARY_DIR${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}" \
  LD_LIBRARY_PATH="$WHISPER_LIBRARY_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$WHISPER_BINARY" --help >/dev/null 2>&1; then
  ok "Whisper.cpp binary: $WHISPER_BINARY"
else
  warn "Whisper.cpp binary is present but its local runtime libraries could not be loaded (optional)"
  missing_optional=$((missing_optional + 1))
fi
if [ -f "back/whisper.cpp/models/ggml-small.bin" ]; then
  ok "Whisper.cpp model: back/whisper.cpp/models/ggml-small.bin"
elif [ -f "back/whisper.cpp/models/ggml-base.bin" ]; then
  warn "Whisper.cpp model: ggml-base.bin found; ggml-small.bin or larger is recommended for Portuguese voice commands"
  missing_optional=$((missing_optional + 1))
else
  warn "Whisper.cpp model not found (expected ggml-small.bin or another configured local model)"
  missing_optional=$((missing_optional + 1))
fi
if [ ! -x "piper_tts/.venv/bin/piper" ]; then
  warn "Piper binary missing at piper_tts/.venv/bin/piper (optional)"
  missing_optional=$((missing_optional + 1))
elif "piper_tts/.venv/bin/piper" --help >/dev/null 2>&1; then
  ok "Piper binary: piper_tts/.venv/bin/piper"
elif [ -x "piper_tts/.venv/bin/python" ] \
  && "piper_tts/.venv/bin/python" "piper_tts/.venv/bin/piper" --help >/dev/null 2>&1; then
  ok "Piper binary: piper_tts/.venv/bin/piper (relocated virtualenv supported)"
else
  warn "Piper launcher is present but cannot run (optional)"
  missing_optional=$((missing_optional + 1))
fi
optional_file "piper_tts/pt_BR-faber-medium.onnx" "Piper pt_BR default model"

printf '\nSummary:\n'
printf 'Required missing: %s\n' "$missing_required"
printf 'Optional missing: %s\n' "$missing_optional"

if [ "$missing_required" -gt 0 ]; then
  printf '\nInstall the required commands above before running the full app.\n'
  exit 1
fi

printf '\nBase app dependencies look ready. Optional voice warnings can be handled later.\n'
