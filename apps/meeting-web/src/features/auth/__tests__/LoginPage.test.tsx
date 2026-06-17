import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { LoginPage } from "../LoginPage";

describe("LoginPage", () => {
  it("submits credentials and navigates to meetings", async () => {
    const { container } = render(
      <TestRouter initialEntries={["/login"]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/meetings" element={<div>meetings loaded</div>} />
        </Routes>
      </TestRouter>,
    );

    expect(container.querySelector(".auth-page")).toBeInTheDocument();
    expect(container.querySelector(".auth-card")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "本地会议智能系统" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => expect(screen.getByText("meetings loaded")).toBeInTheDocument());
  });
});
