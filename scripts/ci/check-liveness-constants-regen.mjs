#!/usr/bin/env node
/**
 * Tempdoc 575 §17 Face A — CI gate for the register → {Java, TS} liveness-constant codegen.
 *
 * Runs `gen-liveness-constants.mjs --check`: non-zero exit if the generated worker constant
 * (`modules/worker-core/.../liveness/LivenessWindows.java`) or FE constant
 * (`modules/ui-web/src/api/generated/liveness-constants.ts`) has drifted from the register
 * (`governance/observed-happening.v1.json`'s `liveness` block) — i.e. someone changed the
 * register without re-running the generator, or hand-edited a generated file. The generator
 * ALSO validates the ordering law and throws on an incoherent window, so this gate is the
 * replacement teeth for the retired `observed-happening/liveness-window-coherent` rule.
 *
 * Invoked by: node scripts/ci/check-liveness-constants-regen.mjs
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-liveness-constants.mjs');

const result = spawnSync('node', [GEN_SCRIPT, '--check'], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
});
process.exit(result.status ?? 1);
