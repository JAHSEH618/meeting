import type { ReactNode } from "react";

interface Props {
  to?: string;
  children?: ReactNode;
}

export function SkipLink({ to = "#main-content", children = "跳到主内容" }: Props) {
  return (
    <a className="skip-link" href={to}>
      {children}
    </a>
  );
}
