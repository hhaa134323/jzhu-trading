#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${ROOT_DIR}/.run"
LOG_DIR="${ROOT_DIR}/logs"

MARKET_CONTAINER_NAME="jzhu-market-data-service"
INDICATOR_CONTAINER_NAME="jzhu-indicator-service"
BACKTEST_CONTAINER_NAME="jzhu-backtest-service"
WEB_CONTAINER_NAME="jzhu-web-service"
WEBAPP_CONTAINER_NAME="jzhu-web-app"

to_docker_host_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "${p}"
  else
    echo "${p}"
  fi
}

docker_cmd() {
  if [[ "${OSTYPE:-}" == msys* ]] || [[ "${OSTYPE:-}" == cygwin* ]] || [[ -n "${MSYSTEM:-}" ]]; then
    MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"
  else
    docker "$@"
  fi
}

load_env_file() {
  local env_file="${ROOT_DIR}/.env"
  if [[ ! -f "${env_file}" ]]; then
    return
  fi

  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%%$'\r'}"
    [[ -z "${line}" ]] && continue
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    if [[ "${line}" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      export "${BASH_REMATCH[1]}=${BASH_REMATCH[2]}"
    fi
  done <"${env_file}"
}

cleanup_conflicting_containers() {
  if ! command -v docker >/dev/null 2>&1; then
    return
  fi

  local ids
  ids="$(docker_cmd ps --format '{{.ID}} {{.Ports}}' | awk '/:8181->|:8182->|:8183->|:8185->/ {print $1}')"
  if [[ -n "${ids}" ]]; then
    while IFS= read -r id; do
      [[ -z "${id}" ]] && continue
      docker_cmd rm -f "${id}" >/dev/null 2>&1 || true
    done <<<"${ids}"
  fi

  docker_cmd rm -f "${MARKET_CONTAINER_NAME}" "${INDICATOR_CONTAINER_NAME}" "${BACKTEST_CONTAINER_NAME}" "${WEB_CONTAINER_NAME}" "${WEBAPP_CONTAINER_NAME}" >/dev/null 2>&1 || true
}

MARKET_PID_FILE="${RUN_DIR}/market-data-service.pid"
INDICATOR_PID_FILE="${RUN_DIR}/indicator-service.pid"
BACKTEST_PID_FILE="${RUN_DIR}/backtest-service.pid"
WEB_PID_FILE="${RUN_DIR}/web-service.pid"
WEBAPP_PID_FILE="${RUN_DIR}/web-app.pid"

mkdir -p "${RUN_DIR}" "${LOG_DIR}" "${HOME}/.m2"

# If running under MSYS/ Git Bash and JAVA_HOME is set to a Windows path,
# convert it to a Unix-style path so Maven's wrapper works correctly.
if command -v cygpath >/dev/null 2>&1 && [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(cygpath -u "${JAVA_HOME}")"
  export JAVA_HOME
fi

# Detect whether to use local mvn or Docker-based Maven.
# You can force Docker Maven by setting FORCE_DOCKER_MAVEN=1 in the environment.
detect_maven() {
  if [[ "${FORCE_DOCKER:-}" == "1" || "${FORCE_DOCKER:-}" == "true" ]]; then
    USE_LOCAL_MVN=0
    return
  fi

  if [[ "${FORCE_DOCKER_MAVEN:-}" == "1" || "${FORCE_DOCKER_MAVEN:-}" == "true" ]]; then
    USE_LOCAL_MVN=0
    return
  fi

  if command -v mvn >/dev/null 2>&1; then
    if mvn -v >/dev/null 2>&1; then
      USE_LOCAL_MVN=1
    else
      USE_LOCAL_MVN=0
    fi
  else
    USE_LOCAL_MVN=0
  fi
}

print_usage() {
  cat <<EOF
Usage: ./scripts/manage.sh <command>

Commands:
  start      Start all services (TimescaleDB, market-data-service, indicator-service, backtest-service, web-service, web-app)
  stop       Stop all services
  restart    Restart all services
  status     Show current status
  logs       Tail service logs
EOF
}

is_pid_running() {
  local pid="$1"
  [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1
}

start_db() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "[WARN] Docker not found, skip starting TimescaleDB"
    return
  fi

  if docker_cmd ps -a --format '{{.Names}}' | grep -q '^trading-timescaledb$'; then
    docker_cmd start trading-timescaledb >/dev/null 2>&1 || true
  else
    docker_cmd run -d \
      --name trading-timescaledb \
      -p 5432:5432 \
      -e POSTGRES_DB=trading_platform \
      -e POSTGRES_USER=trading \
      -e POSTGRES_PASSWORD=trading123 \
      -v trading-pgdata:/var/lib/postgresql/data \
      timescale/timescaledb:latest-pg16 >/dev/null
  fi

  echo "[OK] TimescaleDB is running"
}

run_maven_service() {
  local module="$1"
  local pid_file="$2"
  local log_file="$3"
  local module_pom_path="${ROOT_DIR}/${module}/pom.xml"
  local extra_ports=()
  local extra_env=()
  local container_name=""

  case "${module}" in
    market-data-service)
      container_name="${MARKET_CONTAINER_NAME}"
      extra_ports=(-p 8182:8182)
      extra_env=(
        -e DB_HOST=host.docker.internal
        -e DB_PORT=5432
        -e DB_NAME=trading_platform
        -e DB_USER=trading
        -e DB_PASSWORD=trading123
        -e FMP_API_KEY="${FMP_API_KEY:-}"
      )
      ;;
    indicator-service)
      container_name="${INDICATOR_CONTAINER_NAME}"
      extra_ports=(-p 8183:8183)
      extra_env=(
        -e DB_HOST=host.docker.internal
        -e DB_PORT=5432
        -e DB_NAME=trading_platform
        -e DB_USER=trading
        -e DB_PASSWORD=trading123
      )
      ;;
    backtest-service)
      container_name="${BACKTEST_CONTAINER_NAME}"
      extra_ports=(-p 8185:8185)
      extra_env=(
        -e DB_HOST=host.docker.internal
        -e DB_PORT=5432
        -e DB_NAME=trading_platform
        -e DB_USER=trading
        -e DB_PASSWORD=trading123
        -e SERVICE_MARKET_DATA_URL=http://host.docker.internal:8182
        -e SERVICE_INDICATOR_URL=http://host.docker.internal:8183
      )
      ;;
    web-service)
      container_name="${WEB_CONTAINER_NAME}"
      extra_ports=(-p 8181:8181)
      extra_env=(
        -e SERVICE_MARKET_DATA_URL=http://host.docker.internal:8182
        -e SERVICE_INDICATOR_URL=http://host.docker.internal:8183
        -e SERVICE_BACKTEST_URL=http://host.docker.internal:8185
      )
      ;;
  esac

  local root_docker_path
  local m2_docker_path
  root_docker_path="$(to_docker_host_path "${ROOT_DIR}")"
  m2_docker_path="$(to_docker_host_path "${HOME}/.m2")"

  if [[ -f "${pid_file}" ]] && is_pid_running "$(cat "${pid_file}")"; then
    echo "[SKIP] ${module} already running (PID $(cat "${pid_file}"))"
    return
  fi

  rm -f "${pid_file}"

  if [[ "${USE_LOCAL_MVN:-0}" == "1" ]]; then
    (
      cd "${ROOT_DIR}"
      nohup mvn -f "${module_pom_path}" spring-boot:run -DskipTests >"${log_file}" 2>&1 &
      echo $! >"${pid_file}"
    )
  elif command -v docker >/dev/null 2>&1; then
    (
      cd "${ROOT_DIR}"
      if [[ -n "${container_name}" ]]; then
        docker_cmd rm -f "${container_name}" >/dev/null 2>&1 || true
      fi
      nohup env MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker run --rm \
        --name "${container_name}" \
        --label jzhu.managed=true \
        --label "jzhu.service=${module}" \
        --add-host host.docker.internal:host-gateway \
        "${extra_ports[@]}" \
        "${extra_env[@]}" \
        -v "${root_docker_path}:/workspace" \
        -v "${m2_docker_path}:/root/.m2" \
        -w /workspace \
        maven:3.9.9-eclipse-temurin-21 \
        mvn -f "/workspace/${module}/pom.xml" spring-boot:run -DskipTests >"${log_file}" 2>&1 &
      echo $! >"${pid_file}"
    )
  else
    echo "[ERROR] Neither mvn nor docker is available. Cannot start ${module}."
    exit 1
  fi

  echo "[OK] ${module} started (PID $(cat "${pid_file}"))"
}

