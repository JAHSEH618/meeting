import { Suspense, lazy } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { useAuth } from "@/shared/auth/useAuth";
import { SkipLink } from "@/shared/components/SkipLink";
import { EnrollmentPage } from "@/pages/EnrollmentPage";
import { LoginPage } from "@/pages/LoginPage";
import { MeetingsPage } from "@/pages/MeetingsPage";

const NewMeetingPage = lazy(() =>
  import("@/pages/NewMeetingPage").then((m) => ({ default: m.NewMeetingPage })),
);

const MeetingDetailPage = lazy(() =>
  import("@/pages/MeetingDetailPage").then((m) => ({ default: m.MeetingDetailPage })),
);

const SpeakerProfilesPage = lazy(() =>
  import("@/pages/SpeakerProfilesPage").then((m) => ({ default: m.SpeakerProfilesPage })),
);

export default function App() {
  const { ready, token } = useAuth();
  if (!ready) {
    return (
      <div className="layout">
        <main className="layout__main" aria-busy="true" role="status" aria-live="polite">加载中…</main>
      </div>
    );
  }
  return (
    <div className="layout">
      <SkipLink />
      <header className="layout__header">
        <strong className="layout__brand">运营工作站</strong>
        <nav className="layout__nav" aria-label="主导航">
          <NavLink to="/meetings" className={({ isActive }) => (isActive ? "active" : "")}>会议</NavLink>
          <NavLink to="/enrollment" className={({ isActive }) => (isActive ? "active" : "")}>声纹录入</NavLink>
          <NavLink to="/speaker-profiles" className={({ isActive }) => (isActive ? "active" : "")}>声纹档案</NavLink>
        </nav>
        <span style={{ fontSize: 12, color: "var(--ink-3)" }}>{token ? "已登录" : "未登录"}</span>
      </header>
      <main id="main-content" className="layout__main">
        <Suspense fallback={<div aria-busy="true" role="status">加载中…</div>}>
          <Routes>
            <Route path="/" element={<MeetingsPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/meetings" element={<MeetingsPage />} />
            <Route path="/meetings/new" element={<NewMeetingPage />} />
            <Route path="/meetings/:meetingId" element={<MeetingDetailPage />} />
            <Route path="/enrollment" element={<EnrollmentPage />} />
            <Route path="/speaker-profiles" element={<SpeakerProfilesPage />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  );
}
