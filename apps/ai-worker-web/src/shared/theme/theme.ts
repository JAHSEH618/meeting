/**
 * Theme management: light / dark / follow-system.
 *
 * The resolved theme is stamped on `<html data-theme="light|dark">`, which
 * the token layer (`tokens.css`) uses to swap design tokens. index.html
 * contains a tiny inline script that stamps the attribute before first
 * paint (no flash); this module owns everything after that — toggling,
 * persistence, reacting to OS theme changes and cross-tab sync.
 */

export type ThemePreference = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";

/** Shared with meeting-web on purpose: both SPAs sit on one origin, so the
 * operator's choice follows them across the two consoles. */
export const THEME_STORAGE_KEY = "meeting.theme";

export function getThemePreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === "light" || stored === "dark") return stored;
  } catch {
    // Storage unavailable (private mode etc.) — fall through to system.
  }
  return "system";
}

export function resolveTheme(preference: ThemePreference): ResolvedTheme {
  if (preference === "light" || preference === "dark") return preference;
  return systemPrefersDark() ? "dark" : "light";
}

export function applyTheme(resolved: ResolvedTheme): void {
  document.documentElement.dataset.theme = resolved;
}

export function setThemePreference(preference: ThemePreference): void {
  try {
    if (preference === "system") {
      window.localStorage.removeItem(THEME_STORAGE_KEY);
    } else {
      window.localStorage.setItem(THEME_STORAGE_KEY, preference);
    }
  } catch {
    // Non-persistent is still usable for the current tab.
  }
  applyTheme(resolveTheme(preference));
}

/**
 * Apply the stored preference and keep it live: follow OS theme changes
 * while the preference is "system", and mirror changes made in another
 * same-origin tab. Call once at app boot.
 */
export function initTheme(): void {
  applyTheme(resolveTheme(getThemePreference()));

  const media = matchDarkMedia();
  if (media) {
    const onSystemChange = () => {
      if (getThemePreference() === "system") {
        applyTheme(resolveTheme("system"));
      }
    };
    // Older WebKit exposes only addListener.
    if (typeof media.addEventListener === "function") {
      media.addEventListener("change", onSystemChange);
    } else if (typeof media.addListener === "function") {
      media.addListener(onSystemChange);
    }
  }

  window.addEventListener("storage", (event) => {
    if (event.key === THEME_STORAGE_KEY) {
      applyTheme(resolveTheme(getThemePreference()));
    }
  });
}

function systemPrefersDark(): boolean {
  return matchDarkMedia()?.matches ?? false;
}

function matchDarkMedia(): MediaQueryList | null {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return null;
  return window.matchMedia("(prefers-color-scheme: dark)");
}
