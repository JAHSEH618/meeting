import { Suspense, lazy } from "react";
import { Routes, Route, Navigate, NavLink, Outlet } from "react-router-dom";
import { AuthGuard } from "./AuthGuard";
import "./app.css";
import { LoginPage } from "@features/auth/LoginPage";
import { MeetingListPage } from "@features/meetings/MeetingListPage";
import { MeetingCreatePage } from "@features/meetings/MeetingCreatePage";
import { MeetingDetailPage } from "@features/meetings/MeetingDetailPage";

// Heavy routes are loaded on demand so the first-screen bundle stays
// under the 200 KB gzip budget (web SPEC §6). Vite emits a separate
// chunk per lazy() call.
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
  <div className="route-fallback" aria-busy="true" role="status">加载中…</div>
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
  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">本地会议智能系统</div>
        <nav className="topnav" aria-label="主导航">
          <NavLink to="/meetings">会议</NavLink>
          <NavLink to="/documents">文档</NavLink>
          <NavLink to="/rag">RAG</NavLink>
          <NavLink to="/speaker-profiles">声纹档案</NavLink>
          <NavLink to="/admin/legal-holds">合规</NavLink>
        </nav>
      </header>
      <Outlet />
    </div>
  );
}