install_shared_modules() {
  local root_docker_path
  local m2_docker_path
  root_docker_path="$(to_docker_host_path "${ROOT_DIR}")"
  m2_docker_path="$(to_docker_host_path "${HOME}/.m2")"

  if [[ "${USE_LOCAL_MVN:-0}" == "1" ]]; then
    (
      cd "${ROOT_DIR}"
      mvn -f "${ROOT_DIR}/pom.xml" -pl trading-common -am install -DskipTests >/dev/null 2>&1
    )
  elif command -v docker >/dev/null 2>&1; then
    docker_cmd run --rm \
      -v "${root_docker_path}:/workspace" \
      -v "${m2_docker_path}:/root/.m2" \
      -w /workspace \
      maven:3.9.9-eclipse-temurin-21 \
      mvn -f /workspace/pom.xml -pl trading-common -am install -DskipTests >/dev/null
  else
    echo "[ERROR] Neither mvn nor docker is available. Cannot install shared modules."
    exit 1
  fi

  echo "[OK] Shared modules installed"
}

start_web_app() {
  local webapp_docker_path
  webapp_docker_path="$(to_docker_host_path "${ROOT_DIR}/web-app")"

  if [[ -f "${WEBAPP_PID_FILE}" ]] && is_pid_running "$(cat "${WEBAPP_PID_FILE}")"; then
    echo "[SKIP] web-app already running (PID $(cat "${WEBAPP_PID_FILE}"))"
    return
  fi

  rm -f "${WEBAPP_PID_FILE}"

  DOCKER_MODE=0
  if [[ "${FORCE_DOCKER:-}" == "1" || "${FORCE_DOCKER:-}" == "true" ]]; then
    DOCKER_MODE=1
  elif ! command -v npm >/dev/null 2>&1; then
    DOCKER_MODE=1
  fi

  if [[ "${DOCKER_MODE}" == "1" ]]; then
    if command -v docker >/dev/null 2>&1; then
      (
        cd "${ROOT_DIR}/web-app"
        docker_cmd rm -f "${WEBAPP_CONTAINER_NAME}" >/dev/null 2>&1 || true
        nohup env MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker run --rm \
          --name "${WEBAPP_CONTAINER_NAME}" \
          --label jzhu.managed=true \
          --label jzhu.service=web-app \
          -p 3000:3000 \
          -v "${webapp_docker_path}:/app" \
          -w /app \
          node:22-alpine \
          sh -c "npm install && npm run dev -- --host 0.0.0.0 --port 3000" >"${LOG_DIR}/web-app.log" 2>&1 &
        echo $! >"${WEBAPP_PID_FILE}"
      )
    else
      echo "[ERROR] Neither npm nor docker is available. Cannot start web-app."
      exit 1
    fi
  else
    (
      cd "${ROOT_DIR}/web-app"
      nohup npm run dev -- --host 0.0.0.0 --port 3000 >"${LOG_DIR}/web-app.log" 2>&1 &
      echo $! >"${WEBAPP_PID_FILE}"
    )
  fi

  echo "[OK] web-app started (PID $(cat "${WEBAPP_PID_FILE}"))"
}

