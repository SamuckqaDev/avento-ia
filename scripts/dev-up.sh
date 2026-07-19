#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"

export AVENTO_PROJECT_ROOT="$ROOT"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

BACKEND_URL_WAS_SET="${AVENTO_BACKEND_URL+x}"
FRONTEND_URL_WAS_SET="${AVENTO_FRONTEND_URL+x}"
BACKEND_URL="${AVENTO_BACKEND_URL:-http://127.0.0.1:8000}"
FRONTEND_URL="${AVENTO_FRONTEND_URL:-http://127.0.0.1:5173}"
COMFYUI_URL="${AVENTO_COMFYUI_URL:-http://127.0.0.1:8188}"
COMFYUI_DIR="${AVENTO_COMFYUI_DIR:-$HOME/ComfyUI}"
COMFYUI_AUTOSTART="${AVENTO_COMFYUI_AUTOSTART:-1}"
COMFYUI_AUTO_INSTALL="${AVENTO_COMFYUI_AUTO_INSTALL:-1}"
COMFYUI_IMAGE_AUTO_INSTALL="${AVENTO_COMFYUI_IMAGE_AUTO_INSTALL:-1}"
COMFYUI_SDXL_AUTO_INSTALL="${AVENTO_COMFYUI_SDXL_AUTO_INSTALL:-1}"
COMFYUI_FLUX2_AUTO_INSTALL="${AVENTO_COMFYUI_FLUX2_AUTO_INSTALL:-1}"
COMFYUI_VIDEO_AUTO_INSTALL="${AVENTO_COMFYUI_VIDEO_AUTO_INSTALL:-1}"
OLLAMA_URL="${AVENTO_OLLAMA_URL:-http://127.0.0.1:11434}"
OLLAMA_AUTOSTART="${AVENTO_OLLAMA_AUTOSTART:-1}"
NPM_CACHE_DIR="${AVENTO_NPM_CACHE_DIR:-$ROOT/.avento-tools/npm-cache}"
SPRING_PROFILE="${AVENTO_SPRING_PROFILE:-local}"
LOG_DIR="$ROOT/tmp/dev"
COMFYUI_PID=""
OLLAMA_PID=""
APP_LOG_PID=""
DOCKER_LOG_PID=""
CLEANUP_DONE=0

