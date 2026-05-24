import { describe, expect, it } from "vitest";

import { Sha256, sha256Hex, toHex } from "../sha256-stream";

const TEST_VECTORS: Array<{ input: string; expected: string }> = [
  // FIPS 180-2 §B.1
  { input: "abc", expected: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" },
  // FIPS 180-2 §B.2
  {
    input: "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
    expected: "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
  },
  { input: "", expected: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" },
];

describe("Sha256 streaming hasher", () => {
  it.each(TEST_VECTORS)("matches FIPS test vector for $expected", ({ input, expected }) => {
    const hasher = new Sha256();
    hasher.update(new TextEncoder().encode(input));
    expect(toHex(hasher.digest())).toBe(expected);
  });

  it("produces the same digest regardless of chunk boundary", async () => {
    // 1 MiB of pseudo-random-but-deterministic bytes spanning multiple
    // 64-byte SHA-256 block boundaries.
    const payload = new Uint8Array(1024 * 1024);
    for (let i = 0; i < payload.length; i += 1) payload[i] = (i * 31 + 7) & 0xff;
    const oneShot = new Sha256();
    oneShot.update(payload);
    const expected = toHex(oneShot.digest());

    // Mid-block boundaries (note: 1024*1024 = 1048576 is not divisible by 73)
    const chunked = new Sha256();
    for (let off = 0; off < payload.length; off += 73) {
      chunked.update(payload.subarray(off, Math.min(payload.length, off + 73)));
    }
    expect(toHex(chunked.digest())).toBe(expected);

    // Streaming Blob helper, with a chunkSize that crosses block boundaries.
    const blob = new Blob([payload]);
    expect(await sha256Hex(blob, 7919)).toBe(expected);
  });

  it("refuses update after digest()", () => {
    const hasher = new Sha256();
    hasher.update(new TextEncoder().encode("abc"));
    hasher.digest();
    expect(() => hasher.update(new TextEncoder().encode("x"))).toThrow(/update.*digest/);
    expect(() => hasher.digest()).toThrow(/digest.*twice/);
  });

  it("matches subtle.digest on a non-trivial payload (sha256Hex Blob helper)", async () => {
    const payload = new TextEncoder().encode("hello world\n".repeat(10000));
    const blob = new Blob([payload]);
    const ours = await sha256Hex(blob, 4096);
    const reference = await globalThis.crypto.subtle.digest("SHA-256", payload);
    const referenceHex = Array.from(new Uint8Array(reference))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    expect(ours).toBe(referenceHex);
  });
});
