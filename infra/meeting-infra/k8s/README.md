# meeting-infra K8s + Terraform layout

```
k8s/
  base/
    meeting-api/   # Deployment + Service + ConfigMap + HPA + PDB
    meeting-web/   # Deployment + Service
    ai-worker/     # StatefulSet (GPU nodeSelector, /opt/models PVC)
    kustomization.yaml
  overlays/
    dev/           # 1 replica, low resources, image tag :dev — 也是
                   # 当前 Phase J 验收（acceptance）环境。staging 占位
                   # 目录尚未填充，docs 里凡是写 "staging" 的地方都按
                   # meeting-dev 解读，详见 deploy/DEPLOY.md §5.4。
    staging/       # — TBD（占位目录，验收暂用 dev/ overlay）—
    prod/          # 3 replicas, pinned image tags

terraform/
  main.tf          # RDS PostgreSQL + S3 exports bucket + KMS master key
```

## Build a manifest bundle for a target environment

> 所有 `kustomize` / `kubectl` 命令都默认从 **repo 根目录**执行，
> 路径统一写成 `infra/meeting-infra/k8s/...`，方便 deploy.sh、CI、
> README、DEPLOY 之间互相对照。

下面的 `kustomize build` **只用于本地渲染 / 审阅**清单：它不创建
namespace、不创建 secrets、不装 PostgreSQL/RabbitMQ/MinIO 依赖、
也没有传 `--enable-helm`，直接 `| kubectl apply -f -` 会让
meeting-api Pod 落到 CrashLoopBackOff。

```bash
# 仅渲染清单到 stdout（review / diff / kubeconform 用）
kustomize build infra/meeting-infra/k8s/overlays/dev
kustomize build infra/meeting-infra/k8s/overlays/prod
```

实际部署走 `deploy/deploy.sh`，它会按顺序补齐：

1. `./deploy/deploy.sh k8s-deps dev` — namespace + Bitnami
   PostgreSQL+pgvector / RabbitMQ / MinIO；
2. `./deploy/deploy.sh k8s-dev` — 创建 `meeting-api-secret` /
   `ai-worker-secret`，`kustomize build --enable-helm` 渲染
   overlay 后 `kubectl apply -f -`，并阻塞到 rollout 完成。

```bash
./deploy/deploy.sh k8s-deps dev    # 仅依赖（每环境一次）
./deploy/deploy.sh k8s-dev         # 应用层 + 等待 rollout

# 生产同理：
./deploy/deploy.sh k8s-deps prod
./deploy/deploy.sh k8s-prod
```

> Phase 8.6 only ships the dev + prod overlays. The `staging/` directory
> is reserved but currently empty — Phase J acceptance therefore runs on
> the `dev/` overlay (namespace `meeting-dev`). When the staging tree
> lands, update `deploy/DEPLOY.md` §5.4 and the Phase J runbook in lock-
> step so the three docs agree on which environment is the acceptance
> target.

## Secrets

`meeting-api-secret` and `ai-worker-secret` are expected to exist in
the target namespace **before** applying the bundle. Wire them via a
secrets manager (Vault, AWS Secrets Manager, etc.) — the bundle never
ships plaintext credentials.

## Validate locally before pushing

```bash
# CI runs the same kubeconform call from .github/workflows/ci.yml
# (k8s-lint job). The older kubeval CLI is unmaintained — kubeconform
# supports newer Kubernetes API versions and CRD schemas.
kustomize build infra/meeting-infra/k8s/overlays/dev \
    | kubeconform -strict -summary -ignore-missing-schemas -kubernetes-version 1.29.0
kustomize build infra/meeting-infra/k8s/overlays/prod \
    | kubeconform -strict -summary -ignore-missing-schemas -kubernetes-version 1.29.0
```

Install kubeconform:

```bash
# macOS
brew install kubeconform
# Linux
curl -sLo /tmp/kc.tgz https://github.com/yannh/kubeconform/releases/download/v0.6.7/kubeconform-linux-amd64.tar.gz \
    && tar -xzf /tmp/kc.tgz -C /tmp && sudo mv /tmp/kubeconform /usr/local/bin/
```

CI runs the same step in the `k8s-lint` job.
