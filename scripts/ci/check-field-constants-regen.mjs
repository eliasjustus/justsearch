#!/usr/bin/env node
/**
 * Tempdoc 594 Move 1a — CI gate for the SSOT → FE field-constant codegen.
 *
 * Runs `gen-field-constants.mjs --check`: non-zero exit if the generated FE constant
 * (`modules/ui-web/src/api/generated/field-constants.ts`) has drifted from the catalog
 * (`SSOT/catalogs/fields.v1.json`'s `vector` field dimension). Mirrors
 * check-liveness-constants-regen.mjs (575 §17 Face A).
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-field-constants.mjs');

const result = spawnSync('node', [GEN_SCRIPT, '--check'], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
});
process.exit(result.status ?? 1);
