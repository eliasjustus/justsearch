#!/usr/bin/env node
/**
 * Discipline-gate kernel runner — tempdoc 530.
 *
 * Reads `governance/registry.v1.json`, dispatches per-gate enforcers,
 * aggregates findings into a single SARIF v2.1.0 document. Failures in
 * `gate` mode exit non-zero; `warn` mode reports without gating.
 *
 * The wire-Category protobuf-evolution gate lives at
 * `scripts/governance/gates/wire/` and is dispatched through this same
 * runner (tempdoc 530 Pass-7 Phase F). The prior standalone
 * `scripts/contract-governance/` kernel was retired in that pass.
 *
 * Usage:
 *   node scripts/governance/run.mjs --mode warn|gate \
 *        [--out tmp/governance-report.sarif] \
 *        [--registry governance/registry.v1.json] \
 *        [--gate <id>]            (run only one gate)
 *        [--self-test]            (run gate self-test fixtures + assert verdicts)
 *        [--rebalance]            (apply auto-rebalance writes back to baseline files)
 *
 * Exit codes:
 *   0 — pass (or warn-mode regardless of findings)
 *   1 — gate-mode + at least one gate verdict is fail
 *   2 — runner error (missing registry, missing enforcer, shallow clone)
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { emitSarif } from './lib/sarif-emitter.mjs';
import { isShallowRepository, resolveBaselineRef } from './lib/git-utils.mjs';
import { assertTruthTableShape } from './lib/truth-table-runner.mjs';
import { appendRunRecord } from './lib/history.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, '..', '..');

function parseArgs(argv) {
  const args = {
    mode: 'warn',
    out: 'tmp/governance-report.sarif',
    registry: 'governance/registry.v1.json',
    gate: null,
    selfTest: false,
    rebalance: false,
    explain: null,
    suggestChangeset: false,
    preflight: null,
    format: 'sarif',
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--mode') args.mode = argv[++i];
    else if (a === '--out') args.out = argv[++i];
    else if (a === '--registry') args.registry = argv[++i];
    else if (a === '--gate') args.gate = argv[++i];
    else if (a === '--self-test') args.selfTest = true;
    else if (a === '--rebalance') args.rebalance = true;
    else if (a === '--explain') args.explain = argv[++i];
    else if (a === '--suggest-changeset') args.suggestChangeset = true;
    else if (a === '--preflight') args.preflight = argv[++i] ?? 'HEAD';
    else if (a === '--format') args.format = argv[++i];
    else if (a === '--help' || a === '-h') {
      printHelp();
      process.exit(0);
    } else {
      console.error(`unknown argument: ${a}`);
      process.exit(2);
    }
  }
  if (args.mode !== 'warn' && args.mode !== 'gate') {
    console.error(`--mode must be 'warn' or 'gate' (got: ${args.mode})`);
    process.exit(2);
  }
  if (!['sarif', 'compact'].includes(args.format)) {
    console.error(`--format must be 'sarif' or 'compact' (got: ${args.format})`);
    process.exit(2);
  }
  return args;
}

function printHelp() {
  console.log(`Usage: node scripts/governance/run.mjs [options]

Discipline-gate kernel runner (tempdoc 530).

Modes:
  --mode warn|gate                gate exits non-zero on any fail (default: warn)
  --self-test                     run each gate's fixture pair
  --rebalance                     apply auto-shrink rebalance writes
  --explain <ruleId>              print rule description + changeset template
  --suggest-changeset             walk live state; emit stub changesets for predicted-fail gates
  --preflight [<baselineRef>]     predict which gates would fail given current state vs baselineRef

Options:
  --out <path>           SARIF output path (default: tmp/governance-report.sarif)
  --format sarif|compact terminal output format (default: sarif)
  --registry <path>      registry path (default: governance/registry.v1.json)
  --gate <id>            run only the named gate (default: all)
  -h, --help             this message
`);
}

function loadRegistry(registryPath) {
  const fullPath = resolve(REPO_ROOT, registryPath);
  if (!existsSync(fullPath)) {
    console.error(`registry not found: ${fullPath}`);
    process.exit(2);
  }
  const raw = readFileSync(fullPath, 'utf8');
  const reg = JSON.parse(raw);
  if (reg.kind !== 'discipline-gate-registry.v1') {
    console.error(
      `registry kind mismatch: expected 'discipline-gate-registry.v1', got '${reg.kind}'`,
    );
    process.exit(2);
  }
  return reg;
}

async function loadEnforcer(gate) {
  const enforcerPath = resolve(REPO_ROOT, gate.enforcer);
  if (!existsSync(enforcerPath)) {
    throw new Error(`enforcer not found for gate '${gate.id}': ${enforcerPath}`);
  }
  const mod = await import(pathToFileURL(enforcerPath).href);
  const exportName = pickEnforcerExport(mod);
  if (!exportName) {
    throw new Error(
      `enforcer ${enforcerPath} has no exported function (expected default, enforce, or enforceXxx)`,
    );
  }
  // Truth-table shape discipline (tempdoc 530 §Open questions "hard" lean):
  // every gate must ship a `truth-table.mjs` sibling exporting at least one
  // `verdict*` function. The runner refuses to dispatch a gate whose truth
  // table doesn't conform. This is the substrate-encoded version of "every
  // verdict matrix is a pure function with the contract output shape" —
  // future gates can't drift into bespoke vocabularies without failing
  // load-time validation.
  const truthTablePath = resolve(enforcerPath, '..', 'truth-table.mjs');
  if (existsSync(truthTablePath)) {
    const ttMod = await import(pathToFileURL(truthTablePath).href);
    assertTruthTableShape(ttMod, gate.id);
  } else {
    throw new Error(
      `gate '${gate.id}': required truth-table.mjs sibling not found at ${truthTablePath} ` +
        `(see scripts/governance/lib/truth-table-runner.mjs for the contract).`,
    );
  }
  return mod[exportName];
}

function pickEnforcerExport(mod) {
  if (typeof mod.default === 'function') return 'default';
  if (typeof mod.enforce === 'function') return 'enforce';
  for (const k of Object.keys(mod)) {
    if (k.startsWith('enforce') && typeof mod[k] === 'function') return k;
  }
  return null;
}

async function runGate(gate, args, baselineRef) {
  const enforce = await loadEnforcer(gate);
  return enforce({
    repoRoot: REPO_ROOT,
    gate,
    baselineRef,
    mode: args.mode,
    rebalance: args.rebalance,
    fixtureMode: false,
  });
}

async function runSelfTest(gate) {
  const fixturesRoot = gate.selfTestFixturesDir
    ? resolve(REPO_ROOT, gate.selfTestFixturesDir)
    : null;
  if (!fixturesRoot || !existsSync(fixturesRoot)) {
    return { gate: gate.id, skipped: true, reason: 'no fixtures directory' };
  }
  const enforce = await loadEnforcer(gate);
  const results = [];
  for (const flavor of ['positive', 'negative']) {
    const fixtureRoot = resolve(fixturesRoot, flavor);
    if (!existsSync(fixtureRoot)) continue;
    const expectFail = flavor === 'negative';
    const r = await enforce({
      repoRoot: REPO_ROOT,
      gate,
      baselineRef: null,
      mode: 'gate',
      rebalance: false,
      fixtureMode: true,
      fixtureRoot,
    });
    const passed = expectFail ? r.verdict === 'fail' : r.verdict === 'pass';
    results.push({
      flavor,
      expectFail,
      verdict: r.verdict,
      passed,
      findings: r.findings,
    });
  }
  return { gate: gate.id, skipped: false, results };
}

async function main() {
  const args = parseArgs(process.argv);
  const repoRoot = REPO_ROOT;

  // Same shallow-clone precondition as contract-governance (§A.16): some gates
  // resolve a baseline ref and need full history. Gates with `baseline.kind ===
  // "ratchet-file"` don't need a git baseline; we only fail when a gate actually
  // requests one. (Falling back to a warning here is too lenient.)

  const registry = loadRegistry(args.registry);
  const gates = args.gate ? registry.gates.filter(g => g.id === args.gate) : registry.gates;
  if (args.gate && gates.length === 0) {
    console.error(`gate id '${args.gate}' not found in ${args.registry}`);
    process.exit(2);
  }

  // Tempdoc 530 §3.2: --explain <ruleId> — print description + changeset template.
  if (args.explain) {
    const { explainRule } = await import('./lib/explain.mjs');
    await explainRule({ ruleId: args.explain, gates, repoRoot: REPO_ROOT });
    process.exit(0);
  }

  // Tempdoc 530 §3.2: --suggest-changeset — walk live state, write stub changesets.
  if (args.suggestChangeset) {
    const { suggestChangesets } = await import('./lib/suggest.mjs');
    await suggestChangesets({ gates, repoRoot: REPO_ROOT });
    process.exit(0);
  }

  // Tempdoc 530 §3.5: --preflight <ref> — predict which gates would fail.
  if (args.preflight) {
    const { runPreflight } = await import('./lib/preflight.mjs');
    await runPreflight({ baselineRef: args.preflight, gates, repoRoot: REPO_ROOT });
    process.exit(0);
  }

  const runs = [];
  const verdicts = [];
  const allRuleDescriptions = {};
  const allRebalances = [];

  if (args.selfTest) {
    let selfTestFailed = false;
    for (const gate of gates) {
      const r = await runSelfTest(gate);
      if (r.skipped) {
        console.log(`self-test: ${r.gate}: skipped (${r.reason})`);
        continue;
      }
      for (const fr of r.results) {
        if (!fr.passed) {
          selfTestFailed = true;
          console.error(
            `self-test mismatch: ${r.gate}/${fr.flavor} expected ${
              fr.expectFail ? 'fail' : 'pass'
            }, got ${fr.verdict}`,
          );
          for (const f of fr.findings) {
            console.error(`  - ${f.ruleId} (${f.level}): ${f.message}`);
          }
        } else {
          console.log(`self-test: ${r.gate}/${fr.flavor}: ${fr.verdict} (expected)`);
        }
      }
    }
    if (selfTestFailed) {
      console.error('self-test failed; gate machinery may be broken');
      process.exit(args.mode === 'gate' ? 1 : 0);
    }
    process.exit(0);
  }

  // Resolve baseline refs once. Two cases:
  //  - `kind === 'git'`: gate's primary baseline IS a git ref (e.g., wire Category).
  //  - `kind === 'ratchet-file'` + `diffStrategy: 'git-base'`: gate's primary
  //    baseline is a checked-in file, but pin-bump / baseline-shift detection
  //    needs the file's previous state — read from baselineRef.
  // Without a baseline ref, pin-bump detection silently degrades to off. This
  // closes the tempdoc 530 §Layer 1 silent-pin-bump pattern: a commit that
  // grows code AND raises the pin in the same diff is caught by reading the
  // pin from baselineRef, not the live file.
  for (const gate of gates) {
    let baselineRef = null;
    const needsGit =
      gate.baseline?.kind === 'git' ||
      (gate.baseline?.kind === 'ratchet-file' && gate.baseline?.diffStrategy === 'git-base');
    if (needsGit) {
      if (isShallowRepository(repoRoot)) {
        console.error(
          `shallow clone detected; gate '${gate.id}' requires full git history. ` +
            `Re-run with 'fetch-depth: 0'.`,
        );
        process.exit(2);
      }
      try {
        const resolveSpec =
          gate.baseline.kind === 'git'
            ? gate.baseline
            : { strategy: gate.baseline.diffStrategy, fallback: gate.baseline.diffFallback };
        const resolved = resolveBaselineRef(resolveSpec, repoRoot);
        baselineRef = resolved.ref;
      } catch (err) {
        // No baseline ref reachable (likely a single-commit repo). Continue
        // without pin-bump detection; the gate still enforces the ratchet
        // against the live state.
        console.warn(`gate '${gate.id}': baseline ref unreachable (${err.message}); ` +
          `pin-bump detection disabled for this run.`);
      }
    }

    const r = await runGate(gate, args, baselineRef);
    runs.push({
      toolName: r.toolName,
      toolVersion: r.toolVersion,
      axis: 'discipline-gate',
      categoryId: gate.id,
      findings: r.findings,
    });
    verdicts.push({ gate: gate.id, verdict: r.verdict });
    Object.assign(allRuleDescriptions, r.ruleDescriptions ?? {});
    if (r.rebalanceWrites?.length) {
      allRebalances.push({ gate: gate.id, writes: r.rebalanceWrites });
    }
  }

  // Emit SARIF.
  const sarif = emitSarif(runs, { ruleDescriptions: allRuleDescriptions });
  const outPath = resolve(repoRoot, args.out);
  mkdirSync(dirname(outPath), { recursive: true });
  writeFileSync(outPath, JSON.stringify(sarif, null, 2) + '\n');

  // Layer 3 §3.7a: append run-history for dashboard generation.
  try { appendRunRecord({ repoRoot, runs, verdicts }); } catch { /* history append is best-effort */ }

  // Aggregate gate decision.
  const anyFail = verdicts.some(v => v.verdict === 'fail');
  const totalFindings = runs.reduce((acc, r) => acc + r.findings.length, 0);

  console.log(
    `governance: ${verdicts.length} gate${verdicts.length === 1 ? '' : 's'} evaluated, ` +
      `${verdicts.filter(v => v.verdict === 'fail').length} fail, ` +
      `${totalFindings} findings, SARIF: ${args.out}`,
  );
  for (const v of verdicts) {
    console.log(`  ${v.gate}: ${v.verdict}`);
  }
  // Polish §14: --format compact also prints one line per error-level finding.
  if (args.format === 'compact') {
    for (const r of runs) {
      for (const f of r.findings) {
        if (f.level !== 'error') continue;
        const trimmed = f.message.replace(/\s+/g, ' ').slice(0, 160);
        console.log(`    [${r.categoryId}] ${f.ruleId}: ${trimmed}`);
      }
    }
  }
  if (args.rebalance && allRebalances.length > 0) {
    for (const rb of allRebalances) {
      console.log(`  ${rb.gate}: applied ${rb.writes.length} rebalance write(s)`);
    }
  }

  if (args.mode === 'gate' && anyFail) process.exit(1);
  process.exit(0);
}

main().catch(err => {
  console.error('governance runner error:', err.message);
  if (process.env.GOVERNANCE_DEBUG === '1') {
    console.error(err.stack);
  }
  process.exit(2);
});
