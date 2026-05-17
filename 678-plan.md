# Phase 6 / 7 / 8 执行计划（细化版）

> v2 · 2026-05-18 · 在 v1 基础上把每个 checkbox 细化到"工程师拿到就能开 PR"的粒度。文件路径、类签名、测试用例、验收命令都已列出。
>
> **基线代码模板**（每个 PR 都应当对照这些已落地实现，不要重新发明）：
>
> | 关注点 | 标杆文件 | 学什么 |
> |---|---|---|
> | 聚合 + 状态机 | `meeting-api-domain/.../domain/rag/KnowledgeChunk.java` | Builder、defensive copy、`markX()` 状态方法、构造参数校验 |
> | 应用服务 | `meeting-api-app/.../app/rag/RagQueryApplicationService.java` | `TenantScopedTransaction.execute(...)`、降级路径、结构化日志 `key=value` |
> | Facade DTO | `meeting-api-client/.../client/rag/RagQueryFacade.java` + `RagQueryCommand` | 命令对象 record + compact ctor 校验 |
> | Controller | `meeting-api-adapter/.../adapter/rag/RagQueryController.java` | `ApiResponse.ok(...)`、`TenantContextHolder`、header 注入 |
> | JDBC 仓储 | `meeting-api-infrastructure/.../persistence/rag/JdbcKnowledgeChunkRepository.java` | RLS、原生 SQL、`SELECT FOR UPDATE` |
> | 简单应用服务 | `meeting-api-app/.../app/document/DocumentApplicationService.java` | `Clock` 注入、ID 前缀格式 `doc_<uuidNoDash>`、reindex 模式 |
>
> **关键约定**：
> - ID 前缀：`exp_` / `lh_` / `dj_` / `cert_` / `bg_` / `audit_` + UUID 去横杠
> - 所有 `@Transactional` 通过 `TenantScopedTransaction.execute(tenantId, userId, requestId, ...)` 包裹（不直接用 Spring 注解，避免漏 `app.tenant_id` 设置）
> - 日志格式：`event_name key1=value1 key2=value2`（参考 `rag_query_done tenant=t1 user=u1 citations=5 coverage=FULL`）
> - 错误码统一在 `client/common/ErrorCode.java` 枚举里登记后才能在代码中引用
> - **新业务域要先开 6 个 package 再开始落代码**（client / domain / app / adapter / infrastructure-persistence / infrastructure-gateway），否则 ArchUnit 会失败

---

## Phase 6：异步导出

### 6.0 前置（不要跳过）

- [ ] **6.0.1** 在新分支 `feat/phase5-checkin` 上勾选 `todo.md` 第 256-276 行 Phase 5 项，加 `阶段 5 收尾备忘（2026-05-18）` 段落；该 PR 与 Phase 6 任意 PR **不混提**，便于评审。
- [ ] **6.0.2** 先建 6 个空 package 并各放一个 `package-info.java`：
  - `meeting-api-client/.../client/export/`
  - `meeting-api-domain/.../domain/export/`
  - `meeting-api-app/.../app/export/`（注：当前已存在但为空目录）
  - `meeting-api-adapter/.../adapter/export/`
  - `meeting-api-infrastructure/.../persistence/export/`
  - `meeting-api-infrastructure/.../gateway/export/`

  package-info 内容只需 `@org.springframework.lang.NonNullApi @org.springframework.lang.NonNullFields package ...;` —— 避免 ArchUnit 因空目录报错。

### 6.1 契约补齐（1 PR · ~2 天）`[BLOCKS 6.2-6.6]`

#### 6.1.1 enums.yaml 增量

文件：`packages/meeting-contracts/schemas/common/enums.yaml`

- [ ] **6.1.1.a** 追加：
  ```yaml
  exportStatus:
    - QUEUED          # 已创建，等待 consumer 拉取
    - RUNNING         # consumer 正在渲染
    - SUCCEEDED       # file_id 已生成，downloadUrl 可用
    - FAILED          # 渲染失败，error_code 已填
    - CANCELLED       # 用户取消
    - REVOKED         # 短链已撤销（与 SUCCEEDED 互斥，是 SUCCEEDED 的后续状态）

  exportDataBoundaryMode:
    - FULL            # 一期默认；不做文本脱敏
    - REDACTED        # 预留；一期不允许选

  exportType:
    - MEETING         # 单会议导出
    - AUDIT           # Phase 7 审计导出（预留）
  ```
- [ ] **6.1.1.b** 在 `scripts/check-consistency.sh` 的 enum 一致性检查中加入新枚举的 Java/TS/Python 三端校验（自动通过 codegen 完成）。

#### 6.1.2 public-api.yaml schema 补齐

文件：`packages/meeting-contracts/openapi/public-api.yaml`

- [ ] **6.1.2.a** 新增 components.schemas：
  ```yaml
  CreateExportRequest:
    type: object
    required: [format]
    properties:
      format:    {$ref: '#/components/schemas/ExportFormat'}
      watermarkText: {type: string, maxLength: 200, nullable: true}
      includeSpeakers: {type: boolean, default: true}
      includeMinutes:  {type: boolean, default: true}
      includeItems:    {type: boolean, default: true}

  ExportJobResponse:
    type: object
    required: [exportId, meetingId, format, status, createdAt]
    properties:
      exportId: {type: string}
      meetingId: {type: string}
      format: {$ref: '#/components/schemas/ExportFormat'}
      status: {$ref: '#/components/schemas/ExportStatus'}
      inputTranscriptVersion: {type: integer, minimum: 0, nullable: true}
      inputMinutesVersion:    {type: integer, minimum: 0, nullable: true}
      snapshotManifestId:     {type: string, nullable: true}
      watermarkText:          {type: string, nullable: true}
      downloadUrl:             {type: string, format: uri, nullable: true}
      downloadExpiresAt:       {type: string, format: date-time, nullable: true}
      downloadRevokedAt:       {type: string, format: date-time, nullable: true}
      fileSizeBytes:           {type: integer, format: int64, nullable: true}
      fileSha256:              {type: string, nullable: true}
      error:                   {$ref: '#/components/responses/ErrorInfo', nullable: true}
      stale:                   {type: boolean, description: '快照后内容是否已 STALE'}
      createdAt:               {type: string, format: date-time}
      finishedAt:              {type: string, format: date-time, nullable: true}
  ```

  在 `responses` 节加 `OkPaginated` 的 `ExportJobResponse` 列表变体（如果还没有泛型分页则参考 `RagAnswerDTO` 列表的写法）。

#### 6.1.3 error-codes.yaml 增量

文件：`packages/meeting-contracts/schemas/common/error-codes.yaml`

- [ ] **6.1.3.a** 新增 3 条：
  ```yaml
  - code: EXPORT_CONTENT_STALE
    httpStatus: 422
    retryable: false
    operatorTag: phase6-export
    defaultMessage: "导出依赖的转录或纪要内容已变更，请先重新生成纪要后重试。"
    i18nKey: errors.EXPORT_CONTENT_STALE
  - code: EXPORT_DOWNLOAD_LINK_REVOKED
    httpStatus: 410
    retryable: false
    operatorTag: phase6-export
    defaultMessage: "导出链接已撤销。"
    i18nKey: errors.EXPORT_DOWNLOAD_LINK_REVOKED
  - code: EXPORT_FORMAT_UNSUPPORTED
    httpStatus: 422
    retryable: false
    operatorTag: phase6-export
    defaultMessage: "不支持的导出格式。"
    i18nKey: errors.EXPORT_FORMAT_UNSUPPORTED
  - code: EXPORT_RUNTIME_ERROR
    httpStatus: 503
    retryable: true
    operatorTag: phase6-export
    defaultMessage: "导出处理失败，请稍后重试。"
    i18nKey: errors.EXPORT_RUNTIME_ERROR
  ```

#### 6.1.4 Fixtures

文件位置：`packages/meeting-contracts/fixtures/`

- [ ] **6.1.4.a** `valid/public-api-create-export-pdf-200.json`
- [ ] **6.1.4.b** `valid/public-api-get-export-stale-200.json`（status=SUCCEEDED, stale=true）
- [ ] **6.1.4.c** `valid/public-api-revoke-export-link-200.json`
- [ ] **6.1.4.d** `invalid/public-api-create-export-stale-422.json`（error.code=EXPORT_CONTENT_STALE）
- [ ] **6.1.4.e** `invalid/public-api-create-export-legal-hold-423.json`（error.code=LEGAL_HOLD_BLOCKED，依赖 7.1.3）

#### 6.1.5 Codegen 与验收

- [ ] **6.1.5.a** `npm run check` 通过（Spectral + JSON Schema + enum 一致性 + fixtures）
- [ ] **6.1.5.b** `npm run codegen` 提交 TS / Python / Java codegen 产物，CI `git diff` 干净
- [ ] **6.1.5.c** `meeting-api-client` 手写 enum `ExportStatus.java`、`ExportDataBoundaryMode.java`、`ExportType.java` 加上，并由新增的 `EnumConsistencyTest` 校验值集与 enums.yaml 一致

### 6.2 Domain 层（1 PR · ~2 天）`[BLOCKS 6.3]`

#### 6.2.1 `ExportJob` 聚合

文件：`meeting-api-domain/src/main/java/com/meeting/api/domain/export/ExportJob.java`

模板：`KnowledgeChunk.java`（Builder + 状态机方法）

