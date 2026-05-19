import { NavLink, Route, Routes } from "react-router-dom";
import { useAuth } from "@/shared/auth/useAuth";
import { EnrollmentPage } from "@/pages/EnrollmentPage";
import { MeetingsPage } from "@/pages/MeetingsPage";
import { MeetingWorkstationPage } from "@/pages/MeetingWorkstationPage";

export default function App() {
  const { ready, token } = useAuth();
  if (!ready) return <div className="layout"><main className="layout__main">加载中…</main></div>;
  return (
    <div className="layout">
      <header className="layout__header">
        <strong>会议工作站</strong>
        <nav className="layout__nav">
          <NavLink to="/meetings" className={({ isActive }) => (isActive ? "active" : "")}>会议</NavLink>
          <NavLink to="/enrollment" className={({ isActive }) => (isActive ? "active" : "")}>声纹录入</NavLink>
        </nav>
        <span>{token ? "已登录" : "未登录"}</span>
      </header>
      <main className="layout__main">
        <Routes>
          <Route path="/" element={<MeetingsPage />} />
          <Route path="/meetings" element={<MeetingsPage />} />
          <Route path="/meetings/:meetingId" element={<MeetingWorkstationPage />} />
          <Route path="/meetings/new" element={<MeetingWorkstationPage />} />
          <Route path="/enrollment" element={<EnrollmentPage />} />
        </Routes>
      </main>
    </div>
  );
}
