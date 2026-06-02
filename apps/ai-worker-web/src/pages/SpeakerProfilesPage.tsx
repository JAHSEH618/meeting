import { Link } from "react-router-dom";
import { useState } from "react";
import { useRevokeSpeakerProfile, useSpeakerProfilesQuery } from "@/features/speaker-profiles/queries";
import { ApiError } from "@/shared/api/client";
import type { SpeakerProfileDTO } from "@/shared/api/types";
import { formatDate } from "@/shared/utils/formatters";

const STATUS_TONE: Record<string, string> = {
  ACTIVE: "pill--success",
  REVOKED: "pill--warn",
  DELETED: "pill--danger",
};

export function SpeakerProfilesPage() {
  const profilesQuery = useSpeakerProfilesQuery();
  const revoke = useRevokeSpeakerProfile();
  const [pendingRevokeProfile, setPendingRevokeProfile] = useState<SpeakerProfileDTO | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const profiles = profilesQuery.data ?? [];
  const error = localError ?? formatOptionalError(profilesQuery.error ?? revoke.error);

  const handleConfirmRevoke = async () => {
    if (!pendingRevokeProfile) return;
    setLocalError(null);
    try {
      await revoke.mutateAsync({ profileId: pendingRevokeProfile.speakerProfileId, reason: "operator_request" });
      setPendingRevokeProfile(null);
    } catch (e) {
      setLocalError(formatError(e));
    }
  };

  return (
    <div className="stack">
      <header className="page-header">
        <div>
          <h1 className="page-title">声纹档案</h1>
          <p className="page-subtitle">Java 管理授权、档案状态和人员绑定。</p>
        </div>
        <div className="toolbar">
          <Link className="button button--primary" to="/enrollment">去录入</Link>
        </div>
      </header>

      <section className="card stack" aria-labelledby="speaker-profile-list">
        <div className="toolbar">
          <strong id="speaker-profile-list">档案列表</strong>
          <span className="pill pill--neutral">{profiles.length} 个</span>
        </div>
        <p className="page-subtitle">声纹向量已用 KMS 信封加密存储</p>

        {profilesQuery.isPending ? (
          <p className="page-subtitle" aria-live="polite">加载中…</p>
        ) : null}

        {error ? (
          <div className="banner banner--danger" role="alert">
            <strong className="banner__title">声纹档案加载或操作失败</strong>
            <span className="banner__body">{error}</span>
          </div>
        ) : null}

        {!profilesQuery.isPending && !error && profiles.length === 0 ? (
          <div className="empty-state">
            <strong>暂无声纹档案</strong>
            <span>进入「声纹录入」为人员建立档案。</span>
          </div>
        ) : null}

        {profiles.length > 0 ? (
          <table className="data-table">
            <thead>
              <tr>
                <th>姓名</th>
                <th>Person ID</th>
                <th>状态</th>
                <th className="num">录入数</th>
                <th>最近录入</th>
                <th>更新时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {profiles.map((profile) => (
                <tr key={profile.speakerProfileId}>
                  <td>{profile.displayName}</td>
                  <td translate="no">{profile.personId}</td>
                  <td>
                    <span className={`pill ${STATUS_TONE[profile.status ?? ""] ?? "pill--neutral"}`}>
                      {profile.status ?? "UNKNOWN"}
                    </span>
                  </td>
                  <td className="num">{profile.enrollmentCount ?? 0}</td>
                  <td>{formatDate(profile.lastEnrolledAt)}</td>
                  <td>{formatDate(profile.updatedAt ?? profile.createdAt)}</td>
                  <td>
                    <button
                      className="button button--secondary"
                      type="button"
                      disabled={revoke.isPending || profile.status === "REVOKED"}
                      onClick={() => setPendingRevokeProfile(profile)}
                    >
                      撤销 {profile.displayName}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </section>

      {pendingRevokeProfile ? (
        <div className="modal" role="presentation">
          <section
            className="modal__panel stack"
            role="dialog"
            aria-modal="true"
            aria-labelledby="speaker-profile-revoke-title"
          >
            <header className="page-header">
              <div>
                <h2 id="speaker-profile-revoke-title" className="page-title">撤销声纹档案</h2>
                <p className="page-subtitle">
                  撤销后该档案将不再参与后续说话人匹配。
                </p>
              </div>
            </header>
            <div className="banner banner--warn">
              <strong className="banner__title">{pendingRevokeProfile.displayName}</strong>
              <span className="banner__body" translate="no">{pendingRevokeProfile.speakerProfileId}</span>
            </div>
            <footer className="toolbar">
              <button
                className="button button--ghost"
                type="button"
                disabled={revoke.isPending}
                onClick={() => setPendingRevokeProfile(null)}
              >
                取消
              </button>
              <button
                className="button button--danger"
                type="button"
                disabled={revoke.isPending}
                onClick={() => void handleConfirmRevoke()}
              >
                确认撤销
              </button>
            </footer>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function formatOptionalError(error: unknown): string | null {
  return error ? formatError(error) : null;
}

function formatError(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.error.code}: ${error.error.message}`;
  }
  return error instanceof Error ? error.message : String(error);
}