- [ ] **6.2.1.a** 字段（对齐 DDL `export_jobs`）：`id, tenantId, meetingId, exportType, format, dataBoundaryMode, status, inputMinutesVersion, inputTranscriptVersion, snapshotManifestId, watermarkText, fileId, fileHash, fileSizeBytes, downloadExpiresAt, downloadRevokedAt, errorCode, createdBy, createdAt, updatedAt, finishedAt`
- [ ] **6.2.1.b** 状态机方法：
  ```java
  public void markRunning(OffsetDateTime at);                                    // QUEUED -> RUNNING
  public void markSucceeded(String fileId, String sha256, long sizeBytes,
                            OffsetDateTime expiresAt, OffsetDateTime at);        // RUNNING -> SUCCEEDED
  public void markFailed(ErrorCode code, String reason, OffsetDateTime at);     // RUNNING -> FAILED
  public void markCancelled(OffsetDateTime at);                                  // QUEUED|RUNNING -> CANCELLED
  public void revokeDownload(OffsetDateTime at);                                 // SUCCEEDED -> REVOKED
  ```
  非法转换抛 `IllegalStateException(currentStatus + " -> " + target)`。
- [ ] **6.2.1.c** 单元测试 `ExportJobTest.java`：覆盖每个状态机转换 + 非法路径（如 SUCCEEDED → RUNNING 抛异常）

#### 6.2.2 仓储端口

文件：`meeting-api-domain/.../domain/export/ExportJobRepository.java`

- [ ] **6.2.2.a** 接口签名：
  ```java
  public interface ExportJobRepository {
      void save(ExportJob job);                                                    // INSERT or UPDATE by id
      Optional<ExportJob> findById(String tenantId, String exportId);
      Page<ExportJob> listByMeeting(String tenantId, String meetingId, Pageable p);
      List<ExportJob> claimQueued(String tenantId, int limit);                     // SELECT ... FOR UPDATE SKIP LOCKED
      void updateStatus(String tenantId, String exportId, ExportStatus newStatus,
                        @Nullable String fileId, @Nullable String fileHash,
                        @Nullable Long fileSizeBytes,
                        @Nullable OffsetDateTime expiresAt,
                        @Nullable OffsetDateTime revokedAt,
                        @Nullable ErrorCode errorCode);
  }
  ```

#### 6.2.3 ExportGateway 端口

文件：`meeting-api-domain/.../domain/export/ExportGateway.java`

- [ ] **6.2.3.a** Strategy 端口：
  ```java
  public interface ExportGateway {
      ExportFormat supportedFormat();
      RenderedFile render(ExportJob job, MeetingSnapshot snapshot) throws ExportRuntimeException;

      record RenderedFile(byte[] bytes, String sha256, long sizeBytes) {}
  }
  ```
  注册方式：每个格式一个 `@Component`，`@Service ExportGatewayRegistry` 收集所有实现并按 `supportedFormat()` 路由。

#### 6.2.4 MeetingSnapshotPort

文件：`meeting-api-domain/.../domain/export/MeetingSnapshotPort.java`

- [ ] **6.2.4.a** 跨域只读端口：
  ```java
  public interface MeetingSnapshotPort {
      Optional<MeetingSnapshot> loadSnapshot(
          String tenantId, String meetingId,
          int transcriptVersion, @Nullable Integer minutesVersion);
  }
  public record MeetingSnapshot(
      String meetingId, String title, String securityLevel,
      List<TranscriptSegmentRow> segments,
      @Nullable MinutesRow minutes,
      List<ActionItemRow> actionItems,
      List<DecisionRow> decisions,
      List<RiskRow> risks,
      int transcriptVersion, @Nullable Integer minutesVersion
  ) {}
  ```
- [ ] **6.2.4.b** **关键**：实现位于 `meeting-api-infrastructure/.../persistence/export/JdbcMeetingSnapshotPort.java`，查询时 **必须**校验 `transcript_version`、`minutes_version`、`stale_status=ACTIVE` —— 如果版本不存在或已 STALE 直接返回 `Optional.empty()`，应用服务把 empty 翻译成 `EXPORT_CONTENT_STALE`。

#### 6.2.5 领域事件 + 异常

- [ ] **6.2.5.a** 在 `meeting-api-domain/.../domain/export/event/` 新增：
  - `ExportJobCreatedEvent(tenantId, exportId, meetingId, format, expectedInputVersion)`
  - `ExportJobCompletedEvent(tenantId, exportId, status, fileId, fileSha256)`
  - `ExportDownloadRevokedEvent(tenantId, exportId, revokedBy)`
  事件 payload 需满足 outbox `domain_events_outbox.payload_json` 反序列化（参考 `WorkerPhaseCompletedEvent`）
- [ ] **6.2.5.b** `ExportRuntimeException extends RuntimeException`：携带 `ErrorCode` + cause；`ExportInputInvalidException`（不可重试）

#### 6.2.6 验收

- [ ] **6.2.6.a** Domain 测试 ≥ 8 个：状态转换正反路径、Snapshot 版本不匹配返回 empty
- [ ] **6.2.6.b** ArchUnit 仍 ERROR 级通过（domain 包不依赖 Spring Web / JDBC / MyBatis-Plus）

### 6.3 Application + Infrastructure 层（3 PR · ~1.5 周）`[BLOCKS 6.4]`

#### 6.3.1 CreateExportApplicationService

文件：`meeting-api-app/.../app/export/CreateExportApplicationService.java`

模板：`RagQueryApplicationService` + `DocumentApplicationService.create()`

- [ ] **6.3.1.a** 实现 `ExportFacade.create(CreateExportCommand)` 接口：
  ```java
  @Service
  public class CreateExportApplicationService implements CreateExportFacade {
      // 注入：
      //   TenantScopedTransaction tenantTx;
      //   ExportJobRepository exportRepo;
      //   MeetingRepository meetingRepo;        // 用于校验 meeting 存在 + 取版本号
      //   LegalHoldCheckPort legalHoldCheck;    // 来自 Phase 7.2.3
      //   ArtifactManifestRepository manifestRepo;
      //   MessagePublisher outbox;
      //   AuditEventLogger audit;                // 来自 Phase 7.5.1
      //   Clock clock;

      public ExportJobDTO create(CreateExportCommand cmd) {
          return tenantTx.execute(cmd.tenantId(), cmd.createdBy(), cmd.requestId(), () -> {
              // 1. 校验 meeting 存在
              MeetingRow meeting = meetingRepo.findById(cmd.tenantId(), cmd.meetingId())
                  .orElseThrow(() -> new ValidationException(ErrorCode.MEETING_NOT_FOUND, ...));

              // 2. legal hold 检查 -> LEGAL_HOLD_BLOCKED 423
              if (legalHoldCheck.isProtected(cmd.tenantId(), "MEETING", cmd.meetingId())) {
                  audit.log(AuditAction.EXPORT, "MEETING", cmd.meetingId(), AuditResult.BLOCKED,
                           Map.of("reason", "legal_hold"), null);
                  throw new LegalHoldBlockedException("MEETING", cmd.meetingId());
              }

              // 3. STALE 检查：snapshotPort 必须能加载当前版本
              MeetingSnapshot snapshot = snapshotPort
                  .loadSnapshot(cmd.tenantId(), cmd.meetingId(),
                                meeting.transcriptVersion(), meeting.minutesVersion())
                  .orElseThrow(() -> new ValidationException(ErrorCode.EXPORT_CONTENT_STALE, ...));

              // 4. 写 artifact_manifest 快照
              String manifestId = artifactManifestRepo.save(snapshotManifest(snapshot));

              // 5. 创建 ExportJob 行
              String exportId = "exp_" + UUID.randomUUID().toString().replace("-", "");
              ExportJob job = ExportJob.builder()
                  .id(exportId).tenantId(cmd.tenantId()).meetingId(cmd.meetingId())
                  .exportType(ExportType.MEETING).format(cmd.format())
                  .dataBoundaryMode(ExportDataBoundaryMode.FULL)
                  .status(ExportStatus.QUEUED)
                  .inputTranscriptVersion(snapshot.transcriptVersion())
                  .inputMinutesVersion(snapshot.minutesVersion())
                  .snapshotManifestId(manifestId)
                  .watermarkText(cmd.watermarkText())
                  .createdBy(cmd.createdBy()).createdAt(now)
                  .build();
              exportRepo.save(job);

              // 6. outbox -> export-queue
              outbox.publishExportQueue(toMessage(job));
              audit.log(AuditAction.EXPORT, "MEETING", cmd.meetingId(), AuditResult.SUCCESS,
                       Map.of("exportId", exportId, "format", cmd.format().name()), null);
              log.info("export_created tenant={} export={} meeting={} format={}",
                       cmd.tenantId(), exportId, cmd.meetingId(), cmd.format());
              return toDto(job, /* stale */ false, /* downloadUrl */ null);
          });
      }
  }
  ```
- [ ] **6.3.1.b** 配置：`meeting.export.download-ttl-hours=24`、`meeting.export.bucket=meeting-exports`、`meeting.export.watermark-default=` (空)

#### 6.3.2 其他应用服务

- [ ] **6.3.2.a** `ListExportsApplicationService.list(tenantId, meetingId, cursor)`：列表 + cursor 分页
- [ ] **6.3.2.b** `GetExportApplicationService.get(tenantId, exportId)`：返回 ExportJobDTO，**生成 downloadUrl** 通过 `TosSignedUrlService.sign(bucket, objectKey, ttl)`；如果 `download_revoked_at != null` 则返回 `downloadUrl=null`；如果 SUCCEEDED 但快照已 STALE（通过比对 `meetings.transcript_version > job.input_transcript_version`）则在 DTO 中 `stale=true`
- [ ] **6.3.2.c** `CancelExportApplicationService.cancel(tenantId, exportId, userId)`：终态 export 抛 409（`EXPORT_ALREADY_FINISHED`）；RUNNING 状态发布 `ExportCancelRequestedEvent`，consumer 收到后 markCancelled 并清理临时文件
- [ ] **6.3.2.d** `RevokeExportLinkApplicationService.revoke(tenantId, exportId, userId)`：调用 `ExportJob.revokeDownload(now)`，写 audit `EXPORT_REVOKED`

