/** Guard the immutable agent-utility publication pointer and outward projections. */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const PUBLIC_ROOT = path.join(ROOT, "scripts", "jseval", "public-agent-utility");
const POINTER_PATH = path.join(PUBLIC_ROOT, "current.v1.json");

function fail(message) {
  console.error(`check-public-agent-utility: FAIL - ${message}`);
  process.exit(1);
}

function readJson(file, label) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    fail(`${label} is missing or invalid JSON: ${error.message}`);
  }
}

function exactKeys(value, keys, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label} must be an object`);
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    fail(`${label} keys differ: expected ${expected.join(", ")}; got ${actual.join(", ")}`);
  }
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function confined(base, relative, label) {
  const resolved = path.resolve(base, relative);
  const prefix = path.resolve(ROOT) + path.sep;
  if (!resolved.startsWith(prefix)) fail(`${label} escapes the repository`);
  return resolved;
}

const pointer = readJson(POINTER_PATH, "publication pointer");
exactKeys(pointer, ["schema", "schema_version", "current", "previous", "reason", "selected_at"], "pointer");
if (pointer.schema !== "agent-utility-publication-pointer.v1" || pointer.schema_version !== 1) {
  fail("unsupported publication pointer schema");
}
if (typeof pointer.reason !== "string" || pointer.reason.length === 0) fail("pointer reason must be non-empty");

if (pointer.current !== null) {
  exactKeys(pointer.current, ["publication_id", "path", "manifest_sha256"], "pointer.current");
  const manifestPath = confined(PUBLIC_ROOT, pointer.current.path, "manifest path");
  if (!fs.existsSync(manifestPath)) fail("selected manifest does not exist");
  if (sha256(manifestPath) !== pointer.current.manifest_sha256) fail("selected manifest hash mismatch");

  const manifest = readJson(manifestPath, "publication manifest");
  exactKeys(
    manifest,
    ["schema", "schema_version", "publication_id", "created_at", "lifecycle_state", "record", "observations", "policy", "sanitizer_version", "replay_command", "supersedes"],
    "manifest"
  );
  if (
    manifest.schema !== "agent-utility-publication.v1" ||
    manifest.schema_version !== 1 ||
    manifest.publication_id !== pointer.current.publication_id ||
    manifest.lifecycle_state !== "accepted"
  ) fail("pointer does not select the matching accepted publication");
  exactKeys(manifest.record, ["path", "sha256", "semantic_digest"], "manifest.record");
  exactKeys(manifest.observations, ["path", "sha256"], "manifest.observations");
  exactKeys(manifest.policy, ["id", "sha256", "status"], "manifest.policy");

  const recordPath = confined(path.dirname(manifestPath), manifest.record.path, "record path");
  const observationsPath = confined(path.dirname(manifestPath), manifest.observations.path, "observations path");
  if (sha256(recordPath) !== manifest.record.sha256) fail("canonical record byte hash mismatch");
  if (sha256(observationsPath) !== manifest.observations.sha256) fail("observation evidence byte hash mismatch");
  const record = readJson(recordPath, "canonical record");
  if (record.semantic_digest !== manifest.record.semantic_digest || record.claim_verdict?.accepted !== true) {
    fail("selected canonical record is not accepted or has the wrong semantic digest");
  }

  const python = process.platform === "win32" ? "python" : "python3";
  const replayCode = [
    "import json, sys",
    "from jseval.utility_publication import replay_publication",
    "print(json.dumps(replay_publication(sys.argv[1]), sort_keys=True))",
  ].join("; ");
  const replay = spawnSync(python, ["-c", replayCode, manifestPath], {
    cwd: path.join(ROOT, "scripts", "jseval"),
    encoding: "utf8",
  });
  if (replay.status !== 0) fail(`offline replay failed:\n${replay.stderr || replay.stdout}`);
  console.log(`check-public-agent-utility: replayed ${pointer.current.publication_id}`);
} else if (pointer.selected_at !== null) {
  fail("the initial no-result pointer must have selected_at=null");
}

const projection = spawnSync("node", [path.join(ROOT, "scripts", "docs", "gen-public-agent-utility.mjs"), "--check"], {
  cwd: ROOT,
  stdio: "inherit",
});
if (projection.status !== 0) fail("public projections are stale or invalid");

console.log(`check-public-agent-utility: OK (${pointer.current ? "accepted result replayed" : "no accepted result"})`);
