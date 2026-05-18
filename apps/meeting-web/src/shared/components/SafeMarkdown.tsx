import type { ComponentProps } from "react";
import { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import remarkGfm from "remark-gfm";

/**
 * SafeMarkdown — render untrusted markdown (LLM output, user-edited
 * minutes, RAG answers, evidence text) without exposing the page to
 * XSS.
 *
 * Defence layers, in order of execution:
 *   1. {@link ReactMarkdown} parses the markdown source (no raw HTML
 *      pass-through unless `rehypePlugins` allows it — we do allow it
 *      below, but only after sanitization).
 *   2. {@link rehypeSanitize} runs against {@link safeSchema}, an
 *      allow-list derived from {@code rehype-sanitize}'s default schema
 *      with these tightenings:
 *        - drop `<script>` / `<iframe>` / `<object>` / `<embed>` /
 *          `<style>` / `<form>` / `<input>` / `<meta>` (already excluded
 *          by default but re-asserted defensively).
 *        - strip every `on*` event handler attribute.
 *        - restrict `href` / `src` to {@code http(s):} or anchor refs;
 *          {@code javascript:}, {@code data:}, {@code vbscript:},
 *          {@code file:} and similar schemes are dropped.
 *        - drop `srcdoc` on iframes (iframes themselves are dropped
 *          but srcdoc would survive if a future schema relaxes the
 *          element ban).
 *   3. CSP at nginx (Phase 8.3.1) blocks any script that slipped past
 *      both layers from executing.
 *
 * Layer 3 is the last line of defence and should never be relied on
 * alone; the purpose of this component is to prevent the markup ever
 * reaching the browser in an executable form.
 *
 * Usage:
 *   <SafeMarkdown source={minutes.markdown} />
 *
 * Do NOT pass {@code rehypePlugins} or {@code remarkPlugins} from the
 * call site — that would let consumers reintroduce {@code rehype-raw}
 * (which bypasses sanitization). All plugin wiring lives here.
 */
export interface SafeMarkdownProps {
    /** Untrusted markdown source. Renders nothing when empty / null. */
    source: string | null | undefined;
    /** Optional className on the wrapping `<div>` (for layout / spacing). */
    className?: string;
    /** Optional aria-label for screen readers. */
    ariaLabel?: string;
}

const SAFE_URL_SCHEMES = ["http", "https", "mailto", "tel"];

/** Strict allow-list derived from rehype-sanitize's default schema. */
const safeSchema: Parameters<typeof rehypeSanitize>[0] = {
    ...defaultSchema,
    // Re-assert element ban — never let these survive even if upstream
    // defaults change.
    tagNames: (defaultSchema.tagNames ?? []).filter(
        (tag) =>
            ![
                "script",
                "iframe",
                "object",
                "embed",
                "style",
                "form",
                "input",
                "button",
                "textarea",
                "select",
                "option",
                "meta",
                "link",
                "base",
            ].includes(tag),
    ),
    attributes: {
        ...(defaultSchema.attributes ?? {}),
        // Globally drop event-handler attributes — defaultSchema already
        // does this, but we re-state with a hard-coded list so adding a
        // new sink doesn't silently relax the policy.
        "*": (defaultSchema.attributes?.["*"] ?? []).filter(
            (attr) => typeof attr !== "string" || !attr.toLowerCase().startsWith("on"),
        ),
    },
    protocols: {
        ...(defaultSchema.protocols ?? {}),
        href: SAFE_URL_SCHEMES,
        src: SAFE_URL_SCHEMES,
        cite: SAFE_URL_SCHEMES,
    },
    // No <iframe>, no srcdoc to worry about — but defensively forbid the
    // attribute everywhere.
    strip: ["srcdoc"],
};

const REHYPE_PLUGINS: ComponentProps<typeof ReactMarkdown>["rehypePlugins"] = [
    [rehypeSanitize, safeSchema],
];

const REMARK_PLUGINS: ComponentProps<typeof ReactMarkdown>["remarkPlugins"] = [
    remarkGfm,
];

export function SafeMarkdown({ source, className, ariaLabel }: SafeMarkdownProps) {
    const content = useMemo(() => source ?? "", [source]);
    if (!content.trim()) {
        return null;
    }
    return (
        <div className={className} aria-label={ariaLabel} data-testid="safe-markdown">
            <ReactMarkdown
                remarkPlugins={REMARK_PLUGINS}
                rehypePlugins={REHYPE_PLUGINS}
            >
                {content}
            </ReactMarkdown>
        </div>
    );
}