#### 6.3.3 仓储实现

文件：`meeting-api-infrastructure/.../persistence/export/JdbcExportJobRepository.java`

模板：`JdbcKnowledgeChunkRepository.java`

- [ ] **6.3.3.a** 用 MyBatis-Plus + 原生 SQL；`claimQueued()` 使用 `FOR UPDATE SKIP LOCKED LIMIT ?`
- [ ] **6.3.3.b** Testcontainers IT `JdbcExportJobRepositoryIT.java`：
  - 写入 → findById 命中
  - 跨租户隔离：tenant A 写的 export tenant B 看不到（RLS 验证）
  - `claimQueued` 在两次调用之间锁互斥
- [ ] **6.3.3.c** `JdbcMeetingSnapshotPort`：从 transcript_segments + meeting_minutes + meeting_action_items + decisions + risks 拉取，**带版本号过滤**

#### 6.3.4 Outbox -> RabbitMQ publisher

文件：`meeting-api-infrastructure/.../mq/ExportQueuePublisher.java`

- [ ] **6.3.4.a** outbox listener 订阅 `event_type='ExportJobCreatedEvent'`，转成符合 `export-job-message.schema.json` 的 JSON，投递 `export-queue` 路由 key
- [ ] **6.3.4.b** **schema 校验**：发布前通过 `ContractSchemaValidator.validate(payload, "export-job-message.schema.json")` 校验（失败即标记 outbox 行 `FAILED`，不投递）
- [ ] **6.3.4.c** IT：起 RabbitMQ Testcontainer，往 outbox 写一行 → publisher 抽取 → 队列收到 + JSON schema 通过

#### 6.3.5 三份 ExportGateway 实现

文件：`meeting-api-infrastructure/.../gateway/export/`

- [ ] **6.3.5.a** `MarkdownExportGateway.java`：
  - 章节模板：`# 标题` → `## 元数据`（与会人 / 安全等级）→ `## 转录`（按 segment，`[hh:mm:ss] 张三：xxx`）→ `## 纪要` → `## 待办` → `## 决策` → `## 风险`
  - 水印放页脚： `<!-- watermark: xxx -->` （Markdown 注释，不渲染但保留）
  - sha256 = `MessageDigest.getInstance("SHA-256").digest(utf8Bytes)`
- [ ] **6.3.5.b** `DocxExportGateway.java`：
  - 用 `org.docx4j:docx4j-JAXB-ReferenceImpl:11.4.x`
  - 标题样式 `Heading1` / `Heading2`；段落 `Normal`
  - 水印：用 `wp14:anchor` 文本框，水印文本 + 旋转 45°、灰色 80%
- [ ] **6.3.5.c** `PdfExportGateway.java`：
  - **不直接生成 PDF**，而是先生成 DOCX，然后调 LibreOffice headless：
    ```java
    Path docx = writeTempDocx(snapshot);
    Process p = new ProcessBuilder(
        "soffice", "--headless", "--convert-to", "pdf",
        "--outdir", tempDir.toString(), docx.toString()
    ).redirectErrorStream(true).start();
    if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
        throw new ExportRuntimeException(ErrorCode.EXPORT_RUNTIME_ERROR, "soffice failed");
    }
    ```
  - **必须在短事务外调用** —— 调用方 `ExportQueueConsumer` 已保证（消费 + 渲染 + 写 TOS 是无 `@Transactional` 的循环）
  - 配置：`meeting.export.libreoffice.binary=soffice`、`meeting.export.libreoffice.timeout-seconds=60`

#### 6.3.6 测试

- [ ] **6.3.6.a** `CreateExportApplicationServiceTest`（mock 仓储）：
  - happy path → 写 export_jobs + outbox + audit
  - 不存在的 meeting → 422
  - legal hold 命中 → 423 + audit `BLOCKED`
  - STALE → 422 `EXPORT_CONTENT_STALE`
- [ ] **6.3.6.b** `MarkdownExportGatewayTest`：固定快照输入 → 输出 Markdown 含所有章节 + 水印注释 + sha256 稳定
- [ ] **6.3.6.c** `PdfExportGatewayIT`（标记 `@Tag("docker")`，需要 LibreOffice）：生成 PDF 文件首页含水印文字（用 PDFBox 文本提取）

### 6.4 Adapter 层（1-2 PR · ~3 天）`[BLOCKS 6.5]`

#### 6.4.1 ExportController

文件：`meeting-api-adapter/.../adapter/export/ExportController.java`

模板：`RagQueryController.java`

- [ ] **6.4.1.a** 5 个路由：
  ```java
  @GetMapping("/api/meetings/{meetingId}/exports")
  ResponseEntity<ApiResponse<PageResult<ExportJobDTO>>> list(...)

  @PostMapping("/api/meetings/{meetingId}/exports")
  ResponseEntity<ApiResponse<ExportJobDTO>> create(@RequestBody CreateExportRequest, ...)

  @GetMapping("/api/exports/{exportId}")
  ResponseEntity<ApiResponse<ExportJobDTO>> get(...)

  @PostMapping("/api/exports/{exportId}/cancel")
  ResponseEntity<ApiResponse<Void>> cancel(...)

  @PostMapping("/api/exports/{exportId}/revoke-link")
  ResponseEntity<ApiResponse<Void>> revokeLink(...)
  ```
- [ ] **6.4.1.b** 所有写操作必带 `X-Request-Id` / `X-Trace-Id` / `Idempotency-Key`（参考 `RagQueryController`）；`get/list` 不要求 Idempotency-Key

#### 6.4.2 ExportQueueConsumer

文件：`meeting-api-adapter/.../adapter/export/ExportQueueConsumer.java`

- [ ] **6.4.2.a** 入口：`@RabbitListener(queues = "export-queue")`，反序列化为 `ExportJobMessage`（来自 codegen）
- [ ] **6.4.2.b** **必须**在 consumer 顶层设置 tenant context：
  ```java
  public void onMessage(ExportJobMessage msg) {
      try (var ctx = TenantContextHolder.set(msg.tenantId(), "system:export-consumer", msg.traceId())) {
          processExport(msg);
      }
  }
  ```
- [ ] **6.4.2.c** processExport 主流程（**不在 @Transactional 内**，每段独立短事务）：
  1. 短 TX：拉 `ExportJob` 行 + markRunning → save
  2. 无 TX：调 `ExportGatewayRegistry.gateway(format).render(job, snapshot)` 得 bytes
  3. 无 TX：上传到 TOS `meeting-exports/tenant/{t}/meeting/{m}/export/{e}/file.{ext}`
  4. 短 TX：写 `meeting_files` 行 + markSucceeded(fileId, hash, size, expiresAt) → save + outbox `ExportJobCompletedEvent`（用于 SSE）
- [ ] **6.4.2.d** 异常映射：
  - `ExportInputInvalidException` → markFailed + 不重试（NACK + don't requeue）
  - `ExportRuntimeException` → 当前 attempt < 3 时重试；否则 DLQ + markFailed
  - 网络错误 → 当前 attempt < 3 时重试
- [ ] **6.4.2.e** Testcontainers IT（RabbitMQ + MinIO）：投一条 message → 等待 5s → 验证 export_jobs.status=SUCCEEDED + file 在 MinIO 存在

#### 6.4.3 SSE 事件扩展

- [ ] **6.4.3.a** 在 `enums.yaml` 的 `taskEventType` 追加 `EXPORT_STATUS_CHANGED`，跑 codegen
- [ ] **6.4.3.b** 在 `SseEventEmitter` 监听 `ExportJobCompletedEvent` + `ExportDownloadRevokedEvent` → 推送 SSE
- [ ] **6.4.3.c** SSE 路由：复用 `/api/processing-tasks/{taskId}/events` 还是新开 `/api/exports/{exportId}/events`？**建议新开** —— 与 ai-worker 步骤事件隔离，更清晰。
- [ ] **6.4.3.d** 前端 `ExportsPage` 可订阅或轮询（轮询更简单，3s 一次）

#### 6.4.4 ArchUnit + 集成测试

- [ ] **6.4.4.a** `ExportControllerWebMvcTest`：使用 `@WebMvcTest(ExportController.class)`，覆盖：
  - 200 happy path
  - 422 STALE / 423 LEGAL_HOLD_BLOCKED / 409 已 CANCELLED 不能再 cancel
  - 缺 Idempotency-Key → 400
- [ ] **6.4.4.b** `ExportQueueConsumerIT`：RabbitMQ + MinIO + PG 全 Testcontainer

### 6.5 前端导出页（1-2 PR · ~3 天）

#### 6.5.1 替换 ExportsPage.tsx

文件：`apps/meeting-web/src/features/exports/ExportsPage.tsx`

模板：`apps/meeting-web/src/features/documents/DocumentsPage.tsx`

- [ ] **6.5.1.a** 当前 19 行 stub 改为：
  - 顶部"创建导出"按钮（弹出 `ExportCreateDialog`：选 format / watermark / 包含项）
  - 列表表格：`createdAt | format | status | downloadUrl | actions`
  - status 列徽标颜色：QUEUED 灰、RUNNING 蓝、SUCCEEDED 绿、FAILED 红、REVOKED 黄
  - actions：`下载` / `取消` / `撤销链接`，按后端权限和当前状态显示
  - `stale=true` 时在 status 旁加 ⚠️ + tooltip "导出后内容已变更"
