import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/shared/api/client";
import { createPerson } from "@/shared/api/endpoints";
import type { CreatePersonRequest, PersonDTO } from "@/shared/api/types";

interface PersonCreateModalProps {
  open: boolean;
  onClose: () => void;
  onCreated: (person: PersonDTO) => void;
  createFn?: (req: CreatePersonRequest) => Promise<PersonDTO>;
}

export function PersonCreateModal({ open, onClose, onCreated, createFn = createPerson }: PersonCreateModalProps) {
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [duplicates, setDuplicates] = useState<PersonDTO[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reset = useCallback(() => {
    setDisplayName("");
    setEmail("");
    setDuplicates([]);
    setBusy(false);
    setError(null);
  }, []);

  useEffect(() => {
    if (!open) reset();
  }, [open, reset]);

  if (!open) return null;

  const finish = (person: PersonDTO) => {
    reset();
    onCreated(person);
    onClose();
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  const submit = async (forceCreate = false) => {
    const trimmedName = displayName.trim();
    if (!trimmedName) return;
    setBusy(true);
    setError(null);
    try {
      const req: CreatePersonRequest = {
        displayName: trimmedName,
        ...(email.trim() ? { email: email.trim() } : {}),
        ...(forceCreate ? { forceCreate: true } : {}),
      };
      const person = await createFn(req);
      finish(person);
    } catch (e) {
      const duplicateMatches = getDuplicateMatches(e);
      if (duplicateMatches) {
        setDuplicates(duplicateMatches);
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal" role="presentation">
      <section className="modal__panel stack" role="dialog" aria-modal="true" aria-labelledby="person-create-title">
        <header className="page-header">
          <div>
            <h2 id="person-create-title" className="page-title">新建人员</h2>
            <p className="page-subtitle">创建后会自动选中该人员。</p>
          </div>
        </header>

        <div className="field">
          <label className="field__label" htmlFor="person-create-name">姓名</label>
          <input
            id="person-create-name"
            name="displayName"
            className="input"
            value={displayName}
            onChange={(event) => {
              setDisplayName(event.target.value);
              setDuplicates([]);
            }}
            autoComplete="name"
          />
        </div>

        <div className="field">
          <label className="field__label" htmlFor="person-create-email">邮箱</label>
          <input
            id="person-create-email"
            name="email"
            type="email"
            className="input"
            value={email}
            onChange={(event) => {
              setEmail(event.target.value);
              setDuplicates([]);
            }}
            autoComplete="email"
            spellCheck={false}
          />
        </div>

        {duplicates.length > 0 ? (
          <div className="banner banner--warn">
            <strong className="banner__title">已存在 {duplicates.length} 个同名人员</strong>
            <div className="stack">
              {duplicates.map((person) => (
                <div className="toolbar" key={person.personId}>
                  <span>{person.displayName}</span>
                  {person.email ? <span className="page-subtitle">{person.email}</span> : null}
                  <button className="button button--secondary" type="button" onClick={() => finish(person)}>
                    使用已有
                  </button>
                </div>
              ))}
            </div>
            <button className="button button--primary" type="button" disabled={busy} onClick={() => void submit(true)}>
              仍创建新的
            </button>
          </div>
        ) : null}

        {error ? <div className="error" role="alert">{error}</div> : null}

        <footer className="toolbar">
          <button className="button button--ghost" type="button" onClick={handleClose}>取消</button>
          <button
            className="button button--primary"
            type="button"
            disabled={busy || !displayName.trim() || duplicates.length > 0}
            onClick={() => void submit(false)}
          >
            创建
          </button>
        </footer>
      </section>
    </div>
  );
}

function getDuplicateMatches(error: unknown): PersonDTO[] | null {
  if (error instanceof ApiError && error.error.code === "PERSON_DUPLICATE") {
    const matches = error.error.details?.matches;
    return Array.isArray(matches) ? matches as PersonDTO[] : [];
  }
  const maybe = error as { code?: string; details?: { matches?: PersonDTO[] } };
  if (maybe?.code === "PERSON_DUPLICATE") return maybe.details?.matches ?? [];
  return null;
}
