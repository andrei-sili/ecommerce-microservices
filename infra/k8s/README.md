# Local Kubernetes (Wave 5a) — k3d + Kustomize

Ports the Compose **compute + data plane** onto a local **k3d** single-node cluster: the 6
application services, their 6 Postgres databases (database-per-service), and RabbitMQ — 13 pods
in one `ecommerce` namespace.

**Out of scope in 5a:** Kong (API gateway) and Consul are **not** ported (Wave 5b); observability
and a registry/GHCR come later (5c/5d). There is **no single north-south edge yet** — reach a
service with `kubectl port-forward`. The committed `infra/docker-compose.yml` stays the untouched,
still-working baseline; nothing here changes it or any service code.

K8s **Service names are identical to the Compose DNS names** (`user-service`, `product-service`,
…, `user-db`, …, `rabbitmq`), so every east-west URL and JDBC/DSN string is unchanged and CoreDNS
resolves them exactly as Docker DNS did.

## Prerequisites (tools)

Install once to any dir on your `PATH` (e.g. `~/.local/bin` — no sudo needed for the binaries):

```bash
# k3d v5.9.0 (bundles k3s)
curl -sSLf -o ~/.local/bin/k3d https://github.com/k3d-io/k3d/releases/download/v5.9.0/k3d-linux-amd64 && chmod +x ~/.local/bin/k3d
# kubectl
curl -sSLf -o ~/.local/bin/kubectl https://dl.k8s.io/release/v1.32.5/bin/linux/amd64/kubectl && chmod +x ~/.local/bin/kubectl
# kustomize v5.8.1 (>= 5.8.1: 5.8.0 had a namespace-propagation regression)
curl -sSLf https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv5.8.1/kustomize_v5.8.1_linux_amd64.tar.gz | tar -xz -C ~/.local/bin kustomize
# kubeconform v0.6.7 (manifest validation; used by `make -C infra k8s-validate` and CI)
curl -sSLf https://github.com/yannh/kubeconform/releases/download/v0.6.7/kubeconform-linux-amd64.tar.gz | tar -xz -C ~/.local/bin kubeconform
```

Docker must be running (k3d runs k3s inside Docker — no VM).

## Host prep — inotify limits (needs sudo, do this FIRST)

A 13-pod cluster exhausts Ubuntu's default inotify limits (`max_user_instances=128`,
`max_user_watches=8192`) → the **#1 cause of CrashLoops**. `up.sh` checks this and stops with the
exact command if the limits are too low. Run it once on the host:

```bash
sudo sh -c 'printf "fs.inotify.max_user_watches=524288\nfs.inotify.max_user_instances=512\n" > /etc/sysctl.d/99-k8s-inotify.conf && sysctl --system'
```

## Bring up (one command)

```bash
make -C infra k8s-up        # or: bash infra/k8s/up.sh
```

`up.sh` is idempotent and does: inotify check → `k3d cluster create ecommerce` (single node,
Traefik disabled, host `:8000` → NodePort `30080` reserved for Kong in 5b) → `docker compose build`
the `ecommerce/*-service:dev` images → `k3d image import` (app + `postgres:16-alpine` +
`rabbitmq:3-management-alpine`; no registry in 5a) → seed a `secret.env` if missing →
`kubectl apply -k overlays/local` → wait for rollout.

```bash
kubectl get pods -n ecommerce      # expect 13 Ready (6 svc + 6 db + rabbitmq)
```

## North-south access (no Kong yet)

```bash
kubectl -n ecommerce port-forward svc/user-service 8081:8081     # then curl localhost:8081/api/v1/...
kubectl -n ecommerce port-forward svc/rabbitmq 15672:15672        # RabbitMQ management UI
```

## Secrets — nothing secret in git