- [ ] **6.5.1.b** 状态自刷新：`useQuery` 配 `refetchInterval: 3000`，全部 status 终态后停止；或接 SSE（视 6.4.3 选型）
- [ ] **6.5.1.c** 错误码文案：
  - `EXPORT_CONTENT_STALE` → "导出依赖的内容已变更，请先重新生成纪要后重试"，按钮 disabled
  - `LEGAL_HOLD_BLOCKED` → "该会议处于 legal hold 状态，无法导出"
  - `EXPORT_DOWNLOAD_LINK_REVOKED` → "下载链接已撤销"

#### 6.5.2 Vitest

文件：`apps/meeting-web/src/features/exports/__tests__/ExportsPage.test.tsx`

- [ ] **6.5.2.a** MSW mock 列表、创建、撤销 endpoint
- [ ] **6.5.2.b** 测试：
  - 创建成功 → 列表多一行 QUEUED
  - SUCCEEDED + stale=true → ⚠️ 标记可见
  - REVOKED → 下载按钮 disabled
  - 错误响应 `EXPORT_CONTENT_STALE` → 创建对话框内显示固定文案
  - Idempotency-Key 在网络失败重试时复用

### 6.6 LibreOffice runtime + Dockerfile

#### 6.6.1 meeting-api Dockerfile

文件：`apps/meeting-api/Dockerfile`

- [ ] **6.6.1.a** Multi-stage：
  ```dockerfile
  FROM maven:3.9-eclipse-temurin-17 AS build
  WORKDIR /src
  COPY . .
  RUN ./mvnw -q -DskipTests package

  FROM eclipse-temurin:17-jre-jammy
  RUN apt-get update && \
      apt-get install -y --no-install-recommends \
        libreoffice-core libreoffice-writer \
        fonts-noto-cjk fonts-noto-cjk-extra \
        ttf-mscorefonts-installer && \
      apt-get clean && rm -rf /var/lib/apt/lists/*
  COPY --from=build /src/meeting-api-start/target/meeting-api-start-*.jar /app/app.jar
  EXPOSE 8080
  HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
      CMD curl -fs http://localhost:8080/actuator/health || exit 1
  ENTRYPOINT ["java","-jar","/app/app.jar"]
  ```
- [ ] **6.6.1.b** 镜像大小目标 < 1.5GB。压测：`docker images meeting-api:dev --format '{{.Size}}'`。超出则裁剪 LibreOffice：保留 `libreoffice-writer`，剔除 `impress` / `calc` / `draw` / `base`。
- [ ] **6.6.1.c** `.dockerignore`：`target/` `*.log` `**/node_modules` `.git` `.idea`

#### 6.6.2 docker-compose 接入

文件：`infra/meeting-infra/docker/compose/docker-compose.yml`

- [ ] **6.6.2.a** 新增 `meeting-api` service（`profile: ["full-stack"]`），env 注入 HMAC / DB / RabbitMQ / MinIO
- [ ] **6.6.2.b** 健康检查指向 `/actuator/health`；依赖 postgres / rabbitmq / minio 健康

#### 6.6.3 PDF smoke

文件：`infra/meeting-infra/scripts/export-pdf-smoke.sh`

- [ ] **6.6.3.a** 假设 full-stack 已启动；脚本调用：
  ```bash
  curl -X POST http://localhost:8080/api/auth/login -d '{...}' | jq -r .data.accessToken
  curl -X POST .../api/meetings -d '{...}'
  curl -X POST .../api/meetings/{id}/exports -d '{"format":"PDF"}'
  # 轮询直到 status=SUCCEEDED
  curl .../api/exports/{exportId} -o /tmp/test.pdf
  pdftotext /tmp/test.pdf - | grep -q "watermark text"  # 验证水印
  ```
- [ ] **6.6.3.b** 加入 CI（可选，profile=`full-stack-smoke`）

#### 6.6.4 Dashboard 增量

- [ ] **6.6.4.a** `infra/meeting-infra/observability/dashboards/meeting-api-overview.json` 加面板：export 成功率 / 转换耗时分布 / 文件大小直方图 / 错误码 TopN

### 6.7 Phase 6 验收

- [ ] **6.7.1** 跑 `npm run check && ./mvnw verify && cd apps/meeting-web && npm test && cd ../ai-worker && uv run pytest` 全绿
- [ ] **6.7.2** 手工 E2E（参考 todo.md 阶段 2 收尾备忘格式）：登录 → 上传 → AI 完成 → 创建 PDF 导出 → 等 SUCCEEDED → 下载 → 撤销 → 二次下载 410
- [ ] **6.7.3** 故意编辑转录后立即创建导出 → 422 `EXPORT_CONTENT_STALE`
- [ ] **6.7.4** todo.md 阶段 6 全部勾选 + `阶段 6 收尾备忘（YYYY-MM-DD）` 段落

---

## Phase 7：合规

### 7.0 前置

- [ ] **7.0.1** 先建 6 个 package（compliance / audit / breakglass 三个域 × 5 层 COLA，加 break-glass 的 migration）
- [ ] **7.0.2** Flyway migration `V202605200001__break_glass_requests.sql`（schema 在 7.4.1 详述）

### 7.1 契约补齐（1 PR · ~2 天）`[BLOCKS 7.2-7.6]`

#### 7.1.1 enums.yaml 增量

- [ ] **7.1.1.a** 追加：
  ```yaml
  legalHoldStatus: [ACTIVE, RELEASED]
  legalHoldScopeType: [MEETING, DOCUMENT, SPEAKER_PROFILE, PROJECT]

  deletionJobStatus:
    - REQUESTED
    - PENDING_APPROVAL    # 一期可不用，但 schema 保留
    - RUNNING
    - SUCCEEDED
    - PARTIAL_FAILED
    - FAILED
    - BLOCKED_BY_LEGAL_HOLD
  deletionScopeType: [MEETING, DOCUMENT, SPEAKER_PROFILE, USER, PROJECT, TENANT]

  breakGlassStatus: [PENDING, APPROVED, REJECTED, EXPIRED, REVOKED]

  auditActorType: [USER, SYSTEM, SERVICE_ACCOUNT]
  auditResult: [SUCCESS, FAILURE, BLOCKED, DENIED]
  auditAction:
    - CREATE
    - READ
    - UPDATE
    - DELETE
    - EXPORT
    - LOGIN
    - LOGOUT
    - LEGAL_HOLD_PLACE
    - LEGAL_HOLD_RELEASE
    - DELETION_REQUEST
    - DELETION_EXECUTE
    - BREAK_GLASS_REQUEST
    - BREAK_GLASS_APPROVE
    - BREAK_GLASS_REJECT
    - BREAK_GLASS_ACCESS
  ```

#### 7.1.2 public-api.yaml schema

- [ ] **7.1.2.a** 12 个新 schema（按类型组织）：
  - `CreateLegalHoldRequest`、`LegalHoldResponse`、`ReleaseLegalHoldRequest`
  - `CreateDeletionJobRequest`、`DeletionJobResponse`、`DeletionCertificateResponse`
  - `CreateBreakGlassRequest`、`BreakGlassResponse`、`ApproveBreakGlassRequest`、`RejectBreakGlassRequest`
  - `AuditEventResponse`、`ListAuditEventsQueryParams`
- [ ] **7.1.2.b** `DeletionCertificateResponse` 含 `objectHashes` 数组（每项 `{bucket, key, sha256}`）+ `certificateHash` + `failedItems` + `downloadUrl`（PDF 副本）
- [ ] **7.1.2.c** 新增 `GET /admin/audit-events` 路由（带 cursor 分页 + 时间窗 + 资源筛选）+ `GET /admin/audit-events/export`（请求审计导出）

#### 7.1.3 error-codes.yaml

- [ ] **7.1.3.a** 新增：
  ```
  LEGAL_HOLD_BLOCKED                  423 retryable=false
  LEGAL_HOLD_NOT_FOUND                404
  LEGAL_HOLD_ALREADY_RELEASED         409
  DELETION_JOB_BLOCKED_BY_LEGAL_HOLD  423 retryable=false
  DELETION_JOB_ALREADY_FINISHED       409
  BREAK_GLASS_REQUEST_EXPIRED         410
  BREAK_GLASS_INSUFFICIENT_APPROVERS  403
  BREAK_GLASS_SELF_APPROVAL_FORBIDDEN 403
  AUDIT_QUERY_TOO_BROAD               400
  ```

#### 7.1.4 Fixtures

- [ ] **7.1.4.a** valid: `create-legal-hold-on-meeting-201.json`、`create-deletion-job-202.json`、`get-deletion-certificate-200.json`、`create-break-glass-200.json`、`approve-break-glass-200.json`、`list-audit-events-200.json`
- [ ] **7.1.4.b** invalid: `deletion-blocked-by-legal-hold-423.json`、`break-glass-expired-410.json`、`break-glass-self-approval-403.json`、`audit-query-too-broad-400.json`

### 7.2 Legal Hold（1-2 PR · ~1 周）`[BLOCKS 7.3]`

#### 7.2.1 LegalHold 聚合

文件：`meeting-api-domain/.../domain/compliance/LegalHold.java`

- [ ] **7.2.1.a** 字段：`id, tenantId, scopeType, scopeId, reason, requestedBy, approvedBy, status, createdAt, releasedAt, releasedBy, releaseReason`
- [ ] **7.2.1.b** 状态机：`ACTIVE → RELEASED`（不可重新激活），方法 `release(userId, reason, at)`
- [ ] **7.2.1.c** `LegalHoldRepository`：`save / findById / findActive(tenantId, scopeType, scopeId) / listByTenant(pageable)`

