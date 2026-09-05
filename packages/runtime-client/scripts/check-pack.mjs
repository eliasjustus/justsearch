/* SPDX-License-Identifier: Apache-2.0 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = join(root, '..', '..');
const normalizedText = (path) => readFileSync(path, 'utf8').replaceAll('\r\n', '\n').trimEnd();
for (const legalFile of ['LICENSE', 'NOTICE']) {
  if (normalizedText(join(root, legalFile)) !== normalizedText(join(repositoryRoot, legalFile))) {
    console.error(`runtime-client ${legalFile} has drifted from the repository copy`);
    process.exit(1);
  }
}
const npmExecPath = process.env.npm_execpath;
if (!npmExecPath) {
  throw new Error('npm_execpath is unavailable; run this check through npm run check:pack');
}
const result = spawnSync(process.execPath, [npmExecPath, 'pack', '--dry-run', '--json'], {
  cwd: root,
  encoding: 'utf8',
});
if (result.error) {
  throw new Error(`failed to launch npm pack: ${result.error.message}`, { cause: result.error });
}
if (result.status !== 0) {
  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  process.exit(result.status ?? 1);
}
const report = JSON.parse(result.stdout)[0];
const files = report.files.map((entry) => entry.path);
const forbidden = files.filter(
  (path) => path.startsWith('src/') || path.startsWith('openapi/') || path === 'orval.config.mjs',
);
if (forbidden.length > 0) {
  console.error(`runtime-client package leaks generation inputs: ${forbidden.join(', ')}`);
  process.exit(1);
}
for (const required of [
  'dist/index.js',
  'dist/index.d.ts',
  'package.json',
  'README.md',
  'LICENSE',
  'NOTICE',
]) {
  if (!files.includes(required)) {
    console.error(`runtime-client package is missing ${required}`);
    process.exit(1);
  }
}
console.log(`runtime-client package contents verified (${files.length} files)`);
