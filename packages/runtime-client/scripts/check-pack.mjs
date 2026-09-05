/* SPDX-License-Identifier: Apache-2.0 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const repositoryRoot = join(root, '..', '..');
const normalizedText = (path) => readFileSync(path, 'utf8').replaceAll('\r\n', '\n').trimEnd();

const packageManifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const expectedRepository = {
  type: 'git',
  url: 'git+https://github.com/justsearch-app/justsearch.git',
  directory: 'packages/runtime-client',
};
const expectedHomepage =
  'https://github.com/justsearch-app/justsearch/blob/main/docs/reference/runtime-contract.md#generated-node-client';
const expectedBugsUrl = 'https://github.com/justsearch-app/justsearch/issues';
const expectedNpmUrl = 'https://www.npmjs.com/package/@justsearch/runtime-client';
const expectedPrepublish = 'npm run check:regen && npm test && npm run check:pack';
const expectedKeywords = ['justsearch', 'local-first', 'runtime-contract', 'sdk', 'typescript'];
const metadataErrors = [];
if (
  packageManifest.repository?.type !== expectedRepository.type ||
  packageManifest.repository?.url !== expectedRepository.url ||
  packageManifest.repository?.directory !== expectedRepository.directory
) {
  metadataErrors.push('repository metadata');
}
if (packageManifest.homepage !== expectedHomepage) metadataErrors.push('homepage');
if (packageManifest.bugs?.url !== expectedBugsUrl) metadataErrors.push('bugs URL');
if (packageManifest.publishConfig?.access !== 'public') metadataErrors.push('public publishConfig');
if (
  !Array.isArray(packageManifest.keywords) ||
  expectedKeywords.some((keyword) => !packageManifest.keywords.includes(keyword))
) {
  metadataErrors.push('keywords');
}
if (packageManifest.scripts?.prepublishOnly !== expectedPrepublish) {
  metadataErrors.push('prepublishOnly lifecycle');
}
const readme = readFileSync(join(root, 'README.md'), 'utf8');
for (const requiredLink of [
  expectedHomepage,
  'https://github.com/justsearch-app/justsearch/blob/main/docs/explanation/23-runtime-manifest.md',
  expectedBugsUrl,
  expectedNpmUrl,
]) {
  if (!readme.includes(requiredLink)) metadataErrors.push(`README link ${requiredLink}`);
}
if (metadataErrors.length > 0) {
  console.error(`runtime-client publication metadata is incomplete: ${metadataErrors.join(', ')}`);
  process.exit(1);
}

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
