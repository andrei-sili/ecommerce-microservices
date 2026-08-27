# ---------- build stage ----------
FROM python:3.12-slim AS builder

WORKDIR /build

COPY pyproject.toml ./

RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir build \
    && pip install --no-cache-dir \
        fastapi \
        uvicorn[standard] \
        "sqlalchemy[asyncio]" \
        asyncpg \
        alembic \
        pydantic \
        pydantic-settings \
        aio-pika \
        prometheus-fastapi-instrumentator==8.0.2

# ---------- runtime stage ----------
FROM python:3.12-slim AS runtime

WORKDIR /app

# CVE-2026-14456 (HIGH): OpenSSL DoS via unbounded memory growth in the QUIC server,
# fixed in 3.5.7-1~deb13u2. Upgraded in place rather than by moving to a newer base
# tag: as of 2026-08-27 python:3.12-slim still resolves to the digest carrying the
# vulnerable 3.5.6-1~deb13u2, so there is no fixed tag to move to; trixie-security
# (already in the base's sources) does carry the fix.
# This must live in the RUNTIME stage — it is the stage that gets scanned and shipped.
# Scoped to the three packages the scan names (all built from the openssl source
# package) instead of a blanket `apt-get upgrade`, to keep the layer small.
# The version assertion turns a silently skipped upgrade (`--only-upgrade` is a no-op
# for a package it cannot find) into a failed build instead of a red scan later.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade \
        openssl \
        libssl3t64 \
        openssl-provider-legacy \
    && rm -rf /var/lib/apt/lists/* \
    && for pkg in openssl libssl3t64 openssl-provider-legacy; do \
           dpkg --compare-versions "$(dpkg-query -W -f='${Version}' "$pkg")" ge 3.5.7-1~deb13u2 \
           || { echo "FATAL: $pkg is older than 3.5.7-1~deb13u2 (CVE-2026-14456)" >&2; exit 1; }; \
       done

# Copy installed packages from builder
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin

# The runtime needs no installer, and pip itself is the source of the image-gate
# findings: Trivy parses pip's vendored-dependency SBOM (pip/_vendor/bom.cdx.json),
# which pins setuptools 70.3.0 (CVE-2025-47273) and msgpack 1.1.2
# (GHSA-6v7p-g79w-8964) as pip-internal vendored copies that no standalone
# `pip install --upgrade` can ever touch. Removing pip (and ensurepip's bundled
# pip wheel) clears the findings at the root and drops a package installer from
# the production image.
RUN rm -rf /usr/local/lib/python3.12/site-packages/pip \
           /usr/local/lib/python3.12/site-packages/pip-*.dist-info \
           /usr/local/lib/python3.12/ensurepip \
           /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.12

# Copy application code
COPY app/ ./app/
COPY migrations/ ./migrations/
COPY alembic.ini ./

EXPOSE 8086

# Run Alembic migrations then start the service
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8086"]
