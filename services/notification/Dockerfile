# ---------- build stage ----------
FROM python:3.12-slim AS builder

WORKDIR /build

COPY pyproject.toml ./

# setuptools is upgraded, not just pip: python:3.12-slim ships 70.3.0, which carries
# CVE-2025-47273 (path traversal). site-packages is copied wholesale into the runtime stage below,
# so the base image's version would otherwise ship in the final image. Fixed in 78.1.1.
RUN pip install --no-cache-dir --upgrade pip setuptools \
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

# Copy application code
COPY app/ ./app/
COPY migrations/ ./migrations/
COPY alembic.ini ./

EXPOSE 8086

# Run Alembic migrations then start the service
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8086"]