stop_by_pid_file() {
  local name="$1"
  local pid_file="$2"

  if [[ ! -f "${pid_file}" ]]; then
    echo "[SKIP] ${name} not running (no pid file)"
    return
  fi

  local pid
  pid="$(cat "${pid_file}")"
  if is_pid_running "${pid}"; then
    kill "${pid}" >/dev/null 2>&1 || true
    for _ in {1..20}; do
      if ! is_pid_running "${pid}"; then
        break
      fi
      sleep 0.5
    done
    if is_pid_running "${pid}"; then
      kill -9 "${pid}" >/dev/null 2>&1 || true
    fi
    echo "[OK] ${name} stopped"
  else
    echo "[SKIP] ${name} process already exited"
  fi

  rm -f "${pid_file}"
}

stop_db() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "[SKIP] Docker not found, skip stopping TimescaleDB"
    return
  fi

  if docker_cmd ps --format '{{.Names}}' | grep -q '^trading-timescaledb$'; then
    docker_cmd stop trading-timescaledb >/dev/null 2>&1 || true
    echo "[OK] TimescaleDB stopped"
  else
    echo "[SKIP] TimescaleDB is not running"
  fi
}

status_item() {
  local name="$1"
  local pid_file="$2"
  local container_name="${3:-}"
  if [[ -f "${pid_file}" ]] && is_pid_running "$(cat "${pid_file}")"; then
    echo "[RUNNING] ${name} (PID $(cat "${pid_file}"))"
  elif [[ -n "${container_name}" ]] && command -v docker >/dev/null 2>&1 && docker_cmd ps --format '{{.Names}}' | grep -q "^${container_name}$"; then
    echo "[RUNNING] ${name} (container ${container_name})"
  else
    echo "[STOPPED] ${name}"
  fi
}

