import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  createSpeakerEnrollment,
  createSpeakerProfile,
  deleteSpeakerProfile,
  listSpeakerEnrollments,
  listSpeakerProfiles,
  revokeSpeakerProfile,
  createAudioUpload,
  createAudioUploadPart,
  putAudioUploadPart,
  completeAudioUpload,
  listMeetings,
  createMeeting,
  type SpeakerEnrollment,
  type SpeakerProfile,
} from "@shared/api/client";
import type { ApiClientError } from "@shared/api/client";
import { getUserMessage } from "@shared/utils/error-mapper";
import { sha256Hex } from "@shared/utils/sha256-stream";

const SAMPLE_TEXTS = [
  "今天我们在这里召开本地会议智能系统的技术方案评审会，感谢大家的积极参与。",
  "声纹识别是一种生物识别技术，通过分析语音的物理特征来识别说话人的身份。",
  "天行健，君子以自强不息；地势坤，君子以厚德载物。",
  "人工智能是人类智慧的结晶，未来它将深度融入我们生活的方方面面，带来巨大变革。",
  "请阅读这段示例文本，保持声音清晰自然，录制约三十秒左右的音频以完成声纹注册。"
];

async function getOrCreateSystemMeeting(): Promise<string> {
  const meetingsList = await listMeetings();
  const firstMeeting = meetingsList.items?.[0];
  if (firstMeeting?.meetingId) {
    return firstMeeting.meetingId;
  }
  const newMeeting = await createMeeting({
    title: "声纹注册临时载体会议",
    language: "zh",
    securityLevel: "INTERNAL",
  });
  return newMeeting.meetingId;
}

export function SpeakerProfilesPage() {
  const [profiles, setProfiles] = useState<SpeakerProfile[]>([]);
  const [enrollmentsByProfile, setEnrollmentsByProfile] = useState<Record<string, SpeakerEnrollment[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingProfileId, setPendingProfileId] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createPersonId, setCreatePersonId] = useState("");
  const [createDisplayName, setCreateDisplayName] = useState("");

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
                    <SpeakerEnrollPanel
                      profileId={profile.speakerProfileId}
                      onEnrollSuccess={() => void loadEnrollments(profile.speakerProfileId)}
                      setError={setError}
                    />
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

interface SpeakerEnrollPanelProps {
  profileId: string;
  onEnrollSuccess: () => void;
  setError: (msg: string | null) => void;
}

