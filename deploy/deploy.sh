#!/usr/bin/env bash
# ==============================================================================
# 本地会议智能系统 · 一键部署脚本
# 用法：
#   ./deploy/deploy.sh local              # 本地开发环境 (Docker Compose)
#   ./deploy/deploy.sh build              # 构建所有 Docker 镜像
#   ./deploy/deploy.sh push               # 推送镜像到仓库
#   ./deploy/deploy.sh k8s-dev            # 部署到 K8s dev 环境
#   ./deploy/deploy.sh k8s-prod           # 部署到 K8s prod 环境
#   ./deploy/deploy.sh terraform-plan     # Terraform 基础架构规划
#   ./deploy/deploy.sh terraform-apply    # Terraform 创建云资源
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEPLOY_DIR="${REPO_ROOT}/deploy"
INFRA_DIR="${REPO_ROOT}/infra/meeting-infra"

# ── 颜色输出 ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "\n${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"; }
log_step_title() { echo -e "${GREEN}>>>${NC} $*"; }

# ── 依赖检查 ──────────────────────────────────────────────────────────────────
check_dependency() {
    local cmd="$1"
    local name="${2:-$cmd}"
    if ! command -v "$cmd" &>/dev/null; then
        log_error "缺少依赖: ${name}，请先安装"
        exit 1
    fi
}

check_deps() {
    log_info "检查依赖..."
    check_dependency docker
    check_dependency docker "docker compose"
    docker compose version &>/dev/null || { log_error "Docker Compose 插件未安装"; exit 1; }
    log_ok "依赖检查通过"
}

# ── 环境文件 ──────────────────────────────────────────────────────────────────
ensure_env() {
    if [ ! -f "${REPO_ROOT}/.env" ]; then
        log_warn ".env 文件不存在，从 .env.example 复制..."
        cp "${REPO_ROOT}/.env.example" "${REPO_ROOT}/.env"
        log_warn "请编辑 .env 文件填入真实密钥后重新运行"
        exit 1
    fi
}

# ── 构建镜像 ──────────────────────────────────────────────────────────────────
build_images() {
    log_step
    log_step_title "构建 Docker 镜像"

    ensure_env

    log_info "构建 meeting-api 镜像..."
    docker build \
        -t meeting-api:dev \
        -t meeting-api:v0.1.0 \
        -f "${REPO_ROOT}/apps/meeting-api/Dockerfile" \
        "${REPO_ROOT}/apps/meeting-api"
    log_ok "meeting-api 镜像构建完成"

    log_info "构建 ai-worker 镜像..."
    docker build \
        -t ai-worker:dev \
        -t ai-worker:v0.1.0 \
        -f "${REPO_ROOT}/apps/ai-worker/Dockerfile" \
        --build-arg BASE=python:3.11-slim \
        "${REPO_ROOT}"
    log_ok "ai-worker 镜像构建完成"

    log_info "构建 meeting-web 镜像..."
    docker build \
        -t meeting-web:dev \
        -t meeting-web:v0.1.0 \
        -f "${REPO_ROOT}/apps/meeting-web/Dockerfile" \
        "${REPO_ROOT}"
    log_ok "meeting-web 镜像构建完成"

    log_info "构建 ai-worker (CUDA) 镜像...（可选，用于 GPU 生产环境）"
    # UV_EXTRAS=real-models 让 CUDA 镜像装齐 FlagEmbedding/funasr/pyannote.audio。
    # 若漏掉，AI_WORKER_USE_FAKE_*_RUNTIME=false 的 Pod 会在首个真实任务时炸。
    # K8s base statefulset 默认期望 ``ai-worker:cuda`` tag —— 见
    # infra/meeting-infra/k8s/base/ai-worker/statefulset.yaml。
    #
    # 不再吞 stderr —— CUDA base / torch / FlagEmbedding 这条线最容易失败，隐藏
    # 日志会让排障无从下手。失败时显示 warning 但不中断 dev 流程（CUDA 镜像在
    # CPU-only dev box 上构建失败是预期）。
    if docker build \
        -t ai-worker:cuda \
        -t ai-worker:cuda-v0.1.0 \
        --build-arg UV_EXTRAS=real-models \
        -f "${REPO_ROOT}/apps/ai-worker/Dockerfile" \
        "${REPO_ROOT}"; then
        log_ok "ai-worker CUDA 镜像构建完成"
    else
        log_warn "CUDA 镜像构建失败（如无 NVIDIA 基础镜像或 CUDA toolchain 可忽略）"
    fi
}

