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
| 3D-Speaker CAM++ | `AI_WORKER_CAM_PLUS_MODELS_DIR` | `/opt/models/cam_plus/v1` | 一并设 `AI_WORKER_USE_FAKE_SPEAKER_RUNTIME=false` |

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

---

## Staging Fixtures（仅用于 Phase J 验收，不是生产准入）

> ⚠️ **本节为 staging-only。** 下表里的 SHA-256 来自 `apps/ai-worker/scripts/stage_mock_weights.py`
> 生成的确定性 mock 权重文件，**不是**真实模型权重的 hash，**不能**被填进上面的"模型 Checksum"生产表。
> 真实权重 + 真实 hash 必须等基础设施 owner 完成下载、审批、上传内网制品库后再录入生产表。

用途：让 `docs/runbooks/phase-j-acceptance.md` §J4（checksum guard）可以在没有真实权重的开发机或 CI
节点上完整跑通——包括"故意改一个字节 → `/internal/ready` 返回 503"这一项。

### 生成步骤

```bash
cd apps/ai-worker
# 默认 staging root：<repo>/.cache/staging-models（已在 .gitignore 中）
# 也可显式指定，例如 sudo install -d -o $USER /opt/models 后传 --root /opt/models
uv run python scripts/stage_mock_weights.py --format shell    # 给 shell 用
uv run python scripts/stage_mock_weights.py --format dotenv   # 给 .env 用
uv run python scripts/stage_mock_weights.py --format table    # 给本文档用
```

脚本会:

1. 在 `<root>/{bge-m3,bge-reranker-v2-m3,qwen3-asr-1.7b,pyannote,cam_plus}/...` 下生成确定性 mock 权重文件;
2. 对每个目录调用 `compute_checksum()`（与 ai-worker 运行时使用同一函数）;
3. 输出可直接 `eval` / `source` 的 `AI_WORKER_*_MODELS_DIR` + `AI_WORKER_*_EXPECTED_CHECKSUM` 对。

设好 env 后启动 ai-worker，`/internal/models` 的 checksum 字段应与下表完全一致，`/internal/ready`
返回 200。改任一权重文件一个字节再重启，`/internal/models` 对应模型 `status=ERROR` + `lastError`
说明 checksum 不匹配，`/internal/ready` 返回 503。

### 当前 mock checksums（`scripts/stage_mock_weights.py` 输出，2026-06-18 重算可复现）

| 模型 | 相对路径 | SHA256 (staging mock) |
|---|---|---|
| bge-m3 | `bge-m3/v1` | `sha256:e298897bddb95005b53acf3664c0a57947ec58d6313ef6d769acd31d0fd0afe6` |
| bge-reranker-v2-m3 | `bge-reranker-v2-m3/v1` | `sha256:794d3b4b6f9b2991ab0c8f7e3790fea6b5f70d3a233939547716daaf91b56f5d` |
| qwen3-asr-1.7b | `qwen3-asr-1.7b/v2026.05.1` | `sha256:41b21abe05af064ba0737ab5622152443495c114a1a73a5b1d494bba656dc0e3` |
| pyannote/speaker-diarization-3.1 | `pyannote/v3.1` | `sha256:b434841f09abc5ac194e04daf5e56e72bdba21c1fcaf5852df02d52136eb6165` |
| 3D-Speaker CAM++ | `cam_plus/v1` | `sha256:79d265f8347e955244198b5c537378f8638a8cfa5e00069fd01069b8b864c8d4` |

### 不在 staging 清单里的模型

`Qwen3-ForcedAligner-0.6B` 仍按本文档前述章节的 license 流程走，但因为
`apps/ai-worker/ai_worker/common/config.py` 还没有它的 `*_models_dir` env，
`apps/ai-worker/ai_worker/interfaces/api/main.py:_all_model_infos` 也不会枚举它，所以这一版
Phase J 验收不为它生成 staging hash。等 alignment runtime 真正接入后再补。
