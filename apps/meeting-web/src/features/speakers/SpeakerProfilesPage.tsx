import { useState } from "react";
import { Link } from "react-router-dom";
import { useSpeakerProfilesQuery, useCreateSpeakerProfile } from "./queries";
import { SpeakerProfileCard } from "./SpeakerProfileCard";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

export function SpeakerProfilesPage() {
  const profilesQ = useSpeakerProfilesQuery();
  const create = useCreateSpeakerProfile();

  const [showCreate, setShowCreate] = useState(false);
  const [createPersonId, setCreatePersonId] = useState("");
  const [createDisplayName, setCreateDisplayName] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [overrideError, setOverrideError] = useState<string | null>(null);

  const profiles = profilesQ.data?.items ?? [];
  const apiErr = (profilesQ.error ?? create.error) as ApiClientError | null;
  const apiErrMsg = apiErr
    ? (apiErr.code ? getUserMessage(apiErr.code) : "操作失败")
    : null;
  const displayError = overrideError ?? formError ?? apiErrMsg;

  const handleCreate = async () => {
    if (!createPersonId.trim() || !createDisplayName.trim()) {
      setFormError("请填写 personId 和显示名");
      return;
    }
    setFormError(null);
    setOverrideError(null);
    try {
      await create.mutateAsync({
        personId: createPersonId.trim(),
        displayName: createDisplayName.trim(),
      });
      setShowCreate(false);
      setCreatePersonId("");
      setCreateDisplayName("");
    } catch {
      /* surfaced via apiErrMsg */
    }
  };

  return (
    <main className="page">
      <header className="page-header">
        <div>
          <h1 className="page-title">声纹档案</h1>
          <p className="page-subtitle">{profiles.length} 个档案 · 声纹向量已用 KMS 信封加密存储</p>
        </div>
        <div className="page-actions">
          <Link className="button" to="/meetings">会议</Link>
          <button
            type="button"
            className="button button--primary"
            onClick={() => setShowCreate(true)}
          >
            新建档案
          </button>
        </div>
      </header>

      {profilesQ.isPending ? <p className="page-subtitle" aria-live="polite">加载中…</p> : null}
      {displayError ? <div className="error" role="alert">{displayError}</div> : null}

      {showCreate ? (
        <section className="card stack" aria-label="新建档案表单">
          <strong>新建声纹档案</strong>
          <div className="field">
            <label className="field__label" htmlFor="speaker-person-id">Person ID</label>
            <input
              id="speaker-person-id"
              name="personId"
              autoComplete="off"
              value={createPersonId}
              onChange={(e) => setCreatePersonId(e.target.value)}
              placeholder="alice"
            />
          </div>
          <div className="field">
            <label className="field__label" htmlFor="speaker-display-name">显示名</label>
            <input
              id="speaker-display-name"
              name="displayName"
              autoComplete="off"
              value={createDisplayName}
              onChange={(e) => setCreateDisplayName(e.target.value)}
              placeholder="例如 Alice 张"
            />
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="button button--primary"
              onClick={() => void handleCreate()}
              disabled={create.isPending}
            >
              {create.isPending ? "创建中…" : "创建"}
            </button>
            <button
              type="button"
              className="button button--ghost"
              onClick={() => {
                setShowCreate(false);
                setFormError(null);
              }}
            >
              取消
            </button>
          </div>
        </section>
      ) : null}

      {profiles.length === 0 && !profilesQ.isPending ? (
        <div className="empty-state">
          <strong>还没有声纹档案</strong>
          <span>点击右上「新建档案」开始。</span>
        </div>
      ) : null}

      <div className="stack">
        {profiles.map((profile) => (
          <SpeakerProfileCard
            key={profile.speakerProfileId}
            profile={profile}
            setError={setOverrideError}
          />
        ))}
      </div>
    </main>
  );
}
