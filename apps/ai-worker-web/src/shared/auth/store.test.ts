import { describe, expect, it, beforeEach, vi } from "vitest";
import { authStore, consumeFragmentToken, redirectToLogin } from "@/shared/auth/store";

describe("authStore", () => {
  beforeEach(() => {
    authStore.clear();
    window.__WORKSTATION_CONFIG__ = undefined;
  });

  it("starts empty", () => {
    expect(authStore.get()).toBeNull();
  });

  it("stores in memory and notifies subscribers", () => {
    const seen: (string | null)[] = [];
    const unsub = authStore.subscribe((t) => seen.push(t));
    authStore.set("token-1");
    authStore.set("token-2");
    unsub();
    expect(seen).toEqual(["token-1", "token-2"]);
    expect(authStore.get()).toBe("token-2");
  });

  it("clear resets to null", () => {
    authStore.set("token-1");
    authStore.clear();
    expect(authStore.get()).toBeNull();
  });

  it("consumeFragmentToken reads access_token from fragment", () => {
    const consumed = consumeFragmentToken("#access_token=abc.def.ghi&state=x");
    expect(consumed).toBe(true);
    expect(authStore.get()).toBe("abc.def.ghi");
  });

  it("consumeFragmentToken ignores missing token", () => {
    expect(consumeFragmentToken("")).toBe(false);
    expect(consumeFragmentToken("#")).toBe(false);
    expect(consumeFragmentToken("#some=other")).toBe(false);
  });

  it("never writes to localStorage / sessionStorage", () => {
    authStore.set("never-persist");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("redirects to local workstation login when no external login URL is configured", () => {
    const assignSpy = vi.fn();
    const origLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...origLocation, assign: assignSpy, href: "http://127.0.0.1:5174/workstation/meetings" },
    });

    try {
      expect(redirectToLogin()).toBe(true);
      expect(assignSpy).toHaveBeenCalledWith(
        "/workstation/login?redirect=http%3A%2F%2F127.0.0.1%3A5174%2Fworkstation%2Fmeetings",
      );
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: origLocation });
    }
  });

  it("does not redirect when already on local workstation login", () => {
    const assignSpy = vi.fn();
    const origLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...origLocation, assign: assignSpy, href: "http://127.0.0.1:5174/workstation/login" },
    });

    try {
      expect(redirectToLogin()).toBe(false);
      expect(assignSpy).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: origLocation });
    }
  });

  it("redirects to runtime login URL when configured", () => {
    window.__WORKSTATION_CONFIG__ = { authLoginUrl: "https://login.example.test/workstation-login" };
    const assignSpy = vi.fn();
    const origLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...origLocation, assign: assignSpy, href: "http://127.0.0.1:5174/workstation/" },
    });

    try {
      expect(redirectToLogin()).toBe(true);
      expect(assignSpy).toHaveBeenCalledWith(
        "https://login.example.test/workstation-login?redirect=http%3A%2F%2F127.0.0.1%3A5174%2Fworkstation%2F",
      );
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: origLocation });
    }
  });
});
