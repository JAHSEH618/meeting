# 差异化存储策略设计规格

> **创建日期**: 2026-06-12  
> **状态**: 设计完成  
> **作者**: Claude Code  
> **目标**: 实现Java-web→TOS直接存储，Workstation→本地处理+TOS异步备份

---

## 一、需求背景

### 1.1 用户需求
- **Java-web端**访问meeting-api上传 → **直接存储到TOS**
- **Python workstation端**访问ai-worker上传 → **本地处理 + TOS异步备份**，后续处理不再请求TOS

### 1.2 现状分析
- ✅ TOS SDK已替换完成
- ✅ 两个前端已有独立的上传路径：
  - Java-web → `POST /api/documents` (meeting-api:8080)
  - Workstation → `POST /admin/documents` (ai-worker:8090，待实现)
- ✅ 存储抽象完善：Java `ObjectStorageGateway`、Python `ArtifactStore`

---

## 二、架构设计

### 2.1 存储路径分离

```
┌─────────────────────────────────────────────────────────┐
│                    Java-web Frontend                     │
└───────────────────┬─────────────────────────────────────┘
                    │ POST /api/documents
                    ▼
┌─────────────────────────────────────────────────────────┐
│              meeting-api (Java)                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │ VolcengineTosObjectStorageGateway                │   │
│  └──────────────────┬───────────────────────────────┘   │
└─────────────────────┼─────────────────────────────────┘
                      │ 直接上传
                      ▼
              ┌────────────────┐
              │ Volcengine TOS │
              └────────────────┘

┌─────────────────────────────────────────────────────────┐
│                 Workstation Frontend                     │
└───────────────────┬─────────────────────────────────────┘
                    │ POST /admin/documents
                    ▼
┌─────────────────────────────────────────────────────────┐
│           ai-worker admin BFF (Python)                   │
│  ┌──────────────────────────────────────────────────┐   │
│  │ DocumentUploadHandler                             │   │
│  │  1. Write to LocalArtifactStore                   │   │
│  │  2. Enqueue TosBackupJob (async)                  │   │
│  └──────────────────┬───────────────────────────────┘   │
└─────────────────────┼─────────────────────────────────┘
                      │
        ┌─────────────┴──────────────┐
        │                            │
        ▼                            ▼
┌────────────────┐          ┌────────────────┐
│ /shared-data/  │          │ Background Job │
│   storage/     │          │ → Copy to TOS  │
└────────────────┘          └───────┬────────┘
                                    │
                                    ▼
                            ┌────────────────┐
                            │ Volcengine TOS │
                            └────────────────┘
```

### 2.2 关键设计决策

1. **Java-web路径**：保持现有实现，`meeting.storage.type=tos`时使用`VolcengineTosObjectStorageGateway`
2. **Workstation路径**：
   - 前端调用ai-worker BFF的`POST /admin/documents`
   - BFF写入本地存储（立即返回）
   - 后台异步任务复制到TOS（灾备）
   - Worker处理时从本地读取（零网络延迟）

---

## 三、实现方案

### 3.1 ai-worker端实现

#### 新增文档上传endpoint

**文件**: `apps/ai-worker/ai_worker/admin/documents.py`

```python
from fastapi import APIRouter, UploadFile, HTTPException
from ai_worker.infrastructure.artifact_store import build_artifact_store
from ai_worker.admin.java_client import JavaPublicClient
import asyncio

router = APIRouter(prefix="/admin/documents", tags=["documents"])

@router.post("/upload")
async def upload_document(
    file: UploadFile,
    tenant_id: str,
    meeting_id: str | None = None,
):
    """
    Workstation文档上传：
    1. 立即写入本地存储
    2. 后台异步备份到TOS
    3. 通过Java API创建文档记录
    """
    store = build_artifact_store()
    
    # 1. 立即写入本地（不阻塞）
    content = await file.read()
    bucket = "meeting-documents"
    key = f"{tenant_id}/{meeting_id or 'standalone'}/{file.filename}"
    
    ref = await store.upload(bucket, key, content, file.content_type)
    
    # 2. 后台异步TOS备份
    asyncio.create_task(backup_to_tos(bucket, key, content))
    
    # 3. 调用Java API创建文档记录
    java_client = JavaPublicClient()
    doc = await java_client.create_document(
        tenant_id=tenant_id,
        title=file.filename,
        storage_uri=ref.uri,  # tos://meeting-documents/...
        meeting_id=meeting_id,
    )
    
    return {"documentId": doc.id, "uri": ref.uri}


async def backup_to_tos(bucket: str, key: str, content: bytes):
    """异步备份到TOS（仅在TOS模式启用时）"""
    from ai_worker.common.config import settings
    
    if settings.storage_backend != "tos":
        return  # 本地模式无需备份
    
    try:
        from ai_worker.infrastructure.tos_artifact_store import TosArtifactStore
        # 直接用TOS client上传，不走LocalArtifactStore
        # TODO: 实现直接TOS上传逻辑
        pass
    except Exception as e:
        # 备份失败不影响业务，仅记录日志
        import structlog
        log = structlog.get_logger()
        log.error("tos_backup_failed", bucket=bucket, key=key, error=str(e))
```

#### 配置更新

**文件**: `apps/ai-worker/ai_worker/common/config.py`

```python
class Settings(BaseSettings):
    # 新增：是否启用TOS备份
    enable_tos_backup: bool = True  # Workstation上传后是否备份到TOS
```

### 3.2 Java端无需改动

