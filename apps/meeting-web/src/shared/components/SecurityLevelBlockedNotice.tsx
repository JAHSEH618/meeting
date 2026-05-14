import type { ReactNode } from "react";

const SECURITY_BLOCKED_TITLE = "LLM 已被安全策略阻断";
export const SECURITY_BLOCKED_MESSAGE = "一期不支持该安全等级的自动 LLM 处理";

export interface SecurityLevelBlockedNoticeProps {
  securityLevel?: string | null;
  blockedCapability?: string | null;
  children?: ReactNode;
}

/**
 * Stable, phase-1 business notice for SECURITY_LEVEL_BLOCKED responses.
 * The message text is fixed by spec and must not be parameterized — only the
 * supporting metadata (level, capability) varies. Use this anywhere an LLM-dependent
 * action surfaces the SECURITY_LEVEL_BLOCKED error.
 */
export function SecurityLevelBlockedNotice({ securityLevel, blockedCapability, children }: SecurityLevelBlockedNoticeProps) {
  return (
    <section
      className="card stack"
      role="status"
      aria-live="polite"
      data-testid="security-level-blocked-notice"
    >
      <strong>{SECURITY_BLOCKED_TITLE}</strong>
      <span className="muted">{SECURITY_BLOCKED_MESSAGE}</span>
      <div className="toolbar">
        {securityLevel ? (
          <span className="badge" data-security-level={securityLevel}>{securityLevel}</span>
        ) : null}
        {blockedCapability ? (
          <span className="muted">能力 {blockedCapability}</span>
        ) : null}
      </div>
      {children}
    </section>
  );
}
