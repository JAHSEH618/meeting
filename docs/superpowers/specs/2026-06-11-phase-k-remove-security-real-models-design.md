# Phase K：移除安全等级 + 集成真实模型

**日期**：2026-06-11  
**作者**：战略调整会话（用户 + Claude）  
**状态**：Approved  
**分支**：`phase-k-remove-security-level`

---

## 1. 背景与目标

### 1.1 战略决策

基于 2026-06-11 战略审查会议，确定三个关键调整：

1. **不做会议分级**：移除 `securityLevel` 枚举，所有会议文本可发送 DashScope，简化架构
2. **保持双前端**：`meeting-web` 和 `ai-worker-web` 独立维护（阶段性方案，Mac 开发期）
3. **集成全部 5 个真实模型**：Qwen3-ASR、pyannote 3.3、CAM++、bge-m3、bge-reranker-v2-m3

### 1.2 当前问题

- **架构复杂度**：安全等级检查贯穿 contracts → Java LLM gateway → 前端，维护成本高
- **功能受限**：一期 `CONFIDENTIAL`/`SECRET` 会议 LLM 功能 fail-closed，产品只有 50% 可用性
- **fake 数据**：5 个模型全是 fake 实现，无法演示真实转录质量

### 1.3 目标

**Phase K（v1.1，3 周）**：
- 移除安全等级检查，LLM 调用无限制
- 集成 5 个真实模型，端到端真实数据
- 通过 Phase J 9 项验收，可交付演示版本

### 1.4 约束

- **Java 仍是业务源头**：ai-worker 不写权限逻辑
- **一次性交付**：Phase K 完成所有改动（不分 Phase K/L）
- **保持现有架构边界**：COLA-V5、双 HMAC、outbox 模式不变
- **worker-web 阶段性方案**：暂不规划废弃时间点，等 N 卡上线后再决策

---

## 2. 架构变更

### 2.1 移除安全等级

#### 影响面分析

| 层 | 删除项 | 修改项 |
|---|--------|--------|
| **Contracts** | `securityLevel` 枚举（4 值） | CreateMeetingRequest、MeetingDTO、UpdateMeetingRequest |
| **Java Domain** | SecurityLevel.java、SecurityGate.java、DashScopeSecurityGate.java、SecurityLevelValidator.java | Meeting 聚合、LlmGateway 接口 |
| **Java App** | checkSecurityLevel() 调用链 | MeetingApplicationService 创建/更新逻辑 |
| **Java Infrastructure** | `security_level` 列（DDL） | 无 |
| **Web** | SecurityLevelBlockedNotice.tsx、SecurityLevelSelect.tsx | MeetingCreatePage 表单 |

#### Flyway 迁移

```sql
-- V202606110001__remove_security_level.sql
ALTER TABLE meetings DROP COLUMN IF EXISTS security_level;

-- 回滚预案（手动执行，不纳入迁移链）
-- ALTER TABLE meetings ADD COLUMN security_level VARCHAR(20) DEFAULT 'INTERNAL';
```

#### 合规说明

移除安全等级后，**所有会议文本可发送 DashScope**：
- **文档标注**：Release Notes、用户协议明确"系统调用云端 API 生成纪要"
- **用户责任**：不应上传包含机密信息的音频
- **后续扩展**：Phase L 可选实现本地 LLM（vLLM + Qwen2.5-14B）作为"企业版"功能

---

### 2.2 真实模型集成

#### 模型清单

| 模型 | 用途 | 显存 | 权重路径 | Checksum 位置 |
|------|------|------|----------|---------------|
| **Qwen3-ASR**（FunASR） | 语音识别 | ~8GB | `/opt/models/qwen3-asr/v1` | `docs/model-registry.md` |
| **pyannote 3.3** | 说话人分离 | ~2GB | `/opt/models/pyannote/v3.3` | 同上 |
| **CAM++** | 声纹 embedding | ~1.5GB | `/opt/models/cam++/v1` | 同上 |
| **bge-m3** | 文本 embedding | ~2GB | `/opt/models/bge-m3/v1` | 同上 |
| **bge-reranker-v2-m3** | 重排序 | ~1GB | `/opt/models/bge-reranker-v2-m3/v1` | 同上 |

**总显存需求**：~14.5GB（模型） + 4GB（推理缓冲） + 3GB（OS） = **21.5GB**（RTX 4090 24GB 安全）

#### 加载策略

