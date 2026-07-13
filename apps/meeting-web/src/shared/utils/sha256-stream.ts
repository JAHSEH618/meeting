// Pure-JS streaming SHA-256 (FIPS 180-4) for browsers/jsdom.
//
// Web Crypto's `crypto.subtle.digest` is one-shot and requires the full
// payload as an ArrayBuffer, which would force gigabyte-scale audio
// uploads into memory all at once. This helper hashes a Blob in fixed
// chunks (default 4 MiB), holding only one chunk of bytes at a time.
//
// The implementation is intentionally minimal — only what we need for
// `Sha256.update(Uint8Array)` + `Sha256.digest()` and the single
// `sha256Hex(blob)` convenience.

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
  0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
  0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
  0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
  0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
  0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
  0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
  0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
  0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

function rotr(x: number, n: number): number {
  return ((x >>> n) | (x << (32 - n))) >>> 0;
}

export class Sha256 {
  private readonly buffer = new Uint8Array(64);
  private bufferLen = 0;
  private bitLen = 0n;
  private readonly hash = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  private finalized = false;

  update(data: Uint8Array): void {
    if (this.finalized) {
      throw new Error("Sha256: update() called after digest()");
    }
    this.bitLen += BigInt(data.length) * 8n;
    let cursor = 0;
    if (this.bufferLen > 0) {
      const take = Math.min(64 - this.bufferLen, data.length);
      this.buffer.set(data.subarray(0, take), this.bufferLen);
      this.bufferLen += take;
      cursor = take;
      if (this.bufferLen === 64) {
        this.processBlock(this.buffer, 0);
        this.bufferLen = 0;
      }
    }
    while (cursor + 64 <= data.length) {
      this.processBlock(data, cursor);
      cursor += 64;
    }
    if (cursor < data.length) {
      const remaining = data.length - cursor;
      this.buffer.set(data.subarray(cursor, cursor + remaining), 0);
      this.bufferLen = remaining;
    }
  }

  digest(): Uint8Array {
    if (this.finalized) {
      throw new Error("Sha256: digest() called twice");
    }
    this.finalized = true;
    const bitLen = this.bitLen;
    this.buffer[this.bufferLen] = 0x80;
    this.bufferLen += 1;
    if (this.bufferLen > 56) {
      this.buffer.fill(0, this.bufferLen, 64);
      this.processBlock(this.buffer, 0);
      this.bufferLen = 0;
    }
    this.buffer.fill(0, this.bufferLen, 56);
    const view = new DataView(this.buffer.buffer, this.buffer.byteOffset, 64);
    view.setBigUint64(56, bitLen, false);
    this.processBlock(this.buffer, 0);

    const out = new Uint8Array(32);
    const outView = new DataView(out.buffer);
    for (let i = 0; i < 8; i += 1) {
      outView.setUint32(i * 4, this.hash[i]!, false);
    }
    return out;
  }

  private processBlock(source: Uint8Array, offset: number): void {
    const view = new DataView(source.buffer, source.byteOffset + offset, 64);
    const w = new Uint32Array(64);
    for (let i = 0; i < 16; i += 1) {
      w[i] = view.getUint32(i * 4, false);
    }
    for (let i = 16; i < 64; i += 1) {
      const x = w[i - 15]!;
      const y = w[i - 2]!;
      const s0 = rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3);
      const s1 = rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10);
      w[i] = (w[i - 16]! + s0 + w[i - 7]! + s1) >>> 0;
    }
    let a = this.hash[0]!;
    let b = this.hash[1]!;
    let c = this.hash[2]!;
    let d = this.hash[3]!;
    let e = this.hash[4]!;
    let f = this.hash[5]!;
    let g = this.hash[6]!;
    let h = this.hash[7]!;
    for (let i = 0; i < 64; i += 1) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[i]! + w[i]!) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const mj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + mj) >>> 0;
      h = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    this.hash[0] = (this.hash[0]! + a) >>> 0;
    this.hash[1] = (this.hash[1]! + b) >>> 0;
    this.hash[2] = (this.hash[2]! + c) >>> 0;
    this.hash[3] = (this.hash[3]! + d) >>> 0;
    this.hash[4] = (this.hash[4]! + e) >>> 0;
    this.hash[5] = (this.hash[5]! + f) >>> 0;
    this.hash[6] = (this.hash[6]! + g) >>> 0;
    this.hash[7] = (this.hash[7]! + h) >>> 0;
  }
}

export function toHex(bytes: Uint8Array): string {
  let hex = "";
  for (let i = 0; i < bytes.length; i += 1) {
    hex += bytes[i]!.toString(16).padStart(2, "0");
  }
  return hex;
}

/**
 * Hash a Blob in fixed chunks (default 4 MiB) so that memory peak stays
 * bounded regardless of file size. Suitable for hashing multi-hundred-MiB
 * audio recordings without forcing the entire file into the JS heap.
 */
export async function sha256Hex(blob: Blob, chunkSize = 4 * 1024 * 1024): Promise<string> {
  const hasher = new Sha256();
  for (let offset = 0; offset < blob.size; offset += chunkSize) {
    const end = Math.min(blob.size, offset + chunkSize);
    const chunk = await readBlobAsUint8Array(blob.slice(offset, end));
    hasher.update(chunk);
  }
  return toHex(hasher.digest());
}

export async function readBlobAsUint8Array(blob: Blob): Promise<Uint8Array> {
  if (typeof (blob as Blob & { arrayBuffer?: () => Promise<ArrayBuffer> }).arrayBuffer === "function") {
    return new Uint8Array(await blob.arrayBuffer());
  }
  if (typeof FileReader !== "undefined") {
    return new Promise<Uint8Array>((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(reader.error ?? new Error("FileReader 读取失败"));
      reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
      reader.readAsArrayBuffer(blob);
    });
  }
  return new Uint8Array(await new Response(blob).arrayBuffer());
}