if [[ "$NPM_CACHE_DIR" != /* ]]; then
  NPM_CACHE_DIR="$ROOT/$NPM_CACHE_DIR"
fi
mkdir -p "$LOG_DIR" "$NPM_CACHE_DIR"
export npm_config_cache="$NPM_CACHE_DIR"

warn() {
  printf 'WARN %s\n' "$1"
}

info() {
  printf 'INFO %s\n' "$1"
}

wait_for_url() {
  local url="$1"
  local label="$2"
  local attempts="${3:-60}"

  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      info "$label ready at $url"
      return 0
    fi
    sleep 1
  done

  warn "$label did not become ready at $url"
  return 1
}

port_in_use() {
  local port="$1"
  lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

url_port() {
  node -e 'const url = new URL(process.argv[1]); process.stdout.write(url.port || (url.protocol === "https:" ? "443" : "80"));' "$1"
}

find_free_port() {
  local start="$1"
  local end="$2"
  local port

  for port in $(seq "$start" "$end"); do
    if ! port_in_use "$port"; then
      printf '%s' "$port"
      return 0
    fi
  done

  return 1
}

stop_pid_if_running() {
  local pid="$1"
  local label="$2"

  if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
    info "stopping stale $label process $pid"
    kill "$pid" >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do
      if ! kill -0 "$pid" >/dev/null 2>&1; then
        return 0
      fi
      sleep 0.2
    done
    warn "$label process $pid did not stop gracefully; forcing it"
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi
}

start_terminal_logs() {
  : >"$LOG_DIR/backend.log"
  : >"$LOG_DIR/frontend.log"
  touch "$LOG_DIR/comfyui.log"
  touch "$LOG_DIR/ollama.log"

  info "streaming Docker and application logs in this terminal"
  docker compose logs --tail=20 --follow postgres redis-stack &
  DOCKER_LOG_PID=$!

  tail -n 40 -F \
    "$LOG_DIR/backend.log" \
    "$LOG_DIR/frontend.log" \
    "$LOG_DIR/comfyui.log" \
    "$LOG_DIR/ollama.log" &
  APP_LOG_PID=$!
}

start_ollama() {
  local ollama_host

  if curl -fsS "$OLLAMA_URL/api/tags" >/dev/null 2>&1; then
    info "Ollama is ready at $OLLAMA_URL"
    return 0
  fi

  if [ "$OLLAMA_AUTOSTART" != "1" ]; then
    warn "Ollama is not responding at $OLLAMA_URL and autostart is disabled"
    return 0
  fi

  if ! command -v ollama >/dev/null 2>&1; then
    warn "Ollama command was not found; install Ollama or set AVENTO_OLLAMA_AUTOSTART=0"
    return 1
  fi

  info "starting Ollama at $OLLAMA_URL"
  ollama_host="$(node -e 'const url = new URL(process.argv[1]); process.stdout.write(url.host);' "$OLLAMA_URL")"
  OLLAMA_HOST="$ollama_host" ollama serve >"$LOG_DIR/ollama.log" 2>&1 &
  OLLAMA_PID=$!

  if ! wait_for_url "$OLLAMA_URL/api/tags" "Ollama" 60; then
    warn "Ollama failed to start; see $LOG_DIR/ollama.log"
    return 1
  fi
}

prepare_docker_mcp_gateway() {
  if [ "${AVENTO_MCP_DOCKER_ENABLED:-true}" != "true" ]; then
    info "Docker MCP Gateway disabled (AVENTO_MCP_DOCKER_ENABLED=false)"
    return 0
  fi

  if ! DOCKER_MCP_IN_CONTAINER=1 docker mcp version >/dev/null 2>&1; then
    warn "Docker MCP Gateway plugin is unavailable; the remaining tools will still start"
    return 0
  fi

  info "preparing Docker MCP Gateway for the current Docker/Colima context"
  if ! DOCKER_MCP_IN_CONTAINER=1 docker mcp gateway run \
    --servers docker \
    --transport stdio \
    --block-secrets \
    --dry-run \
    2>&1 | tee "$LOG_DIR/docker-mcp.log"; then
    warn "Docker MCP Gateway preparation failed; see $LOG_DIR/docker-mcp.log"
    return 0
  fi
  info "Docker MCP Gateway is ready"
}

stop_repo_process_on_port() {
  local port="$1"
  local label="$2"
  local pids
  local command

  pids="$(lsof -nP -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  for pid in $pids; do
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [[ "$command" == *"$ROOT"* ]]; then
      stop_pid_if_running "$pid" "$label"
    fi
  done
}

stop_stale_dev_processes() {
  local pids

  # Stop old Avento dev processes from this repo so the user has one clear
  # URL and never accidentally opens an older backend/frontend.
  pids="$(pgrep -f "$ROOT/back/avento/target/classes.*com.avento.AventoApplication" || true)"
  for pid in $pids; do
    stop_pid_if_running "$pid" "backend"
  done

  pids="$(pgrep -f "$ROOT/front/node_modules/.bin/vite" || true)"
  for pid in $pids; do
    stop_pid_if_running "$pid" "frontend"
  done

  stop_repo_process_on_port "${AVENTO_BACKEND_PORT:-8000}" "backend"
  stop_repo_process_on_port "${AVENTO_FRONTEND_PORT:-5173}" "frontend"
}

start_comfyui() {
  if curl -fsS "$COMFYUI_URL/system_stats" >/dev/null 2>&1 \
    || curl -fsS "$COMFYUI_URL/api/system_stats" >/dev/null 2>&1; then
    if "$ROOT/scripts/setup-comfyui-image.sh" --check >/dev/null 2>&1 \
      && ! comfyui_image_nodes_ready; then
      if [ "$COMFYUI_AUTOSTART" != "1" ]; then
        warn "ComfyUI is running without the Avento image nodes"
        warn "Restart ComfyUI manually so DWPose and Impact Pack are loaded"
        return 0
      fi
      local running_pid
      running_pid="$(pgrep -f "$COMFYUI_DIR/main.py.*--port $(url_port "$COMFYUI_URL")" | head -1 || true)"
      if [ -n "$running_pid" ]; then
        info "restarting ComfyUI to load newly installed image nodes"
        stop_pid_if_running "$running_pid" "stale ComfyUI"
      else
        warn "ComfyUI is running without the Avento image nodes and its process could not be identified"
        warn "Restart ComfyUI manually so DWPose and Impact Pack are loaded"
        return 0
      fi
    elif "$ROOT/scripts/setup-comfyui-sdxl.sh" --check >/dev/null 2>&1 \
      && ! comfyui_sdxl_nodes_ready; then
      if [ "$COMFYUI_AUTOSTART" != "1" ]; then
        warn "ComfyUI is running without the Avento SDXL fidelity nodes"
        warn "Restart ComfyUI manually so IP-Adapter and SDXL preprocessors are loaded"
        return 0
      fi
      local running_pid
      running_pid="$(pgrep -f "$COMFYUI_DIR/main.py.*--port $(url_port "$COMFYUI_URL")" | head -1 || true)"
      if [ -n "$running_pid" ]; then
        info "restarting ComfyUI to load the SDXL fidelity nodes"
        stop_pid_if_running "$running_pid" "stale ComfyUI"
      else
        warn "ComfyUI could not be restarted automatically to load the SDXL fidelity nodes"
        return 0
      fi
    elif "$ROOT/scripts/setup-comfyui-flux2.sh" --check >/dev/null 2>&1 \
      && ! comfyui_flux2_models_ready; then
      if [ "$COMFYUI_AUTOSTART" != "1" ]; then
        warn "ComfyUI is running without the newly installed FLUX.2 models"
        warn "Restart ComfyUI manually so the model catalog is refreshed"
        return 0
      fi
      local running_pid
      running_pid="$(pgrep -f "$COMFYUI_DIR/main.py.*--port $(url_port "$COMFYUI_URL")" | head -1 || true)"
      if [ -n "$running_pid" ]; then
        info "restarting ComfyUI to load the FLUX.2 Klein models"
        stop_pid_if_running "$running_pid" "stale ComfyUI"
      else
        warn "ComfyUI could not be restarted automatically to load FLUX.2 Klein"
        return 0
      fi
    else
      info "ComfyUI is ready at $COMFYUI_URL"
      return 0
    fi
  fi

  if [ "$COMFYUI_AUTOSTART" != "1" ]; then
    warn "ComfyUI is not responding at $COMFYUI_URL and autostart is disabled"
    return 0
  fi

  if [ ! -f "$COMFYUI_DIR/main.py" ]; then
    if [ "$COMFYUI_AUTO_INSTALL" = "1" ]; then
      info "ComfyUI was not found; installing it at $COMFYUI_DIR"
      "$ROOT/scripts/setup-comfyui.sh" "$COMFYUI_DIR" >"$LOG_DIR/comfyui-setup.log" 2>&1 || {
        warn "ComfyUI installation failed; see $LOG_DIR/comfyui-setup.log"
        warn "Image and video generation will remain unavailable until ComfyUI is ready"
        return 0
      }
    else
      warn "ComfyUI was not found at $COMFYUI_DIR; image and video generation are unavailable"
      warn "Set AVENTO_COMFYUI_DIR or enable AVENTO_COMFYUI_AUTO_INSTALL=1"
      return 0
    fi
  fi

  local python_command="${AVENTO_COMFYUI_PYTHON:-}"
  if [ -z "$python_command" ]; then
    if [ -x "$COMFYUI_DIR/.venv/bin/python" ]; then
      python_command="$COMFYUI_DIR/.venv/bin/python"
    else
      python_command="python3"
    fi
  fi

  info "starting ComfyUI from $COMFYUI_DIR"
  "$python_command" "$COMFYUI_DIR/main.py" \
    --listen 127.0.0.1 \
    --port "$(url_port "$COMFYUI_URL")" \
    >"$LOG_DIR/comfyui.log" 2>&1 &
  COMFYUI_PID=$!

  for _ in $(seq 1 90); do
    if curl -fsS "$COMFYUI_URL/system_stats" >/dev/null 2>&1 \
      || curl -fsS "$COMFYUI_URL/api/system_stats" >/dev/null 2>&1; then
      info "ComfyUI ready at $COMFYUI_URL"
      return 0
    fi
    sleep 1
  done

  warn "ComfyUI did not become ready; see $LOG_DIR/comfyui.log"
  return 0
}

comfyui_image_nodes_ready() {
  local object_info
  object_info="$(curl -fsS "$COMFYUI_URL/object_info" 2>/dev/null || true)"
  [[ "$object_info" == *'"DWPreprocessor"'* \
    && "$object_info" == *'"FaceDetailer"'* \
    && "$object_info" == *'"UltralyticsDetectorProvider"'* ]]
}

comfyui_sdxl_nodes_ready() {
  local object_info
  object_info="$(curl -fsS "$COMFYUI_URL/object_info" 2>/dev/null || true)"
  [[ "$object_info" == *'"IPAdapterUnifiedLoader"'* \
    && "$object_info" == *'"IPAdapter"'* \
    && "$object_info" == *'"DepthAnythingV2Preprocessor"'* \
    && "$object_info" == *'"CannyEdgePreprocessor"'* ]]
}

comfyui_flux2_models_ready() {
  local diffusion_models
  local expected_model="${AVENTO_COMFYUI_FLUX2_MODEL:-flux-2-klein-4b-fp8.safetensors}"
  local text_encoders
  local vae_models
  if [ -z "${AVENTO_COMFYUI_FLUX2_MODEL:-}" ] \
    && [ "$(uname -s)" = "Darwin" ] \
    && [ "$(uname -m)" = "arm64" ]; then
    expected_model="flux-2-klein-4b.safetensors"
  fi
  diffusion_models="$(curl -fsS "$COMFYUI_URL/models/diffusion_models" 2>/dev/null || true)"
  text_encoders="$(curl -fsS "$COMFYUI_URL/models/text_encoders" 2>/dev/null || true)"
  vae_models="$(curl -fsS "$COMFYUI_URL/models/vae" 2>/dev/null || true)"
  [[ "$diffusion_models" == *"\"$expected_model\""* \
    && "$text_encoders" == *'"qwen_3_4b.safetensors"'* \
    && "$vae_models" == *'"flux2-vae.safetensors"'* ]]
}

ensure_comfyui_video_models() {
  if "$ROOT/scripts/setup-comfyui-video.sh" --check >/dev/null 2>&1; then
    info "ComfyUI WAN 2.2 TI2V video models are ready"
    return 0
  fi

  if [ "$COMFYUI_VIDEO_AUTO_INSTALL" != "1" ]; then
    warn "ComfyUI video models are missing and automatic installation is disabled"
    warn "Run ./scripts/setup-comfyui-video.sh or set AVENTO_COMFYUI_VIDEO_AUTO_INSTALL=1"
    return 0
  fi

  info "installing ComfyUI WAN 2.2 TI2V video models (about 11.4 GB plus shared encoder, one-time download)"
  if ! "$ROOT/scripts/setup-comfyui-video.sh"; then
    warn "ComfyUI video model installation failed; rerun ./scripts/setup-comfyui-video.sh to resume"
    return 0
  fi
}

ensure_comfyui_image_models() {
  if "$ROOT/scripts/setup-comfyui-image.sh" --check >/dev/null 2>&1; then
    info "ComfyUI image refinement models are ready"
    return 0
  fi

  if [ "$COMFYUI_IMAGE_AUTO_INSTALL" != "1" ]; then
    warn "ComfyUI image refinement models are missing and automatic installation is disabled"
    warn "Run ./scripts/setup-comfyui-image.sh or set AVENTO_COMFYUI_IMAGE_AUTO_INSTALL=1"
    return 0
  fi

  info "installing ComfyUI image refinement pipeline (about 4 GB, one-time download)"
  if ! "$ROOT/scripts/setup-comfyui-image.sh" >"$LOG_DIR/comfyui-image-setup.log" 2>&1; then
    warn "ComfyUI image pipeline installation failed; see $LOG_DIR/comfyui-image-setup.log"
    return 0
  fi
}

ensure_comfyui_flux2_models() {
  if "$ROOT/scripts/setup-comfyui-flux2.sh" --check >/dev/null 2>&1; then
    info "ComfyUI FLUX.2 Klein 4B models are ready"
    return 0
  fi

  if [ "$COMFYUI_FLUX2_AUTO_INSTALL" != "1" ]; then
    warn "ComfyUI FLUX.2 Klein models are missing and automatic installation is disabled"
    warn "Run ./scripts/setup-comfyui-flux2.sh or set AVENTO_COMFYUI_FLUX2_AUTO_INSTALL=1"
    return 0
  fi

  info "installing FLUX.2 Klein 4B for this platform (about 11.6-15.6 GB, one-time download)"
  if ! "$ROOT/scripts/setup-comfyui-flux2.sh" 2>&1 | tee "$LOG_DIR/comfyui-flux2-setup.log"; then
    warn "FLUX.2 Klein installation failed; rerun ./scripts/setup-comfyui-flux2.sh to resume"
    return 0
  fi
}

ensure_comfyui_sdxl_models() {
  if "$ROOT/scripts/setup-comfyui-sdxl.sh" --check >/dev/null 2>&1; then
    info "ComfyUI RealVisXL fidelity pipeline is ready"
    return 0
  fi

  if [ "$COMFYUI_SDXL_AUTO_INSTALL" != "1" ]; then
    warn "ComfyUI SDXL fidelity models are missing and automatic installation is disabled"
    warn "Run ./scripts/setup-comfyui-sdxl.sh or set AVENTO_COMFYUI_SDXL_AUTO_INSTALL=1"
    return 0
  fi

  info "installing RealVisXL, SDXL ControlNets and IP-Adapter (about 19 GB, one-time download)"
  if ! "$ROOT/scripts/setup-comfyui-sdxl.sh" 2>&1 | tee "$LOG_DIR/comfyui-sdxl-setup.log"; then
    warn "SDXL fidelity pipeline installation failed; rerun ./scripts/setup-comfyui-sdxl.sh to resume"
    return 0
  fi
}

cleanup() {
  if [ "$CLEANUP_DONE" = "1" ]; then
    return 0
  fi
  CLEANUP_DONE=1
  trap - EXIT INT TERM

  info "stopping Avento dev processes"
  stop_pid_if_running "$APP_LOG_PID" "application log stream"
  stop_pid_if_running "$DOCKER_LOG_PID" "Docker log stream"
  stop_pid_if_running "$COMFYUI_PID" "ComfyUI"
  stop_pid_if_running "$OLLAMA_PID" "Ollama"
  stop_stale_dev_processes
}

trap cleanup EXIT INT TERM

cd "$ROOT"

stop_stale_dev_processes

if [ "${AVENTO_LOCAL_MCP_AUTO_SETUP:-1}" = "1" ]; then
  "$ROOT/scripts/setup-local-mcps.sh"
else
  info "local MCP auto-setup disabled (AVENTO_LOCAL_MCP_AUTO_SETUP=0)"
fi

./scripts/check-local-deps.sh

if ! docker info >/dev/null 2>&1; then
  if command -v colima >/dev/null 2>&1; then
    info "Docker is not responding; starting Colima"
    colima start
  else
    warn "Docker is not responding and Colima is not installed"
    exit 1
  fi
fi

"$ROOT/scripts/prepare-docker-stack.sh"
prepare_docker_mcp_gateway

info "waiting for PostgreSQL through Docker/Colima"
POSTGRES_READY=0
for _ in $(seq 1 60); do
  if docker compose exec -T postgres pg_isready -U "${AVENTO_POSTGRES_USER:-avento}" -d "${AVENTO_POSTGRES_DB:-avento}" >/dev/null 2>&1; then
    info "PostgreSQL is ready at 127.0.0.1:${AVENTO_POSTGRES_PORT:-5432}"
    POSTGRES_READY=1
    break
  fi
  sleep 1
done

if [ "$POSTGRES_READY" != "1" ]; then
  warn "PostgreSQL did not become ready through Docker/Colima"
  exit 1
fi

export AVENTO_DATASOURCE_URL="${AVENTO_DATASOURCE_URL:-jdbc:postgresql://127.0.0.1:${AVENTO_POSTGRES_PORT:-5432}/${AVENTO_POSTGRES_DB:-avento}}"
export AVENTO_DATASOURCE_USERNAME="${AVENTO_DATASOURCE_USERNAME:-${AVENTO_POSTGRES_USER:-avento}}"
export AVENTO_DATASOURCE_PASSWORD="${AVENTO_DATASOURCE_PASSWORD:-${AVENTO_POSTGRES_PASSWORD:-avento_dev_password}}"

AVENTO_AUTH_ROOT_EMAIL="${AVENTO_AUTH_ROOT_EMAIL:-admin@avento.local}"
if [ -z "${AVENTO_AUTH_ROOT_PASSWORD:-}" ]; then
  AVENTO_AUTH_ROOT_PASSWORD="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-20)"
  printf 'AVENTO_AUTH_ROOT_PASSWORD=%s\n' "$AVENTO_AUTH_ROOT_PASSWORD" >>"$ENV_FILE"
  chmod 600 "$ENV_FILE"
  info "generated a local root password and saved it to $ENV_FILE (git-ignored)"
fi
export AVENTO_AUTH_ROOT_EMAIL
export AVENTO_AUTH_ROOT_PASSWORD
export AVENTO_SMOKE_EMAIL="${AVENTO_SMOKE_EMAIL:-$AVENTO_AUTH_ROOT_EMAIL}"
export AVENTO_SMOKE_PASSWORD="$AVENTO_AUTH_ROOT_PASSWORD"
info "local root login: $AVENTO_AUTH_ROOT_EMAIL / $AVENTO_AUTH_ROOT_PASSWORD"

start_ollama

ensure_comfyui_image_models
ensure_comfyui_sdxl_models
ensure_comfyui_flux2_models
ensure_comfyui_video_models
start_comfyui
start_terminal_logs

if [ ! -d "$ROOT/front/node_modules" ]; then
  info "installing frontend dependencies"
  npm --prefix "$ROOT/front" install
fi

BACKEND_PORT="$(url_port "$BACKEND_URL")"
FRONTEND_PORT="$(url_port "$FRONTEND_URL")"
BACKEND_WAS_AUTOFALLBACK=0

if curl -fsS "$BACKEND_URL/api/health" >/dev/null 2>&1; then
  info "using existing healthy backend at $BACKEND_URL"
else
  if port_in_use "$BACKEND_PORT"; then
    if [ -n "$BACKEND_URL_WAS_SET" ]; then
      warn "port $BACKEND_PORT is already in use, but $BACKEND_URL/api/health is not healthy"
      warn "choose another AVENTO_BACKEND_URL or stop the process using that port"
      exit 1
    fi

    BACKEND_PORT="$(find_free_port 18000 18050)"
    BACKEND_URL="http://127.0.0.1:$BACKEND_PORT"
    BACKEND_WAS_AUTOFALLBACK=1
    info "port 8000 is busy with an older backend; using $BACKEND_URL instead"
  fi

  info "starting backend with Spring profile '$SPRING_PROFILE'"
  SPRING_BOOT_ARGS="--server.port=$BACKEND_PORT --server.address=127.0.0.1"
  SPRING_BOOT_ARGS="$SPRING_BOOT_ARGS --spring.datasource.url=$AVENTO_DATASOURCE_URL"
  SPRING_BOOT_ARGS="$SPRING_BOOT_ARGS --spring.datasource.driverClassName=org.postgresql.Driver"
  SPRING_BOOT_ARGS="$SPRING_BOOT_ARGS --spring.datasource.username=$AVENTO_DATASOURCE_USERNAME"
  SPRING_BOOT_ARGS="$SPRING_BOOT_ARGS --spring.datasource.password=$AVENTO_DATASOURCE_PASSWORD"
  SPRING_BOOT_ARGS="$SPRING_BOOT_ARGS --spring.ai.ollama.base-url=$OLLAMA_URL"
  SPRING_BOOT_ARGS="$SPRING_BOOT_ARGS --avento.image.provider=${AVENTO_IMAGE_PROVIDER:-comfyui}"
  mvn -f "$ROOT/back/avento/pom.xml" clean spring-boot:run \
    -Dspring-boot.run.profiles="$SPRING_PROFILE" \
    -Dspring-boot.run.arguments="$SPRING_BOOT_ARGS" \
    >"$LOG_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!

  wait_for_url "$BACKEND_URL/api/health" "backend" 90
fi

if [ "$BACKEND_WAS_AUTOFALLBACK" = "1" ] && [ -z "$FRONTEND_URL_WAS_SET" ]; then
  FRONTEND_PORT="$(find_free_port 5174 5199)"
  FRONTEND_URL="http://127.0.0.1:$FRONTEND_PORT"
fi

if curl -fsS "$FRONTEND_URL" >/dev/null 2>&1; then
  info "using existing frontend at $FRONTEND_URL"
else
  if port_in_use "$FRONTEND_PORT"; then
    if [ -n "$FRONTEND_URL_WAS_SET" ]; then
      warn "port $FRONTEND_PORT is already in use, but $FRONTEND_URL is not responding"
      warn "choose another AVENTO_FRONTEND_URL or stop the process using that port"
      exit 1
    fi

    FRONTEND_PORT="$(find_free_port 5174 5199)"
    FRONTEND_URL="http://127.0.0.1:$FRONTEND_PORT"
    info "port 5173 is busy; using $FRONTEND_URL instead"
  fi

  info "starting frontend"
  AVENTO_BACKEND_URL="$BACKEND_URL" npm --prefix "$ROOT/front" run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" >"$LOG_DIR/frontend.log" 2>&1 &
  FRONTEND_PID=$!

  wait_for_url "$FRONTEND_URL" "frontend" 60
fi

info "running smoke test"
AVENTO_BACKEND_URL="$BACKEND_URL" AVENTO_FRONTEND_URL="$FRONTEND_URL" "$ROOT/scripts/smoke-local.sh"

printf '\nAvento is running:\n'
printf '  Backend:  %s\n' "$BACKEND_URL"
printf '  Frontend: %s\n' "$FRONTEND_URL"
printf '  Logs:     %s\n' "$LOG_DIR"
printf '  Root login: %s / %s\n\n' "$AVENTO_AUTH_ROOT_EMAIL" "$AVENTO_AUTH_ROOT_PASSWORD"
printf 'Live logs are streaming in this terminal. Press Ctrl+C to stop the dev processes started by this script.\n\n'

while true; do
  if ! curl -fsS "$BACKEND_URL/api/health" >/dev/null 2>&1; then
    warn "backend stopped; see $LOG_DIR/backend.log"
    exit 1
  fi
  if ! curl -fsS "$FRONTEND_URL" >/dev/null 2>&1; then
    warn "frontend stopped; see $LOG_DIR/frontend.log"
    exit 1
  fi
  sleep 2
done
