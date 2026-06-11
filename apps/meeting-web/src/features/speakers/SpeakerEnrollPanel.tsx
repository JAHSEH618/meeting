import { useEffect, useState } from "react";
import {
  createAudioUpload,
  createAudioUploadPart,
  putAudioUploadPart,
  completeAudioUpload,
  createMeeting,
  createSpeakerEnrollment,
  listMeetings,
  listSpeakerEnrollments,
} from "@shared/api/client";
import { sha256Hex } from "@shared/utils/sha256-stream";

const SAMPLE_TEXTS = [
  "今天我们在这里召开本地会议智能系统的技术方案评审会，感谢大家的积极参与。",
  "声纹识别是一种生物识别技术，通过分析语音的物理特征来识别说话人的身份。",
  "天行健，君子以自强不息；地势坤，君子以厚德载物。",
  "人工智能是人类智慧的结晶，未来它将深度融入我们生活的方方面面，带来巨大变革。",
  "请阅读这段示例文本，保持声音清晰自然，录制约三十秒左右的音频以完成声纹注册。",
];

async function getOrCreateSystemMeeting(): Promise<string> {
  const meetingsList = await listMeetings();
  const firstMeeting = meetingsList.items?.[0];
  if (firstMeeting?.meetingId) return firstMeeting.meetingId;
  const newMeeting = await createMeeting({
    title: "声纹注册临时载体会议",
    language: "zh",
  });
  return newMeeting.meetingId;
}

function formatDuration(sec: number) {
  const mins = Math.floor(sec / 60);
  const secs = sec % 60;
  return `${mins.toString().padStart(2, "0")}:${secs.toString().padStart(2, "0")}`;
}

interface Props {
  profileId: string;
  onEnrollSuccess: () => void;
  setError: (msg: string | null) => void;
}