#### 7.2.2 LegalHoldCheckPort（其他域调用的查询端口）

文件：`meeting-api-domain/.../domain/compliance/LegalHoldCheckPort.java`

- [ ] **7.2.2.a** 单方法接口：`boolean isProtected(String tenantId, String scopeType, String scopeId);`
- [ ] **7.2.2.b** 实现 `JdbcLegalHoldCheckPort` 用单条 SQL：`SELECT 1 FROM legal_holds WHERE tenant_id=? AND scope_type=? AND scope_id=? AND status='ACTIVE' LIMIT 1`，**带 1s 短缓存**（Caffeine，避免热路径打数据库）
- [ ] **7.2.2.c** 缓存失效：`Place` / `Release` 应用服务在事务提交后 evict 对应 key

#### 7.2.3 ApplicationService

文件：`meeting-api-app/.../app/compliance/LegalHoldApplicationService.java`

- [ ] **7.2.3.a** 实现 5 个方法（`create / get / list / release / delete`）；`delete` 是 release 的别名（OpenAPI DELETE = release）
- [ ] **7.2.3.b** create 时 audit log + outbox `LegalHoldPlacedEvent`
- [ ] **7.2.3.c** release 时 audit log + outbox `LegalHoldReleasedEvent` + 清缓存

#### 7.2.4 跨域注入：调用方加 legal hold check

修改以下三个文件，**每个改动单独 commit 便于评审**：

- [ ] **7.2.4.a** `MeetingApplicationService.delete()` 第一行：`legalHoldCheck.isProtected(tenantId, "MEETING", meetingId)` → throw `LegalHoldBlockedException`
- [ ] **7.2.4.b** `DocumentApplicationService.delete()` 同上 `"DOCUMENT", documentId`
- [ ] **7.2.4.c** `SpeakerProfileApplicationService.delete()` 同上 `"SPEAKER_PROFILE", profileId`
- [ ] **7.2.4.d** `CreateExportApplicationService.create()` 同上（这是 Phase 6.3.1.a 已经在签名里预留的依赖）

#### 7.2.5 Controller + 测试

- [ ] **7.2.5.a** `LegalHoldController` 5 个路由（OpenAPI 已定义路径）
- [ ] **7.2.5.b** Testcontainers IT：place → 试删除 meeting 返回 423 → release → 删除成功 200
- [ ] **7.2.5.c** ArchUnit：`compliance` 包不依赖 `meeting` / `document` / `speaker` 的 `ApplicationService`（避免循环依赖）；反向依赖只能通过 `LegalHoldCheckPort` 单向

### 7.3 Deletion Job（2 PR · ~1.5 周）`[依赖 7.2]`

#### 7.3.1 DeletionJob 聚合 + Certificate

文件：`meeting-api-domain/.../domain/compliance/DeletionJob.java`

- [ ] **7.3.1.a** 字段（对齐 DDL）：`id, tenantId, scopeType, scopeId, status, requestedBy, approvedBy, legalHoldChecked, deletedRowsJson, deletedFilesJson, kmsKeysDestroyedJson, certificateHash, errorCode, createdAt, finishedAt`
- [ ] **7.3.1.b** 状态机：`REQUESTED → RUNNING → SUCCEEDED|PARTIAL_FAILED|FAILED|BLOCKED_BY_LEGAL_HOLD`
- [ ] **7.3.1.c** `DeletionCertificate` 值对象 + `DeletionCertificateRepository`
- [ ] **7.3.1.d** `CanonicalJsonHasher` 工具：键字典序排序 + UTF-8 + SHA-256，输出 hex；用于 certificate_hash 稳定性

#### 7.3.2 DeletionExecutorPort

文件：`meeting-api-domain/.../domain/compliance/DeletionExecutorPort.java`

- [ ] **7.3.2.a** Strategy 端口：
  ```java
  public interface DeletionExecutorPort {
      String supportedScope();  // MEETING / DOCUMENT / SPEAKER_PROFILE / ...
      DeletionOutcome execute(String tenantId, String scopeId);

      record DeletionOutcome(
          List<DeletedRow> rows,
          List<DeletedFile> files,
          List<DestroyedKey> keys,
          List<FailedItem> failures
      ) {}
  }
  ```
- [ ] **7.3.2.b** 5 个实现 `@Component`：`MeetingDeletionExecutor` / `DocumentDeletionExecutor` / `SpeakerProfileDeletionExecutor` / `UserDeletionExecutor` / `ProjectDeletionExecutor`
- [ ] **7.3.2.c** Executor 内部：
  - 软删 + 物理删 TOS 对象 + KMS 销毁（仅 speaker_profile / user）
  - 失败项进 `failures` 不抛异常
  - 每个 executor **独立短事务**（不在 runner 的长事务里）

#### 7.3.3 CreateDeletionJobApplicationService

- [ ] **7.3.3.a** 与 7.2 LegalHold check 联动：申请时即检查；命中即 `BLOCKED_BY_LEGAL_HOLD` + audit + 不创建 deletion_job 行
- [ ] **7.3.3.b** 否则创建 `REQUESTED` 状态行 + outbox `DeletionJobRequestedEvent`

#### 7.3.4 DeletionJobRunner（outbox listener）

文件：`meeting-api-app/.../app/compliance/DeletionJobRunner.java`

- [ ] **7.3.4.a** 订阅 `DeletionJobRequestedEvent`；用 `SELECT FOR UPDATE` 锁定 deletion_job 行 + status='REQUESTED'
- [ ] **7.3.4.b** **二次检查 legal hold**（race condition 防御：申请到执行之间可能新增 legal hold）
- [ ] **7.3.4.c** 路由到对应 `DeletionExecutorPort` → 收集 outcome → 生成 certificate
- [ ] **7.3.4.d** 失败处理：
  - 任一 failure → `PARTIAL_FAILED`
  - 整个 executor 抛异常 → `FAILED`
  - legal hold 命中 → `BLOCKED_BY_LEGAL_HOLD`
- [ ] **7.3.4.e** Certificate PDF 副本：调 `MarkdownExportGateway` 生成 Markdown → `PdfExportGateway`（**复用 Phase 6**）→ 写 TOS `meeting-artifacts/tenant/{t}/deletion/{j}/certificate.pdf`

#### 7.3.5 Controller + 测试

- [ ] **7.3.5.a** `DeletionJobController` 3 个路由：`POST /admin/deletion-jobs` 返回 `202 + Location`；`GET /admin/deletion-jobs/{id}` + `GET .../{id}/certificate`
- [ ] **7.3.5.b** Testcontainers IT：
  - happy path → 行删除 + 文件删除 + KMS 销毁 + certificate 生成
  - 申请到执行之间放置 legal hold → `BLOCKED_BY_LEGAL_HOLD`
  - TOS 删除 1 个对象失败 → `PARTIAL_FAILED` + failedItems 非空
  - certificate_hash 稳定性：相同 outcome 两次 hash 一致

### 7.4 Break-Glass（1-2 PR · ~1 周）`[与 7.5 同步]`

#### 7.4.1 Migration `V202605200001__break_glass_requests.sql`

- [ ] **7.4.1.a** 表定义：
  ```sql
  CREATE TABLE IF NOT EXISTS break_glass_requests (
    id text PRIMARY KEY,
    tenant_id text NOT NULL REFERENCES tenants(id),
    requester_id text NOT NULL REFERENCES users(id),
    scope_type text NOT NULL,            -- MEETING / DOCUMENT / TENANT
    scope_id text NOT NULL,
    reason text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING', -- PENDING / APPROVED / REJECTED / EXPIRED / REVOKED
    valid_from timestamptz,
    valid_until timestamptz,
    approver_id text REFERENCES users(id),
    approved_at timestamptz,
    rejected_at timestamptz,
    reject_reason text,
    revoked_at timestamptz,
    revoked_by text REFERENCES users(id),
    approvers_json jsonb NOT NULL DEFAULT '[]'::jsonb,  -- 预留 N-of-M
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
  );
  CREATE INDEX break_glass_requests_user_idx
    ON break_glass_requests (tenant_id, requester_id, status, valid_until);
  CREATE INDEX break_glass_requests_scope_idx
    ON break_glass_requests (tenant_id, scope_type, scope_id, status);
  ALTER TABLE break_glass_requests ENABLE ROW LEVEL SECURITY;
  ALTER TABLE break_glass_requests FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_isolation ON break_glass_requests
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
  CREATE TRIGGER set_updated_at BEFORE UPDATE ON break_glass_requests
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
  ```
- [ ] **7.4.1.b** 在 V202605110001 不动；通过新 migration 追加（保持 baseline 完整性）

#### 7.4.2 BreakGlassRequest 聚合

文件：`meeting-api-domain/.../domain/breakglass/BreakGlassRequest.java`

- [ ] **7.4.2.a** 状态机：`PENDING → APPROVED|REJECTED`；`APPROVED → EXPIRED`（自动）`|REVOKED`（手动）
- [ ] **7.4.2.b** 方法：`approve(approverId, validFrom, validUntil, at) / reject(approverId, reason, at) / revoke(by, at) / expire(at)`
- [ ] **7.4.2.c** **关键校验**：`approve` 时 `approverId == requesterId` 抛 `BreakGlassSelfApprovalForbiddenException`

#### 7.4.3 BreakGlassEvaluationPort

- [ ] **7.4.3.a** `boolean hasActiveAccess(String tenantId, String userId, String scopeType, String scopeId);`
- [ ] **7.4.3.b** 查询：status=`APPROVED` AND now() BETWEEN valid_from AND valid_until
- [ ] **7.4.3.c** 缓存：30s TTL（短，避免过期访问）

