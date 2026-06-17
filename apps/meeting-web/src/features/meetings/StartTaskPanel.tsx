import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useStartTask } from "./queries";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function StartTaskPanel({ meetingId }: { meetingId: string }) {
  const navigate = useNavigate();
  const [audioFileId, setAudioFileId] = useState("audio_fixture_01");
  const start = useStartTask(meetingId);

  const errorMsg = start.error
    ? ((start.error as ApiClientError).code
        ? getUserMessage((start.error as ApiClientError).code!)
        : "处理任务创建失败")
    : null;

  async function handleStart() {
    const fileId = audioFileId.trim() || "audio_fixture_01";
    try {
      const task = await start.mutateAsync(fileId);
      navigate(`/meetings/${meetingId}/tasks/${task.taskId}`);
    } catch {
      /* error rendered above */
    }
  }

  return (
    <section className="glass-panel glass-panel--compact stack">
      <div>
        <strong>处理任务</strong>
        <p className="page-subtitle">提交音频文件 ID 启动 MEETING_FULL_PIPELINE。</p>
      </div>
      {errorMsg ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">{errorMsg}</strong>
        </div>
      ) : null}
      <div className="field" style={{ maxWidth: 420 }}>
        <label className="field__label" htmlFor="audio-file-id">音频文件 ID</label>
        <input
          id="audio-file-id"
          name="audioFileId"
          autoComplete="off"
          value={audioFileId}
          onChange={(e) => setAudioFileId(e.target.value)}
        />
      </div>
      <button
        type="button"
        className="button button--primary"
        disabled={start.isPending}
        onClick={handleStart}
      >
        {start.isPending ? "启动中…" : "启动 MEETING_FULL_PIPELINE"}
      </button>
    </section>
  );
}
