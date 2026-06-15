import { describe, it, expect, beforeEach } from "vitest";
import { generateIdempotencyKey, generateStableIdempotencyKey, clearIdempotencyCache } from "../idempotency";

describe("generateIdempotencyKey", () => {
  it("should include the prefix", () => {
    const key = generateIdempotencyKey("test");
    expect(key.startsWith("test_")).toBe(true);
  });

  it("should produce unique keys across multiple calls", () => {
    const keys = new Set<string>();
    for (let i = 0; i < 100; i++) {
      keys.add(generateIdempotencyKey("test"));
    }
    expect(keys.size).toBe(100);
  });

  it("should increment counter in the suffix", () => {
    const key1 = generateIdempotencyKey("test");
    const key2 = generateIdempotencyKey("test");
    const counter1 = parseInt(key1.split("_").pop()!, 10);
    const counter2 = parseInt(key2.split("_").pop()!, 10);
    expect(counter2).toBe(counter1 + 1);
  });
});

describe('generateStableIdempotencyKey', () => {
  beforeEach(() => {
    clearIdempotencyCache();
  });

  it('generates unique keys for different actions', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1');
    const key2 = generateStableIdempotencyKey('update-meeting', 'user1');

    expect(key1).not.toBe(key2);
    expect(key1).toMatch(/^create-meeting_/);
    expect(key2).toMatch(/^update-meeting_/);
  });

  it('returns same key for same action and context', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx1');
    const key2 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx1');

    expect(key1).toBe(key2);
  });

  it('returns different keys for different contexts', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx1');
    const key2 = generateStableIdempotencyKey('create-meeting', 'user1', 'ctx2');

    expect(key1).not.toBe(key2);
  });

  it('clears cache on clearIdempotencyCache', () => {
    const key1 = generateStableIdempotencyKey('create-meeting', 'user1');
    clearIdempotencyCache();
    const key2 = generateStableIdempotencyKey('create-meeting', 'user1');

    expect(key1).not.toBe(key2);
  });
});
