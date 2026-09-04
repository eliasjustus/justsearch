#!/usr/bin/env node
/**
 * Deployment-topology and bootstrap-failure tests for tempdoc 925.
 *
 * The healthy fixture is a Git checkout outside every node_modules ancestor. It starts from the
 * exact command/args committed in .codex/config.toml, performs a real MCP handshake, and calls the
 * truth-sensitive handlers. Negative copies prove failures stay off stdout and leave stable,
 * sanitized diagnostics.
 */

import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import fsp from 'node:fs/promises';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const EXPECTED_TOOLS = [
  'justsearch.dev.start',
  'justsearch.dev.tail_log',
  'justsearch.dev.fetch_api_json',
  'justsearch.dev.api_call',
  'justsearch.dev.search_query',
  'justsearch.dev.ingest',
  'justsearch.dev.preflight',
  'justsearch.dev.quick_health',
  'justsearch.dev.acquire_when_free',
  'justsearch.dev.stop',
  'justsearch.dev.ai_activate',
  'justsearch.dev.reload',
];

process.exitCode = 1;
let passed = 0;
async function check(label, fn) {
  await fn();
  passed += 1;
  console.log(`  PASS  ${label}`);
}

async function copyFile(relative, fixtureRoot) {
  const source = path.join(REPO_ROOT, relative);
  const target = path.join(fixtureRoot, relative);
  await fsp.mkdir(path.dirname(target), { recursive: true });
  await fsp.copyFile(source, target);
}

async function makeFixture(root, name) {
  const fixture = path.join(root, name);
  await fsp.mkdir(fixture, { recursive: true });
  await copyFile('.codex/config.toml', fixture);
  await copyFile('scripts/dev/justsearch-dev-mcp.mjs', fixture);
  await copyFile('scripts/dev/dev-runner.cjs', fixture);
  for (const name of await fsp.readdir(path.join(REPO_ROOT, 'scripts', 'dev', 'justsearch-dev-mcp'))) {
    if (name.endsWith('.mjs')) await copyFile(`scripts/dev/justsearch-dev-mcp/${name}`, fixture);
  }
  for (const name of ['ownership-verdict.cjs', 'resolve-jdk.cjs', 'process-record.cjs']) {
    await copyFile(`scripts/dev/lib/${name}`, fixture);
  }
  execFileSync('git', ['init', '-b', 'main', fixture], { stdio: 'ignore' });
  return fixture;
}

function configuredLauncher(fixture) {
  const text = fs.readFileSync(path.join(fixture, '.codex', 'config.toml'), 'utf8');
  assert.match(text, /^required\s*=\s*true$/m);
  const command = text.match(/^command\s*=\s*"([^"]+)"$/m)?.[1];
  const argsText = text.match(/^args\s*=\s*(\[[^\r\n]+\])$/m)?.[1];
  if (!command || !argsText) throw new Error('could not parse justsearch-dev command/args from .codex/config.toml');
  return { command, args: JSON.parse(argsText) };
}

function startClient(fixture, cwd) {
  const { command, args } = configuredLauncher(fixture);
  const child = spawn(command, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  const pending = new Map();
  let nextId = 1;
  let buffer = '';
  let stderr = '';
  let protocolViolation = null;

  child.stderr.on('data', (chunk) => { stderr = (stderr + chunk.toString('utf8')).slice(-8_000); });
  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let newline;
    while ((newline = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      let message;
      try { message = JSON.parse(line); }
      catch {
        protocolViolation = line;
        continue;
      }
      const waiter = pending.get(message.id);
      if (waiter) {
        pending.delete(message.id);
        clearTimeout(waiter.timer);
        waiter.resolve(message);
      }
    }
  });

  function request(method, params, timeoutMs = 15_000) {
    return new Promise((resolve, reject) => {
      const id = nextId++;
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`${method} timed out; stderr=${stderr}`));
      }, timeoutMs);
      pending.set(id, { resolve, reject, timer });
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  return {
    child,
    request,
    notify(method, params) {
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
    },
    get protocolViolation() { return protocolViolation; },
    get stderr() { return stderr; },
    close() { try { child.kill(); } catch { /* already exited */ } },
  };
}

async function connectClient(fixture, cwd) {
  const client = startClient(fixture, cwd);
  const initialized = await client.request('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'test-dev-mcp-bootstrap', version: '1' },
  });
  assert.equal(initialized.error, undefined, JSON.stringify(initialized.error));
  client.notify('notifications/initialized', {});
  return client;
}

