import "@testing-library/jest-dom";
import { beforeAll, afterAll, afterEach } from "vitest";
import { webcrypto } from "node:crypto";
import { server } from "@shared/api/mocks/server";
import { resetAuthForTests } from "@services/auth";

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, "crypto", {
    value: webcrypto,
    configurable: true,
  });
}

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => {
  server.resetHandlers();
  resetAuthForTests();
});
