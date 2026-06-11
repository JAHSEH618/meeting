# TOS SDK替换实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将阿里云OSS SDK替换为火山引擎TOS SDK

**Architecture:** 保持现有ObjectStorageGateway抽象不变，仅替换底层SDK实现。创建VolcengineTosObjectStorageGateway替换AliyunOssObjectStorageGateway，创建TosArtifactStore替换OssArtifactStore。

**Tech Stack:** 
- Java: volcengine-java-sdk-tos 2.6.3
- Python: tos 2.6.0

---

## 文件映射

**Java侧：**
- 修改: `apps/meeting-api/meeting-api-infrastructure/pom.xml` - 替换依赖
- 创建: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java`
- 删除: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/AliyunOssObjectStorageGateway.java`
- 修改: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/LocalObjectStorageGateway.java` - 更新注解
- 修改: `apps/meeting-api/meeting-api-start/src/main/resources/application.yml` - 配置键重命名
- 创建: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/VolcengineTosGatewaySmokeIT.java`

**Python侧：**
- 修改: `apps/ai-worker/pyproject.toml` - 替换依赖
- 修改: `apps/ai-worker/ai_worker/common/config.py` - 配置键重命名
- 创建: `apps/ai-worker/ai_worker/infrastructure/tos_artifact_store.py`
- 删除: `apps/ai-worker/ai_worker/infrastructure/oss_artifact_store.py`
- 修改: `apps/ai-worker/ai_worker/infrastructure/artifact_store.py` - 工厂函数更新

---

### Task 1: Java依赖替换

**Files:**
- Modify: `apps/meeting-api/meeting-api-infrastructure/pom.xml:69-72`

- [ ] **Step 1: 删除阿里云OSS依赖**

```bash
cd apps/meeting-api/meeting-api-infrastructure
# 打开pom.xml，删除69-72行的aliyun-sdk-oss依赖
```

- [ ] **Step 2: 添加火山引擎TOS依赖**

在pom.xml第69行位置添加：
```xml
<dependency>
  <groupId>com.volcengine</groupId>
  <artifactId>volcengine-java-sdk-tos</artifactId>
  <version>2.6.3</version>
</dependency>
```

- [ ] **Step 3: 验证依赖解析**

```bash
cd apps/meeting-api
./mvnw dependency:resolve -pl meeting-api-infrastructure
```
Expected: BUILD SUCCESS，无错误

- [ ] **Step 4: Commit**

```bash
git add apps/meeting-api/meeting-api-infrastructure/pom.xml
git commit -m "deps(java): replace aliyun-oss with volcengine-tos SDK"
```

---

### Task 2: 创建VolcengineTosObjectStorageGateway（第1部分）

**Files:**
- Create: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java`

- [ ] **Step 1: 创建类骨架**

```java
package com.meeting.api.infrastructure.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.TosClientException;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.auth.StaticCredentials;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "tos")
public class VolcengineTosObjectStorageGateway implements ObjectStorageGateway {

    private static final Logger log = LoggerFactory.getLogger(VolcengineTosObjectStorageGateway.class);

    private final TOSV2 client;
    private final String defaultBucket;