async function callTool(client, name, args = {}) {
  const response = await client.request('tools/call', { name, arguments: args });
  assert.equal(response.error, undefined, JSON.stringify(response.error));
  return response.result?.structuredContent
    ?? JSON.parse(response.result?.content?.[0]?.text ?? '{}');
}

function assertNoNodeModulesAncestor(fixture) {
  for (let cursor = path.resolve(fixture); ; cursor = path.dirname(cursor)) {
    assert.equal(fs.existsSync(path.join(cursor, 'node_modules')), false, `node_modules ancestor: ${cursor}`);
    const parent = path.dirname(cursor);
    if (parent === cursor) break;
  }
}

function runUntilExit(fixture, cwd = fixture, timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    const { command, args } = configuredLauncher(fixture);
    const child = spawn(command, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk.toString('utf8'); });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString('utf8'); });
    child.once('error', reject);
    const timer = setTimeout(() => {
      try { child.kill(); } catch { /* already exited */ }
      reject(new Error(`bootstrap did not exit; stderr=${stderr}`));
    }, timeoutMs);
    child.once('exit', (exitCode) => {
      clearTimeout(timer);
      resolve({ exitCode, stdout, stderr });
    });
  });
}

async function readDiagnostic(fixture) {
  return JSON.parse(await fsp.readFile(
    path.join(fixture, 'tmp', 'justsearch-dev-mcp', 'bootstrap-failure.json'),
    'utf8',
  ));
}

async function assertNegativeBootstrap(fixture, expectedCode) {
  const result = await runUntilExit(fixture);
  assert.notEqual(result.exitCode, 0);
  assert.equal(Buffer.byteLength(result.stdout), 0, `stdout=${result.stdout}`);
  assert.match(result.stderr, new RegExp(expectedCode));
  assert.ok(Buffer.byteLength(result.stderr) < 3_000, `stderr was not bounded: ${result.stderr.length}`);
  const diagnostic = await readDiagnostic(fixture);
  assert.equal(diagnostic.code, expectedCode);
  assert.equal(diagnostic.repoRoot, fixture);
  assert.equal(diagnostic.module, 'scripts/dev/justsearch-dev-mcp/server.mjs');
  return { result, diagnostic };
}

