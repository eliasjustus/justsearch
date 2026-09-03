#!/usr/bin/env node
/** Project cross-harness invariants from AGENTS.md into Claude's adapter. */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { hardInvariants } from '../agent-analytics/lib/hard-invariants.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const CLAUDE = path.join(ROOT, 'CLAUDE.md');
const START = '<!-- generated:agent-invariants:start — source: AGENTS.md; run: node scripts/docs/agent-instructions-sync.mjs -->';
const END = '<!-- generated:agent-invariants:end -->';
const RULE_IDS = [
  'head-never-touches-lucene',
  'loopback-only-network',
  'no-legacy-endpoints',
  'verify-dont-guess',
  'frontend-stack-is-lit',
  'language-agnostic-analysis',
];

export function renderInvariantProjection() {
  const invariants = hardInvariants();
  if (invariants.length !== RULE_IDS.length) {
    throw new Error(`expected ${RULE_IDS.length} AGENTS.md invariants, found ${invariants.length}`);
  }
  return [
    START,
    ...invariants.map((text, index) => `${index + 1}. ${text} <!-- rule:${RULE_IDS[index]} -->`),
    END,
  ].join('\n');
}

export function expectedClaude() {
  const current = fs.readFileSync(CLAUDE, 'utf8').replace(/\r\n/g, '\n');
  const start = current.indexOf(START);
  const end = current.indexOf(END);
  if (start < 0 || end < start) throw new Error('CLAUDE.md invariant projection markers are missing');
  return current.slice(0, start) + renderInvariantProjection() + current.slice(end + END.length);
}

function main() {
  const expected = expectedClaude();
  const current = fs.readFileSync(CLAUDE, 'utf8').replace(/\r\n/g, '\n');
  if (process.argv.includes('--check')) {
    if (current !== expected) {
      console.error('agent-instructions-sync --check: CLAUDE.md invariants drifted from AGENTS.md');
      process.exit(1);
    }
    console.log('agent-instructions-sync --check: OK');
    return;
  }
  fs.writeFileSync(CLAUDE, expected, 'utf8');
  console.log('agent-instructions-sync: projected AGENTS.md invariants into CLAUDE.md');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