# ── 推送镜像 ──────────────────────────────────────────────────────────────────
push_images() {
    local registry="${1:-}"
    if [ -z "$registry" ]; then
        log_error "请指定镜像仓库地址，如: ./deploy/deploy.sh push registry.example.com"
        exit 1
    fi

    log_step
    log_step_title "推送镜像到 ${registry}"

    local images=("meeting-api" "meeting-web" "ai-worker")
    local tag="${2:-v0.1.0}"

    for img in "${images[@]}"; do
        log_info "推送 ${img}:${tag}..."
        docker tag "${img}:${tag}" "${registry}/${img}:${tag}"
        docker push "${registry}/${img}:${tag}"
        log_ok "${img}:${tag} 推送完成"
    done

    # Phase J ML — push the CUDA ai-worker too when present locally. K8s
    # base + prod overlay reference ai-worker:cuda-${tag}; without this
    # the registry would be missing the image and the prod rollout would
    # ImagePullBackOff after a fresh build.
    local cuda_tag="cuda-${tag}"
    if docker image inspect "ai-worker:${cuda_tag}" >/dev/null 2>&1; then
        log_info "推送 ai-worker:${cuda_tag}..."
        docker tag "ai-worker:${cuda_tag}" "${registry}/ai-worker:${cuda_tag}"
        docker push "${registry}/ai-worker:${cuda_tag}"
        log_ok "ai-worker:${cuda_tag} 推送完成"
    else
        log_warn "本地未找到 ai-worker:${cuda_tag}，跳过 CUDA 镜像推送（先执行 deploy.sh build 构建）"
    fi
}

# ── 本地环境 ──────────────────────────────────────────────────────────────────
local_up() {
    log_step
    log_step_title "启动本地开发环境"

    ensure_env
    check_deps

    cd "${INFRA_DIR}/docker/compose"

    log_info "启动基础设施容器 (PostgreSQL, RabbitMQ, MinIO, Vault)..."
    docker compose -f docker-compose.yml up -d
    log_ok "基础设施启动中..."

    log_info "等待基础设施就绪..."
    wait_for_service "postgres" 30 || { log_error "postgres 启动失败，已中止"; exit 1; }
    wait_for_service "rabbitmq" 30 || { log_error "rabbitmq 启动失败，已中止"; exit 1; }
    wait_for_service "minio" 30 || { log_error "minio 启动失败，已中止"; exit 1; }

    log_info "启动 meeting-api (需要先构建镜像)..."
    docker compose -f docker-compose.yml --profile full-stack up -d meeting-api

    log_info "等待 meeting-api 就绪..."
    wait_for_service "meeting-api" 60 || { log_error "meeting-api 启动失败，已中止"; exit 1; }

    log_info "启动 ai-worker..."
    docker compose -f docker-compose.yml --profile workstation up -d ai-worker

    log_info "等待 ai-worker 就绪..."
    wait_for_service "ai-worker" 60 || { log_error "ai-worker 启动失败，已中止"; exit 1; }

    log_ok "所有服务已启动"
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "  服务访问地址:"
    echo "  ───────────────────────────────────────────────────────────"
    echo "  meeting-api:      http://localhost:8080"
    echo "  ai-worker:        http://localhost:8090"
    echo "  meeting-web:      cd apps/meeting-web && npm run dev (Vite)"
    echo "  PostgreSQL:       localhost:5432  (meeting/meeting_dev)"
    echo "  RabbitMQ 管理:    http://localhost:15672  (meeting/meeting_dev)"
    echo "  MinIO 控制台:     http://localhost:9001  (minioadmin/minioadmin)"
    echo "  Prometheus:       http://localhost:9090  (--profile observability)"
    echo "  Grafana:          http://localhost:3000  (--profile observability)"
    echo "═══════════════════════════════════════════════════════════════"
}

