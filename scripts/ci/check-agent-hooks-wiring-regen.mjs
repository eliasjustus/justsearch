#!/usr/bin/env node
/**
 * Tempdoc 592 (rung 1) — CI gate for the hook-wiring codegen.
 * Runs `gen-agent-hooks-wiring.mjs --check`: non-zero exit if .claude/settings.local.json's
 * hooks block has drifted from governance/agent-hooks.v1.json.
 */
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-agent-hooks-wiring.mjs');

const result = spawnSync('node', [GEN_SCRIPT, '--check'], { cwd: REPO_ROOT, stdio: 'inherit' });
process.exit(result.status ?? 1);