#### 7.4.4 权限切面联动

- [ ] **7.4.4.a** 在 `@PermissionRequired` 切面（或 `MeetingAuthorizationService.canRead()`）查询：如果用户没有常规权限但 break-glass 在窗口内 → 放行 + **强制** audit `BREAK_GLASS_ACCESS`
- [ ] **7.4.4.b** Audit payload 含 `breakGlassId`、`scopeType`、`scopeId`、`requestPath`

#### 7.4.5 过期扫描

文件：`meeting-api-app/.../app/breakglass/BreakGlassExpiryScanner.java`

- [ ] **7.4.5.a** `@Scheduled(fixedDelay = 5*60*1000)`，扫描 `status=APPROVED AND valid_until < now()` → expire
- [ ] **7.4.5.b** 配置：`meeting.break-glass.scanner.enabled=true`、`meeting.break-glass.default-window-hours=4`

#### 7.4.6 Controller + 测试

- [ ] **7.4.6.a** 5 个路由（list / create / approve / reject / audit）
- [ ] **7.4.6.b** Testcontainers IT：
  - 申请 → 审批 → 窗口内访问 `CONFIDENTIAL` 会议成功 + audit 行
  - 时间过期 → 访问失败 + audit `BLOCKED`
  - 自审批拒绝 403
  - 审批后撤销 → 立即失效

### 7.5 Audit Logger + Query API（1 PR · ~3 天）`[与 7.2-7.4 同步]`

#### 7.5.1 AuditEventLogger

文件：`meeting-api-infrastructure/.../audit/AuditEventLogger.java`

- [ ] **7.5.1.a** 单方法接口：
  ```java
  public interface AuditEventLogger {
      void log(AuditAction action, String resourceType, @Nullable String resourceId,
               AuditResult result, Map<String, Object> payload, @Nullable String reason);
  }
  ```
- [ ] **7.5.1.b** 实现自动从 ThreadLocal 取 `actorUserId / traceId / ip / userAgent`（参考 `TenantContextHolder`）
- [ ] **7.5.1.c** 写 `audit_events` 行**在调用方事务内**（audit 失败应阻断业务，否则审计可被绕过）

#### 7.5.2 切面接入点

- [ ] **7.5.2.a** 所有合规相关写操作：legal-hold place/release、deletion job create/complete、break-glass create/approve/reject/access、export create/revoke、login/logout
- [ ] **7.5.2.b** 推荐用 `@AuditedAction(action = ..., resourceType = "...")` 注解 + AOP 切面统一处理

#### 7.5.3 Query API

文件：`meeting-api-adapter/.../adapter/audit/AuditEventController.java`

- [ ] **7.5.3.a** `GET /admin/audit-events?actorId=...&resourceType=...&action=...&from=...&to=...&cursor=...`
- [ ] **7.5.3.b** **强制**最大时间窗 90 天，超出 → `AUDIT_QUERY_TOO_BROAD` 400
- [ ] **7.5.3.c** RLS：admin 可查任意 user 的事件（依赖 RLS policy 已通过 `tenant_id` 隔离）；非 admin 通过 application 层加 `actor_user_id = currentUser` 过滤

#### 7.5.4 审计导出

- [ ] **7.5.4.a** `POST /admin/audit-events/export?from=...&to=...` → 创建一个 scope_type=`AUDIT` 的 ExportJob（走 Phase 6.3.5 的 ExportGateway，新增 `AuditExportGateway`）
- [ ] **7.5.4.b** 输出为 CSV（默认）或 Markdown；最多 100K 行

### 7.6 前端合规页（2 PR · ~1 周）

#### 7.6.1 4 个 admin 页面

- [ ] **7.6.1.a** `/admin/legal-holds` - 列表 + 创建对话框（scope_type 下拉 + scope_id 输入 + reason 文本框）+ release 按钮
- [ ] **7.6.1.b** `/admin/deletion-jobs` - 列表（按状态筛选）+ 创建对话框 + 状态自刷新 + 失败项摘要可展开
- [ ] **7.6.1.c** `/admin/deletion-jobs/:jobId/certificate` - 证书详情 + 对象 hash 列表 + 下载 PDF 副本
- [ ] **7.6.1.d** `/admin/break-glass` - 三个 tab：我的申请 / 待我审批 / 审计

#### 7.6.2 权限隐藏 vs 安全边界

- [ ] **7.6.2.a** 入口按角色隐藏：`compliance:delete` / `compliance:legal-hold` / `security:break-glass` / `audit:read`
- [ ] **7.6.2.b** 但**所有写操作失败都展示后端稳定错误码**（前端隐藏不是安全边界，参考 web SPEC §5.1）

#### 7.6.3 Vitest

- [ ] **7.6.3.a** 各页面错误态、Idempotency-Key 复用、空态展示

### 7.7 Infra（与 8.x 协同）

- [ ] **7.7.1** `infra/meeting-infra/scripts/legal-hold-lifecycle-smoke.sh`：模拟 MinIO 对象 + 数据库行，验证 legal hold 命中时生命周期清理不删受保护对象
- [ ] **7.7.2** `docs/runbooks/backup-recovery.md`：PG pg_basebackup + WAL、RPO 5min/RTO 30min、TOS sha256 校验、季度演练 checklist
- [ ] **7.7.3** `docs/runbooks/legal-hold-procedure.md`：标准操作流程
- [ ] **7.7.4** Grafana `compliance.json` 面板增量：legal hold 数（按 scope_type）、deletion 成功率、break-glass 审批率、audit 事件 TopN

### 7.8 验收

- [ ] **7.8.1** Legal hold 端到端：place → 删 meeting 423 → 创建 export 423 → release → 删除 200
- [ ] **7.8.2** Deletion 端到端：申请 → 执行 → certificate 生成 + 下载 → 对象 hash 校验通过
- [ ] **7.8.3** Deletion + legal hold 竞态：申请到执行之间放 hold → `BLOCKED_BY_LEGAL_HOLD`
- [ ] **7.8.4** Break-glass 端到端：申请 → 审批 → 窗口内访问 → 过期 → 再次访问 BLOCKED
- [ ] **7.8.5** Audit 查询：admin 可查任意 user；普通 user RLS 拦截
- [ ] **7.8.6** todo.md 阶段 7 全部勾选

---

## Phase 8：硬化

### 8.1 Java 指标 / 健康 / fail-fast（2 PR · ~1 周）

#### 8.1.1 扩展 MeetingApiMetrics

文件：`meeting-api-app/.../app/observability/MeetingApiMetrics.java`

- [ ] **8.1.1.a** Public endpoint timer interceptor：实现 `HandlerInterceptor`：
  ```java
  @Component
  public class HttpRequestTimerInterceptor implements HandlerInterceptor {
      // preHandle: record start time in request attribute
      // afterCompletion: registry.timer("meeting_api_request_duration_seconds",
      //   "method", req.method(), "path_template", pathTemplate(handler),
      //   "status", String.valueOf(status))
      //   .record(Duration.ofNanos(System.nanoTime() - start));
  }
  ```
  注册：`WebMvcConfigurer.addInterceptors(...)`，排除 `/actuator/**` 和 `/internal/**`
- [ ] **8.1.1.b** RAG 拆分计时（直接在 `RagQueryApplicationService` 用 `Timer.Sample.start(registry)`）：
  ```
  rag_query_phase_duration_seconds{phase="scope_calc"}
  rag_query_phase_duration_seconds{phase="vector_search"}
  rag_query_phase_duration_seconds{phase="keyword_search"}
  rag_query_phase_duration_seconds{phase="permission_recheck"}
  rag_query_phase_duration_seconds{phase="rerank_call"}
  rag_query_phase_duration_seconds{phase="llm_call"}
  ```
- [ ] **8.1.1.c** 新增 counter：
  - `kms_envelope_encrypt_failures_total{operation}`
  - `llm_calls_blocked_security_level_total{level}`
  - `tenant_context_missing_total{path}`
  - `export_renders_total{format,outcome}`
  - `deletion_jobs_total{scope_type,outcome}`
  - `legal_hold_blocks_total{operation}`
  - `break_glass_accesses_total{result}`
- [ ] **8.1.1.d** 单元测试：`MeetingApiMetricsTest` 验证每个 counter / timer 有正确的 name + tags

#### 8.1.2 HealthIndicator

文件：`meeting-api-infrastructure/.../health/`

- [ ] **8.1.2.a** `PostgresRlsHealthIndicator`：起 dummy tenant_id + SELECT 1 行 → DOWN if not blocked properly
- [ ] **8.1.2.b** `RabbitMqQueueHealthIndicator`：校验 `audio-cpu-queue` / `gpu-asr-queue` / `gpu-diar-queue` / `gpu-speaker-queue` / `embed-queue` / `llm-queue` / `export-queue` 全部存在
- [ ] **8.1.2.c** `MinIoHealthIndicator`：`/minio/health/live` + bucket 写 1 byte smoke
- [ ] **8.1.2.d** `KmsHealthIndicator`：测试 wrap/unwrap
- [ ] **8.1.2.e** `AiWorkerHealthIndicator`：`GET /internal/health`（带 HMAC）
- [ ] **8.1.2.f** `OutboxBacklogHealthIndicator`：count `WHERE status='PENDING' AND created_at < now() - interval '30 seconds'`；> 5000 → DEGRADED；> 50000 → DOWN

#### 8.1.3 prod profile fail-fast

文件：`meeting-api-start/.../start/config/ProdProfileValidator.java`

