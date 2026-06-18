import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Route, Routes } from "react-router-dom";
import { server } from "@shared/api/mocks/server";
import { MeetingCreatePage } from "../MeetingCreatePage";
import type { ApiResponse, Meeting } from "@shared/api/types";

describe("MeetingCreatePage", () => {
  it("selects participants from speaker profiles and sends their person ids", async () => {
    const requests: unknown[] = [];
    server.use(
      http.post("/api/meetings", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json<ApiResponse<Meeting>>({
          success: true,
          data: {
            meetingId: "mtg_new",
            tenantId: "tenant_01",
            title: "客户复盘",
            status: "CREATED",
            language: "zh",
            transcriptVersion: 0,
            minutesVersion: 0,
            createdAt: "2026-05-18T09:00:00Z",
          },
          error: null,
          requestId: "req_create_meeting",
          traceId: "trace_create_meeting",
        });
      }),
    );

    render(
      <TestRouter initialEntries={["/meetings/new"]}>
        <Routes>
          <Route path="/meetings/new" element={<MeetingCreatePage />} />
          <Route path="/meetings/:meetingId" element={<div>会议已创建</div>} />
        </Routes>
      </TestRouter>,
    );

    fireEvent.change(screen.getByLabelText("会议标题"), { target: { value: "客户复盘" } });

    const participantPanel = await screen.findByLabelText("参会人选择");
    fireEvent.click(within(participantPanel).getByRole("checkbox", { name: /Alice 张/ }));
    expect(screen.getByText("已选择 1 位参会人")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "创建会议" }));

    await screen.findByText("会议已创建");
    expect(requests).toHaveLength(1);
    expect(requests[0]).toMatchObject({
      title: "客户复盘",
      participants: [{ personId: "alice", displayName: "Alice 张", role: "participant" }],
    });
  });

  it("opens an inline speaker-profile enrollment flow for a new participant", async () => {
    render(
      <TestRouter initialEntries={["/meetings/new"]}>
        <Routes>
          <Route path="/meetings/new" element={<MeetingCreatePage />} />
        </Routes>
      </TestRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "新建参会人" }));
    fireEvent.change(screen.getByLabelText("人员编号"), { target: { value: "bob" } });
    fireEvent.change(screen.getByLabelText("显示名"), { target: { value: "Bob 李" } });
    fireEvent.click(screen.getByRole("button", { name: "创建声纹档案" }));

    const newProfile = await screen.findByText("Bob 李");
    expect(newProfile).toBeInTheDocument();
    expect(screen.getByText("添加参考音频")).toBeInTheDocument();
  });
});
