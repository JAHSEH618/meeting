from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_id: str = "worker_dev_001"
    meeting_api_base_url: str = "http://localhost:8080"
    callback_hmac_secret: str = "dev-secret"
    model_config = SettingsConfigDict(env_prefix="AI_WORKER_", env_file=".env")


settings = Settings()
