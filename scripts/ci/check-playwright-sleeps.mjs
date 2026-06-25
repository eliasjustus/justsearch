#!/usr/bin/env node
/**
 * Local ratchet: detect raw Playwright fixed sleeps (page.waitForTimeout).
 *
 * Goal: prevent regressions (new sleeps) while we migrate tests to deterministic waits.
 *
 * Usage:
 *   node scripts/ci/check-playwright-sleeps.mjs --mode warn
 *   node scripts/ci/check-playwright-sleeps.mjs --mode gate
 *   node scripts/ci/check-playwright-sleeps.mjs --write-baseline
 */
/* eslint-disable no-console */

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, '..', '..');

function toPosix(p) {
  return String(p).split(path.sep).join('/');
}

function parseArgs(argv) {
  const out = {
    mode: 'warn', // warn|gate
    root: 'modules/ui-web/e2e',
    baseline: 'scripts/ci/determinism-baseline.json',
    writeBaseline: false,
    json: false,
  };

  const args = [...argv];
  const takeValue = (i) => {
    const token = args[i];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    const next = args[i + 1];
    if (next == null) return null;
    return next;
  };

  for (let i = 0; i < args.length; i += 1) {
    const token = args[i];
    if (!token.startsWith('--')) continue;
    const key = token.split('=')[0];
    const hasInline = token.includes('=');
    const value = takeValue(i);
    if (!hasInline) i += 1;

    switch (key) {
      case '--help':
      case '-h':
        out.mode = 'help';
        break;
      case '--mode':
        out.mode = String(value || '').toLowerCase();
        break;
      case '--gate':
        out.mode = 'gate';
        break;
      case '--warn':
        out.mode = 'warn';
        break;
      case '--root':
        out.root = String(value || '');
        break;
      case '--baseline':
        out.baseline = String(value || '');
        break;
      case '--write-baseline':
        out.writeBaseline = true;
        break;
      case '--json':
        out.json = true;
        break;
      default:
        throw new Error(`Unknown flag: ${key}`);
    }
  }

  if (!['warn', 'gate', 'help'].includes(out.mode)) {
    throw new Error(`Invalid --mode: ${out.mode} (expected warn|gate)`);
  }
  if (!out.root) throw new Error('Missing --root');
  if (!out.baseline) throw new Error('Missing --baseline');
  return out;
}

function printUsageAndExit(code = 1) {
  console.error(
    [
      'Usage: node scripts/ci/check-playwright-sleeps.mjs [options]',
      '',
      'Options:',
      '  --mode warn|gate         Default: warn',
      '  --root <dir>             Default: modules/ui-web/e2e',
      '  --baseline <file>        Default: scripts/ci/determinism-baseline.json',
      '  --write-baseline         Writes baseline JSON and exits 0',
      '  --json                   JSON output (single object)',
      '',
    ].join('\n'),
  );
  process.exit(code);
}

async function listFilesRecursive(dirAbs) {
  const out = [];
  const ents = await fsp.readdir(dirAbs, { withFileTypes: true });
  for (const ent of ents) {
    const p = path.join(dirAbs, ent.name);
    if (ent.isDirectory()) {
      // eslint-disable-next-line no-await-in-loop
      out.push(...(await listFilesRecursive(p)));
      continue;
    }
    if (!ent.isFile()) continue;
    // Focus on test files; helpers may legitimately contain waitForTimeout wrappers.
    if (!ent.name.endsWith('.spec.ts') && !ent.name.endsWith('.spec.tsx')) continue;
    out.push(p);
  }
  return out;
}

function countNeedles(text) {
  const re = /\bpage\.waitForTimeout\(/g;
  let n = 0;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const m = re.exec(text);
    if (!m) break;
    n += 1;
  }
  return n;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.mode === 'help') printUsageAndExit(0);

  const rootAbs = path.isAbsolute(opts.root) ? opts.root : path.resolve(repoRoot, opts.root);
  const baselineAbs = path.isAbsolute(opts.baseline) ? opts.baseline : path.resolve(repoRoot, opts.baseline);

  const filesAbs = await listFilesRecursive(rootAbs);
  const counts = {};
  let total = 0;
  for (const f of filesAbs) {
    // eslint-disable-next-line no-await-in-loop
    const raw = await fsp.readFile(f, 'utf8');
    const n = countNeedles(raw);
    if (n <= 0) continue;
    const relPosix = toPosix(path.relative(repoRoot, f));
    counts[relPosix] = n;
    total += n;
  }

  const snapshot = {
    schema: 'playwright-sleeps-snapshot.v1',
    generatedAt: new Date().toISOString(),
    root: toPosix(path.relative(repoRoot, rootAbs)),
    needle: 'page.waitForTimeout(',
    total,
    files: counts,
  };

  if (opts.writeBaseline) {
    await fsp.mkdir(path.dirname(baselineAbs), { recursive: true });
    await fsp.writeFile(baselineAbs, JSON.stringify(snapshot, null, 2) + '\n', 'utf8');
    if (!opts.json) console.log(`Wrote baseline: ${toPosix(path.relative(repoRoot, baselineAbs))}`);
    process.exit(0);
  }

  let baseline = null;
  if (fs.existsSync(baselineAbs) && fs.statSync(baselineAbs).isFile()) {
    baseline = JSON.parse(await fsp.readFile(baselineAbs, 'utf8'));
  }

  const violations = [];
  if (opts.mode === 'gate') {
    if (!baseline) {
      violations.push({ kind: 'missing_baseline', message: `Baseline missing: ${baselineAbs}` });
    } else {
      const baseFiles = baseline?.files && typeof baseline.files === 'object' ? baseline.files : {};
      for (const [file, n] of Object.entries(counts)) {
        const baseN = Number(baseFiles[file] ?? 0);
        if (n > baseN) {
          violations.push({ kind: 'increase', file, observed: n, baseline: baseN });
        }
      }
      // New files with sleeps (baseline=0 implied) already covered by baseN default 0.
    }
  }

  const ok = violations.length === 0;
  const out = { ok, mode: opts.mode, baseline: toPosix(path.relative(repoRoot, baselineAbs)), snapshot, violations };

  if (opts.json) {
    process.stdout.write(JSON.stringify(out) + '\n');
  } else {
    console.log(`Playwright sleeps: total=${total} files=${Object.keys(counts).length}`);
    const top = Object.entries(counts)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 10);
    for (const [file, n] of top) console.log(`  ${n}\t${file}`);
    if (opts.mode === 'gate') {
      if (ok) console.log('OK (no increases vs baseline).');
      else {
        console.error('FAIL (sleep increases vs baseline):');
        for (const v of violations) {
          if (v.kind === 'missing_baseline') console.error(`  ${v.message}`);
          else console.error(`  ${v.file}: observed=${v.observed} baseline=${v.baseline}`);
        }
      }
    }
  }

  process.exit(ok ? 0 : 1);
}

main().catch((err) => {
  const json = process.argv.includes('--json');
  if (json) {
    process.stdout.write(JSON.stringify({ ok: false, error: { message: err?.message || String(err) } }) + '\n');
  } else {
    console.error(err?.stack || String(err));
  }
  process.exit(1);
});