- Real values live in **`overlays/local/secret.env`**, which is **gitignored** (matches the root
  `.gitignore` rule `*.env`). Only `secret.env.example` (placeholders) is committed.
  ```bash
  cp overlays/local/secret.env.example overlays/local/secret.env   # then fill real values
  git check-ignore overlays/local/secret.env                        # must print the path (ignored)
  ```
  It uses the same key scheme as `infra/.env`, so the placeholders already boot a working dev stack.
### Secrets design (why one Secret is safe here)

- The overlay's `secretGenerator` reads `secret.env` into **one** `ecommerce-secrets` Secret (content-hash
  suffix → editing a value auto-rolls the dependent pods). Each workload pulls **only the keys it needs**
  via `valueFrom.secretKeyRef`, so a service's container env never carries another service's credentials
  (cart's env has no payment keys; product/order share `JWT_SECRET`/`INTERNAL_API_KEY` by design;
  notification's DSN is assembled in-pod from its DB user/password — the password is never a literal in any
  committed file).
- Every workload sets **`automountServiceAccountToken: false`** (none calls the K8s API). With no SA token
  in the pod, a compromised container cannot read the `ecommerce-secrets` object through the API even if
  RBAC is later loosened — so a single Secret object gives the same blast-radius isolation as splitting it.
- **base64 ≠ encryption.** A K8s Secret is base64-encoded, fully decodable (`kubectl get secret … -o
  jsonpath … | base64 -d`) and stored unencrypted in etcd by default — that is exactly why no rendered
  Secret or `secret.env` may ever be committed. Never run `kustomize build | git add`.
- **Production hardening** (out of scope here): one Secret object per service + per-ServiceAccount RBAC,
  an external store (External Secrets Operator + Vault/OpenBao, or Sealed Secrets for GitOps-safe commits),
  and etcd encryption-at-rest (KMS v2).

## Data lifecycle (read before you tear down)

- Each Postgres is a single-replica **StatefulSet** with a `volumeClaimTemplate` (RWO, default
  `local-path` SC, 1Gi). **Deleting an app pod or a DB pod keeps the data** — the PVC reattaches on
  reschedule.
- **`down.sh` / `k3d cluster delete ecommerce` DESTROYS all data.** local-path volumes live inside the
  k3d node container, so no retention policy survives a cluster delete. This is the K8s analogue of
  Compose `down -v`. **Re-seed by re-running `up.sh` and the smoke** (register → login → catalog → cart
  → order → pay → notification).
- On-startup migrations (Flyway/Alembic) are kept; safe at `replicas: 1`. A `pg_isready` initContainer
  makes each app wait for its DB so a cold start doesn't crash-loop.

## Probes

- Spring (5): `startupProbe` + `readinessProbe` → `/actuator/health/readiness`; `livenessProbe` →
  `/actuator/health/liveness` (in-process, does **not** touch DB/broker, so a brief DB outage never
  restarts the pod). The probe groups are already enabled in each service's `application.yml`.
- notification (FastAPI): all three → `/health` on 8086.

## Teardown

```bash
make -C infra k8s-down      # or: bash infra/k8s/down.sh   (DATA LOSS)
```

## Validate manifests (no cluster)

```bash
make -C infra k8s-validate  # kustomize build overlays/local | kubeconform -strict
```

The same check runs in CI as the `validate-k8s` job (parallel to `validate-compose`).

## Layout

```
infra/k8s/
  base/
    namespace.yaml                         # ns: ecommerce
    {user,product,cart,order,payment,notification}/{deployment,service.yaml}
    databases/<svc>-db.yaml                # StatefulSet + headless Service ×6
    rabbitmq/rabbitmq.yaml                 # Deployment + PVC + Service
    kustomization.yaml
  overlays/
    local/
      kustomization.yaml                   # configMapGenerator + secretGenerator, namespace
      secret.env.example                   # committed placeholders (real secret.env is gitignored)
    prod/.gitkeep                          # empty stub for a later wave
  up.sh  down.sh  README.md
```
