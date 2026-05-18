# Backup & Recovery Runbook

> 适用于 phase 1 一期生产 / 预生产环境。RPO 5 分钟、RTO 30 分钟。每季度做一次完整恢复演练。

## 责任与目标

- 业务 SLA：`pg_basebackup` + WAL 归档 → **RPO 5 min**，**RTO 30 min**。
- 任何 deletion job 写入 deletion_certificates 后，对应对象 hash 必须能在恢复后重新校验。
- 备份不进 git，但所有 dashboards / Prometheus rules / RabbitMQ definitions 必须在 git。

## PostgreSQL 备份

### 备份位置

| 类型 | 频率 | 存储 | 保留 |
|---|---|---|---|
| 全量 `pg_basebackup` | 每天 02:00 UTC | TOS (生产) / MinIO (本地) `meeting-backups/postgres/full/{yyyy-MM-dd}/` | 30 天 |
| WAL 归档 | 实时（`archive_command`） | `meeting-backups/postgres/wal/` | 14 天 |
| 逻辑 schema dump | 每天 02:30 UTC | `meeting-backups/postgres/schema/{yyyy-MM-dd}.sql.gz` | 90 天 |

`postgresql.conf` 中应启用：

```
wal_level = replica
archive_mode = on
archive_command = 'aws s3 cp %p s3://meeting-backups/postgres/wal/%f --quiet'
archive_timeout = 300
max_wal_senders = 4
```

### 恢复流程（RTO 30 min 验收）

```bash
# 1. Stop the failed postgres instance.
systemctl stop postgresql

# 2. Restore the most recent base backup.
aws s3 sync s3://meeting-backups/postgres/full/2026-05-17/ /var/lib/postgresql/16/main

# 3. Recovery target.
cat > /var/lib/postgresql/16/main/recovery.signal
cat > /etc/postgresql/16/main/postgresql.auto.conf << 'EOF'
restore_command = 'aws s3 cp s3://meeting-backups/postgres/wal/%f %p'
recovery_target_time = '2026-05-18 10:00:00 UTC'   # last good moment
EOF

# 4. Start; wait for "consistent recovery state reached".
systemctl start postgresql
tail -f /var/log/postgresql/postgresql-16-main.log

# 5. Smoke: verify table presence + recent meeting row + RLS policy on.
psql -c "SELECT count(*) FROM meetings WHERE created_at > now() - interval '1 day';"
psql -c "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN ('meetings','speaker_embeddings','knowledge_chunks');"

# 6. Re-establish app.tenant_id smoke — see `infra/meeting-infra/scripts/rls-smoke.sh`.
```

> **Gotcha**: After restore, the meeting-api outbox publisher will replay events. Confirm
> `domain_events_outbox.status` distribution before opening traffic — uncommitted PENDING
> rows from before the restore are fine; they'll publish to RabbitMQ once the publisher
> reconnects.

## TOS / MinIO Object Storage

- 每个对象在写入时同时写 `sha256` 到 `meeting_files.sha256` / `artifact_manifests.artifact_hash` / `deletion_certificates.object_hashes_json`。
- 恢复后通过遍历 `meeting_files` 表对比 `sha256(SELECT FROM TOS by key)`，差异即 incident。
- 关键前缀：
  - `meeting-audio/tenant/{tenantId}/meeting/{meetingId}/{raw,normalized}/`
  - `meeting-artifacts/tenant/{tenantId}/task/{taskId}/`
  - `meeting-exports/tenant/{tenantId}/meeting/{meetingId}/export/{exportId}/`
  - `meeting-artifacts/tenant/{tenantId}/deletion/{jobId}/`（deletion certificate PDF）
- 删除证书副本应启用 object-lock 30 天保留期（合规要求）。

## RabbitMQ

- 一期采用 quorum queue × 3 节点 + 单 vhost。
- 跨地域灾备**不依赖 RabbitMQ 复制**：domain_events_outbox 是真实事实源，重启 publisher 即可重放。
- DLQ 队列保留 14 天（消息 TTL）；运维通过 `rabbitmqctl list_queues messages_ready` 巡检 DLQ 深度。

## KMS / Vault

- 生产：阿里云 KMS，主密钥 ID 配在 `meeting.kms.master-key-id`。
- 本地：Vault-dev，重启即丢失；恢复演练时**不**测试 Vault-dev。
- 声纹 `speaker_embeddings.wrapped_data_key` 在 KMS rotate 后仍可解（KMS 用 envelope wrapping，主密钥版本独立持久）。
- 主密钥轮换 SOP 见 `docs/runbooks/kms-rotation.md`（待补）。

## 演练 Checklist（季度一次）

- [ ] 用上个月某天的 base backup + WAL 恢复到隔离环境
- [ ] 验证 30 min 内服务可读
- [ ] 抽 10 个 `meeting_files` 行计算 sha256 与 TOS 实际对象比对
- [ ] 解密一个 `speaker_embeddings.embedding_ciphertext` 验证 KMS 路径还通
- [ ] 回放一笔 `domain_events_outbox.status='PENDING'` → 验证 SSE 推送下达
- [ ] 关闭演练环境，记录耗时 + 失败项进入下一季度待办

## 监控告警

| 指标 | 阈值 | Severity |
|---|---|---|
| `pg_basebackup` 最近一次完成时间 > 25h | 紧急 | critical |
| WAL 归档 lag > 10 min | 紧急 | critical |
| Backup bucket 容量 > 80% | 警告 | warning |
| 对象 sha256 比对失败次数 / 10k | 紧急 | critical |
