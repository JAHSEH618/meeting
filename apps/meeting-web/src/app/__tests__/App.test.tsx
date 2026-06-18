import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, within } from "@testing-library/react";
import { useAuthStore } from "@shared/stores/auth";
import { App } from "../App";

function signInForShell() {
  useAuthStore.setState({
    ready: true,
    user: {
      userId: "user_01",
      tenantId: "tenant_01",
      displayName: "测试用户",
      roles: ["admin"],
      permissions: ["meeting:create", "meeting:read"],
    },
  });
}

describe("App shell", () => {
  it("keeps non-release compliance features hidden from the main navigation", () => {
    signInForShell();

    render(
      <TestRouter initialEntries={["/meetings"]}>
        <App />
      </TestRouter>,
    );

    const rail = screen.getByLabelText("主导航");
    expect(within(rail).getByText("工作")).toBeInTheDocument();
    expect(within(rail).getByRole("link", { name: "会议" })).toBeInTheDocument();
    expect(within(rail).getByRole("link", { name: "文档" })).toBeInTheDocument();
    expect(within(rail).getByRole("link", { name: "问答" })).toBeInTheDocument();
    expect(within(rail).getByRole("link", { name: "声纹档案" })).toBeInTheDocument();

    expect(within(rail).queryByText("合规")).not.toBeInTheDocument();
    expect(within(rail).queryByRole("link", { name: "法律保留" })).not.toBeInTheDocument();
    expect(within(rail).queryByRole("link", { name: "删除任务" })).not.toBeInTheDocument();
    expect(within(rail).queryByRole("link", { name: "应急访问" })).not.toBeInTheDocument();
    expect(within(rail).queryByRole("link", { name: "审计" })).not.toBeInTheDocument();
  });
});
