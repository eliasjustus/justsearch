#!/usr/bin/env node
/**
 * Tempdoc 592 (rung 1) — CI gate for the hook-wiring codegen.
 * Checks both harness projections of governance/agent-hooks.v1.json.
 */
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-agent-hooks-wiring.mjs');
const CODEX_GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-codex-hooks.mjs');

for (const script of [GEN_SCRIPT, CODEX_GEN_SCRIPT]) {
  const result = spawnSync('node', [script, '--check'], { cwd: REPO_ROOT, stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
