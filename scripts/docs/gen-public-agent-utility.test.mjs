/** Lifecycle and drift tests for the agent-utility documentation projector. */
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), "gen-public-agent-utility.mjs");
const START = "<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->";
const END = "<!-- agent-utility:generated:end -->";
const sha256 = (file) => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value));
}

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "agent-utility-projector-"));
  fs.writeFileSync(path.join(root, "settings.gradle.kts"), "rootProject.name = 'fixture'\n");
  for (const relative of [
    "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
  ]) {
    const file = path.join(root, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `before\n${START}\nstale\n${END}\nafter\n`);
  }
  writeJson(path.join(root, "scripts/jseval/public-agent-utility/current.v1.json"), {
    schema: "agent-utility-publication-pointer.v1", schema_version: 1,
    current: null, previous: null, reason: "No accepted result.", selected_at: null,
  });
  return root;
}

function run(root, ...args) {
  return spawnSync(process.execPath, [SCRIPT, ...args], { cwd: root, encoding: "utf8" });
}

function selectAccepted(root) {
  const bundle = path.join(root, "scripts/jseval/public-agent-utility/publications/accepted-one");
  const record = {
    semantic_digest: "d".repeat(64), claim_verdict: { accepted: true, outcome: "null" },
    estimands: { intention_to_treat: { strata: [{
      corpus: "fixture", model: "agent", n_paired_observations: 100,
      accuracy: { baseline: 0.5, with_tool: 0.5, delta: 0 },
    }] } },
  };
  const policy = { policy_id: "fixture-policy" };
  writeJson(path.join(bundle, "record.json"), record);
  writeJson(path.join(bundle, "policy.json"), policy);
  const manifest = {
    schema: "agent-utility-publication.v1", publication_id: "accepted-one",
    lifecycle_state: "accepted",
    record: { path: "record.json", sha256: sha256(path.join(bundle, "record.json")), semantic_digest: record.semantic_digest },
    policy: { path: "policy.json", id: policy.policy_id, sha256: sha256(path.join(bundle, "policy.json")), status: "active" },
  };
  writeJson(path.join(bundle, "publication.v1.json"), manifest);
  writeJson(path.join(root, "scripts/jseval/public-agent-utility/current.v1.json"), {
    schema: "agent-utility-publication-pointer.v1", schema_version: 1,
    current: {
      publication_id: "accepted-one",
      path: "publications/accepted-one/publication.v1.json",
      manifest_sha256: sha256(path.join(bundle, "publication.v1.json")),
    },
    previous: { publication_id: "superseded-zero" },
    reason: "Supersedes the prior accepted result.", selected_at: "2026-07-13T00:00:00Z",
  });
}

let passed = 0;
function test(label, body) {
  const root = fixture();
  try {
    body(root);
    passed += 1;
  } catch (error) {
    error.message = `${label}: ${error.message}`;
    throw error;
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test("initial null and drift", (root) => {
  assert.equal(run(root).status, 0);
  assert.match(fs.readFileSync(path.join(root, "README.md"), "utf8"), /No agent-utility result is currently accepted/);
  assert.equal(run(root, "--check").status, 0);
  const readme = path.join(root, "README.md");
  fs.writeFileSync(
    readme,
    fs.readFileSync(readme, "utf8").replace("No agent-utility result", "A stale agent-utility result"),
  );
  assert.notEqual(run(root, "--check").status, 0);
});

test("accepted and superseded", (root) => {
  selectAccepted(root);
  assert.equal(run(root).status, 0);
  const readme = fs.readFileSync(path.join(root, "README.md"), "utf8");
  assert.match(readme, /Accepted agent-utility publication `accepted-one`/);
  assert.match(readme, /Outcome: \*\*null\*\*/);
});

test("withdrawn", (root) => {
  const pointer = path.join(root, "scripts/jseval/public-agent-utility/current.v1.json");
  writeJson(pointer, {
    schema: "agent-utility-publication-pointer.v1", schema_version: 1,
    current: null, previous: { publication_id: "accepted-one" },
    reason: "Withdrawn after audit.", selected_at: "2026-07-13T00:00:00Z",
  });
  assert.equal(run(root).status, 0);
  assert.match(fs.readFileSync(path.join(root, "README.md"), "utf8"), /previously selected result was withdrawn/);
});

test("missing markers", (root) => {
  fs.writeFileSync(path.join(root, "RESEARCH.md"), "no markers\n");
  assert.notEqual(run(root).status, 0);
});

console.log(`gen-public-agent-utility.test: OK (${passed} assertions)`);