local_down() {
    log_info "停止本地开发环境..."
    cd "${INFRA_DIR}/docker/compose"
    docker compose -f docker-compose.yml --profile full-stack --profile workstation --profile observability down
    log_ok "本地环境已停止"
}

local_status() {
    cd "${INFRA_DIR}/docker/compose"
    docker compose -f docker-compose.yml --profile full-stack --profile workstation --profile observability ps
}

# ── 辅助函数：等待服务就绪 ────────────────────────────────────────────────────
wait_for_service() {
    local service="$1"
    local max_wait="${2:-30}"
    local interval=2
    local elapsed=0

    while [ "$elapsed" -lt "$max_wait" ]; do
        if docker compose -f "${INFRA_DIR}/docker/compose/docker-compose.yml" ps "$service" 2>/dev/null | grep -q "healthy\|running"; then
            # 额外检查 — 对有 HTTP 健康端点的服务校验真实就绪状态
            case "$service" in
                meeting-api)
                    # 必须用 readiness 探针：聚合 /actuator/health 会被
                    # AiWorkerHealthIndicator 在 ai-worker 还没启动时拉成
                    # DOWN（startup() 的顺序是先 meeting-api 再 ai-worker），
                    # 用 readiness 组才能反映 meeting-api 自身的就绪状态。
                    if curl -sf http://localhost:8080/actuator/health/readiness &>/dev/null; then
                        log_ok "${service} 健康检查通过"
                        return 0
                    fi
                    ;;
                ai-worker)
                    if curl -sf http://localhost:8090/internal/health &>/dev/null; then
                        log_ok "${service} 健康检查通过"
                        return 0
                    fi
                    ;;
                *)
                    log_ok "${service} 已就绪"
                    return 0
                    ;;
            esac
        fi
        sleep $interval
        elapsed=$((elapsed + interval))
        echo -n "."
    done

    log_error "${service} 在 ${max_wait}s 内未就绪"
    return 1
}

