import { useState } from "react";
import { Link } from "react-router-dom";
import { useSpeakerProfilesQuery, useCreateSpeakerProfile } from "./queries";
import { SpeakerProfileCard } from "./SpeakerProfileCard";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const PERSON_ID_RULE_TEXT = "2 到 64 位，支持中文、英文、数字、点、下划线和短横线，不允许空格。";
const PERSON_ID_ERROR_TEXT = "人员编号格式不正确：2 到 64 位，只能包含中文、英文、数字、点、下划线和短横线";
const PERSON_ID_PATTERN = /^[A-Za-z0-9._\-\u4e00-\u9fff]{2,64}$/;

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
    const personId = createPersonId.trim();
    const displayName = createDisplayName.trim();
    if (!personId || !displayName) {
      setFormError("请填写人员编号和显示名");
      return;
    }
    if (!PERSON_ID_PATTERN.test(personId)) {
      setFormError(PERSON_ID_ERROR_TEXT);
      return;
    }
    setFormError(null);
    setOverrideError(null);
    try {
      await create.mutateAsync({
        personId,
        displayName,
      });
      setShowCreate(false);
      setCreatePersonId("");
      setCreateDisplayName("");
    } catch {
      /* surfaced via apiErrMsg */
    }
  };

  return (
    <main className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">声纹档案</span>
          <h1 className="page-hero__title">声纹档案</h1>
          <p className="page-hero__subtitle">{profiles.length} 个档案 · 声纹向量已加密存储</p>
        </div>
        <div className="page-hero__actions">
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
        <section className="glass-panel stack" aria-label="新建档案表单">
          <strong>新建声纹档案</strong>
          <div className="field">
            <label className="field__label" htmlFor="speaker-person-id">人员编号</label>
            <input
              id="speaker-person-id"
              name="personId"
              autoComplete="off"
              maxLength={64}
              aria-describedby="speaker-person-id-rule"
              value={createPersonId}
              onChange={(e) => setCreatePersonId(e.target.value)}
              placeholder="例：EMP-001 / zhangsan / 张三01"
            />
            <p className="field__hint" id="speaker-person-id-rule">
              {PERSON_ID_RULE_TEXT}
            </p>
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
                setCreatePersonId("");
                setCreateDisplayName("");
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
