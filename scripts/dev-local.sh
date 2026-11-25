#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce Local Development Bootstrap & Lifecycle Manager
# Subcommands: up | down | reset | status | env | start-core | stop-core | smoke
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LOCAL_DIR="${REPO_ROOT}/.local-dev"
JWT_DIR="${LOCAL_DIR}/jwt"
PID_DIR="${LOCAL_DIR}/pids"
LOG_DIR="${LOCAL_DIR}/logs"
ENV_FILE="${LOCAL_DIR}/.env.local"
ENV_TEMPLATE="${REPO_ROOT}/.env.local.example"
PRIVATE_KEY_FILE="${JWT_DIR}/private.pkcs8.pem"
PUBLIC_KEY_FILE="${JWT_DIR}/public.pem"

WAIT_TIMEOUT_SECS="${DEV_LOCAL_WAIT_SECS:-120}"

DEFAULT_CORE_SERVICES=(
    "identity-service:8081"
    "catalog-service:8082"
    "inventory-service:8083"
    "order-service:8084"
    "payment-service:8085"
    "pricing-service:8089"
    "cart-service:8090"
    "checkout-orchestrator-service:8091"
    "mobile-bff-service:8080"
    "admin-gateway-service:8099"
)

log_info() { echo "[dev-local] $*"; }
log_warn() { echo "[dev-local] WARN: $*" >&2; }
log_err() { echo "[dev-local] ERROR: $*" >&2; }

require_cmd() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        log_err "Required command '$cmd' not found on PATH."
        exit 1
    fi
}

ensure_docker() {
    require_cmd docker
    if ! docker info >/dev/null 2>&1; then
        log_err "Docker daemon is not running. Start Docker Desktop / Engine and retry."
        exit 1
    fi
}

ensure_env() {
    require_cmd openssl
    require_cmd python3
    if [ ! -f "$ENV_TEMPLATE" ]; then
        log_err "Template $ENV_TEMPLATE not found."
        exit 1
    fi

    mkdir -p "$JWT_DIR" "$PID_DIR" "$LOG_DIR"
    chmod 700 "$LOCAL_DIR" "$JWT_DIR"

    if [ ! -f "$PRIVATE_KEY_FILE" ] || [ ! -f "$PUBLIC_KEY_FILE" ]; then
        log_info "Generating ephemeral RSA-2048 JWT keypair in ${JWT_DIR}..."
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY_FILE" >/dev/null 2>&1
        chmod 600 "$PRIVATE_KEY_FILE"
        openssl pkey -in "$PRIVATE_KEY_FILE" -pubout -out "$PUBLIC_KEY_FILE" >/dev/null 2>&1
        chmod 644 "$PUBLIC_KEY_FILE"
    fi

    python3 - "$ENV_TEMPLATE" "$PRIVATE_KEY_FILE" "$PUBLIC_KEY_FILE" "$ENV_FILE" <<'PY'
import pathlib
import sys

template_path, priv_path, pub_path, out_path = [pathlib.Path(p) for p in sys.argv[1:5]]

def to_single_line_der_b64(pem_text: str) -> str:
    return "".join(
        line.strip()
        for line in pem_text.splitlines()
        if line.strip() and not line.strip().startswith("-----")
    )

priv_single = to_single_line_der_b64(priv_path.read_text(encoding="utf-8"))
pub_single = to_single_line_der_b64(pub_path.read_text(encoding="utf-8"))

rendered = (
    template_path.read_text(encoding="utf-8")
    .replace("__REPLACE_WITH_LOCAL_PKCS8_PRIVATE_KEY__", priv_single)
    .replace("__REPLACE_WITH_LOCAL_X509_PUBLIC_KEY__", pub_single)
)
out_path.write_text(rendered, encoding="utf-8")
PY
    chmod 600 "$ENV_FILE"
    log_info "Local environment file ready at ${ENV_FILE}."
}

load_env() {
    ensure_env
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
}

wait_for_service() {
    local name="$1"
    local check_cmd="$2"
    local elapsed=0
    log_info "Waiting for ${name}..."
    while ! eval "$check_cmd" >/dev/null 2>&1; do
        if [ "$elapsed" -ge "$WAIT_TIMEOUT_SECS" ]; then
            log_err "Timed out waiting for ${name} after ${WAIT_TIMEOUT_SECS}s."
            docker compose logs --tail=40 "$name" >&2 || true
            exit 1
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    log_info "${name} is healthy."
}

ensure_postgres_roles_and_dbs() {
    log_info "Ensuring PostgreSQL local roles and databases exist..."
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL' >/dev/null
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'reconciliation') THEN
        CREATE ROLE reconciliation WITH LOGIN PASSWORD 'devpass';
    END IF;
