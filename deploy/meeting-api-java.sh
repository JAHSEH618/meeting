#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-api (Java / Spring Boot 3.3) build + deploy script
# ─────────────────────────────────────────────────────────────────────────────
# Companion to deploy/DEPLOY.md §五·五.5.1. Walks through the four ways
# we run meeting-api:
#
#   jar      — fastest verify-and-run loop (no Docker required)
#   image    — build the production Docker image (Linux/macOS/WSL2)
#   compose  — bring up meeting-api inside docker compose with full
#              dependency profile so /actuator/health goes UP
#   k8s      — apply the dev overlay through ./deploy/deploy.sh k8s-dev
#              (canonical K8s path; matches CI and DEPLOY.md §5.6)
#
# Why a separate script: the four flows share preflight checks (JDK
# version, .tool-versions, Flyway migration scripts) but diverge in the
# tools they call. Keeping them in one place avoids the "doc said
# JAVA_HOME 17 but I just typed `java -jar`" trap.
#
# Maven Enforcer pins [17,18). Java 21 / 25 will be rejected. Use asdf /
# mise / rtx with .tool-versions, or export JAVA_HOME=$(/usr/libexec/
# java_home -v 17) on macOS before invoking.
#
# Usage:
#   ./deploy/meeting-api-java.sh test           # mvn verify (full unit + IT)
#   ./deploy/meeting-api-java.sh jar            # mvn package + java -jar
#   ./deploy/meeting-api-java.sh image          # docker build
#   ./deploy/meeting-api-java.sh compose        # bring up via deploy.sh local
#   ./deploy/meeting-api-java.sh k8s [dev|prod] # ./deploy/deploy.sh k8s-<env>
#   ./deploy/meeting-api-java.sh migrate        # Flyway-only migration loop
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_DIR="${REPO_ROOT}/apps/meeting-api"
MIGRATION_DIR="${API_DIR}/meeting-api-infrastructure/src/main/resources/db/migration"
JAR_PATH="${API_DIR}/meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar"

