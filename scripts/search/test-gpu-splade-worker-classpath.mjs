#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const launcherPath = path.join(
  repoRoot,
  'modules',
  'indexer-worker',
  'build',
  'install',
  'indexer-worker',
  'bin',
  'indexer-worker.bat',
);

assert.ok(fs.existsSync(launcherPath), `Worker launcher not found: ${launcherPath}`);

const launcher = fs.readFileSync(launcherPath, 'utf8');
assert.match(launcher, /onnxruntime_gpu-1\.19\.2\.jar/);
assert.doesNotMatch(launcher, /onnxruntime-1\.19\.2\.jar/);

console.log('ok');
