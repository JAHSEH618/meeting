import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { MinutesPage } from "../MinutesPage";
import { server } from "@shared/api/mocks/server";
import type { ApiResponse } from "@shared/api/types";

describe("MinutesPage", () => {
  it("loads minutes and shows sections with evidence", async () => {
    render(
      <TestRouter initialEntries={["/meetings/mtg_01/minutes"]}>
        <Routes>
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        </Routes>
      </TestRouter>,
    );

    await waitFor(() => expect(screen.getByText("阶段二上线")).toBeInTheDocument());
    expect(screen.getByText("seg_01")).toBeInTheDocument();
  });

  it("clicks regenerate and replaces minutes with new version", async () => {
    render(
      <TestRouter initialEntries={["/meetings/mtg_01/minutes"]}>
        <Routes>
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        </Routes>
      </TestRouter>,
    );

    await screen.findByText("阶段二上线");
    fireEvent.click(screen.getByRole("button", { name: "重新生成纪要" }));

    await waitFor(() => expect(screen.getByText("纪要已重生成")).toBeInTheDocument());
    expect(screen.getByText(/v2/)).toBeInTheDocument();
  });

  it("shows the SECURITY_LEVEL_BLOCKED business prompt when LLM is blocked", async () => {
    server.use(
      http.post("/api/meetings/:meetingId/minutes/regenerate", () =>
        HttpResponse.json<ApiResponse>(
          {
            success: false,
            data: null,
            error: {
              code: "SECURITY_LEVEL_BLOCKED",
              message: "blocked",
              retryable: false,
              details: { securityLevel: "CONFIDENTIAL", blockedCapability: "MINUTES_SUMMARY" },
            },
            requestId: "req_blocked",
            traceId: "trace_blocked",
          },
          { status: 422 },
        ),
      ),
    );

    render(
      <TestRouter initialEntries={["/meetings/mtg_01/minutes"]}>
        <Routes>
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        </Routes>
      </TestRouter>,
    );

    await screen.findByText("阶段二上线");
    fireEvent.click(screen.getByRole("button", { name: "重新生成纪要" }));

    await waitFor(() =>
      expect(screen.getByText("一期不支持该安全等级的自动 LLM 处理")).toBeInTheDocument(),
    );
  });

  it("shows blocked banner when GET minutes returns SECURITY_LEVEL_BLOCKED", async () => {
    server.use(
      http.get("/api/meetings/:meetingId/minutes", () =>
        HttpResponse.json<ApiResponse>(
          {
            success: false,
            data: null,
            error: {
              code: "SECURITY_LEVEL_BLOCKED",
              message: "blocked",
              retryable: false,
              details: { securityLevel: "SECRET", blockedCapability: "MINUTES_SUMMARY" },
            },
            requestId: "req_blocked",
            traceId: "trace_blocked",
          },
          { status: 422 },
        ),
      ),
    );

    render(
      <TestRouter initialEntries={["/meetings/mtg_01/minutes"]}>
        <Routes>
          <Route path="/meetings/:meetingId/minutes" element={<MinutesPage />} />
        </Routes>
      </TestRouter>,
    );

    await waitFor(() =>
      expect(screen.getByText("一期不支持该安全等级的自动 LLM 处理")).toBeInTheDocument(),
    );
  });
});
