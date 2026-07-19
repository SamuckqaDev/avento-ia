#!/usr/bin/env bash
set -euo pipefail

COMFYUI_DIR="${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}"
CHECKPOINT_NAME="${AVENTO_COMFYUI_DEFAULT_MODEL:-Realistic_Vision_V6.0_NV_B1_fp16.safetensors}"
VAE_NAME="${AVENTO_COMFYUI_IMAGE_VAE:-vae-ft-mse-840000-ema-pruned.safetensors}"
OPENPOSE_NAME="${AVENTO_COMFYUI_OPENPOSE_MODEL:-control_v11p_sd15_openpose.pth}"

CHECKPOINT_URL="${AVENTO_COMFYUI_IMAGE_MODEL_URL:-https://huggingface.co/SG161222/Realistic_Vision_V6.0_B1_noVAE/resolve/main/Realistic_Vision_V6.0_NV_B1_fp16.safetensors}"
VAE_URL="${AVENTO_COMFYUI_IMAGE_VAE_URL:-https://huggingface.co/stabilityai/sd-vae-ft-mse-original/resolve/main/vae-ft-mse-840000-ema-pruned.safetensors}"
OPENPOSE_URL="${AVENTO_COMFYUI_OPENPOSE_MODEL_URL:-https://huggingface.co/lllyasviel/ControlNet-v1-1/resolve/main/control_v11p_sd15_openpose.pth}"

CHECKPOINT_SHA256="${AVENTO_COMFYUI_IMAGE_MODEL_SHA256:-c48bfd159cd7a6507b128685e963c398fa72399cefafaf603781df50ce836cc7}"
VAE_SHA256="${AVENTO_COMFYUI_IMAGE_VAE_SHA256:-735e4c3a447a3255760d7f86845f09f937809baa529c17370d83e4c3758f3c75}"
OPENPOSE_SHA256="${AVENTO_COMFYUI_OPENPOSE_MODEL_SHA256:-db97becd92cd19aff71352a60e93c2508decba3dee64f01f686727b9b406a9dd}"

CHECKPOINT_PATH="$COMFYUI_DIR/models/checkpoints/$CHECKPOINT_NAME"
VAE_PATH="$COMFYUI_DIR/models/vae/$VAE_NAME"
OPENPOSE_PATH="$COMFYUI_DIR/models/controlnet/$OPENPOSE_NAME"
FACE_DETECTOR_PATH="$COMFYUI_DIR/models/ultralytics/bbox/face_yolov8m.pt"
HAND_DETECTOR_PATH="$COMFYUI_DIR/models/ultralytics/bbox/hand_yolov8s.pt"

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
  [ -s "$CHECKPOINT_PATH" ] \
    && [ -s "$VAE_PATH" ] \
    && [ -s "$OPENPOSE_PATH" ] \
    && [ -f "$FACE_DETECTOR_PATH" ] \
    && [ -f "$HAND_DETECTOR_PATH" ] \
    && [ -d "$COMFYUI_DIR/custom_nodes/comfyui_controlnet_aux/.git" ] \
    && [ -d "$COMFYUI_DIR/custom_nodes/ComfyUI-Impact-Pack/.git" ] \
    && [ -d "$COMFYUI_DIR/custom_nodes/ComfyUI-Impact-Subpack/.git" ]
}

verify_installation() {
  check_installation \
    && valid_file "$CHECKPOINT_PATH" "$CHECKPOINT_SHA256" \
    && valid_file "$VAE_PATH" "$VAE_SHA256" \
    && valid_file "$OPENPOSE_PATH" "$OPENPOSE_SHA256"
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

clone_node() {
  local repository="$1"
  local directory="$2"
  if [ -d "$directory/.git" ]; then
    info "custom node ready: $(basename "$directory")"
    return 0
  fi
  mkdir -p "$(dirname "$directory")"
  git clone --depth 1 "$repository" "$directory"
}

install_requirements() {
  local python="$COMFYUI_DIR/.venv/bin/python"
  local control_aux="$COMFYUI_DIR/custom_nodes/comfyui_controlnet_aux"
  local impact="$COMFYUI_DIR/custom_nodes/ComfyUI-Impact-Pack"
  local impact_subpack="$COMFYUI_DIR/custom_nodes/ComfyUI-Impact-Subpack"

  if [ ! -x "$python" ]; then
    printf 'ERROR ComfyUI virtual environment was not found at %s\n' "$python" >&2
    printf 'Run ./scripts/setup-comfyui.sh first.\n' >&2
    return 1
  fi

  info "installing ControlNet auxiliary dependencies"
  if [ "$(uname -s)" = "Darwin" ]; then
    grep -v '^onnxruntime-gpu' "$control_aux/requirements.txt" | xargs "$python" -m pip install
    "$python" -m pip install onnxruntime
  else
    "$python" -m pip install -r "$control_aux/requirements.txt"
  fi

  info "installing Impact Pack dependencies"
  "$python" -m pip install -r "$impact/requirements.txt"
  "$python" -m pip install -r "$impact_subpack/requirements.txt"
  COMFYUI_PATH="$COMFYUI_DIR" COMFYUI_MODEL_PATH="$COMFYUI_DIR/models" \
    "$python" "$impact/install.py"
  COMFYUI_PATH="$COMFYUI_DIR" COMFYUI_MODEL_PATH="$COMFYUI_DIR/models" \
    "$python" "$impact_subpack/install.py"
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

clone_node "https://github.com/Fannovel16/comfyui_controlnet_aux.git" \
  "$COMFYUI_DIR/custom_nodes/comfyui_controlnet_aux"
clone_node "https://github.com/ltdrdata/ComfyUI-Impact-Pack.git" \
  "$COMFYUI_DIR/custom_nodes/ComfyUI-Impact-Pack"
clone_node "https://github.com/ltdrdata/ComfyUI-Impact-Subpack.git" \
  "$COMFYUI_DIR/custom_nodes/ComfyUI-Impact-Subpack"

install_requirements
download_verified "$CHECKPOINT_URL" "$CHECKPOINT_PATH" "$CHECKPOINT_SHA256"
download_verified "$VAE_URL" "$VAE_PATH" "$VAE_SHA256"
download_verified "$OPENPOSE_URL" "$OPENPOSE_PATH" "$OPENPOSE_SHA256"

if ! verify_installation; then
  printf 'ERROR ComfyUI image pipeline installation is incomplete.\n' >&2
  exit 1
fi

printf '\nComfyUI image pipeline is ready:\n'
printf '  Checkpoint: %s\n' "$CHECKPOINT_PATH"
printf '  VAE:        %s\n' "$VAE_PATH"
printf '  OpenPose:   %s\n' "$OPENPOSE_PATH"
printf '  Detailers:  %s, %s\n' "$FACE_DETECTOR_PATH" "$HAND_DETECTOR_PATH"