- [ ] **8.1.3.a** `@Profile("prod") @Component` + `@PostConstruct` 校验：
  - `meeting.callback.hmac-secret` 非空 + 非 demo 值
  - `meeting.ai-worker.hmac-secret` 非空 + 与 callback 不同
  - `meeting.ai-worker.base-url` 非 `localhost` / `127.0.0.1`
  - `meeting.chunk.strategy-version` 非空
  - `meeting.kms.master-key-id` 非空
  - `meeting.llm.allow-confidential=false`（不能为 true）
  - `spring.flyway.baseline-on-migrate=false`
- [ ] **8.1.3.b** 校验失败抛 `BeanCreationException` + 清晰的 message：`"prod profile requires meeting.callback.hmac-secret to be a non-demo value, but found '<masked>'"`

### 8.2 Prometheus rules + dashboard 增量（1 PR · ~3 天）

文件：`infra/meeting-infra/observability/prometheus/rules.yaml`

- [ ] **8.2.1** 10 条 alert：
  ```yaml
  groups:
    - name: meeting-api
      rules:
        - alert: OutboxBacklogHigh
          expr: meeting_api_outbox_backlog > 5000
          for: 30m
          labels: {severity: critical}
        - alert: OutboxPublishLag
          expr: meeting_api_outbox_oldest_pending_seconds > 30
          for: 5m
          labels: {severity: critical}
        - alert: RabbitMqDlqDepth
          expr: rabbitmq_queue_messages_ready{queue=~".*\\.dlq"} > 100
          for: 10m
          labels: {severity: warning}
        - alert: CallbackAuthFailureSurge
          expr: increase(meeting_api_callback_total{outcome="auth_failed"}[5m]) > 50
          labels: {severity: critical}
        - alert: RagRerankDegradedRate
          expr: rate(meeting_api_ai_worker_calls{operation="rerank",outcome="degraded"}[5m]) /
                rate(meeting_api_ai_worker_calls{operation="rerank"}[5m]) > 0.2
          for: 10m
          labels: {severity: warning}
        - alert: KmsFailureSurge
          expr: increase(kms_envelope_encrypt_failures_total[5m]) > 5
          labels: {severity: critical}
        - alert: GpuOom
          expr: increase(ai_worker_oom_exits_total[10m]) > 0
          labels: {severity: critical}
        - alert: ExportFailureRateHigh
          expr: rate(export_renders_total{outcome="failed"}[1h]) /
                rate(export_renders_total[1h]) > 0.1
          for: 30m
          labels: {severity: warning}
        - alert: DeletionJobStuck
          expr: time() - max(deletion_job_running_started_at) > 7200
          labels: {severity: critical}
        - alert: SecurityLevelBlockedSurge
          expr: increase(llm_calls_blocked_security_level_total{level=~"PUBLIC|INTERNAL"}[15m]) > 0
          labels: {severity: warning}
  ```
- [ ] **8.2.2** Alertmanager `routes`: severity=critical → webhook stub；warning → Slack webhook（一期 stub 即可）
- [ ] **8.2.3** Dashboard 增量：在 `meeting-api-overview.json` 加 HealthIndicator 状态面板（用 `up{job="meeting-api"}` + `kube_pod_container_status_ready`）

### 8.3 前端安全 / 监控 / 性能（2 PR · ~1 周）

#### 8.3.1 CSP

文件：`apps/meeting-web/vite.config.ts` + 部署 nginx 配置

- [ ] **8.3.1.a** 开发环境通过 `vite-plugin-csp-guard` 注入
- [ ] **8.3.1.b** 生产 nginx：
  ```
  add_header Content-Security-Policy
    "default-src 'self'; \
     connect-src 'self'; \
     img-src 'self' data:; \
     style-src 'self' 'nonce-$request_id'; \
     script-src 'self'; \
     frame-ancestors 'none';" always;
  add_header X-Frame-Options "DENY" always;
  add_header X-Content-Type-Options "nosniff" always;
  ```

#### 8.3.2 Markdown sanitizer

文件：`apps/meeting-web/src/shared/markdown/SafeMarkdown.tsx`

- [ ] **8.3.2.a** 用 `react-markdown` + `rehype-sanitize` + 自定义 schema（禁 `<script>` `<iframe>` `on*` `javascript:`）
- [ ] **8.3.2.b** 所有渲染 Markdown 入口（`RagPage` answer / `MinutesPage` body / evidence / document preview）改用 `<SafeMarkdown>`
- [ ] **8.3.2.c** Vitest XSS 库 `safe-markdown.test.tsx`：20+ payload（每行一个）：
  - `<script>alert(1)</script>`
  - `<img src=x onerror=alert(1)>`
  - `[link](javascript:alert(1))`
  - `<iframe src="http://evil"></iframe>`
  - `<svg onload=alert(1)>`
  - ...

#### 8.3.3 前端监控（Sentry / 自研）

- [ ] **8.3.3.a** `services/telemetry.ts` 暴露 `reportError(err, context)`；context 限定：`{route, errorCode, requestId, traceId, browser, os}`
- [ ] **8.3.3.b** `redactSensitive(payload)` 中间件：自动剥除 `Authorization` / `token` / `password` / `transcript` 等字段
- [ ] **8.3.3.c** Vitest 验证：含敏感字段的 payload 经 redact 后字段不在最终 report 中

#### 8.3.4 Code split + bundle 验证

- [ ] **8.3.4.a** `App.tsx` 用 `React.lazy(() => import(...))` 拆分：
  ```ts
  const RagPage = lazy(() => import('./features/rag/RagPage'));
  const TranscriptEditPage = lazy(() => import('./features/transcript/TranscriptEditPage'));
  const ExportsPage = lazy(() => import('./features/exports/ExportsPage'));
  const CompliancePages = lazy(() => import('./features/compliance/CompliancePages'));
  const AdminPages = lazy(() => import('./features/admin/AdminPages'));
  ```
- [ ] **8.3.4.b** `npm run build` 后用 `vite-bundle-visualizer` 检查首屏 gzip JS < 200KB；超出则报告具体大块（`lodash` / 大组件库等）

### 8.4 ai-worker 观测 / 模型供应链（1-2 PR · ~1 周）

#### 8.4.1 `/internal/models` 增强

文件：`apps/ai-worker/ai_worker/interfaces/api/models_endpoint.py`

- [ ] **8.4.1.a** 返回 schema：
  ```json
  {
    "models": [
      {
        "capability": "TEXT_EMBEDDING",
        "name": "bge-m3",
        "version": "v1",
        "checksum": "sha256:abc...",
        "device": "cuda:0",
        "status": "READY|LOADING|FAILED",
        "loaded_at": "2026-05-17T12:00:00Z",
        "warmup_completed_at": "...",
        "last_error": null
      }
    ]
  }
  ```
- [ ] **8.4.1.b** 校验 checksum：启动时计算每个模型权重文件 sha256，对比 `model_registry` 表的 checksum；不匹配 → status=FAILED + 拒绝 ready

#### 8.4.2 GPU metrics

文件：`apps/ai-worker/ai_worker/observability/gpu_metrics.py`

- [ ] **8.4.2.a** 用 `pynvml` 暴露：
  ```
  ai_worker_gpu_memory_used_bytes{device}
  ai_worker_gpu_memory_total_bytes{device}
  ai_worker_gpu_utilization_percent{device}
  ai_worker_model_rtf{step}                # ASR / DIARIZATION / ...
  ai_worker_step_failures_total{step,error_code}
  ai_worker_oom_exits_total
  ```
- [ ] **8.4.2.b** 在 ASR / Diarization actor 内 `try/except torch.cuda.OutOfMemoryError` → 写 callback `ASR_GPU_OOM` → `sys.exit(137)`

#### 8.4.3 Prod air-gapped

- [ ] **8.4.3.a** prod profile env 强制：`HF_HUB_OFFLINE=1` + `TRANSFORMERS_OFFLINE=1`
- [ ] **8.4.3.b** 模型路径只从 `/opt/models/` 加载（不允许任何 `from_pretrained("hf://...")`）
- [ ] **8.4.3.c** 启动日志：每个模型 `path=/opt/models/bge-m3 checksum=sha256:abc... size=2.1GB`

#### 8.4.4 `docs/model-registry.md` 填充

- [ ] **8.4.4.a** 每个模型填入实际 checksum、内网制品路径、审批人、审批日期
- [ ] **8.4.4.b** 同步写入 `model_registry` 表（启动时 seed migration）

### 8.5 Dockerfile（3 PR · ~1 周）

#### 8.5.1 meeting-api Dockerfile —— 见 6.6.1

#### 8.5.2 meeting-web Dockerfile

文件：`apps/meeting-web/Dockerfile`

- [ ] **8.5.2.a** Multi-stage：
  ```dockerfile
  FROM node:20-alpine AS build
  WORKDIR /src
  COPY package*.json ./
  RUN npm ci
  COPY . .
  RUN npm run build

  FROM nginx:1.27-alpine
  COPY --from=build /src/dist /usr/share/nginx/html
  COPY nginx.conf /etc/nginx/conf.d/default.conf
  EXPOSE 80
  HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
      CMD wget --spider -q http://localhost/ || exit 1
  ```
- [ ] **8.5.2.b** `nginx.conf` 配置 CSP / XFO / gzip / brotli / SPA fallback (`try_files $uri /index.html`)
- [ ] **8.5.2.c** 最终镜像 < 100MB

#### 8.5.3 ai-worker Dockerfile

文件：`apps/ai-worker/Dockerfile`

