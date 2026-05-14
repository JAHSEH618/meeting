import { describe, expect, it } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { SpeakerProfilesPage } from "../SpeakerProfilesPage";

describe("SpeakerProfilesPage", () => {
  it("lists existing speaker profiles with consent status", async () => {
    render(
      <MemoryRouter>
        <SpeakerProfilesPage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText("Alice 张")).toBeInTheDocument());
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "撤销授权" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除档案" })).toBeInTheDocument();
  });

  it("opens the create form and accepts personId + displayName", async () => {
    render(
      <MemoryRouter>
        <SpeakerProfilesPage />
      </MemoryRouter>,
    );

    await screen.findByText("Alice 张");
    fireEvent.click(screen.getByRole("button", { name: "新建档案" }));

    expect(screen.getByPlaceholderText("alice")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("例如 Alice 张")).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText("alice"), { target: { value: "bob" } });
    fireEvent.change(screen.getByPlaceholderText("例如 Alice 张"), { target: { value: "Bob 李" } });
    fireEvent.click(screen.getByRole("button", { name: "创建" }));

    await waitFor(() => expect(screen.queryByRole("button", { name: "创建" })).not.toBeInTheDocument());
  });
});
