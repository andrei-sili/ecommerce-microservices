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

# Upgrade setuptools HERE, in the runtime stage, not in the builder. python:3.12-slim ships 70.3.0
# (CVE-2025-47273, path traversal), and this stage has its own copy: the COPY above overlays files
# but leaves the base image's setuptools-70.3.0.dist-info directory in place, since the newer
# release writes a differently-named dist-info. Trivy reads dist-info, so it kept reporting 70.3.0
# even with a newer setuptools installed alongside. Letting pip do the upgrade in this stage removes
# the old dist-info properly. Fixed in 78.1.1.
RUN pip install --no-cache-dir --upgrade setuptools \
    && rm -rf /root/.cache/pip

# Copy application code
COPY app/ ./app/
COPY migrations/ ./migrations/
COPY alembic.ini ./

EXPOSE 8086

# Run Alembic migrations then start the service
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8086"]
