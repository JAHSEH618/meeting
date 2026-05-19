import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useWizard } from "@/features/wizard/useWizard";

describe("useWizard", () => {
  it("starts at META by default", () => {
    const { result } = renderHook(() => useWizard());
    expect(result.current.step).toBe("META");
    expect(result.current.state.meetingId).toBeNull();
  });

  it("starts at AUDIO when meetingId is provided", () => {
    const { result } = renderHook(() => useWizard({ meetingId: "m_01" }));
    expect(result.current.step).toBe("AUDIO");
  });

  it("goNext advances through the canonical order", () => {
    const { result } = renderHook(() => useWizard());
    const seen: string[] = [result.current.step];
    for (let i = 0; i < result.current.order.length; i++) {
      act(() => result.current.goNext());
      seen.push(result.current.step);
    }
    expect(seen).toEqual(["META", "AUDIO", "GLOSSARY", "DOCUMENTS", "PROCESS", "SPEAKERS", "FINALIZE", "EXPORT", "EXPORT"]);
  });

  it("patch merges state immutably", () => {
    const { result } = renderHook(() => useWizard());
    act(() => result.current.patch({ meetingId: "m_42", startedProcessing: true }));
    expect(result.current.state.meetingId).toBe("m_42");
    expect(result.current.state.startedProcessing).toBe(true);
    expect(result.current.state.finalized).toBe(false);
  });

  it("goTo jumps to an arbitrary step", () => {
    const { result } = renderHook(() => useWizard());
    act(() => result.current.goTo("FINALIZE"));
    expect(result.current.step).toBe("FINALIZE");
  });
});
