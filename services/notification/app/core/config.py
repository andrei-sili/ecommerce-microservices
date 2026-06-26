from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", case_sensitive=True, extra="ignore")

    # Database
    DATABASE_URL: str = (
        "postgresql+asyncpg://notification:notification@localhost:5437/notification_db"
    )

    # RabbitMQ
    RABBITMQ_URL: str = "amqp://guest:guest@localhost:5672/"

    # Email provider (empty = sandbox mode)
    EMAIL_API_KEY: str = ""

    # Consumer tuning
    MAX_RETRIES: int = 5

    # Set true in tests to skip RabbitMQ consumer startup
    SKIP_CONSUMER: bool = False


settings = Settings()
