/**
 * Fail-closed lint: the public "provable privacy" claim must stay true to the code (tempdoc 633 #1d).
 *
 * The README / threat-model assert two mechanically-checkable properties:
 *   (1) the webview CSP pins network egress to loopback (no external origin can be reached), and
 *   (2) the shipped app declares no analytics/telemetry SDK, and
 *   (3) model-download source claims match the current registry.
 * Both are easy to break silently (a CSP edit that adds a host; a new dependency). This lint anchors the
 * claim to its source of truth so a drift fails the build rather than shipping a false privacy promise.
 *
 * Mirrors the shape of scripts/docs/check-frontend-stack-claims.mjs (repo-root detect, console report,
 * process.exitCode discipline) but checks a *positive config invariant* rather than forbidden prose.
 */

import fs from "node:fs";
import path from "node:path";

function repoRootFromCwd() {
  const cwd = process.cwd();
  const markers = ["settings.gradle.kts", "build.gradle.kts", ".git"];
  for (let dir = cwd; ; dir = path.dirname(dir)) {
    for (const m of markers) {
      if (fs.existsSync(path.join(dir, m))) return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return cwd;
}

// A connect-src token is permitted only if it is a loopback origin or one of Tauri's internal
// custom-protocol tokens. Anything else — a bare `*`, a scheme wildcard like `https:`/`http:`, `data:`,
// `blob:`, or an external host — is an egress hole and must fail (the drift class this lint exists to catch).
function isPermittedConnectToken(token) {
  if (token === "'self'" || token === "ipc:" || token === "http://ipc.localhost") return true;
  // Loopback origin with an optional :<port> or :* suffix (IPv4, localhost, or bracketed IPv6 ::1).
  return /^https?:\/\/(\[::1\]|127\.0\.0\.1|localhost)(:(\d+|\*))?$/i.test(token);
}

// Recursively find the first string value under a "csp" key.
function findCsp(node) {
  if (node == null) return null;
  if (Array.isArray(node)) {
    for (const v of node) {
      const r = findCsp(v);
      if (r) return r;
    }
    return null;
  }
  if (typeof node === "object") {
    for (const [k, v] of Object.entries(node)) {
      if (k === "csp" && typeof v === "string") return v;
      const r = findCsp(v);
      if (r) return r;
    }
  }
  return null;
}

function checkCsp(repoRoot, errors) {
  const confPath = path.join(repoRoot, "modules", "shell", "src-tauri", "tauri.conf.json");
  if (!fs.existsSync(confPath)) {
    errors.push(`tauri.conf.json not found at ${confPath} — cannot verify the CSP loopback-lock claim.`);
    return;
  }
  const csp = findCsp(JSON.parse(fs.readFileSync(confPath, "utf8")));
  if (!csp) {
    errors.push("No `csp` field found in tauri.conf.json — the privacy claim depends on it being present.");
    return;
  }
  const connectSrc = csp
    .split(";")
    .map((d) => d.trim())
    .find((d) => d.toLowerCase().startsWith("connect-src"));
  if (!connectSrc) {
    errors.push("CSP has no `connect-src` directive — egress is unconstrained; the loopback-lock claim is false.");
    return;
  }
  const tokens = connectSrc.split(/\s+/).slice(1); // drop "connect-src"
  if (!tokens.some((t) => t.includes("127.0.0.1"))) {
    errors.push(`CSP connect-src does not pin 127.0.0.1: "${connectSrc}"`);
  }
  // Allowlist semantics: every token must be loopback/IPC-permitted. This catches scheme wildcards
  // (`*`, `https:`, `http:`) and external hosts — a host-only check would miss `... https:` while 127.0.0.1
  // is still present (the egress hole that motivated this).
  for (const t of tokens) {
    if (!isPermittedConnectToken(t)) {
      errors.push(
        `CSP connect-src token "${t}" is not loopback-permitted — only 'self', ipc:, http://ipc.localhost, ` +
          `and http(s)://{127.0.0.1|localhost|[::1]}[:port|:*] are allowed (a wildcard like * / https: or an ` +
          `external host would allow egress and break the privacy claim).`
      );
    }
  }
}

// Analytics/telemetry SDK coordinates that must not appear in dependency-declaration files.
const TELEMETRY_SDKS = [
  /\bposthog\b/i,
  /@sentry\//i,
  /\bio\.sentry\b/i,
  /\bmixpanel\b/i,
  /(@amplitude\/|\bamplitude-js\b)/i,
  /(@segment\/|\banalytics-node\b)/i,
];

function walk(dir, pred) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) {
      if (ent.name === "node_modules" || ent.name === "build" || ent.name === ".gradle") continue;
      out.push(...walk(full, pred));
    } else if (pred(full)) {
      out.push(full);
    }
  }
  return out;
}

