import { Link } from "react-router-dom";
import type { Meeting } from "@shared/api/types";
import { formatDate } from "@shared/utils/formatters";
import { StartTaskPanel } from "./StartTaskPanel";

export function MeetingOverviewTab({ meeting }: { meeting: Meeting }) {
  return (
    <div className="stack">
      <section className="grid">
        <div className="metric">
          <div className="metric__label">状态</div>
          <div className="metric__value">{meeting.status}</div>
        </div>
        <div className="metric">
          <div className="metric__label">安全等级</div>
          <div className="metric__value">{meeting.securityLevel}</div>
        </div>
        <div className="metric">
          <div className="metric__label">语言</div>
          <div className="metric__value">{meeting.language}</div>
        </div>
        <div className="metric">
          <div className="metric__label">转录版本</div>
          <div className="metric__value">v{meeting.transcriptVersion}</div>
        </div>
        <div className="metric">
          <div className="metric__label">纪要版本</div>
          <div className="metric__value">v{meeting.minutesVersion}</div>
        </div>
        <div className="metric">
          <div className="metric__label">创建时间</div>
          <div className="metric__value" style={{ fontSize: 14 }}>{formatDate(meeting.createdAt)}</div>
        </div>
      </section>

      <StartTaskPanel meetingId={meeting.meetingId} />

      <section className="card stack">
        <strong>快速进入</strong>
        <div className="toolbar">
          <Link className="button" to={`/meetings/${meeting.meetingId}/audio`}>上传音频</Link>
          <Link className="button" to={`/meetings/${meeting.meetingId}/transcript`}>转录</Link>
          <Link className="button" to={`/meetings/${meeting.meetingId}/minutes`}>纪要</Link>
          <Link className="button" to={`/meetings/${meeting.meetingId}/exports`}>导出</Link>
        </div>
      </section>
    </div>
  );
}