```python
# ai_worker/model_runtime/loader.py

class ModelLoader:
    def __init__(self):
        self.offline_mode = os.getenv("AI_WORKER_MODEL_OFFLINE_MODE", "true") == "true"
    
    def load_with_checksum_verification(self, model_name: str, model_dir: Path) -> Any:
        """加载模型前验证 checksum"""
        expected = os.getenv(f"AI_WORKER_{model_name.upper()}_EXPECTED_CHECKSUM")
        if not expected:
            raise ValueError(f"Missing checksum for {model_name}")
        
        actual = compute_checksum(model_dir)
        if actual != expected:
            raise ChecksumMismatchError(
                f"{model_name}: expected {expected}, got {actual}"
            )
        
        # Lazy load to GPU
        return self._load_model(model_dir, device="cuda:0")
```

#### 显存优化

**串行调度**（避免峰值 OOM）：
```python
# ai_worker/application/workflows/meeting_full_pipeline.py

async def run_pipeline():
    # Step 1: ASR（占用 8GB）
    asr_result = await asr_runtime.transcribe(audio)
    del asr_runtime  # 释放显存
    torch.cuda.empty_cache()
    
    # Step 2: Diarization（占用 2GB）
    diar_result = await diar_runtime.diarize(audio)
    del diar_runtime
    torch.cuda.empty_cache()
    
    # Step 3: Speaker embedding（占用 1.5GB）
    speaker_result = await speaker_runtime.embed(segments)
    # ... 后续步骤类似
```

**模型单例缓存**：
```python
# ai_worker/model_runtime/manager.py

class ModelManager:
    _instances: Dict[str, Any] = {}
    
    def get_asr_runtime(self) -> AsrRuntime:
        if "asr" not in self._instances:
            self._instances["asr"] = Qwen3AsrRuntime(device="cuda:0")
        return self._instances["asr"]
```

---

## 3. 实施路线图

### Week 1：移除安全等级 + ASR/Diarization

| 天 | 任务 | 产出 | 验证命令 |
|---|------|------|----------|
| **D1** | Contracts：删除 securityLevel，重新生成 | codegen diff clean | `cd packages/meeting-contracts && npm run check` |
| **D1** | Java：V202606110001 迁移 + 删除 domain 枚举 | Migration + 编译通过 | `cd apps/meeting-api && ./mvnw clean compile` |
| **D2** | 前端：删除 SecurityLevel 组件，简化表单 | UI 无等级字段 | `cd apps/meeting-web && npm test` |
| **D3** | 下载 Qwen3-ASR，计算 checksum | SHA-256 记录 | `scripts/stage_mock_weights.py --verify` |
| **D4-D5** | 实现 Qwen3AsrRuntime，替换 fake | ASR 真实 JSON | 单 30min 音频 RTF < 0.3 |
| **D6** | 下载 pyannote 3.3，计算 checksum | SHA-256 记录 | — |
| **D7** | 实现 PyannoteDiarizationRuntime | Diarization 真实 turns | DER < 15% |

**Week 1 里程碑**：音频上传 → 真实 ASR → 真实说话人分离 → callback Java

---

### Week 2：Speaker + Text Embedding

| 天 | 任务 | 产出 | 验证 |
|---|------|------|------|
| **D8** | 下载 CAM++ 权重，计算 checksum | SHA-256 | — |
| **D9-D10** | 实现 Cam++SpeakerRuntime | 192 维 embedding | 候选匹配 top-1 准确率 > 85% |
| **D11** | 下载 bge-m3，计算 checksum | SHA-256 | — |
| **D12-D13** | 实现 BgeM3EmbeddingRuntime | 1024 维 embedding | RAG recall@10 基线 |
| **D14** | 下载 bge-reranker-v2-m3，计算 checksum | SHA-256 | — |

**Week 2 里程碑**：声纹匹配 + RAG embedding 真实输出

---

### Week 3：Reranker + 联合调试 + 验收

| 天 | 任务 | 产出 | 验证 |
|---|------|------|------|
| **D15-D16** | 实现 BgeRerankerRuntime | Rerank 真实评分 | NDCG@10 基线 |
| **D17** | 联合调试：单任务端到端 | 真实数据全链路 | 手工 E2E 清单 |
| **D18** | 并发压测：3 任务并行 | GPU 指标 + OOM 策略 | 显存峰值 < 22GB |
| **D19** | Phase J 验收（J1-J9） | 9 项检查报告 | 全部通过 |
| **D20** | Playwright E2E 扩展 | 5 个 spec 绿灯 | CI job 通过 |
| **D21** | 文档更新 + v1.1 tag | CHANGELOG、model-registry.md | — |

**Week 3 里程碑**：v1.1 GA，可演示真实全链路

---

## 4. 技术细节

### 4.1 模型权重管理

#### 目录结构
```
/opt/models/
├── qwen3-asr/v1/          # FunASR Qwen3
├── pyannote/v3.3/         # Diarization
├── cam++/v1/              # Speaker embedding
├── bge-m3/v1/             # Text embedding
└── bge-reranker-v2-m3/v1/ # Reranker
```

