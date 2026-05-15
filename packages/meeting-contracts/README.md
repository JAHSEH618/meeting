# meeting-contracts

跨工程契约单一事实来源。

详细工程规格见 [`SPEC.md`](SPEC.md)。

内容：

```text
openapi/public-api.yaml
openapi/internal-callback-api.yaml
openapi/ai-worker-internal-api.yaml
schemas/rabbitmq/processing-task-message.schema.json
schemas/rabbitmq/export-job-message.schema.json
schemas/common/error-codes.yaml
schemas/common/enums.yaml
```

当前已提供最小可校验骨架：Public OpenAPI、Internal Callback OpenAPI、AI Worker Internal OpenAPI、RabbitMQ worker / export JSON Schema、枚举和错误码。后续再接入 SDK 生成和私有 npm / Maven / PyPI 发布。

## 本地命令

```bash
npm install
npm run check
npm run codegen:check-temp
```

`npm run check` 覆盖 Spectral、JSON Schema、枚举一致性和 fixtures 校验；`codegen:check-temp` 只生成到临时目录并做 diff，不写目标工程。

## MVP-0 状态

阶段 1 已固定 auth、meeting、processing task、task step、SSE event、internal callback 和 RabbitMQ task message 的最小契约；valid / invalid fixtures 已覆盖 public API、internal callback 和 worker message fail-fast 场景。