function checkNoTelemetrySdk(repoRoot, errors) {
  const depFiles = walk(
    path.join(repoRoot, "modules"),
    (p) => p.endsWith("build.gradle.kts") || p.endsWith("package.json")
  );
  for (const f of depFiles) {
    const text = fs.readFileSync(f, "utf8");
    const rel = path.relative(repoRoot, f).replaceAll("\\", "/");
    for (const re of TELEMETRY_SDKS) {
      const m = re.exec(text);
      if (m) {
        errors.push(`Analytics/telemetry SDK reference "${m[0]}" in ${rel} — the 'no telemetry exporter' claim would be false.`);
      }
    }
  }
}

function checkModelSourceClaims(repoRoot, errors) {
  const threatPath = path.join(repoRoot, "docs", "reference", "security", "threat-model.md");
  const registryPath = path.join(
    repoRoot,
    "modules",
    "ui",
    "src",
    "main",
    "resources",
    "ai",
    "model-registry.v2.json"
  );
  if (!fs.existsSync(threatPath)) {
    errors.push(`Threat model not found at ${threatPath} - cannot verify model-source claims.`);
    return;
  }
  if (!fs.existsSync(registryPath)) {
    errors.push(`Model registry not found at ${registryPath} - cannot verify model-source claims.`);
    return;
  }

  const threatText = fs.readFileSync(threatPath, "utf8");
  const registryText = fs.readFileSync(registryPath, "utf8");
  const chatRegistryUsesHuggingFace =
    /"downloadUrl"\s*:\s*"https:\/\/huggingface\.co\/bartowski\/Qwen_Qwen3\.5-9B-GGUF\//i.test(
      registryText
    );
  const threatClaimsChatMirror =
    /\b(project|justsearch)\s+mirrors?\s+(?:the\s+)?(?:third-party\s+)?chat[- ](?:model\s+)?GGUF\b/i.test(
      threatText
    ) ||
    /\bchat[- ](?:model\s+)?GGUF\s+(?:is\s+)?mirrored\b/i.test(threatText);

  if (chatRegistryUsesHuggingFace && threatClaimsChatMirror) {
    errors.push(
      "Threat model claims the chat GGUF is mirrored, but model-registry.v2.json still downloads " +
        "the packaged chat GGUF/mmproj from huggingface.co/bartowski. Update the doc or point the " +
        "registry at the project-controlled mirror."
    );
  }
}

function main() {
  const repoRoot = repoRootFromCwd();
  const errors = [];
  checkCsp(repoRoot, errors);
  checkNoTelemetrySdk(repoRoot, errors);
  checkModelSourceClaims(repoRoot, errors);

  if (errors.length === 0) {
    console.log("check-privacy-claims: OK (CSP pins loopback; no analytics SDK; model-source claims match registry)");
    return;
  }
  console.log(`check-privacy-claims: FAIL (errors=${errors.length})`);
  console.log("  The public 'provable privacy' claim (README + docs/reference/security/threat-model.md) drifted from code.");
  for (const e of errors) console.log(`- ${e}`);
  process.exitCode = 1;
}

main();
