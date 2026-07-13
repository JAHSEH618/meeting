import { useCallback, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  abortAudioUpload,
  abortFileUpload,
  attachMeetingDocument,
  completeAudioUpload,
  completeFileUpload,
  createAudioUploadPart,
  createDocument,
  createFileUploadPart,
  createMeeting,
  initAudioUpload,
  initFileUpload,
  searchPersons,
  searchDocuments,
  updateMeetingGlossary,
} from "@/shared/api/endpoints";
import type {
  DocumentSummaryDTO,
  DocumentType,
  FileUploadCompleteResponseDTO,
  GlossaryTermDTO,
  PersonDTO,
} from "@/shared/api/types";
import { PersonCreateModal } from "@/shared/components/PersonCreateModal";
import { useDebouncedSearch } from "@/shared/hooks/useDebouncedSearch";
import { MultipartUploader, MultipartUploadError } from "@/shared/upload/MultipartUploader";
import { formatError } from "@/shared/utils/format-error";

const MAX_GLOSSARY_TERMS = 200;
const MAX_TERM_LENGTH = 64;
const DEFAULT_PARTICIPANT_ROLE = "PARTICIPANT";

interface SelectedDocument {
  documentId: string;
  title: string;
  source: "existing" | "uploaded";
}

interface UploadingDocument {
  id: string;
  name: string;
  progress: number;
  status: "uploading" | "done" | "failed";
  uploader: MultipartUploader<FileUploadCompleteResponseDTO>;
  error?: string;
}

interface SelectedParticipant {
  personId: string;
  displayName: string;
  role: string;
}

