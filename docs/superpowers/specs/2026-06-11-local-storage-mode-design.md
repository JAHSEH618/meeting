# 本地存储模式设计规格

> **创建日期**: 2026-06-11  
> **状态**: 设计完成  
> **作者**: Claude Code  
> **目标**: 设计TOS上传开关，支持在ai-worker所属物理机本地处理，避免远程存储依赖

---

## 一、背景与目标

### 1.1 需求来源

用户需求：
- 分析当前代码流程与文档（README、structure.md）的一致性
- 设计一个开关决定是否上传到TOS（火山引擎对象存储）
- 如果不上传，直接在ai-worker所属物理机本地处理

### 1.2 现状分析

**代码与文档严重不一致 - 关键问题**：
- ❌ **SDK错误**：文档描述"火山引擎TOS"，实际代码使用**火山引擎TOS SDK** (`aliyun-sdk-oss` / `oss2`)
- ❌ **依赖错误**：
  - Java: `com.aliyun.oss:aliyun-sdk-oss:3.18.4` ← 应为 `com.volcengine:volcengine-java-sdk-tos`
  - Python: `oss2>=2.19,<3.0` ← 应为 `tos>=2.6,<3.0`
- ❌ **类名错误**：`AliyunOssObjectStorageGateway` / `OssArtifactStore` ← 应为 `VolcengineTosObjectStorageGateway` / `TosArtifactStore`
- ❌ **配置键错误**：`meeting.storage.oss.*` / `AI_WORKER_TOS_*` ← 应为 `meeting.storage.tos.*` / `AI_WORKER_TOS_*`

**正确状态应为**：
- ✅ 架构边界清晰：Java管业务、Python管计算的设计完整落地
- ✅ 存储抽象完善：Java的`ObjectStorageGateway`、Python的`ArtifactStore`协议设计良好
- ✅ 已有切换能力：`meeting.storage.type` (Java) 和 `AI_WORKER_STORAGE_BACKEND` (Python) 已支持local/tos切换
- ✅ 本地模式已实现：`LocalObjectStorageGateway` + `LocalArtifactStore` 完整可用

**本设计的两个目标**：
1. **修正SDK依赖**：将火山引擎TOS替换为火山引擎TOS
2. **设计本地开关**：支持本地存储模式，无需TOS依赖

---

## 二、设计方案

### 2.1 方案选择

**前置任务：修正SDK依赖（必须先完成）**
- 替换 `com.aliyun.oss:aliyun-sdk-oss` → `com.volcengine:volcengine-java-sdk-tos`
- 替换 `oss2` → `tos`
- 重命名类：`AliyunOssObjectStorageGateway` → `VolcengineTosObjectStorageGateway`
- 重命名配置：`meeting.storage.oss.*` → `meeting.storage.tos.*`
- 重命名URI：`tos://bucket/key` → `tos://bucket/key`

**方案A：复用现有配置 + 修正TOS SDK（采纳）**
- 核心：利用现有`LocalObjectStorageGateway`和`LocalArtifactStore`
- 优点：零破坏性改动、配置清晰、符合COLA架构原则
- 实施：
  1. 替换火山引擎TOS SDK为火山引擎TOS SDK
  2. 统一配置模式、补充文档、增加启动校验

**方案B：新增统一开关（备选）**
- 核心：`STORAGE_MODE=local/remote`自动联动两端配置
- 优点：单点配置、不会不一致
- 缺点：降低灵活性、需要额外代码

**方案C：细粒度控制（备选）**
- 核心：区分业务文件和artifact的存储策略
- 优点：最精细控制
- 缺点：复杂度高、需要Java支持`file://` URI

### 2.2 架构设计

#### 部署拓扑

