import { Suspense, lazy } from "react";
import { Routes, Route, Navigate, NavLink, Outlet, Link } from "react-router-dom";
import { AuthGuard } from "./AuthGuard";
import { SkipLink } from "@shared/components/SkipLink";
import "./app.css";
import { LoginPage } from "@features/auth/LoginPage";
import { useAuth } from "@services/auth";
import { MeetingListPage } from "@features/meetings/MeetingListPage";
import { MeetingCreatePage } from "@features/meetings/MeetingCreatePage";
import { MeetingDetailPage } from "@features/meetings/MeetingDetailPage";

const AudioUploadPage = lazy(() =>
  import("@features/audio/AudioUploadPage").then((m) => ({ default: m.AudioUploadPage })),
);
const TranscriptPage = lazy(() =>
  import("@features/transcript/TranscriptPage").then((m) => ({ default: m.TranscriptPage })),
);
const MinutesPage = lazy(() =>
  import("@features/minutes/MinutesPage").then((m) => ({ default: m.MinutesPage })),
);
const ItemsPage = lazy(() =>
  import("@features/items/ItemsPage").then((m) => ({ default: m.ItemsPage })),
);
const RagPage = lazy(() =>
  import("@features/rag/RagPage").then((m) => ({ default: m.RagPage })),
);
const SpeakerProfilesPage = lazy(() =>
  import("@features/speakers/SpeakerProfilesPage").then((m) => ({
    default: m.SpeakerProfilesPage,
  })),
);
const MeetingSpeakerConfirmPage = lazy(() =>
  import("@features/speakers/MeetingSpeakerConfirmPage").then((m) => ({
    default: m.MeetingSpeakerConfirmPage,
  })),
);
const DocumentsPage = lazy(() =>
  import("@features/documents/DocumentsPage").then((m) => ({ default: m.DocumentsPage })),
);
const ExportsPage = lazy(() =>
  import("@features/exports/ExportsPage").then((m) => ({ default: m.ExportsPage })),
);
const LegalHoldsPage = lazy(() =>
  import("@features/admin/LegalHoldsPage").then((m) => ({ default: m.LegalHoldsPage })),
);
const DeletionJobsPage = lazy(() =>
  import("@features/admin/DeletionJobsPage").then((m) => ({ default: m.DeletionJobsPage })),
);
const BreakGlassPage = lazy(() =>
  import("@features/admin/BreakGlassPage").then((m) => ({ default: m.BreakGlassPage })),
);
const AuditEventsPage = lazy(() =>
  import("@features/admin/AuditEventsPage").then((m) => ({ default: m.AuditEventsPage })),
);
const TaskProgressPage = lazy(() =>
  import("@features/tasks/TaskProgressPage").then((m) => ({ default: m.TaskProgressPage })),
);

const RouteFallback = () => (
  <div className="page" aria-busy="true" role="status" aria-live="polite">
    加载中…
  </div>
);

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<AuthGuard />}>
        <Route element={<Shell />}>
          <Route path="/" element={<Navigate to="/meetings" replace />} />
          <Route path="/meetings" element={<MeetingListPage />} />
          <Route path="/meetings/new" element={<MeetingCreatePage />} />
          <Route path="/meetings/:meetingId" element={<MeetingDetailPage />} />
          <Route
            path="/meetings/:meetingId/audio"
            element={<Suspense fallback={<RouteFallback />}><AudioUploadPage /></Suspense>}
          />
          <Route
            path="/meetings/:meetingId/tasks/:taskId"
            element={<Suspense fallback={<RouteFallback />}><TaskProgressPage /></Suspense>}
          />
          <Route
            path="/meetings/:meetingId/transcript"
            element={<Suspense fallback={<RouteFallback />}><TranscriptPage /></Suspense>}
          />
          <Route
            path="/meetings/:meetingId/minutes"
            element={<Suspense fallback={<RouteFallback />}><MinutesPage /></Suspense>}
          />
          <Route
            path="/meetings/:meetingId/items"
            element={<Suspense fallback={<RouteFallback />}><ItemsPage /></Suspense>}
          />
          <Route
            path="/meetings/:meetingId/speakers"
            element={<Suspense fallback={<RouteFallback />}><MeetingSpeakerConfirmPage /></Suspense>}
          />
          <Route
            path="/meetings/:meetingId/exports"
            element={<Suspense fallback={<RouteFallback />}><ExportsPage /></Suspense>}
          />
          <Route
            path="/rag"
            element={<Suspense fallback={<RouteFallback />}><RagPage /></Suspense>}
          />
          <Route
            path="/speaker-profiles"
            element={<Suspense fallback={<RouteFallback />}><SpeakerProfilesPage /></Suspense>}
          />
          <Route
            path="/documents"
            element={<Suspense fallback={<RouteFallback />}><DocumentsPage /></Suspense>}
          />
          <Route
            path="/admin/legal-holds"
            element={<Suspense fallback={<RouteFallback />}><LegalHoldsPage /></Suspense>}
          />
          <Route
            path="/admin/deletion-jobs"
            element={<Suspense fallback={<RouteFallback />}><DeletionJobsPage /></Suspense>}
          />
          <Route
            path="/admin/break-glass"
            element={<Suspense fallback={<RouteFallback />}><BreakGlassPage /></Suspense>}
          />
          <Route
            path="/admin/audit-events"
            element={<Suspense fallback={<RouteFallback />}><AuditEventsPage /></Suspense>}
          />
          <Route path="/speakers" element={<Navigate to="/speaker-profiles" replace />} />
          <Route path="/exports" element={<Navigate to="/meetings" replace />} />
        </Route>
      </Route>
    </Routes>
  );
}

function Shell() {
  const { user, logout } = useAuth();

  const handleLogout = async () => {
    if (confirm('确定要退出登录吗？')) {
      await logout();
    }
  };

  return (
    <div className="app-shell">
      <SkipLink />
      <aside className="shell__rail" aria-label="主导航">
        <div className="shell__brand">Meeting</div>
        <Link className="button button--primary shell__create" to="/meetings/new">新建会议</Link>

        <nav className="shell__rail-section" aria-labelledby="rail-work">
          <h3 id="rail-work">工作</h3>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/meetings"
          >
            会议
          </NavLink>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/documents"
          >
            文档
          </NavLink>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/rag"
          >
            问答
          </NavLink>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/speaker-profiles"
          >
            声纹档案
          </NavLink>
        </nav>

        <nav className="shell__rail-section" aria-labelledby="rail-compliance">
          <h3 id="rail-compliance">合规</h3>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/admin/legal-holds"
          >
            法律保留
          </NavLink>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/admin/deletion-jobs"
          >
            删除任务
          </NavLink>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/admin/break-glass"
          >
            应急访问
          </NavLink>
          <NavLink
            className={({ isActive }) => `shell__rail-link${isActive ? " active" : ""}`}
            to="/admin/audit-events"
          >
            审计
          </NavLink>
        </nav>

        {user && (
          <button
            className="logout-btn"
            onClick={handleLogout}
            title="退出登录"
          >
            退出
          </button>
        )}
      </aside>

      <main id="main-content" className="shell__main">
        <Outlet />
      </main>
    </div>
  );
}
