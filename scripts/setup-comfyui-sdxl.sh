#!/usr/bin/env bash
set -euo pipefail

COMFYUI_DIR="${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}"

CHECKPOINT_NAME="${AVENTO_COMFYUI_SDXL_MODEL:-RealVisXL_V5.0_fp16.safetensors}"
VAE_NAME="${AVENTO_COMFYUI_SDXL_VAE:-sdxl_vae.safetensors}"
OPENPOSE_NAME="${AVENTO_COMFYUI_SDXL_OPENPOSE_MODEL:-xinsir-openpose-sdxl-1.0.safetensors}"
CANNY_NAME="${AVENTO_COMFYUI_SDXL_CANNY_MODEL:-xinsir-canny-sdxl-1.0.safetensors}"
DEPTH_NAME="${AVENTO_COMFYUI_SDXL_DEPTH_MODEL:-xinsir-depth-sdxl-1.0.safetensors}"
CLIP_VISION_NAME="CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors"
IPADAPTER_NAME="ip-adapter-plus_sdxl_vit-h.safetensors"
IPADAPTER_FACE_NAME="ip-adapter-plus-face_sdxl_vit-h.safetensors"
DEPTH_PREPROCESSOR_NAME="depth_anything_v2_vits.pth"

CHECKPOINT_URL="${AVENTO_COMFYUI_SDXL_MODEL_URL:-https://huggingface.co/SG161222/RealVisXL_V5.0/resolve/main/RealVisXL_V5.0_fp16.safetensors}"
VAE_URL="${AVENTO_COMFYUI_SDXL_VAE_URL:-https://huggingface.co/madebyollin/sdxl-vae-fp16-fix/resolve/main/sdxl_vae.safetensors}"
OPENPOSE_URL="${AVENTO_COMFYUI_SDXL_OPENPOSE_MODEL_URL:-https://huggingface.co/xinsir/controlnet-openpose-sdxl-1.0/resolve/main/diffusion_pytorch_model.safetensors}"
CANNY_URL="${AVENTO_COMFYUI_SDXL_CANNY_MODEL_URL:-https://huggingface.co/xinsir/controlnet-canny-sdxl-1.0/resolve/main/diffusion_pytorch_model.safetensors}"
DEPTH_URL="${AVENTO_COMFYUI_SDXL_DEPTH_MODEL_URL:-https://huggingface.co/xinsir/controlnet-depth-sdxl-1.0/resolve/main/diffusion_pytorch_model.safetensors}"
CLIP_VISION_URL="https://huggingface.co/h94/IP-Adapter/resolve/main/models/image_encoder/model.safetensors"
IPADAPTER_URL="https://huggingface.co/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter-plus_sdxl_vit-h.safetensors"
IPADAPTER_FACE_URL="https://huggingface.co/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter-plus-face_sdxl_vit-h.safetensors"
DEPTH_PREPROCESSOR_URL="https://huggingface.co/depth-anything/Depth-Anything-V2-Small/resolve/main/depth_anything_v2_vits.pth"

CHECKPOINT_SHA256="${AVENTO_COMFYUI_SDXL_MODEL_SHA256:-6a35a7855770ae9820a3c931d4964c3817b6d9e3c6f9c4dabb5b3a94e5643b80}"
VAE_SHA256="${AVENTO_COMFYUI_SDXL_VAE_SHA256:-235745af8d86bf4a4c1b5b4f529868b37019a10f7c0b2e79ad0abca3a22bc6e1}"
OPENPOSE_SHA256="${AVENTO_COMFYUI_SDXL_OPENPOSE_MODEL_SHA256:-b8524e557a7df60d081f5d4a0eb109967d107df217943bf88c2d99b9ebcc06c5}"
CANNY_SHA256="${AVENTO_COMFYUI_SDXL_CANNY_MODEL_SHA256:-bf47cd757ceaf2572c53321329ef819ea38c09a6e3783588387913cd94dff47c}"
DEPTH_SHA256="${AVENTO_COMFYUI_SDXL_DEPTH_MODEL_SHA256:-ea3770af4c33ea8ba8dccc0106ec5d5c5c6e504eb382119a3ccac3c9d973d344}"
CLIP_VISION_SHA256="6ca9667da1ca9e0b0f75e46bb030f7e011f44f86cbfb8d5a36590fcd7507b030"
IPADAPTER_SHA256="3f5062b8400c94b7159665b21ba5c62acdcd7682262743d7f2aefedef00e6581"
IPADAPTER_FACE_SHA256="677ad8860204f7d0bfba12d29e6c31ded9beefdf3e4bbd102518357d31a292c1"
DEPTH_PREPROCESSOR_SHA256="715fade13be8f229f8a70cc02066f656f2423a59effd0579197bbf57860e1378"

