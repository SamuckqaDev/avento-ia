#!/usr/bin/env bash
set -euo pipefail

COMFYUI_DIR="${1:-${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}}"
COMFYUI_REPOSITORY="${AVENTO_COMFYUI_REPOSITORY:-https://github.com/comfyanonymous/ComfyUI.git}"
PYTHON_COMMAND="${AVENTO_COMFYUI_PYTHON:-python3}"

info() {
  printf 'INFO %s\n' "$1"
}

if ! command -v git >/dev/null 2>&1; then
  printf 'ERROR git is required to install ComfyUI\n' >&2
  exit 1
fi

if ! command -v "$PYTHON_COMMAND" >/dev/null 2>&1; then
  printf 'ERROR Python command not found: %s\n' "$PYTHON_COMMAND" >&2
  exit 1
fi

if [ ! -f "$COMFYUI_DIR/main.py" ]; then
  if [ -e "$COMFYUI_DIR" ] && [ ! -d "$COMFYUI_DIR/.git" ]; then
    printf 'ERROR ComfyUI path exists but is not a git checkout: %s\n' "$COMFYUI_DIR" >&2
    exit 1
  fi

  if [ ! -d "$COMFYUI_DIR" ]; then
    info "cloning ComfyUI into $COMFYUI_DIR"
    git clone --depth 1 "$COMFYUI_REPOSITORY" "$COMFYUI_DIR"
  else
    info "updating ComfyUI in $COMFYUI_DIR"
    git -C "$COMFYUI_DIR" pull --ff-only
  fi
fi

if [ ! -x "$COMFYUI_DIR/.venv/bin/python" ]; then
  info "creating ComfyUI Python environment"
  "$PYTHON_COMMAND" -m venv "$COMFYUI_DIR/.venv"
fi

info "installing ComfyUI Python dependencies"
"$COMFYUI_DIR/.venv/bin/python" -m pip install --upgrade pip
"$COMFYUI_DIR/.venv/bin/python" -m pip install -r "$COMFYUI_DIR/requirements.txt"

printf '\nComfyUI is ready at %s\n' "$COMFYUI_DIR"
printf 'Checkpoints must be placed in %s/models/checkpoints\n' "$COMFYUI_DIR"
