# Spec 澄清 · 一期实现一致性决议

> 状态：历史决策记录。
> 当前开发不再把本文作为第二套权威规格读取；活动规则以 `docs/spec.md`、`infra/meeting-infra/SPEC.md`、`apps/meeting-web/SPEC.md`、`packages/meeting-contracts/**` 和可校验事实源为准。
> 如果本文与活动规格冲突，以活动规格为准。

## C1. Rerank 一期是否启用

**原矛盾：**
- `docs/spec.md` §3.3 模型选型表：列了 bge-reranker-v2-m3，标注显存约 3GB
- `docs/spec.md` §9.5 检索实现：描述了 "rerank 后返回 top_n=8"
- `infra/meeting-infra/SPEC.md` §5：rerank-queue 标记"预留/后续"

**澄清结论：**
- **一期启用 Rerank，但不独立部署 rerank-queue。**
- Rerank 模型 bge-reranker-v2-m3 在 `ai-worker` 进程内 lazy-load，与 bge-m3 共用 GPU 显存。
- pgvector 召回 top_k=20 后的 RRF 精排和 rerank 在同一个请求内完成，不拆分到独立队列。
- `rerank-queue` 仅在后续需要独立扩容或分散 GPU 压力时启用，一期保持接口预留但不创建对应 RabbitMQ 队列。

**一致性动作：**
- `infra/meeting-infra/SPEC.md` §5 将 `rerank-queue` 从"预留"改为"一期进程内执行，不独立建队列"。
- Docker Compose 的 rabbitmq definitions 不创建 rerank-queue。

## C2. 声纹 Embedding 明文回写字段 — 始终携带还是可选

**原矛盾：**
- `docs/app-api-contracts.md` §6.4 speaker-candidates callback 示例中 `embedding` 是必填字段。
- `internal-callback-api.yaml` 中 `PlainSpeakerEmbedding` 的 `values` 是 `required` 的 `array`。
- 但 SPEC 提到"embedding 不得返回前端"且 `ai-worker` 不能写 TOS，如果 JSON payload 体积太大（192 维 × 12 speakers ≈ 小）其实不构成问题。

**澄清结论：**
- **始终携带明文 embedding。**
- 声纹 embedding 维度为 192（CAM++ 默认），12 个 speaker 的 FLOAT32_ARRAY 合计约 9KB，不构成 payload 过大的风险。
- `values` 字段必须始终存在、非空，不接受仅通过 `artifactUri` 引用的方式传输 speaker embedding。
- 明文传输仅允许通过 internal TLS + HMAC callback 通道；Java 收到后立即 KMS 信封加密落库，callback 成功或重试耗尽后 worker 清除进程内明文引用。

**一致性动作：**
- `internal-callback-api.yaml` 中 `PlainSpeakerEmbedding.values` 保留 `required`。（当前已正确）
- `docs/spec.md` §6.4 和 `docs/app-api-contracts.md` §6.4 均无需修改——当前表述已一致。

## C3. PARTIAL_SUCCEEDED 的 Optional Step 清单

**原矛盾：**
- SPEC 多处提到 "optional step 失败可以降级为 PARTIAL_SUCCEEDED"，但始终没有明确哪些 step 是 optional。
- 只有 `ALIGNMENT` 明确标注了"按需执行，失败不阻断"。

**澄清结论：**
一期 Optional Step（失败可降级为 PARTIAL_SUCCEEDED）为：

| Step | Optional 理由 | 降级后影响 |
|---|---|---|
| `ALIGNMENT` | 一期默认不全量执行，仅精确引用、报告导出或人工触发时按需启用 | 无影响，timestamp 精度保持 SEGMENT 级别 |
| `RAG_INDEXING` | 非 AI Pipeline 核心产出，会议转录和纪要可独立查看 | RAG 查询该会议不可用，前端提示"知识库索引失败" |
| `SPEAKER_MATCHING` | 依赖 speaker profile 库是否为空的业务条件 | 转录保持 SPEAKER_00 匿名 label，用户仍可手动确认 |

以下步骤失败不得降级，必须返回 FAILED：
`AUDIO_PREPROCESS`、`ASR`、`DIARIZATION`、`SPEAKER_EMBEDDING`、`TRANSCRIPT_MERGE`、`SUMMARY`、`EXTRACTION`。

> 说明：`ASR` 失败时转录完全不可用，无可用 artifact 来支撑 PARTIAL_SUCCEEDED；
> `DIARIZATION` 失败时 speaker label 全部缺失，合并出的转录质量不满足可用标准。
> `SPEAKER_EMBEDDING` 失败仅影响声纹匹配，不影响 ASR/Diarization 结果，
> 因此理论上也可降级为 PARTIAL_SUCCEEDED——但一期暂不降级，若 embedding 模型加载失败属于基础设施问题应直接 FAILED 以便排查。

**一致性动作：**
- `docs/spec.md` §7.3 processing_tasks 状态迁移节补充 Optional Step 清单。

## C4. 冷启动 RAG 行为 — 仅有 Transcript Chunk、纪要未生成时 RAG 是否可查

**原矛盾：**
- `docs/spec.md` §9.4 状态规则：RAG 只召回 `status=ACTIVE AND stale_status=ACTIVE` 的 chunk。
- `docs/spec.md` §9.4 底部有一句话："会议上传后，只要结构化转录 chunk 已经写入……RAG 可以在纪要生成前仅基于 transcript scope 回答；此时 response 必须带 `coverage=TRANSCRIPT_ONLY`"。
- 前端没有对应的覆盖范围提示 UI 规范。

**澄清结论：**
- **一期允许仅有 Transcript Chunk 时进行 RAG 查询。**
- 前端展示 RAG 答案区域必须顶部有覆盖范围标签：
  - `TRANSCRIPT_ONLY`：只索引了转录，纪要/待办/决策/风险尚未入库，答案可能不完整。
  - `FULL`：所有来源均已索引。
- 如果转录也未完成（即 meeting 没有任何 ACTIVE chunk），RAG 不调用 LLM，直接返回"该会议暂无可用索引内容"。
- RAG 答案缓存必须绑定 `coverage` 字段，coverage 变化后旧缓存失效。

**一致性动作：**
- `docs/spec.md` §9.4 和 `docs/app-api-contracts.md` §4.11 RAG 响应增加 `coverage` 字段。
- 前端 `shared/api/types.ts` 中 `RagQueryResponse` 增加 `coverage: "TRANSCRIPT_ONLY" | "FULL"`。
