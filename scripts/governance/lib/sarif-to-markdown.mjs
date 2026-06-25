/**
 * SARIF → markdown converter — Layer 3 §3.4 of tempdoc 530.
 *
 * Converts a SARIF v2.1.0 document (as emitted by `scripts/governance/run.mjs`)
 * into a collapsible markdown summary suitable for `$GITHUB_STEP_SUMMARY`
 * (cheap path) or PR comments (heavier path; requires `pull-requests: write`).
 *
 * Usage:
 *   node scripts/governance/lib/sarif-to-markdown.mjs --in tmp/governance-report.sarif
 *     [--out tmp/governance-summary.md]
 *
 * If --out is omitted, prints to stdout (suitable for `>> $GITHUB_STEP_SUMMARY`).
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

function parseArgs(argv) {
  const out = { in: 'tmp/governance-report.sarif', out: null };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--in') out.in = argv[++i];
    else if (a === '--out') out.out = argv[++i];
    else if (a === '-h' || a === '--help') {
      console.log('Usage: node scripts/governance/lib/sarif-to-markdown.mjs --in <sarif> [--out <md>]');
      process.exit(0);
    }
  }
  return out;
}

function levelEmoji(level) {
  switch (level) {
    case 'error': return '🛑';
    case 'warning': return '⚠️';
    case 'note': return 'ℹ️';
    default: return '·';
  }
}

export function sarifToMarkdown(sarif) {
  const lines = [];
  lines.push('## Discipline-gate kernel report');
  lines.push('');

  const runs = sarif.runs ?? [];
  if (runs.length === 0) {
    lines.push('_No gates evaluated._');
    return lines.join('\n');
  }

  // Aggregate counts.
  let totalErrors = 0, totalWarnings = 0, totalNotes = 0;
  const perGate = {};
  for (const run of runs) {
    const gateId = run.properties?.categoryId ?? run.tool?.driver?.name ?? 'unknown';
    perGate[gateId] = perGate[gateId] ?? { error: 0, warning: 0, note: 0, results: [] };
    for (const result of run.results ?? []) {
      const lvl = result.level ?? 'note';
      perGate[gateId][lvl] = (perGate[gateId][lvl] ?? 0) + 1;
      perGate[gateId].results.push(result);
      if (lvl === 'error') totalErrors++;
      else if (lvl === 'warning') totalWarnings++;
      else if (lvl === 'note') totalNotes++;
    }
  }

  // Summary line.
  const verdict = totalErrors === 0 ? '✅ pass' : '🛑 fail';
  lines.push(`**Verdict:** ${verdict}  ·  ${totalErrors} error · ${totalWarnings} warning · ${totalNotes} note`);
  lines.push('');

  // Per-gate table.
  lines.push('| Gate | Errors | Warnings | Notes |');
  lines.push('|---|---:|---:|---:|');
  for (const [gateId, counts] of Object.entries(perGate).sort()) {
    lines.push(`| \`${gateId}\` | ${counts.error || 0} | ${counts.warning || 0} | ${counts.note || 0} |`);
  }
  lines.push('');

  // Per-gate details (collapsible).
  for (const [gateId, counts] of Object.entries(perGate).sort()) {
    if (counts.results.length === 0) continue;
    lines.push(`<details>`);
    lines.push(`<summary><code>${gateId}</code> findings (${counts.results.length})</summary>`);
    lines.push('');
    lines.push('| Level | Rule | Message |');
    lines.push('|---|---|---|');
    for (const r of counts.results) {
      const ruleId = r.ruleId ?? '?';
      const msg = (r.message?.text ?? '').replace(/\|/g, '\\|').slice(0, 200);
      lines.push(`| ${levelEmoji(r.level)} ${r.level} | \`${ruleId}\` | ${msg} |`);
    }
    lines.push('');
    lines.push('</details>');
    lines.push('');
  }

  return lines.join('\n');
}

async function main() {
  const args = parseArgs(process.argv);
  const inPath = resolve(process.cwd(), args.in);
  if (!existsSync(inPath)) {
    console.error(`SARIF not found: ${inPath}`);
    process.exit(2);
  }
  const sarif = JSON.parse(readFileSync(inPath, 'utf8'));
  const md = sarifToMarkdown(sarif);
  if (args.out) {
    writeFileSync(resolve(process.cwd(), args.out), md + '\n');
  } else {
    process.stdout.write(md + '\n');
  }
}

// Only run main() if this file is invoked directly, not on import.
// On Windows, process.argv[1] is a Windows path; import.meta.url is a file://
// URL with forward slashes. Compare via the URL's pathname.
const invokedDirectly = (() => {
  if (!process.argv[1]) return false;
  try {
    const fromUrl = new URL(import.meta.url).pathname.replace(/^\//, '').toLowerCase();
    const fromArgv = process.argv[1].replace(/\\/g, '/').toLowerCase();
    return fromArgv.endsWith(fromUrl) || fromUrl.endsWith(fromArgv);
  } catch {
    return false;
  }
})();
if (invokedDirectly) {
  main().catch(err => {
    console.error('sarif-to-markdown error:', err.message);
    process.exit(2);
  });
}
