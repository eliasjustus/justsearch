import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const isWindows = process.platform === 'win32';
const scriptPath = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'bootstrap-node-win.ps1',
);

async function withDistributionIndex(body, statusCode, run) {
  const server = createServer((request, response) => {
    assert.equal(request.url, '/dist/latest-v24.x/');
    response.writeHead(statusCode, {
      'Content-Type': 'text/html; charset=utf-8',
      Connection: 'close',
    });
    response.end(body);
  });

  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert.ok(address && typeof address === 'object');

  try {
    return await run(`http://127.0.0.1:${address.port}/dist`);
  } finally {
    server.close();
    await once(server, 'close');
  }
}

function resolveFrom(baseUrl) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      'powershell.exe',
      [
        '-NoLogo',
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        scriptPath,
        '-Major',
        '24',
        '-DistributionBaseUrl',
        baseUrl,
        '-ResolveOnly',
      ],
      { windowsHide: true },
    );

    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', (code, signal) => resolve({ code, signal, stdout, stderr }));
  });
}

test('resolve-only parses a multi-digit patch version from a local index', { skip: !isWindows }, async () => {
  const result = await withDistributionIndex(
    '<a href="node-v24.12.11-win-x64.zip">node-v24.12.11-win-x64.zip</a>',
    200,
    resolveFrom,
  );

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.signal, null);
  assert.match(
    result.stdout.trim(),
    /^http:\/\/127\.0\.0\.1:\d+\/dist\/v24\.12\.11\/node-v24\.12\.11-win-x64\.zip$/,
  );
  assert.equal(result.stderr, '');
});

test('resolve-only rejects a malformed local index without inventing a fallback URL', { skip: !isWindows }, async () => {
  const result = await withDistributionIndex(
    '<a href="node-v24.12.+-win-x64.zip">malformed archive</a>',
    200,
    resolveFrom,
  );

  assert.notEqual(result.code, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /did not contain a Windows x64 archive/);
  assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /v24\.0\.0/);
});

test('resolve-only reports an unavailable local index as a fetch failure', { skip: !isWindows }, async () => {
  const result = await withDistributionIndex('temporarily unavailable', 503, resolveFrom);

  assert.notEqual(result.code, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /Unable to fetch the Node\.js distribution index/);
  assert.match(`${result.stdout}\n${result.stderr}`, /network or proxy settings/);
});
