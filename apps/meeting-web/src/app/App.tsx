import { Routes, Route, Navigate } from "react-router-dom";
import { AuthGuard } from "./AuthGuard";
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

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<AuthGuard />}>
        <Route path="/" element={<Navigate to="/meetings" replace />} />
        <Route path="/meetings" element={<MeetingListPage />} />
        <Route path="/meetings/new" element={<MeetingCreatePage />} />
        <Route path="/meetings/:meetingId" element={<MeetingDetailPage />} />
        <Route path="/meetings/:meetingId/transcript" element={<TranscriptPage />} />
        <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        <Route path="/rag" element={<RagPage />} />
        <Route path="/speakers" element={<SpeakerProfilesPage />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/exports" element={<ExportsPage />} />
      </Route>
    </Routes>
  );
}
