import { NavLink, useParams } from "react-router-dom";

interface Tab {
  to: string;
  label: string;
  end?: boolean;
}

export function MeetingTabBar() {
  const { meetingId = "" } = useParams();
  const tabs: Tab[] = [
    { to: `/meetings/${meetingId}`, label: "概览", end: true },
    { to: `/meetings/${meetingId}/transcript`, label: "转录" },
    { to: `/meetings/${meetingId}/minutes`, label: "纪要" },
    { to: `/meetings/${meetingId}/items`, label: "行动项" },
    { to: `/meetings/${meetingId}/speakers`, label: "说话人" },
    { to: `/meetings/${meetingId}/exports`, label: "导出" },
  ];
  return (
    <nav className="tabbar" aria-label="会议导航">
      {tabs.map((t) => (
        <NavLink key={t.to} to={t.to} end={t.end}>
          {t.label}
        </NavLink>
      ))}
    </nav>
  );
}