log()  { printf '\033[1;36m▸\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m⚠\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

ensure_java_17() {
    # Honour an existing JAVA_HOME first; only auto-detect when unset.
    if [ -n "${JAVA_HOME:-}" ]; then
        local actual_major
        actual_major="$("${JAVA_HOME}/bin/java" -version 2>&1 \
            | awk -F'"' '/version/ {split($2, a, "."); print a[1]}')"
        if [ "${actual_major}" != "17" ]; then
            err "JAVA_HOME points to Java ${actual_major}; Maven Enforcer requires [17,18)."
            exit 1
        fi
        return
    fi
    if [ "$(uname -s)" = "Darwin" ]; then
        if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
            JAVA_HOME="$(/usr/libexec/java_home -v 17)"
            export JAVA_HOME
            log "Auto-detected JAVA_HOME=${JAVA_HOME}"
            return
        fi
    fi
    if command -v javac >/dev/null 2>&1; then
        local sys_major
        sys_major="$(javac -version 2>&1 | awk '{split($2, a, "."); print a[1]}')"
        if [ "${sys_major}" = "17" ]; then
            log "System javac is Java 17, proceeding without JAVA_HOME override."
            return
        fi
    fi
    err "Cannot find Java 17. Install via brew (macOS): brew install --cask temurin@17"
    err "or via asdf/mise: asdf install java temurin-17.0.16 && asdf local java temurin-17.0.16"
    err "or via apt (Linux): sudo apt-get install -y temurin-17-jdk"
    exit 1
}

run_tests() {
    ensure_java_17
    log "mvn verify (unit + ArchUnit + Testcontainers IT)"
    cd "${API_DIR}"
    ./mvnw verify -q "$@"
}

build_jar() {
    ensure_java_17
    log "mvn package -DskipTests -pl meeting-api-start -am"
    cd "${API_DIR}"
    ./mvnw -pl meeting-api-start -am -DskipTests package -q
    log "Built ${JAR_PATH}"
    if [ "${1:-}" = "--run" ]; then
        log "Starting java -jar ${JAR_PATH} on :8080 — Ctrl-C to stop"
        log "Required env: POSTGRES_HOST, POSTGRES_PORT, RABBITMQ_HOST, AI_WORKER_*. See DEPLOY.md §九."
        exec java -jar "${JAR_PATH}"
    fi
    log "Run with: java -jar ${JAR_PATH}"
    log "Or invoke this script with: $0 jar --run"
}

build_image() {
    command -v docker >/dev/null 2>&1 || { err "docker not in PATH"; exit 1; }
    local tag="${1:-meeting-api:dev}"
    log "docker build → ${tag}"
    # Context is apps/meeting-api/ — the Dockerfile self-contains the
    # multi-module Maven build, so no repo-root context juggling like
    # meeting-web needs.
    docker build -t "${tag}" \
        -f "${API_DIR}/Dockerfile" \
        "${API_DIR}"
    log "Built ${tag}"

    # Apple Silicon producers who need to push amd64 nodes for prod:
    if [ "$(uname -m)" = "arm64" ] && [ "${2:-}" = "--cross" ]; then
        log "docker buildx → linux/amd64 (cross-arch build for prod nodes)"
        docker buildx build --platform linux/amd64 \
            -t "${tag}-amd64" \
            -f "${API_DIR}/Dockerfile" \
            "${API_DIR}"
    fi
}

run_compose() {
    log "Bringing up meeting-api + deps via deploy.sh local"
    log "  → starts postgres/rabbitmq/minio/vault, then meeting-api, then ai-worker"
    log "  → polls /actuator/health/readiness so AiWorkerHealthIndicator cannot"
    log "    deadlock the boot gate (see deploy.sh:222)"
    exec "${REPO_ROOT}/deploy/deploy.sh" local
}

deploy_k8s() {
    local env="${1:-dev}"
    if [ "${env}" != "dev" ] && [ "${env}" != "prod" ]; then
        err "k8s deploy target must be 'dev' or 'prod' (got '${env}')"
        exit 64
    fi
    log "Preflight reminder (DEPLOY.md §5.3.2):"
    log "  1. Bitnami helm upgrade --install postgres / rabbitmq / minio inside meeting-${env}"
    log "  2. kind users: kind load docker-image meeting-api:dev meeting-web:dev ai-worker:dev"
    log "  3. prod overlay needs SealedSecrets/Vault — see §5.7 ProdProfileValidator"
    log
    log "Invoking ./deploy/deploy.sh k8s-${env}"
    exec "${REPO_ROOT}/deploy/deploy.sh" "k8s-${env}"
}

run_migrations() {
    log "Flyway migrations are run by meeting-api on startup."
    log "Three manual paths (DEPLOY.md §六):"
    log "  1) Restart Pod / container (rollout restart deployment/meeting-api)"
    log "  2) Flyway Docker CLI:"
    cat <<EOF
       docker run --rm -v "${MIGRATION_DIR}:/flyway/sql" \\
         flyway/flyway:10 \\
         -url=jdbc:postgresql://host.docker.internal:5432/meeting \\
         -user=meeting -password=meeting_dev \\
         -baselineOnMigrate=false migrate
EOF
    log "  3) psql ON_ERROR_STOP loop:"
    cat <<EOF
       ls ${MIGRATION_DIR}/V*.sql | sort | xargs -I{} \\
         psql -h localhost -U meeting -d meeting -v ON_ERROR_STOP=1 -f {}
EOF
    log
    log "ddl-check CI job (.github/workflows/ci.yml) replays exactly path 3."
}

main() {
    case "${1:-}" in
        test)     shift; run_tests "$@" ;;
        jar)      shift; build_jar "$@" ;;
        image)    shift; build_image "$@" ;;
        compose)  run_compose ;;
        k8s)      shift; deploy_k8s "$@" ;;
        migrate)  run_migrations ;;
        *)
            cat <<EOF
Usage: $0 <command> [args]

Commands:
  test                          mvn verify -q (CI command)
  jar [--run]                   mvn package -pl meeting-api-start; optionally java -jar
  image [tag] [--cross]         docker build (apple silicon --cross adds linux/amd64)
  compose                       ./deploy/deploy.sh local (full local stack)
  k8s [dev|prod]                ./deploy/deploy.sh k8s-<env> (canonical K8s deploy)
  migrate                       Print Flyway migration recipes (auto / docker / psql)

Toolchain requirements (.tool-versions):
  java 17.0.16   (Maven Enforcer [17,18))
  nodejs 20.18.0 (codegen / contracts)
  python 3.11.9  (ai-worker, not used by this script)

Prod profile (SPRING_PROFILES_ACTIVE=prod) gates checked at boot:
  - AI_WORKER_CALLBACK_HMAC_SECRET / AI_WORKER_INTERNAL_API_HMAC_SECRET
    must both be non-demo and different
  - AI_WORKER_BASE_URL cannot contain localhost/127.0.0.1
  - KMS_MASTER_KEY_ID cannot be 'dev-kms-master-key'
  - MEETING_KMS_MASTER_KEY_BASE64 must be set (or use a cloud KMS)
  - MEETING_TENANTS_ACTIVE must list at least one tenant
  - SPRING_FLYWAY_BASELINE_ON_MIGRATE must be false
  Run \`./deploy/meeting-api-java.sh test\` for the full IT suite.
EOF
            exit 64
            ;;
    esac
}

main "$@"
