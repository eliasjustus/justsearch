#!/usr/bin/env node
/**
 * Tests for scripts/ci/verify-codeowners.mjs
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir =
  process.platform === 'win32' ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1') : SCRIPT_DIR;
const verifyScript = path.join(scriptDir, 'verify-codeowners.mjs');

const REQUIRED_PATTERNS = [
  '*',
  '/.github/**',
  '/docs/**',
  '/scripts/**',
  '/SSOT/**',
  '/modules/ui/**',
  '/modules/ui-web/**',
  '/modules/indexer-worker/**',
  '/modules/adapters-lucene/**',
  '/modules/app-services/**',
  '/modules/configuration/**',
  '/modules/ipc-common/**',
  '/modules/system-tests/**',
  '/modules/reranker/**',
  '/modules/app-inference/**',
];

function runVerify(filePath) {
  return spawnSync('node', [verifyScript, '--file', filePath], {
    encoding: 'utf8',
    timeout: 10000,
  });
}

function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-codeowners-'));

  try {
    const passFile = path.join(tempRoot, 'CODEOWNERS.pass');
    const passLines = REQUIRED_PATTERNS.map((pattern) => `${pattern} @eliasjustus`);
    fs.writeFileSync(passFile, `${passLines.join('\n')}\n`, 'utf8');

    const passResult = runVerify(passFile);
    assert.equal(passResult.status, 0, `pass scenario failed: ${passResult.stderr}`);

    const missingPatternFile = path.join(tempRoot, 'CODEOWNERS.missing');
    const missingLines = passLines.filter((line) => !line.startsWith('/modules/reranker/** '));
    fs.writeFileSync(missingPatternFile, `${missingLines.join('\n')}\n`, 'utf8');

    const missingResult = runVerify(missingPatternFile);
    assert.equal(missingResult.status, 1, 'missing pattern scenario should fail');
    assert.match(missingResult.stderr, /\/modules\/reranker\/\*\*/);

    const ownerlessFile = path.join(tempRoot, 'CODEOWNERS.ownerless');
    const ownerlessLines = passLines.map((line) =>
      line.startsWith('/docs/** ') ? '/docs/**' : line,
    );
    fs.writeFileSync(ownerlessFile, `${ownerlessLines.join('\n')}\n`, 'utf8');

    const ownerlessResult = runVerify(ownerlessFile);
    assert.equal(ownerlessResult.status, 1, 'ownerless pattern scenario should fail');
    assert.match(ownerlessResult.stderr, /Required patterns without owner tokens:/);

    // eslint-disable-next-line no-console
    console.log('verify-codeowners tests: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main();