END $$;
SQL

    local db
    local dbs=(
        identity_db orders order_db carts cart_db payments payment_db checkout checkout_db
        catalog catalog_db inventory inventory_db pricing pricing_db fulfillment fulfillment_db
        warehouse warehouse_db rider_fleet rider_db routing routing_db search search_db
        notification notification_db wallets wallet_db audit_db audit fraud fraud_db
        feature_flags_db config_db reconciliation reconciliation_db reverse_etl reverse_etl_db
    )
    for db in "${dbs[@]}"; do
        if ! docker compose exec -T postgres psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${db}'" | grep -q 1; then
            if [ "$db" = "reconciliation" ] || [ "$db" = "reconciliation_db" ]; then
                docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -c "CREATE DATABASE \"${db}\" OWNER reconciliation;" >/dev/null
            else
                docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -c "CREATE DATABASE \"${db}\";" >/dev/null
            fi
        fi
    done
    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres -c "ALTER DATABASE reconciliation OWNER TO reconciliation; ALTER DATABASE reconciliation_db OWNER TO reconciliation;" >/dev/null
}

apply_sql_dir() {
    local target_db="$1"
    local migration_dir="$2"
    [ -d "$migration_dir" ] || return 0

    local sql_files=()
    while IFS= read -r file; do
        sql_files+=("$file")
    done < <(find "$migration_dir" -maxdepth 1 -type f -name '*.sql' | sort)
    [ ${#sql_files[@]} -gt 0 ] || return 0

    docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$target_db" <<'SQL' >/dev/null
CREATE TABLE IF NOT EXISTS _dev_local_migrations (
    filename TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
SQL

    local file base applied
    for file in "${sql_files[@]}"; do
        base="$(basename "$file")"
        applied="$(docker compose exec -T postgres psql -U postgres -d "$target_db" -tAc "SELECT 1 FROM _dev_local_migrations WHERE filename = '${base}'")"
        if [ "$applied" != "1" ]; then
            log_info "Applying SQL migration ${target_db}: ${base}"
            docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$target_db" < "$file" >/dev/null
            docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d "$target_db" -c "INSERT INTO _dev_local_migrations(filename) VALUES ('${base}') ON CONFLICT DO NOTHING;" >/dev/null
        fi
    done
}

apply_go_service_schemas() {
    apply_sql_dir "reconciliation" "${REPO_ROOT}/services/reconciliation-engine/src/main/resources/db/migration"
    apply_sql_dir "reconciliation_db" "${REPO_ROOT}/services/reconciliation-engine/src/main/resources/db/migration"

    local retl_dir
    for retl_dir in \
        "${REPO_ROOT}/services/reverse-etl-orchestrator/db/migration" \
        "${REPO_ROOT}/services/reverse-etl-orchestrator/migrations"; do
        if [ -d "$retl_dir" ]; then
            apply_sql_dir "reverse_etl" "$retl_dir"
            apply_sql_dir "reverse_etl_db" "$retl_dir"
        fi
    done
}

ensure_temporal_namespaces() {
    local ns
    for ns in instacommerce reverse-etl; do
        if ! docker compose exec -T temporal temporal operator namespace describe --address temporal:7233 --namespace "$ns" >/dev/null 2>&1; then
            log_info "Creating Temporal namespace '${ns}'..."
            docker compose exec -T temporal temporal operator namespace create --address temporal:7233 --namespace "$ns" >/dev/null
        fi
    done
}

cmd_up() {
    ensure_docker
    ensure_env

    log_info "Starting local infrastructure stack via docker compose..."
    docker compose up -d

    wait_for_service "postgres" "docker compose exec -T postgres pg_isready -U postgres -d postgres"
    wait_for_service "redis" "docker compose exec -T redis redis-cli ping | grep -q PONG"
    wait_for_service "kafka" "docker compose exec -T kafka kafka-broker-api-versions --bootstrap-server localhost:9092"
    wait_for_service "temporal" "docker compose exec -T temporal temporal operator cluster health --address localhost:7233"

    ensure_postgres_roles_and_dbs
    apply_go_service_schemas
    ensure_temporal_namespaces

    log_info "Local infrastructure is up and initialized."
    log_info "Load env with: set -a && source .local-dev/.env.local && set +a"
}

cmd_stop_core() {
    mkdir -p "$PID_DIR"
    local pid_file svc pid
    local stopped=0
    for pid_file in "${PID_DIR}"/*.pid; do
        [ -e "$pid_file" ] || continue
        svc="$(basename "$pid_file" .pid)"
        pid="$(cat "$pid_file")"
        if [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1; then
            log_info "Stopping ${svc} (pid=${pid})..."
            kill -TERM "$pid" >/dev/null 2>&1 || true
            local waited=0
            while kill -0 "$pid" >/dev/null 2>&1 && [ "$waited" -lt 10 ]; do
                sleep 1
                waited=$((waited + 1))
            done
            if kill -0 "$pid" >/dev/null 2>&1; then
                log_warn "Force killing ${svc} (pid=${pid})"
                kill -KILL "$pid" >/dev/null 2>&1 || true
            fi
            stopped=$((stopped + 1))
        fi
        rm -f "$pid_file"
    done
    if [ "$stopped" -eq 0 ]; then
        log_info "No tracked core services running."
    fi
}

cmd_down() {
    cmd_stop_core
    ensure_docker
    log_info "Stopping docker compose stack..."
    docker compose down
}

cmd_reset() {
    cmd_stop_core
    ensure_docker
    log_info "Resetting docker compose stack and volumes..."
    docker compose down -v
    rm -rf "$PID_DIR" "$LOG_DIR"
    cmd_up
}

resolve_core_specs() {
    if [ "$#" -gt 0 ]; then
        printf '%s\n' "$@"
        return
    fi
    if [ -n "${CORE_SERVICES:-}" ]; then
        local item
        for item in $CORE_SERVICES; do
            printf '%s\n' "$item"
        done
        return
    fi
    printf '%s\n' "${DEFAULT_CORE_SERVICES[@]}"
}

service_port_for() {
    local svc="$1"
    local entry
    for entry in "${DEFAULT_CORE_SERVICES[@]}"; do
        if [ "${entry%%:*}" = "$svc" ]; then
            echo "${entry##*:}"
            return
        fi
    done
    grep -E "^\| \`${svc}\` \|" "${REPO_ROOT}/GLOSSARY.md" | sed -E 's/^\| `[^`]+` \| [^|]+ \| ([0-9]+) \|.*/\1/' | head -n 1
}

cmd_start_core() {
    ensure_docker
    load_env
    require_cmd java

    if ! docker compose exec -T postgres pg_isready -U postgres -d postgres >/dev/null 2>&1; then
        log_err "Postgres is not healthy. Run './scripts/dev-local.sh up' first."
        exit 1
    fi

    local specs=()
    while IFS= read -r spec; do
        [ -n "$spec" ] && specs+=("$spec")
    done < <(resolve_core_specs "$@")

    local gradle_tasks=(":contracts:jar")
    local services=()
    local entry svc port
    for entry in "${specs[@]}"; do
        svc="${entry%%:*}"
        services+=("$svc")
        gradle_tasks+=(":services:${svc}:bootJar")
    done

    log_info "Building bootJars for core services: ${services[*]}"
    ./gradlew "${gradle_tasks[@]}" --no-daemon --console=plain

    mkdir -p "$PID_DIR" "$LOG_DIR"
    for entry in "${specs[@]}"; do
        svc="${entry%%:*}"
        if [[ "$entry" == *:* ]]; then
            port="${entry##*:}"
        else
            port="$(service_port_for "$svc")"
        fi

        local pid_file="${PID_DIR}/${svc}.pid"
        if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" >/dev/null 2>&1; then
            log_info "${svc} already running (pid=$(cat "$pid_file"))."
            continue
        fi

        local jar_path
        jar_path="$(find "${REPO_ROOT}/services/${svc}/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)"
        if [ -z "$jar_path" ]; then
            log_err "No bootJar found for ${svc} in services/${svc}/build/libs"
            exit 1
        fi

        log_info "Starting ${svc} on port ${port}..."
        nohup java -Xms128m -Xmx512m -jar "$jar_path" --server.port="$port" > "${LOG_DIR}/${svc}.log" 2>&1 &
        echo $! > "$pid_file"
    done

    for entry in "${specs[@]}"; do
        svc="${entry%%:*}"
        if [[ "$entry" == *:* ]]; then
            port="${entry##*:}"
        else
            port="$(service_port_for "$svc")"
        fi
        local pid
        pid="$(cat "${PID_DIR}/${svc}.pid")"
        local elapsed=0
        while ! curl -fsS "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; do
            if ! kill -0 "$pid" >/dev/null 2>&1; then
                log_err "${svc} exited during startup. Last logs:"
                tail -n 40 "${LOG_DIR}/${svc}.log" >&2 || true
                exit 1
            fi
            if [ "$elapsed" -ge "$WAIT_TIMEOUT_SECS" ]; then
                log_err "Timed out waiting for ${svc} readiness on port ${port}."
                tail -n 40 "${LOG_DIR}/${svc}.log" >&2 || true
                exit 1
            fi
            sleep 2
            elapsed=$((elapsed + 2))
        done
        log_info "${svc} ready on http://localhost:${port}"
    done
}

cmd_env() {
    ensure_env
    if [ "${1:-}" = "--export" ]; then
        cat "$ENV_FILE"
    else
        echo "Generated local env: ${ENV_FILE}"
        echo "Generated RSA-2048 keys: ${PRIVATE_KEY_FILE}, ${PUBLIC_KEY_FILE}"
        echo "Run: set -a && source ${ENV_FILE} && set +a"
    fi
}

cmd_status() {
    echo "=== Local Environment ==="
    if [ -f "$ENV_FILE" ]; then
        echo "  env file:  present (${ENV_FILE})"
    else
        echo "  env file:  missing (run ./scripts/dev-local.sh up)"
    fi
    if [ -f "$PRIVATE_KEY_FILE" ] && [ -f "$PUBLIC_KEY_FILE" ]; then
        echo "  jwt keys:  present (${JWT_DIR})"
    else
        echo "  jwt keys:  missing"
    fi

    echo ""
    echo "=== Docker Compose Infrastructure ==="
    if docker info >/dev/null 2>&1; then
        docker compose ps || true
    else
        echo "  Docker daemon not running."
    fi

    echo ""
    echo "=== Tracked Core Services ==="
    local any=0 pid_file svc pid port
    mkdir -p "$PID_DIR"
    for pid_file in "${PID_DIR}"/*.pid; do
        [ -e "$pid_file" ] || continue
        any=1
        svc="$(basename "$pid_file" .pid)"
        pid="$(cat "$pid_file")"
        port="$(service_port_for "$svc")"
        if kill -0 "$pid" >/dev/null 2>&1; then
            if [ -n "$port" ] && curl -fsS "http://localhost:${port}/actuator/health/readiness" >/dev/null 2>&1; then
                echo "  ${svc}: RUNNING (pid=${pid}, port=${port}, ready=yes)"
            else
                echo "  ${svc}: RUNNING (pid=${pid}, port=${port:-?}, ready=no)"
            fi
        else
            echo "  ${svc}: DEAD (stale pid=${pid})"
        fi
    done
    [ "$any" -eq 1 ] || echo "  (none running)"
}

cmd_smoke() {
    ensure_docker
    load_env

    log_info "Running infrastructure smoke checks..."
    docker compose exec -T postgres pg_isready -U postgres -d postgres >/dev/null
    docker compose exec -T postgres psql -U reconciliation -d reconciliation -c "SELECT 1;" >/dev/null
    docker compose exec -T redis redis-cli ping | grep -q PONG
    "${REPO_ROOT}/scripts/test-local-kafka.sh"
    docker compose exec -T temporal temporal operator namespace describe --address temporal:7233 --namespace instacommerce >/dev/null
    docker compose exec -T temporal temporal operator namespace describe --address temporal:7233 --namespace reverse-etl >/dev/null

    local pid_file svc pid port
    mkdir -p "$PID_DIR"
    for pid_file in "${PID_DIR}"/*.pid; do
        [ -e "$pid_file" ] || continue
        svc="$(basename "$pid_file" .pid)"
        pid="$(cat "$pid_file")"
        port="$(service_port_for "$svc")"
        if kill -0 "$pid" >/dev/null 2>&1 && [ -n "$port" ]; then
            curl -fsS "http://localhost:${port}/actuator/health/readiness" >/dev/null
            log_info "Healthcheck PASS: ${svc} (:${port})"
        fi
    done

    log_info "Local smoke verification PASSED."
}

usage() {
    cat <<'EOF'
Usage: ./scripts/dev-local.sh <command> [args...]

Commands:
  up           Generate ephemeral JWT keys + .env.local, start Docker infra, wait for health, create Temporal namespaces & Go schemas
  down         Stop tracked core services and stop Docker infrastructure
  reset        Stop services, wipe Docker volumes (pgdata), and re-run 'up'
  status       Display env, Docker Compose, and tracked service health status
  env          Generate .local-dev/.env.local & keys if missing (pass --export to print file contents)
  start-core   Build bootJars and start core Java services (or specific service[:port] args)
  stop-core    Stop tracked core services started by start-core
  smoke        Verify Postgres, Redis, Kafka dual listeners, Temporal namespaces, and running core services
EOF
}

cmd="${1:-}"
if [ -z "$cmd" ]; then
    usage
    exit 1
fi
shift

case "$cmd" in
    up) cmd_up "$@" ;;
    down) cmd_down "$@" ;;
    reset) cmd_reset "$@" ;;
    status) cmd_status "$@" ;;
    env) cmd_env "$@" ;;
    start-core) cmd_start_core "$@" ;;
    stop-core) cmd_stop_core "$@" ;;
    smoke) cmd_smoke "$@" ;;
    -h|--help|help) usage ;;
    *)
        log_err "Unknown command: $cmd"
        usage
        exit 1
        ;;
esac