# ── K8s 部署 ──────────────────────────────────────────────────────────────────
k8s_deploy() {
    local env="$1"
    local mode="${2:-}"   # 显式传 "--no-wait" 时不阻塞 rollout 状态判定
    local overlay_dir="${INFRA_DIR}/k8s/overlays/${env}"
    local wait_for_rollout=true
    if [ "$mode" = "--no-wait" ]; then
        wait_for_rollout=false
    fi

    if [ ! -d "$overlay_dir" ]; then
        log_error "K8s overlay 不存在: ${overlay_dir}"
        exit 1
    fi

    log_step
    log_step_title "部署到 Kubernetes 环境: ${env}"

    check_dependency kubectl
    check_dependency kustomize

    # 检查集群连接
    log_info "检查 Kubernetes 集群连接..."
    kubectl cluster-info &>/dev/null || { log_error "无法连接 Kubernetes 集群"; exit 1; }

    # 设置命名空间
    local ns="meeting-${env}"
    log_info "创建命名空间 ${ns}..."
    kubectl create namespace "${ns}" --dry-run=client -o yaml | kubectl apply -f -

    # 创建 Secret (生产环境应通过 Vault/SealedSecrets 注入)
    log_info "创建必要 Secret..."
    if [ "$env" = "dev" ]; then
        cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: meeting-api-secret
  namespace: ${ns}
type: Opaque
stringData:
  POSTGRES_USER: meeting
  POSTGRES_PASSWORD: meeting_dev
  RABBITMQ_USER: meeting
  RABBITMQ_PASS: meeting_dev
  MINIO_ROOT_USER: minioadmin
  MINIO_ROOT_PASSWORD: minioadmin
  AI_WORKER_CALLBACK_HMAC_SECRET: change-me-callback-secret-32bytes
  AI_WORKER_INTERNAL_API_HMAC_SECRET: change-me-internal-secret-32bytes
  DASHSCOPE_API_KEY: sk-change-me
---
apiVersion: v1
kind: Secret
metadata:
  name: ai-worker-secret
  namespace: ${ns}
type: Opaque
stringData:
  AI_WORKER_INTERNAL_API_HMAC_SECRET: change-me-internal-secret-32bytes
  AI_WORKER_CALLBACK_HMAC_SECRET: change-me-callback-secret-32bytes
  AI_WORKER_ADMIN_JWT_SECRET: dev-admin-secret-32-bytes-fixed
EOF
    else
        log_warn "生产环境 Secret 请通过外部 Secrets Manager (Vault/AWS Secrets Manager) 注入"
        log_warn "手动创建后请注释以下提示:"
        log_warn "  kubectl create secret generic meeting-api-secret -n ${ns} --from-literal=... "
        log_warn "  kubectl create secret generic ai-worker-secret -n ${ns} --from-literal=... "
    fi
    log_ok "Secret 已创建"

    # 构建并应用 Kustomize
    log_info "构建 Kustomize 清单..."
    kustomize build "${overlay_dir}" --enable-helm > "${DEPLOY_DIR}/.kustomize-${env}.yaml"

    log_info "应用到集群..."
    kubectl apply -f "${DEPLOY_DIR}/.kustomize-${env}.yaml"

    # 等待部署就绪 — 默认 fail-fast；--no-wait 时只观察、不影响退出码。
    local rollout_targets=(
        "deployment/meeting-api"
        "deployment/meeting-web"
        "statefulset/ai-worker"
    )
    if $wait_for_rollout; then
        for target in "${rollout_targets[@]}"; do
            log_info "等待 ${target} 部署就绪..."
            if ! kubectl rollout status "${target}" -n "${ns}" --timeout=300s; then
                log_error "${target} rollout 失败 — 见 'kubectl describe ${target} -n ${ns}'"
                log_error "如需忽略 rollout 失败，重新执行: ./deploy/deploy.sh k8s-${env} --no-wait"
                exit 1
            fi
        done
    else
        log_warn "已传入 --no-wait — 跳过 rollout 状态阻塞，不会因未就绪退出失败"
        for target in "${rollout_targets[@]}"; do
            kubectl rollout status "${target}" -n "${ns}" --timeout=300s 2>/dev/null || true
        done
    fi

    log_ok "Kubernetes 部署完成 (环境: ${env})"
    echo ""
    kubectl get pods -n "${ns}" -o wide
}

k8s_status() {
    local env="${1:-dev}"
    local ns="meeting-${env}"
    echo "═══════════════════════════════════════════════════════════════"
    echo "  K8s 环境: ${env}  |  命名空间: ${ns}"
    echo "═══════════════════════════════════════════════════════════════"
    echo ""
    echo "--- Pods ---"
    kubectl get pods -n "${ns}" -o wide
    echo ""
    echo "--- Services ---"
    kubectl get svc -n "${ns}"
    echo ""
    echo "--- Ingress ---"
    kubectl get ingress -n "${ns}"
}

k8s_destroy() {
    local env="$1"
    local ns="meeting-${env}"
    log_warn "即将删除 ${env} 环境的所有资源！(命名空间: ${ns})"
    read -r -p "确认删除？输入 'yes' 继续: " confirm
    if [ "$confirm" != "yes" ]; then
        log_info "已取消"
        exit 0
    fi
    kubectl delete namespace "${ns}"
    log_ok "已删除命名空间 ${ns}"
}

# ── Terraform 基础架构 ────────────────────────────────────────────────────────
terraform_plan() {
    log_step
    log_step_title "Terraform 基础架构规划"

    check_dependency terraform

    cd "${INFRA_DIR}/terraform"

    local env="${1:-dev}"
    log_info "规划环境: ${env}"

    terraform init -backend=false 2>/dev/null || terraform init

    terraform plan \
        -var="environment=${env}" \
        -var="db_password=placeholder-change-me" \
        -out="/tmp/meeting-tf-${env}.plan"
}

