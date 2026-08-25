/**
 * hook-integrity enforcer — tempdoc 592 (rung 2).
 *
 * Verifies the agent-analytics hook layer is actually ENFORCING, not merely
 * present. Reads the single-authority manifest governance/agent-hooks.v1.json and
 * checks, against the live .claude/settings.local.json + hook files:
 *   - wiring     : every binding resolves to a catalog hook; every catalog hook is bound.
 *   - live-wiring: every catalog hook is ALSO present in the live settings.local.json —
 *                  the only file Claude Code reads. `wiring: "opt-in"` opts out on record.
 *   - cwd-invariant: every settings command is the ${CLAUDE_PROJECT_DIR} exec-form
 *                    (the regression teeth for the 592 crash class).
 *   - load       : every hook file parses (`node --check`) — crash-on-load can't ship.
 *   - bite       : every blocking hook emits its block signal on a violating fixture
 *                  (command-signal), or references a unit test that proves its core.
 *   - tier-sync  : every `hook:` marker in the tier-register resolves to the manifest.
 *   - orphan     : every hook FILE on disk (excluding *.test.mjs) appears in the manifest
 *                  catalog — the FILE->manifest direction (tempdoc 861 Phase 6). The five
 *                  phases above all start FROM the manifest and check outward; none of them
 *                  ever lists the hook directory, so a file that never made it into the
 *                  manifest was invisible to every one of them. That is how
 *                  `ui-shot-cleanup.mjs` sat on disk, described as live by an always-loaded
 *                  rules file, for the project's whole public history while never once firing
 *                  (861 §3c) — this phase is the gate closing over that specific gap.
 *
 * Honest scope (Wall 2): the bite test proves the hook EMITS its block signal; it
 * cannot prove the harness HONORS it for every tool/event (Claude Code exit-2
 * blocking is known-inconsistent for some non-Bash tools). Signal-emission is the
 * controllable contract.
 */

import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, rmSync, mkdtempSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

