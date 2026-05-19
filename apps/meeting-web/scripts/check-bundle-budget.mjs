// Bundle budget guard referenced by docs/runbooks/phase-j-acceptance.md (J3).
//
// Reads a `vite-bundle-visualizer --output-format json` report (which is
// the raw `rollup-plugin-visualizer` schema: `{ version, tree, nodeParts,
// nodeMetas, env }`) and asserts that the total gzipped size of all
// rendered modules is at or below the supplied byte budget.
//
// Usage:
//   node scripts/check-bundle-budget.mjs <bundle-report.json> <max-bytes>
//
// Exits 0 when the budget holds, 1 when it is breached, 2 on bad usage
// or unknown JSON shape. The intent is a CI guard, so the failure path
// is the loud one — every overrun prints the top 10 modules by gzip
// size to make the regression easy to attribute.
//
// "First-screen" approximation: this sums every module the build
// emitted. Vite already route-splits via dynamic `import()`, so most
// non-entry code lives in lazy chunks that don't count against the
// initial download in practice. Tightening this to walk only entry
// chunks would need rollup-plugin-visualizer's `nodeMetas[*].isEntry`
// flag, which is set per-module not per-chunk — overkill for an SPA
// where the entry chunk dominates. Revisit when route-level lazy
// splitting reaches >40 % of bytes.

import { readFileSync } from "node:fs";
import { exit } from "node:process";

const args = process.argv.slice(2);
if (args.length !== 2) {
    console.error("usage: check-bundle-budget.mjs <bundle-report.json> <max-bytes>");
    exit(2);
}
const [reportPath, budgetArg] = args;
const budget = Number.parseInt(budgetArg, 10);
if (!Number.isFinite(budget) || budget <= 0) {
    console.error(`invalid budget '${budgetArg}' — expected a positive integer (bytes)`);
    exit(2);
}

let report;
try {
    report = JSON.parse(readFileSync(reportPath, "utf8"));
} catch (err) {
    console.error(`failed to read ${reportPath}: ${err.message}`);
    exit(2);
}

if (!report || typeof report !== "object" || !report.tree || !report.nodeParts) {
    console.error(
        `unexpected JSON shape in ${reportPath} — expected fields {tree, nodeParts}\n` +
        `(was the report produced by 'vite-bundle-visualizer --output-format json'?)`
    );
    exit(2);
}

function walkLeaves(node, sink) {
    if (!node) return;
    if (Array.isArray(node.children) && node.children.length > 0) {
        for (const child of node.children) walkLeaves(child, sink);
        return;
    }
    sink(node);
}

const parts = report.nodeParts;
const modules = [];
let total = 0;

walkLeaves(report.tree, (leaf) => {
    if (!leaf.uid) return;
    const part = parts[leaf.uid];
    if (!part) return;
    const gzip = typeof part.gzipLength === "number" ? part.gzipLength : 0;
    total += gzip;
    modules.push({ name: leaf.name ?? leaf.uid, gzip });
});

const formatBytes = (n) => {
    if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(2)} MiB`;
    if (n >= 1024) return `${(n / 1024).toFixed(1)} KiB`;
    return `${n} B`;
};

console.log("== bundle budget check ==");
console.log(`report:   ${reportPath}`);
console.log(`budget:   ${budget} bytes (${formatBytes(budget)})`);
console.log(`measured: ${total} bytes (${formatBytes(total)})`);
console.log("");

modules.sort((a, b) => b.gzip - a.gzip);
console.log("top 10 modules by gzip size:");
for (const m of modules.slice(0, 10)) {
    console.log(`  ${String(m.gzip).padStart(8)}  ${m.name}`);
}

if (total > budget) {
    console.error(
        `\nFAIL: gzip total ${total} B exceeds budget ${budget} B ` +
        `(over by ${formatBytes(total - budget)})`
    );
    exit(1);
}
console.log(`\nPASS`);
