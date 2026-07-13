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

function distribution(mean, median, p95, n = 100) {
  return { mean, median, p95, n };
}

function selectAccepted(root, outcome = "null") {
  const bundle = path.join(root, "scripts/jseval/public-agent-utility/publications/accepted-one");
  const certification = {
    schema: "707-corpus-certification-snapshot.v1",
    member: "en-legal-clerc", dataset: "clerc/1000/verbose", size: 1000,
    query_variant: "verbose", query_count: 20, corpus_signature: "c".repeat(64),
    certification_sha256: "e".repeat(64), fully_certified: true,
    scientific_gate_sha256: {
      closed_book: "1".repeat(64), retrieval_calibration: "2".repeat(64),
      union_recall: "3".repeat(64), leak_floor: "4".repeat(64),
    },
  };
  const cell = {
    stratum_id: "en-legal-clerc|clerc/1000/verbose|1000|verbose|agent",
    corpus_member: "en-legal-clerc", corpus: "clerc/1000/verbose", corpus_size: 1000,
    query_variant: "verbose", model: "agent", seed_ids: [11, 17, 23, 29, 31],
    seed_count: 5, query_count: 20, n_expected_cells: 200, n_observed_cells: 198,
    n_pending_cells: 2, n_expected_pairs: 100,
    n_paired_observations: 99, n_per_protocol_pairs: 95, paired_retention: 95 / 100,
    per_arm_loss: {
      A: { n_expected: 100, n_attempted: 99, n_completed: 97, n_excluded: 2, n_pending: 1, exclusion_rate: 2 / 99 },
      B: { n_expected: 100, n_attempted: 99, n_completed: 96, n_excluded: 3, n_pending: 1, exclusion_rate: 3 / 99 },
    },
    accuracy: {
      baseline: 0.5, with_tool: 0.55, delta: 0.05, delta_ci: [0.01, 0.09],
    },
    provider_cache_creation_input_tokens: {
      baseline: distribution(100, 90, 160), with_tool: distribution(120, 110, 180),
      delta_mean: 20, delta_ci: [10, 30],
    },
    cost_usd: {
      baseline: distribution(0.01, 0.009, 0.018),
      with_tool: distribution(0.012, 0.011, 0.02),
      delta_mean: 0.002, delta_ci: [0.001, 0.003],
    },
    adoption: { with_tool: { adopted_cells: 80, eligible_cells: 99, adoption_rate: 80 / 99 } },
    corpus_certification: certification,
  };
  const record = {
    semantic_digest: "d".repeat(64),
    claim_verdict: {
      accepted: true, outcome,
      stratum_outcomes: [{
        stratum_id: cell.stratum_id, corpus: cell.corpus, model: cell.model, outcome,
      }],
    },
    estimands: { intention_to_treat: { strata: [cell] } },
  };
  const policy = { policy_id: "fixture-policy" };
  writeJson(path.join(bundle, "record.json"), record);
  writeJson(path.join(bundle, "policy.json"), policy);
  fs.writeFileSync(path.join(bundle, "observations.v1.jsonl"), "{}\n");
  const manifest = {
    schema: "agent-utility-publication.v1", publication_id: "accepted-one",
    lifecycle_state: "accepted",
    record: { path: "record.json", sha256: sha256(path.join(bundle, "record.json")), semantic_digest: record.semantic_digest },
    policy: { path: "policy.json", id: policy.policy_id, sha256: sha256(path.join(bundle, "policy.json")), status: "active" },
    observations: {
      path: "observations.v1.jsonl", sha256: sha256(path.join(bundle, "observations.v1.jsonl")),
    },
    replay_command: "python -m jseval utility-replay --publication accepted-one",
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
  for (const relative of [
    "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
  ]) {
    const projected = fs.readFileSync(path.join(root, relative), "utf8");
    assert.match(projected, /No agent-utility result is currently accepted/);
    assert.doesNotMatch(projected.slice(projected.indexOf(START), projected.indexOf(END)), /\| Corpus|\d+\.\d+%/);
  }
  assert.equal(run(root, "--check").status, 0);
  const readme = path.join(root, "README.md");
  fs.writeFileSync(
    readme,
    fs.readFileSync(readme, "utf8").replace("No agent-utility result", "A stale agent-utility result"),
  );
  assert.notEqual(run(root, "--check").status, 0);
});

