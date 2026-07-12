import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  THEME_STORAGE_KEY,
  applyTheme,
  getThemePreference,
  initTheme,
  resolveTheme,
  setThemePreference,
} from "./theme";

type MediaListener = (event: { matches: boolean }) => void;

function stubMatchMedia(initialMatches: boolean) {
  const listeners: MediaListener[] = [];
  const media = {
    matches: initialMatches,
    addEventListener: (_type: string, listener: MediaListener) => listeners.push(listener),
    removeEventListener: () => undefined,
  };
  vi.stubGlobal("matchMedia", vi.fn(() => media));
  return {
    media,
    emitChange(matches: boolean) {
      media.matches = matches;
      listeners.forEach((listener) => listener({ matches }));
    },
  };
}

describe("theme", () => {
  beforeEach(() => {
    window.localStorage.clear();
    delete document.documentElement.dataset.theme;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("defaults to the system preference when nothing is stored", () => {
    expect(getThemePreference()).toBe("system");
  });

  it("resolves system preference from prefers-color-scheme", () => {
    stubMatchMedia(true);
    expect(resolveTheme("system")).toBe("dark");
    expect(resolveTheme("light")).toBe("light");
    expect(resolveTheme("dark")).toBe("dark");
  });

  it("stamps the resolved theme on the document root", () => {
    applyTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("persists explicit preferences and clears storage for system", () => {
    stubMatchMedia(false);
    setThemePreference("dark");
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");

    setThemePreference("system");
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
    expect(document.documentElement.dataset.theme).toBe("light");
  });

  it("follows OS theme changes while the preference is system", () => {
    const { emitChange } = stubMatchMedia(false);
    initTheme();
    expect(document.documentElement.dataset.theme).toBe("light");

    emitChange(true);
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("ignores OS theme changes once an explicit preference is set", () => {
    const { emitChange } = stubMatchMedia(false);
    initTheme();
    setThemePreference("light");

    emitChange(true);
    expect(document.documentElement.dataset.theme).toBe("light");
  });
});
