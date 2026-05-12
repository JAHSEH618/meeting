# meeting-contracts

跨工程契约单一事实来源。

详细工程规格见 [`SPEC.md`](SPEC.md)。

内容：

```text
openapi/public-api.yaml
openapi/internal-callback-api.yaml
openapi/ai-worker-internal-api.yaml
schemas/rabbitmq/processing-task-message.schema.json
schemas/common/error-codes.yaml
schemas/common/enums.yaml
```

当前已提供最小可校验骨架：Public OpenAPI、Internal Callback OpenAPI、AI Worker Internal OpenAPI、RabbitMQ task JSON Schema、枚举和错误码。后续再接入 SDK 生成和私有 npm / Maven / PyPI 发布。
