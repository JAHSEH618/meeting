# Legal Hold Operating Procedure

> 适用于 phase 1。所有 legal hold 走 admin 页面（`/admin/legal-holds`）或 API
> （`/api/legal-holds`），不允许直接改数据库。

## 何时使用

- 监管 / 法务调查（电子证据保全）。
- 用户主动起诉前的数据保留。
- 内部 incident 涉及证据保留。

法定保全命中后，以下操作**返回 423**：

- `DELETE /meetings/{meetingId}` / `DELETE /documents/{documentId}` /
  `DELETE /speaker-profiles/{profileId}`
- `POST /meetings/{meetingId}/exports` —— 防止数据通过导出渠道流出
- `POST /admin/deletion-jobs` —— 删除任务命中后状态为 `BLOCKED_BY_LEGAL_HOLD`

## Place Hold

1. 申请人在 `/admin/legal-holds` 点 "放置保全"。
2. 必填：
   - `scopeType`: `MEETING` / `DOCUMENT` / `SPEAKER_PROFILE` / `PROJECT`
   - `scopeId`: 对应资源 id
   - `reason`: 自由文本（不少于 8 字），将出现在审计与导出受阻提示中
3. 审批人字段（`approvedBy`）一期可空——单审批人模型；phase 2+ 接入 N-of-M。
4. 提交后立即生效：
   - `legal_holds` 行 `status=ACTIVE`
   - `audit_events` 写 `LEGAL_HOLD_PLACE` 行
   - `LegalHoldCheckPort` 缓存即时失效（一期无缓存，phase 8.2 加 Caffeine 后需要 evict）

## Release Hold

1. 在 `/admin/legal-holds` 点对应行的 "释放"。
2. 必填 `release reason`：解释为什么不再需要保全（如 "案件已结" / "数据已通过法务渠道交付"）。
3. 提交后：
   - `legal_holds.status=RELEASED`，`released_at` / `released_by` / `release_reason` 三列填齐
   - `audit_events` 写 `LEGAL_HOLD_RELEASE` 行
   - 受保全的对象立即可再被删除 / 导出

> 状态机一次性。释放后**不可重新激活**——如需重新保全，必须创建新的 legal hold 行。
> 这是为了让审计能区分 "持续保全 5 个月" 和 "保全 → 释放 → 重新保全" 两种不同的法律语义。

## 不允许的操作

- ❌ 直接 `UPDATE legal_holds SET status=...`（违反 RLS + 审计要求）
- ❌ 用同一个 scopeType+scopeId 同时放置多个 ACTIVE 行（`findActive` 返回最新一行，但语义不清；只有一行 ACTIVE 是不变量）
- ❌ 在保全期间用 break-glass 绕过删除（break-glass 只允许 READ，不允许 DELETE / EXPORT，参见 spec §safety）

## 审计

`/admin/audit-events?action=LEGAL_HOLD_PLACE` 或 `LEGAL_HOLD_RELEASE` 查询历史。
所有 `LEGAL_HOLD_BLOCKED` 拒绝同时在 `audit_events` 留下 `BLOCKED` 结果行——含
`scopeType` / `scopeId` / 失败动作（delete / export / deletion_request）。

## 备份恢复后的 legal hold 处理

- legal_holds 表参与 PG 全量备份 + WAL 归档，恢复后状态自动一致。
- 若恢复到比 hold 放置更早的时间点：hold 行不存在 → 此期间的 delete 不会被阻断。
  这是已知限制；运维必须在恢复后立即重新放置仍在 ACTIVE 的所有 hold。
- 用 `SELECT * FROM legal_holds WHERE status='ACTIVE' ORDER BY created_at DESC` 在恢复前
  备份的 hold 清单与恢复后表对比，缺失的需要手动重新放置。

## SLA / SLO

| 指标 | 目标 |
|---|---|
| Place hold p95 latency | < 200 ms |
| Release hold p95 latency | < 200 ms |
| `LEGAL_HOLD_BLOCKED` 返回 p95 latency | < 50 ms (LegalHoldCheckPort) |
| `legal_holds` 备份新鲜度 | < 5 min (来自 PG WAL 归档) |
