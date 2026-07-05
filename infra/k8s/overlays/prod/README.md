# prod overlay — GHCR digest-pinned images

Same base as `overlays/local`, with the six service images repointed to the GHCR
images pushed by the `push-images` workflow on every merge to `main`, pinned by
**digest**: `ghcr.io/andrei-sili/ecommerce-microservices/<svc>-service@sha256:<digest>`.

- **Why digest, not tag (5d-h):** a digest is content-addressed and truly immutable.
  GHCR has no native immutable tags, so even the `sha-<longsha>` tag could in
  principle be re-pushed; the digest cannot. Rollback = set any prior digest.
- **The committed digest is a placeholder** (all zeros): at PR time no image has been
  pushed yet. Every `push-images` run prints the exact per-service ref in each matrix
  job's **run summary** (Actions run page). Pin at deploy time by copying it:

  ```bash
  cd infra/k8s/overlays/prod
  # one per service; the ref comes verbatim from the push-images run summary
  kustomize edit set image \
    "ecommerce/user-service=ghcr.io/andrei-sili/ecommerce-microservices/user-service@sha256:<digest>"
  ```

  Digests differ per service (unlike the uniform `sha-<longsha>` tag), so there is no
  single-variable loop — copy each service's line from the run summary.
- **Never a moving tag in prod** (`latest`/`dev`/`main`): a Deployment must reference
  immutable content so a rollout is reproducible and rollback is exact.
- **Secrets/config are deliberately absent.** There is no real cluster yet; this overlay
  carries no configMap/secretGenerator, so it renders without any `secret.env`. When a
  cluster exists, secrets come from an external store (External Secrets Operator +
  Vault/OpenBao, or Sealed Secrets) — never a committed `kind: Secret` (base64 is not
  encryption; CI's Committed-Secret guard fails any populated Secret under `infra/`).
- Validated in CI (`validate-k8s`) and locally via `make -C infra k8s-validate`:
  `kustomize build infra/k8s/overlays/prod | kubeconform -strict`.

See `infra/k8s/README.md` ("Registry & CI/CD") for the full flow.
