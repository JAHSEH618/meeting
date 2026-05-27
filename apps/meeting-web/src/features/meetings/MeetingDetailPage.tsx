import { Link, useParams } from "react-router-dom";
import { useMeetingQuery } from "./queries";
import { MeetingOverviewTab } from "./MeetingOverviewTab";
import { MeetingTabBar } from "./MeetingTabBar";
import { getUserMessage } from "@shared/utils/error-mapper";
import type { ApiClientError } from "@shared/api/client";

export function MeetingDetailPage() {
  const { meetingId } = useParams();
  const { data: meeting, isPending, error } = useMeetingQuery(meetingId);

  const errorMsg = error
    ? ((error as ApiClientError).code
        ? getUserMessage((error as ApiClientError).code!)
        : "会议详情加载失败")
    : null;

  return (
    <div className="page">
      {isPending ? <p className="page-subtitle" aria-busy="true">加载中…</p> : null}
      {errorMsg ? (
        <div className="banner banner--danger" role="alert">
          <strong className="banner__title">{errorMsg}</strong>
        </div>
      ) : null}
      {meeting ? (
        <>
          <header className="page-header">
            <div>
              <h1 className="page-title">{meeting.title}</h1>
              <p className="page-subtitle">
                <span translate="no">{meeting.meetingId}</span> · {meeting.securityLevel} · {meeting.language}
              </p>
            </div>
            <div className="page-actions">
              <Link className="button button--primary" to={`/meetings/${meeting.meetingId}/audio`}>上传音频</Link>
              <Link className="button" to="/meetings">返回列表</Link>
            </div>
          </header>
          <MeetingTabBar />
          <MeetingOverviewTab meeting={meeting} />
        </>
      ) : null}
    </div>
  );
}