import { HOOK_INTEGRITY_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';
import {
  verdictForBinding,
  verdictForCatalogBound,
  verdictForLiveWiring,
  verdictForCwdInvariantCommand,
  verdictForLoad,
  verdictForBite,
  verdictForBiteDeclared,
  verdictForTierRegisterSync,
  verdictForOrphanHookFile,
} from './truth-table.mjs';

const TOOL = { toolName: 'justsearch-hook-integrity', toolVersion: '0.1.0' };
const RD = HOOK_INTEGRITY_RULE_DESCRIPTIONS;

function fail(ruleId, message, uri) {
  return { ruleId, level: 'error', message, uri };
}

/** Walk every command entry in a settings hooks block. */
function* walkCommands(hooks) {
  for (const [event, groups] of Object.entries(hooks ?? {})) {
    for (let gi = 0; gi < groups.length; gi++) {
      const list = groups[gi].hooks ?? [];
      for (let hi = 0; hi < list.length; hi++) {
        yield { event, location: `${event}[${gi}].hooks[${hi}]`, entry: list[hi] };
      }
    }
  }
}

function isExecForm(entry) {
  return (
    entry.command === 'node' &&
    Array.isArray(entry.args) &&
    typeof entry.args[0] === 'string' &&
    entry.args[0].startsWith('${CLAUDE_PROJECT_DIR}/')
  );
}

/** Run a command-signal bite: spawn the real hook with a violating payload, check the signal. */
function runCommandSignalBite({ hookFile, bite, repoRoot }) {
  let stdin = JSON.parse(JSON.stringify(bite.stdin ?? {}));
  let tmpDir = null;
  let markerSession = null;
  try {
    if (Array.isArray(bite.transcript)) {
      tmpDir = mkdtempSync(join(tmpdir(), 'hook-bite-'));
      const transcriptPath = join(tmpDir, 'transcript.jsonl');
      writeFileSync(transcriptPath, bite.transcript.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
      markerSession = `__bite__${process.pid}_${Date.now()}`;
      // Substitute placeholders the manifest declares.
      for (const k of Object.keys(stdin)) {
        if (stdin[k] === '__BITE_TRANSCRIPT__') stdin[k] = transcriptPath;
        if (stdin[k] === '__BITE_SESSION__') stdin[k] = markerSession;
      }
    }
    const res = spawnSync('node', [hookFile], {
      input: JSON.stringify(stdin),
      encoding: 'utf8',
      timeout: 10000,
    });
    const exp = bite.expect ?? {};
    if ('exitCode' in exp) {
      if (res.status === exp.exitCode) return { satisfied: true, detail: `exit ${res.status}` };
      return { satisfied: false, detail: `expected exit ${exp.exitCode}, got ${res.status} (stderr: ${(res.stderr || '').slice(0, 200)})` };
    }
    if ('stdoutIncludes' in exp) {
      if ((res.stdout || '').includes(exp.stdoutIncludes)) return { satisfied: true, detail: 'block signal emitted' };
      return { satisfied: false, detail: `stdout did not include ${JSON.stringify(exp.stdoutIncludes)} (got: ${(res.stdout || '').slice(0, 200)})` };
    }
    return { satisfied: false, detail: 'bite.expect declares neither exitCode nor stdoutIncludes' };
  } finally {
    if (tmpDir) rmSync(tmpDir, { recursive: true, force: true });
    if (markerSession) {
      rmSync(join(repoRoot, 'tmp', 'agent-telemetry', `maintain-nudged-${markerSession}.json`), { force: true });
    }
  }
}

export async function enforceHookIntegrity(options) {
  const { repoRoot, gate, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const manifestPath = resolve(sourceRoot, gate.config?.manifest ?? 'governance/agent-hooks.v1.json');
  const settingsPath = resolve(sourceRoot, '.claude/settings.local.json');
  // Relocated out of always-loaded context by tempdoc 799 K.2. Config-driven so a
  // future move is a registry edit, not a code edit (the trap this gate itself hit).
  const tierRegisterPath = resolve(
    sourceRoot,
    gate.config?.tierRegister ?? 'docs/reference/contributing/tier-register.md',
  );

  const findings = [];
  let verdict = 'pass';
  const push = (v, uri) => {
    if (v.status === 'fail') {
      verdict = 'fail';
      findings.push(fail(v.ruleId, v.reason, uri));
    }
  };

  if (!existsSync(manifestPath)) {
    return { ...TOOL, findings: [fail('hook-integrity/manifest-missing', `manifest not found at ${manifestPath}`, gate.config?.manifest ?? 'governance/agent-hooks.v1.json')], verdict: 'fail', ruleDescriptions: RD };
  }
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (e) {
    return { ...TOOL, findings: [fail('hook-integrity/manifest-unreadable', `manifest parse error: ${e.message}`, gate.config?.manifest ?? 'governance/agent-hooks.v1.json')], verdict: 'fail', ruleDescriptions: RD };
  }

  const catalog = manifest.hooks ?? {};
  const bindings = manifest.bindings ?? {};
  const hookDir = manifest.hookDir ?? 'scripts/agent-analytics/hooks';
  const manifestUri = gate.config?.manifest ?? 'governance/agent-hooks.v1.json';

  // 1. Wiring — bindings resolve; catalog hooks are bound.
  const bound = new Set();
  for (const [event, groups] of Object.entries(bindings)) {
    for (const g of groups) {
      for (const h of g.hooks ?? []) {
        bound.add(h.hookId);
        push(verdictForBinding({ event, hookId: h.hookId, resolved: !!catalog[h.hookId] }), manifestUri);
      }
    }
  }
  for (const hookId of Object.keys(catalog)) {
    push(verdictForCatalogBound({ hookId, bound: bound.has(hookId) }), manifestUri);
  }

  // 2. cwd-invariant commands — scan the LIVE settings (catches a hand-edit bypassing the manifest).
  //    2b. LIVE WIRING (added 2026-08-18): rule 1 above only proves the manifest is
  //    internally consistent — catalog vs its own `bindings`. It says nothing about the
  //    file Claude Code actually reads. That gap let SEVEN hooks (one of them blocking)
  //    sit registered, bite-tested, and present in settings.local.json.example while
  //    never firing in any real session, for over a month, with this gate green. A hook
  //    that is not in the live settings does not exist at runtime, so assert it here.
  //    A hook may opt out deliberately by declaring `"wiring": "opt-in"` in the catalog
  //    (with a `wiringNote` saying why) — that is a decision on the record, not drift.
  if (existsSync(settingsPath)) {
    let settings;
    try {
      settings = JSON.parse(readFileSync(settingsPath, 'utf8'));
      for (const { location, entry } of walkCommands(settings.hooks)) {
        const repr = entry.command === 'node' && Array.isArray(entry.args) ? `node ${entry.args.join(' ')}` : String(entry.command);
        push(verdictForCwdInvariantCommand({ location, isExecForm: isExecForm(entry), commandRepr: repr }), '.claude/settings.local.json');
      }
      const liveText = JSON.stringify(settings.hooks ?? {});
      for (const [hookId, meta] of Object.entries(catalog)) {
        const file = meta?.file ?? `${hookId}.mjs`;
        push(verdictForLiveWiring({
          hookId,
          live: liveText.includes(file),
          optIn: meta?.wiring === 'opt-in',
        }), '.claude/settings.local.json');
      }
    } catch (e) {
      push({ ruleId: 'hook-integrity/cwd-relative-command', status: 'fail', reason: `settings parse error: ${e.message}` }, '.claude/settings.local.json');
    }
  }

  // 3. Load — each catalog hook's full import graph resolves (tempdoc §4.II:
  //    "spawn each hook with a benign payload and assert it does not crash on load").
  //    We spawn the hook for real with `{}` on stdin AND JUSTSEARCH_DISABLE_HOOKS=1:
  //    the module + all its imports must resolve BEFORE main() runs/skips, so a broken
  //    or missing import exits non-zero pre-main (caught); kill-switch-aware hooks skip
  //    main (no side effects) and the rest read `{}`+EOF and return early. Either way a
  //    clean hook exits 0 — the only non-zero exit is a load crash. This catches the
  //    broken-import class that `node --check` (syntax-only) would miss.
  for (const [hookId, entry] of Object.entries(catalog)) {
    const file = resolve(sourceRoot, hookDir, entry.file);
    if (!existsSync(file)) {
      push(verdictForLoad({ hookId, loaded: false, detail: `file not found: ${join(hookDir, entry.file)}` }), join(hookDir, entry.file));
      continue;
    }
    const res = spawnSync('node', [file], {
      input: '{}',
      encoding: 'utf8',
      timeout: 10000,
      env: { ...process.env, JUSTSEARCH_DISABLE_HOOKS: '1' },
    });
    const loaded = res.status === 0;
    const detail = loaded ? '' : `exit ${res.status}: ${(res.stderr || '').trim().slice(0, 300)}`;
    push(verdictForLoad({ hookId, loaded, detail }), join(hookDir, entry.file));
  }

  // 4. Bite — every blocking hook has bite evidence.
  for (const [hookId, entry] of Object.entries(catalog)) {
    if (entry.role !== 'blocking') continue;
    push(verdictForBiteDeclared({ hookId, hasBite: !!entry.bite }), manifestUri);
    if (!entry.bite) continue;
    const file = resolve(sourceRoot, hookDir, entry.file);
    if (entry.bite.kind === 'unit') {
      const testPath = resolve(sourceRoot, entry.bite.test);
      push(verdictForBite({ hookId, satisfied: existsSync(testPath), kind: 'unit', detail: existsSync(testPath) ? 'unit test present' : `unit test not found: ${entry.bite.test}` }), entry.bite.test);
    } else if (entry.bite.kind === 'command-signal') {
      if (!existsSync(file)) {
        push(verdictForBite({ hookId, satisfied: false, kind: 'command-signal', detail: `hook file not found: ${join(hookDir, entry.file)}` }), join(hookDir, entry.file));
        continue;
      }
      const r = runCommandSignalBite({ hookFile: file, bite: entry.bite, repoRoot: sourceRoot });
      push(verdictForBite({ hookId, satisfied: r.satisfied, kind: 'command-signal', detail: r.detail }), join(hookDir, entry.file));
    }
  }

  // 5. Tier-register sync — every `hook:` marker resolves to a manifest catalog entry.
  if (existsSync(tierRegisterPath)) {
    const md = readFileSync(tierRegisterPath, 'utf8');
    const seen = new Set();
    for (const m of md.matchAll(/`hook:([^`]+)`/g)) {
      const file = m[1].trim();
      // Skip grammar/template placeholders (e.g. `hook:<filename>` in the format
      // section) — a real marker names a concrete `.mjs` file.
      if (!file.endsWith('.mjs') || file.includes('<')) continue;
      if (seen.has(file)) continue;
      seen.add(file);
      const id = file.replace(/\.mjs$/, '');
      push(verdictForTierRegisterSync({ marker: file, resolved: !!catalog[id] }), 'docs/reference/contributing/tier-register.md');
    }
  }

  // 6. File -> manifest — every hook FILE on disk appears in the manifest catalog (861 Phase 6).
  //    Exclude only `*.test.mjs` siblings. [A12]: the catalog's `"wiring": "opt-in"` escape is
  //    NOT consulted here — an opt-in hook is still IN the catalog (it opts out of the
  //    live-wiring check above, a different phase entirely); this check asks only whether a
  //    file on disk appears in manifest.hooks at all. Carrying the opt-in caveat into this
  //    check would create a needless exemption path, and an exemption path in an orphan check
  //    is precisely how the next orphan hides.
  const hookDirPath = resolve(sourceRoot, hookDir);
  if (existsSync(hookDirPath)) {
    const catalogFiles = new Set(Object.values(catalog).map((entry) => entry.file));
    for (const dirent of readdirSync(hookDirPath, { withFileTypes: true })) {
      if (!dirent.isFile()) continue;
      if (dirent.name.endsWith('.test.mjs')) continue;
      push(
        verdictForOrphanHookFile({ file: dirent.name, inCatalog: catalogFiles.has(dirent.name) }),
        join(hookDir, dirent.name),
      );
    }
  }

  return { ...TOOL, findings, verdict, ruleDescriptions: RD };
}
