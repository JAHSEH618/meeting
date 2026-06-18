import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

function readConnectSrcEntries() {
  const nginxConfig = readFileSync("nginx.conf", "utf8");
  const cspMatch = nginxConfig.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"/);

  if (!cspMatch) {
    throw new Error("Content-Security-Policy header is missing from nginx.conf");
  }

  const cspHeader = cspMatch[1];
  if (!cspHeader) {
    throw new Error("Content-Security-Policy header value is empty in nginx.conf");
  }

  const directives = cspHeader
    .split(";")
    .map((directive) => directive.trim())
    .filter(Boolean);
  const connectSrc = directives.find((directive) => directive.startsWith("connect-src "));

  if (!connectSrc) {
    throw new Error("connect-src directive is missing from nginx Content-Security-Policy");
  }

  return connectSrc.split(/\s+/).slice(1);
}

describe("production nginx Content-Security-Policy", () => {
  it("allows browser uploads to Aliyun OSS presigned URLs", () => {
    expect(readConnectSrcEntries()).toEqual(
      expect.arrayContaining([
        "'self'",
        "https://oss-cn-hangzhou.aliyuncs.com",
        "https://*.oss-cn-hangzhou.aliyuncs.com",
      ]),
    );
  });
});
