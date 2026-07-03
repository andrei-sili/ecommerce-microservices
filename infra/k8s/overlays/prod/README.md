# prod overlay — GHCR SHA-pinned images

Same base as `overlays/local`, with the six service images repointed to the immutable
GHCR tags pushed by the `push-images` workflow on every merge to `main`:
`ghcr.io/andrei-sili/ecommerce-microservices/<svc>-service:sha-<longsha>`.

- **The committed `newTag` is a placeholder** (all zeros): at PR time no image has been
  pushed yet. Pin the real commit at deploy time:

  ```bash
  cd infra/k8s/overlays/prod
  SHA=$(git rev-parse HEAD)   # or any prior main commit for rollback
  for svc in user product cart order payment notification; do
    kustomize edit set image \
      "ecommerce/${svc}-service=ghcr.io/andrei-sili/ecommerce-microservices/${svc}-service:sha-${SHA}"
  done
  ```

- **Never a moving tag in prod** (`latest`/`dev`/`main`): a Deployment must reference an
  exact SHA so a rollout is reproducible and rollback = set any prior SHA.
- **Secrets/config are deliberately absent.** There is no real cluster yet; this overlay
  carries no configMap/secretGenerator, so it renders without any `secret.env`. When a
  cluster exists, secrets come from an external store (External Secrets Operator +
  Vault/OpenBao, or Sealed Secrets) — never a committed `kind: Secret` (base64 is not
  encryption; CI's Committed-Secret guard fails any populated Secret under `infra/`).
- Validated in CI (`validate-k8s`) and locally via `make -C infra k8s-validate`:
  `kustomize build infra/k8s/overlays/prod | kubeconform -strict`.

See `infra/k8s/README.md` ("Registry & CI/CD") for the full flow.
