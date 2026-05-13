import { Routes, Route, Navigate, NavLink, Outlet } from "react-router-dom";
import { AuthGuard } from "./AuthGuard";
import "./app.css";
import { LoginPage } from "@features/auth/LoginPage";
import { MeetingListPage } from "@features/meetings/MeetingListPage";
import { MeetingCreatePage } from "@features/meetings/MeetingCreatePage";
import { MeetingDetailPage } from "@features/meetings/MeetingDetailPage";
import { TranscriptPage } from "@features/transcript/TranscriptPage";
import { MinutesPage } from "@features/minutes/MinutesPage";
import { RagPage } from "@features/rag/RagPage";
import { SpeakerProfilesPage } from "@features/speakers/SpeakerProfilesPage";
import { DocumentsPage } from "@features/documents/DocumentsPage";
import { ExportsPage } from "@features/exports/ExportsPage";
import { TaskProgressPage } from "@features/tasks/TaskProgressPage";

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
          <Route path="/meetings/:meetingId/tasks/:taskId" element={<TaskProgressPage />} />
          <Route path="/meetings/:meetingId/transcript" element={<TranscriptPage />} />
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
          <Route path="/meetings/:meetingId/exports" element={<ExportsPage />} />
          <Route path="/rag" element={<RagPage />} />
          <Route path="/speaker-profiles" element={<SpeakerProfilesPage />} />
          <Route path="/documents" element={<DocumentsPage />} />
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
        </nav>
      </header>
      <Outlet />
    </div>
  );
}
