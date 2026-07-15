#!/usr/bin/env bash
# Render a Kong declarative config from its committed template by injecting the local RS256
# PUBLIC key (Slice 5e phase-4). The repo tracks only the TEMPLATE (kong.tpl.yml); the rendered
# kong.yml holds key material and is gitignored. Every deployment renders with ITS OWN local key
# (compose infra/keys/, k3d overlays/local/keys/, CI an ephemeral pair) — there is no single true
# key to commit, which is exactly why this step exists.
#
# Usage:
#   render-kong.sh                                  # compose mode: ensure infra/keys/ keypair,
#                                                   #   render infra/kong/kong.tpl.yml -> kong.yml
#   render-kong.sh --template T --out O --public-key K   # render one shape (k3d / CI drive this)
#
# Guarantees (D1 conditions): idempotent + deterministic for a given key; fail-closed if the
# template/key is missing or the placeholder survived the substitution (never emit a config that
# still carries the raw placeholder). The placeholder is an invalid PEM, so `kong config parse`
# also rejects the un-rendered template — a second, independent guard enforced in CI.
set -euo pipefail

PLACEHOLDER='__JWT_RS256_PUBLIC_KEY_PEM__'
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

die() { printf 'render-kong: ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf 'render-kong: %s\n' "$*"; }

# Substitute the single placeholder line with the PEM, preserving the placeholder line's own
# indentation so the YAML block scalar stays valid regardless of the template's nesting depth.
render() {
  local template=$1 out=$2 pubkey=$3
  [ -f "$template" ] || die "template not found: $template"
  [ -s "$pubkey" ]   || die "public key missing or empty: $pubkey (generate it first — see runbook § JWT keys)"
  grep -q "$PLACEHOLDER" "$template" \
    || die "template $template has no $PLACEHOLDER placeholder — refusing to render blindly"
  # Fail-closed on a directory at $out: Docker's short-syntax bind-mount creates the mount source
  # as a DIRECTORY when a bare `docker compose up` runs before the first render. Without this guard
  # `mv` would move the temp file INTO that directory and exit 0 — a false "rendered" success in the
  # exact first-touch failure mode this template split introduces.
  [ -d "$out" ] && die "output path is a directory: $out — Docker likely created it as a bind-mount source before the first render. Delete it (rm -rf '$out') and re-run render-kong.sh."

  local tmp="$out.tmp.$$"
  awk -v pemfile="$pubkey" -v placeholder="$PLACEHOLDER" '
    index($0, placeholder) > 0 {
      match($0, /^[ \t]*/); indent = substr($0, 1, RLENGTH)
      while ((getline line < pemfile) > 0) print indent line
      close(pemfile)
      next
    }
    { print }
  ' "$template" > "$tmp"

  # Fail-closed: the rendered file must exist, be non-empty, and carry no surviving placeholder.
  [ -s "$tmp" ] || { rm -f "$tmp"; die "render produced an empty file for $out"; }
  if grep -q "$PLACEHOLDER" "$tmp"; then
    rm -f "$tmp"
    die "placeholder survived rendering $out — the public key was not injected (fail-closed)"
  fi
  mv -T "$tmp" "$out"  # -T: never move INTO a dir (belt-and-braces with the [ -d ] guard above)
  log "rendered $out from $(basename "$template") (public key: $pubkey)"
}

# Compose mode: generate the gitignored dev keypair if absent (parity with k3d up.sh), then render.
compose_mode() {
  local keys="$INFRA_DIR/keys"
  local priv="$keys/jwt-rs256-private.pem" pub="$keys/jwt-rs256-public.pem"
  if [ ! -f "$priv" ]; then
    log "no JWT signing key yet — generating an RSA-2048 keypair under infra/keys/"
    mkdir -p "$keys"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$priv"
    rm -f "$pub"
  fi
  [ -f "$pub" ] || openssl pkey -in "$priv" -pubout -out "$pub"
  # DEV-ONLY: the compose containers run as uid 999 while openssl writes the private key 0600 owned
  # by the host uid, so user-service can't read it without this. Acceptable ONLY for the gitignored
  # ephemeral dev key — never a real key (runbook § JWT keys, 1a chmod gotcha).
  chmod 0644 "$priv"
  render "$INFRA_DIR/kong/kong.tpl.yml" "$INFRA_DIR/kong/kong.yml" "$pub"
}

main() {
  if [ "$#" -eq 0 ]; then
    compose_mode
    return
  fi
  local template="" out="" pubkey=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --template)   template=$2; shift 2 ;;
      --out)        out=$2;      shift 2 ;;
      --public-key) pubkey=$2;   shift 2 ;;
      *) die "unknown argument: $1" ;;
    esac
  done
  [ -n "$template" ] && [ -n "$out" ] && [ -n "$pubkey" ] \
    || die "usage: render-kong.sh --template T --out O --public-key K"
  render "$template" "$out" "$pubkey"
}

main "$@"