const scratchRoot = await fsp.mkdtemp(path.join(os.tmpdir(), 'justsearch-dev-bootstrap-'));
try {
  const healthy = await makeFixture(scratchRoot, 'healthy');
  assertNoNodeModulesAncestor(healthy);
  const diagnosticPath = path.join(healthy, 'tmp', 'justsearch-dev-mcp', 'bootstrap-failure.json');
  await fsp.mkdir(path.dirname(diagnosticPath), { recursive: true });
  await fsp.writeFile(diagnosticPath, '{"code":"STALE"}\n', 'utf8');

  await check('exact config launcher initializes without any node_modules ancestor', async () => {
    const client = await connectClient(healthy, healthy);
    try {
      const listed = await client.request('tools/list', {});
      assert.deepEqual(listed.result?.tools?.map((tool) => tool.name), EXPECTED_TOOLS);
      assert.equal(client.protocolViolation, null);
    } finally {
      client.close();
    }
  });
  await check('successful bootstrap removes a stale failure record', async () => {
    assert.equal(fs.existsSync(diagnosticPath), false);
  });
  await check('exact config launcher resolves the Git root from a module subdirectory', async () => {
    const moduleDirectory = path.join(healthy, 'scripts', 'dev', 'justsearch-dev-mcp');
    const client = await connectClient(healthy, moduleDirectory);
    try {
      const listed = await client.request('tools/list', {});
      assert.equal(listed.result?.tools?.length, 12);
      assert.equal(client.protocolViolation, null);
    } finally {
      client.close();
    }
  });

  const activePath = path.join(healthy, 'tmp', 'dev-runner', 'active.json');
  await fsp.mkdir(path.dirname(activePath), { recursive: true });
  await fsp.writeFile(activePath, '{ deliberately malformed JSON\n', 'utf8');
  await check('malformed active.json cannot make preflight report noStaleRun PASS', async () => {
    const client = await connectClient(healthy, healthy);
    try {
      const preflight = await callTool(client, 'justsearch.dev.preflight');
      assert.equal(preflight.ready, false);
      assert.equal(preflight.checkStates.noStaleRun, 'UNKNOWN');
      assert.equal(preflight.checks.noStaleRun, false);
      assert.match(preflight.details.noStaleRun, /INVALID/);
    } finally {
      client.close();
    }
  });
  await check('malformed active.json makes quick_health state/running unknown', async () => {
    const client = await connectClient(healthy, healthy);
    try {
      const health = await callTool(client, 'justsearch.dev.quick_health', { probe: false });
      assert.equal(health.runState, 'UNKNOWN');
      assert.equal(health.running, null);
      assert.equal(health.runStateError.code, 'ACTIVE_RECORD_INVALID');
    } finally {
      client.close();
    }
  });
  await fsp.rm(activePath, { force: true });
  await check('genuinely absent active.json remains a proven ABSENT/false state', async () => {
    const client = await connectClient(healthy, healthy);
    try {
      const health = await callTool(client, 'justsearch.dev.quick_health', { probe: false });
      assert.equal(health.runState, 'ABSENT');
      assert.equal(health.running, false);
    } finally {
      client.close();
    }
  });

  const missing = await makeFixture(scratchRoot, 'missing-runtime');
  await fsp.rm(path.join(missing, 'scripts', 'dev', 'justsearch-dev-mcp', 'runtime.generated.mjs'));
  await check('missing generated runtime → stable diagnostic and no stdout', async () => {
    await assertNegativeBootstrap(missing, 'DEV_MCP_BOOT_RUNTIME_MISSING');
  });

  const corrupt = await makeFixture(scratchRoot, 'corrupt-runtime');
  await fsp.writeFile(
    path.join(corrupt, 'scripts', 'dev', 'justsearch-dev-mcp', 'runtime.generated.mjs'),
    'export const broken = ;\n',
    'utf8',
  );
  await check('corrupt generated runtime → import diagnostic and no stdout', async () => {
    await assertNegativeBootstrap(corrupt, 'DEV_MCP_BOOT_IMPORT_FAILED');
  });

  const unsupported = await makeFixture(scratchRoot, 'unsupported-node');
  const unsupportedEntry = path.join(unsupported, 'scripts', 'dev', 'justsearch-dev-mcp.mjs');
  const unsupportedText = (await fsp.readFile(unsupportedEntry, 'utf8'))
    .replace('const MIN_NODE_MAJOR = 24;', 'const MIN_NODE_MAJOR = 999;');
  await fsp.writeFile(unsupportedEntry, unsupportedText, 'utf8');
  await check('unsupported Node → stable diagnostic and no stdout', async () => {
    await assertNegativeBootstrap(unsupported, 'DEV_MCP_BOOT_UNSUPPORTED_NODE');
  });

  const mainFailure = await makeFixture(scratchRoot, 'main-failure');
  await fsp.writeFile(
    path.join(mainFailure, 'scripts', 'dev', 'justsearch-dev-mcp', 'server.mjs'),
    "export async function main() { setInterval(() => {}, 60_000); throw new Error('Authorization: Bearer bearer-secret token=ultra-secret'); }\n",
    'utf8',
  );
  await check('main rejection terminates partial handles and sanitizes secrets', async () => {
    const { result, diagnostic } = await assertNegativeBootstrap(mainFailure, 'DEV_MCP_BOOT_MAIN_FAILED');
    assert.equal(result.stderr.includes('ultra-secret'), false);
    assert.equal(result.stderr.includes('bearer-secret'), false);
    assert.equal(diagnostic.message.includes('ultra-secret'), false);
    assert.equal(diagnostic.message.includes('bearer-secret'), false);
    assert.match(diagnostic.message, /authorization=<redacted>/);
    assert.match(diagnostic.message, /token=<redacted>/);
  });

  const runtimeUncaught = await makeFixture(scratchRoot, 'runtime-uncaught');
  await fsp.writeFile(
    path.join(runtimeUncaught, 'scripts', 'dev', 'justsearch-dev-mcp', 'server.mjs'),
    "export async function main() { setTimeout(() => { throw new Error('runtime boom'); }, 10); }\n",
    'utf8',
  );
  await check('post-main uncaught exception terminates with a runtime diagnostic', async () => {
    await assertNegativeBootstrap(runtimeUncaught, 'DEV_MCP_RUNTIME_UNCAUGHT');
  });

  const runtimeRejection = await makeFixture(scratchRoot, 'runtime-rejection');
  await fsp.writeFile(
    path.join(runtimeRejection, 'scripts', 'dev', 'justsearch-dev-mcp', 'server.mjs'),
    "export async function main() { setTimeout(() => { Promise.reject(new Error('runtime rejection')); }, 10); }\n",
    'utf8',
  );
  await check('post-main unhandled rejection terminates with a runtime diagnostic', async () => {
    await assertNegativeBootstrap(runtimeRejection, 'DEV_MCP_RUNTIME_UNHANDLED_REJECTION');
  });

  console.log(`test-dev-mcp-bootstrap: ${passed} passed`);
  process.exitCode = 0;
} finally {
  await fsp.rm(scratchRoot, { recursive: true, force: true });
}
