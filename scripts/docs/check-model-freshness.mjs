/**
 * Fail-closed docs lint: no doc may name a RETIRED chat-model as the current /
 * default / packaged generative LLM.
 *
 * Sibling of check-frontend-stack-claims.mjs (the frontend-framework freshness
 * gate). This is the *model-name* freshness gate (tempdoc 650 §M2). The
 * Qwen3VL-8B family was retired in favour of Qwen3.5-9B, but stale references
 * lingered across explanation / reference / how-to / business docs the same way
 * "React" did — the second confirmed instance of the stale-technical-claim class.
 * One denylist + fence-skip + historical-marker pattern, data-driven from the
 * model registry so it cannot itself go stale.
 *
 * Registry-projected: each retired name is enforced ONLY while the authoritative
 * registry (model-registry.v2.json) confirms it is NOT a current package. If a
 * retired name is ever re-adopted into the registry, this gate no-ops for it —
 * there is no hand-maintained "current model" copy to drift.
 *
 * Trees scanned (canonical corpus + public surface), mirroring the frontend gate:
 *   docs/explanation/  docs/reference/  docs/how-to/  docs/business/  + root README.md
 * Excluded:
 *   - docs/reference/issues/             (historical bug/decision logs)
 *   - docs/reference/model-inventory.md  (the model-tracking ledger — it legitimately
 *     names old<->new artifacts in its reconciliation tables; it is self-policing)
 *
 * Conservative by construction:
 *   - fenced code blocks are skipped (manifest / command examples),
 *   - a line is exempt when it frames the name historically (prior / retired /
 *     former / legacy / older / measured / baseline / reconcile / replaced / ...)
 *     OR also names the current model (a comparison / reconciliation line).
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

function walk(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

const normalizeRel = (p) => p.replaceAll("\\", "/");

// Retired chat-model families that must not appear as a current claim.
const FORBIDDEN = [{ id: "qwen3vl", re: /\bQwen3-?VL\b/i, label: "Qwen3VL (retired chat model)" }];

// Token for the CURRENT packaged chat model. A line naming both a retired family
// AND the current model is a comparison / reconciliation line — exempt.
const CURRENT_MODEL = /\bQwen[_-]?3\.5\b/i;

// A line is exempt when it frames the token historically.
const HISTORICAL =
  /\b(prior|retired|former|formerly|legacy|older|deprecat(?:ed|ion)|superseded|replaces?|replaced|reconciles?|reconciled|measured|baseline|no longer|instead of|used to|was\s+the)\b/i;

function main() {
  const repoRoot = repoRootFromCwd();

  // Registry projection: only enforce a retired name while the registry confirms
  // it is not a current package. Fail-open if the registry is unreadable.
  const registryPath = path.join(
    repoRoot, "modules", "ui", "src", "main", "resources", "ai", "model-registry.v2.json");
  let registryText = "";
  try {
    registryText = fs.readFileSync(registryPath, "utf8");
  } catch {
    registryText = "";
  }
  const active = FORBIDDEN.filter((tok) => !registryText || !tok.re.test(registryText));
  if (active.length === 0) {
    console.log("check-model-freshness: OK (all retired names are current per registry — gate inert)");
    return;
  }

  const dirs = [
    path.join(repoRoot, "docs", "explanation"),
    path.join(repoRoot, "docs", "reference"),
    path.join(repoRoot, "docs", "how-to"),
    path.join(repoRoot, "docs", "business"),
  ];
  const extraFiles = [path.join(repoRoot, "README.md")];
  const files = [...dirs.flatMap((d) => walk(d)), ...extraFiles.filter((f) => fs.existsSync(f))]
    .filter((p) => p.toLowerCase().endsWith(".md"))
    // Historical logs + the model-tracking ledger legitimately name retired artifacts.
    .filter((p) => !normalizeRel(p).includes("/reference/issues/"))
    .filter((p) => !normalizeRel(p).endsWith("/reference/model-inventory.md"));

  const errors = [];
  for (const f of files) {
    const relFile = normalizeRel(path.relative(repoRoot, f));
    const lines = fs.readFileSync(f, "utf8").split(/\r?\n/);
    let inFence = false;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (/^\s*```/.test(line)) { inFence = !inFence; continue; }
      if (inFence) continue;
      if (HISTORICAL.test(line)) continue;
      if (CURRENT_MODEL.test(line)) continue;

      for (const tok of active) {
        if (tok.re.test(line)) {
          errors.push({ relFile, lineNumber: i + 1, label: tok.label, text: line.trim().slice(0, 120) });
        }
      }
    }
  }

  if (errors.length === 0) {
    console.log(`check-model-freshness: OK (files=${files.length})`);
    return;
  }

  console.log(`check-model-freshness: FAIL (files=${files.length} errors=${errors.length})`);
  console.log("  The packaged chat model is Qwen3.5-9B, not Qwen3VL (tempdoc 650 §M2). Update the claim,");
  console.log("  or frame it historically (prior / retired / measured / baseline / 'replaced by ...').");
  for (const e of errors) {
    console.log(`- ${e.relFile}:${e.lineNumber} :: ${e.label} :: ${e.text}`);
  }
  process.exitCode = 1;
}

main();
