#!/usr/bin/env node
/**
 * Tempdoc 564 — CI gate for the JSON-Schema → {TS, Zod} codegen.
 *
 * Runs `gen-wire-schema-types.mjs --check`: non-zero exit on any drift between
 * the codegen output and the committed
 * `modules/ui-web/src/api/generated/schema-types/*.ts` files. Catches the case
 * where someone regenerates an `SSOT/schemas/*.v1.json` (e.g. via
 * `:modules:app-api:updateSchemas`) without committing the regenerated FE
 * types + Zod schemas — i.e. the generated projection drifting from its source.
 *
 * Invoked by:
 *   node scripts/ci/check-wire-schema-types-regen.mjs
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-wire-schema-types.mjs');

const result = spawnSync('node', [GEN_SCRIPT, '--check'], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
});
process.exit(result.status ?? 1);
