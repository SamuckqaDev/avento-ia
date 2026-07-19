#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${AVENTO_LOCAL_MCP_TOOLS_DIR:-$ROOT/.avento-tools/mcp}"
PYTHON="${AVENTO_LOCAL_MCP_PYTHON:-python3}"

info() {
  printf 'INFO %s\n' "$1"
}

tools_ready() {
  [ -x "$TOOLS_DIR/bin/python" ] \
    && [ -x "$TOOLS_DIR/bin/markitdown" ] \
    && [ -x "$TOOLS_DIR/bin/markitdown-mcp" ] \
    && "$TOOLS_DIR/bin/python" -c 'import markitdown' >/dev/null 2>&1 \
    && "$TOOLS_DIR/bin/markitdown" --help >/dev/null 2>&1
}

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  printf 'ERROR Python 3.10+ is required to install local document tools.\n' >&2
  exit 1
fi

if tools_ready; then
  info "local MCP document tools are ready at $TOOLS_DIR"
  exit 0
fi

if [ -d "$TOOLS_DIR" ]; then
  info "local MCP document tools are incomplete or were moved; rebuilding $TOOLS_DIR"
  rm -rf "$TOOLS_DIR"
fi

info "creating isolated local MCP environment at $TOOLS_DIR"
"$PYTHON" -m venv "$TOOLS_DIR"

info "installing MarkItDown universal reader and its MCP server"
if command -v uv >/dev/null 2>&1; then
  uv pip install --python "$TOOLS_DIR/bin/python" 'markitdown[all]' markitdown-mcp
else
  "$TOOLS_DIR/bin/python" -m pip install --upgrade pip
  "$TOOLS_DIR/bin/python" -m pip install 'markitdown[all]' markitdown-mcp
fi

"$TOOLS_DIR/bin/markitdown" --help >/dev/null
"$TOOLS_DIR/bin/python" -c 'import markitdown'
info "MarkItDown and markitdown-mcp installed successfully"
