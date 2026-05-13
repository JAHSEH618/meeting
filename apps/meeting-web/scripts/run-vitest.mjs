import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const vitestPath = fileURLToPath(new URL("../node_modules/vitest/vitest.mjs", import.meta.url));
const env = { ...process.env };
const webStorageFlag = "--no-experimental-webstorage";

if (process.allowedNodeEnvironmentFlags.has(webStorageFlag)) {
  env.NODE_OPTIONS = [env.NODE_OPTIONS, webStorageFlag].filter(Boolean).join(" ");
}

const result = spawnSync(process.execPath, [vitestPath, ...process.argv.slice(2)], {
  env,
  stdio: "inherit",
});

process.exit(result.status ?? 1);