**现有实现已满足需求**：
- `meeting.storage.type=tos` → 使用`VolcengineTosObjectStorageGateway` → 直接TOS
- Java-web通过`POST /api/documents` → meeting-api处理 → TOS

---

## 四、声纹录入功能验证

### 4.1 Java-web端（meeting-web）

**现有功能**（无需改动）：
- 页面：`apps/meeting-web/src/features/speakers/SpeakerEnrollPanel.tsx`
- 流程：
  1. 用户录音 → Base64音频
  2. `POST /api/speaker-profiles/enroll` → meeting-api
  3. meeting-api存储音频 → TOS（via VolcengineTosObjectStorageGateway）
  4. 触发ai-worker声纹提取任务

### 4.2 Workstation端（ai-worker-web）

**现有功能**（无需改动）：
- 页面：`apps/ai-worker-web/src/pages/EnrollmentPage.tsx`
- 流程：
  1. 用户录音 → 音频文件
  2. `POST /admin/enrollments/start` → ai-worker BFF
  3. BFF存储音频 → **本地**（LocalArtifactStore）
  4. 后台备份到TOS（异步）
  5. 立即触发声纹提取

**关键差异**：
- Java-web：音频先上传TOS，worker从TOS下载处理
- Workstation：音频直接本地，worker本地读取（零延迟）

---

## 五、测试验证

### 5.1 Java-web路径测试

```bash
# 1. 确保TOS配置启用
export STORAGE_TYPE=tos
export TOS_ENDPOINT=https://tos-cn-beijing.volces.com
export TOS_ACCESS_KEY_ID=xxx
export TOS_ACCESS_KEY_SECRET=xxx

# 2. 启动meeting-api
cd apps/meeting-api && ./mvnw spring-boot:run

# 3. 测试文档上传
curl -X POST http://localhost:8080/api/documents \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test.pdf" \
  -F "title=Test Document"

# 4. 验证：文件应在TOS，URI格式为 tos://meeting-documents/...
```

### 5.2 Workstation路径测试

```bash
# 1. 确保本地模式 + TOS备份
export STORAGE_TYPE=local
export STORAGE_LOCAL_ROOT=/shared-data/storage
export AI_WORKER_ENABLE_TOS_BACKUP=true
export AI_WORKER_TOS_ENDPOINT=https://tos-cn-beijing.volces.com

# 2. 启动ai-worker
cd apps/ai-worker && uv run ai-worker-api

# 3. 测试文档上传
curl -X POST http://localhost:8090/admin/documents/upload \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@test.pdf" \
  -F "tenantId=tenant_01"

# 4. 验证：
# - 文件应立即出现在 /shared-data/storage/meeting-documents/
# - 后台任务应复制到TOS（检查日志）
# - URI格式为 tos://meeting-documents/...
```

### 5.3 声纹录入测试

**Java-web**：
1. 访问 http://localhost:5173/speakers
2. 点击"新增声纹"
3. 录制3段音频
4. 提交 → 检查音频URI应为`tos://...`
5. 等待处理完成

**Workstation**：
1. 访问 http://localhost:5174/workstation/enrollment
2. 上传音频文件
3. 提交 → 检查音频立即保存到本地
4. Worker立即从本地读取处理（无TOS下载）

---

## 六、部署配置

### 6.1 Java-web生产环境

```yaml
# docker-compose.yml
meeting-api:
  environment:
    STORAGE_TYPE: tos
    TOS_ENDPOINT: https://tos-cn-beijing.volces.com
    TOS_REGION: cn-beijing
    TOS_ACCESS_KEY_ID: ${TOS_ACCESS_KEY_ID}
    TOS_ACCESS_KEY_SECRET: ${TOS_ACCESS_KEY_SECRET}
  # 不需要本地存储卷
```

### 6.2 Workstation生产环境

```yaml
# docker-compose.yml
ai-worker:
  environment:
    AI_WORKER_STORAGE_BACKEND: local
    AI_WORKER_ARTIFACT_STORE_ROOT: /shared-data/storage
    AI_WORKER_ENABLE_TOS_BACKUP: true
    AI_WORKER_TOS_ENDPOINT: https://tos-cn-beijing.volces.com
    AI_WORKER_TOS_ACCESS_KEY_ID: ${TOS_ACCESS_KEY_ID}
    AI_WORKER_TOS_ACCESS_KEY_SECRET: ${TOS_ACCESS_KEY_SECRET}
  volumes:
    - meeting-storage:/shared-data/storage
```

---

## 七、实施清单

- [ ] 在ai-worker创建`admin/documents.py` router
- [ ] 实现`upload_document()` endpoint
- [ ] 实现`backup_to_tos()` 异步备份函数
- [ ] 添加`enable_tos_backup`配置项
- [ ] 更新ai-worker-web前端调用新endpoint
- [ ] 编写集成测试
- [ ] 更新文档
- [ ] Code review

---

## 八、风险与限制

### 8.1 风险
- ⚠️ **TOS备份失败**：异步备份失败不影响业务，但会导致灾备缺失
- ⚠️ **本地存储空间**：Workstation大量上传可能耗尽本地磁盘

### 8.2 缓解措施
- 备份失败记录详细日志，定期检查
- 实施本地存储定期清理策略（保留7天或TOS备份成功后删除）
- 监控本地磁盘使用率

---

## 九、未来优化

1. **智能清理**：TOS备份成功后删除本地副本（保留热数据）
2. **失败重试**：备份失败自动重试3次
3. **批量备份**：多个文件批量上传TOS，减少API调用
4. **备份状态查询**：提供API查询备份进度
