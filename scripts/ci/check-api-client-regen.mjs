#!/usr/bin/env node
/**
 * Tempdoc 583 §D.3d — CI gate for the typed FE API-route client codegen.
 *
 * Runs `gen-api-client.mjs --check`: non-zero exit on any drift between the
 * codegen output and the committed `modules/ui-web/src/api/generated/apiRoutes.ts`,
 * i.e. someone updated the committed route snapshot
 * (`modules/ui-web/src/api/generated/route-manifest.snapshot.json`) without
 * regenerating + committing the typed client (`apiRoutes.ts`).
 *
 * Scope (honest, per §D.7): this guards client↔snapshot coherence only — it does
 * NOT (and can't, ADR-0026: no live backend in CI) verify the snapshot still
 * matches the live route surface. Re-run `gen-api-client.mjs --from-live=<baseUrl>`
 * after changing the route surface to refresh the snapshot.
 *
 * Invoked by:
 *   node scripts/ci/check-api-client-regen.mjs
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-api-client.mjs');

const result = spawnSync('node', [GEN_SCRIPT, '--check'], {
  cwd: REPO_ROOT,
  stdio: 'inherit',
});
process.exit(result.status ?? 1);
