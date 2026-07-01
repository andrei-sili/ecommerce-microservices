# Infra entrypoints. Run from the repo root as `make -C infra <target>`.
# Compose stays the untouched baseline; the k8s-* targets drive the Wave 5a k3d stack.
.PHONY: k8s-up k8s-down k8s-validate

K8S_DIR := $(dir $(lastword $(MAKEFILE_LIST)))k8s
OVERLAY := $(K8S_DIR)/overlays/local

# Bring up the local k3d cluster + apply the local overlay (one command).
k8s-up:
	bash $(K8S_DIR)/up.sh

# Delete the cluster (DATA LOSS — see infra/k8s/README.md).
k8s-down:
	bash $(K8S_DIR)/down.sh

# Render + schema-validate the manifests (no cluster needed). Mirrors the CI gate.
# Uses a throwaway secret.env (placeholders) if none exists so the generator can build.
k8s-validate:
	@test -f $(OVERLAY)/secret.env || cp $(OVERLAY)/secret.env.example $(OVERLAY)/secret.env
	kustomize build $(OVERLAY) | kubeconform -strict -summary -kubernetes-version 1.32.0 -schema-location default
