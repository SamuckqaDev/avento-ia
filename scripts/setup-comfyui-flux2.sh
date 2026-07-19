#!/usr/bin/env bash
set -euo pipefail

COMFYUI_DIR="${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}"
if [ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ]; then
  APPLE_SILICON=1
  DEFAULT_MODEL_NAME="flux-2-klein-4b.safetensors"
  DEFAULT_MODEL_URL="https://huggingface.co/black-forest-labs/FLUX.2-klein-4B/resolve/main/flux-2-klein-4b.safetensors"
  DEFAULT_MODEL_SHA256="ec3d4e733a771f61c052fb4856c48b336c55eaf2c65487c2a1faeb9bbda7a343"
  MODEL_VARIANT="BF16 for Apple Silicon"
else
  APPLE_SILICON=0
  DEFAULT_MODEL_NAME="flux-2-klein-4b-fp8.safetensors"
  DEFAULT_MODEL_URL="https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8/resolve/main/flux-2-klein-4b-fp8.safetensors"
  DEFAULT_MODEL_SHA256="97ed34fe0567e436200f2faee3939b88f2b5d99f8af2a4dc16532c4245c0ccb6"
  MODEL_VARIANT="FP8"
fi

MODEL_NAME="${AVENTO_COMFYUI_FLUX2_MODEL:-$DEFAULT_MODEL_NAME}"
TEXT_ENCODER_NAME="${AVENTO_COMFYUI_FLUX2_TEXT_ENCODER:-qwen_3_4b.safetensors}"
VAE_NAME="${AVENTO_COMFYUI_FLUX2_VAE:-flux2-vae.safetensors}"

MODEL_URL="${AVENTO_COMFYUI_FLUX2_MODEL_URL:-$DEFAULT_MODEL_URL}"
TEXT_ENCODER_URL="${AVENTO_COMFYUI_FLUX2_TEXT_ENCODER_URL:-https://huggingface.co/Comfy-Org/flux2-klein-4B/resolve/main/split_files/text_encoders/qwen_3_4b.safetensors}"
VAE_URL="${AVENTO_COMFYUI_FLUX2_VAE_URL:-https://huggingface.co/Comfy-Org/flux2-dev/resolve/main/split_files/vae/flux2-vae.safetensors}"

MODEL_SHA256="${AVENTO_COMFYUI_FLUX2_MODEL_SHA256:-$DEFAULT_MODEL_SHA256}"
TEXT_ENCODER_SHA256="${AVENTO_COMFYUI_FLUX2_TEXT_ENCODER_SHA256:-6c671498573ac2f7a5501502ccce8d2b08ea6ca2f661c458e708f36b36edfc5a}"
VAE_SHA256="${AVENTO_COMFYUI_FLUX2_VAE_SHA256:-d64f3a68e1cc4f9f4e29b6e0da38a0204fe9a49f2d4053f0ec1fa1ca02f9c4b5}"

MODEL_PATH="$COMFYUI_DIR/models/diffusion_models/$MODEL_NAME"
TEXT_ENCODER_PATH="$COMFYUI_DIR/models/text_encoders/$TEXT_ENCODER_NAME"
VAE_PATH="$COMFYUI_DIR/models/vae/$VAE_NAME"

info() {
  printf 'INFO %s\n' "$1"
}

sha256() {
  shasum -a 256 "$1" | awk '{print $1}'
}

valid_file() {
  local path="$1"
  local expected_sha="$2"
  [ -f "$path" ] && [ "$(sha256 "$path")" = "$expected_sha" ]
}

check_installation() {
  [ -s "$MODEL_PATH" ] && [ -s "$TEXT_ENCODER_PATH" ] && [ -s "$VAE_PATH" ]
}

verify_installation() {
  valid_file "$MODEL_PATH" "$MODEL_SHA256" \
    && valid_file "$TEXT_ENCODER_PATH" "$TEXT_ENCODER_SHA256" \
    && valid_file "$VAE_PATH" "$VAE_SHA256"
}

download_verified() {
  local url="$1"
  local destination="$2"
  local expected_sha="$3"
  local partial="$destination.part"

  if valid_file "$destination" "$expected_sha"; then
    info "already installed: $destination"
    return 0
  fi

  mkdir -p "$(dirname "$destination")"
  if [ -f "$destination" ]; then
    mv "$destination" "$destination.invalid-$(date +%s)"
  fi
  info "downloading $(basename "$destination")"
  curl --fail --location --retry 5 --retry-delay 3 --continue-at - --output "$partial" "$url"
  if [ "$(sha256 "$partial")" != "$expected_sha" ]; then
    printf 'ERROR checksum mismatch for %s\n' "$destination" >&2
    return 1
  fi
  mv "$partial" "$destination"
}

if [ "${1:-}" = "--check" ]; then
  check_installation
  exit $?
fi

if [ "${1:-}" = "--verify" ]; then
  verify_installation
  exit $?
fi

if [ ! -f "$COMFYUI_DIR/main.py" ]; then
  printf 'ERROR ComfyUI was not found at %s\n' "$COMFYUI_DIR" >&2
  printf 'Run ./scripts/setup-comfyui.sh first.\n' >&2
  exit 1
fi

download_verified "$MODEL_URL" "$MODEL_PATH" "$MODEL_SHA256"
download_verified "$TEXT_ENCODER_URL" "$TEXT_ENCODER_PATH" "$TEXT_ENCODER_SHA256"
download_verified "$VAE_URL" "$VAE_PATH" "$VAE_SHA256"

if ! verify_installation; then
  printf 'ERROR FLUX.2 Klein installation is incomplete.\n' >&2
  exit 1
fi

if [ "$APPLE_SILICON" = "1" ] && [ -z "${AVENTO_COMFYUI_FLUX2_MODEL:-}" ]; then
  INCOMPATIBLE_FP8_PATH="$COMFYUI_DIR/models/diffusion_models/flux-2-klein-4b-fp8.safetensors"
  if [ -f "$INCOMPATIBLE_FP8_PATH" ]; then
    info "removing incompatible Apple Silicon FP8 checkpoint: $INCOMPATIBLE_FP8_PATH"
    rm -f "$INCOMPATIBLE_FP8_PATH"
  fi
fi

printf '\nFLUX.2 Klein 4B (%s) is ready:\n' "$MODEL_VARIANT"
printf '  Model:        %s\n' "$MODEL_PATH"
printf '  Text encoder: %s\n' "$TEXT_ENCODER_PATH"
printf '  VAE:          %s\n' "$VAE_PATH"
