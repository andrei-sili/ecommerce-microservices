# infra — local orchestration

Two deployments share this folder: **Docker Compose** (the baseline single-command stack) and a
parallel **k3d** Kubernetes stack under `k8s/`. Compose is the priority: the whole system must come
up with one command in under five minutes.

## Compose bring-up

```bash
# 1. one-time: copy the env template and fill local values
cp infra/.env.example infra/.env

# 2. render the Kong edge config with THIS machine's RS256 public key (also generates the
#    gitignored dev keypair under infra/keys/ on first run). REQUIRED before every up: the
#    committed template carries an invalid-PEM placeholder, so the stack cannot boot un-rendered.
bash infra/scripts/render-kong.sh

# 3. bring the stack up
docker compose -f infra/docker-compose.yml up --build
```

`infra/scripts/render-kong.sh` (no args) is idempotent — safe to re-run; a given key always renders
byte-identical output. It writes the **gitignored** `infra/kong/kong.yml` from the tracked
`infra/kong/kong.tpl.yml`. Business traffic enters only through Kong on `http://localhost:8000`.

## Kong edge config is rendered, not committed (Slice 5e phase-4)

Kong validates the RS256 JWT at the edge on the protected routes with the **public** key. Because
each deployment holds a **different** local keypair (and CI mints a third), no single true key can
be committed. So the repo tracks a **template** and the bring-up renders the real config:

| Deployment | Template (tracked) | Rendered (gitignored) | Public key source | Rendered by |
|---|---|---|---|---|
| Compose | `kong/kong.tpl.yml` | `kong/kong.yml` | `infra/keys/jwt-rs256-public.pem` | `scripts/render-kong.sh` |
| k3d | `k8s/base/kong/kong.tpl.yml` | `k8s/base/kong/kong.yml` | `k8s/overlays/local/keys/jwt-rs256-public.pem` | `k8s/up.sh` (auto) |

`render-kong.sh` injects the public PEM into the template's `rsa_public_key` placeholder and
**fail-closes** if the placeholder survives or the key is missing. The placeholder is an invalid
PEM on purpose, so `kong config parse` (a CI gate) rejects an un-rendered template. Kong is never
given `JWT_SECRET` and never the private key — a public key verifies, it cannot forge; each service
still validates the JWT itself (defense-in-depth).

An end-to-end auth check across every route (positive + negative, exact status + body) is
`infra/scripts/edge-smoke.sh` (parameterized base URL; `:8000` works for both deployments).

## k3d

See `k8s/README.md`. `make -C infra k8s-up` handles the Kong render automatically.