```
┌─────────────────────────────────────────────┐
│         物理机 / K8s Node                    │
│                                             │
│  ┌──────────────┐      ┌──────────────┐    │
│  │ meeting-api  │      │  ai-worker   │    │
│  │              │      │              │    │
│  │ LocalObject  │      │ LocalArtifact│    │
│  │ StorageGateway│      │   Store      │    │
│  └──────┬───────┘      └──────┬───────┘    │
│         │                     │            │
│         └──────┬──────────────┘            │
│                ▼                            │
│     ┌─────────────────────┐                │
│     │   共享存储卷          │                │
│     │ /shared-data/storage │                │
│     └─────────────────────┘                │
└─────────────────────────────────────────────┘
```

**关键约束**：
- Java和Python必须能访问同一个文件系统路径
- Docker Compose: 使用`volumes`挂载同一个卷
- Kubernetes: 使用`PersistentVolumeClaim`或`hostPath`

---

## 三、配置设计

### 3.1 Java侧配置

**application.yml**:
```yaml
meeting:
  storage:
    type: ${STORAGE_TYPE:local}  # local | tos
    
    # 本地模式：必须配置共享存储根路径
    local-root: ${STORAGE_LOCAL_ROOT:/shared-data/storage}
    
    # TOS模式：火山引擎凭证
    tos:
      endpoint: ${TOS_ENDPOINT:}
      region: ${TOS_REGION:cn-beijing}
      access-key-id: ${TOS_ACCESS_KEY_ID:}
      access-key-secret: ${TOS_ACCESS_KEY_SECRET:}
    
    # 逻辑bucket名称（两种模式共用）
    bucket-audio: meeting-audio-local
    bucket-artifacts: meeting-artifacts-local
    bucket-exports: meeting-exports-local
```

**条件激活**:
- `@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "local", matchIfMissing = true)` → `LocalObjectStorageGateway`
- `@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "tos")` → `VolcengineTosObjectStorageGateway`

### 3.2 Python侧配置

**config.py**:
```python
class Settings(BaseSettings):
    storage_backend: str = "local"  # local | tos
    artifact_store_root: str = "/shared-data/storage"
    
    # TOS模式配置（火山引擎）
    tos_endpoint: str | None = None
    tos_region: str | None = None
    tos_access_key_id: str | None = None
    tos_access_key_secret: str | None = None
```

**工厂函数**:
```python
def build_artifact_store() -> ArtifactStore:
    if settings.storage_backend == "tos":
        return TosArtifactStore(...)
    return LocalArtifactStore(root=settings.artifact_store_root)
```

### 3.3 环境变量统一

**.env配置**:
```bash
# ============================================
# 存储模式配置
# ============================================
# local  - 本地文件系统，Java和Python共享卷
# tos    - 火山引擎TOS远程存储
STORAGE_TYPE=local

# 本地模式：共享存储根路径（Java和Python必须一致）
STORAGE_LOCAL_ROOT=/shared-data/storage

# TOS模式：火山引擎凭证（STORAGE_TYPE=tos时必填）
TOS_ENDPOINT=https://tos-cn-beijing.volces.com
TOS_REGION=cn-beijing
TOS_ACCESS_KEY_ID=
TOS_ACCESS_KEY_SECRET=

# Python侧继承
AI_WORKER_STORAGE_BACKEND=${STORAGE_TYPE}
AI_WORKER_ARTIFACT_STORE_ROOT=${STORAGE_LOCAL_ROOT}
AI_WORKER_TOS_ENDPOINT=${TOS_ENDPOINT}
AI_WORKER_TOS_REGION=${TOS_REGION}
AI_WORKER_TOS_ACCESS_KEY_ID=${TOS_ACCESS_KEY_ID}
AI_WORKER_TOS_ACCESS_KEY_SECRET=${TOS_ACCESS_KEY_SECRET}
```

---

## 四、实现细节

### 4.1 Java侧增强

#### 第一步：替换火山引擎TOS SDK为火山引擎TOS SDK

**修改文件**: `apps/meeting-api/meeting-api-infrastructure/pom.xml`

```xml
<!-- 删除火山引擎TOS依赖 -->
<dependency>
  <groupId>com.aliyun.oss</groupId>
  <artifactId>aliyun-sdk-oss</artifactId>
  <version>3.18.4</version>
</dependency>

<!-- 替换为火山引擎TOS SDK -->
<dependency>
  <groupId>com.volcengine</groupId>
  <artifactId>volcengine-java-sdk-tos</artifactId>
  <version>2.6.3</version>
</dependency>
```

