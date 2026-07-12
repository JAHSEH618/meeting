import { useState } from "react";
import {
  getThemePreference,
  setThemePreference,
  type ThemePreference,
} from "./theme";

const NEXT: Record<ThemePreference, ThemePreference> = {
  system: "light",
  light: "dark",
  dark: "system",
};

const LABEL: Record<ThemePreference, string> = {
  system: "跟随系统",
  light: "浅色",
  dark: "深色",
};

const ICON: Record<ThemePreference, string> = {
  system: "◐",
  light: "☀",
  dark: "☾",
};

/** Cycles 跟随系统 → 浅色 → 深色. */
export function ThemeToggle({ className }: { className?: string }) {
  const [preference, setPreference] = useState<ThemePreference>(() => getThemePreference());

  const handleClick = () => {
    const next = NEXT[preference];
    setThemePreference(next);
    setPreference(next);
  };

  return (
    <button
      type="button"
      className={className ?? "theme-toggle"}
      onClick={handleClick}
      title={`主题：${LABEL[preference]}，点击切换`}
      aria-label={`主题：${LABEL[preference]}，点击切换`}
    >
      <span aria-hidden="true">{ICON[preference]}</span>
      <span className="theme-toggle__label">{LABEL[preference]}</span>
    </button>
  );
}