export function NewMeetingPage() {
  const navigate = useNavigate();
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState("zh");
  const [terms, setTerms] = useState<GlossaryTermDTO[]>([]);
  const [termDraft, setTermDraft] = useState("");
  const [audioFile, setAudioFile] = useState<File | null>(null);
  const [audioProgress, setAudioProgress] = useState(0);
  const [selectedParticipants, setSelectedParticipants] = useState<SelectedParticipant[]>([]);
  const [personModalOpen, setPersonModalOpen] = useState(false);
  const [selectedDocuments, setSelectedDocuments] = useState<SelectedDocument[]>([]);
  const [uploadingDocuments, setUploadingDocuments] = useState<UploadingDocument[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [createdMeetingId, setCreatedMeetingId] = useState<string | null>(null);
  const activeAudioUploader = useRef<MultipartUploader<unknown> | null>(null);

  const personFetcher = useCallback((q: string, signal: AbortSignal) => searchPersons(q, { signal }), []);
  const personSearch = useDebouncedSearch<PersonDTO>(personFetcher);
  const documentFetcher = useCallback((q: string, signal: AbortSignal) => searchDocuments(q, { signal }), []);
  const documentSearch = useDebouncedSearch<DocumentSummaryDTO>(documentFetcher);
  const persons = personSearch.results ?? [];
  const hasDocumentUploadInFlight = uploadingDocuments.some((document) => document.status === "uploading");
  const canStart = title.trim().length > 0 && !!audioFile && !busy && !hasDocumentUploadInFlight;

  const addTerm = () => {
    const term = termDraft.trim();
    if (!term || term.length > MAX_TERM_LENGTH || terms.length >= MAX_GLOSSARY_TERMS) return;
    if (terms.some((existing) => existing.term.toLowerCase() === term.toLowerCase())) return;
    setTerms((current) => [...current, { term, aliases: [] }]);
    setTermDraft("");
  };

  const addExistingDocument = (document: DocumentSummaryDTO) => {
    setSelectedDocuments((current) =>
      current.some((item) => item.documentId === document.documentId)
        ? current
        : [...current, { documentId: document.documentId, title: document.title, source: "existing" }],
    );
  };

  const addParticipant = (person: PersonDTO) => {
    if (!person.personId) return;
    setSelectedParticipants((current) =>
      current.some((item) => item.personId === person.personId)
        ? current
        : [
          ...current,
          {
            personId: person.personId,
            displayName: person.displayName,
            role: DEFAULT_PARTICIPANT_ROLE,
          },
        ],
    );
  };

  const handleDocumentFile = async (file: File) => {
    const id = `${file.name}-${file.lastModified}-${file.size}`;
    const uploader = new MultipartUploader<FileUploadCompleteResponseDTO>({
      file,
      init: initFileUpload,
      createPart: createFileUploadPart,
      complete: completeFileUpload,
      abort: abortFileUpload,
      onProgress: (progress) => {
        setUploadingDocuments((current) =>
          current.map((item) => item.id === id ? { ...item, progress } : item),
        );
      },
    });
    setUploadingDocuments((current) => [...current, { id, name: file.name, progress: 0, status: "uploading", uploader }]);
    setError(null);
    try {
      const completed = await uploader.upload();
      const document = await createDocument({
        title: file.name,
        fileId: completed.fileId,
        documentType: deriveDocumentType(file.name, file.type),
        contentHash: completed.sha256,
      });
      setUploadingDocuments((current) =>
        current.map((item) => item.id === id ? { ...item, progress: 1, status: "done" } : item),
      );
      setSelectedDocuments((current) => [
        ...current,
        { documentId: document.documentId, title: document.title, source: "uploaded" },
      ]);
    } catch (e) {
      if (isUploadAborted(e)) return;
      setUploadingDocuments((current) =>
        current.map((item) =>
          item.id === id ? { ...item, status: "failed", error: formatError(e) } : item,
        ),
      );
      setError(formatError(e));
    }
  };

  const startProcessing = async () => {
    if (!canStart || !audioFile) return;
    setBusy(true);
    setError(null);
    // REMOVED: setCreatedMeetingId(null);  // Don't reset - preserve for resumption
    try {
      // Create meeting only if not already created
      let meetingId = createdMeetingId;
      if (!meetingId) {
        const meeting = await createMeeting({
          title: title.trim(),
          language,
          participants: selectedParticipants.map((participant) => ({
            personId: participant.personId,
            displayName: participant.displayName,
            role: participant.role,
          })),
        });
        meetingId = meeting.meetingId;
        setCreatedMeetingId(meetingId);
      }

      // Resume from here - these steps are idempotent
      if (terms.length > 0) await updateMeetingGlossary(meetingId, terms);
      for (const document of selectedDocuments) {
        await attachMeetingDocument(meetingId, { documentId: document.documentId, role: "REFERENCE" });
      }
      const audioUploader = new MultipartUploader({
        file: audioFile,
        init: (req) => initAudioUpload(meetingId, req),
        createPart: (uploadId, req) => createAudioUploadPart(meetingId, uploadId, req),
        complete: (uploadId, req) => completeAudioUpload(meetingId, uploadId, req),
        abort: (uploadId) => abortAudioUpload(meetingId, uploadId),
        onProgress: setAudioProgress,
      });
      activeAudioUploader.current = audioUploader;
      await audioUploader.upload();
      navigate(`/meetings/${meetingId}`);
    } catch (e) {
      setError(formatError(e));
    } finally {
      setBusy(false);
      activeAudioUploader.current = null;
    }
  };

  const cancelDocumentUpload = (item: UploadingDocument) => {
    item.uploader.abort();
    setUploadingDocuments((current) => current.filter((candidate) => candidate.id !== item.id));
  };

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">新建会议</h1>
          <p className="page-subtitle">一次性收集标题、术语、参考文档和音频。</p>
        </div>
      </header>

      <section className="card stack" aria-labelledby="new-meeting-meta">
        <h2 id="new-meeting-meta">基础信息</h2>
        <div className="grid grid--two">
          <div className="field">
            <label className="field__label" htmlFor="meeting-title">标题</label>
            <input
              id="meeting-title"
              name="title"
              className="input"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              autoComplete="off"
            />
          </div>
          <div className="field">
            <label className="field__label" htmlFor="meeting-language">语言</label>
            <select
              id="meeting-language"
              name="language"
              className="select"
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
            >
              <option value="zh">中文</option>
              <option value="en">English</option>
            </select>
          </div>
        </div>
      </section>

      <section className="card stack" aria-labelledby="new-meeting-glossary">
        <h2 id="new-meeting-glossary">术语</h2>
        <div className="row">
          <label className="visually-hidden" htmlFor="term-draft">术语</label>
          <input
            id="term-draft"
            name="glossaryTerm"
            className="input input--inline"
            value={termDraft}
            maxLength={MAX_TERM_LENGTH}
            onChange={(event) => setTermDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addTerm();
              }
            }}
            placeholder="按 Enter 添加术语…"
            autoComplete="off"
          />
          <button className="button button--secondary" type="button" disabled={!termDraft.trim()} onClick={addTerm}>+ 添加</button>
        </div>
        <div className="toolbar" aria-live="polite">
          {terms.map((term) => (
            <span key={term.term} className="chip">
              {term.term}
              <button
                className="chip__remove"
                type="button"
                aria-label={`删除 ${term.term}`}
                onClick={() => setTerms((current) => current.filter((item) => item.term !== term.term))}
              >
                x
              </button>
            </span>
          ))}
        </div>
      </section>

      <section className="card stack" aria-labelledby="new-meeting-participants">
        <h2 id="new-meeting-participants">参会人</h2>
        <div className="field">
          <label className="field__label" htmlFor="person-search">搜索人员</label>
          <input
            id="person-search"
            name="personSearch"
            className="input"
            placeholder="按姓名 / 邮箱搜索…"
            onChange={(event) => personSearch.search(event.target.value)}
            autoComplete="off"
          />
        </div>
        {personSearch.loading ? <p className="page-subtitle" aria-live="polite">搜索中…</p> : null}
        {persons.length ? (
          <div className="stack">
            {persons.map((person) => {
              const alreadyAdded = selectedParticipants.some((participant) => participant.personId === person.personId);
              return (
                <div key={person.personId} className="toolbar">
                  <span>{person.displayName}</span>
                  {person.email ? <span className="page-subtitle">{person.email}</span> : null}
                  <button
                    className="button button--secondary"
                    type="button"
                    aria-label={`${alreadyAdded ? "已添加" : "添加"} ${person.displayName}`}
                    disabled={alreadyAdded}
                    onClick={() => addParticipant(person)}
                  >
                    {alreadyAdded ? "已添加" : "添加"}
                  </button>
                </div>
              );
            })}
          </div>
        ) : null}
        <div className="toolbar">
          <button className="button button--secondary" type="button" onClick={() => setPersonModalOpen(true)}>
            + 新建人员
          </button>
          {selectedParticipants.map((participant) => (
            <span key={participant.personId} className="chip">
              {participant.displayName}
              <button
                className="chip__remove"
                type="button"
                aria-label={`移除 ${participant.displayName}`}
                onClick={() => setSelectedParticipants((current) =>
                  current.filter((item) => item.personId !== participant.personId),
                )}
              >
                x
              </button>
            </span>
          ))}
        </div>
        <PersonCreateModal
          open={personModalOpen}
          onClose={() => setPersonModalOpen(false)}
          onCreated={(person) => {
            addParticipant(person);
            setPersonModalOpen(false);
          }}
        />
      </section>

      <section className="card stack" aria-labelledby="new-meeting-documents">
        <h2 id="new-meeting-documents">参考文档</h2>
        <div className="field">
          <label className="field__label" htmlFor="document-search">搜索已有文档</label>
          <input
            id="document-search"
            name="documentSearch"
            className="input"
            placeholder="输入文档标题…"
            onChange={(event) => documentSearch.search(event.target.value)}
            autoComplete="off"
          />
        </div>
        {documentSearch.results?.length ? (
          <div className="stack">
            {documentSearch.results.map((document) => (
              <div key={document.documentId} className="toolbar">
                <span>{document.title}</span>
                <button className="button button--secondary" type="button" onClick={() => addExistingDocument(document)}>
                  关联
                </button>
              </div>
            ))}
          </div>
        ) : null}

        <label htmlFor="reference-document-upload" className="upload-dropzone">
          <input
            id="reference-document-upload"
            name="referenceDocument"
            aria-label="参考文档上传"
            className="upload-dropzone__input"
            type="file"
            accept=".pdf,.docx,.pptx,.txt,.md"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void handleDocumentFile(file);
              event.target.value = "";
            }}
          />
          <span className="upload-dropzone__label">选择或拖入新参考文档</span>
        </label>

        {uploadingDocuments.length > 0 ? (
          <div className="stack">
            {uploadingDocuments.map((item) => (
              <div key={item.id} className="upload-row">
                <span>{item.name}</span>
                <progress value={item.progress} max={1} aria-label={`${item.name} 上传进度`} />
                <span className={`pill ${item.status === "failed" ? "pill--danger" : item.status === "done" ? "pill--success" : "pill--info"}`}>
                  {item.status === "uploading" ? "上传中" : item.status === "done" ? "已上传" : "失败"}
                </span>
                {item.status === "uploading" ? (
                  <button className="button button--ghost" type="button" onClick={() => cancelDocumentUpload(item)}>取消</button>
                ) : null}
                {item.error ? <span className="error">{item.error}</span> : null}
              </div>
            ))}
          </div>
        ) : null}

        <div className="toolbar">
          {selectedDocuments.map((document) => (
            <span key={document.documentId} className="chip">
              {document.title}
              <button
                className="chip__remove"
                type="button"
                aria-label={`移除 ${document.title}`}
                onClick={() => setSelectedDocuments((current) =>
                  current.filter((item) => item.documentId !== document.documentId),
                )}
              >
                x
              </button>
            </span>
          ))}
        </div>
      </section>

      <section className="card stack" aria-labelledby="new-meeting-audio">
        <h2 id="new-meeting-audio">音频文件</h2>
        <label htmlFor="meeting-audio-file" className="upload-dropzone">
          <input
            id="meeting-audio-file"
            name="meetingAudio"
            aria-label="音频文件"
            className="upload-dropzone__input"
            type="file"
            accept="audio/*"
            onChange={(event) => setAudioFile(event.target.files?.[0] ?? null)}
          />
          <span className="upload-dropzone__label">{audioFile ? audioFile.name : "选择音频文件"}</span>
        </label>
        {audioProgress > 0 ? <progress value={audioProgress} max={1} aria-label="音频上传进度" /> : null}
      </section>

      {error || personSearch.error ? (
        <div className="banner banner--danger" role="alert">
          <span className="banner__body">{error ?? formatError(personSearch.error)}</span>
          {createdMeetingId ? (
            <Link className="button button--secondary" to={`/meetings/${createdMeetingId}`}>
              查看已创建会议
            </Link>
          ) : null}
        </div>
      ) : null}

      <footer className="toolbar">
        <button className="button button--primary" type="button" data-testid="start-processing" disabled={!canStart} onClick={() => void startProcessing()}>
          {busy ? "处理中…" : "开始处理"}
        </button>
        {busy && activeAudioUploader.current ? (
          <button className="button button--secondary" type="button" onClick={() => activeAudioUploader.current?.abort()}>
            取消上传
          </button>
        ) : null}
      </footer>
    </div>
  );
}

function deriveDocumentType(fileName: string, mime: string): DocumentType {
  const lower = fileName.toLowerCase();
  if (mime === "application/pdf" || lower.endsWith(".pdf")) return "PDF";
  if (mime === "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || lower.endsWith(".docx")) return "DOCX";
  if (mime === "application/vnd.openxmlformats-officedocument.presentationml.presentation" || lower.endsWith(".pptx")) return "PPTX";
  if (mime === "text/markdown" || lower.endsWith(".md")) return "MD";
  if (mime === "text/plain" || lower.endsWith(".txt")) return "TXT";
  return "OTHER";
}


function isUploadAborted(e: unknown): boolean {
  return e instanceof MultipartUploadError && e.code === "UPLOAD_ABORTED";
}
