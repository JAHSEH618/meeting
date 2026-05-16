import { useCallback, useEffect, useState } from "react";
import {
  createDocument,
  deleteDocument,
  listDocuments,
  reindexDocument,
  type ApiClientError,
} from "@shared/api/client";
import type { Document, SecurityLevel } from "@shared/api/types";
import { getUserMessage } from "@shared/utils/error-mapper";

const SECURITY_LEVELS: SecurityLevel[] = ["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"];

const FILE_ID_HINT =
  "fileId 指向已上传到对象存储的源文件。当前阶段文件上传由后台管道完成；在此处仅登记元数据并触发解析 / 索引。";

const DOCUMENT_TYPES = ["PDF", "DOCX", "MARKDOWN", "TXT"] as const;
type DocumentType = (typeof DOCUMENT_TYPES)[number];
const DEFAULT_DOCUMENT_TYPE: DocumentType = "PDF";

export function DocumentsPage() {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingId, setPendingId] = useState<string | null>(null);

  const [showCreate, setShowCreate] = useState(false);
  const [title, setTitle] = useState("");
  const [fileId, setFileId] = useState("");
  const [documentType, setDocumentType] = useState<DocumentType>(DEFAULT_DOCUMENT_TYPE);
  const [securityLevel, setSecurityLevel] = useState<SecurityLevel>("INTERNAL");
  const [contentHash, setContentHash] = useState("");

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const resp = await listDocuments();
      // Sort newest first so freshly registered docs surface at the top.
      const sorted = [...resp.items].sort(
        (a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""),
      );
      setDocuments(sorted);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载文档列表失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const resetCreateForm = () => {
    setTitle("");
    setFileId("");
    setDocumentType(DEFAULT_DOCUMENT_TYPE);
    setSecurityLevel("INTERNAL");
    setContentHash("");
  };

  const handleCreate = async () => {
    if (!title.trim() || !fileId.trim()) {
      setError("请填写文档标题和 fileId");
      return;
    }
    setError(null);
    try {
      await createDocument({
        title: title.trim(),
        fileId: fileId.trim(),
        documentType,
        securityLevel,
        contentHash: contentHash.trim() || null,
      });
      setShowCreate(false);
      resetCreateForm();
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "登记文档失败");
    }
  };

  const handleDelete = async (documentId: string, title: string) => {
    if (!window.confirm(`确认删除文档「${title}」？关联的知识块也会被清理。`)) {
      return;
    }
    setPendingId(documentId);
    try {
      await deleteDocument(documentId);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "删除失败");
    } finally {
      setPendingId(null);
    }
  };

  const handleReindex = async (documentId: string) => {
    setPendingId(documentId);
    setError(null);
    try {
      await reindexDocument(documentId);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "重新索引失败");
    } finally {
      setPendingId(null);
    }
  };

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">知识库文档</h1>
          <p className="muted">用于 RAG 检索的文档元数据登记 + 索引重建。</p>
        </div>
        <div className="toolbar">
          <button
            type="button"
            className="button primary"
            onClick={() => setShowCreate((v) => !v)}
            aria-expanded={showCreate}
          >
            {showCreate ? "收起表单" : "登记文档"}
          </button>
        </div>
      </div>

      {error ? (
        <div className="error" role="alert">
          {error}
        </div>
      ) : null}

      {showCreate ? (
        <div className="card stack" aria-label="登记文档表单">
          <p className="muted">{FILE_ID_HINT}</p>
          <label className="stack">
            <span>标题</span>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="例：2026 Q2 路线图"
            />
          </label>
          <label className="stack">
            <span>fileId</span>
            <input
              value={fileId}
              onChange={(e) => setFileId(e.target.value)}
              placeholder="例：file_doc_abc"
            />
          </label>
          <label className="stack">
            <span>类型</span>
            <select value={documentType} onChange={(e) => setDocumentType(e.target.value as DocumentType)}>
              {DOCUMENT_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
          <label className="stack">
            <span>密级</span>
            <select
              value={securityLevel}
              onChange={(e) => setSecurityLevel(e.target.value as SecurityLevel)}
            >
              {SECURITY_LEVELS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <label className="stack">
            <span>SHA-256（可选）</span>
            <input
              value={contentHash}
              onChange={(e) => setContentHash(e.target.value)}
              placeholder="留空表示尚未计算"
            />
          </label>
          <div className="toolbar">
            <button type="button" className="button primary" onClick={() => void handleCreate()}>
              提交
            </button>
            <button
              type="button"
              className="button"
              onClick={() => {
                setShowCreate(false);
                resetCreateForm();
              }}
            >
              取消
            </button>
          </div>
        </div>
      ) : null}

      {loading ? <p className="muted">加载中</p> : null}

      {!loading && documents.length === 0 ? (
        <p className="muted">还没有任何文档。点击右上角“登记文档”开始。</p>
      ) : null}

      <div className="stack">
        {documents.map((doc) => (
          <article key={doc.documentId} className="card stack" aria-label={`document-${doc.documentId}`}>
            <div className="toolbar">
              <strong>{doc.title}</strong>
              <span className="badge">{doc.documentType}</span>
              <span className="badge">{doc.securityLevel}</span>
              <span className="badge">{doc.status}</span>
              <span className="muted">索引: {doc.textExtractionStatus}</span>
            </div>
            <dl className="grid">
              <div>
                <dt className="muted">documentId</dt>
                <dd>
                  <code>{doc.documentId}</code>
                </dd>
              </div>
              <div>
                <dt className="muted">fileId</dt>
                <dd>
                  <code>{doc.fileId}</code>
                </dd>
              </div>
              <div>
                <dt className="muted">contentHash</dt>
                <dd>
                  <code>{doc.contentHash ?? "（未计算）"}</code>
                </dd>
              </div>
              <div>
                <dt className="muted">创建时间</dt>
                <dd>{doc.createdAt}</dd>
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
                onClick={() => void handleDelete(doc.documentId, doc.title)}
                disabled={pendingId === doc.documentId}
              >
                删除
              </button>
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}
