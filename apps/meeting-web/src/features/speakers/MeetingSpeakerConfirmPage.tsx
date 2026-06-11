import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  confirmMeetingSpeaker,
  getMeeting,
  listMeetingSpeakers,
  listSpeakerProfiles,
  rejectMeetingSpeaker,
  type MeetingSpeaker,
  type SpeakerProfile,
} from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";

const CANDIDATE_EXPIRED_HINT = "候选列表过期或为空，请等待转录处理完成后再确认";

export function MeetingSpeakerConfirmPage() {
  const { meetingId = "" } = useParams();
  const [speakers, setSpeakers] = useState<MeetingSpeaker[]>([]);
  const [profiles, setProfiles] = useState<SpeakerProfile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingLabel, setPendingLabel] = useState<string | null>(null);
  const [transcriptVersion, setTranscriptVersion] = useState<number | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [meeting, speakersResp, profilesResp] = await Promise.all([
        getMeeting(meetingId),
        listMeetingSpeakers(meetingId),
        listSpeakerProfiles().catch(() => ({ items: [] as SpeakerProfile[] })),
      ]);
      setTranscriptVersion(meeting.transcriptVersion);
      setSpeakers(speakersResp.speakers);
      setProfiles(profilesResp.items);
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "加载失败");
    } finally {
      setLoading(false);
    }
  }, [meetingId]);

  useEffect(() => {
    if (!meetingId) return;
    void reload();
  }, [meetingId, reload]);

  const handleConfirm = async (speakerLabel: string, personId: string, speakerProfileId: string) => {
    if (transcriptVersion == null) {
      setError("加载失败");
      return;
    }
    setPendingLabel(speakerLabel);
    setError(null);
    try {
      await confirmMeetingSpeaker(meetingId, speakerLabel, {
        personId,
        speakerProfileId,
        expectedTranscriptVersion: transcriptVersion,
      });
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "确认失败");
    } finally {
      setPendingLabel(null);
    }
  };

  const handleReject = async (speakerLabel: string) => {
    setPendingLabel(speakerLabel);
    setError(null);
    try {
      await rejectMeetingSpeaker(meetingId, speakerLabel);
      await reload();
    } catch (cause) {
      const apiError = cause as ApiClientError;
      setError(apiError.code ? getUserMessage(apiError.code) : "拒绝失败");
    } finally {
      setPendingLabel(null);
    }
  };

  return (
    <main className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">说话人确认</h1>
          <p className="muted">{meetingId}</p>
        </div>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meetingId}`}>返回会议</Link>
          <Link className="button" to={`/meetings/${meetingId}/transcript`}>查看转录</Link>
          <Link className="button" to="/speaker-profiles">声纹档案</Link>
        </div>
      </div>

      {loading ? <p className="muted">加载中</p> : null}
      {error ? <div className="error" role="alert">{error}</div> : null}

      {!loading && speakers.length === 0 ? (
        <section className="card stack" role="status">
          <strong>暂无说话人候选</strong>
          <span className="muted">{CANDIDATE_EXPIRED_HINT}</span>
        </section>
      ) : null}

      {speakers.map((speaker) => {
        const profileByCandidate = (personId: string, speakerProfileId: string) =>
          profiles.find((p) => p.speakerProfileId === speakerProfileId) ?? profiles.find((p) => p.personId === personId);
        const isPending = pendingLabel === speaker.speakerLabel;
        const candidatePersons = speaker.candidates.map((candidate) => ({
          ...candidate,
          profile: profileByCandidate(candidate.personId, candidate.speakerProfileId),
        }));
        return (
          <section className="card stack" key={speaker.speakerLabel} data-speaker-label={speaker.speakerLabel}>
            <div className="toolbar">
              <strong>{speaker.speakerLabel}</strong>
              <span className="badge" data-status={speaker.confirmationStatus}>{speaker.confirmationStatus}</span>
              {speaker.displayName ? <span className="muted">已确认 {speaker.displayName}</span> : null}
            </div>

            {speaker.confirmationStatus === "CONFIRMED" ? (
              <p className="muted">已确认为 {speaker.displayName ?? speaker.personId}</p>
            ) : null}

            {speaker.confirmationStatus !== "CONFIRMED" && candidatePersons.length === 0 ? (
              <p className="muted">{CANDIDATE_EXPIRED_HINT}</p>
            ) : null}

            {speaker.confirmationStatus !== "CONFIRMED" && candidatePersons.length > 0 ? (
              <div className="stack">
                {candidatePersons.map(({ personId, speakerProfileId, displayName, confidence, profile }) => (
                  <article className="stack" key={`${personId}:${speakerProfileId}`}>
                    <div className="toolbar">
                      <strong>{profile?.displayName ?? displayName}</strong>
                      <span className="muted">匹配 {Math.round(confidence * 100)}%</span>
                      {profile ? <span className="badge" data-consent={profile.consentStatus}>{profile.consentStatus}</span> : null}
                    </div>
                    <div className="toolbar">
                      <button
                        type="button"
                        className="button primary"
                        disabled={isPending || (profile && profile.consentStatus !== "ACTIVE")}
                        onClick={() => void handleConfirm(speaker.speakerLabel, personId, speakerProfileId)}
                      >
                        确认为 {profile?.displayName ?? displayName}
                      </button>
                    </div>
                  </article>
                ))}
                <div className="toolbar">
                  <button
                    type="button"
                    className="button"
                    disabled={isPending}
                    onClick={() => void handleReject(speaker.speakerLabel)}
                  >
                    拒绝候选
                  </button>
                </div>
              </div>
            ) : null}
          </section>
        );
      })}
    </main>
  );
}
