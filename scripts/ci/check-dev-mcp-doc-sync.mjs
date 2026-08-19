#!/usr/bin/env node
/**
 * The dev MCP surface's reference doc describes the surface that actually exists.
 *
 * Tempdoc 844 §6.3 measured the same inventory stated in four places — the reference doc, the
 * dev-stack skill, the server's own `initialize.instructions`, and the harness header — and every
 * one of them was wrong at the same time: three said 15 tools, one said 14, the registered count
 * was 16, `acquire_when_free` appeared in none of them, and `effective_config` was documented as
 * `/api/config/effective` when the code maps it to `/api/debug/effective-config`. Nothing compared
 * the doc against the server, so the drift was invisible and had been for months.
 *
 * Authority for "registered" is the RUNNING server, reached the way an agent reaches it: spawn
 * `scripts/dev/justsearch-dev-mcp.mjs` over stdio, `initialize`, `tools/list`. A source-regex over
 * server.mjs would pass on a tool that fails to register. The endpoint map and the api_call
 * allowlist are read from the module's exported constants (`FETCH_API_ENDPOINT_MAP`,
 * `API_CALL_ALLOWLIST`) — the same objects the handlers use, not a second copy.
 *
 * Spawning the MCP server does NOT start a dev stack; `tools/list` touches no run state.
 *
 * Usage: node scripts/ci/check-dev-mcp-doc-sync.mjs
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

export const DOC_FILE = 'docs/reference/contributing/mcp-dev-tools.md';
export const SERVER_ENTRY = 'scripts/dev/justsearch-dev-mcp.mjs';
export const SERVER_MODULE = 'scripts/dev/justsearch-dev-mcp/server.mjs';

function repoRootFromCwd() {
  const markers = ['settings.gradle.kts', 'build.gradle.kts', '.git'];
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (markers.some((marker) => fs.existsSync(path.join(dir, marker)))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
  }
  return process.cwd();
}

/* ------------------------------------------------------------------ */
/* Doc parsing — pure, so the tests can drive it without a subprocess  */
/* ------------------------------------------------------------------ */

/** The rows of the first markdown table that follows `## <heading>`, as arrays of trimmed cells. */
export function tableUnderHeading(docText, heading) {
  const lines = docText.split(/\r?\n/);
  const start = lines.findIndex((l) => l.trim().toLowerCase() === `## ${heading}`.toLowerCase());
  if (start < 0) return null;
  const rows = [];
  let seenHeader = false;
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (/^##\s/.test(line)) break; // next section — the table was not found
    if (!line.trimStart().startsWith('|')) {
      if (rows.length > 0 || seenHeader) break; // the table ended
      continue; // prose before the table
    }
    const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
    if (!seenHeader) { seenHeader = true; continue; } // header row
    if (cells.every((c) => /^:?-{2,}:?$/.test(c))) continue; // separator row
    rows.push(cells);
  }
  return seenHeader ? rows : null;
}

const backticked = (cell) => {
  const m = String(cell).match(/^`([^`]+)`$/);
  return m ? m[1] : null;
};

/** Tool names in the doc's `## Available Tools` table (first column, backticked). */
export function documentedToolNames(docText) {
  const rows = tableUnderHeading(docText, 'Available Tools');
  if (rows == null) return null;
  const names = [];
  for (const row of rows) {
    const name = backticked(row[0]);
    if (name && name.startsWith('justsearch.dev.')) names.push(name);
  }
  return names;
}

/** The tool count the doc asserts in prose ("exactly these **12** tools"). */
export function documentedToolCount(docText) {
  const m = docText.match(/exposes exactly these \*\*(\d+)\*\* tools/);
  return m ? Number(m[1]) : null;
}

/** `{ key: path }` from the doc's `## Predefined JSON Endpoints` table. */
export function documentedEndpointMap(docText) {
  const rows = tableUnderHeading(docText, 'Predefined JSON Endpoints');
  if (rows == null) return null;
  const out = {};
  for (const row of rows) {
    const key = backticked(row[0]);
    const endpoint = backticked(row[1]);
    if (key && endpoint) out[key] = endpoint;
  }
  return out;
}

