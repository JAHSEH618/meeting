# 模型准入清单 · 一期

> 本文是 `docs/spec.md` §3.3 模型选型的补充：记录每个模型的 license、来源 URL、checksum 和准入审批状态。
> 后续运维可在 `model_registry` 表（DDL 已建）中同步这些记录，或在 git 中维护 JSON 文件。

## 项目本体 License

**状态：待定。** README.md 中标注 `[待定]`。

建议在 code freeze 前选择以下之一：
- **内部使用** → `Proprietary` / `All Rights Reserved`，仓库设为 private。
- **开源** → `Apache 2.0` 或 `MIT`，需确认所有依赖的 license 兼容。

在选定之前，所有模型准入审批以"内部使用，不对外分发"为前提。

## 一期模型 License 审批

| 模型 | 来源 | License | 商用准入 | 审批状态 | 审批人 | 审批日期 |
|---|---|---|---|---|---|---|
| Qwen3-ASR-1.7B | HuggingFace / ModelScope (Alibaba) | Apache 2.0 | 需确认模型卡条款 | ⏳ 待审批 | — | — |
| Qwen3-ForcedAligner-0.6B | HuggingFace / ModelScope | Apache 2.0 | 需确认模型卡条款 | ⏳ 待审批 | — | — |
| pyannote/speaker-diarization-3.1 | HuggingFace (pyannote) | MIT | 需确认 pyannote 模型条款 | ⏳ 待审批 | — | — |
| 3D-Speaker CAM++ | ModelScope | Apache 2.0 或模型卡条款 | 需确认 | ⏳ 待审批 | — | — |
| BAAI/bge-m3 | HuggingFace (BAAI) | MIT | ✅ MIT 许可 | ⏳ 待审批 | — | — |
| BAAI/bge-reranker-v2-m3 | HuggingFace (BAAI) | Apache 2.0 | ✅ Apache 2.0 许可 | ⏳ 待审批 | — | — |
| DashScope (qwen-plus) | 阿里云 DashScope | API 服务 | 需 DPA / 数据处理协议 | ⏳ 待审批 | — | — |

## 准入检查清单

每个模型在进入生产前必须完成以下检查：

- [ ] License 文本已下载并存入 `docs/licenses/` 目录
- [ ] 商用条款已确认（不包含对内部使用禁止的条款）
- [ ] 权重 checksum (SHA256) 已记录
- [ ] 权重已上传至内网制品库（离线部署，不依赖 HuggingFace 运行时下载）
- [ ] DPA / 数据保留 / 训练使用 / 跨境传输条款已确认（DashScope）
- [ ] 审批人已签署模型准入记录

## 模型 Checksum（待填充——需等权重实际下载后生成）

权重 checksum 计算方法（Phase 8.4.1.b）：
1. 将权重文件全部解压到 `/opt/models/<model>/<version>/` 目录；
2. 在 ai-worker 容器中调用 `python -c "from ai_worker.observability.model_checksum import compute_checksum; print(compute_checksum('/opt/models/<model>/<version>'))"`，或者本地 `cd apps/ai-worker && uv run python -c '...'`；
3. 将结果（形如 `sha256:abcd…`）粘贴到对应行；
4. 同步写入 `model_registry` 表（启动时 seed migration）；
5. 上线后通过 `GET /internal/models` 校验 ai-worker 进程的 in-memory checksum 与本表一致——不一致即拒绝 ready。

| 模型 | SHA256 | 计算时间 | 计算人 |
|---|---|---|---|
| Qwen3-ASR-1.7B | `<pending — download weights first>` | — | — |
| Qwen3-ForcedAligner-0.6B | `<pending>` | — | — |
| pyannote/speaker-diarization-3.1 | `<pending>` | — | — |
| 3D-Speaker CAM++ | `<pending>` | — | — |
| BAAI/bge-m3 | `<pending>` | — | — |
| BAAI/bge-reranker-v2-m3 | `<pending>` | — | — |

## 内网制品路径（模板）

```text
nexus://models/qwen3-asr-1.7b/v2026.05.1/
nexus://models/qwen3-forced-aligner-0.6b/v2026.05.1/
nexus://models/pyannote/v3.1/
nexus://models/cam_plus/v1/
nexus://models/bge-m3/v1/
nexus://models/bge-reranker-v2-m3/v1/
```

## 运行时挂载点

容器内每个模型必须固定挂载到 `/opt/models/<model>` 下，并通过 env 告诉
ai-worker 真实路径。下表是 prod / staging 推荐配置（dev / CI 留空即
回退到 deterministic fake runtime）：

| 模型 | env 变量 | 推荐挂载点 | 备注 |
|---|---|---|---|
| Qwen3-ASR-1.7B | `AI_WORKER_QWEN3_ASR_MODELS_DIR` | `/opt/models/qwen3-asr-1.7b/v2026.05.1` | 一并设 `AI_WORKER_USE_FAKE_ASR_RUNTIME=false` |
| pyannote/speaker-diarization-3.1 | `AI_WORKER_PYANNOTE_MODELS_DIR` | `/opt/models/pyannote/v3.1` | 一并设 `AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false` |
| BAAI/bge-m3 | `AI_WORKER_BGE_M3_MODELS_DIR` | `/opt/models/bge-m3/v1` | 一并设 `AI_WORKER_USE_FAKE_RUNTIME=false` |
| BAAI/bge-reranker-v2-m3 | `AI_WORKER_BGE_RERANKER_MODELS_DIR` | `/opt/models/bge-reranker-v2-m3/v1` | 同上 |
| Qwen3-ForcedAligner-0.6B | 暂未接入 runtime — pipeline 仍走轻量对齐 | — | 待 alignment 改造再开 env |
| 3D-Speaker CAM++ | 暂未接入 runtime — speaker matching 走 mock | — | 待 speaker matching 改造再开 env |

Dockerfile 已注入 `HF_HUB_OFFLINE=1` + `TRANSFORMERS_OFFLINE=1`，因此
即使代码里 `from_pretrained("...")` 也不会触发联网下载——挂载缺失会
直接 `FileNotFoundError`，对应 runtime 的 `status=ERROR` +
`/internal/models` 的 `lastError` 字段会暴露原因，prod ready 探针
拒绝转入 healthy。

## 后续步骤

1. 由架构 owner 或法务确认项目本体 License，更新 `README.md` 和根目录 `LICENSE` 文件。
2. 由基础设施 owner 下载所有模型权重，生成 SHA256，上传内网制品库。
3. 由合规负责人逐模型完成 license 审批并在本文件中更新审批状态。
4. 审批完成后，将每条模型记录同步写入 `model_registry` 表。
