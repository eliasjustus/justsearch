#!/usr/bin/env node
/**
 * Guardrail: block hardcoded getByTestId string literals in Playwright specs.
 *
 * Rationale:
 * - Prefer shared selector constants (`E2E_TEST_IDS`) to reduce drift and refactor breakage.
 *
 * Usage:
 *   node scripts/ci/check-playwright-hardcoded-testids.mjs --mode gate
 *   node scripts/ci/check-playwright-hardcoded-testids.mjs --mode warn
 *   node scripts/ci/check-playwright-hardcoded-testids.mjs --json
 */
/* eslint-disable no-console */

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
    mode: 'gate', // gate|warn
    root: 'modules/ui-web/e2e',
    json: false,
  };

  const args = [...argv];
  const takeValue = (i) => {
    const token = args[i];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[i + 1] ?? null;
  };
  const consumeNext = new Set();

  for (let i = 0; i < args.length; i += 1) {
    if (consumeNext.has(i)) continue;
    const token = args[i];
    if (token === '-h' || token === '--help') {
      out.mode = 'help';
      continue;
    }
    if (!token.startsWith('--')) {
      continue;
    }

    const key = token.split('=')[0];
    const inline = token.includes('=');
    const value = takeValue(i);
    if (!inline && key !== '--gate' && key !== '--warn' && key !== '--json') {
      consumeNext.add(i + 1);
    }

    switch (key) {
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
      case '--json':
        out.json = true;
        break;
      default:
        throw new Error(`Unknown flag: ${key}`);
    }
  }

  if (out.mode === 'help') return out;
  if (!['gate', 'warn'].includes(out.mode)) {
    throw new Error(`Invalid --mode: ${out.mode} (expected gate|warn)`);
  }
  if (!out.root) throw new Error('Missing --root');
  return out;
}

function printUsageAndExit(code = 1) {
  console.error(
    [
      'Usage: node scripts/ci/check-playwright-hardcoded-testids.mjs [options]',
      '',
      'Options:',
      '  --mode gate|warn         Default: gate',
      '  --root <dir>             Default: modules/ui-web/e2e',
      '  --json                   JSON output (single object)',
      '',
      'Fails in gate mode when any hardcoded getByTestId string literal is found.',
    ].join('\n'),
  );
  process.exit(code);
}

async function listSpecFilesRecursive(dirAbs) {
  const out = [];
  const entries = await fsp.readdir(dirAbs, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dirAbs, entry.name);
    if (entry.isDirectory()) {
      // eslint-disable-next-line no-await-in-loop
      out.push(...(await listSpecFilesRecursive(full)));
      continue;
    }
    if (!entry.isFile()) continue;
    if (!entry.name.endsWith('.spec.ts') && !entry.name.endsWith('.spec.tsx')) continue;
    out.push(full);
  }
  return out;
}

function lineColFromIndex(text, index) {
  let line = 1;
  let col = 1;
  for (let i = 0; i < index; i += 1) {
    if (text.charCodeAt(i) === 10) {
      line += 1;
      col = 1;
    } else {
      col += 1;
    }
  }
  return { line, col };
}

function findHardcodedTestIdLiterals(text) {
  const matches = [];
  const re = /\bgetByTestId\s*\(\s*(['"])(.*?)\1\s*\)/g;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const m = re.exec(text);
    if (!m) break;
    matches.push({
      raw: m[0],
      literal: m[2] ?? '',
      index: m.index,
    });
  }
  return matches;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.mode === 'help') printUsageAndExit(0);

  const rootAbs = path.isAbsolute(opts.root) ? opts.root : path.resolve(repoRoot, opts.root);
  const filesAbs = await listSpecFilesRecursive(rootAbs);

  const findings = [];
  for (const fileAbs of filesAbs) {
    // eslint-disable-next-line no-await-in-loop
    const source = await fsp.readFile(fileAbs, 'utf8');
    const matches = findHardcodedTestIdLiterals(source);
    if (matches.length === 0) continue;
    const rel = toPosix(path.relative(repoRoot, fileAbs));
    for (const m of matches) {
      const { line, col } = lineColFromIndex(source, m.index);
      findings.push({
        file: rel,
        line,
        col,
        literal: m.literal,
        raw: m.raw,
      });
    }
  }

  const byFile = {};
  for (const item of findings) {
    byFile[item.file] = (byFile[item.file] || 0) + 1;
  }
  const filesWithFindings = Object.entries(byFile)
    .map(([file, count]) => ({ file, count }))
    .sort((a, b) => b.count - a.count || a.file.localeCompare(b.file));

  const result = {
    ok: opts.mode === 'warn' ? true : findings.length === 0,
    mode: opts.mode,
    root: toPosix(path.relative(repoRoot, rootAbs)),
    policy: 'no hardcoded getByTestId string literals in Playwright specs',
    totalFindings: findings.length,
    filesWithFindings,
    findings,
  };

  if (opts.json) {
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } else {
    console.log(`Playwright hardcoded test-id literals: total=${findings.length} files=${filesWithFindings.length}`);
    for (const row of filesWithFindings.slice(0, 20)) {
      console.log(`  ${row.count}\t${row.file}`);
    }
    if (opts.mode === 'gate' && findings.length > 0) {
      console.error('FAIL: hardcoded getByTestId string literals found.');
      for (const f of findings.slice(0, 50)) {
        console.error(`  ${f.file}:${f.line}:${f.col} -> ${f.raw}`);
      }
    }
  }

  process.exit(result.ok ? 0 : 1);
}

main().catch((err) => {
  const json = process.argv.includes('--json');
  if (json) {
    process.stdout.write(
      `${JSON.stringify({ ok: false, error: { message: err?.message || String(err) } })}\n`,
    );
  } else {
    console.error(err?.stack || String(err));
  }
  process.exit(1);
});
