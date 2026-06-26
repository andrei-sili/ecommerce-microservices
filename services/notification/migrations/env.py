import asyncio
import os
import sys
from logging.config import fileConfig
from typing import Any

from alembic import context
from sqlalchemy.ext.asyncio import create_async_engine

# Ensure the service root is on sys.path so 'app' is importable
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.models.db import Base  # noqa: E402

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _get_url() -> str:
    # env var takes precedence over alembic.ini (needed for docker + CI)
    return os.getenv("DATABASE_URL", config.get_main_option("sqlalchemy.url") or "")


def do_run_migrations(connection: Any) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    url = _get_url()
    engine = create_async_engine(url)
    async with engine.connect() as connection:
        await connection.run_sync(do_run_migrations)
    await engine.dispose()


def run_migrations_online() -> None:
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    raise RuntimeError("Offline mode not supported for async engine")
else:
    run_migrations_online()
