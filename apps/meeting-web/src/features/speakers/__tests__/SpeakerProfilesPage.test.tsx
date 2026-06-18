import { describe, expect, it } from "vitest";
import { TestRouter } from "@shared/test/TestRouter";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { SpeakerProfilesPage } from "../SpeakerProfilesPage";

describe("SpeakerProfilesPage", () => {
  it("lists existing speaker profiles with consent status", async () => {
    render(
      <TestRouter>
        <SpeakerProfilesPage />
      </TestRouter>,
    );

    await waitFor(() => expect(screen.getByText("Alice 张")).toBeInTheDocument());
    expect(screen.getByText("已授权")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "撤销授权" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除档案" })).toBeInTheDocument();
  });

  it("opens the create form and accepts personId + displayName", async () => {
    render(
      <TestRouter>
        <SpeakerProfilesPage />
      </TestRouter>,
    );

    await screen.findByText("Alice 张");
    fireEvent.click(screen.getByRole("button", { name: "新建档案" }));

    expect(screen.getByText("2 到 64 位，支持中文、英文、数字、点、下划线和短横线，不允许空格。")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("例：EMP-001 / zhangsan / 张三01")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("例如 Alice 张")).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("例：EMP-001 / zhangsan / 张三01"), { target: { value: "bob" } });
    fireEvent.change(screen.getByPlaceholderText("例如 Alice 张"), { target: { value: "Bob 李" } });
    fireEvent.click(screen.getByRole("button", { name: "创建" }));

    await waitFor(() => expect(screen.queryByRole("button", { name: "创建" })).not.toBeInTheDocument());
  });

  it("explains and enforces the person id input rule", async () => {
    render(
      <TestRouter>
        <SpeakerProfilesPage />
      </TestRouter>,
    );

    await screen.findByText("Alice 张");
    fireEvent.click(screen.getByRole("button", { name: "新建档案" }));

    fireEvent.change(screen.getByLabelText("人员编号"), { target: { value: "bob 01" } });
    fireEvent.change(screen.getByLabelText("显示名"), { target: { value: "Bob 李" } });
    fireEvent.click(screen.getByRole("button", { name: "创建" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "人员编号格式不正确：2 到 64 位，只能包含中文、英文、数字、点、下划线和短横线",
    );
    expect(screen.getByRole("button", { name: "创建" })).toBeInTheDocument();
  });
});