    public VolcengineTosObjectStorageGateway(
        @Value("${meeting.storage.tos.endpoint:}") String endpoint,
        @Value("${meeting.storage.tos.region:cn-beijing}") String region,
        @Value("${meeting.storage.tos.access-key-id:}") String accessKeyId,
        @Value("${meeting.storage.tos.access-key-secret:}") String accessKeySecret,
        @Value("${meeting.storage.bucket-audio:meeting-audio-auska}") String defaultBucket
    ) {
        if (endpoint == null || endpoint.isBlank()
            || region == null || region.isBlank()
            || accessKeyId == null || accessKeyId.isBlank()
            || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException(
                "meeting.storage.tos.{endpoint,region,access-key-id,access-key-secret} " +
                "are all required when meeting.storage.type=tos"
            );
        }
        this.client = new TOSV2ClientBuilder()
            .build(region, endpoint, new StaticCredentials(accessKeyId, accessKeySecret));
        this.defaultBucket = defaultBucket;
        log.info("tos_gateway_initialized endpoint={} region={} defaultBucket={}", 
            endpoint, region, defaultBucket);
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public String defaultBucket() {
        return defaultBucket;
    }

    // 下一步实现方法
}
```

- [ ] **Step 2: Commit骨架**

```bash
git add apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java
git commit -m "feat(storage): add VolcengineTosObjectStorageGateway skeleton"
```

---

### Task 3: 实现VolcengineTosObjectStorageGateway方法

**Files:**
- Modify: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java:62`

- [ ] **Step 1: 实现presignPut方法**

在第62行（`// 下一步实现方法`注释处）添加：
```java
@Override
public PresignedUrl presignPut(
    String bucket, String objectKey, int partNumber,
    String contentType, OffsetDateTime expiresAt
) {
    try {
        com.volcengine.tos.model.object.PreSignedPutObjectInput input = 
            new com.volcengine.tos.model.object.PreSignedPutObjectInput()
                .setBucket(bucket)
                .setKey(objectKey)
                .setExpires((int)(expiresAt.toEpochSecond() - OffsetDateTime.now().toEpochSecond()));
        if (contentType != null && !contentType.isBlank()) {
            input.setContentType(contentType);
        }
        com.volcengine.tos.model.object.PreSignedPutObjectOutput output = client.preSignedPutObject(input);
        Map<String, String> headers = (contentType != null && !contentType.isBlank())
            ? Map.of("Content-Type", contentType)
            : Map.of();
        return new PresignedUrl(output.getSignedUrl(), expiresAt, headers);
    } catch (TosClientException | TosServerException ex) {
        throw new ApplicationException(
            ErrorCode.TOS_PRESIGN_FAILED, 500,
            "tos presign put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
            true
        );
    }
}
```

- [ ] **Step 2: 实现presignGet方法**

继续添加：
```java
@Override
public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
    try {
        com.volcengine.tos.model.object.PreSignedURLInput input = 
            new com.volcengine.tos.model.object.PreSignedURLInput()
                .setBucket(bucket)
                .setKey(objectKey)
                .setExpires((int)(expiresAt.toEpochSecond() - OffsetDateTime.now().toEpochSecond()));
        com.volcengine.tos.model.object.PreSignedURLOutput output = client.preSignedURL(input);
        return new PresignedUrl(output.getSignedUrl(), expiresAt, Map.of());
    } catch (TosClientException | TosServerException ex) {
        throw new ApplicationException(
            ErrorCode.TOS_PRESIGN_FAILED, 500,
            "tos presign get failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
            true
        );
    }
}
```

- [ ] **Step 3: Commit presign方法**

```bash
git add apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java
git commit -m "feat(storage): implement TOS presign methods"
```

---

### Task 4: 实现剩余TOS Gateway方法

**Files:**
- Modify: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java`

- [ ] **Step 1: 实现statObject方法**

```java
@Override
public StorageObject statObject(String bucket, String objectKey) {
    try {
        com.volcengine.tos.model.object.HeadObjectV2Input input = 
            new com.volcengine.tos.model.object.HeadObjectV2Input().setBucket(bucket).setKey(objectKey);
        com.volcengine.tos.model.object.HeadObjectV2Output output = client.headObject(input);
        OffsetDateTime modified = output.getLastModified() != null
            ? OffsetDateTime.ofInstant(output.getLastModified().toInstant(), ZoneOffset.UTC)
            : OffsetDateTime.now(ZoneOffset.UTC);
        return new StorageObject(
            bucket, objectKey,
            output.getContentLength(),
            null,
            output.getEtag(),
            modified
        );
    } catch (TosClientException | TosServerException ex) {
        throw new ApplicationException(
            ErrorCode.TOS_OBJECT_NOT_FOUND, 404,
            "tos head failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
            false
        );
    }
}
```

- [ ] **Step 2: 实现deleteObject和putObject方法**

```java
@Override
public void deleteObject(String bucket, String objectKey) {
    try {
        com.volcengine.tos.model.object.DeleteObjectInput input = 
            new com.volcengine.tos.model.object.DeleteObjectInput().setBucket(bucket).setKey(objectKey);
        client.deleteObject(input);
    } catch (TosClientException | TosServerException ex) {
        throw new ApplicationException(
            ErrorCode.TOS_WRITE_FAILED, 502,
            "tos delete failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
            true
        );
    }
}

@Override
public StorageObject putObject(
    String bucket, String objectKey, byte[] bytes,
    String contentType, String sha256
) {
    try {
        com.volcengine.tos.model.object.PutObjectInput input = 
            new com.volcengine.tos.model.object.PutObjectInput()
                .setBucket(bucket)
                .setKey(objectKey)
                .setContent(new ByteArrayInputStream(bytes))
                .setContentLength(bytes.length);
        if (contentType != null && !contentType.isBlank()) {
            com.volcengine.tos.model.object.ObjectMetaRequestOptions meta = 
                new com.volcengine.tos.model.object.ObjectMetaRequestOptions()
                    .setContentType(contentType);
            input.setOptions(meta);
        }
        com.volcengine.tos.model.object.PutObjectOutput output = client.putObject(input);
        return new StorageObject(
            bucket, objectKey, bytes.length, sha256,
            output.getEtag(),
            OffsetDateTime.now(ZoneOffset.UTC)
        );
    } catch (TosClientException | TosServerException ex) {
        throw new ApplicationException(
            ErrorCode.TOS_WRITE_FAILED, 502,
            "tos put failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
            true
        );
    }
}
```

- [ ] **Step 3: Commit完整实现**

```bash
git add apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java
git commit -m "feat(storage): complete VolcengineTosObjectStorageGateway implementation"
```

---

### Task 5: 删除AliyunOssObjectStorageGateway

**Files:**
- Delete: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/AliyunOssObjectStorageGateway.java`

- [ ] **Step 1: 删除文件**

```bash
git rm apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/AliyunOssObjectStorageGateway.java
```

- [ ] **Step 2: 删除测试文件**

```bash
git rm apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/AliyunOssGatewaySmokeIT.java
```

- [ ] **Step 3: Commit删除**

```bash
git commit -m "refactor(storage): remove AliyunOssObjectStorageGateway"
```

---

### Task 6: 更新Java配置

**Files:**
- Modify: `apps/meeting-api/meeting-api-start/src/main/resources/application.yml:80-95`
- Modify: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/LocalObjectStorageGateway.java:22`

- [ ] **Step 1: 更新application.yml配置键**

将`application.yml`第80-95行的`oss`改为`tos`：
```yaml
meeting:
  storage:
    type: ${STORAGE_TYPE:local}
    local-root: ${STORAGE_LOCAL_ROOT:}
    tos:
      endpoint: ${TOS_ENDPOINT:}
      region: ${TOS_REGION:cn-beijing}
      access-key-id: ${TOS_ACCESS_KEY_ID:}
      access-key-secret: ${TOS_ACCESS_KEY_SECRET:}
```

- [ ] **Step 2: 更新LocalObjectStorageGateway注解**

修改第22行：
```java
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "local", matchIfMissing = true)
```

- [ ] **Step 3: 更新StorageConfigValidator**

修改`StorageConfigValidator.java`检查`tos`而非`oss`：
```java
} else if ("tos".equalsIgnoreCase(storageType)) {
    log.info("storage_config_validated type=tos");
}
```

- [ ] **Step 4: Commit配置更新**

```bash
git add apps/meeting-api/meeting-api-start/src/main/resources/application.yml \
        apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/LocalObjectStorageGateway.java \
        apps/meeting-api/meeting-api-start/src/main/java/com/meeting/api/start/config/StorageConfigValidator.java