- [ ] **8.5.3.a** 基础镜像：`nvidia/cuda:12.2-runtime-ubuntu22.04`
- [ ] **8.5.3.b** uv 安装 + `uv sync --frozen`
- [ ] **8.5.3.c** 模型权重**不内嵌**，通过 PV 挂载 `/opt/models/`
- [ ] **8.5.3.d** entrypoint 启动前检查 `nvidia-smi`，无 GPU 则 exit 1

#### 8.5.4 三份 `.dockerignore`

- [ ] **8.5.4.a** 共同排除：`target/` `node_modules/` `.git/` `__pycache__/` `.venv/` `*.log` `.idea/`

### 8.6 K8s + Terraform（3-4 PR · ~2 周）

#### 8.6.1 base/meeting-api

文件：`infra/meeting-infra/k8s/base/meeting-api/`

- [ ] **8.6.1.a** `deployment.yaml`（replicas: 2，readinessProbe `/actuator/health/readiness`，livenessProbe `/actuator/health/liveness`，resources requests/limits）
- [ ] **8.6.1.b** `service.yaml`（ClusterIP，port 8080）
- [ ] **8.6.1.c** `configmap.yaml`（非敏感配置）+ env from Secret（HMAC / DB / KMS）
- [ ] **8.6.1.d** `hpa.yaml`（cpu>70% 扩容，max 6 replicas）
- [ ] **8.6.1.e** `pdb.yaml`（minAvailable: 1）
- [ ] **8.6.1.f** `servicemonitor.yaml`（Prometheus Operator）

#### 8.6.2 base/meeting-web

- [ ] **8.6.2.a** `deployment.yaml` + `service.yaml` + `configmap.yaml`（含 nginx 配置）

#### 8.6.3 base/ai-worker

- [ ] **8.6.3.a** **StatefulSet**（worker name 稳定用于 lease owner）
- [ ] **8.6.3.b** `nodeSelector: {nvidia.com/gpu.present: "true"}`，resources `nvidia.com/gpu: 1`
- [ ] **8.6.3.c** PV 挂载模型权重路径

#### 8.6.4 base/postgres + base/rabbitmq

- [ ] **8.6.4.a** PG StatefulSet + WAL 归档 sidecar / cronjob
- [ ] **8.6.4.b** RabbitMQ StatefulSet × 3（quorum queue）+ 启动应用 `definitions.json`

#### 8.6.5 overlays

- [ ] **8.6.5.a** `overlays/dev/kustomization.yaml`：1 副本 + 低资源 + image tag `:dev`
- [ ] **8.6.5.b** `overlays/staging/`、`overlays/prod/`：副本数 + HPA + 资源 + `imagePullPolicy: IfNotPresent` + `readOnlyRootFilesystem: true`

#### 8.6.6 Terraform

- [ ] **8.6.6.a** `terraform/main.tf` 至少 3 个资源：PostgreSQL RDS / 对象存储 bucket / KMS key
- [ ] **8.6.6.b** secrets 通过 vault provider 注入，**不进 state**

#### 8.6.7 CI

- [ ] **8.6.7.a** `.github/workflows/ci.yml` 加 job `k8s-lint`：`kustomize build overlays/dev | kubeval`

### 8.7 Playwright E2E（1 PR · ~1 周）

#### 8.7.1 框架

文件：`apps/meeting-web/e2e/`

- [ ] **8.7.1.a** `playwright.config.ts` + 1 个 `tests/` 目录
- [ ] **8.7.1.b** package.json: `"e2e": "playwright test"` + `"e2e:install": "playwright install chromium"`
- [ ] **8.7.1.c** CI job `meeting-web-e2e`：用 docker-compose 起 full-stack → 跑 e2e

#### 8.7.2 主链路 spec

文件：`apps/meeting-web/e2e/tests/main-flow.spec.ts`

- [ ] **8.7.2.a** 登录 → 创建会议 → 上传 30s WAV（用 fixture audio）→ 等待 SUCCEEDED → 转录可见 → 纪要 regenerate → RAG 提问 + citation 验证 → 创建 PDF 导出 → 下载校验
- [ ] **8.7.2.b** `CONFIDENTIAL` 分支：手工创建 CONFIDENTIAL 会议 → 触发自动 LLM → 验证 422 + 固定文案
- [ ] **8.7.2.c** STALE 分支：编辑转录 → 验证下游 STALE 提示出现
- [ ] **8.7.2.d** Legal hold 分支：管理员 place hold → 普通用户尝试删除会议 → 423

#### 8.7.3 稳定性

- [ ] **8.7.3.a** retry 1 次；CI 5 次连跑 ≥ 4 次通过；失败上传 trace artifact
- [ ] **8.7.3.b** 跑时长目标 < 10 min

### 8.8 验收

- [ ] **8.8.1** staging 起 full-stack → 所有 HealthIndicator UP；Prometheus rules 加载；Grafana dashboards 全部数据
- [ ] **8.8.2** prod profile fail-fast：故意删 env → 启动失败 + 错误日志清晰指出缺失的 key
- [ ] **8.8.3** 前端 CSP 0 violation；bundle visualizer 首屏 gzip < 200KB；XSS test 全过
- [ ] **8.8.4** ai-worker `/internal/models` 含 checksum；checksum 不匹配时 ready=false
- [ ] **8.8.5** Playwright 主链路 CI 上稳定（5 连跑 ≥ 4）
- [ ] **8.8.6** K8s `dev` overlay 在 kind / minikube 起来 + 无 CrashLoopBackOff
- [ ] **8.8.7** todo.md 阶段 8 全部勾选

---

## 跨阶段交叉点（容易遗漏）

- **6.3.1（CreateExport）+ 7.2.4.d（LegalHold check 注入）**：CreateExportApplicationService 在 7.2 之前实现时可暂时跳过 `legalHoldCheck` 注入，但**契约错误码**（`LEGAL_HOLD_BLOCKED`）必须在 6.1.3 提前就位 —— 这样 7.2 落地时只改一行依赖注入，不改契约。
- **6.3.5（PdfExportGateway）+ 7.3.4.e（Deletion certificate PDF）**：PdfExportGateway 必须是**独立可调用**的 Spring bean，不要把 PDF 生成深耦合在 ExportQueueConsumer 内。Phase 7 复用时直接 `@Autowired PdfExportGateway`。
- **6.4.2.c（Export consumer 短事务）+ 7.3.4（Deletion runner 短事务）+ CLAUDE.md invariant 11**：两个 runner 都要明确不在 `@Transactional` 内调外部依赖。建议都用 `TenantContextHolder.set(...)` + 显式 `txTemplate.execute(...)` 包裹仅持久化部分。
- **6.4.2.d（Export DLQ）+ 8.2.1 RabbitMqDlqDepth alert**：DLQ 警报阈值要在 6.4 落地时同步设定，否则 export 失败会静默堆积。
- **7.4.4（Break-glass 切面）+ 8.3.3（Sentry redact）**：BreakGlass payload 含敏感字段，前端 Sentry reporter 看到的 `error.details` 必须 redact。
- **7.5.1（AuditLogger）+ 8.1.1.c（audit metrics）**：每条 audit log 同步打 `audit_events_total{action,result}` counter，便于看趋势。
- **8.4.1（/internal/models checksum）+ 7.3.4.c（Deletion KMS 销毁）**：deletion 销毁 speaker_profile 的 KMS DEK 时，`actual_model_version` 需要写入 `kms_keys_destroyed_json.model_version` 字段以便审计追溯。

---

## 推荐落地顺序（按 PR 序列）

1. **PR-A**：`6.0.1` Phase 5 勾选 + 备忘段落
2. **PR-B**：`6.0.2` Export 6 个 package-info 占位
3. **PR-C**：`6.1` 契约补齐（含 fixtures + codegen）
4. **PR-D**：`6.2` Export domain 层
5. **PR-E**：`6.3.1-6.3.4` Export create + repo + outbox
6. **PR-F**：`6.3.5` 三份 Gateway
7. **PR-G**：`6.4` Adapter + consumer
8. **PR-H**：`6.5` 前端 ExportsPage
9. **PR-I**：`6.6` Dockerfile + smoke
10. **PR-J**：`6.7` Phase 6 验收
11. **PR-K**：`7.0` + `7.1` 合规契约
12. **PR-L**：`7.2` LegalHold（含 7.2.4 三处跨域注入）
13. **PR-M**：`7.3` DeletionJob
14. **PR-N**：`7.4` BreakGlass
15. **PR-O**：`7.5` AuditLogger + Query
16. **PR-P**：`7.6` 前端合规页
17. **PR-Q**：`8.1-8.2` 指标 / 告警
18. **PR-R**：`8.3` 前端安全
19. **PR-S**：`8.4` ai-worker 供应链
20. **PR-T**：`8.5` 3 份 Dockerfile
21. **PR-U**：`8.6` K8s + Terraform
22. **PR-V**：`8.7-8.8` Playwright + 验收

每个 PR 控制在 800 行 diff 以内（除契约 codegen 产物）；超出说明拆得不够细，回到本计划继续切。

---

## 与 todo.md 同步策略

每完成一个本计划的 checkbox（不是 PR），同步在 `todo.md` 对应行勾选；如果 todo.md 没有对应条目（如 6.1.1 这种细化项），不需要回填 —— 678-plan.md 是细化版，todo.md 是顶层视图。

完成整个阶段后（如 6.7 全过），在 todo.md 该阶段末尾仿 Phase 2 备忘格式新增`阶段 X 收尾备忘（YYYY-MM-DD）`段落，列出：
- 已落地项（与本计划对照）
- 未做项（流程阻塞 / 决策待定 / 后续阶段处理）
- 已知问题（环境依赖 / 集成限制）

这样 PR 评审有依据，下一阶段开工不需要重新对账。
