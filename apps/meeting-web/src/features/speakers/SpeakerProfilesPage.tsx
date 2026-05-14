import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  createSpeakerEnrollment,
  createSpeakerProfile,
  deleteSpeakerProfile,
  listSpeakerEnrollments,
  listSpeakerProfiles,
  revokeSpeakerProfile,
  type SpeakerEnrollment,
  type SpeakerProfile,
} from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const ENROLLMENT_HINT = "参考音频文件 ID 由音频上传流程提供，请先上传一段干净的 30-90 秒样本。";

export function SpeakerProfilesPage() {
  const [profiles, setProfiles] = useState<SpeakerProfile[]>([]);
  const [enrollmentsByProfile, setEnrollmentsByProfile] = useState<Record<string, SpeakerEnrollment[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingProfileId, setPendingProfileId] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createPersonId, setCreatePersonId] = useState("");
  const [createDisplayName, setCreateDisplayName] = useState("");
  const [enrollmentInputs, setEnrollmentInputs] = useState<Record<string, string>>({});

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const profilesResp = await listSpeakerProfiles();
      setProfiles(profilesResp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  const loadEnrollments = async (profileId: string) => {
    try {
      const resp = await listSpeakerEnrollments(profileId);
      setEnrollmentsByProfile((current) => ({ ...current, [profileId]: resp.items }));
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载授权记录失败");
    }
  };

  const handleCreate = async () => {
    if (!createPersonId.trim() || !createDisplayName.trim()) {
      setError("请填写 personId 和显示名");
      return;
    }
    setError(null);
    try {
      await createSpeakerProfile({
        personId: createPersonId.trim(),
        displayName: createDisplayName.trim(),
      });
      setShowCreate(false);
      setCreatePersonId("");
      setCreateDisplayName("");
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "创建失败");
    }
  };

  const handleRevoke = async (profileId: string) => {
    setPendingProfileId(profileId);
    try {
      await revokeSpeakerProfile(profileId);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "撤销失败");
    } finally {
      setPendingProfileId(null);
    }
  };

  const handleDelete = async (profileId: string) => {
    setPendingProfileId(profileId);
    try {
      await deleteSpeakerProfile(profileId);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "删除失败");
    } finally {
      setPendingProfileId(null);
    }
  };

  const handleAddEnrollment = async (profileId: string) => {
    const audioFileId = (enrollmentInputs[profileId] ?? "").trim();
    if (!audioFileId) {
      setError("请填写参考音频文件 ID");
      return;
    }
    setPendingProfileId(profileId);
    try {
      await createSpeakerEnrollment(profileId, audioFileId);
      setEnrollmentInputs((current) => ({ ...current, [profileId]: "" }));
      await loadEnrollments(profileId);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "添加参考音频失败");
    } finally {
      setPendingProfileId(null);
    }
  };

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">声纹档案</h1>
          <p className="muted">{profiles.length} 个档案</p>
        </div>
        <div className="toolbar">
          <Link className="button" to="/meetings">会议</Link>
          <button type="button" className="button primary" onClick={() => setShowCreate(true)}>新建档案</button>
        </div>
      </div>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

      {showCreate ? (
        <section className="card stack" aria-label="新建档案表单">
          <strong>新建声纹档案</strong>
          <label className="stack">
            <span>Person ID</span>
            <input value={createPersonId} onChange={(e) => setCreatePersonId(e.target.value)} placeholder="alice" />
          </label>
          <label className="stack">
            <span>显示名</span>
            <input value={createDisplayName} onChange={(e) => setCreateDisplayName(e.target.value)} placeholder="例如 Alice 张" />
          </label>
          <div className="toolbar">
            <button type="button" className="button primary" onClick={() => void handleCreate()}>创建</button>
            <button type="button" className="button" onClick={() => setShowCreate(false)}>取消</button>
          </div>
        </section>
      ) : null}

      {profiles.map((profile) => {
        const isActive = profile.consentStatus === "ACTIVE";
        const enrollments = enrollmentsByProfile[profile.speakerProfileId];
        return (
          <section
            className="card stack"
            key={profile.speakerProfileId}
            data-profile-id={profile.speakerProfileId}
          >
            <div className="toolbar">
              <strong>{profile.displayName ?? profile.personId}</strong>
              <span className="badge" data-consent={profile.consentStatus}>{profile.consentStatus}</span>
              <span className="muted">{profile.personId}</span>
            </div>

            <details>
              <summary>参考音频 {enrollments ? `(${enrollments.length})` : ""}</summary>
              {enrollments == null ? (
                <button type="button" className="button" onClick={() => void loadEnrollments(profile.speakerProfileId)}>
                  加载参考音频
                </button>
              ) : (
                <div className="stack">
                  {enrollments.length === 0 ? <p className="muted">暂无参考音频</p> : null}
                  {enrollments.map((enrollment) => (
                    <article className="stack" key={enrollment.enrollmentId}>
                      <div className="toolbar">
                        <span>{enrollment.sourceAudioFileId}</span>
                        <span className="badge">{enrollment.enrollmentStatus}</span>
                        {typeof enrollment.qualityScore === "number" ? (
                          <span className="muted">质量 {Math.round(enrollment.qualityScore * 100)}%</span>
                        ) : null}
                      </div>
                    </article>
                  ))}
                  {isActive ? (
                    <div className="stack">
                      <span className="muted">{ENROLLMENT_HINT}</span>
                      <div className="toolbar">
                        <input
                          value={enrollmentInputs[profile.speakerProfileId] ?? ""}
                          onChange={(e) =>
                            setEnrollmentInputs((current) => ({
                              ...current,
                              [profile.speakerProfileId]: e.target.value,
                            }))
                          }
                          placeholder="参考音频文件 ID"
                          aria-label={`参考音频文件 ID for ${profile.speakerProfileId}`}
                        />
                        <button
                          type="button"
                          className="button primary"
                          disabled={pendingProfileId === profile.speakerProfileId}
                          onClick={() => void handleAddEnrollment(profile.speakerProfileId)}
                        >
                          添加参考音频
                        </button>
                      </div>
                    </div>
                  ) : null}
                </div>
              )}
            </details>

            <div className="toolbar">
              {isActive ? (
                <button
                  type="button"
                  className="button"
                  disabled={pendingProfileId === profile.speakerProfileId}
                  onClick={() => void handleRevoke(profile.speakerProfileId)}
                >
                  撤销授权
                </button>
              ) : null}
              <button
                type="button"
                className="button"
                disabled={pendingProfileId === profile.speakerProfileId}
                onClick={() => void handleDelete(profile.speakerProfileId)}
              >
                删除档案
              </button>
            </div>
          </section>
        );
      })}
    </main>
  );
}
