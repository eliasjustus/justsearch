/**
 * Project the selected immutable agent-utility publication into public docs.
 *
 * The pointer is the only mutable selection surface. Accepted numbers are read
 * from its hash-pinned manifest and canonical record; a null pointer renders an
 * explicit no-result statement. Never edit generated regions by hand.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const START =
  "<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->";
const END = "<!-- agent-utility:generated:end -->";

function repoRoot() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, "settings.gradle.kts"))) return dir;
    if (path.dirname(dir) === dir) throw new Error("cannot locate repository root");
  }
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function loadSelection(root) {
  const pointerPath = path.join(root, "scripts", "jseval", "public-agent-utility", "current.v1.json");
  const pointer = readJson(pointerPath);
  if (pointer.schema !== "agent-utility-publication-pointer.v1" || !("current" in pointer)) {
    throw new Error("malformed agent-utility publication pointer");
  }
  if (pointer.current === null) return { pointer, manifest: null, record: null };

  const manifestPath = path.resolve(path.dirname(pointerPath), pointer.current.path);
  if (sha256(manifestPath) !== pointer.current.manifest_sha256) {
    throw new Error("selected agent-utility manifest hash mismatch");
  }
  const manifest = readJson(manifestPath);
  if (
    manifest.schema !== "agent-utility-publication.v1" ||
    manifest.publication_id !== pointer.current.publication_id ||
    manifest.lifecycle_state !== "accepted"
  ) {
    throw new Error("pointer must select the matching accepted publication");
  }
  const recordPath = path.resolve(path.dirname(manifestPath), manifest.record.path);
  if (sha256(recordPath) !== manifest.record.sha256) {
    throw new Error("selected agent-utility record hash mismatch");
  }
  const record = readJson(recordPath);
  const policyPath = path.resolve(path.dirname(manifestPath), manifest.policy.path);
  if (sha256(policyPath) !== manifest.policy.sha256) {
    throw new Error("selected agent-utility policy hash mismatch");
  }
  if (
    record.semantic_digest !== manifest.record.semantic_digest ||
    record.claim_verdict?.accepted !== true
  ) {
    throw new Error("selected record lacks the accepted, hash-pinned claim verdict");
  }
  return { pointer, manifest, record };
}

function cells(record) {
  const out = (record.estimands?.intention_to_treat?.strata || []).map((cell) => ({
    corpus: cell.corpus,
    model: cell.model,
    cell,
  }));
  return out.sort((a, b) => `${a.corpus}/${a.model}`.localeCompare(`${b.corpus}/${b.model}`));
}

function pct(value) {
  return typeof value === "number" ? `${value >= 0 ? "+" : ""}${(value * 100).toFixed(1)} pp` : "n/a";
}

function acceptedTable(record) {
  return [
    "| Corpus | Agent model | Baseline accuracy | With JustSearch | Paired delta | Paired n |",
    "|---|---|---:|---:|---:|---:|",
    ...cells(record).map(({ corpus, model, cell }) =>
      `| ${corpus} | ${model} | ${(cell.accuracy.baseline * 100).toFixed(1)}% | ` +
      `${(cell.accuracy.with_tool * 100).toFixed(1)}% | ${pct(cell.accuracy.delta)} | ` +
      `${cell.n_paired_observations} |`
    ),
  ].join("\n");
}

function noResult(selection, target) {
  const reason = selection.pointer.reason;
  const state = selection.pointer.previous ? "The previously selected result was withdrawn. " : "";
  const common =
    `No agent-utility result is currently accepted for publication. ${state}${reason} ` +
    "The checked-in scientific policy intentionally leaves its adoption, non-inferiority, and efficiency-equivalence thresholds unresolved; " +
    "choosing those thresholds and paying for a new model run are owner decisions, not defaults the harness invents.";
  if (target === "readme") return common;
  if (target === "research") {
    return [
      common,
      "",
      "The latest sanitized pilot evidence is retained as a rejected fixture and can be recomposed without credentials, " +
        "a backend, or model calls. A result can appear here only after an immutable bundle replays, passes the settled " +
        "policy, and is explicitly selected by the owner.",
      "",
      "```bash",
      "cd scripts/jseval",
      "python -m jseval utility-recompose --evidence tests/fixtures/agent-utility-rejected-2026-07-12/observations.v1.jsonl --output-dir out",
      "python -m jseval utility-replay --publication <publication-id>",
      "```",
    ].join("\n");
  }
  return [
    common,
    "",
    "The publication chain is: all attempted Inspect cells -> strict sanitized observation evidence -> pure offline " +
      "recomposition -> versioned claim-policy verdict -> immutable accepted publication manifest -> explicit accepted-result pointer. " +
      "Rejected evidence remains a test/history fixture rather than a publication bundle. The pointer is " +
      "`scripts/jseval/public-agent-utility/current.v1.json`.",
    "",
    "Replay uses only committed evidence:",
    "",
    "```bash",
    "cd scripts/jseval",
    "python -m jseval utility-replay --publication <publication-id>",
    "```",
  ].join("\n");
}

function accepted(selection, target) {
  const { manifest, record } = selection;
  const lead =
    `Accepted agent-utility publication \`${manifest.publication_id}\` (record ` +
    `\`${record.semantic_digest.slice(0, 12)}\`) passed policy \`${manifest.policy.id}\` and immutable replay. ` +
    `Outcome: **${record.claim_verdict.outcome}**.`;
  if (target === "readme") return `${lead}\n\n${acceptedTable(record)}`;
  const tail =
    `Reproduce it with \`cd scripts/jseval && python -m jseval utility-replay --publication ${manifest.publication_id}\`.`;
  return `${lead}\n\n${acceptedTable(record)}\n\n${tail}`;
}

function generated(selection, target) {
  return [START, "", selection.record ? accepted(selection, target) : noResult(selection, target), "", END].join("\n");
}

function project(file, body, check, root) {
  const text = fs.readFileSync(file, "utf8");
  const start = text.indexOf(START);
  const end = text.indexOf(END);
  if (start < 0 || end < start) throw new Error(`${path.relative(root, file)} lacks agent-utility markers`);
  const next = text.slice(0, start) + body + text.slice(end + END.length);
  const rel = path.relative(root, file).replaceAll("\\", "/");
  if (next === text) return console.log(`gen-public-agent-utility: ${rel} - in-sync`);
  if (check) throw new Error(`${rel} is stale; run generator without --check`);
  fs.writeFileSync(file, next);
  console.log(`gen-public-agent-utility: ${rel} - updated`);
}

function main() {
  const root = repoRoot();
  const check = process.argv.includes("--check");
  const selection = loadSelection(root);
  const targets = [
    ["README.md", "readme"],
    ["RESEARCH.md", "research"],
    [path.join("docs", "reference", "benchmarks", "agent-utility.md"), "reference"],
  ];
  for (const [relative, target] of targets) {
    project(path.join(root, relative), generated(selection, target), check, root);
  }
  console.log(`gen-public-agent-utility: OK${check ? " (in sync)" : ""}`);
}

main();
