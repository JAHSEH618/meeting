import "@testing-library/jest-dom";
import { beforeAll, afterAll, afterEach } from "vitest";
import { webcrypto } from "node:crypto";
import { transferableAbortController } from "node:util";
import { server } from "@shared/api/mocks/server";
import { resetAuthForTests } from "@services/auth";

if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, "crypto", {
    value: webcrypto,
    configurable: true,
  });
}

class NodeFetchAbortController {
  private readonly controller = transferableAbortController();

  get signal(): AbortSignal {
    return this.controller.signal as AbortSignal;
  }

  abort(reason?: unknown): void {
    this.controller.abort(reason);
  }
}

const nodeAbortSignal = transferableAbortController().signal.constructor;

Object.defineProperty(globalThis, "AbortController", {
  value: NodeFetchAbortController,
  configurable: true,
});

Object.defineProperty(globalThis, "AbortSignal", {
  value: nodeAbortSignal,
  configurable: true,
});

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => {
  server.resetHandlers();
  resetAuthForTests();
});
