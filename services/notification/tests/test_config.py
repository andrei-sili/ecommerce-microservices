"""Tests for Settings.broker_url — no RabbitMQ or DB required."""

from urllib.parse import quote

from app.core.config import Settings

# Minimal DATABASE_URL so Settings validates (no real DB needed)
_DB = "postgresql+asyncpg://x:x@localhost/x"


def test_broker_url_built_from_parts_default_vhost() -> None:
    """Parts are assembled into amqp://... with vhost '/' encoded as %2F."""
    s = Settings(
        RABBITMQ_USER="ecommerce_mq",
        RABBITMQ_PASSWORD="s3cr3t",
        RABBITMQ_HOST="rabbitmq",
        RABBITMQ_PORT=5672,
        RABBITMQ_VHOST="/",
        RABBITMQ_URL="",
        DATABASE_URL=_DB,
    )
    assert s.broker_url == "amqp://ecommerce_mq:s3cr3t@rabbitmq:5672/%2F"


def test_broker_url_password_special_chars_encoded() -> None:
    """Special characters in the password are percent-encoded."""
    password = "p@$$w0rd!"
    s = Settings(
        RABBITMQ_USER="ecommerce_mq",
        RABBITMQ_PASSWORD=password,
        RABBITMQ_HOST="rabbitmq",
        RABBITMQ_PORT=5672,
        RABBITMQ_VHOST="/",
        RABBITMQ_URL="",
        DATABASE_URL=_DB,
    )
    expected = f"amqp://ecommerce_mq:{quote(password, safe='')}@rabbitmq:5672/%2F"
    assert s.broker_url == expected
    # The raw '@' in the password must not appear unencoded in the authority
    assert s.broker_url.count("@") == 1


def test_broker_url_override_honored() -> None:
    """Non-empty RABBITMQ_URL is returned verbatim; parts are ignored."""
    override = "amqp://custom:secret@mybroker:5673/myvhost"
    s = Settings(
        RABBITMQ_URL=override,
        DATABASE_URL=_DB,
    )
    assert s.broker_url == override


def test_broker_url_empty_override_falls_back_to_parts() -> None:
    """Empty string RABBITMQ_URL falls through to the parts-based builder."""
    s = Settings(
        RABBITMQ_USER="user",
        RABBITMQ_PASSWORD="pass",
        RABBITMQ_HOST="host",
        RABBITMQ_PORT=5672,
        RABBITMQ_VHOST="/",
        RABBITMQ_URL="",
        DATABASE_URL=_DB,
    )
    assert s.broker_url.startswith("amqp://user:pass@host:5672/")
    assert s.broker_url.endswith("%2F")