#### 第二步：创建VolcengineTosObjectStorageGateway

**新增文件**: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/VolcengineTosObjectStorageGateway.java`

```java
package com.meeting.api.infrastructure.storage;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.TosClientException;
import com.volcengine.tos.TosServerException;
import com.volcengine.tos.auth.StaticCredentials;
import com.volcengine.tos.model.*;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.storage.ObjectStorageGateway;
import com.meeting.api.domain.storage.StorageObject;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 火山引擎TOS-backed {@link ObjectStorageGateway}.
 * 当 {@code meeting.storage.type=tos} 时激活。
 */
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

    @Override
    public PresignedUrl presignPut(
        String bucket, String objectKey, int partNumber,
        String contentType, OffsetDateTime expiresAt
    ) {
        try {
            PreSignedPutObjectInput input = new PreSignedPutObjectInput()
                .setBucket(bucket)
                .setKey(objectKey)
                .setExpires((int)(expiresAt.toEpochSecond() - OffsetDateTime.now().toEpochSecond()));
            if (contentType != null && !contentType.isBlank()) {
                input.setContentType(contentType);
            }
            PreSignedPutObjectOutput output = client.preSignedPutObject(input);
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

    @Override
    public PresignedUrl presignGet(String bucket, String objectKey, OffsetDateTime expiresAt) {
        try {
            PreSignedURLInput input = new PreSignedURLInput()
                .setBucket(bucket)
                .setKey(objectKey)
                .setExpires((int)(expiresAt.toEpochSecond() - OffsetDateTime.now().toEpochSecond()));
            PreSignedURLOutput output = client.preSignedURL(input);
            return new PresignedUrl(output.getSignedUrl(), expiresAt, Map.of());
        } catch (TosClientException | TosServerException ex) {
            throw new ApplicationException(
                ErrorCode.TOS_PRESIGN_FAILED, 500,
                "tos presign get failed: " + bucket + "/" + objectKey + " " + ex.getMessage(),
                true
            );
        }
    }

    @Override
    public StorageObject statObject(String bucket, String objectKey) {
        try {
            HeadObjectV2Input input = new HeadObjectV2Input().setBucket(bucket).setKey(objectKey);
            HeadObjectV2Output output = client.headObject(input);
            OffsetDateTime modified = output.getLastModified() != null
                ? OffsetDateTime.ofInstant(output.getLastModified().toInstant(), ZoneOffset.UTC)
                : OffsetDateTime.now(ZoneOffset.UTC);
            return new StorageObject(
                bucket, objectKey,
                output.getContentLength(),
                null,  // TOS ETag不是SHA-256
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

    @Override
    public void deleteObject(String bucket, String objectKey) {
        try {
            DeleteObjectInput input = new DeleteObjectInput().setBucket(bucket).setKey(objectKey);
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
            PutObjectInput input = new PutObjectInput()
                .setBucket(bucket)
                .setKey(objectKey)
                .setContent(new ByteArrayInputStream(bytes))
                .setContentLength(bytes.length);
            if (contentType != null && !contentType.isBlank()) {
                ObjectMetaRequestOptions meta = new ObjectMetaRequestOptions()
                    .setContentType(contentType);
                input.setOptions(meta);
            }
            PutObjectOutput output = client.putObject(input);
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
}
```

#### 第三步：删除AliyunOssObjectStorageGateway

**删除文件**: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/AliyunOssObjectStorageGateway.java`

#### 第四步：修改LocalObjectStorageGateway条件

**修改文件**: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/LocalObjectStorageGateway.java`

```java
@Component
@ConditionalOnProperty(name = "meeting.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorageGateway implements ObjectStorageGateway {
    // 保持现有实现不变
}
```

#### 第五步：启动校验器

**新增文件**: `apps/meeting-api/meeting-api-start/src/main/java/com/meeting/api/start/config/StorageConfigValidator.java`

```java
package com.meeting.api.start.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 存储配置校验器：确保本地模式配置了local-root。
 */
@Component
public class StorageConfigValidator implements ApplicationListener<ApplicationReadyEvent> {
    
    @Value("${meeting.storage.type:local}")
    private String storageType;
    
    @Value("${meeting.storage.local-root:}")
    private String localRoot;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if ("local".equalsIgnoreCase(storageType) || "minio".equalsIgnoreCase(storageType)) {
            if (localRoot == null || localRoot.isBlank()) {
                throw new IllegalStateException(
                    "meeting.storage.type=" + storageType + " requires " +
                    "meeting.storage.local-root to be configured. " +
                    "Set STORAGE_LOCAL_ROOT environment variable or " +
                    "meeting.storage.local-root property."
                );
            }
        }
    }
}
```

#### LocalObjectStorageGateway现状

**文件**: `apps/meeting-api/meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/storage/LocalObjectStorageGateway.java`

**已实现能力**:
- ✅ `putObject()`: 写入本地文件系统
- ✅ `statObject()`: 读取本地文件元信息
- ✅ `presignPut/Get()`: 返回本地URL（开发模式兼容）
- ✅ 支持`meeting.storage.local-root`配置

**无需改动**。

### 4.2 Python侧增强

#### LocalArtifactStore现状

**文件**: `apps/ai-worker/ai_worker/infrastructure/artifact_store.py`

**已实现能力**:
- ✅ `upload()`: 写入本地文件系统
- ✅ `download()`: 从本地读取
- ✅ `local_path()`: 返回本地路径
- ✅ 支持`artifact_store_root`配置

**无需改动**。

### 4.3 URI契约保持

**关键设计决策**: 无论本地/远程模式，URI格式统一为 `tos://bucket/key`

**Python侧**:
```python
async def upload(self, bucket: str, key: str, data: bytes, ...) -> ArtifactRef:
    path = self._path_for(bucket, key)  # /shared-data/storage/bucket/key
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return ArtifactRef(
        uri=f"tos://{bucket}/{key}",  # 保持tos://协议
        sha256=hashlib.sha256(data).hexdigest(),
        ...
    )
```

**Java侧**:
```java
public StorageObject statObject(String bucket, String objectKey) {
    Path target = localRoot.resolve(bucket).resolve(objectKey);
    // /shared-data/storage/bucket/key
    return new StorageObject(bucket, objectKey, Files.size(target), ...);
}
```

**优点**:
- ✅ 契约不变：`internal-callback-api.yaml`无需修改
- ✅ 可移植性：本地→生产无需修改业务代码
- ✅ 测试友好：Mock数据格式统一

---

## 五、部署配置

### 5.1 Docker Compose - 本地模式

**文件**: `infra/meeting-infra/docker/compose/docker-compose.yml`

```yaml
version: '3.8'

services:
  meeting-api:
    build: ../../../../apps/meeting-api
    environment:
      STORAGE_TYPE: local
      STORAGE_LOCAL_ROOT: /shared-data/storage
      POSTGRES_HOST: postgres
      RABBITMQ_HOST: rabbitmq
    volumes:
      - meeting-storage:/shared-data/storage
    ports:
      - "8080:8080"
    depends_on:
      - postgres
      - rabbitmq

  ai-worker:
    build: ../../../../apps/ai-worker
    environment:
      AI_WORKER_STORAGE_BACKEND: local
      AI_WORKER_ARTIFACT_STORE_ROOT: /shared-data/storage
      AI_WORKER_MEETING_API_BASE_URL: http://meeting-api:8080
    volumes:
      - meeting-storage:/shared-data/storage
    ports:
      - "8090:8090"
    depends_on:
      - meeting-api

volumes:
  meeting-storage:
    driver: local
```

### 5.2 Docker Compose - OSS远程模式

```yaml
services:
  meeting-api:
    environment:
      STORAGE_TYPE: oss
      TOS_ENDPOINT: https://oss-cn-hangzhou.aliyuncs.com
      TOS_REGION: cn-hangzhou
      TOS_ACCESS_KEY_ID: ${TOS_ACCESS_KEY_ID}
      TOS_ACCESS_KEY_SECRET: ${TOS_ACCESS_KEY_SECRET}
    # 不需要volumes

  ai-worker:
    environment:
      AI_WORKER_STORAGE_BACKEND: oss
      AI_WORKER_TOS_ENDPOINT: https://oss-cn-hangzhou.aliyuncs.com
      AI_WORKER_TOS_REGION: cn-hangzhou
      AI_WORKER_TOS_ACCESS_KEY_ID: ${TOS_ACCESS_KEY_ID}
      AI_WORKER_TOS_ACCESS_KEY_SECRET: ${TOS_ACCESS_KEY_SECRET}
```

### 5.3 Kubernetes - PVC挂载

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: meeting-storage-pvc
spec:
  accessModes:
    - ReadWriteMany  # 允许多Pod同时读写
  resources:
    requests:
      storage: 500Gi

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: meeting-api
spec:
  template:
    spec:
      containers:
      - name: meeting-api
        env:
        - name: STORAGE_TYPE
          value: "local"
        - name: STORAGE_LOCAL_ROOT
          value: "/shared-data/storage"
        volumeMounts:
        - name: storage
          mountPath: /shared-data/storage
      volumes:
      - name: storage
        persistentVolumeClaim:
          claimName: meeting-storage-pvc

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ai-worker
spec:
  template:
    spec:
      containers:
      - name: ai-worker
        env:
        - name: AI_WORKER_STORAGE_BACKEND
          value: "local"
        - name: AI_WORKER_ARTIFACT_STORE_ROOT
          value: "/shared-data/storage"
        volumeMounts:
        - name: storage
          mountPath: /shared-data/storage
      volumes:
      - name: storage
        persistentVolumeClaim:
          claimName: meeting-storage-pvc
```

---

## 六、测试验证

### 6.1 单元测试

**Java测试**:
```java
// apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/LocalStorageModeTest.java
@SpringBootTest
@TestPropertySource(properties = {
    "meeting.storage.type=local",
    "meeting.storage.local-root=/tmp/test-storage"
})
class LocalStorageModeTest {
    
    @Autowired
    private ObjectStorageGateway storageGateway;
    
    @Test
    void shouldBeLocalObjectStorageGateway() {
        assertInstanceOf(LocalObjectStorageGateway.class, storageGateway);
    }
    
    @Test
    void putObject_shouldWriteToLocalFilesystem() throws IOException {
        byte[] content = "test-audio-content".getBytes();
        StorageObject result = storageGateway.putObject(
            "test-bucket", "audio/test.mp3", content, "audio/mpeg", "fake-sha256"
        );
        
        Path expected = Path.of("/tmp/test-storage/test-bucket/audio/test.mp3");
        assertTrue(Files.exists(expected));
        assertArrayEquals(content, Files.readAllBytes(expected));
        
        // Cleanup
        Files.deleteIfExists(expected);
    }
}
```

**Python测试**:
```python
# apps/ai-worker/tests/infrastructure/test_local_artifact_store.py
import pytest
from pathlib import Path
from ai_worker.infrastructure.artifact_store import LocalArtifactStore

@pytest.fixture
def temp_storage(tmp_path):
    return LocalArtifactStore(root=tmp_path)

@pytest.mark.asyncio
async def test_upload_returns_oss_uri(temp_storage, tmp_path):
    ref = await temp_storage.upload(
        "test-bucket", "artifact/quality.json", b'{"status": "ok"}', "application/json"
    )
    
    # URI保持tos://格式
    assert ref.uri == "tos://test-bucket/artifact/quality.json"
    
    # 实际文件在本地
    path = tmp_path / "test-bucket" / "artifact" / "quality.json"
    assert path.exists()
    assert path.read_text() == '{"status": "ok"}'

@pytest.mark.asyncio
async def test_local_path_resolution(temp_storage, tmp_path):
    await temp_storage.upload("bucket", "file.dat", b"data")
    
    path = temp_storage.local_path("tos://bucket/file.dat")
    assert path == tmp_path / "bucket" / "file.dat"
    assert path.exists()
```

### 6.2 集成测试

**端到端验证脚本**:
```bash
#!/bin/bash
# scripts/test-local-storage-mode.sh

set -e

echo "==> 设置本地存储模式"
export STORAGE_TYPE=local
export STORAGE_LOCAL_ROOT=/tmp/meeting-test-storage
mkdir -p $STORAGE_LOCAL_ROOT

echo "==> 启动Docker Compose"
cd infra/meeting-infra/docker/compose
docker compose up -d meeting-api ai-worker

echo "==> 等待服务就绪"
sleep 10

echo "==> 上传测试音频"
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  | jq -r '.data.accessToken')

MEETING_ID=$(curl -s -X POST http://localhost:8080/api/meetings \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"本地存储测试","securityLevel":"PUBLIC"}' \
  | jq -r '.data.meetingId')

echo "==> 会议ID: $MEETING_ID"

echo "==> 验证本地文件系统"
ls -lh $STORAGE_LOCAL_ROOT/meeting-audio-local/
if [ -z "$(ls -A $STORAGE_LOCAL_ROOT/meeting-audio-local/)" ]; then
   echo "❌ 音频文件未写入本地存储"
   exit 1
fi

echo "✅ 本地存储模式测试通过"

echo "==> 清理"
docker compose down
rm -rf $STORAGE_LOCAL_ROOT
```

---

## 七、文档更新

### 7.1 README.md补充

在"快速开始"章节后添加：

```markdown
### 存储模式配置

系统支持两种存储模式：

#### 本地存储模式（开发/私有化部署）

适用场景：
- 本地开发调试
- 单机部署
- 私有化部署（避免外部云服务依赖）
- 降低OSS费用

配置方式：
```bash
# .env配置
STORAGE_TYPE=local
STORAGE_LOCAL_ROOT=/shared-data/storage

# 要求：Java和Python必须挂载同一个共享卷
```

目录结构：
```
/shared-data/storage/
├── meeting-audio-local/
│   └── tenant_xxx/meeting_xxx/audio.mp3
├── meeting-artifacts-local/
│   └── tenant_xxx/meeting_xxx/artifacts/quality.json
└── meeting-exports-local/
    └── tenant_xxx/meeting_xxx/export.pdf
```

#### 远程TOS模式（生产环境）

适用场景：
- 分布式部署
- Java和Python跨机部署
- 需要OSS的持久化、备份、CDN能力

配置方式：
```bash
STORAGE_TYPE=oss
TOS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
TOS_REGION=cn-hangzhou
TOS_ACCESS_KEY_ID=<your-key-id>
TOS_ACCESS_KEY_SECRET=<your-secret>
```
```

### 7.2 CLAUDE.md补充

在"Commands"章节后添加：

```markdown
## 存储模式切换

### 本地模式（Java + Python同机）

```bash
export STORAGE_TYPE=local
export STORAGE_LOCAL_ROOT=/shared-data/storage

# Java
./mvnw -pl meeting-api-start -am spring-boot:run

# Python
uv run ai-worker-api
```

### 远程TOS模式

```bash
export STORAGE_TYPE=oss
export TOS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
export TOS_REGION=cn-hangzhou
export TOS_ACCESS_KEY_ID=xxx
export TOS_ACCESS_KEY_SECRET=xxx
```

### 关键约束

- 本地模式要求Java和Python能访问同一文件系统路径
- URI格式始终保持 `tos://bucket/key`，便于模式切换
- LocalObjectStorageGateway启动时会校验local-root配置
- 切换模式后需重启服务
```

---

## 八、实施清单

- [x] 分析代码与文档一致性
- [x] 设计TOS上传开关方案（选择方案A）
- [x] 设计配置方案（统一环境变量）
- [x] 设计Docker Compose配置
- [x] 设计Kubernetes部署方案
- [ ] 实现Java配置校验器 `StorageConfigValidator.java`
- [ ] 编写Java单元测试 `LocalStorageModeTest.java`
- [ ] 编写Python单元测试 `test_local_artifact_store.py`
- [ ] 编写集成测试脚本 `scripts/test-local-storage-mode.sh`
- [ ] 更新Docker Compose配置文件
- [ ] 更新README.md存储模式章节
- [ ] 更新CLAUDE.md存储模式章节
- [ ] 更新.env.example补充本地模式示例

---

## 九、验收标准

### 功能验收

- [ ] 本地模式：Java和Python能通过共享卷读写文件
- [ ] 本地模式：音频上传后文件落盘到`$STORAGE_LOCAL_ROOT/meeting-audio-local/`
- [ ] 本地模式：worker artifact落盘到`$STORAGE_LOCAL_ROOT/meeting-artifacts-local/`
- [ ] 本地模式：导出文件落盘到`$STORAGE_LOCAL_ROOT/meeting-exports-local/`
- [ ] TOS模式：音频上传到火山引擎TOS
- [ ] TOS模式：worker能从OSS下载音频进行处理
- [ ] 两种模式URI格式一致（`tos://bucket/key`）

### 配置验收

- [ ] `STORAGE_TYPE=local`时未配置`STORAGE_LOCAL_ROOT`会启动失败
- [ ] `STORAGE_TYPE=oss`时未配置OSS凭证会启动失败
- [ ] Docker Compose本地模式能正常挂载共享卷
- [ ] K8s PVC模式两个Pod能同时读写

### 文档验收

- [ ] README.md包含存储模式切换说明
- [ ] CLAUDE.md包含快速切换命令
- [ ] .env.example包含两种模式示例配置
- [ ] 本设计文档提交到`docs/superpowers/specs/`

---

## 十、后续优化

### 短期优化

1. **监控指标**：
   - 本地模式：磁盘使用率、IOPS
   - TOS模式：API调用次数、流量费用

2. **自动清理**：
   - 本地模式：定期清理过期文件（基于会议删除策略）
   - TOS模式：配置生命周期规则

### 长期优化

1. **混合模式**：
   - 音频/导出走OSS（持久化）
   - artifact走本地（临时中间产物）

2. **缓存策略**：
   - TOS模式下worker本地缓存热点音频
   - 减少重复下载

3. **迁移工具**：
   - 提供local→oss数据迁移脚本
   - 支持增量同步

---

## 附录A：术语对照

| 文档术语 | 代码实现 | 说明 |
|---------|---------|------|
| 火山引擎TOS | 火山引擎TOS | 文档需更新为"火山引擎TOS"或保持抽象为"对象存储" |
| 本地存储 | LocalObjectStorageGateway + LocalArtifactStore | 两端实现已完整 |
| 远程存储 | AliyunOssObjectStorageGateway + OssArtifactStore | 两端实现已完整 |

## 附录B：配置优先级

1. **环境变量** > Spring配置文件 > 默认值
2. **推荐实践**：生产环境通过环境变量注入敏感配置
3. **开发环境**：使用`.env`文件或`application-local.yml`

## 附录C：FAQ

**Q: 本地模式下Java和Python必须在同一台机器吗？**  
A: 不一定。可以是：
- 同一物理机
- 同一K8s Node
- 不同机器但通过NFS挂载同一目录

**Q: 本地模式下磁盘空间不够怎么办？**  
A: 
- 配置会议自动删除策略
- 定期归档到OSS
- 扩容本地磁盘或切换到TOS模式

**Q: 可以混合模式吗（Java用OSS，Python用local）？**  
A: 技术上可行但不推荐。会导致：
- Python读不到Java上传的音频（需要Java先上传OSS，Python再下载）
- 配置复杂，难以维护

**Q: URI为什么不用`file://`？**  
A: 保持`tos://`格式是为了：
- 契约统一，模式切换不破坏API
- 代码可移植，本地开发→生产部署无需改代码
- 测试友好，Mock数据格式一致