#### Checksum 计算
```python
# ai_worker/observability/model_checksum.py

def compute_checksum(model_dir: Path) -> str:
    """递归计算 .pt/.bin/.safetensors 的 SHA-256"""
    files = sorted(model_dir.rglob("*.pt")) + \
            sorted(model_dir.rglob("*.bin")) + \
            sorted(model_dir.rglob("*.safetensors"))
    hasher = hashlib.sha256()
    for f in files:
        hasher.update(f.read_bytes())
    return f"sha256:{hasher.hexdigest()}"
```

#### Dockerfile 离线模式
```dockerfile
# apps/ai-worker/Dockerfile
ENV HF_HUB_OFFLINE=1
ENV TRANSFORMERS_OFFLINE=1
# 禁止联网下载，运行时从 /opt/models 加载
```

---

### 4.2 回滚方案

#### 环境变量开关
```bash
# .env
AI_WORKER_USE_FAKE_MODELS=false  # true 回退 fake
```

#### 代码结构
```python
# ai_worker/model_runtime/asr/__init__.py
from ai_worker.config import settings

if settings.USE_FAKE_MODELS:
    from .fake_asr_runtime import FakeAsrRuntime as AsrRuntime
else:
    from .qwen3_asr_runtime import Qwen3AsrRuntime as AsrRuntime
```

#### Git 分支策略
```
master (v1.0 stable, 已合并 worker-web-speaker-upload)
  ↓
phase-k-remove-security-level (开发分支, 当前)
  ↓ (PR after Phase J 验收)
master (v1.1 with real models)
```

**回滚触发条件**：
- 显存 OOM 频率 > 10%
- ASR RTF > 0.5
- Phase J 验收任意项失败

---

## 5. 风险与缓解

### 5.1 技术风险

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| **显存 OOM** | 中 | 高 | 串行调度 + `torch.cuda.empty_cache()` + OOM 退出策略（返回 137） |
| **ASR RTF > 0.5** | 低 | 中 | FunASR 优化版 + Batch size=1 + fake 回退开关 |
| **模型权重下载失败** | 低 | 高 | 内网镜像 + 手动下载 + checksum 验证 + 离线模式强制 |
| **Flyway 迁移冲突** | 低 | 中 | DROP COLUMN IF EXISTS + 备份 + 回滚脚本 |

### 5.2 产品风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| **所有数据可发云端** | 隐私合规风险 | 文档标注 + 用户协议 + Phase L 本地 LLM 路线图 |
| **客户投诉移除分级** | 品牌信任度下降 | Release Notes 说明 + 企业版路线图 |
| **3 周延误** | v1.1 交付延期 | D7/D14 里程碑检查 + reranker 可砍 |

---

## 6. 验收标准

### 功能验收
- [ ] 会议创建无安全等级选择器（meeting-web + ai-worker-web）
- [ ] LLM gateway 无 security gate 检查
- [ ] 5 个模型全部真实输出
- [ ] 端到端：上传 → 真实转录 → 真实纪要 → 真实 RAG

### 性能验收
- [ ] ASR RTF < 0.3
- [ ] Diarization DER < 15%
- [ ] 显存峰值 < 22GB（3 任务并发）
- [ ] OOM 频率 < 5%（100 任务测试）

### Phase J 验收（9 项）
- [ ] J1：Full-stack healthy
- [ ] J2：Prod profile fail-fast
- [ ] J3：Frontend CSP/bundle/XSS
- [ ] J4：Model checksum guard
- [ ] J5：Playwright stability
- [ ] J6：K8s dev overlay
- [ ] J7：All unit suites green
- [ ] J8：Backup recovery drill
- [ ] J9：Legal-hold operational drill

---

## 7. 后续工作（Phase L，v1.2+）

不在 Phase K 范围内，留待后续评估：

1. **本地 LLM 集成**（vLLM + Qwen2.5-14B）— 企业版功能
2. **水平扩展验证**（多 ai-worker 实例 + RabbitMQ 负载均衡）
3. **RAG 向量库迁移**（pgvector → Qdrant/Milvus，如需）
4. **多语言支持**（当前只验证中文）
5. **worker-web 废弃决策**（N 卡上线后，与用户共同评估）

---

## 8. 参考文档

- `docs/spec.md` §2.1 — 安全等级原始设计
- `docs/todo.md` 阶段 2 / 阶段 8 — 真实模型集成任务
- `docs/runbooks/phase-j-acceptance.md` — 9 项验收清单
- `packages/meeting-contracts/schemas/common/enums.yaml` — securityLevel 枚举定义
- `apps/ai-worker/docs/model-registry.md` — 模型准入清单（待填充 checksum）
