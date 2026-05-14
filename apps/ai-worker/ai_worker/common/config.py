from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_id: str = "worker_dev_001"
    meeting_api_base_url: str = "http://localhost:8080"
    callback_hmac_secret: str = "dev-secret"
    internal_api_hmac_secret: str = "dev-internal-secret"
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "meeting"
    rabbitmq_password: str = "meeting_dev"
    rabbitmq_virtual_host: str = "/"
    rabbitmq_task_queues: str = "audio-cpu-queue,gpu-asr-queue,gpu-diar-queue,gpu-speaker-queue,embed-queue"
    callback_max_retries: int = 3
    artifact_store_root: str = ".artifacts"
    model_config = SettingsConfigDict(env_prefix="AI_WORKER_", env_file=".env")


settings = Settings()
