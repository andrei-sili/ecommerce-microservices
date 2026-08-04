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

# Copy installed packages from builder
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin

# The runtime needs no installer, and pip itself is the source of the image-gate
# findings: Trivy reads pip/_vendor/vendor.txt, which pins setuptools 70.3.0
# (CVE-2025-47273) and msgpack (GHSA-6v7p-g79w-8964) as pip-internal vendored
# copies that no standalone `pip install --upgrade` can ever touch. Removing pip
# (and ensurepip's bundled pip wheel) clears the findings at the root and drops a
# package installer from the production image.
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
