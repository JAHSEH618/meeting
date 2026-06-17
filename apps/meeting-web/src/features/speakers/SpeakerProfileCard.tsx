import { useState } from "react";
import { useSpeakerEnrollmentsQuery, useRevokeSpeakerProfile, useDeleteSpeakerProfile } from "./queries";
import { SpeakerEnrollPanel } from "./SpeakerEnrollPanel";
import type { SpeakerProfile } from "@shared/api/client";

interface Props {
  profile: SpeakerProfile;
  setError: (msg: string | null) => void;
}

export function SpeakerProfileCard({ profile, setError }: Props) {
  const [enrollmentsOpen, setEnrollmentsOpen] = useState(false);
  const enrollmentsQuery = useSpeakerEnrollmentsQuery(profile.speakerProfileId, enrollmentsOpen);
  const revoke = useRevokeSpeakerProfile();
  const remove = useDeleteSpeakerProfile();

  const isActive = profile.consentStatus === "ACTIVE";
  const enrollments = enrollmentsQuery.data?.items;

  const onRevoke = async () => {
    if (!window.confirm("确定要撤销此声纹的授权吗？撤销后将无法继续使用。")) {
      return;
    }
    try {
      await revoke.mutateAsync(profile.speakerProfileId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "撤销失败");
    }
  };

  const onDelete = async () => {
    if (!window.confirm("确定要删除此声纹吗？此操作不可恢复。")) {
      return;
    }
    try {
      await remove.mutateAsync(profile.speakerProfileId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "删除失败");
    }
  };

  return (
    <section
      className="glass-panel glass-panel--compact stack"
      data-profile-id={profile.speakerProfileId}
    >
      <div className="toolbar">
        <strong>{profile.displayName ?? profile.personId}</strong>
        <span
          className={`pill ${isActive ? "pill--success" : "pill--neutral"}`}
          data-consent={profile.consentStatus}
        >
          {profile.consentStatus}
        </span>
        <span className="page-subtitle" translate="no">{profile.personId}</span>
      </div>

      <details onToggle={(e) => setEnrollmentsOpen((e.currentTarget as HTMLDetailsElement).open)}>
        <summary>参考音频 {enrollments ? `(${enrollments.length})` : ""}</summary>
        {enrollmentsQuery.isPending && enrollmentsOpen ? (
          <p className="page-subtitle" aria-live="polite">加载中…</p>
        ) : null}
        {enrollments ? (
          <div className="stack">
            {enrollments.length === 0 ? <p className="page-subtitle">暂无参考音频</p> : null}
            {enrollments.map((enrollment) => (
              <article className="stack" key={enrollment.enrollmentId}>
                <div className="toolbar">
                  <span translate="no">{enrollment.sourceAudioFileId}</span>
                  <span
                    className={`pill ${
                      enrollment.enrollmentStatus === "SUCCEEDED"
                        ? "pill--success"
                        : enrollment.enrollmentStatus === "FAILED"
                          ? "pill--danger"
                          : "pill--info"
                    }`}
                  >
                    {enrollment.enrollmentStatus}
                  </span>
                  {typeof enrollment.qualityScore === "number" ? (
                    <span className="page-subtitle">
                      质量 {Math.round(enrollment.qualityScore * 100)}%
                    </span>
                  ) : null}
                </div>
              </article>
            ))}
            {isActive ? (
              <SpeakerEnrollPanel
                profileId={profile.speakerProfileId}
                onEnrollSuccess={() => enrollmentsQuery.refetch()}
                setError={setError}
              />
            ) : null}
          </div>
        ) : null}
      </details>

      <div className="toolbar">
        {isActive ? (
          <button
            type="button"
            className="button"
            disabled={revoke.isPending}
            onClick={() => void onRevoke()}
          >
            撤销授权
          </button>
        ) : null}
        <button
          type="button"
          className="button button--danger"
          disabled={remove.isPending}
          onClick={() => void onDelete()}
        >
          删除档案
        </button>
      </div>
    </section>
  );
}