/** `{ path: [methods] }` from the doc's `## Generic API Calls` table. */
export function documentedAllowlist(docText) {
  const rows = tableUnderHeading(docText, 'Generic API Calls');
  if (rows == null) return null;
  const out = {};
  for (const row of rows) {
    const p = backticked(row[0]);
    if (!p || !p.startsWith('/api/')) continue;
    out[p] = String(row[1]).split(',').map((s) => s.trim()).filter(Boolean);
  }
  return out;
}

/* ------------------------------------------------------------------ */
/* Live surface — spawn the stdio server and ask it                    */
/* ------------------------------------------------------------------ */

/** Registered tool names, from a real initialize + tools/list over stdio. */
export function registeredToolNames(repoRoot, { timeoutMs = 30_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [SERVER_ENTRY], {
      cwd: repoRoot,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    let buf = '';
    let stderrTail = '';
    let settled = false;
    const finish = (fn, arg) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { child.kill(); } catch { /* already gone */ }
      fn(arg);
    };
    const timer = setTimeout(
      () => finish(reject, new Error(`MCP server did not answer tools/list within ${timeoutMs}ms. stderr=${stderrTail.slice(-2000)}`)),
      timeoutMs,
    );

    const send = (msg) => child.stdin.write(`${JSON.stringify(msg)}\n`);
    child.stderr.on('data', (c) => { stderrTail += c.toString('utf8'); });
    child.on('error', (err) => finish(reject, new Error(`failed to spawn ${SERVER_ENTRY}: ${err.message}`)));
    child.on('exit', (code) => {
      if (!settled) finish(reject, new Error(`MCP server exited (${code}) before tools/list. stderr=${stderrTail.slice(-2000)}`));
    });
    child.stdout.on('data', (chunk) => {
      buf += chunk.toString('utf8');
      let i;
      while ((i = buf.indexOf('\n')) !== -1) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (!line) continue;
        let msg;
        try {
          msg = JSON.parse(line);
        } catch {
          return finish(reject, new Error(`non-JSON line on the MCP server's stdout (protocol violation): ${line.slice(0, 300)}`));
        }
        if (msg.id === 1) {
          send({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} });
          send({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} });
        } else if (msg.id === 2) {
          if (msg.error) return finish(reject, new Error(`tools/list failed: ${JSON.stringify(msg.error)}`));
          return finish(resolve, (msg.result?.tools ?? []).map((t) => t.name));
        }
      }
    });

    send({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'check-dev-mcp-doc-sync', version: '1' } },
    });
  });
}

/* ------------------------------------------------------------------ */
/* Comparison                                                          */
/* ------------------------------------------------------------------ */

const sorted = (xs) => [...xs].sort();