const OUTCOME_PHRASES = {
  benefit: /produced a policy-qualified utility benefit/,
  harm: /produced material harm/,
  null: /equivalent within the pre-registered accuracy and efficiency margins/,
  "adoption-only": /adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement/,
};

for (const [outcome, phrase] of Object.entries(OUTCOME_PHRASES)) {
  test(`accepted ${outcome} and superseded`, (root) => {
    selectAccepted(root, outcome);
    assert.equal(run(root).status, 0);
    const targets = [
      "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
    ].map((relative) => fs.readFileSync(path.join(root, relative), "utf8"));
    for (const projected of targets) {
      assert.match(projected, /Accepted publication `accepted-one`/);
      assert.match(projected, phrase);
      if (outcome !== "benefit") {
        assert.doesNotMatch(projected, /produced a policy-qualified utility benefit/);
      }
    }
    const readme = targets[0];
    assert.match(readme, /docs\/reference\/benchmarks\/agent-utility\.md/);
    assert.doesNotMatch(readme.slice(readme.indexOf(START), readme.indexOf(END)), /\| Corpus|50\.0%|Paired n/);
  });
}

test("research exposes compact per-stratum metrics and uncertainty", (root) => {
  selectAccepted(root, "benefit");
  assert.equal(run(root).status, 0);
  const research = fs.readFileSync(path.join(root, "RESEARCH.md"), "utf8");
  assert.match(research, /Provider cache-creation token delta/);
  assert.match(research, /\+20\.0 \(CI \+10\.0 to \+30\.0\)/);
  assert.match(research, /\+5\.0 pp \(CI \+1\.0 pp to \+9\.0 pp\)/);
  assert.match(research, /80\.8% \| 99 \|/);
});

test("reference exposes arms, loss, certification, and immutable references", (root) => {
  selectAccepted(root, "harm");
  assert.equal(run(root).status, 0);
  const reference = fs.readFileSync(
    path.join(root, "docs/reference/benchmarks/agent-utility.md"), "utf8",
  );
  assert.match(reference, /Stratum outcome: \*\*harm\*\*/);
  assert.match(reference, /Accuracy \| 50\.0% \| 55\.0% \| \+5\.0 pp/);
  assert.match(reference, /mean 100\.0; median 90\.0; p95 160\.0; n=100/);
  assert.match(reference, /mean \$0\.010000; median \$0\.009000; p95 \$0\.018000; n=100/);
  assert.match(reference, /expected\/observed cells: 200\/198/);
  assert.match(reference, /\| B \| 100 \| 99 \| 96 \| 3 \| 1 \| 3\.0% \|/);
  assert.match(reference, /Certification SHA-256 \| `e{64}`/);
  assert.match(reference, /Scientific gate evidence/);
  assert.match(reference, /Publication manifest:/);
  assert.match(reference, /Sanitized observation evidence:/);
  assert.match(reference, /utility-replay --publication accepted-one/);
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

test("drift is detected in every target", (root) => {
  assert.equal(run(root).status, 0);
  for (const relative of [
    "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
  ]) {
    const file = path.join(root, relative);
    fs.writeFileSync(file, fs.readFileSync(file, "utf8").replace("No agent-utility result", "Stale result"));
    assert.notEqual(run(root, "--check").status, 0, relative);
    assert.equal(run(root).status, 0, relative);
  }
});

test("accepted projection rejects tampered observation evidence", (root) => {
  selectAccepted(root, "benefit");
  const evidence = path.join(
    root, "scripts/jseval/public-agent-utility/publications/accepted-one/observations.v1.jsonl",
  );
  fs.appendFileSync(evidence, "{}\n");
  assert.notEqual(run(root).status, 0);
});

console.log(`gen-public-agent-utility.test: OK (${passed} assertions)`);
