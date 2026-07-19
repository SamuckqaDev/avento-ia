#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-avento-ia}"

export COMPOSE_PROJECT_NAME

info() {
  printf 'INFO %s\n' "$1"
}

fail() {
  printf 'ERROR %s\n' "$1" >&2
  exit 1
}

volume_has_data() {
  local volume="$1"
  local image="$2"

  docker run --rm \
    --user 0:0 \
    --entrypoint sh \
    -v "$volume:/volume:ro" \
    "$image" \
    -c 'test -n "$(find /volume -mindepth 1 -maxdepth 1 -print -quit)"'
}

create_compose_volume() {
  local volume="$1"
  local logical_name="$2"

  docker volume create \
    --label "com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
    --label "com.docker.compose.volume=$logical_name" \
    "$volume" >/dev/null
}

migrate_legacy_container() {
  local container="$1"
  local expected_service="$2"
  local mount_destination="$3"
  local target_volume="$4"
  local logical_volume="$5"

  if ! docker container inspect "$container" >/dev/null 2>&1; then
    return 0
  fi

  local project
  local service
  local source_volume
  local image
  local target_created=0

  project="$(docker container inspect "$container" --format '{{ index .Config.Labels "com.docker.compose.project" }}')"
  service="$(docker container inspect "$container" --format '{{ index .Config.Labels "com.docker.compose.service" }}')"

  if [ "$project" = "$COMPOSE_PROJECT_NAME" ]; then
    return 0
  fi

  if [ "$service" != "$expected_service" ]; then
    fail "container $container belongs to another stack and will not be changed"
  fi

  source_volume="$(docker container inspect "$container" \
    --format "{{ range .Mounts }}{{ if eq .Destination \"$mount_destination\" }}{{ .Name }}{{ end }}{{ end }}")"
  image="$(docker container inspect "$container" --format '{{ .Config.Image }}')"

  if [ -z "$source_volume" ]; then
    fail "container $container has no named volume mounted at $mount_destination"
  fi

  info "found legacy $container container from Compose project $project"
  info "stopping $container before copying its persistent data"
  docker stop "$container" >/dev/null

  if [ "$source_volume" != "$target_volume" ]; then
    if docker volume inspect "$target_volume" >/dev/null 2>&1; then
      if volume_has_data "$target_volume" "$image"; then
        docker start "$container" >/dev/null
        fail "both $source_volume and $target_volume contain data; refusing to choose one automatically"
      fi
    else
      create_compose_volume "$target_volume" "$logical_volume"
      target_created=1
    fi

    info "copying $source_volume to $target_volume"
    if ! docker run --rm \
      --user 0:0 \
      --entrypoint sh \
      -v "$source_volume:/source:ro" \
      -v "$target_volume:/target" \
      "$image" \
      -c 'cp -a /source/. /target/'; then
      if [ "$target_created" = "1" ]; then
        docker volume rm "$target_volume" >/dev/null 2>&1 || true
      fi
      docker start "$container" >/dev/null
      fail "could not migrate $source_volume; the legacy container was restarted"
    fi
  fi

  docker rm "$container" >/dev/null
  info "migrated $container; source volume $source_volume was retained as a backup"
}

cd "$ROOT"

migrate_legacy_container \
  "avento-postgres" \
  "postgres" \
  "/var/lib/postgresql/data" \
  "avento-postgres-data" \
  "avento-postgres-data"

migrate_legacy_container \
  "avento-redis-stack" \
  "redis-stack" \
  "/data" \
  "avento-redis-data" \
  "avento-redis-data"

info "starting Avento PostgreSQL and Redis Stack"
docker compose up -d
