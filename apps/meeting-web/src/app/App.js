import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
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
    return (_jsxs(Routes, { children: [_jsx(Route, { path: "/login", element: _jsx(LoginPage, {}) }), _jsxs(Route, { element: _jsx(AuthGuard, {}), children: [_jsx(Route, { path: "/", element: _jsx(Navigate, { to: "/meetings", replace: true }) }), _jsx(Route, { path: "/meetings", element: _jsx(MeetingListPage, {}) }), _jsx(Route, { path: "/meetings/new", element: _jsx(MeetingCreatePage, {}) }), _jsx(Route, { path: "/meetings/:meetingId", element: _jsx(MeetingDetailPage, {}) }), _jsx(Route, { path: "/meetings/:meetingId/transcript", element: _jsx(TranscriptPage, {}) }), _jsx(Route, { path: "/meetings/:meetingId/minutes", element: _jsx(MinutesPage, {}) }), _jsx(Route, { path: "/rag", element: _jsx(RagPage, {}) }), _jsx(Route, { path: "/speakers", element: _jsx(SpeakerProfilesPage, {}) }), _jsx(Route, { path: "/documents", element: _jsx(DocumentsPage, {}) }), _jsx(Route, { path: "/exports", element: _jsx(ExportsPage, {}) })] })] }));
}
