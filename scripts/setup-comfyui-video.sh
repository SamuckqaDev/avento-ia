#!/usr/bin/env bash
set -euo pipefail

COMFYUI_DIR="${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}"
MODEL_REPOSITORY="${AVENTO_COMFYUI_VIDEO_MODEL_REPOSITORY:-https://huggingface.co/Comfy-Org/Wan_2.2_ComfyUI_Repackaged/resolve/main/split_files}"
TEXT_REPOSITORY="${AVENTO_COMFYUI_VIDEO_TEXT_REPOSITORY:-https://huggingface.co/Comfy-Org/Wan_2.1_ComfyUI_repackaged/resolve/main/split_files}"
MODE="${1:-install}"

DIFFUSION_MODEL="wan2.2_ti2v_5B_fp16.safetensors"
TEXT_ENCODER="umt5_xxl_fp8_e4m3fn_scaled.safetensors"
VAE_MODEL="wan2.2_vae.safetensors"

file_size() {
  stat -f '%z' "$1" 2>/dev/null || stat -c '%s' "$1"
}

model_ready() {
  local target="$1"
  local expected_size="$2"
  [ -f "$target" ] && [ "$(file_size "$target")" = "$expected_size" ]
}

check_models() {
  local missing=0
  local target

  target="$COMFYUI_DIR/models/diffusion_models/$DIFFUSION_MODEL"
  if ! model_ready "$target" 9999658848; then
    printf 'MISSING %s\n' "$target"
    missing=1
  fi

  target="$COMFYUI_DIR/models/text_encoders/$TEXT_ENCODER"
  if ! model_ready "$target" 6735906897; then
    printf 'MISSING %s\n' "$target"
    missing=1
  fi

  target="$COMFYUI_DIR/models/vae/$VAE_MODEL"
  if ! model_ready "$target" 1409400960; then
    printf 'MISSING %s\n' "$target"
    missing=1
  fi

  return "$missing"
}

download_model() {
  local category="$1"
  local filename="$2"
  local expected_size="$3"
  local repository="${4:-$MODEL_REPOSITORY}"
  local target="$COMFYUI_DIR/models/$category/$filename"
  local partial="$target.part"
  local url="$repository/$category/$filename"

  mkdir -p "$(dirname "$target")"
  if model_ready "$target" "$expected_size"; then
    printf 'OK   %s\n' "$target"
    return 0
  fi

  if [ -f "$target" ]; then
    printf 'WARN replacing incomplete model: %s\n' "$target"
    mv "$target" "$partial"
  fi

  printf 'INFO downloading %s\n' "$filename"
  curl --fail --location --retry 4 --retry-delay 3 --continue-at - --output "$partial" "$url"

  local actual_size
  actual_size="$(file_size "$partial")"
  if [ "$actual_size" != "$expected_size" ]; then
    printf 'ERROR invalid size for %s: expected %s, got %s\n' "$filename" "$expected_size" "$actual_size" >&2
    exit 1
  fi

  mv "$partial" "$target"
  printf 'OK   %s\n' "$target"
}

if [ "$MODE" = "--check" ]; then
  check_models
  exit $?
fi

if ! command -v curl >/dev/null 2>&1; then
  printf 'ERROR curl is required to download ComfyUI video models\n' >&2
  exit 1
fi

download_model "diffusion_models" "$DIFFUSION_MODEL" 9999658848
download_model "text_encoders" "$TEXT_ENCODER" 6735906897 "$TEXT_REPOSITORY"
download_model "vae" "$VAE_MODEL" 1409400960

if [ "${AVENTO_COMFYUI_VIDEO_CLEANUP_LEGACY:-1}" = "1" ]; then
  rm -f \
    "$COMFYUI_DIR/models/diffusion_models/wan2.1_t2v_1.3B_fp16.safetensors" \
    "$COMFYUI_DIR/models/vae/wan_2.1_vae.safetensors"
fi

printf '\nComfyUI WAN 2.2 TI2V video models are ready in %s/models\n' "$COMFYUI_DIR"
