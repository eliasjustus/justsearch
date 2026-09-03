#!/usr/bin/env node

import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import test from 'node:test';

const execFileAsync = promisify(execFile);
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const captureScript = path.join(repoRoot, 'modules', 'ui-web', 'scripts', 'capture-evidence-bundle.mjs');

test('evidence capture drains its stdout contract before exiting', async () => {
  const source = await readFile(captureScript, 'utf8');
  assert.match(
    source,
    /process\.stdout\.write\(printable \+ '\\n'\);[\s\S]{0,500}process\.exitCode = status === 'passed' \? 0 : 1;/,
    'the successful capture path must set exitCode rather than force-exiting with a live stdout pipe',
  );
  const runtimeSection = source.slice(source.indexOf('async function main()'));
  assert.doesNotMatch(
    runtimeSection,
    /(?:^|\n)\s*process\.exit\(/u,
    'capture completion and crash handling must both allow pending Node cleanup to drain',
  );

  const outRoot = await mkdtemp(path.join(os.tmpdir(), 'justsearch-evidence-exit-'));
  const server = createServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end('{}');
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert.ok(address && typeof address === 'object');
  const apiBaseUrl = `http://127.0.0.1:${address.port}`;
  try {
    const { stdout, stderr } = await execFileAsync(
      process.execPath,
      [
        captureScript,
        '--scenario=exit-contract',
        `--api-base-url=${apiBaseUrl}`,
        `--out-root=${outRoot}`,
        '--external-status=passed',
      ],
      { cwd: repoRoot, windowsHide: true },
    );

    const lines = stdout.split(/\r?\n/u).filter((line) => line.trim().length > 0);
    assert.equal(lines.length, 1, `expected exactly one stdout line, got: ${stdout}`);
    assert.doesNotMatch(stderr, /UV_HANDLE_CLOSING|Assertion failed/u);
    const bundleStats = await stat(lines[0]);
    assert.equal(bundleStats.isDirectory(), true, 'stdout must name the completed evidence bundle directory');
  } finally {
    const closed = once(server, 'close');
    server.closeAllConnections();
    server.close();
    await closed;
    await rm(outRoot, { recursive: true, force: true });
  }
});
