/**
 * Tests for the agent-utility publication pointer gate (scripts/ci/check-public-agent-utility.mjs).
 *
 * Failure paths call `process.exit(1)` inside the guard, so they are exercised by spawning the
 * script as a subprocess (sibling convention: gen-public-agent-utility.test.mjs) with
 * CHECK_PUBLIC_AGENT_UTILITY_ROOT pointed at a disposable fixture root — never against the real
 * repo except for the one test that deliberately targets the committed pointer.
 *
 * Run: `node scripts/ci/check-public-agent-utility.test.mjs` (exits non-zero on failure)
 */
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

import { missingPythonMessage } from "./check-public-agent-utility.mjs";

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), "check-public-agent-utility.mjs");
const sha256 = (file) => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value));
}

function pointerPath(root) {
  return path.join(root, "scripts", "jseval", "public-agent-utility", "current.v1.json");
}

function fixtureRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "check-agent-utility-"));
}

function writePointer(root, pointer) {
  writeJson(pointerPath(root), pointer);
}

function writeManifest(root, publicationId, content) {
  const dir = path.join(root, "scripts", "jseval", "public-agent-utility", "publications", publicationId);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, "publication.v1.json");
  fs.writeFileSync(file, JSON.stringify(content));
  return file;
}

const BASE_POINTER = {
  schema: "agent-utility-publication-pointer.v1",
  schema_version: 1,
  current: null,
  previous: null,
  reason: "No accepted result.",
  selected_at: null,
};

/** Spawns the real script against a fixture root via the test-only env override. */
function run(root) {
  return spawnSync(process.execPath, [SCRIPT], {
    encoding: "utf8",
    env: { ...process.env, CHECK_PUBLIC_AGENT_UTILITY_ROOT: root },
  });
}

function scenario(label, body) {
  const root = fixtureRoot();
  try {
    body(root, (sub, cond) => ok(`${label}: ${sub}`, cond));
  } catch (error) {
    failures.push(`${label}: unexpected error - ${error.message}`);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// --- the committed real pointer (current: null) passes pointer-shape validation ---
{
  const REPO_ROOT = path.resolve(path.dirname(SCRIPT), "..", "..");
  const realPointer = JSON.parse(fs.readFileSync(pointerPath(REPO_ROOT), "utf8"));
  ok("the committed real pointer currently has no accepted result (current: null)", realPointer.current === null);
  const result = spawnSync(process.execPath, [SCRIPT], { encoding: "utf8" });
  ok("running against the real repo (no override) exits 0", result.status === 0);
  ok("running against the real repo reports no accepted result", (result.stdout || "").includes("no accepted result"));
}

// --- pointer-shape failures ---
scenario("a pointer with an extra key fails", (root, ok) => {
  writePointer(root, { ...BASE_POINTER, extra_field: "not allowed" });
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the offending field", /pointer keys differ/.test(result.stderr || ""));
});

scenario("an empty reason fails", (root, ok) => {
  writePointer(root, { ...BASE_POINTER, reason: "" });
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the empty reason", /reason must be non-empty/.test(result.stderr || ""));
});

scenario("an initial pointer with a non-null selected_at fails", (root, ok) => {
  writePointer(root, { ...BASE_POINTER, current: null, previous: null, selected_at: "2026-07-13T00:00:00Z" });
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the initial-pointer violation", /initial no-result pointer must have selected_at=null/.test(result.stderr || ""));
});

scenario("previous != null with selected_at == null fails", (root, ok) => {
  writePointer(root, {
    ...BASE_POINTER, current: null, previous: { publication_id: "withdrawn-one" }, selected_at: null,
  });
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the withdrawn-pointer violation", /withdrawn publication pointer must retain its transition timestamp/.test(result.stderr || ""));
});

// --- pointer.current selection failures ---
scenario("pointer.current with a missing manifest file fails", (root, ok) => {
  writePointer(root, {
    ...BASE_POINTER,
    current: { publication_id: "fake-one", path: "publications/fake-one/publication.v1.json", manifest_sha256: "0".repeat(64) },
    reason: "Testing a missing manifest.", selected_at: "2026-07-13T00:00:00Z",
  });
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the missing manifest", /selected manifest does not exist/.test(result.stderr || ""));
});

scenario("pointer.current with a manifest hash mismatch fails", (root, ok) => {
  const manifestPath = writeManifest(root, "fake-one", {
    schema: "agent-utility-publication.v1", publication_id: "fake-one", lifecycle_state: "accepted",
  });
  writePointer(root, {
    ...BASE_POINTER,
    current: {
      publication_id: "fake-one",
      path: "publications/fake-one/publication.v1.json",
      manifest_sha256: "1".repeat(64), // deliberately wrong; real hash would be sha256(manifestPath)
    },
    reason: "Testing a hash mismatch.", selected_at: "2026-07-13T00:00:00Z",
  });
  ok("fixture manifest exists on disk", fs.existsSync(manifestPath));
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the hash mismatch", /selected manifest hash mismatch/.test(result.stderr || ""));
});

scenario("pointer.current selecting a manifest for a different publication_id fails", (root, ok) => {
  const manifestPath = writeManifest(root, "fake-one", {
    schema: "agent-utility-publication.v1", publication_id: "someone-else", lifecycle_state: "accepted",
  });
  writePointer(root, {
    ...BASE_POINTER,
    current: {
      publication_id: "fake-one",
      path: "publications/fake-one/publication.v1.json",
      manifest_sha256: sha256(manifestPath),
    },
    reason: "Testing a publication_id mismatch.", selected_at: "2026-07-13T00:00:00Z",
  });
  const result = run(root);
  ok("exits non-zero", result.status !== 0);
  ok("names the selection mismatch", /does not select the matching accepted publication/.test(result.stderr || ""));
});

// --- missing-Python message formatter (pure function; no subprocess needed) ---
ok(
  "an ENOENT spawn error produces the not-found remedy, naming the attempted binary",
  (() => {
    const message = missingPythonMessage("python", { error: { code: "ENOENT" } });
    return (
      message.includes("'python' not found (ENOENT)") &&
      message.includes("python -m pip install -e scripts/jseval") &&
      message.includes("set PYTHON to the interpreter path")
    );
  })(),
);
ok(
  "a non-zero exit status includes the status and stderr, and never prints 'undefined'",
  (() => {
    const message = missingPythonMessage("python", { status: 1, stderr: "boom", stdout: "" });
    return message.includes("status 1") && message.includes("boom") && !message.includes("undefined");
  })(),
);
ok(
  "a non-zero exit status with empty stderr falls back to stdout, never prints 'undefined'",
  (() => {
    const message = missingPythonMessage("python", { status: 2, stderr: "", stdout: "trace" });
    return message.includes("status 2") && message.includes("trace") && !message.includes("undefined");
  })(),
);
ok(
  "a non-zero exit status with no captured output at all never prints 'undefined'",
  (() => {
    const message = missingPythonMessage("python", { status: 3, stderr: "", stdout: "" });
    return message.includes("(no output captured)") && !message.includes("undefined");
  })(),
);

if (failures.length) {
  console.error(`check-public-agent-utility.test: FAIL (${failures.length})`);
  for (const failure of failures) console.error("  - " + failure);
  process.exit(1);
}
console.log(`check-public-agent-utility.test: OK (${passed} assertions)`);
