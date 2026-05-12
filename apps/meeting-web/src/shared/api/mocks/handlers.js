import { http, HttpResponse } from "msw";
const meetingList = [
    {
        meetingId: "mtg_01",
        tenantId: "tenant_01",
        title: "产品周会",
        securityLevel: "INTERNAL",
        status: "CREATED",
        language: "zh",
        transcriptVersion: 0,
        minutesVersion: 0,
        createdAt: "2026-05-11T09:00:00Z",
    },
];
export const handlers = [
    http.post("/api/auth/login", () => {
        return HttpResponse.json({
            success: true,
            data: {
                accessToken: "mock-access-token",
                expiresAt: new Date(Date.now() + 3600000).toISOString(),
                user: {
                    userId: "user_01",
                    tenantId: "tenant_01",
                    displayName: "测试用户",
                    roles: ["admin"],
                    permissions: ["meeting:create", "meeting:read"],
                },
            },
            error: null,
            requestId: "req_01",
            traceId: "trace_01",
        });
    }),
    http.post("/api/auth/logout", () => {
        return HttpResponse.json({
            success: true,
            data: null,
            error: null,
            requestId: "req_02",
            traceId: "trace_02",
        });
    }),
    http.get("/api/auth/me", () => {
        return HttpResponse.json({
            success: true,
            data: {
                userId: "user_01",
                tenantId: "tenant_01",
                displayName: "测试用户",
                roles: ["admin"],
                permissions: ["meeting:create", "meeting:read"],
            },
            error: null,
            requestId: "req_03",
            traceId: "trace_03",
        });
    }),
    http.get("/api/meetings", () => {
        return HttpResponse.json({
            success: true,
            data: { items: meetingList },
            error: null,
            requestId: "req_04",
            traceId: "trace_04",
        });
    }),
    http.post("/api/meetings", () => {
        return HttpResponse.json({
            success: true,
            data: meetingList[0],
            error: null,
            requestId: "req_05",
            traceId: "trace_05",
        });
    }),
];
