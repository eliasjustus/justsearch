#!/usr/bin/env node
/**
 * Knip report producer for the `dead-code` gate's input contract (tempdoc 742 D2).
 *
 * The registry declares the producer as `npm --prefix modules/ui-web run
 * knip:report`, invoked with cwd = repo root, but npm always runs the
 * script itself with cwd = the package dir (`modules/ui-web`). A plain
 * shell redirect (`knip --reporter json > ../../tmp/knip-report.json`)
 * goes through cmd.exe on Windows, where relative-path redirects have gotten
 * this repo into trouble before - so this wrapper resolves the repo-root
 * output path explicitly from `import.meta.url` instead of relying on shell
 * cwd/relative-path behavior.
 *
 * Knip exits non-zero whenever it finds ANY issues (which is every run,
 * given a real codebase) - that is not a producer failure, it's the normal
 * case. The producer's job is only "did a parseable report get written";
 * it must exit 0 whenever that's true, and non-zero only if knip itself
 * crashed (no stdout / unparseable stdout).
 */

import { spawnSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const uiWebDir = dirname(dirname(fileURLToPath(import.meta.url)));
const repoRoot = resolve(uiWebDir, '..', '..');
const outPath = resolve(repoRoot, 'tmp', 'knip-report.json');

// Invoke knip's JS entry point directly with the current node executable,
// rather than the `.bin/knip(.cmd)` shim - spawnSync with shell:false fails
// (EINVAL) on the Windows .cmd shim, and shell:true reintroduces the same
// cmd.exe quoting/redirect hazards this wrapper exists to avoid.
const knipEntry = resolve(uiWebDir, 'node_modules', 'knip', 'bin', 'knip.js');

const result = spawnSync(process.execPath, [knipEntry, '--reporter', 'json', '--no-progress'], {
  cwd: uiWebDir,
  encoding: 'utf8',
  shell: false,
});

if (result.error) {
  console.error(`knip-report: failed to spawn knip: ${result.error.message}`);
  process.exit(1);
}

const stdout = result.stdout ?? '';
let parsed;
try {
  parsed = JSON.parse(stdout);
} catch (err) {
  console.error('knip-report: knip stdout was not valid JSON (fail-closed)');
  console.error(result.stderr ?? '');
  process.exit(1);
}

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(parsed, null, 2) + '\n');
console.log(`knip-report: wrote ${outPath} (knip exit code ${result.status})`);
// Knip's own exit code reflects issue count (non-zero = issues found), which
// is expected on every real run. The producer contract only cares whether a
// parseable report was written, which it was - exit 0 unconditionally here.
process.exit(0);
