import { describe, it, expect } from "vitest";
import { generateIdempotencyKey } from "../idempotency";

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
