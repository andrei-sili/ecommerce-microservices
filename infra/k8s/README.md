# Local Kubernetes (Wave 5a + 5b) — k3d + Kustomize

Ports the Compose **compute + data plane** onto a local **k3d** single-node cluster: the 6
application services, their 6 Postgres databases (database-per-service), RabbitMQ, and (Wave 5b)
**Kong** as the single north-south edge — **14 pods** in one `ecommerce` namespace.

**Wave 5b (this doc):** Kong is the only host ingress, exposed as a `NodePort` on
`http://localhost:8000`; **Consul is retired** — Kubernetes does discovery + health-gated routing
natively (CoreDNS is the registry; kube-proxy + pod readiness are the router), so Kong's Wave-4
active health checks were deleted. Observability landed in Wave 5c (below); the GHCR registry +
CI/CD scanning landed in Wave 5d (below). The committed `infra/docker-compose.yml` stays a still-working baseline (Consul removed
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

## Observability (Wave 5c) — metrics + logs, ClusterIP-only

Additive, Operator-based observability layered on the 5a/5b cluster as **separate apply
steps** (not folded into `overlays/local`). Nothing is exposed north-south: Grafana and
Prometheus are reached by `kubectl port-forward` only — **no Kong route, no second
NodePort**. Metrics endpoints stay ClusterIP-internal and off `/api/v1`.

- **Packaging = Option C (vendored `helm template`).** kube-prometheus-stack, Loki and
  Alloy are rendered **once** from PINNED charts into committed `rendered.yaml` under
  `base/observability/{metrics,logs}/`; bring-up is a plain `kubectl apply -k` with **no
  runtime Helm**. `helm` is an **authoring-only** tool (install to `~/.local/bin`,
  v3.21.x or v4.x) used solely by the `regen.sh` scripts on a chart bump — each bump is a
  reviewable `rendered.yaml` diff. Pins: kube-prometheus-stack **87.5.1**,
  grafana-community/loki **18.3.1**, grafana/alloy **1.10.0**. After a chart version bump,
  delete the old admission-webhook certgen Jobs before re-applying (completed Jobs are
  immutable → "field is immutable" on apply):
  `kubectl -n ecommerce delete job kps-admission-create kps-admission-patch`.
- **`--server-side` is MANDATORY for the metrics apply.** kube-prometheus-stack's
  Prometheus/Alertmanager CRDs are ~0.6–0.8 MB each, far over the 262144-byte client-side
  last-applied-annotation limit, so a plain `kubectl apply -k` fails "metadata.annotations:
  Too long". `up.sh` uses `kubectl apply -k … --server-side --force-conflicts`. Because the
  chart ships its CRDs **and** their CRs (Prometheus, ServiceMonitor) in one kustomization,
  `up.sh` applies it **twice** with a `kubectl wait --for=condition=established` on the CRDs
  in between: a single apply races (the CRs are rejected "no matches for kind" before the
  freshly-applied CRDs register, so the operator + Grafana land but the Prometheus CR + the
  8 ServiceMonitors silently don't). Both passes are idempotent.
- **Metrics targets (8, each a ServiceMonitor, 30s):** 5 Spring `/actuator/prometheus`
  (8081–8085), notification `/metrics` (8086), RabbitMQ `:15692/metrics`
  (`rabbitmq_prometheus` plugin), Kong `:8100/metrics` (built-in prometheus plugin, scraped
  via the **separate `kong-metrics` ClusterIP Service** — never a NodePort). The Prometheus
  Operator selects our ServiceMonitors because `serviceMonitorSelectorNilUsesHelmValues:
  false` (they carry no Helm release label). **The 5 Spring + notification targets stay
  DOWN until the instrumented service images are built/merged** (Wave-5c per-service PRs).
- **Logs (gated, skippable):** a monolithic single-binary **Loki** (filesystem, emptyDir,
  memcached caches **OFF** — their defaults request ~9.8 GB and are otherwise
  unschedulable) + an **Alloy** DaemonSet that tails `/var/log/pods` (CRI stage) and pushes
  to `http://loki:3100`. Loki is added to Grafana as a datasource. `up.sh` applies logs
  **after** metrics and auto-skips if `kubectl top nodes` shows the node ≥ 85 % memory;
  force-skip with `SKIP_LOGS=1`. Logs are **disposable** (emptyDir → dropped on pod
  restart), never presented as durable.
- **Grafana admin, public-repo-safe:** `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` live
  in the **dedicated gitignored `overlays/local/grafana.env`** (placeholders in
  `grafana.env.example`; `up.sh` seeds it, migrating any `GRAFANA_ADMIN_*` keys out of a
  pre-existing `secret.env` so a running cluster's login doesn't change). They are delivered
  through a **stable-named `grafana-admin` Secret** (`overlays/local` `secretGenerator`
  with `disableNameSuffixHash: true`) that the vendored chart references by fixed name via
  `existingSecret` — the hash-suffixed `ecommerce-secrets` name can't be followed by a
  static rendered manifest. It is created by the `overlays/local` apply, before the metrics
  apply, so the name resolves when Grafana starts. Anonymous viewer is **OFF**. The env
  file is 2-key **on purpose**: `secretGenerator` ingests a whole env file, and feeding it
  `secret.env` (pre-hardening) mirrored EVERY app secret into `grafana-admin`. Because
  `grafana-admin` has **no content-hash suffix**, rotating the password does NOT roll the
  Grafana pod — restart it manually:
  `kubectl -n ecommerce rollout restart deploy/kube-prometheus-stack-grafana`.
- **Grafana RBAC is namespace-scoped** (`grafana.rbac.namespaced: true` +
  `sidecar.dashboards.searchNamespace: null` in `values-metrics.yaml`): the chart default
  binds the Grafana ServiceAccount to a ClusterRole with get/watch/list on ConfigMaps AND
  **Secrets cluster-wide**; the rendered manifest now carries a Role/RoleBinding in
  `ecommerce` instead, and the dashboards sidecar watches only its own namespace (all
  dashboard ConfigMaps and the chart's datasource ConfigMap live there). **Migration on a
  pre-hardening cluster:** `kubectl apply` does not prune, so delete the stale pair once —
  `kubectl delete clusterrolebinding kube-prometheus-stack-grafana-clusterrolebinding &&
  kubectl delete clusterrole kube-prometheus-stack-grafana-clusterrole`.
- **Images** are pulled by kubelet from public registries at bring-up (NOT `k3d image
  import`ed — import doubles host+node storage and **disk is the tight constraint**;
  keep ~15 GB free). First observability bring-up needs internet for ~7 images (~1.3 GB).

```bash
# after `make -C infra k8s-up` (which applies both pillars):
kubectl -n ecommerce port-forward svc/kube-prometheus-stack-grafana 3000:80   # Grafana  -> http://localhost:3000
kubectl -n ecommerce port-forward svc/kps-prometheus 9090:9090                # Prometheus -> http://localhost:9090
```

Grafana dashboards (baked JSON ConfigMaps, `grafana_dashboard: "1"`, loaded by the sidecar
— no runtime egress to grafana.com): Spring Boot HTTP (21308), Spring Boot 3 JVM (22108),
FastAPI Observability (22676), RabbitMQ-Overview (10991), Kong (7424). Datasource uids are
pinned to `prometheus`/`loki` by `dashboards/normalize.py`. Re-fetch/regenerate with
`base/observability/*/regen.sh` (authoring-only; needs `helm`/`python3` + network).

## Secrets — nothing secret in git

- Real values live in **`overlays/local/secret.env`** (app stack) and
  **`overlays/local/grafana.env`** (Grafana admin login only), both **gitignored** (matches
  the root `.gitignore` rule `*.env`). Only the `.example` placeholders are committed.
  ```bash
  cp overlays/local/secret.env.example overlays/local/secret.env     # then fill real values
  cp overlays/local/grafana.env.example overlays/local/grafana.env   # then set a real password
  git check-ignore overlays/local/secret.env overlays/local/grafana.env  # must print both (ignored)
  ```
  `secret.env` uses the same key scheme as `infra/.env`, so the placeholders already boot a
  working dev stack.
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
make -C infra k8s-validate  # kustomize build overlays/local + observability/{metrics,logs} | kubeconform -strict
```

The same check runs in CI as the `validate-k8s` job (parallel to `validate-compose`). The
observability kustomizations carry prometheus-operator CRs, so their validation adds the
CRD schema catalog (`-schema-location …/datreeio/CRDs-catalog/…`) plus
`-skip CustomResourceDefinition` (which skips only the vendored upstream CRD objects —
every CR stays strictly validated, and a typo'd apiVersion cannot slip through silently).

## Registry & CI/CD (Wave 5d, hardened in 5d-h) — GHCR images + blocking scans

Every merge to `main` builds and pushes all 6 service images to GHCR
(`.github/workflows/push-images.yml`, Buildx + per-service GHA cache, auth via the
workflow's own `GITHUB_TOKEN` — no PAT, no new secret):

- `ghcr.io/andrei-sili/ecommerce-microservices/<svc>-service:sha-<longsha>` — one per
  commit, for traceability.
- `…:main` — moving branch tag, convenience only (also what the weekly scheduled scan
  targets). Never referenced by a manifest.
- **The pushed DIGEST is what `overlays/prod` pins** (5d-h): each matrix job emits
  `ecommerce/<svc>-service=ghcr.io/…/<svc>-service@sha256:<digest>` into its run
  summary — copy it verbatim at deploy time. Digests are content-addressed, so they
  are immutable even though GHCR has no native immutable tags.
- OCI labels (`org.opencontainers.image.source`) link each package back to this repo.

**One-time GitHub UI steps (repo owner):** set each of the 6 GHCR packages to **public**
(new packages default to PRIVATE even in a public repo → kubelet can't pull) and turn
**immutable packages/releases ON** (the control that saved trivy-action v0.35.0 in the
2026-03 supply-chain attack). Add the new CI checks to branch protection only after
their first green run.

### Deploying a pushed image (prod overlay)

`overlays/prod` maps every `ecommerce/<svc>-service:dev` to its GHCR image, pinned by
**digest**. The committed digest is a syntactically-valid placeholder (nothing is
pushed at PR time); pin the real digests at deploy time by copying each service's ref
from the `push-images` run summary — details in `overlays/prod/README.md`:

```bash
cd infra/k8s/overlays/prod
kustomize edit set image \
  "ecommerce/user-service=ghcr.io/andrei-sili/ecommerce-microservices/user-service@sha256:<digest>"
```

Never a moving tag (`latest`/`dev`/`main`) in prod. With public GHCR packages the
kubelet pulls without an imagePullSecret — which also permanently fixes the 5b gotcha
where kubelet image-GC left `k3d image import`ed images `ImagePullBackOff` on
scale-back (with a registry, kubelet just re-pulls).

### Local `:dev` staleness gotcha (local flow unchanged)

`overlays/local` keeps `ecommerce/<svc>-service:dev` + `k3d image import` — no registry
round-trip. But **re-importing the same `:dev` tag does NOT restart pods**: with
`imagePullPolicy: IfNotPresent` the node keeps serving the previously-cached image, so
you "redeploy" and silently run OLD code. After every re-import run
`kubectl -n ecommerce rollout restart deploy/<svc>-service` (the smoke flow already
does) — or use per-build tags.

### CI security posture (Wave 5d, promoted to blocking in 5d-h)

- **Every third-party action is pinned to a full commit SHA** (version as a trailing
  comment) — the 2026-03 Trivy supply-chain attack force-pushed 76/77 of trivy-action's
  tags. Re-verify versions upstream before re-pinning forward, and pin only immutable
  releases. Pinned to the verified-safe trivy-action v0.35.0 / CLI v0.69.3 — never
  CLI 0.69.4–0.69.6.
- **Trivy image scan is a BLOCKING PR gate**: fails on any CRITICAL CVE with a fix
  available (`ignore-unfixed: true` — unfixed base-image CVEs must not block forever)
  in the locally-built representative pair (`user` for the shared JVM base,
  `notification` for python-slim; `load: true`, never a GHCR ref — nothing is pushed
  on PRs). HIGH is deliberately not blocking yet: the triaged baseline carries 29
  fixed HIGHs — 22 Spring-platform deps (owner: dev-java, cleared by the Spring bump)
  + 7 base-image Go CVEs in `pebble` (cleared by a base-image refresh in the service
  Dockerfiles). Promote HIGH only once BOTH land (or the pebble CVEs get documented
  exceptions), otherwise the gate surprise-fails.
- **Trivy misconfig scan is split by provenance**: BLOCKING at CRITICAL,HIGH on
  hand-written manifests (all of `infra/` minus the vendored
  `observability/{metrics,logs}/rendered.yaml`); report-only on the vendored trees
  (upstream chart output we never hand-patch). Exceptions to either gate live ONLY in
  `.github/trivyignore` (id + justification + owner + date); the SARIF reports in the
  Security tab stay unfiltered.
- **Weekly full-fleet scan** (`.github/workflows/scheduled-image-scan.yml`): every
  Monday, report-only Trivy CVE scan of all six pushed `:main` GHCR images — a CVE
  disclosed after the last merge still surfaces within a week.
- **Committed-Secret guard** (`.github/scripts/check-committed-secrets.py`): CI fails if
  any tracked YAML **or JSON** under `infra/` contains a `kind: Secret` with non-empty
  `data`/`stringData`. base64 is not encryption and typically evades gitleaks; the
  guard parses documents (not a text grep), so vendored schema text and Grafana
  dashboard JSON can't false-positive, and unparseable files fail closed.
- **Deliberately not built (named production next step):** GitOps (Argo CD) reconciling
  a persistent cluster, with **GitHub OIDC** for short-lived cloud credentials. An
  ephemeral local k3d cluster gives a GitOps operator nothing durable to watch — deploy
  stays push-based and local. No kubeconfig, no deploy job, no new secret in CI.

## Layout

```
infra/k8s/
  base/
    namespace.yaml                         # ns: ecommerce
    {user,product,cart,order,payment,notification}/{deployment,service.yaml}
    databases/<svc>-db.yaml                # StatefulSet + headless Service ×6
    rabbitmq/rabbitmq.yaml                 # Deployment + PVC + Service
    kong/{deployment,service}.yaml         # DB-less edge + NodePort 30080 + kong-metrics ClusterIP :8100
    kong/kong.yml                          # declarative config (+ prometheus plugin) -> hashed ConfigMap
    kustomization.yaml                     # + configMapGenerator(kong-config)
    observability/                         # Wave 5c (Option C: vendored `helm template`)
      metrics/                             # kube-prometheus-stack 87.5.1
        regen.sh values-metrics.yaml rendered.yaml kustomization.yaml
        servicemonitors/*.yaml             # 8 ServiceMonitor CRs (5 spring + notification + rabbitmq + kong)
        dashboards/                        # 5 baked JSON dashboards -> ConfigMaps (grafana_dashboard: "1")
      logs/                                # grafana-community/loki 18.3.1 + grafana/alloy 1.10.0
        regen.sh values-loki.yaml values-alloy.yaml rendered.yaml kustomization.yaml
  overlays/
    local/
      kustomization.yaml                   # configMapGenerator + secretGenerator (+ grafana-admin), namespace
      secret.env.example                   # committed placeholders (real secret.env is gitignored)
      grafana.env.example                  # committed placeholders (real grafana.env is gitignored; 2-key)
    prod/                                  # Wave 5d/5d-h: same base, GHCR digest-pinned images
      kustomization.yaml                   # images transformer -> ghcr.io/…/<svc>-service@sha256:<digest>
      README.md                            # deploy-time `kustomize edit set image` flow (digests from run summary)
  up.sh  down.sh  README.md
```

> The `observability/{metrics,logs}` kustomizations are applied as SEPARATE
> `kubectl apply -k … --server-side` steps by `up.sh` — they are NOT referenced by
> `base/kustomization.yaml` or the local overlay (that keeps the core app apply unchanged
> and lets the logs pillar be skipped independently).