export function SpeakerEnrollPanel({ profileId, onEnrollSuccess, setError }: Props) {
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
  const [enrollmentFeedback, setEnrollmentFeedback] = useState<{
    tone: "success" | "error";
    message: string;
  } | null>(null);

  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | null = null;
    if (recording) {
      interval = setInterval(() => setRecordDuration((prev) => prev + 1), 1000);
    } else {
      setRecordDuration(0);
    }
    return () => {
      if (interval) clearInterval(interval);
    };
  }, [recording]);

  useEffect(() => {
    if (!pollingEnrollmentId) return;
    const poll = async () => {
      try {
        const resp = await listSpeakerEnrollments(profileId);
        const match = resp.items.find((e) => e.enrollmentId === pollingEnrollmentId);
        if (match) {
          if (match.enrollmentStatus === "SUCCEEDED") {
            setEnrollmentFeedback({ tone: "success", message: "声纹注册成功" });
            setPollingEnrollmentId(null);
            onEnrollSuccess();
          } else if (match.enrollmentStatus === "FAILED") {
            setEnrollmentFeedback({
              tone: "error",
              message: "声纹注册失败，请重新录制清晰明亮的音频进行尝试",
            });
            setPollingEnrollmentId(null);
            onEnrollSuccess();
          }
        }
      } catch {
        /* polling will retry */
      }
    };
    const timer = setInterval(poll, 1500);
    return () => clearInterval(timer);
  }, [pollingEnrollmentId, profileId, onEnrollSuccess]);

  const handleNextText = () => {
    setSampleTextIdx((prev) => (prev + 1) % SAMPLE_TEXTS.length);
  };

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      let recorder: MediaRecorder;
      try {
        recorder = new MediaRecorder(stream, { mimeType: "audio/webm" });
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
    } catch {
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
    setEnrollmentFeedback(null);
    setEnrolling(true);
    setStatusText("准备上传通道…");
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

      setStatusText("正在计算音频指纹…");
      const sha256 = await sha256Hex(fileBlob);

      setStatusText("正在申请上传通道…");
      const session = await createAudioUpload(meetingId, {
        fileName,
        contentType: fileBlob.type || "application/octet-stream",
        fileSizeBytes: fileBlob.size,
        fileSha256: sha256,
        partSizeBytes: fileBlob.size * 2,
      });

      setStatusText("正在上传录音数据…");
      const signed = await createAudioUploadPart(meetingId, session.uploadId, {
        partNumber: 1,
        sizeBytes: fileBlob.size,
        partSha256: sha256,
      });
      await putAudioUploadPart(signed.uploadUrl, fileBlob, signed.headers);

      setStatusText("正在校验并完成上传…");
      const completedSession = await completeAudioUpload(meetingId, session.uploadId, {
        fileSha256: sha256,
        durationMs: null,
        parts: [{
          partNumber: 1,
          partSha256: sha256,
          etag: signed.etag || "etag_1",
        }],
      });

      if (!completedSession.fileId) throw new Error("完成上传失败，未生成 File ID");

      setStatusText("正在提交声纹注册任务…");
      const enrollment = await createSpeakerEnrollment(profileId, completedSession.fileId);

      if (enrollment && enrollment.enrollmentId) {
        setPollingEnrollmentId(enrollment.enrollmentId);
        setRecordedBlob(null);
        setUploadFile(null);
        setError(null);
      } else {
        throw new Error("声纹档案注册返回数据异常");
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setEnrolling(false);
      setStatusText(null);
    }
  };

  return (
    <div className="speaker-enroll-panel stack">
      <span className="speaker-enroll-panel__heading">添加参考音频</span>

      {enrollmentFeedback ? (
        <div
          className={
            enrollmentFeedback.tone === "success"
              ? "banner banner--success"
              : "banner banner--danger"
          }
          role={enrollmentFeedback.tone === "success" ? "status" : "alert"}
          aria-live={enrollmentFeedback.tone === "success" ? "polite" : "assertive"}
        >
          {enrollmentFeedback.message}
        </div>
      ) : null}

      <div className="speaker-enroll-panel__tabs">
        <button
          type="button"
          className={`button${tab === "record" ? " button--primary" : ""}`}
          onClick={() => setTab("record")}
          disabled={enrolling || !!pollingEnrollmentId}
        >
          🎙️ 当场录音
        </button>
        <button
          type="button"
          className={`button${tab === "upload" ? " button--primary" : ""}`}
          onClick={() => setTab("upload")}
          disabled={enrolling || !!pollingEnrollmentId}
        >
          📁 上传文件
        </button>
      </div>

      {pollingEnrollmentId ? (
        <div className="speaker-enroll-panel__progress" role="status" aria-live="polite">
          <span className="speaker-enroll-panel__spinner" aria-hidden="true" />
          <div className="stack" style={{ gap: 4 }}>
            <span className="speaker-enroll-panel__progress-title">
              🎙️ 音频上传成功，后端正在提取并注册声纹…
            </span>
            <span className="page-subtitle">
              正在等待机器学习特征匹配完成，完成后将自动在页面内提示。
            </span>
          </div>
        </div>
      ) : (
        <>
          {tab === "record" ? (
            <div className="stack">
              <div className="sample-text-card stack">
                <div className="toolbar" style={{ justifyContent: "space-between" }}>
                  <span className="page-subtitle">请大声朗读以下文本（声音需清晰自然）：</span>
                  <button
                    type="button"
                    className="button button--ghost"
                    onClick={handleNextText}
                    disabled={recording || enrolling}
                  >
                    换一句 🔄
                  </button>
                </div>
                <p className="sample-text-card__body">「{SAMPLE_TEXTS[sampleTextIdx]}」</p>
              </div>

              <div className="toolbar">
                {recording ? (
                  <button
                    type="button"
                    className="button button--danger speaker-recorder__pulse"
                    onClick={stopRecording}
                  >
                    🛑 停止录音 ({formatDuration(recordDuration)})
                  </button>
                ) : (
                  <button
                    type="button"
                    className="button button--primary"
                    onClick={startRecording}
                    disabled={enrolling}
                  >
                    🎙️ 开始录音
                  </button>
                )}
                {recordedBlob && !recording ? (
                  <audio
                    src={URL.createObjectURL(recordedBlob)}
                    controls
                    className="speaker-recorder__preview"
                  />
                ) : null}
              </div>
            </div>
          ) : null}

          {tab === "upload" ? (
            <div className="stack">
              <label className="upload-dropzone">
                <input
                  type="file"
                  accept="audio/*,.wav,.mp3,.m4a"
                  disabled={enrolling}
                  onChange={(e) => setUploadFile(e.target.files?.[0] ?? null)}
                  className="upload-dropzone__input"
                  name="speakerEnrollmentAudio"
                />
                <span className="upload-dropzone__icon">📁</span>
                <span className="upload-dropzone__label">
                  {uploadFile ? uploadFile.name : "点击选择音频文件 (MP3, WAV, M4A)"}
                </span>
                {uploadFile ? (
                  <span className="page-subtitle">
                    大小: {(uploadFile.size / 1024 / 1024).toFixed(2)} MB
                  </span>
                ) : null}
              </label>
            </div>
          ) : null}

          {statusText ? (
            <div className="speaker-enroll-panel__status" role="status" aria-live="polite">
              <span className="speaker-enroll-panel__spinner speaker-enroll-panel__spinner--small" aria-hidden="true" />
              <span>{statusText}</span>
            </div>
          ) : null}

          <div className="toolbar">
            <button
              type="button"
              className="button button--primary"
              disabled={
                enrolling
                || (tab === "record" && !recordedBlob)
                || (tab === "upload" && !uploadFile)
              }
              onClick={() => void handleEnroll()}
            >
              {enrolling ? "正在处理…" : "提交注册"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
