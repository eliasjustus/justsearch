#!/usr/bin/env node
/**
 * Tempdocs 583 §D.3d + 893 §D.2 — CI gate for both route-snapshot derivatives.
 *
 * The existing CI-wired entry point checks both the typed client and the classified reference-client
 * OpenAPI snapshot. It stops at the first failure and preserves that child's non-zero status.
 *
 * Scope (honest, per §D.7): this guards derivative↔snapshot coherence only — it does
 * NOT (and can't, ADR-0026: no live backend in CI) verify the route snapshot still
 * matches the live route surface. Re-run `gen-api-client.mjs --from-live=<baseUrl>`
 * after changing the route surface to refresh the snapshot.
 *
 * Invoked by:
 *   node scripts/ci/check-api-client-regen.mjs
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');
const GEN_SCRIPT = join(REPO_ROOT, 'scripts', 'codegen', 'gen-api-client.mjs');
const OPENAPI_CHECK = join(REPO_ROOT, 'scripts', 'ci', 'check-reference-client-openapi-regen.mjs');

export function runApiProjectionChecks(run = spawnSync) {
  const checks = [
    [process.execPath, [GEN_SCRIPT, '--check']],
    [process.execPath, [OPENAPI_CHECK]],
  ];
  for (const [command, args] of checks) {
    const result = run(command, args, { cwd: REPO_ROOT, stdio: 'inherit' });
    if (result.error || result.status !== 0) return result.status ?? 1;
  }
  return 0;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) process.exit(runApiProjectionChecks());