git commit -m "config(storage): rename oss to tos configuration keys"
```

---

### Task 7: Python依赖替换

**Files:**
- Modify: `apps/ai-worker/pyproject.toml:12`

- [ ] **Step 1: 替换依赖**

将第12行的`"oss2>=2.19,<3.0",`替换为：
```toml
  "tos>=2.6,<3.0",
```

- [ ] **Step 2: 安装新依赖**

```bash
cd apps/ai-worker
uv sync
```
Expected: Successfully installed tos

- [ ] **Step 3: Commit依赖更新**

```bash
git add apps/ai-worker/pyproject.toml
git commit -m "deps(python): replace oss2 with tos SDK"
```

---

### Task 8: 创建TosArtifactStore

**Files:**
- Create: `apps/ai-worker/ai_worker/infrastructure/tos_artifact_store.py`

- [ ] **Step 1: 创建TosArtifactStore**

```python
"""火山引擎TOS-backed artifact store."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import tos
from tos.clientv2 import TosClientV2
from tos.auth import StaticCredentials

from ai_worker.infrastructure.artifact_store import ArtifactRef, LocalArtifactStore


class TosArtifactStore:
    """Read-via-TOS, write-via-local artifact store."""

    def __init__(
        self,
        endpoint: str,
        region: str,
        access_key_id: str,
        access_key_secret: str,
        local_writer: LocalArtifactStore,
        cache_dir: str | Path | None = None,
    ) -> None:
        if not endpoint or not endpoint.strip():
            raise ValueError("TosArtifactStore: endpoint is required")
        if not region or not region.strip():
            raise ValueError("TosArtifactStore: region is required")
        if not access_key_id or not access_key_secret:
            raise ValueError("TosArtifactStore: credentials are required")
        
        self._client = TosClientV2(
            auth=StaticCredentials(access_key_id, access_key_secret),
            endpoint=endpoint,
            region=region,
        )
        self._local_writer = local_writer
        self._cache_dir = Path(cache_dir) if cache_dir else Path(tempfile.gettempdir()) / "ai-worker-tos"
        self._cache_dir.mkdir(parents=True, exist_ok=True)

    async def upload(
        self,
        bucket: str,
        key: str,
        data: bytes,
        content_type: str = "application/octet-stream",
    ) -> ArtifactRef:
        return await self._local_writer.upload(bucket, key, data, content_type)

    async def upload_json(self, bucket: str, key: str, payload: dict[str, Any]) -> ArtifactRef:
        return await self._local_writer.upload_json(bucket, key, payload)

    async def download(self, uri: str) -> bytes:
        bucket, key = _parse_tos_uri(uri)
        resp = self._client.get_object(bucket=bucket, key=key)
        return resp.content.read()

    async def download_json(self, uri: str) -> dict[str, Any]:
        return json.loads((await self.download(uri)).decode("utf-8"))

    async def delete(self, uri: str) -> None:
        await self._local_writer.delete(uri)

    def local_path(self, uri: str) -> Path:
        bucket, key = _parse_tos_uri(uri)
        cache_name = hashlib.sha256(f"{bucket}/{key}".encode("utf-8")).hexdigest()
        target = self._cache_dir / bucket / cache_name
        
        if target.exists() and target.stat().st_size > 0:
            return target
        
        target.parent.mkdir(parents=True, exist_ok=True)
        head = self._client.head_object(bucket=bucket, key=key)
        expected_size = head.content_length
        
        fd, tmp_str = tempfile.mkstemp(
            prefix=f"{cache_name}.",
            suffix=".part",
            dir=target.parent,
        )
        os.close(fd)
        tmp = Path(tmp_str)
        
        try:
            resp = self._client.get_object(bucket=bucket, key=key)
            tmp.write_bytes(resp.content.read())
            actual_size = tmp.stat().st_size
            
            if expected_size is not None and actual_size != expected_size:
                raise OSError(
                    f"TosArtifactStore: short download for {uri}: "
                    f"got {actual_size} bytes, expected {expected_size}"
                )
            
            os.replace(tmp, target)
        except BaseException:
            try:
                tmp.unlink(missing_ok=True)
            except OSError:
                pass
            raise
        
        return target


def _parse_tos_uri(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "tos" or not parsed.netloc or not parsed.path:
        raise ValueError(f"TosArtifactStore expects tos://bucket/key, got: {uri}")
    return parsed.netloc, parsed.path.lstrip("/")
```

- [ ] **Step 2: Commit TosArtifactStore**

```bash
git add apps/ai-worker/ai_worker/infrastructure/tos_artifact_store.py
git commit -m "feat(storage): add TosArtifactStore implementation"
```

---

### Task 9: 更新Python配置和工厂函数

**Files:**
- Modify: `apps/ai-worker/ai_worker/common/config.py:17-34`
- Modify: `apps/ai-worker/ai_worker/infrastructure/artifact_store.py:139-172`
- Delete: `apps/ai-worker/ai_worker/infrastructure/oss_artifact_store.py`

- [ ] **Step 1: 更新config.py配置键**

将`config.py`第17-34行的`oss_`改为`tos_`：
```python
# storage_backend selects how ai-worker resolves ``tos://bucket/key`` URIs
# Choices:
#   "local" — default
#   "tos"   — production
storage_backend: str = "local"
tos_endpoint: str | None = None
tos_region: str | None = None
tos_access_key_id: str | None = None
tos_access_key_secret: str | None = None
```

- [ ] **Step 2: 更新artifact_store.py工厂函数**

修改`build_artifact_store()`函数：
```python
def build_artifact_store() -> "ArtifactStore":
    from ai_worker.common.config import settings

    local = LocalArtifactStore()
    if (settings.storage_backend or "local").lower() != "tos":
        return local
    if not (settings.tos_endpoint and settings.tos_region and settings.tos_access_key_id and settings.tos_access_key_secret):
        raise RuntimeError(
            "AI_WORKER_STORAGE_BACKEND=tos requires AI_WORKER_TOS_ENDPOINT, "
            "AI_WORKER_TOS_REGION, AI_WORKER_TOS_ACCESS_KEY_ID, "
            "AI_WORKER_TOS_ACCESS_KEY_SECRET to be set."
        )
    from ai_worker.infrastructure.tos_artifact_store import TosArtifactStore

    return TosArtifactStore(
        endpoint=settings.tos_endpoint,
        region=settings.tos_region,
        access_key_id=settings.tos_access_key_id,
        access_key_secret=settings.tos_access_key_secret,
        local_writer=local,
    )
```

- [ ] **Step 3: 更新URI格式从oss://到tos://**

修改`LocalArtifactStore.upload()`返回的URI：
```python
return ArtifactRef(
    uri=f"tos://{bucket}/{key}",  # 改为tos://
    sha256=hashlib.sha256(data).hexdigest(),
    size_bytes=len(data),
    content_type=content_type,
)
```

修改`_parse_artifact_uri()`：
```python
if parsed.scheme == "tos" and parsed.netloc and parsed.path:
    return parsed.scheme, parsed.netloc, parsed.path.lstrip("/")
```

- [ ] **Step 4: 删除OssArtifactStore**

```bash
git rm apps/ai-worker/ai_worker/infrastructure/oss_artifact_store.py
git rm apps/ai-worker/tests/test_oss_artifact_store.py
```

- [ ] **Step 5: Commit Python配置更新**

```bash
git add apps/ai-worker/ai_worker/common/config.py \
        apps/ai-worker/ai_worker/infrastructure/artifact_store.py
git commit -m "refactor(storage): replace oss with tos in Python"
```

---

### Task 10: 更新环境变量和文档

**Files:**
- Modify: `infra/meeting-infra/docker/compose/docker-compose.yml:125-128`
- Modify: `.env.example`

- [ ] **Step 1: 更新docker-compose.yml**

将第125-128行的`OSS_`改为`TOS_`：
```yaml
TOS_ENDPOINT: ${TOS_ENDPOINT:-}
TOS_REGION: ${TOS_REGION:-cn-beijing}
TOS_ACCESS_KEY_ID: ${TOS_ACCESS_KEY_ID:-}
TOS_ACCESS_KEY_SECRET: ${TOS_ACCESS_KEY_SECRET:-}
```

- [ ] **Step 2: 更新.env.example**

```bash
# TOS模式：火山引擎凭证
TOS_ENDPOINT=https://tos-cn-beijing.volces.com
TOS_REGION=cn-beijing
TOS_ACCESS_KEY_ID=
TOS_ACCESS_KEY_SECRET=
```

- [ ] **Step 3: Commit环境变量更新**

```bash
git add infra/meeting-infra/docker/compose/docker-compose.yml .env.example
git commit -m "config: update environment variables from OSS to TOS"
```

---

### Task 11: 运行测试验证

**Files:**
- Test: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/LocalStorageModeTest.java`

- [ ] **Step 1: 运行Java测试**

```bash
cd apps/meeting-api
./mvnw test -Dtest=LocalStorageModeTest
```
Expected: Tests run: 5, Failures: 0

- [ ] **Step 2: 运行Python测试**

```bash
cd apps/ai-worker
uv run pytest tests/infrastructure/test_artifact_store.py -v
```
Expected: PASSED

- [ ] **Step 3: 验证编译**

```bash
cd apps/meeting-api
./mvnw -pl meeting-api-start -am compile
```
Expected: BUILD SUCCESS

---

## 自审清单

- [x] **Spec覆盖**: TOS SDK替换完成
- [x] **无占位符**: 所有代码完整
- [x] **类型一致性**: VolcengineTosObjectStorageGateway / TosArtifactStore命名一致
- [x] **URI格式**: 统一从oss://改为tos://

---

## 执行说明

**实施顺序**：必须按Task 1→11顺序执行，因为：
1. Task 1-6是Java侧替换（有依赖顺序）
2. Task 7-9是Python侧替换
3. Task 10是配置统一
4. Task 11是验证

**预计时间**：约60-90分钟

**风险点**：
- TOS SDK API可能与OSS不完全兼容，需要调整方法调用
- URI格式从oss://改为tos://需要全局搜索替换

