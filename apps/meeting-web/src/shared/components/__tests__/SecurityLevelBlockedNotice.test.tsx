import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { SECURITY_BLOCKED_MESSAGE, SecurityLevelBlockedNotice } from "../SecurityLevelBlockedNotice";

describe("SecurityLevelBlockedNotice", () => {
  it("renders the fixed phase-1 business message", () => {
    render(<SecurityLevelBlockedNotice securityLevel="CONFIDENTIAL" blockedCapability="MINUTES_SUMMARY" />);
    expect(screen.getByText(SECURITY_BLOCKED_MESSAGE)).toBeInTheDocument();
    expect(screen.getByText(SECURITY_BLOCKED_MESSAGE)).toHaveTextContent(
      "一期不支持该安全等级的自动 LLM 处理",
    );
  });

  it("exposes the security level and capability for inspection", () => {
    render(<SecurityLevelBlockedNotice securityLevel="SECRET" blockedCapability="ITEM_EXTRACTION" />);
    expect(screen.getByText("SECRET")).toBeInTheDocument();
    expect(screen.getByText(/能力 ITEM_EXTRACTION/)).toBeInTheDocument();
  });

  it("omits supporting metadata when not provided", () => {
    render(<SecurityLevelBlockedNotice />);
    expect(screen.getByTestId("security-level-blocked-notice")).toBeInTheDocument();
    expect(screen.queryByText(/能力/)).not.toBeInTheDocument();
  });
});