terraform_apply() {
    log_step
    log_step_title "Terraform 应用到云环境"

    check_dependency terraform

    cd "${INFRA_DIR}/terraform"

    local env="${1:-dev}"
    log_warn "即将在 AWS 上创建 ${env} 环境资源！"

    terraform apply \
        -var="environment=${env}" \
        -var="db_password=${TF_VAR_db_password:-placeholder-change-me}"
}

# ── 合约代码生成 ──────────────────────────────────────────────────────────────
codegen() {
    log_step
    log_step_title "从合约重新生成代码"

    check_dependency node
    check_dependency npm

    cd "${REPO_ROOT}/packages/meeting-contracts"

    log_info "安装依赖..."
    npm ci --silent

    log_info "生成代码..."
    npm run codegen

    log_ok "代码生成完成"
}

# ── 健康检查 ──────────────────────────────────────────────────────────────────
health_check() {
    log_step
    log_step_title "健康检查"

    local base_api="${API_URL:-http://localhost:8080}"
    local base_worker="${WORKER_URL:-http://localhost:8090}"
    local base_web="${WEB_URL:-http://localhost:5173}"
    local fail=0

    _curl_health() {
        local label="$1"
        local url="$2"
        echo "检查 ${label} (${url})..."
        if curl -sf "${url}" -o /tmp/.deploy-health.$$; then
            python3 -m json.tool < /tmp/.deploy-health.$$ 2>/dev/null \
                || cat /tmp/.deploy-health.$$
            log_ok "${label} 通过"
            rm -f /tmp/.deploy-health.$$
            return 0
        fi
        rm -f /tmp/.deploy-health.$$
        log_error "${label} 不可达"
        return 1
    }

    _curl_health "meeting-api liveness"  "${base_api}/actuator/health"             || fail=$((fail + 1))
    _curl_health "meeting-api readiness" "${base_api}/actuator/health/readiness"   || fail=$((fail + 1))
    _curl_health "ai-worker liveness"    "${base_worker}/internal/health"          || fail=$((fail + 1))
    # /internal/ready 是 Phase J 新增的探针，会触发 checksum guard；503 说明
    # checksum 不匹配或权重未就绪，按 fail 处理。
    _curl_health "ai-worker readiness"   "${base_worker}/internal/ready"           || fail=$((fail + 1))

    # meeting-web 是静态 SPA — dev 模式下常单独跑 Vite，这里不强求成功
    echo "检查 meeting-web (${base_web}/)..."
    if curl -sf -o /dev/null "${base_web}/"; then
        log_ok "meeting-web 可达"
    else
        log_warn "meeting-web 不可达 (dev 模式下若 Vite 未启动可忽略)"
    fi

    if [ $fail -ne 0 ]; then
        log_error "健康检查失败 (${fail} 项致命错误)"
        exit 1
    fi
    log_ok "全部关键健康检查通过"
}

# ── 数据库迁移 ────────────────────────────────────────────────────────────────
db_migrate() {
    log_step
    log_step_title "数据库迁移"

    local env="${1:-local}"

    if [ "$env" = "local" ]; then
        log_info "在本地 Docker 环境执行 Flyway 迁移..."
        # Flyway 迁移在 meeting-api 启动时自动运行
        docker restart meeting-api
        log_info "等待 meeting-api 完成迁移..."
        wait_for_service "meeting-api" 90
    elif [ "$env" = "k8s" ]; then
        log_info "K8s 迁移: meeting-api 启动时自动执行 Flyway 迁移"
        local ns="${2:-meeting-dev}"
        kubectl rollout restart deployment/meeting-api -n "${ns}"
        kubectl rollout status deployment/meeting-api -n "${ns}" --timeout=300s
    fi
    log_ok "数据库迁移完成"
}

