import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api/client";
import { PersonCreateModal } from "./PersonCreateModal";
import type { PersonDTO } from "@/shared/api/types";

const match: PersonDTO = {
  personId: "p1",
  displayName: "李四",
  email: "li@example.com",
  externalId: null,
  createdAt: "2026-05-27T00:00:00Z",
};

describe("PersonCreateModal", () => {
  it("uses typed and named inputs with appropriate autocomplete", () => {
    render(<PersonCreateModal open onClose={vi.fn()} onCreated={vi.fn()} createFn={vi.fn()} />);

    expect(screen.getByLabelText(/姓名/)).toHaveAttribute("name", "displayName");
    expect(screen.getByLabelText(/姓名/)).toHaveAttribute("autocomplete", "name");
    expect(screen.getByLabelText("人员编号")).toHaveAttribute("name", "externalId");
    expect(screen.getByLabelText("人员编号")).toHaveAttribute("autocomplete", "off");
    expect(screen.getByText("2 到 64 位，支持中文、英文、数字、点、下划线和短横线，不允许空格。")).toBeInTheDocument();
    expect(screen.getByLabelText(/邮箱/)).toHaveAttribute("name", "email");
    expect(screen.getByLabelText(/邮箱/)).toHaveAttribute("type", "email");
    expect(screen.getByLabelText(/邮箱/)).toHaveAttribute("autocomplete", "email");
    expect(screen.getByLabelText(/邮箱/)).toHaveAttribute("spellcheck", "false");
  });

  it("submits displayName and calls onCreated", async () => {
    const onCreated = vi.fn();
    const onClose = vi.fn();
    const createFn = vi.fn(async () => ({ ...match, personId: "p2", displayName: "王五" }));

    render(<PersonCreateModal open onClose={onClose} onCreated={onCreated} createFn={createFn} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "王五" } });
    fireEvent.change(screen.getByLabelText("人员编号"), { target: { value: "EMP-001" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ personId: "p2" })));
    expect(createFn).toHaveBeenCalledWith({ displayName: "王五", externalId: "EMP-001" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("blocks invalid person id before creating", async () => {
    const createFn = vi.fn();

    render(<PersonCreateModal open onClose={vi.fn()} onCreated={vi.fn()} createFn={createFn} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "王五" } });
    fireEvent.change(screen.getByLabelText("人员编号"), { target: { value: "EMP 001" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "人员编号格式不正确：2 到 64 位，只能包含中文、英文、数字、点、下划线和短横线",
    );
    expect(createFn).not.toHaveBeenCalled();
  });

  it("shows duplicate matches and lets the user choose existing", async () => {
    const onCreated = vi.fn();
    const createFn = vi.fn(async () => {
      throw new ApiError(409, {
        code: "PERSON_DUPLICATE",
        message: "duplicate",
        retryable: false,
        details: { matches: [match] },
      }, "r", "t");
    });

    render(<PersonCreateModal open onClose={vi.fn()} onCreated={onCreated} createFn={createFn} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await waitFor(() => expect(screen.getByText(/已存在/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /使用已有/ }));
    expect(onCreated).toHaveBeenCalledWith(match);
  });

  it("force creates after duplicate confirmation", async () => {
    const onCreated = vi.fn();
    let first = true;
    const createFn = vi.fn(async (req) => {
      if (first) {
        first = false;
        throw new ApiError(409, {
          code: "PERSON_DUPLICATE",
          message: "duplicate",
          retryable: false,
          details: { matches: [match] },
        }, "r", "t");
      }
      return { ...match, personId: "p2", displayName: req.displayName };
    });

    render(<PersonCreateModal open onClose={vi.fn()} onCreated={onCreated} createFn={createFn} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));
    await waitFor(() => expect(screen.getByText(/已存在/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /仍创建新的/ }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ personId: "p2" })));
    expect(createFn).toHaveBeenLastCalledWith({ displayName: "李四", forceCreate: true });
  });

  it("allows a normal retry after editing email from duplicate state", async () => {
    const onCreated = vi.fn();
    let first = true;
    const createFn = vi.fn(async (req) => {
      if (first) {
        first = false;
        throw new ApiError(409, {
          code: "PERSON_DUPLICATE",
          message: "duplicate",
          retryable: false,
          details: { matches: [match] },
        }, "r", "t");
      }
      return { ...match, personId: "p2", displayName: req.displayName, email: req.email ?? null };
    });

    render(<PersonCreateModal open onClose={vi.fn()} onCreated={onCreated} createFn={createFn} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.change(screen.getByLabelText(/邮箱/), { target: { value: "li@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));
    await waitFor(() => expect(screen.getByText(/已存在/)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/邮箱/), { target: { value: "li-new@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ personId: "p2" })));
    expect(createFn).toHaveBeenLastCalledWith({ displayName: "李四", email: "li-new@example.com" });
  });

  it("resets form fields and duplicate matches when closed and reopened", async () => {
    const onClose = vi.fn();
    const createFn = vi.fn(async () => {
      throw new ApiError(409, {
        code: "PERSON_DUPLICATE",
        message: "duplicate",
        retryable: false,
        details: { matches: [match] },
      }, "r", "t");
    });
    const props = { onClose, onCreated: vi.fn(), createFn };
    const { rerender } = render(<PersonCreateModal open {...props} />);

    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "李四" } });
    fireEvent.change(screen.getByLabelText(/邮箱/), { target: { value: "li@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));
    await waitFor(() => expect(screen.getByText(/已存在/)).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    rerender(<PersonCreateModal open={false} {...props} />);
    rerender(<PersonCreateModal open {...props} />);

    expect(screen.getByLabelText(/姓名/)).toHaveValue("");
    expect(screen.getByLabelText("人员编号")).toHaveValue("");
    expect(screen.getByLabelText(/邮箱/)).toHaveValue("");
    expect(screen.queryByText(/已存在/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^创建$/ })).toBeDisabled();
  });
});
