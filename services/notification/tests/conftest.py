"""Test fixtures.

Sets SKIP_CONSUMER before any app module is imported so the Settings singleton
picks it up. Uses testcontainers (session-scoped, sync) for an isolated
PostgreSQL instance; each test gets a fresh engine + schema so there are no
cross-test event-loop or data conflicts.
"""

import os

# Must be set before importing Settings (module-level singleton in config.py)
os.environ["SKIP_CONSUMER"] = "true"

from collections.abc import AsyncGenerator

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.models.db import Base


@pytest.fixture(scope="session")
def pg_container():  # type: ignore[return]
    """Start a throwaway PostgreSQL container once for the whole test session."""
    from testcontainers.postgres import PostgresContainer

    with PostgresContainer("postgres:16-alpine") as pg:
        yield pg


@pytest.fixture(scope="session")
def test_db_url(pg_container) -> str:  # type: ignore[return-value]
    """Build the asyncpg URL from the testcontainers-managed container."""
    host = pg_container.get_container_host_ip()
    port = pg_container.get_exposed_port(5432)
    return (
        f"postgresql+asyncpg://{pg_container.username}:{pg_container.password}"
        f"@{host}:{port}/{pg_container.dbname}"
    )


@pytest_asyncio.fixture
async def db_session(test_db_url: str) -> AsyncGenerator[AsyncSession, None]:
    """Create a fresh schema, yield a session, then drop the schema.

    Uses NullPool so no asyncpg connections are cached between tests — this
    prevents 'Future attached to a different loop' errors when each test runs
    in its own asyncio event loop.
    """
    engine = create_async_engine(test_db_url, poolclass=NullPool)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    async with factory() as session:
        yield session
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()