# ── 日志查看 ──────────────────────────────────────────────────────────────────
tail_logs() {
    local service="${1:-}"
    if [ -n "$service" ]; then
        cd "${INFRA_DIR}/docker/compose"
        docker compose -f docker-compose.yml logs -f "$service"
    else
        cd "${INFRA_DIR}/docker/compose"
        docker compose -f docker-compose.yml --profile full-stack --profile workstation --profile observability logs -f
    fi
}

# ── 清理 ──────────────────────────────────────────────────────────────────────
clean() {
    log_step
    log_step_title "清理 Docker 资源"

    log_warn "这将删除所有容器、卷和本地镜像！"
    read -r -p "确认？输入 'yes' 继续: " confirm
    if [ "$confirm" != "yes" ]; then
        log_info "已取消"
        exit 0
    fi

    cd "${INFRA_DIR}/docker/compose"
    docker compose -f docker-compose.yml --profile full-stack --profile workstation --profile observability down -v --remove-orphans

    # 清理构建缓存
    docker builder prune -f
    log_ok "清理完成"
}

# ── 帮助信息 ──────────────────────────────────────────────────────────────────
show_help() {
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  本地会议智能系统 · 部署工具"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "用法:  ./deploy/deploy.sh <命令> [参数]"
    echo ""
    echo "本地开发:"
    echo "  local              启动完整本地开发环境 (Docker Compose)"
    echo "  local-down         停止本地环境"
    echo "  local-status       查看本地服务状态"
    echo ""
    echo "构建与镜像:"
    echo "  build              构建所有 Docker 镜像"
    echo "  push <registry>    推送镜像到远程仓库"
    echo ""
    echo "Kubernetes:"
    echo "  k8s-dev [--no-wait]      部署到 K8s 开发环境 (默认阻塞 rollout，失败即退出)"
    echo "  k8s-prod [--no-wait]     部署到 K8s 生产环境 (--no-wait 仅观察不阻塞)"
    echo "  k8s-status [env]   查看 K8s 部署状态"
    echo "  k8s-destroy <env>  销毁 K8s 环境"
    echo ""
    echo "基础架构 (Terraform):"
    echo "  terraform-plan     规划云资源变更"
    echo "  terraform-apply    应用到云资源"
    echo ""
    echo "运维操作:"
    echo "  codegen            重新生成合约代码"
    echo "  health             运行健康检查 (任一关键探针失败则退出非零)"
    echo "  db-migrate [env]   执行数据库迁移"
    echo "  logs [service]     查看服务日志"
    echo "  clean              清理所有 Docker 资源"
    echo ""
    echo "环境变量:"
    echo "  API_URL            meeting-api 地址   (默认: http://localhost:8080)"
    echo "  WORKER_URL         ai-worker 地址     (默认: http://localhost:8090)"
    echo "  WEB_URL            meeting-web 地址   (默认: http://localhost:5173)"
    echo "  TF_VAR_db_password Terraform 数据库密码"
    echo "═══════════════════════════════════════════════════════════════════"
}

# ── 主入口 ────────────────────────────────────────────────────────────────────
main() {
    local cmd="${1:-help}"

    case "$cmd" in
        local)              local_up ;;
        local-down)         local_down ;;
        local-status)       local_status ;;
        build)              build_images ;;
        push)               push_images "${2:-}" "${3:-v0.1.0}" ;;
        k8s-dev)            k8s_deploy "dev"  "${2:-}" ;;
        k8s-prod)           k8s_deploy "prod" "${2:-}" ;;
        k8s-status)         k8s_status "${2:-dev}" ;;
        k8s-destroy)        k8s_destroy "${2:-dev}" ;;
        terraform-plan)     terraform_plan "${2:-dev}" ;;
        terraform-apply)    terraform_apply "${2:-dev}" ;;
        codegen)            codegen ;;
        health)             health_check ;;
        db-migrate)         db_migrate "${2:-local}" "${3:-}" ;;
        logs)               tail_logs "${2:-}" ;;
        clean)              clean ;;
        help|--help|-h)     show_help ;;
        *)
            log_error "未知命令: ${cmd}"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