show_status() {
  status_item "market-data-service" "${MARKET_PID_FILE}" "${MARKET_CONTAINER_NAME}"
  status_item "indicator-service" "${INDICATOR_PID_FILE}" "${INDICATOR_CONTAINER_NAME}"
  status_item "backtest-service" "${BACKTEST_PID_FILE}" "${BACKTEST_CONTAINER_NAME}"
  status_item "web-service" "${WEB_PID_FILE}" "${WEB_CONTAINER_NAME}"
  status_item "web-app" "${WEBAPP_PID_FILE}" "${WEBAPP_CONTAINER_NAME}"

  if command -v docker >/dev/null 2>&1 && docker_cmd ps --format '{{.Names}}' | grep -q '^trading-timescaledb$'; then
    echo "[RUNNING] trading-timescaledb"
  else
    echo "[STOPPED] trading-timescaledb"
  fi
}

show_logs() {
  echo "== market-data-service log =="
  tail -n 60 "${LOG_DIR}/market-data-service.log" 2>/dev/null || true
  echo ""
  echo "== indicator-service log =="
  tail -n 60 "${LOG_DIR}/indicator-service.log" 2>/dev/null || true
  echo ""
  echo "== backtest-service log =="
  tail -n 60 "${LOG_DIR}/backtest-service.log" 2>/dev/null || true
  echo ""
  echo "== web-service log =="
  tail -n 60 "${LOG_DIR}/web-service.log" 2>/dev/null || true
  echo ""
  echo "== web-app log =="
  tail -n 60 "${LOG_DIR}/web-app.log" 2>/dev/null || true
}

wait_for_http_port() {
  local name="$1"
  local url="$2"
  local max_wait_seconds="${3:-90}"
  local waited=0

  while (( waited < max_wait_seconds )); do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' "${url}" || true)"
    if [[ "${code}" != "000" ]]; then
      echo "[OK] ${name} is reachable (${code})"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "[WARN] ${name} is not reachable yet after ${max_wait_seconds}s (${url})"
  return 1
}

start_all() {
  load_env_file
  detect_maven
  cleanup_conflicting_containers
  install_shared_modules
  start_db
  run_maven_service "market-data-service" "${MARKET_PID_FILE}" "${LOG_DIR}/market-data-service.log"
  wait_for_http_port "market-data-service" "http://localhost:8182/" 120 || true
  run_maven_service "indicator-service" "${INDICATOR_PID_FILE}" "${LOG_DIR}/indicator-service.log"
  wait_for_http_port "indicator-service" "http://localhost:8183/" 120 || true
  run_maven_service "backtest-service" "${BACKTEST_PID_FILE}" "${LOG_DIR}/backtest-service.log"
  wait_for_http_port "backtest-service" "http://localhost:8185/" 120 || true
  run_maven_service "web-service" "${WEB_PID_FILE}" "${LOG_DIR}/web-service.log"
  wait_for_http_port "web-service" "http://localhost:8181/" 120 || true
  start_web_app
  wait_for_http_port "web-app" "http://localhost:3000/" 120 || true
  echo "[DONE] All services are started"
}

stop_all() {
  stop_by_pid_file "web-app" "${WEBAPP_PID_FILE}"
  stop_by_pid_file "web-service" "${WEB_PID_FILE}"
  stop_by_pid_file "backtest-service" "${BACKTEST_PID_FILE}"
  stop_by_pid_file "indicator-service" "${INDICATOR_PID_FILE}"
  stop_by_pid_file "market-data-service" "${MARKET_PID_FILE}"
  stop_db
  cleanup_conflicting_containers
  echo "[DONE] All services are stopped"
}

main() {
  local cmd="${1:-}"
  case "${cmd}" in
    start)
      start_all
      ;;
    stop)
      stop_all
      ;;
    restart)
      stop_all
      start_all
      ;;
    status)
      show_status
      ;;
    logs)
      show_logs
      ;;
    *)
      print_usage
      exit 1
      ;;
  esac
}

main "${1:-}"
