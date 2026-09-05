#!/usr/bin/env node
/**
 * Offline proof edge for tempdoc 893 §D.2.
 *
 * The Java exporter reads the committed route-manifest capture and checks the committed classified
 * OpenAPI snapshot. This proves snapshot -> derivative only; it does not prove that either snapshot
 * matches a current Head. Use gen-api-client.mjs --from-live=<baseUrl> for that separate proof edge.
 */

import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const REPO_ROOT = join(dirname(__filename), '..', '..');
const wrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
const result = spawnSync(
  join(REPO_ROOT, wrapper),
  [':modules:ui:generateReferenceClientOpenApiSnapshot', '-PreferenceClientOpenApiCheck=true'],
  { cwd: REPO_ROOT, stdio: 'inherit', shell: process.platform === 'win32' },
);
process.exit(result.status ?? 1);
