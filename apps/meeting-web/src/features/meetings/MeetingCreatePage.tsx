import { FormEvent, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createMeeting } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";
import { formatConsentStatus } from "@shared/utils/formatters";
import { SpeakerEnrollPanel } from "@features/speakers/SpeakerEnrollPanel";
import { useCreateSpeakerProfile, useSpeakerProfilesQuery } from "@features/speakers/queries";
import type { ApiClientError, SpeakerProfile } from "@shared/api/client";

function normalizeProfile(profile: SpeakerProfile): SpeakerProfile {
  return {
    ...profile,
    consentStatus: profile.consentStatus ?? profile.status ?? "UNKNOWN",
    revokedAt: profile.revokedAt ?? null,
  };
}

function profileName(profile: SpeakerProfile): string {
  return profile.displayName?.trim() || "未命名参会人";
}

export function MeetingCreatePage() {
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState("zh");
  const [participantSearch, setParticipantSearch] = useState("");
  const [selectedProfileIds, setSelectedProfileIds] = useState<string[]>([]);
  const [createdProfiles, setCreatedProfiles] = useState<SpeakerProfile[]>([]);
  const [showNewParticipant, setShowNewParticipant] = useState(false);
  const [newPersonId, setNewPersonId] = useState("");
  const [newDisplayName, setNewDisplayName] = useState("");
  const [newParticipantProfile, setNewParticipantProfile] = useState<SpeakerProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const navigate = useNavigate();
  const profilesQ = useSpeakerProfilesQuery();
  const createSpeaker = useCreateSpeakerProfile();

  const profiles = useMemo(() => {
    const merged = new Map<string, SpeakerProfile>();
    for (const profile of profilesQ.data?.items ?? []) {
      merged.set(profile.speakerProfileId, normalizeProfile(profile));
    }
    for (const profile of createdProfiles) {
      merged.set(profile.speakerProfileId, normalizeProfile(profile));
    }
    return Array.from(merged.values());
  }, [createdProfiles, profilesQ.data?.items]);

  const filteredProfiles = useMemo(() => {
    const keyword = participantSearch.trim().toLowerCase();
    if (!keyword) return profiles;
    return profiles.filter((profile) => profileName(profile).toLowerCase().includes(keyword));
  }, [participantSearch, profiles]);

  const selectedProfiles = selectedProfileIds
    .map((profileId) => profiles.find((profile) => profile.speakerProfileId === profileId))
    .filter((profile): profile is SpeakerProfile => Boolean(profile));

  function toggleParticipant(profileId: string) {
    setSelectedProfileIds((current) =>
      current.includes(profileId)
        ? current.filter((item) => item !== profileId)
        : [...current, profileId],
    );
  }

  function apiErrorMessage(cause: unknown, fallback: string) {
    const apiError = cause as ApiClientError;
    return apiError.code ? getUserMessage(apiError.code) : fallback;
  }

  async function handleCreateParticipant() {
    if (!newPersonId.trim() || !newDisplayName.trim()) {
      setError("请填写人员编号和显示名");
      return;
    }
    setError(null);
    try {
      const profile = normalizeProfile(await createSpeaker.mutateAsync({
        personId: newPersonId.trim(),
        displayName: newDisplayName.trim(),
      }));
      setCreatedProfiles((current) => [
        ...current.filter((item) => item.speakerProfileId !== profile.speakerProfileId),
        profile,
      ]);
      setSelectedProfileIds((current) =>
        current.includes(profile.speakerProfileId) ? current : [...current, profile.speakerProfileId],
      );
      setNewParticipantProfile(profile);
      setNewPersonId("");
      setNewDisplayName("");
    } catch (cause) {
      setError(apiErrorMessage(cause, "声纹档案创建失败"));
    }
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const participants = selectedProfiles.map((profile) => ({
        personId: profile.personId,
        displayName: profileName(profile),
        role: "participant",
      }));
      const meeting = await createMeeting({
        title: title.trim(),
        language,
        participants,
      });
      navigate(`/meetings/${meeting.meetingId}`);
    } catch (cause) {
      setError(apiErrorMessage(cause, "会议创建失败"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page page--workbench">
      <header className="page-hero page-hero--workbench">
        <div>
          <span className="page-hero__label">会议</span>
          <h1 className="page-hero__title">新建会议</h1>
          <p className="page-hero__subtitle">创建后可在详情页启动处理任务。</p>
        </div>
      </header>
      <section className="glass-panel">
        <form className="form" onSubmit={onSubmit}>
          <div className="field">
            <label htmlFor="title">会议标题</label>
            <input
              id="title"
              type="text"
              name="title"
              autoComplete="off"
              required
              value={title}
              onChange={(event) => setTitle(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="language">语言</label>
            <select
              id="language"
              name="language"
              value={language}
              onChange={(event) => setLanguage(event.target.value)}
            >
              <option value="zh">中文</option>
              <option value="en">英文</option>
            </select>
          </div>
          <div
            className="participant-picker stack"
            aria-label={profilesQ.isPending ? undefined : "参会人选择"}
            aria-busy={profilesQ.isPending}
          >
            <div className="participant-picker__header">
              <div>
                <strong>参会人</strong>
                <span>从声纹档案选择，创建会议时会带上对应人员信息。</span>
              </div>
              <button
                type="button"
                className="button button--ghost button--compact"
                onClick={() => setShowNewParticipant(true)}
              >
                新建参会人
              </button>
            </div>

            <div className="field">
              <label className="field__label" htmlFor="participant-search">查找参会人</label>
              <input
                id="participant-search"
                type="search"
                autoComplete="off"
                value={participantSearch}
                onChange={(event) => setParticipantSearch(event.target.value)}
                placeholder="按显示名查找"
              />
            </div>

            {profilesQ.isPending ? <p className="page-subtitle" aria-live="polite">正在加载声纹档案…</p> : null}
            {profilesQ.error ? (
              <div className="error" role="alert">
                {apiErrorMessage(profilesQ.error, "声纹档案加载失败")}
              </div>
            ) : null}

            {filteredProfiles.length > 0 ? (
              <div className="participant-list">
                {filteredProfiles.map((profile) => {
                  const isSelected = selectedProfileIds.includes(profile.speakerProfileId);
                  const isActive = profile.consentStatus === "ACTIVE";
                  return (
                    <label
                      className="participant-option"
                      data-selected={isSelected}
                      data-disabled={!isActive}
                      key={profile.speakerProfileId}
                    >
                      <input
                        type="checkbox"
                        checked={isSelected}
                        disabled={!isActive}
                        onChange={() => toggleParticipant(profile.speakerProfileId)}
                      />
                      <span className="participant-option__main">
                        <strong>{profileName(profile)}</strong>
                        <span>
                          {typeof profile.enrollmentCount === "number"
                            ? `${profile.enrollmentCount} 段参考音频`
                            : "声纹档案"}
                        </span>
                      </span>
                      <span className={`pill ${isActive ? "pill--success" : "pill--neutral"}`}>
                        {formatConsentStatus(profile.consentStatus)}
                      </span>
                    </label>
                  );
                })}
              </div>
            ) : !profilesQ.isPending ? (
              <div className="empty-state empty-state--compact">
                <strong>没有可选参会人</strong>
                <span>可新建参会人并补充参考音频。</span>
              </div>
            ) : null}

            <div className="participant-picker__summary">已选择 {selectedProfiles.length} 位参会人</div>

            {showNewParticipant ? (
              <section className="participant-create-panel stack" aria-label="新建参会人">
                <strong>新建参会人</strong>
                <div className="form-grid">
                  <div className="field">
                    <label className="field__label" htmlFor="new-person-id">人员编号</label>
                    <input
                      id="new-person-id"
                      name="personId"
                      autoComplete="off"
                      value={newPersonId}
                      onChange={(event) => setNewPersonId(event.target.value)}
                      placeholder="员工编号或用户名"
                    />
                  </div>
                  <div className="field">
                    <label className="field__label" htmlFor="new-display-name">显示名</label>
                    <input
                      id="new-display-name"
                      name="displayName"
                      autoComplete="off"
                      value={newDisplayName}
                      onChange={(event) => setNewDisplayName(event.target.value)}
                      placeholder="例如 Alice 张"
                    />
                  </div>
                </div>
                <div className="toolbar">
                  <button
                    type="button"
                    className="button button--primary"
                    disabled={createSpeaker.isPending}
                    onClick={() => void handleCreateParticipant()}
                  >
                    {createSpeaker.isPending ? "创建中…" : "创建声纹档案"}
                  </button>
                  <button
                    type="button"
                    className="button button--ghost"
                    onClick={() => {
                      setShowNewParticipant(false);
                      setNewParticipantProfile(null);
                      setError(null);
                    }}
                  >
                    取消
                  </button>
                </div>
                {newParticipantProfile ? (
                  <div className="participant-created-card stack">
                    <div className="control-row control-row--between">
                      <strong>声纹档案已创建</strong>
                      <span className="pill pill--success">已加入参会人</span>
                    </div>
                    <SpeakerEnrollPanel
                      profileId={newParticipantProfile.speakerProfileId}
                      onEnrollSuccess={() => profilesQ.refetch()}
                      setError={setError}
                    />
                  </div>
                ) : null}
              </section>
            ) : null}
          </div>
          {error ? (
            <div className="error" role="alert" aria-live="polite">
              {error}
            </div>
          ) : null}
          <button className="button button--primary" type="submit" disabled={submitting || !title.trim()}>
            {submitting ? "创建中…" : "创建会议"}
          </button>
        </form>
      </section>
    </main>
  );
}
