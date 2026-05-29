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
  it("submits displayName and calls onCreated", async () => {
    const onCreated = vi.fn();
    const onClose = vi.fn();
    const createFn = vi.fn(async () => ({ ...match, personId: "p2", displayName: "王五" }));

    render(<PersonCreateModal open onClose={onClose} onCreated={onCreated} createFn={createFn} />);
    fireEvent.change(screen.getByLabelText(/姓名/), { target: { value: "王五" } });
    fireEvent.click(screen.getByRole("button", { name: /^创建$/ }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ personId: "p2" })));
    expect(createFn).toHaveBeenCalledWith({ displayName: "王五" });
    expect(onClose).toHaveBeenCalledTimes(1);
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
});
