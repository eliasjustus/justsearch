#!/usr/bin/env node
/**
 * Detect circular dependencies in the UI source graph using madge.
 *
 * Contract:
 *   - Inputs: --root, --extensions, --mode warn|gate, --out
 *   - Output schema: ui-cycles-report.v1
 *   - Exit non-zero only when mode=gate and circular dependencies (or tool errors) are detected
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';

function usage(code = 1) {
  process.stderr.write(
    [
      'Usage: node scripts/ci/check-ui-cycles.mjs [options]',
      '',
      'Options:',
      '  --root <path>          Source root (default: modules/ui-web/src)',
      '  --extensions <csv>     File extensions (default: ts,tsx)',
      '  --mode <warn|gate>     Enforcement mode (default: warn)',
      '  --out <path>           JSON report path (default: tmp/ui-cycles-report.json)',
      '  -h, --help',
      '',
    ].join('\n'),
  );
  process.exit(code);
}

function parseArgs(argv) {
  const out = {
    root: 'modules/ui-web/src',
    extensions: 'ts,tsx',
    mode: 'warn',
    out: 'tmp/ui-cycles-report.json',
  };
  const args = [...argv];
  const takeValue = (i) => {
    const token = args[i];
    const eq = token.indexOf('=');
    if (eq !== -1) return token.slice(eq + 1);
    return args[i + 1] ?? null;
  };
  const consumed = new Set();
  for (let i = 0; i < args.length; i += 1) {
    if (consumed.has(i)) continue;
    const token = args[i];
    if (token === '-h' || token === '--help') usage(0);
    if (!token.startsWith('--')) continue;
    const key = token.split('=')[0];
    const inline = token.includes('=');
    const value = takeValue(i);
    if (!inline) consumed.add(i + 1);
    switch (key) {
      case '--root':
        out.root = String(value || '').trim();
        break;
      case '--extensions':
        out.extensions = String(value || '').trim();
        break;
      case '--mode':
        out.mode = String(value || '').trim().toLowerCase();
        break;
      case '--out':
        out.out = String(value || '').trim();
        break;
      default:
        throw new Error(`Unknown option: ${token}`);
    }
  }
  if (!out.root) throw new Error('Missing --root');
  if (!out.extensions) throw new Error('Missing --extensions');
  if (!out.out) throw new Error('Missing --out');
  if (!['warn', 'gate'].includes(out.mode)) throw new Error(`Invalid --mode: ${out.mode}`);
  return out;
}

function parseCyclesJson(text) {
  const raw = String(text || '').trim();
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : null;
  } catch {
    const start = raw.indexOf('[');
    const end = raw.lastIndexOf(']');
    if (start >= 0 && end > start) {
      try {
        const parsed = JSON.parse(raw.slice(start, end + 1));
        return Array.isArray(parsed) ? parsed : null;
      } catch {
        return null;
      }
    }
    return null;
  }
}

function runMadge({ rootAbs, extensions }) {
  if (process.platform === 'win32') {
    const command =
      `npx --no-install madge --circular --extensions ${extensions} --json ${JSON.stringify(rootAbs)}`;
    return spawnSync(command, {
      shell: true,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });
  }
  return spawnSync(
    'npx',
    ['--no-install', 'madge', '--circular', '--extensions', extensions, '--json', rootAbs],
    {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    },
  );
}

function buildMarkdown(report) {
  const lines = [
    '## UI Cycle Gate',
    '',
    '| Metric | Value |',
    '|---|---:|',
    `| Mode | ${report.mode} |`,
    `| Root | \`${report.root}\` |`,
    `| Cycle count | ${report.cycle_count} |`,
    `| Verdict | ${report.verdict.toUpperCase()} |`,
  ];
  if (report.violations.length > 0) {
    lines.push('', 'Violations:');
    for (const violation of report.violations) {
      lines.push(`- ${violation.kind}: ${violation.message}`);
    }
  }
  return `${lines.join('\n')}\n`;
}

async function writeJson(outPath, payload) {
  const abs = path.resolve(process.cwd(), outPath);
  await fs.mkdir(path.dirname(abs), { recursive: true });
  await fs.writeFile(abs, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  return abs;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const rootAbs = path.resolve(process.cwd(), args.root);
  const child = runMadge({ rootAbs, extensions: args.extensions });
  const parsedCycles = parseCyclesJson(child.stdout);

  const violations = [];
  if (child.error) {
    violations.push({
      kind: 'tool_error',
      message: `madge execution failed: ${String(child.error.message || child.error)}`,
    });
  }
  if (!parsedCycles && !child.error) {
    violations.push({
      kind: 'tool_error',
      message: 'failed to parse madge JSON output',
    });
  }
  const cycles = Array.isArray(parsedCycles) ? parsedCycles : [];
  if (cycles.length > 0) {
    violations.push({
      kind: 'circular_dependencies',
      message: `${cycles.length} circular dependency path(s) detected`,
    });
  }

  const report = {
    schema: 'ui-cycles-report.v1',
    generated_at: new Date().toISOString(),
    mode: args.mode,
    root: args.root,
    extensions: args.extensions.split(',').map((value) => value.trim()).filter(Boolean),
    cycle_count: cycles.length,
    cycles,
    madge: {
      exit_code: child.status ?? null,
      signal: child.signal ?? null,
      parsed_output: Array.isArray(parsedCycles),
      stdout_bytes: Buffer.byteLength(child.stdout || '', 'utf8'),
      stderr_bytes: Buffer.byteLength(child.stderr || '', 'utf8'),
      stderr_preview: String(child.stderr || '').trim().slice(0, 1000) || null,
    },
    violations,
    verdict: violations.length === 0 ? 'pass' : 'fail',
  };

  const absOut = await writeJson(args.out, report);
  const markdown = buildMarkdown(report);
  if (process.env.GITHUB_STEP_SUMMARY) {
    await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, markdown, 'utf8');
  }
  process.stdout.write(markdown);
  process.stdout.write(`Saved JSON report to ${absOut}\n`);

  if (args.mode === 'gate' && report.verdict === 'fail') {
    process.exit(1);
  }
  process.exit(0);
}

main().catch(async (error) => {
  const message = error instanceof Error ? (error.stack || error.message) : String(error);
  process.stderr.write(`[check-ui-cycles] ${message}\n`);
  process.exit(1);
});

