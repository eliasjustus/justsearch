/**
 * Tests for the controls-a11y gate (tempdoc 559 Authority V; the focus-forwarder exemption is
 * tempdoc 864 Layer 1(b)).
 *
 * The gate is driven END-TO-END against a fixture tree (`--src`), not re-implemented here: the
 * grep-grade regex plumbing — `neutralizeInterp`, the `<tag …>` match, the attribute tests — is most
 * of what can be wrong, and a test of an extracted predicate would exercise none of it.
 *
 * The exemption cases come in PAIRS. A positive control alone would pass against a gate that
 * exempted everything, so each one is stated beside the negative it must still flag.
 *
 * Run: `node scripts/ci/check-controls-a11y.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const GATE = fileURLToPath(new URL('./check-controls-a11y.mjs', import.meta.url));
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

let passed = 0;
const failures = [];

/** Run the real gate over a one-file fixture tree; returns its exit code + output. */
function runGate(template) {
  const dir = mkdtempSync(join(tmpdir(), 'controls-a11y-'));
  try {
    mkdirSync(join(dir, 'probe'), { recursive: true });
    writeFileSync(
      join(dir, 'probe', 'Probe.ts'),
      `import { html } from 'lit';\nexport const probe = () => html\`\n${template}\n\`;\n`,
      'utf8',
    );
    const run = spawnSync(process.execPath, [GATE, '--src', dir], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    });
    return { code: run.status, output: `${run.stdout}${run.stderr}` };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function ok(label, cond) {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

/** The fixture is clean iff the gate exits 0 and never names the probe file. */
function clean(label, template) {
  const { code, output } = runGate(template);
  ok(label, code === 0 && !output.includes('Probe.ts'));
}

/** The fixture is flagged iff the gate fails AND the finding is the probe's, not a stray. */
function flagged(label, template) {
  const { code, output } = runGate(template);
  ok(label, code === 1 && output.includes('Probe.ts'));
}

// ── The gate's own baseline: it still catches what it exists for ───────────────
flagged('FLAGS: a bare <div @click> (the mouse-only affordance this gate exists for)', '<div @click=${this.act}>go</div>');
clean('OK: a native <button @click>', '<button @click=${this.act}>go</button>');

// ── The focus-forwarder exemption (tempdoc 864 Layer 1(b)) ────────────────────
clean(
  'OK: marker + a lone @pointerdown (the shipped composer glass box)',
  '<div class="glass" data-focus-forward @pointerdown=${this.onGlassPointerDown}></div>',
);
flagged(
  'FLAGS: marker + @click — the marker is not a blanket opt-out',
  '<div class="glass" data-focus-forward @click=${this.onGlassClick}></div>',
);
flagged(
  'FLAGS: marker + @pointerdown AND a second handler — that element does more than forward focus',
  '<div data-focus-forward @pointerdown=${this.forward} @click=${this.act}></div>',
);
flagged(
  'FLAGS: a lone @pointerdown WITHOUT the marker — the claim has to be declared',
  '<div class="glass" @pointerdown=${this.onGlassPointerDown}></div>',
);

if (failures.length > 0) {
  console.error(`check-controls-a11y.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`check-controls-a11y.test: all ${passed} checks passed`);
