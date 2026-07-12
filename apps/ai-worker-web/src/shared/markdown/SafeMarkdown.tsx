import type { ComponentProps } from "react";
import ReactMarkdown from "react-markdown";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
import remarkGfm from "remark-gfm";

/**
 * SafeMarkdown — render untrusted markdown (LLM-generated minutes) without
 * exposing the workstation to XSS.
 *
 * The sanitize schema mirrors meeting-web's hardened SafeMarkdown (the two
 * had forked, leaving this console with the weaker allow-list while
 * rendering the same LLM output):
 *   - re-assert the element ban (script/iframe/object/embed/style/form/…)
 *     even though defaultSchema excludes them, so an upstream default change
 *     can't silently relax the policy;
 *   - strip every `on*` event-handler attribute;
 *   - restrict `href` / `src` / `cite` to http(s)/mailto/tel — javascript:,
 *     data:, vbscript:, file: are dropped;
 *   - forbid `srcdoc` everywhere.
 *
 * Do NOT pass rehypePlugins/remarkPlugins from call sites — all plugin
 * wiring lives here so no consumer can reintroduce rehype-raw.
 */

const SAFE_URL_SCHEMES = ["http", "https", "mailto", "tel"];

const BANNED_TAGS = [
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
];

const safeSchema: Parameters<typeof rehypeSanitize>[0] = {
  ...defaultSchema,
  tagNames: (defaultSchema.tagNames ?? []).filter((tag) => !BANNED_TAGS.includes(tag)),
  attributes: {
    ...(defaultSchema.attributes ?? {}),
    "*": (defaultSchema.attributes?.["*"] ?? []).filter(
      (attr) => typeof attr !== "string" || !attr.toLowerCase().startsWith("on"),
    ),
    // target/rel are set by the link renderer below, not taken from input.
  },
  protocols: {
    ...(defaultSchema.protocols ?? {}),
    href: SAFE_URL_SCHEMES,
    src: SAFE_URL_SCHEMES,
    cite: SAFE_URL_SCHEMES,
  },
  strip: ["srcdoc"],
};

const REHYPE_PLUGINS: ComponentProps<typeof ReactMarkdown>["rehypePlugins"] = [
  [rehypeSanitize, safeSchema],
];

const REMARK_PLUGINS: ComponentProps<typeof ReactMarkdown>["remarkPlugins"] = [remarkGfm];

export interface SafeMarkdownProps {
  source: string;
  testId?: string;
}

export function SafeMarkdown({ source, testId }: SafeMarkdownProps) {
  return (
    <div className="markdown" data-testid={testId}>
      <ReactMarkdown
        remarkPlugins={REMARK_PLUGINS}
        rehypePlugins={REHYPE_PLUGINS}
        components={{
          a: ({ href, children, ...rest }) => (
            <a href={href} target="_blank" rel="noopener noreferrer" {...rest}>
              {children}
            </a>
          ),
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  );
}