function SpeakerEnrollPanel({ profileId, onEnrollSuccess, setError }: SpeakerEnrollPanelProps) {
  const [tab, setTab] = useState<"record" | "upload">("record");
  const [sampleTextIdx, setSampleTextIdx] = useState(0);
  const [recording, setRecording] = useState(false);
  const [mediaRecorder, setMediaRecorder] = useState<MediaRecorder | null>(null);
  const [recordedBlob, setRecordedBlob] = useState<Blob | null>(null);
  const [recordDuration, setRecordDuration] = useState(0);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [statusText, setStatusText] = useState<string | null>(null);
  const [enrolling, setEnrolling] = useState(false);
  const [pollingEnrollmentId, setPollingEnrollmentId] = useState<string | null>(null);

  useEffect(() => {
    let interval: NodeJS.Timeout | null = null;
    if (recording) {
      interval = setInterval(() => {
        setRecordDuration((prev) => prev + 1);
      }, 1000);
    } else {
      setRecordDuration(0);
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [recording]);

  // Polling speaker enrollment status for real-time success alert popup
  useEffect(() => {
    if (!pollingEnrollmentId) return;

    let timer: NodeJS.Timeout;
    const poll = async () => {
      try {
        const resp = await listSpeakerEnrollments(profileId);
        const match = resp.items.find((e) => e.enrollmentId === pollingEnrollmentId);
        if (match) {
          if (match.enrollmentStatus === "SUCCEEDED") {
            alert("🎉 声纹注册成功！");
            setPollingEnrollmentId(null);
            onEnrollSuccess();
          } else if (match.enrollmentStatus === "FAILED") {
            alert("❌ 声纹注册失败，请重新录制清晰明亮的音频进行尝试！");
            setPollingEnrollmentId(null);
            onEnrollSuccess();
          }
        }
      } catch (e) {
        console.error("Failed to poll speaker enrollment status:", e);
      }
    };

    timer = setInterval(poll, 1500);
    return () => clearInterval(timer);
  }, [pollingEnrollmentId, profileId, onEnrollSuccess]);

  const handleNextText = () => {
    setSampleTextIdx((prev) => (prev + 1) % SAMPLE_TEXTS.length);
  };

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const options = { mimeType: "audio/webm" };
      let recorder: MediaRecorder;
      try {
        recorder = new MediaRecorder(stream, options);
      } catch {
        recorder = new MediaRecorder(stream);
      }
      
      const chunks: Blob[] = [];
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) chunks.push(e.data);
      };
      recorder.onstop = () => {
        const blob = new Blob(chunks, { type: recorder.mimeType || "audio/webm" });
        setRecordedBlob(blob);
        stream.getTracks().forEach((track) => track.stop());
      };
      
      setRecordedBlob(null);
      setRecordDuration(0);
      setMediaRecorder(recorder);
      recorder.start();
      setRecording(true);
      setError(null);
    } catch (err) {
      setError("获取麦克风失败，请确保已授予权限。");
    }
  };

  const stopRecording = () => {
    if (mediaRecorder && recording) {
      mediaRecorder.stop();
      setRecording(false);
    }
  };

  const handleEnroll = async () => {
    setError(null);
    setEnrolling(true);
    setStatusText("准备上传通道...");
    try {
      let fileBlob: Blob | File;
      let fileName: string;
      
      if (tab === "record") {
        if (!recordedBlob) throw new Error("请先录音");
        fileBlob = recordedBlob;
        fileName = `voice_enroll_${profileId}_${Date.now()}.webm`;
      } else {
        if (!uploadFile) throw new Error("请先选择音频文件");
        fileBlob = uploadFile;
        fileName = uploadFile.name;
      }

      const meetingId = await getOrCreateSystemMeeting();
      
      setStatusText("正在计算音频指纹...");
      const sha256 = await sha256Hex(fileBlob);
      
      setStatusText("正在申请上传通道...");
      const session = await createAudioUpload(meetingId, {
        fileName,
        contentType: fileBlob.type || "application/octet-stream",
        fileSizeBytes: fileBlob.size,
        fileSha256: sha256,
        partSizeBytes: fileBlob.size * 2,
      });

      setStatusText("正在上传录音数据...");
      const signed = await createAudioUploadPart(meetingId, session.uploadId, {
        partNumber: 1,
        sizeBytes: fileBlob.size,
        partSha256: sha256,
      });

      await putAudioUploadPart(signed.uploadUrl, fileBlob, signed.headers);

      setStatusText("正在校验并完成上传...");
      const completedSession = await completeAudioUpload(meetingId, session.uploadId, {
        fileSha256: sha256,
        durationMs: null,
        parts: [{
          partNumber: 1,
          partSha256: sha256,
          etag: signed.etag || "etag_1",
        }],
      });

      if (!completedSession.fileId) {
        throw new Error("完成上传失败，未生成 File ID");
      }

      setStatusText("正在提交声纹注册任务...");
      const enrollment = await createSpeakerEnrollment(profileId, completedSession.fileId);
      
      if (enrollment && enrollment.enrollmentId) {
        setPollingEnrollmentId(enrollment.enrollmentId);
        setRecordedBlob(null);
        setUploadFile(null);
        setError(null);
      } else {
        throw new Error("声纹档案注册返回数据异常");
      }
    } catch (cause: any) {
      setError(cause.message || String(cause));
    } finally {
      setEnrolling(false);
      setStatusText(null);
    }
  };

  const formatDuration = (sec: number) => {
    const mins = Math.floor(sec / 60);
    const secs = sec % 60;
    return `${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
  };

  return (
    <div className="speaker-enroll-panel stack" style={{ borderTop: "1px solid #dde3ea", paddingTop: "16px", marginTop: "16px" }}>
      <style>{`
        @keyframes enroll-spin {
          to { transform: rotate(360deg); }
        }
        @keyframes record-pulse {
          0% { transform: scale(1); box-shadow: 0 0 0 0 rgba(220, 38, 38, 0.4); }
          70% { transform: scale(1.03); box-shadow: 0 0 0 8px rgba(220, 38, 38, 0); }
          100% { transform: scale(1); box-shadow: 0 0 0 0 rgba(220, 38, 38, 0); }
        }
        .pulse-recording {
          animation: record-pulse 1.5s infinite;
          background-color: #dc2626 !important;
          border-color: #dc2626 !important;
          color: #ffffff !important;
        }
        .upload-dropzone:hover {
          border-color: #176b87 !important;
          background-color: #f1f5f9 !important;
        }
      `}</style>
      
      <span style={{ fontSize: "14px", fontWeight: "600", color: "#17202a" }}>添加参考音频</span>

      <div className="tab-header toolbar" style={{ gap: "8px", marginBottom: "8px" }}>
        <button
          type="button"
          className={`button ${tab === "record" ? "primary" : ""}`}
          style={{ minHeight: "32px", padding: "4px 12px", fontSize: "13px" }}
          onClick={() => setTab("record")}
          disabled={enrolling || !!pollingEnrollmentId}
        >
          🎙️ 当场录音
        </button>
        <button
          type="button"
          className={`button ${tab === "upload" ? "primary" : ""}`}
          style={{ minHeight: "32px", padding: "4px 12px", fontSize: "13px" }}
          onClick={() => setTab("upload")}
          disabled={enrolling || !!pollingEnrollmentId}
        >
          📁 上传文件
        </button>
      </div>

      {pollingEnrollmentId ? (
        <div className="stack" style={{ gap: "10px", alignItems: "center", justifyContent: "center", padding: "20px", background: "#f0fdf4", border: "1px dashed #4ade80", borderRadius: "8px", textAlign: "center" }}>
          <span className="spinner" style={{
            display: "inline-block",
            width: "24px",
            height: "24px",
            border: "3px solid rgba(22, 101, 52, 0.15)",
            borderTopColor: "#166534",
            borderRadius: "50%",
            animation: "enroll-spin 1s linear infinite"
          }} />
          <div className="stack" style={{ gap: "4px" }}>
            <span style={{ fontSize: "14px", fontWeight: "600", color: "#166534" }}>🎙️ 音频上传成功，后端正在提取并注册声纹...</span>
            <span style={{ fontSize: "12px", color: "#4b5563" }}>正在等待机器学习特征匹配完成，完成后将自动为您弹窗提示！</span>
          </div>
        </div>
      ) : (
        <>
          {tab === "record" ? (
            <div className="stack" style={{ gap: "10px" }}>
              <div className="sample-text-card stack" style={{ background: "#f8fafc", border: "1px dashed #cbd5e1", padding: "14px", borderRadius: "8px" }}>
                <div className="toolbar" style={{ justifyContent: "space-between", alignItems: "center" }}>
                  <span className="muted" style={{ fontSize: "12px" }}>请大声朗读以下文本（声音需清晰自然）：</span>
                  <button
                    type="button"
                    className="button link"
                    style={{ minHeight: "auto", border: "none", background: "none", color: "#176b87", padding: 0, fontSize: "12px", fontWeight: "600" }}
                    onClick={handleNextText}
                    disabled={recording || enrolling}
                  >
                    换一句 🔄
                  </button>
                </div>
                <p className="sample-text-body" style={{ margin: "10px 0 4px", fontSize: "15px", fontWeight: "500", lineHeight: "1.6", color: "#1e293b" }}>
                  “ {SAMPLE_TEXTS[sampleTextIdx]} ”
                </p>
              </div>

              <div className="toolbar" style={{ gap: "12px", alignItems: "center" }}>
                {recording ? (
                  <button
                    type="button"
                    className="button pulse-recording"
                    style={{ padding: "8px 16px" }}
                    onClick={stopRecording}
                  >
                    🛑 停止录音 ({formatDuration(recordDuration)})
                  </button>
                ) : (
                  <button
                    type="button"
                    className="button primary"
                    style={{ padding: "8px 16px" }}
                    onClick={startRecording}
                    disabled={enrolling}
                  >
                    🎙️ 开始录音
                  </button>
                )}

                {recordedBlob && !recording ? (
                  <div className="toolbar" style={{ alignItems: "center", gap: "8px" }}>
                    <audio src={URL.createObjectURL(recordedBlob)} controls style={{ height: "36px", maxWidth: "260px" }} />
                  </div>
                ) : null}
              </div>
            </div>
          ) : null}

          {tab === "upload" ? (
            <div className="stack" style={{ gap: "8px" }}>
              <label className="upload-dropzone" style={{
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                padding: "24px",
                border: "2px dashed #cbd5e1",
                borderRadius: "8px",
                cursor: "pointer",
                background: "#f8fafc",
                transition: "all 0.2s ease"
              }}>
                <input
                  type="file"
                  accept="audio/*,.wav,.mp3,.m4a"
                  disabled={enrolling}
                  onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)}
                  style={{ display: "none" }}
                />
                <span style={{ fontSize: "28px" }}>📁</span>
                <span style={{ fontWeight: 500, marginTop: "8px", color: "#475569", fontSize: "14px" }}>
                  {uploadFile ? uploadFile.name : "点击选择音频文件 (MP3, WAV, M4A)"}
                </span>
                {uploadFile && (
                  <span style={{ fontSize: "12px", color: "#64748b", marginTop: "4px" }}>
                    大小: {(uploadFile.size / 1024 / 1024).toFixed(2)} MB
                  </span>
                )}
              </label>
            </div>
          ) : null}

          {statusText ? (
            <div className="loading-status toolbar" style={{ alignItems: "center", gap: "8px", color: "#176b87", marginTop: "4px" }}>
              <span className="spinner" style={{
                display: "inline-block",
                width: "14px",
                height: "14px",
                border: "2px solid rgba(23, 107, 135, 0.15)",
                borderTopColor: "#176b87",
                borderRadius: "50%",
                animation: "enroll-spin 1s linear infinite"
              }} />
              <span style={{ fontSize: "13px", fontWeight: "500" }}>{statusText}</span>
            </div>
          ) : null}

          <div className="toolbar" style={{ marginTop: "8px" }}>
            <button
              type="button"
              className="button primary"
              style={{ minHeight: "38px", padding: "8px 24px" }}
              disabled={enrolling || (tab === "record" && !recordedBlob) || (tab === "upload" && !uploadFile)}
              onClick={handleEnroll}
            >
              {enrolling ? "正在处理..." : "提交注册"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
