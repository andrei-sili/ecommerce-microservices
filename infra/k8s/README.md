# Local Kubernetes (Wave 5a + 5b) — k3d + Kustomize

Ports the Compose **compute + data plane** onto a local **k3d** single-node cluster: the 6
application services, their 6 Postgres databases (database-per-service), RabbitMQ, and (Wave 5b)
**Kong** as the single north-south edge — **14 pods** in one `ecommerce` namespace.

**Wave 5b (this doc):** Kong is the only host ingress, exposed as a `NodePort` on
`http://localhost:8000`; **Consul is retired** — Kubernetes does discovery + health-gated routing
natively (CoreDNS is the registry; kube-proxy + pod readiness are the router), so Kong's Wave-4
active health checks were deleted. **Out of scope:** observability and a registry/GHCR come later
(5c/5d). The committed `infra/docker-compose.yml` stays a still-working baseline (Consul removed
there too, but it keeps Kong's Docker-DNS upstreams + active checks); nothing here changes service code.

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
Traefik disabled, host `:8000` → NodePort `30080` for Kong) → `docker compose build`
the `ecommerce/*-service:dev` images → `k3d image import` (app + `postgres:16-alpine` +
`rabbitmq:3-management-alpine` + `kong:3.9.3`; no registry yet) → seed a `secret.env` if missing →
`kubectl apply -k overlays/local` → wait for rollout (incl. `deploy/kong`).

```bash
kubectl get pods -n ecommerce      # expect 14 Ready (6 svc + 6 db + rabbitmq + kong)
```

## North-south access — the Kong edge on `:8000`

Kong is the **only** host-reachable ingress: host `:8000` → NodePort `30080` → Kong proxy `:8000`
→ the target service's ClusterIP. Every public path is `/api/v1/...` (see the route table in
`base/kong/kong.yml`).

```bash
curl http://localhost:8000/api/v1/products                       # -> product-service via Kong
# register + login through the edge, then call an authed endpoint with the returned JWT:
curl -X POST http://localhost:8000/api/v1/auth/login -H 'Content-Type: application/json' -d '{...}'
```

The app/DB ClusterIP Services are **not** host-reachable except via `kubectl port-forward`; the
RabbitMQ management UI has no public Kong route:

```bash
kubectl -n ecommerce port-forward svc/rabbitmq 15672:15672        # RabbitMQ management UI
```

## Kong edge (Wave 5b)

- **Shape:** a plain `Deployment` (`replicas: 1`) + a kustomize-generated `ConfigMap(kong.yml)` +
  a `NodePort` `Service` (`nodePort: 30080`). DB-less declarative (`KONG_DATABASE=off`), Admin API
  `off`, status API on `:8100` for the kubelet probes. Runs non-root (uid/gid **1001** —
  `docker run --rm kong:3.9.3 id`) with a read-only rootfs (`emptyDir` at `/tmp` + `/kong_prefix`).
- **Config-as-code:** `base/kong/kong.yml` is delivered by a `configMapGenerator`, so its
  content-hash suffix **rolls the Kong pod on every edit** — Kong does **not** hot-reload a
  declarative file, so a hand-written ConfigMap would silently serve stale config. Two Kong
  configs exist by necessity: this K8s one points `services[].url` at ClusterIP names with **no**
  `upstreams`/active checks; the Compose `infra/kong/kong.yml` keeps them (Docker DNS resolves
  dead containers, K8s readiness does not).
- **Down-edge is `502`, not `503`.** When a routed Service has **zero Ready** endpoints, kube-proxy
  `REJECT`s the connection and Kong returns **`502 Bad Gateway`** (never `500`, never a stale
  `200`); it auto-recovers to `200` once the pod is Ready again, for any outage length, with no
  Kong reload. The Wave-4 ring-balancer `503` does not apply here (the ClusterIP peer is always
  resolvable — it just fails to connect). Do **not** re-add `upstreams`+active checks to relabel
  `502`→`503`; that re-adds the machinery this wave deletes.
- **Edge gates (carried from Wave 4):** internal-route isolation by omission (`/api/v1/inventory/*`
  → 404), the case-insensitive `/api/v1/payments/webhook` 404 block, 5/min cap on
  `POST /api/v1/auth/login` over the global 120/min, trust-header strip, CORS, 5 MB size limit,
  `correlation-id`. Auth is Option A: Kong holds **no** `JWT_SECRET`; each service validates locally.

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
- Kong: `startupProbe`/`livenessProbe` → `/status`, `readinessProbe` → `/status/ready` (all on the
  status port `8100`). Liveness deliberately uses `/status` (not `/status/ready`) so a config
  hiccup can't restart-loop the pod; readiness gates the pod out of the Service until a valid,
  non-empty config loads.

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
    kong/{deployment,service}.yaml         # DB-less edge + NodePort 30080
    kong/kong.yml                          # declarative config -> hashed ConfigMap (generated in base)
    kustomization.yaml                     # + configMapGenerator(kong-config)
  overlays/
    local/
      kustomization.yaml                   # configMapGenerator + secretGenerator, namespace
      secret.env.example                   # committed placeholders (real secret.env is gitignored)
    prod/.gitkeep                          # empty stub for a later wave
  up.sh  down.sh  README.md
```
