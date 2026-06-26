from urllib.parse import quote

from pydantic import computed_field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", case_sensitive=True, extra="ignore")

    # Database
    DATABASE_URL: str = (
        "postgresql+asyncpg://notification:notification@localhost:5437/notification_db"
    )

    # RabbitMQ — individual parts (same names as the rest of the platform)
    RABBITMQ_HOST: str = "rabbitmq"
    RABBITMQ_PORT: int = 5672
    RABBITMQ_USER: str = "guest"
    RABBITMQ_PASSWORD: str = "guest"
    RABBITMQ_VHOST: str = "/"

    # Optional verbatim override: when set non-empty, used as-is and the parts above are ignored.
    RABBITMQ_URL: str = ""

    # Email provider (empty = sandbox mode)
    EMAIL_API_KEY: str = ""

    # Consumer tuning
    MAX_RETRIES: int = 5

    # Set true in tests to skip RabbitMQ consumer startup
    SKIP_CONSUMER: bool = False

    @computed_field  # type: ignore[prop-decorator]
    @property
    def broker_url(self) -> str:
        """Effective AMQP connection URL.

        Uses RABBITMQ_URL verbatim if set; otherwise assembles from the individual
        RABBITMQ_* parts used by the rest of the platform, with the password and
        vhost percent-encoded so aio-pika targets the correct vhost.
        """
        if self.RABBITMQ_URL:
            return self.RABBITMQ_URL
        password = quote(self.RABBITMQ_PASSWORD, safe="")
        vhost = quote(self.RABBITMQ_VHOST, safe="")
        return f"amqp://{self.RABBITMQ_USER}:{password}@{self.RABBITMQ_HOST}:{self.RABBITMQ_PORT}/{vhost}"


settings = Settings()
