import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SafeMarkdown } from "./SafeMarkdown";

describe("SafeMarkdown", () => {
  it("renders GFM headings and tables", () => {
    render(
      <SafeMarkdown
        source={"# 会议纪要\n\n| 项 | 值 |\n| --- | --- |\n| 主题 | KPI 回顾 |\n"}
      />,
    );
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("会议纪要");
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it("strips inline script tags", () => {
    const { container } = render(
      <SafeMarkdown source={"正文\n\n<script>window.__pwned=true</script>\n"} />,
    );
    expect(container.querySelector("script")).toBeNull();
    expect((globalThis as Record<string, unknown>).__pwned).toBeUndefined();
  });

  it("drops javascript: URLs", () => {
    render(
      <SafeMarkdown source={"[xss](javascript:alert(1))"} />,
    );
    const link = screen.queryByRole("link");
    if (link) {
      expect(link.getAttribute("href") ?? "").not.toContain("javascript:");
    }
  });

  it("opens external links with rel=noopener noreferrer", () => {
    render(<SafeMarkdown source={"[ok](https://example.com)"} />);
    const link = screen.getByRole("link", { name: "ok" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("renders fenced code blocks as <code>", () => {
    render(<SafeMarkdown source={"```\nhello\n```\n"} />);
    expect(screen.getByText("hello").tagName.toLowerCase()).toBe("code");
  });

  it("strips on* event handler attributes", () => {
    const { container } = render(
      <SafeMarkdown source={'点击<img src="https://example.com/x.png" onerror="window.__pwned=true" />'} />,
    );
    const img = container.querySelector("img");
    if (img) {
      expect(img.getAttribute("onerror")).toBeNull();
    }
    expect((globalThis as Record<string, unknown>).__pwned).toBeUndefined();
  });

  it("drops data: image sources", () => {
    const { container } = render(
      <SafeMarkdown source={"![x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)"} />,
    );
    const img = container.querySelector("img");
    if (img) {
      expect(img.getAttribute("src") ?? "").not.toContain("data:");
    }
  });

  it("strips form and input elements", () => {
    const { container } = render(
      <SafeMarkdown source={'<form action="https://evil.example"><input name="pw" /></form>'} />,
    );
    expect(container.querySelector("form")).toBeNull();
    expect(container.querySelector("input")).toBeNull();
  });

  it("strips iframe and srcdoc", () => {
    const { container } = render(
      <SafeMarkdown source={'<iframe srcdoc="<script>window.__pwned=true</script>"></iframe>'} />,
    );
    expect(container.querySelector("iframe")).toBeNull();
    expect((globalThis as Record<string, unknown>).__pwned).toBeUndefined();
  });
});
