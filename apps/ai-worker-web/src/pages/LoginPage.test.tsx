import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { authStore } from "@/shared/auth/store";
import { LoginPage } from "@/pages/LoginPage";

describe("LoginPage", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    authStore.clear();
    fetchMock.mockReset();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
  });

  afterEach(() => {
    authStore.clear();
  });

  it("posts credentials, stores token, and navigates to redirect", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({
        success: true,
        data: {
          accessToken: "jwt.header.signature",
          expiresAt: "2026-05-27T12:00:00Z",
          user: { userId: "user_admin", tenantId: "tenant_default", roles: ["ADMIN"] },
        },
        error: null,
        requestId: "r",
        traceId: "t",
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ));

    render(
      <MemoryRouter initialEntries={["/login?redirect=%2Fmeetings%2Fnew"]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/meetings/new" element={<div>new meeting page</div>} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => expect(screen.getByText("new meeting page")).toBeInTheDocument());
    expect(authStore.get()).toBe("jwt.header.signature");
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/login", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ username: "admin", password: "admin123" }),
    }));
  });

  it("renders the upstream error message", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({
        success: false,
        data: null,
        error: { code: "AUTH_REQUIRED", message: "invalid username or password", retryable: false },
        requestId: "r",
        traceId: "t",
      }),
      { status: 401, headers: { "Content-Type": "application/json" } },
    ));

    render(
      <MemoryRouter initialEntries={["/login"]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("invalid username or password");
    });
    expect(authStore.get()).toBeNull();
  });
});
