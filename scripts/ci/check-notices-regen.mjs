#!/usr/bin/env node
/**
 * Tempdoc 632 — CI gate for the NOTICE / THIRD_PARTY_NOTICES projection.
 *
 * Runs `gen-notices.mjs --check`: non-zero exit if the committed `NOTICE` or `THIRD_PARTY_NOTICES`
 * has drifted from its sources (model-registry license field, the jk1 dependency report, the npm
 * license dump, the native-binary manifests), or if a presence check fails (a model without a
 * license, the two registry copies diverging, or an un-attributed bundled Tesseract DLL).
 * Mirrors check-field-constants-regen.mjs. The report inputs must be generated first
 * (`gradlew generateLicenseReport` + `license-checker --production --json`).
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-notices.mjs');

const result = spawnSync('node', [GEN_SCRIPT, '--check'], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
});
process.exit(result.status ?? 1);
