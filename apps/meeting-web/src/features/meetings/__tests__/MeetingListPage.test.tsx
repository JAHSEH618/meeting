import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@shared/api/mocks/server";
import { MeetingListPage } from "../MeetingListPage";
import type { ApiResponse, Meeting } from "@shared/api/types";

describe("MeetingListPage", () => {
  it("loads and renders meetings", async () => {
    const { container } = render(
      <TestRouter>
        <MeetingListPage />
      </TestRouter>,
    );

    expect(container.querySelector(".page--hero")).toBeInTheDocument();
    expect(container.querySelector(".page-hero")).toBeInTheDocument();
    expect(container.querySelector(".meeting-modules")).toBeInTheDocument();
    expect(container.querySelector(".glass-panel--table")).toBeInTheDocument();
    expect(screen.getByText("最近会议")).toBeInTheDocument();
    expect(screen.getByText("待启动处理")).toBeInTheDocument();
    expect(screen.getByText("快捷入口")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "打开最近会议" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "创建会议" })).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText("产品周会")).toBeInTheDocument());
    expect(screen.getByText("已创建")).toBeInTheDocument();
  });

  it("filters the meeting table when a status summary card is clicked", async () => {
    const meetings: Meeting[] = [
      {
        meetingId: "mtg_created",
        tenantId: "tenant_01",
        title: "待处理会议",
        status: "CREATED",
        language: "zh",
        transcriptVersion: 0,
        minutesVersion: 0,
        createdAt: "2026-05-11T09:00:00Z",
      },
      {
        meetingId: "mtg_processing",
        tenantId: "tenant_01",
        title: "正在处理会议",
        status: "PROCESSING",
        language: "zh",
        transcriptVersion: 1,
        minutesVersion: 0,
        createdAt: "2026-05-12T09:00:00Z",
      },
      {
        meetingId: "mtg_done",
        tenantId: "tenant_01",
        title: "已完成会议",
        status: "SUCCEEDED",
        language: "zh",
        transcriptVersion: 1,
        minutesVersion: 1,
        createdAt: "2026-05-13T09:00:00Z",
      },
    ];
    server.use(
      http.get("/api/meetings", () =>
        HttpResponse.json<ApiResponse<Meeting[]>>({
          success: true,
          data: meetings,
          error: null,
          requestId: "req_status_filter",
          traceId: "trace_status_filter",
        }),
      ),
    );

    render(
      <TestRouter>
        <MeetingListPage />
      </TestRouter>,
    );

    await screen.findByText("正在处理会议");
    fireEvent.click(screen.getByRole("button", { name: /处理中会议/ }));

    expect(screen.getByText("当前筛选：处理中")).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("正在处理会议")).toBeInTheDocument();
    expect(within(table).queryByText("待处理会议")).not.toBeInTheDocument();
    expect(within(table).queryByText("已完成会议")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "清除筛选" }));
    expect(within(table).getByText("待处理会议")).toBeInTheDocument();
    expect(within(table).getByText("已完成会议")).toBeInTheDocument();
  });
});