/** Pure comparison of a doc's three inventories against the live surface. Returns error strings. */
export function compare({ docText, toolNames, endpointMap, allowlist }) {
  const errors = [];

  // 1. tool names, both directions
  const documented = documentedToolNames(docText);
  if (documented == null) {
    errors.push(`${DOC_FILE}: could not find the "## Available Tools" table — the gate cannot see what the doc claims.`);
  } else {
    if (toolNames.length === 0) {
      errors.push('tools/list returned no tools — an empty surface must never pass silently.');
    }
    const docSet = new Set(documented);
    const liveSet = new Set(toolNames);
    const missing = sorted(toolNames.filter((n) => !docSet.has(n)));
    const phantom = sorted(documented.filter((n) => !liveSet.has(n)));
    if (missing.length > 0) {
      errors.push(`registered but absent from the "Available Tools" table in ${DOC_FILE}: ${missing.join(', ')} — add a row for each.`);
    }
    if (phantom.length > 0) {
      errors.push(`documented in ${DOC_FILE} but NOT registered: ${phantom.join(', ')} — remove the row, or fix the registration.`);
    }
    if (documented.length !== docSet.size) {
      errors.push(`${DOC_FILE}: the "Available Tools" table lists a tool twice.`);
    }
  }

  // 2. the count asserted in prose
  const claimed = documentedToolCount(docText);
  if (claimed == null) {
    errors.push(`${DOC_FILE}: could not find the tool-count sentence ("exposes exactly these **N** tools") — the count must be asserted so it can be checked.`);
  } else if (claimed !== toolNames.length) {
    errors.push(`${DOC_FILE} claims ${claimed} tools; the server registers ${toolNames.length}. Fix the sentence.`);
  }

  // 3. fetch_api_json endpoint keys AND their mapped paths
  const docEndpoints = documentedEndpointMap(docText);
  if (docEndpoints == null) {
    errors.push(`${DOC_FILE}: could not find the "## Predefined JSON Endpoints" table.`);
  } else {
    for (const key of sorted(Object.keys(endpointMap))) {
      if (!(key in docEndpoints)) {
        errors.push(`fetch_api_json endpoint key "${key}" (-> ${endpointMap[key]}) is missing from the endpoint table in ${DOC_FILE}.`);
      } else if (docEndpoints[key] !== endpointMap[key]) {
        errors.push(`fetch_api_json endpoint "${key}" maps to ${endpointMap[key]} in the code, but ${DOC_FILE} says ${docEndpoints[key]}. The code is the authority.`);
      }
    }
    for (const key of sorted(Object.keys(docEndpoints))) {
      if (!(key in endpointMap)) {
        errors.push(`${DOC_FILE} documents endpoint key "${key}", which fetch_api_json does not accept.`);
      }
    }
  }

  // 4. api_call allowlist, both directions (paths; methods too, since a wrong verb is a failed call)
  const docAllow = documentedAllowlist(docText);
  if (docAllow == null) {
    errors.push(`${DOC_FILE}: could not find the "## Generic API Calls" allowlist table.`);
  } else {
    const codeAllow = Object.fromEntries(allowlist.map((e) => [e.path, e.methods]));
    for (const p of sorted(Object.keys(codeAllow))) {
      if (!(p in docAllow)) {
        errors.push(`API_CALL_ALLOWLIST entry ${p} (${codeAllow[p].join(', ')}) is missing from the allowlist table in ${DOC_FILE}.`);
      } else if (sorted(docAllow[p]).join(',') !== sorted(codeAllow[p]).join(',')) {
        errors.push(`allowlist methods for ${p} differ: code has [${codeAllow[p].join(', ')}], ${DOC_FILE} has [${docAllow[p].join(', ')}].`);
      }
    }
    for (const p of sorted(Object.keys(docAllow))) {
      if (!(p in codeAllow)) {
        errors.push(`${DOC_FILE} lists ${p} as allowlisted, but API_CALL_ALLOWLIST does not contain it — an agent would be refused.`);
      }
    }
  }

  return errors;
}

export async function run(repoRoot) {
  const docPath = path.join(repoRoot, DOC_FILE);
  if (!fs.existsSync(docPath)) return [`${DOC_FILE} is missing — the dev MCP surface has no canonical inventory.`];
  const modulePath = path.join(repoRoot, SERVER_MODULE);
  if (!fs.existsSync(modulePath)) return [`${SERVER_MODULE} is missing — this gate can no longer see the surface it describes.`];

  const mod = await import(pathToFileURL(modulePath).href);
  const endpointMap = mod.FETCH_API_ENDPOINT_MAP;
  const allowlist = mod.API_CALL_ALLOWLIST;
  if (!endpointMap || typeof endpointMap !== 'object') {
    return [`${SERVER_MODULE} no longer exports FETCH_API_ENDPOINT_MAP — this gate reads it directly; re-export it or point the gate at its new home.`];
  }
  if (!Array.isArray(allowlist) || allowlist.length === 0) {
    return [`${SERVER_MODULE} no longer exports a non-empty API_CALL_ALLOWLIST — an empty allowlist must not pass silently.`];
  }

  let toolNames;
  try {
    toolNames = await registeredToolNames(repoRoot);
  } catch (err) {
    return [`could not read the live tool list: ${err.message}`];
  }

  return compare({ docText: fs.readFileSync(docPath, 'utf8'), toolNames, endpointMap, allowlist });
}

async function main() {
  const errors = await run(repoRootFromCwd());
  if (errors.length === 0) {
    console.log('check-dev-mcp-doc-sync: OK');
    return;
  }
  console.error('check-dev-mcp-doc-sync: FAIL');
  for (const e of errors) console.error(`  - ${e}`);
  console.error(`\n${DOC_FILE} is the canonical inventory of the dev MCP surface. Update it in the same`);
  console.error('change as the server — that is the whole point of this gate (tempdoc 844 P6).');
  process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