CHECKPOINT_PATH="$COMFYUI_DIR/models/checkpoints/$CHECKPOINT_NAME"
VAE_PATH="$COMFYUI_DIR/models/vae/$VAE_NAME"
OPENPOSE_PATH="$COMFYUI_DIR/models/controlnet/$OPENPOSE_NAME"
CANNY_PATH="$COMFYUI_DIR/models/controlnet/$CANNY_NAME"
DEPTH_PATH="$COMFYUI_DIR/models/controlnet/$DEPTH_NAME"
CLIP_VISION_PATH="$COMFYUI_DIR/models/clip_vision/$CLIP_VISION_NAME"
IPADAPTER_PATH="$COMFYUI_DIR/models/ipadapter/$IPADAPTER_NAME"
IPADAPTER_FACE_PATH="$COMFYUI_DIR/models/ipadapter/$IPADAPTER_FACE_NAME"
IPADAPTER_NODE_DIR="$COMFYUI_DIR/custom_nodes/comfyui-ipadapter"
DEPTH_PREPROCESSOR_PATH="$COMFYUI_DIR/custom_nodes/comfyui_controlnet_aux/src/custom_controlnet_aux/ckpts/depth-anything/Depth-Anything-V2-Small/$DEPTH_PREPROCESSOR_NAME"

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

check_installation() {
  [ -s "$CHECKPOINT_PATH" ] \
    && [ -s "$VAE_PATH" ] \
    && [ -s "$OPENPOSE_PATH" ] \
    && [ -s "$CANNY_PATH" ] \
    && [ -s "$DEPTH_PATH" ] \
    && [ -s "$CLIP_VISION_PATH" ] \
    && [ -s "$IPADAPTER_PATH" ] \
    && [ -s "$IPADAPTER_FACE_PATH" ] \
    && [ -s "$DEPTH_PREPROCESSOR_PATH" ] \
    && [ -d "$IPADAPTER_NODE_DIR/.git" ]
}

verify_installation() {
  check_installation \
    && valid_file "$CHECKPOINT_PATH" "$CHECKPOINT_SHA256" \
    && valid_file "$VAE_PATH" "$VAE_SHA256" \
    && valid_file "$OPENPOSE_PATH" "$OPENPOSE_SHA256" \
    && valid_file "$CANNY_PATH" "$CANNY_SHA256" \
    && valid_file "$DEPTH_PATH" "$DEPTH_SHA256" \
    && valid_file "$CLIP_VISION_PATH" "$CLIP_VISION_SHA256" \
    && valid_file "$IPADAPTER_PATH" "$IPADAPTER_SHA256" \
    && valid_file "$IPADAPTER_FACE_PATH" "$IPADAPTER_FACE_SHA256" \
    && valid_file "$DEPTH_PREPROCESSOR_PATH" "$DEPTH_PREPROCESSOR_SHA256"
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

if [ ! -d "$IPADAPTER_NODE_DIR/.git" ]; then
  info "installing ComfyUI IP-Adapter node"
  git clone --depth 1 https://github.com/comfyorg/comfyui-ipadapter.git "$IPADAPTER_NODE_DIR"
fi

download_verified "$CHECKPOINT_URL" "$CHECKPOINT_PATH" "$CHECKPOINT_SHA256"
download_verified "$VAE_URL" "$VAE_PATH" "$VAE_SHA256"
download_verified "$OPENPOSE_URL" "$OPENPOSE_PATH" "$OPENPOSE_SHA256"
download_verified "$CANNY_URL" "$CANNY_PATH" "$CANNY_SHA256"
download_verified "$DEPTH_URL" "$DEPTH_PATH" "$DEPTH_SHA256"
download_verified "$CLIP_VISION_URL" "$CLIP_VISION_PATH" "$CLIP_VISION_SHA256"
download_verified "$IPADAPTER_URL" "$IPADAPTER_PATH" "$IPADAPTER_SHA256"
download_verified "$IPADAPTER_FACE_URL" "$IPADAPTER_FACE_PATH" "$IPADAPTER_FACE_SHA256"
download_verified "$DEPTH_PREPROCESSOR_URL" "$DEPTH_PREPROCESSOR_PATH" "$DEPTH_PREPROCESSOR_SHA256"

if ! verify_installation; then
  printf 'ERROR ComfyUI SDXL installation is incomplete.\n' >&2
  exit 1
fi

printf '\nComfyUI SDXL fidelity pipeline is ready:\n'
printf '  Checkpoint: %s\n' "$CHECKPOINT_PATH"
printf '  VAE:        %s\n' "$VAE_PATH"
printf '  ControlNet: %s, %s, %s\n' "$OPENPOSE_PATH" "$CANNY_PATH" "$DEPTH_PATH"
printf '  IP-Adapter: %s, %s\n' "$IPADAPTER_PATH" "$IPADAPTER_FACE_PATH"
