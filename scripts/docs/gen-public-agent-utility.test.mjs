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
const FIXTURE_POLICY_ID = "agent-utility-fixture-v9";
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
  // The no-result block renders the ACTIVE claim-policy id + stratum count, both
  // read from whichever utility-claim-policy.*.json declares status "active" --
  // never from a hardcoded id or filename, either of which goes stale at the next
  // ratification and silently names a superseded policy. A superseded sibling is
  // written alongside so the fixture exercises the selection, not just the read.
  writeJson(path.join(root, "scripts/jseval/utility-claim-policy.v3.json"), {
    policy_id: FIXTURE_POLICY_ID, status: "active", unresolved: [],
    required_strata: [{ stratum_id: "a" }, { stratum_id: "b" }, { stratum_id: "c" }],
  });
  writeJson(path.join(root, "scripts/jseval/utility-claim-policy.v1.json"), {
    policy_id: "fixture-superseded-policy", status: "superseded", unresolved: [],
    required_strata: [{ stratum_id: "old" }],
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
const failures = [];

/** Failure-collector idiom (sibling: check-outward-number-citations.test.mjs / check-readiness-reason-codes.test.mjs):
 * a failed assertion is recorded, not thrown, so one broken check never hides the rest of the suite. */
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (error) {
    failures.push(error.message);
  }
};

/** Runs one scenario in its own fixture root; an unexpected exception inside `body` is recorded as a
 * failure (not rethrown), so a crashing scenario does not abort the remaining scenarios in the file. */
function scenario(label, body) {
  const root = fixture();
  try {
    body(root, (sub, cond) => ok(`${label}: ${sub}`, cond));
  } catch (error) {
    failures.push(`${label}: unexpected error - ${error.message}`);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

scenario("initial null and drift", (root, ok) => {
  const first = run(root);
  ok("generate exits 0", first.status === 0);
  for (const relative of [
    "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
  ]) {
    const projected = fs.readFileSync(path.join(root, relative), "utf8");
    ok(`${relative} states no accepted result`, /No agent-utility result is currently accepted/.test(projected));
    const body = projected.slice(projected.indexOf(START), projected.indexOf(END));
    ok(`${relative} carries no benchmark table or percentages`, !/\| Corpus|\d+\.\d+%/.test(body));
    // The active policy is selected by status across utility-claim-policy.*.json,
    // so the superseded sibling in the fixture must NOT be the one rendered.
    ok(`${relative} renders the active policy id dynamically`, body.includes(FIXTURE_POLICY_ID));
    ok(`${relative} never names the superseded policy`, !body.includes("fixture-superseded-policy"));
    ok(`${relative} renders the active stratum count`, /required 3-stratum/.test(body));
  }
  ok("--check exits 0 once in sync", run(root, "--check").status === 0);
  const readme = path.join(root, "README.md");
  fs.writeFileSync(
    readme,
    fs.readFileSync(readme, "utf8").replace("No agent-utility result", "A stale agent-utility result"),
  );
  ok("--check exits non-zero on drift", run(root, "--check").status !== 0);
});

// The rendered sentence asserts the policy "is active and fully resolved". Both
// halves must be verified, not assumed -- a generator that prints that sentence
// over an unresolved or ambiguous policy is publishing a false claim, so
// generation fails closed instead.
scenario("active-policy selection fails closed", (root, ok) => {
  const policyPath = path.join(root, "scripts/jseval/utility-claim-policy.v3.json");
  const active = JSON.parse(fs.readFileSync(policyPath, "utf8"));

  writeJson(policyPath, { ...active, unresolved: ["owner must settle the matrix"] });
  const unresolvedRun = run(root);
  ok("refuses to render an unresolved policy as fully resolved", unresolvedRun.status !== 0);
  ok("names the unresolved item", /unresolved/.test(unresolvedRun.stderr + unresolvedRun.stdout));

  writeJson(policyPath, { ...active, status: "superseded" });
  const noneRun = run(root);
  ok("refuses when no policy is active", noneRun.status !== 0);
  ok("reports the active-policy count", /exactly one active claim policy, found 0/
    .test(noneRun.stderr + noneRun.stdout));

  writeJson(path.join(root, "scripts/jseval/utility-claim-policy.v1.json"), {
    ...active, policy_id: "fixture-second-active",
  });
  writeJson(policyPath, active);
  const ambiguousRun = run(root);
  ok("refuses when two policies claim active", ambiguousRun.status !== 0);
  ok("reports the ambiguity", /exactly one active claim policy, found 2/
    .test(ambiguousRun.stderr + ambiguousRun.stdout));
});

const OUTCOME_PHRASES = {
  benefit: /produced a policy-qualified utility benefit/,
  harm: /produced material harm/,
  null: /equivalent within the pre-registered accuracy and efficiency margins/,
  "adoption-only": /adopted JustSearch, but the campaign did not establish an efficiency or accuracy improvement/,
};

for (const [outcome, phrase] of Object.entries(OUTCOME_PHRASES)) {
  scenario(`accepted ${outcome} and superseded`, (root, ok) => {
    selectAccepted(root, outcome);
    ok("generate exits 0", run(root).status === 0);
    const targets = [
      "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
    ].map((relative) => fs.readFileSync(path.join(root, relative), "utf8"));
    targets.forEach((projected, index) => {
      ok(`target ${index} names the accepted publication`, /Accepted publication `accepted-one`/.test(projected));
      ok(`target ${index} carries the ${outcome} phrase`, phrase.test(projected));
      if (outcome !== "benefit") {
        ok(`target ${index} does not carry the benefit phrase`, !/produced a policy-qualified utility benefit/.test(projected));
      }
    });
    const readme = targets[0];
    ok("README links to the reference doc", /docs\/reference\/benchmarks\/agent-utility\.md/.test(readme));
    const readmeBody = readme.slice(readme.indexOf(START), readme.indexOf(END));
    ok("README stays free of per-stratum tables", !/\| Corpus|50\.0%|Paired n/.test(readmeBody));
  });
}

scenario("research exposes compact per-stratum metrics and uncertainty", (root, ok) => {
  selectAccepted(root, "benefit");
  ok("generate exits 0", run(root).status === 0);
  const research = fs.readFileSync(path.join(root, "RESEARCH.md"), "utf8");
  ok("has the token-delta column header", /Provider cache-creation token delta/.test(research));
  ok("token delta carries its interval", /\+20\.0 \(CI \+10\.0 to \+30\.0\)/.test(research));
  ok("accuracy delta carries its interval", /\+5\.0 pp \(CI \+1\.0 pp to \+9\.0 pp\)/.test(research));
  ok("adoption and paired n render", /80\.8% \| 99 \|/.test(research));
});

scenario("reference exposes arms, loss, certification, and immutable references", (root, ok) => {
  selectAccepted(root, "harm");
  ok("generate exits 0", run(root).status === 0);
  const reference = fs.readFileSync(
    path.join(root, "docs/reference/benchmarks/agent-utility.md"), "utf8",
  );
  ok("stratum outcome heading", /Stratum outcome: \*\*harm\*\*/.test(reference));
  ok("accuracy row", /Accuracy \| 50\.0% \| 55\.0% \| \+5\.0 pp/.test(reference));
  ok("token distribution row", /mean 100\.0; median 90\.0; p95 160\.0; n=100/.test(reference));
  ok("cost distribution row", /mean \$0\.010000; median \$0\.009000; p95 \$0\.018000; n=100/.test(reference));
  ok("expected/observed cells", /expected\/observed cells: 200\/198/.test(reference));
  ok("loss table row B", /\| B \| 100 \| 99 \| 96 \| 3 \| 1 \| 3\.0% \|/.test(reference));
  ok("certification sha", /Certification SHA-256 \| `e{64}`/.test(reference));
  ok("scientific gate evidence label", /Scientific gate evidence/.test(reference));
  ok("publication manifest reference", /Publication manifest:/.test(reference));
  ok("sanitized observation evidence reference", /Sanitized observation evidence:/.test(reference));
  ok("replay command", /utility-replay --publication accepted-one/.test(reference));
});

scenario("withdrawn", (root, ok) => {
  const pointer = path.join(root, "scripts/jseval/public-agent-utility/current.v1.json");
  writeJson(pointer, {
    schema: "agent-utility-publication-pointer.v1", schema_version: 1,
    current: null, previous: { publication_id: "accepted-one" },
    reason: "Withdrawn after audit.", selected_at: "2026-07-13T00:00:00Z",
  });
  ok("generate exits 0", run(root).status === 0);
  ok(
    "README states the previous result was withdrawn",
    /previously selected result was withdrawn/.test(fs.readFileSync(path.join(root, "README.md"), "utf8")),
  );
});

scenario("missing markers", (root, ok) => {
  fs.writeFileSync(path.join(root, "RESEARCH.md"), "no markers\n");
  const result = run(root);
  ok("generate exits non-zero when a target lacks markers", result.status !== 0);
  ok("no stderr/stack trace on the missing-markers path", result.stderr === "");
});

scenario("drift is detected in every target", (root, ok) => {
  ok("initial generate exits 0", run(root).status === 0);
  for (const relative of [
    "README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md",
  ]) {
    const file = path.join(root, relative);
    fs.writeFileSync(file, fs.readFileSync(file, "utf8").replace("No agent-utility result", "Stale result"));
    ok(`${relative}: --check catches drift`, run(root, "--check").status !== 0);
    ok(`${relative}: regenerate exits 0`, run(root).status === 0);
  }
});

scenario("accepted projection rejects tampered observation evidence", (root, ok) => {
  selectAccepted(root, "benefit");
  const evidence = path.join(
    root, "scripts/jseval/public-agent-utility/publications/accepted-one/observations.v1.jsonl",
  );
  fs.appendFileSync(evidence, "{}\n");
  ok("generate exits non-zero on tampered observation evidence", run(root).status !== 0);
});

scenario("--check reports drift for every stale target, not just the first", (root, ok) => {
  ok("initial generate exits 0", run(root).status === 0);
  const staleRelatives = ["README.md", "RESEARCH.md"];
  for (const relative of staleRelatives) {
    const file = path.join(root, relative);
    fs.writeFileSync(file, fs.readFileSync(file, "utf8").replace("No agent-utility result", "Stale result"));
  }
  const result = run(root, "--check");
  ok("--check exits non-zero", result.status !== 0);
  for (const relative of staleRelatives) {
    ok(`--check output names ${relative} as drifted`, (result.stdout || "").includes(`DRIFT ${relative}`));
  }
  ok(
    "the untouched target is not reported as drift",
    !(result.stdout || "").includes("DRIFT docs/reference/benchmarks/agent-utility.md"),
  );
  ok("no stderr/stack trace on the --check drift path", result.stderr === "");
});

scenario("write mode with a marker-less target writes nothing (atomic across targets)", (root, ok) => {
  const targets = ["README.md", "RESEARCH.md", "docs/reference/benchmarks/agent-utility.md"];
  // RESEARCH.md loses its markers; README.md/agent-utility.md are still at their pristine fixture
  // placeholder ("stale" between markers) — a successful run WOULD rewrite them to real content.
  fs.writeFileSync(path.join(root, "RESEARCH.md"), "no markers here\n");
  const before = Object.fromEntries(
    targets.map((relative) => [relative, fs.readFileSync(path.join(root, relative), "utf8")]),
  );
  ok("README.md is still at its pristine (would-drift) placeholder", before["README.md"].includes("stale"));

  const result = run(root);
  ok("write mode exits non-zero when one target lacks markers", result.status !== 0);
  for (const relative of targets) {
    const after = fs.readFileSync(path.join(root, relative), "utf8");
    ok(`${relative} is byte-unchanged after the blocked write`, after === before[relative]);
  }
  ok("no stderr/stack trace on the blocked write path", result.stderr === "");
});

if (failures.length) {
  console.error(`gen-public-agent-utility.test: FAIL (${failures.length})`);
  for (const failure of failures) console.error("  - " + failure);
  process.exit(1);
}
console.log(`gen-public-agent-utility.test: OK (${passed} assertions)`);
