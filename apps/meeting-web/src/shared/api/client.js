// API client — base fetch wrapper with auth token, envelope unwrap, error handling.
// Attaches X-Request-Id, X-Trace-Id, and Idempotency-Key on mutating requests.
const API_BASE = "/api";
let authToken = null;
export function setAuthToken(token) {
    authToken = token;
}
function generateId(prefix) {
    return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
}
async function request(method, path, body, idempotencyKey) {
    const headers = {
        "Content-Type": "application/json",
        Accept: "application/json",
        "X-Request-Id": generateId("req"),
        "X-Trace-Id": generateId("trace"),
    };
    if (authToken) {
        headers["Authorization"] = `Bearer ${authToken}`;
    }
    if (idempotencyKey && method !== "GET") {
        headers["Idempotency-Key"] = idempotencyKey;
    }
    const res = await fetch(`${API_BASE}${path}`, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
    });
    const json = await res.json();
    if (!json.success) {
        const err = json.error;
        const error = new Error(err.message);
        error.code = err.code;
        error.retryable = err.retryable;
        error.details = err.details;
        throw error;
    }
    return json.data;
}
// ── Auth ───────────────────────────────────────────────────────────
export async function login(username, password) {
    return request("POST", "/auth/login", { username, password });
}
export async function logout() {
    return request("POST", "/auth/logout");
}
export async function getCurrentUser() {
    return request("GET", "/auth/me");
}
// ── Meetings ───────────────────────────────────────────────────────
export async function createMeeting(data) {
    return request("POST", "/meetings", data, generateId("create-meeting"));
}
export async function listMeetings() {
    return request("GET", "/meetings");
}
export async function getMeeting(meetingId) {
    return request("GET", `/meetings/${meetingId}`);
}
// ── Tasks ──────────────────────────────────────────────────────────
export async function createProcessingTask(meetingId, audioFileId) {
    return request("POST", `/meetings/${meetingId}/processing-tasks`, { taskType: "MEETING_FULL_PIPELINE", audioFileId }, generateId("create-task"));
}
export async function getTask(taskId) {
    return request("GET", `/processing-tasks/${taskId}`);
}
// ── Transcript ─────────────────────────────────────────────────────
export async function getTranscript(meetingId) {
    return request("GET", `/meetings/${meetingId}/transcript`);
}
export async function updateSegment(meetingId, segmentId, editedText, expectedTranscriptVersion) {
    return request("PATCH", `/meetings/${meetingId}/transcript/segments/${segmentId}`, { expectedTranscriptVersion, editedText }, generateId("edit-segment"));
}
// ── Minutes ────────────────────────────────────────────────────────
export async function getMinutes(meetingId) {
    return request("GET", `/meetings/${meetingId}/minutes`);
}
// ── RAG ────────────────────────────────────────────────────────────
export async function ragQuery(data) {
    return request("POST", "/rag/query", data);
}
