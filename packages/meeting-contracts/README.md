# meeting-contracts

跨工程契约单一事实来源。

详细工程规格见 [`SPEC.md`](SPEC.md)。

内容：

```text
openapi/public-api.yaml
openapi/internal-callback-api.yaml
schemas/rabbitmq/processing-task-message.schema.json
schemas/common/error-codes.yaml
schemas/common/enums.yaml
```

MVP 可先手写维护，后续再接入 SDK 生成和私有 npm / Maven / PyPI 发布。
