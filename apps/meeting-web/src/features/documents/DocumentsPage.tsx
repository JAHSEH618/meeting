import { useMemo, useState } from "react";
import {
  useDocumentsQuery,
  useCreateDocument,
  useDeleteDocument,
  useReindexDocument,
} from "./queries";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";
import { formatDate } from "@shared/utils/formatters";

const FILE_ID_HINT =
  "fileId 指向已上传到对象存储的源文件。当前阶段文件上传由后台管道完成；在此处仅登记元数据并触发解析 / 索引。";

const DOCUMENT_TYPES = ["PDF", "DOCX", "MARKDOWN", "TXT"] as const;
type DocumentType = (typeof DOCUMENT_TYPES)[number];
const DEFAULT_DOCUMENT_TYPE: DocumentType = "PDF";

const INDEX_TONE: Record<string, string> = {
  ACTIVE: "pill--success",
  PENDING: "pill--info",
  INDEXING: "pill--info",
  STALE: "pill--warn",
  FAILED: "pill--danger",
};

export function DocumentsPage() {
  const { data, isPending } = useDocumentsQuery();
  const create = useCreateDocument();
  const remove = useDeleteDocument();
  const reindex = useReindexDocument();

  const documents = useMemo(() => {
    const items = data?.items ?? [];
    return [...items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  }, [data]);

  const [showCreate, setShowCreate] = useState(false);
  const [title, setTitle] = useState("");
  const [fileId, setFileId] = useState("");
  const [documentType, setDocumentType] = useState<DocumentType>(DEFAULT_DOCUMENT_TYPE);
  const [contentHash, setContentHash] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<{ documentId: string; title: string } | null>(
    null,
  );

  const apiErr = (create.error ?? remove.error ?? reindex.error) as ApiClientError | null;
  const apiErrMsg = apiErr
    ? (apiErr.code ? getUserMessage(apiErr.code) : "操作失败")
    : null;
  const displayError = formError ?? apiErrMsg;

  const resetCreateForm = () => {
    setTitle("");
    setFileId("");
    setDocumentType(DEFAULT_DOCUMENT_TYPE);
    setContentHash("");
  };

  const handleCreate = async () => {
    if (!title.trim() || !fileId.trim()) {
      setFormError("请填写文档标题和 fileId");
      return;
    }
    setFormError(null);
    try {
      await create.mutateAsync({
        title: title.trim(),
        fileId: fileId.trim(),
        documentType,
        contentHash: contentHash.trim() || null,
      });
      setShowCreate(false);
      resetCreateForm();
    } catch {
      /* surfaces via apiErrMsg */
    }
  };

  const openDeleteDialog = (documentId: string, title: string) => {
    setDeleteTarget({ documentId, title });
  };

  const closeDeleteDialog = () => {
    if (deleteTarget && pendingId === deleteTarget.documentId) return;
    setDeleteTarget(null);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return;
    const { documentId } = deleteTarget;
    setPendingId(documentId);
    try {
      await remove.mutateAsync(documentId);
      setDeleteTarget(null);
    } catch {
      /* surfaces via apiErrMsg */
    } finally {
      setPendingId(null);
    }
  };

  const handleReindex = async (documentId: string) => {
    setPendingId(documentId);
    try {
      await reindex.mutateAsync(documentId);
    } finally {
      setPendingId(null);
    }
  };

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">知识库文档</h1>
          <p className="page-subtitle">用于问答检索的文档元数据登记与索引重建。</p>
        </div>
        <div className="page-actions">
          <button
            type="button"
            className="button button--primary"
            onClick={() => setShowCreate((v) => !v)}
            aria-expanded={showCreate}
          >
            {showCreate ? "收起表单" : "登记文档"}
          </button>
        </div>
      </header>

      {displayError ? (
        <div className="error" role="alert">{displayError}</div>
      ) : null}

      {showCreate ? (
        <div className="card stack" aria-label="登记文档表单">
          <p className="page-subtitle">{FILE_ID_HINT}</p>
          <div className="field">
            <label className="field__label" htmlFor="doc-title">标题</label>
            <input
              id="doc-title"
              name="title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="例：2026 Q2 路线图"
              autoComplete="off"
            />
          </div>
          <div className="field">
            <label className="field__label" htmlFor="doc-file-id">fileId</label>
            <input
              id="doc-file-id"
              name="fileId"
              value={fileId}
              onChange={(e) => setFileId(e.target.value)}
              placeholder="例：file_doc_abc"
              autoComplete="off"
            />
          </div>
          <div className="field">
            <label className="field__label" htmlFor="doc-type">类型</label>
            <select
              id="doc-type"
              name="documentType"
              value={documentType}
              onChange={(e) => setDocumentType(e.target.value as DocumentType)}
            >
              {DOCUMENT_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label className="field__label" htmlFor="doc-hash">SHA-256（可选）</label>
            <input
              id="doc-hash"
              name="contentHash"
              value={contentHash}
              onChange={(e) => setContentHash(e.target.value)}
              placeholder="留空表示尚未计算"
              autoComplete="off"
            />
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="button button--primary"
              onClick={() => void handleCreate()}
              disabled={create.isPending}
            >
              {create.isPending ? "提交中…" : "提交"}
            </button>
            <button
              type="button"
              className="button button--ghost"
              onClick={() => { setShowCreate(false); resetCreateForm(); setFormError(null); }}
            >
              取消
            </button>
          </div>
        </div>
      ) : null}

      {isPending ? <p className="page-subtitle" aria-live="polite">加载中…</p> : null}
      {!isPending && documents.length === 0 ? (
        <div className="empty-state">
          <strong>还没有任何文档</strong>
          <span>点击右上「登记文档」开始。</span>
        </div>
      ) : null}

      <div className="stack">
        {documents.map((doc) => (
          <article
            key={doc.documentId}
            className="card stack"
            aria-label={`document-${doc.documentId}`}
          >
            <div className="toolbar">
              <strong>{doc.title}</strong>
              <span className="pill pill--neutral">{doc.documentType}</span>
              <span className={`pill ${INDEX_TONE[doc.status] ?? "pill--neutral"}`}>
                {doc.status}
              </span>
              <span className="page-subtitle">索引: {doc.textExtractionStatus}</span>
            </div>
            <dl className="grid">
              <div>
                <dt className="page-subtitle">documentId</dt>
                <dd><code translate="no">{doc.documentId}</code></dd>
              </div>
              <div>
                <dt className="page-subtitle">fileId</dt>
                <dd><code translate="no">{doc.fileId}</code></dd>
              </div>
              <div>
                <dt className="page-subtitle">contentHash</dt>
                <dd><code translate="no">{doc.contentHash ?? "（未计算）"}</code></dd>
              </div>
              <div>
                <dt className="page-subtitle">创建时间</dt>
                <dd>{formatDate(doc.createdAt)}</dd>
              </div>
            </dl>
            <div className="toolbar">
              <button
                type="button"
                className="button"
                onClick={() => void handleReindex(doc.documentId)}
                disabled={pendingId === doc.documentId}
              >
                {pendingId === doc.documentId ? "处理中…" : "重新索引"}
              </button>
              <button
                type="button"
                className="button"
                onClick={() => openDeleteDialog(doc.documentId, doc.title)}
                disabled={pendingId === doc.documentId}
              >
                删除
              </button>
            </div>
          </article>
        ))}
      </div>

      {deleteTarget ? (
        <div className="modal-backdrop" role="presentation">
          <section
            className="modal-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="documents-delete-title"
            aria-describedby="documents-delete-description"
          >
            <div className="modal-header">
              <div>
                <h2 id="documents-delete-title" className="card-title">删除文档</h2>
                <p id="documents-delete-description" className="muted">
                  {deleteTarget.title} 的元数据和关联知识块将被清理。
                </p>
              </div>
              <button
                type="button"
                className="button button--ghost"
                onClick={closeDeleteDialog}
                disabled={pendingId === deleteTarget.documentId}
              >
                取消
              </button>
            </div>
            <div className="modal-actions" aria-live="polite">
              <button
                type="button"
                className="button"
                onClick={closeDeleteDialog}
                disabled={pendingId === deleteTarget.documentId}
              >
                取消
              </button>
              <button
                type="button"
                className="button button--danger"
                onClick={() => void handleDeleteConfirm()}
                disabled={pendingId === deleteTarget.documentId}
              >
                {pendingId === deleteTarget.documentId ? "删除中…" : "确认删除"}
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </div>
  );
}
