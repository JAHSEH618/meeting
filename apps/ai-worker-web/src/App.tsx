import { Suspense, lazy } from "react";
import { NavLink, Route, Routes, useParams } from "react-router-dom";
import { useAuth } from "@/shared/auth/useAuth";
import { SkipLink } from "@/shared/components/SkipLink";
import { EnrollmentPage } from "@/pages/EnrollmentPage";
import { LoginPage } from "@/pages/LoginPage";
import { MeetingsPage } from "@/pages/MeetingsPage";

const MeetingWorkstationPage = lazy(() =>
  import("@/pages/MeetingWorkstationPage").then((m) => ({ default: m.MeetingWorkstationPage })),
);

/**
 * Wrap the workstation page so the route param doubles as a React key.
 *
 * Switching between two existing meetings (``/meetings/A`` → ``/meetings/B``)
 * matches the same ``/meetings/:meetingId`` route, so React Router reuses
 * the same component instance. ``useWizard`` initialises ``meetingId``
 * once from ``useParams()`` on mount, which means without a key the state
 * (including ``state.meetingId``) sticks at A and downstream calls hit
 * the wrong meeting. Keying on the param forces unmount → mount, giving
 * each meeting a clean slate.
 */
function MeetingWorkstationRoute() {
  const { meetingId } = useParams<{ meetingId?: string }>();
  return <MeetingWorkstationPage key={meetingId ?? "new"} />;
}

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
        </nav>
        <span style={{ fontSize: 12, color: "var(--ink-3)" }}>{token ? "已登录" : "未登录"}</span>
      </header>
      <main id="main-content" className="layout__main">
        <Suspense fallback={<div aria-busy="true" role="status">加载中…</div>}>
          <Routes>
            <Route path="/" element={<MeetingsPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/meetings" element={<MeetingsPage />} />
            <Route path="/meetings/new" element={<MeetingWorkstationRoute />} />
            <Route path="/meetings/:meetingId" element={<MeetingWorkstationRoute />} />
            <Route path="/enrollment" element={<EnrollmentPage />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  );
}
