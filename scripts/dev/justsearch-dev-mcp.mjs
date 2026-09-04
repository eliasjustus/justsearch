#!/usr/bin/env node
/**
 * Dependency-free bootstrap for the required JustSearch development MCP.
 *
 * MCP protocol messages are the only stdout content. Bootstrap/runtime failures go to stderr and
 * a best-effort repository-local diagnostic record. Keep this file limited to Node built-ins so
 * it can explain a broken application/runtime import instead of closing stdio before its catch.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const MIN_NODE_MAJOR = 24;
const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..', '..');
const DIAGNOSTIC_PATH = path.join(REPO_ROOT, 'tmp', 'justsearch-dev-mcp', 'bootstrap-failure.json');
const SERVER_MODULE = 'scripts/dev/justsearch-dev-mcp/server.mjs';
let terminating = false;

function sanitizeMessage(error) {
  let message = String(error?.message || error || 'unknown error').replace(/\s+/g, ' ').trim();
  message = message
    .replace(/\bauthorization\s*[:=]\s*(?:bearer\s+)?[^\s,;]+/gi, 'authorization=<redacted>')
    .replace(/\b(password|secret|token)\s*[:=]\s*[^\s,;]+/gi, '$1=<redacted>')
    .replace(/([?&](?:access_token|api_key|token)=)[^&\s]+/gi, '$1<redacted>')
    .replace(/\b(?:sk|ghp|github_pat)_[A-Za-z0-9_-]{8,}\b/g, '<redacted>');
  return message.length <= 1_500 ? message : `${message.slice(0, 1_500)}…`;
}

function classifyBootFailure(error, phase) {
  if (error?.code === 'DEV_MCP_BOOT_UNSUPPORTED_NODE') return error.code;
  if (phase === 'import') {
    if (error?.code === 'ERR_MODULE_NOT_FOUND' && sanitizeMessage(error).includes('runtime.generated.mjs')) {
      return 'DEV_MCP_BOOT_RUNTIME_MISSING';
    }
    if (error?.code === 'ERR_MODULE_NOT_FOUND') return 'DEV_MCP_BOOT_MODULE_NOT_FOUND';
    return 'DEV_MCP_BOOT_IMPORT_FAILED';
  }
  return 'DEV_MCP_BOOT_MAIN_FAILED';
}

function writeDiagnostic({ code, error, phase }) {
  const record = {
    code,
    timestamp: new Date().toISOString(),
    node: process.versions.node,
    platform: process.platform,
    arch: process.arch,
    repoRoot: REPO_ROOT,
    module: SERVER_MODULE,
    phase,
    message: sanitizeMessage(error),
  };
  const temporary = `${DIAGNOSTIC_PATH}.${process.pid}.tmp`;
  try {
    fs.mkdirSync(path.dirname(DIAGNOSTIC_PATH), { recursive: true });
    fs.writeFileSync(temporary, `${JSON.stringify(record, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
    try {
      fs.renameSync(temporary, DIAGNOSTIC_PATH);
    } catch (error) {
      if (process.platform !== 'win32') throw error;
      fs.rmSync(DIAGNOSTIC_PATH, { force: true });
      fs.renameSync(temporary, DIAGNOSTIC_PATH);
    }
  } catch {
    try { fs.rmSync(temporary, { force: true }); } catch { /* best effort */ }
  }
  return record;
}

function reportFailure({ code, error, phase }) {
  const record = writeDiagnostic({ code, error, phase });
  try {
    process.stderr.write(`[justsearch-dev-mcp] ${record.code}: ${record.message}\n`);
  } catch { /* stderr is best effort */ }
}

function terminateRuntime(code, error) {
  if (terminating) {
    process.exit(1);
    return;
  }
  terminating = true;
  reportFailure({ code, error, phase: 'runtime' });
  process.exit(1);
}

process.on('uncaughtException', (error) => terminateRuntime('DEV_MCP_RUNTIME_UNCAUGHT', error));
process.on('unhandledRejection', (error) => terminateRuntime('DEV_MCP_RUNTIME_UNHANDLED_REJECTION', error));

let phase = 'preflight';
try {
  const nodeMajor = Number.parseInt(process.versions.node.split('.')[0], 10);
  if (!Number.isInteger(nodeMajor) || nodeMajor < MIN_NODE_MAJOR) {
    const error = new Error(
      `Node ${process.versions.node} is unsupported; justsearch-dev requires Node ${MIN_NODE_MAJOR} or newer`,
    );
    error.code = 'DEV_MCP_BOOT_UNSUPPORTED_NODE';
    throw error;
  }

  phase = 'import';
  const { main } = await import('./justsearch-dev-mcp/server.mjs');
  phase = 'main';
  await main();
  try { fs.rmSync(DIAGNOSTIC_PATH, { force: true }); } catch { /* stale record cleanup is best effort */ }
} catch (error) {
  reportFailure({ code: classifyBootFailure(error, phase), error, phase });
  // Import/main failures may leave partially-created handles behind. Terminate after the
  // synchronous diagnostic write so the required MCP fails promptly instead of hanging the
  // client's initialize timeout.
  process.exit(1);
}
