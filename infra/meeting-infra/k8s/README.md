# meeting-infra K8s + Terraform layout

```
k8s/
  base/
    meeting-api/   # Deployment + Service + ConfigMap + HPA + PDB
    meeting-web/   # Deployment + Service
    ai-worker/     # StatefulSet (GPU nodeSelector, /opt/models PVC)
    kustomization.yaml
  overlays/
    dev/           # 1 replica, low resources, image tag :dev
    staging/       # — TBD —
    prod/          # 3 replicas, pinned image tags

terraform/
  main.tf          # RDS PostgreSQL + S3 exports bucket + KMS master key
```

## Build a manifest bundle for a target environment

```bash
kustomize build k8s/overlays/dev | kubectl apply -f -
kustomize build k8s/overlays/prod
```

> Phase 8.6 only ships the dev + prod overlays. Staging is intentionally
> a TBD until we settle on environment naming.

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
