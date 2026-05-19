import { describe, expect, it, beforeEach } from "vitest";
import { authStore, consumeFragmentToken } from "@/shared/auth/store";

describe("authStore", () => {
  beforeEach(() => authStore.clear());

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
});
