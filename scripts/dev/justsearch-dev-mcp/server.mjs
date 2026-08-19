import { execFile } from 'node:child_process';
import http from 'node:http';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { promisify } from 'node:util';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const execFileP = promisify(execFile);

// Tempdoc 637 §H.1 — MCP-server self-freshness. The dev MCP server is a long-lived stdio process; after
// server.mjs (or a sibling loaded module) is edited it keeps serving OLD code until the harness reconnects
// it, with no signal — the stale-jar masquerade (#2) applied to the agent tooling itself. So the server
// self-declares its own code-staleness on every tool result (via toToolResult below), the same
// "self-declaration at the surface of use" pattern as the backend jar (#2) and the index (#3).
const MCP_SRC_DIR = path.dirname(fileURLToPath(import.meta.url));

/** The behavior-bearing source set LOADED into this MCP process (§H.1 R2): the MCP dir's *.mjs + the entry
 *  that imports main() + the createRequire'd ownership-verdict.cjs. dev-runner.cjs is *spawned* (always
 *  fresh) so it is excluded; node_modules is excluded. */
function mcpSourceFiles() {
  const files = [];
  try {
    for (const f of readdirSync(MCP_SRC_DIR)) {
      if (f.endsWith('.mjs')) files.push(path.join(MCP_SRC_DIR, f));
    }
  } catch { /* dir unreadable — fail-open below */ }
  files.push(path.join(MCP_SRC_DIR, '..', 'justsearch-dev-mcp.mjs')); // the entry that imports main()
  files.push(path.join(MCP_SRC_DIR, '..', 'lib', 'ownership-verdict.cjs')); // createRequire'd dependency
  return files.filter((p) => { try { return statSync(p).isFile(); } catch { return false; } }).sort();
}

let _srcCache = null; // { maxMtimeMs, stamp } — mtime only GATES the re-hash; the verdict is content-based (§H.1 R3).
/** Content-hash (sha256) of the source set; mtime-gated so the per-call cost is ~statSync×N, re-reading file
 *  contents only when a file's mtime advances. mtime is NOT the verdict (git checkout/merge touch mtimes
 *  without content change) — content decides. Returns a 16-hex stamp or null (fail-open). */
function hashMcpSource() {
  try {
    const files = mcpSourceFiles();
    if (files.length === 0) return null;
    let maxMtimeMs = 0;
    for (const p of files) { const m = statSync(p).mtimeMs; if (m > maxMtimeMs) maxMtimeMs = m; }
    if (_srcCache && maxMtimeMs <= _srcCache.maxMtimeMs) return _srcCache.stamp;
    const h = createHash('sha256');
    for (const p of files) {
      h.update(`${path.basename(p)}|${createHash('sha256').update(readFileSync(p)).digest('hex')}\n`);
    }
    const stamp = h.digest('hex').slice(0, 16);
    _srcCache = { maxMtimeMs, stamp };
    return stamp;
  } catch { return null; }
}

/** The stamp captured ONCE at module load — the code this process is actually running. Exported for tests. */
export const BOOT_SOURCE_STAMP = hashMcpSource();
export { hashMcpSource };

/** Tempdoc 637 §H.1 — a loud notice iff the on-disk source now differs from what booted, else null. Pure over
 *  its `bootStamp` arg (standalone-testable). Exported for verification. */
export function mcpStaleNotice(bootStamp) {
  if (!bootStamp) return null;
  const cur = hashMcpSource();
  if (cur && cur !== bootStamp) {
    return {
      sourceChangedSinceBoot: true,
      recommendedAction:
        'the justsearch-dev MCP server is running code older than its source — reconnect it to pick up edits ' +
        '(restart the session; /mcp reload when available; dev: kill -HUP $PPID).',
    };
  }
  return null;
}

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

import {
  AcquireWhenFreeInputSchema,
  AcquireWhenFreeOutputSchema,
} from './schemas.mjs';
import {
  buildDevRunnerArgsCleanup,
  buildDevRunnerArgsStart,
  buildDevRunnerArgsStatus,
  buildDevRunnerArgsStop,
  coerceExitAwareOk,
  readRunJson,
  runCliJson,
} from './cli.mjs';
import { readJsonFileNoSymlinks, resolveAllowedRunFile, tailTextFileNoSymlinks } from './files.mjs';
import { logError, logInfo, maybeAppendNdjson } from './log.mjs';
import { ensureLoopbackUrl, resolveAgentSessionIdForMcp, resolveMainRepoRoot, resolveRepoRoot, resolveUnderRepo } from './paths.mjs';
import {
  AiActivateInputSchema,
  AiActivateOutputSchema,
  ApiCallInputSchema,
  DevRunnerStatusJsonSchema,
  DevRunnerCleanupJsonSchema,
  DevRunnerStartJsonSchema,
  DevRunnerStopJsonSchema,
  FetchApiJsonInputSchema,
  FetchApiJsonOutputSchema,
  IngestInputSchema,
  IngestOutputSchema,
  PreflightInputSchema,
  PreflightOutputSchema,
  ReloadInputSchema,
  QuickHealthInputSchema,
  QuickHealthOutputSchema,
  SearchQueryInputSchema,
  SearchQueryOutputSchema,
  StartInputSchema,
  StatusOutputSchema,
  StopInputSchema,
  TailLogInputSchema,
  TailLogOutputSchema,
  ToolErrorSchema,
} from './schemas.mjs';

// Tempdoc 606 — the ONE ownership-verdict authority, shared with the dev-runner
// admission gate (single-derivation fix for D3). CJS module imported via
// createRequire (the same interop pattern the dev-runner test uses).
import { createRequire } from 'node:module';
const _ownReq = createRequire(import.meta.url);
const { computeOwnershipVerdict, readSessionActivity, computeDisplacedNotice, recommendedTakeoverFor } = _ownReq('../lib/ownership-verdict.cjs');
// Tempdoc 696: resolve a >= 24 JDK (Temurin 25) for hot-swap's java + gradle compile,
// so a stale JDK-8 JAVA_HOME/PATH can't break `--source 25` hot-swap. Reuses _ownReq (CJS interop).
const { resolveJavaExe, resolveJdkHome } = _ownReq('../lib/resolve-jdk.cjs');

function _pidAlive(pid) {
  if (typeof pid !== 'number' || pid <= 0) return false;
  try { process.kill(pid, 0); return true; } catch { return false; }
}

/** Read non-expired op-leases (mirrors the dev-runner filter) and bucket by criticality. */
async function readOwnershipOpLeases(mainRepoRoot) {
  try {
    const raw = await fsp.readFile(path.join(mainRepoRoot, 'tmp', 'dev-runner', 'op-leases.json'), 'utf8').catch(() => null);
    if (!raw) return { byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] }, entries: [], active: [] };
    const doc = JSON.parse(raw);
    const now = Date.now();
    const active = Array.isArray(doc?.opLeases)
      ? doc.opLeases.filter((e) => { const t = e?.expiresAt ? new Date(e.expiresAt).getTime() : NaN; return Number.isFinite(t) && t > now; })
      : [];
    return {
      active,
      entries: active,
      byCriticality: {
        mustComplete: active.filter((e) => e.criticality === 'MUST_COMPLETE'),
        unsafeToInterrupt: active.filter((e) => e.criticality === 'UNSAFE_TO_INTERRUPT'),
        interruptibleWithLoss: active.filter((e) => e.criticality === 'INTERRUPTIBLE_WITH_LOSS'),
      },
    };
  } catch {
    return { byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] }, entries: [], active: [] };
  }
}

/**
 * Tempdoc 606: single ownership projection consumed by quick_health / status / start.
 * Gathers the facts (lease, supervisor liveness, owner activity, op-leases) and runs
 * the ONE verdict function, returning the advisory `ownership` block (with the
 * prescriptive `verdict` + `recommendedAction`) and the raw `decision`.
 */
function _normPath(p) { return typeof p === 'string' ? p.replace(/\\/g, '/').replace(/\/+$/, '') : p; }

async function buildOwnershipProjection({ mainRepoRoot, callerRepoRoot, callerSessionId, takeover = 'deny', active, runJson }) {
  if (!active?.holder) return { ownership: null, decision: null };
  if (runJson === undefined) {
    try { runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: active.runId }); } catch { runJson = null; }
  }
  const leaseExpired = active.lease?.expiresAt ? new Date(active.lease.expiresAt) < new Date() : true;
  const supervisorAlive = _pidAlive(runJson?.pids?.runnerPid ?? null);
  const sessionsDir = path.join(mainRepoRoot, 'tmp', 'dev-runner', 'sessions');
  const ownerActivity = readSessionActivity(sessionsDir, active.holder.agentSessionId);
  const opLeases = await readOwnershipOpLeases(mainRepoRoot);
  // Tempdoc 606 Piece 2: provenance mismatch — the running stack was built from a
  // different checkout than where this caller is working (the dominant stale-jar case).
  const leaseProv = active.provenance || null;
  const provenanceMismatch = !!(
    leaseProv?.repoRoot && callerRepoRoot &&
    _normPath(leaseProv.repoRoot) !== _normPath(callerRepoRoot)
  );
  const decision = computeOwnershipVerdict({
    active, callerSessionId, selfCheck: true, supervisorAlive, leaseExpired,
    ownerActivity, opLeases, takeover, provenance: { mismatch: provenanceMismatch }, now: Date.now(),
  });
  const ownership = {
    holder: active.holder,
    takeoverPolicy: active.takeoverPolicy ?? null,
    launcherFamily: active.launcherFamily ?? null,
    mode: active.mode ?? null,
    callerIsOwner: !!(callerSessionId && callerSessionId === active.holder.agentSessionId),
    verdict: decision.verdict,
    grade: decision.grade,
    recommendedAction: decision.recommendedAction,
    ...(decision.rebuildFirst ? { rebuildFirst: true } : {}),
    ...(leaseProv ? { provenance: leaseProv } : {}),
  };
  if (active.lease) {
    // Tempdoc 735 G6: surface remaining-hold as a computed, additive field so quick_health /
    // status callers don't each redo the expiresAt-minus-now arithmetic to see how much of a
    // declared campaign-length hold is left.
    // A dead supervisor makes any advertised hold moot (verdict short-circuits to RECLAIM_DEAD),
    // so liveness-qualify the advisory fields rather than showing hours of remaining hold on a
    // crashed stack.
    const remainingMs = supervisorAlive
        ? new Date(active.lease.expiresAt).getTime() - Date.now() : 0;
    ownership.lease = { ...active.lease, remainingSec: Math.max(0, Math.round(remainingMs / 1000)) };
    ownership.leaseFresh = supervisorAlive && new Date(active.lease.expiresAt) > new Date();
  }
  if (opLeases.active.length > 0) ownership.opLeases = opLeases.active;
  // Tempdoc 606 3a: pull-at-next-action notification. Did THIS caller previously own a
  // stack (recorded ownedEpoch) that has since been taken over by someone else?
  try {
    const callerAct = callerSessionId ? readSessionActivity(sessionsDir, callerSessionId) : null;
    const notice = computeDisplacedNotice(
      callerAct?.ownedEpoch, active.ownershipEpoch, active.holder.agentSessionId, callerSessionId);
    if (notice) ownership.displacedNotice = notice;
  } catch { /* notification is best-effort */ }
  // Tempdoc 606 Piece 2b: cross-check the lease's launched stamp against the RUNNING
  // Head's self-reported stamp (manifest.head.buildStamp). A mismatch means a stale/
  // foreign Head is answering on the port despite a fresh lease — the "callerIsOwner
  // but the backend is the killed old one" case. Best-effort; never fails the tool.
  if (leaseProv?.headDistStamp && runJson?.dataDir) {
    try {
      const manRaw = await fsp.readFile(path.join(runJson.dataDir, 'runtime', 'manifest.json'), 'utf8').catch(() => null);
      const runningStamp = manRaw ? JSON.parse(manRaw)?.head?.buildStamp : null;
      if (runningStamp && runningStamp !== leaseProv.headDistStamp) {
        ownership.backendStale = true;
        ownership.runningHeadStamp = runningStamp;
        // Tempdoc 637 #2: surface the stale-jar masquerade LOUDLY at its own (dev-tooling) layer.
        // The running Head serves a different build than the lease launched (installDist skipped /
        // reported UP-TO-DATE), so any behaviour observed may be the OLD code — the silent stale-jar
        // trap that reads one layer up as a "product bug". Prepend (not clobber) the remedy so it is
        // unmissable while preserving any ownership/contention guidance the verdict already set.
        const staleRemedy =
          'STALE BACKEND: the running Head is serving an OLDER build than your source — behaviour may ' +
          'reflect old code. Run `./gradlew.bat :modules:ui:installDist` then restart/reload before ' +
          'trusting results. (Stamp covers the head dist only; the worker dist is not stamped.)';
        ownership.recommendedAction = ownership.recommendedAction
          ? `${staleRemedy} [then: ${ownership.recommendedAction}]`
          : staleRemedy;
      }
    } catch { /* manifest missing/unreadable — skip the cross-check */ }
  }
  return { ownership, decision };
}

/**
 * Tempdoc 637 §G.1 — inline staleness self-declaration at the surface of use. Attaches the 606
 * ownership projection to a stack-touching tool's result **when the running stack is stale** (a jar
 * older than the lease launched — the silent stale-jar masquerade #2). Without this, an agent
 * validating via an action tool sees a clean result and never learns it trusted old code unless it
 * separately pulls `quick_health` (a discipline-dependent ~70% pull). This makes the dev-tooling
 * layer self-declare like #1 (every FE render reads the verdict) and #3 (every search response
 * carries degradation), completing the 606 seam rather than forking one.
 *
 * Stale-only (attaches solely when `backendStale`, matching the `...(x ? {x} : {})` convention — no
 * noise on a fresh stack). Called AFTER the result is schema-parsed, so there is no schema
 * interaction. Fail-open: a projection error never breaks the tool.
 */
export async function withStaleness(structured, { mainRepoRoot, callerRepoRoot, callerSessionId }) {
  try {
    const active = await readJsonFileNoSymlinks({
      repoRoot: mainRepoRoot,
      relPosix: 'tmp/dev-runner/active.json',
      maxBytes: 200_000,
    });
    if (!active?.holder) return structured; // no running stack → nothing to declare
    const { ownership } = await buildOwnershipProjection({
      mainRepoRoot,
      callerRepoRoot,
      callerSessionId,
      takeover: 'deny',
      active,
    });
    if (ownership?.backendStale && structured && typeof structured === 'object') {
      // Carries backendStale + runningHeadStamp + the loud "STALE BACKEND … run installDist" remedy.
      structured.ownership = ownership;
    }
  } catch {
    /* fail-open — a staleness-projection error must never break the tool's own result */
  }
  return structured;
}

function httpGetStatusCode(urlStr, timeoutMs) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      return resolve(null);
    }

    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port),
        path: u.pathname + u.search,
        method: 'GET',
        timeout: timeoutMs,
      },
      (res) => {
        res.resume();
        resolve(typeof res.statusCode === 'number' ? res.statusCode : null);
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', () => resolve(null));
    req.end();
  });
}

function tail(str, maxChars) {
  const s = String(str || '');
  if (s.length <= maxChars) return s;
  return s.slice(s.length - maxChars);
}

/**
 * Tempdoc 844 B4b — `maxBytes` is a READ BUDGET, not a hard fetch cap.
 *
 * It used to `res.destroy(new Error('response_too_large'))`, so a caller who passed
 * `maxBytes: 2000` intending to *shrink* the output got a failed call instead (measured twice).
 * Now the prefix that fits is kept, reading stops, and the outcome is declared: `truncated: true`
 * plus `bytesRead`/`maxBytes`. A truncated body will not parse as JSON, so every JSON-parsing
 * caller must branch on `truncated` and say so — a bare `textTail` leaves the caller no better off
 * than the old error did. No caller in this server depended on the old `response_too_large`
 * message (the separate prod MCP server has its own copy of the helper), so there is no
 * hard-fail flag to keep alive here.
 */
function collectLimited(res, { statusCode, maxBytes, finish }) {
  let bytes = 0;
  const chunks = [];
  let stopped = false;

  res.on('data', (chunk) => {
    if (stopped) return;
    if (bytes + chunk.length > maxBytes) {
      const room = Math.max(0, maxBytes - bytes);
      if (room > 0) chunks.push(chunk.subarray(0, room));
      bytes += room;
      stopped = true;
      try { res.destroy(); } catch { /* already gone */ }
      const text = Buffer.concat(chunks).toString('utf8');
      finish({ ok: true, statusCode, text, textTail: tail(text, 8000), truncated: true, bytesRead: bytes, maxBytes, error: null });
      return;
    }
    bytes += chunk.length;
    chunks.push(chunk);
  });
  res.on('error', (err) => {
    const text = Buffer.concat(chunks).toString('utf8');
    finish({
      ok: false,
      statusCode,
      text,
      textTail: tail(text, 8000),
      truncated: false,
      bytesRead: bytes,
      maxBytes,
      error: { message: err?.message || String(err) },
    });
  });
  res.on('end', () => {
    const text = Buffer.concat(chunks).toString('utf8');
    finish({ ok: true, statusCode, text, textTail: tail(text, 8000), truncated: false, bytesRead: bytes, maxBytes, error: null });
  });
}

/**
 * Tempdoc 844 B4b — the ONE truncation notice, shared by every JSON-parsing caller so a truncated
 * read can never surface as a bare `textTail` or a misleading "Invalid JSON response".
 */
export function truncationNotice({ bytesRead, maxBytes }) {
  return {
    code: 'RESPONSE_TRUNCATED',
    message:
      `Response TRUNCATED: read ${bytesRead} bytes and stopped at maxBytes=${maxBytes}. `
      + 'The returned body is a partial prefix and does NOT parse as JSON. '
      + 'maxBytes caps what is READ from the backend, so lowering it cannot shrink a large response — '
      + 'raise maxBytes, and use jsonPath (or outputMode/summaryOnly) to shrink what is returned.',
  };
}

function httpGetTextLimited(urlStr, { timeoutMs, maxBytes, method = 'GET' }) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      return resolve({ ok: false, statusCode: null, textTail: null, error: { message: 'invalid_url' } });
    }

    let settled = false;
    const finish = (payload) => {
      if (settled) return;
      settled = true;
      resolve(payload);
    };

    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port),
        path: u.pathname + u.search,
        method,
        timeout: timeoutMs,
        headers: { Accept: 'application/json' },
      },
      (res) => collectLimited(res, { statusCode: typeof res.statusCode === 'number' ? res.statusCode : null, maxBytes, finish }),
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) =>
      finish({ ok: false, statusCode: null, text: null, textTail: null, truncated: false, bytesRead: 0, maxBytes, error: { message: err?.message || String(err) } }),
    );
    req.end();
  });
}

function httpPostJsonLimited(urlStr, body, { timeoutMs, maxBytes, method = 'POST' }) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      return resolve({ ok: false, statusCode: null, text: null, textTail: null, error: { message: 'invalid_url' } });
    }

    const bodyStr = JSON.stringify(body);

    let settled = false;
    const finish = (payload) => {
      if (settled) return;
      settled = true;
      resolve(payload);
    };

    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port),
        path: u.pathname + u.search,
        method,
        timeout: timeoutMs,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(bodyStr),
          Accept: 'application/json',
        },
      },
      (res) => collectLimited(res, { statusCode: typeof res.statusCode === 'number' ? res.statusCode : null, maxBytes, finish }),
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) =>
      finish({ ok: false, statusCode: null, text: null, textTail: null, truncated: false, bytesRead: 0, maxBytes, error: { message: err?.message || String(err) } }),
    );
    req.write(bodyStr);
    req.end();
  });
}

async function waitReady({ apiBaseUrl, level, timeoutMs }) {
  const base = ensureLoopbackUrl(apiBaseUrl, 'apiBaseUrl');
  const statusUrl = new URL('/api/status', base).toString();
  const healthUrl = new URL('/api/health', base).toString();

  const deadline = Date.now() + timeoutMs;
  let lastStatus = null;
  let lastHealth = null;

  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    lastStatus = await httpGetStatusCode(statusUrl, 1200);
    if (lastStatus === 200) {
      if (level === 'ready_http') {
        return { ok: true, level, apiStatus: 200, apiHealth: null };
      }
      // eslint-disable-next-line no-await-in-loop
      lastHealth = await httpGetStatusCode(healthUrl, 1200);
      if (lastHealth === 200) {
        return { ok: true, level, apiStatus: 200, apiHealth: 200 };
      }
    }
    // eslint-disable-next-line no-await-in-loop
    await delay(250);
  }

  return { ok: false, level, apiStatus: lastStatus, apiHealth: lastHealth };
}

function toToolResult(structuredContent) {
  // Tempdoc 637 §H.1 — self-declare on EVERY tool result if this MCP server is running stale code (fail-open).
  try {
    const stale = mcpStaleNotice(BOOT_SOURCE_STAMP);
    if (stale && structuredContent && typeof structuredContent === 'object') {
      structuredContent.mcpServerStale = stale;
    }
  } catch { /* fail-open — never break a tool result on a self-freshness error */ }
  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify(structuredContent, null, 2),
      },
    ],
    structuredContent,
  };
}

/**
 * Resolves a runId, falling back to the active run when runId is null/undefined.
 * @returns {Promise<string|null>}
 */
async function resolveRunId(sharedRoot, runId) {
  if (runId) return runId;
  try {
    const active = await readJsonFileNoSymlinks({
      repoRoot: sharedRoot,
      relPosix: 'tmp/dev-runner/active.json',
      maxBytes: 200_000,
    });
    return active?.runId ?? null;
  } catch {
    return null;
  }
}

/**
 * Safely reads run.json for a runId, returning a structured error on failure.
 * When runId is null/undefined, resolves to the active run automatically.
 * @returns {{ ok: true, runId: string, runJson: object } | { ok: false, runId: string|null, error: { code: string, message: string } }}
 */
async function safeReadRunJson(mainRepoRoot, runId) {
  const effectiveId = await resolveRunId(mainRepoRoot, runId);
  if (!effectiveId) {
    return {
      ok: false,
      runId: null,
      error: {
        code: 'NO_ACTIVE_RUN',
        message: 'No active run found. Recovery: call start to launch the dev stack, then retry.',
      },
    };
  }
  try {
    const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: effectiveId });
    return { ok: true, runId: effectiveId, runJson };
  } catch (err) {
    const isNotFound = err?.code === 'ENOENT';
    return {
      ok: false,
      runId: effectiveId,
      error: {
        code: isNotFound ? 'RUN_NOT_FOUND' : 'RUN_READ_ERROR',
        message: isNotFound
          ? `Run not found: ${effectiveId}. Recovery: omit runId to auto-resolve the active run, or call quick_health to check current state.`
          : `Failed to read run.json for ${effectiveId}: ${err?.message || err}. Recovery: call stop to clean up, then start a fresh run.`,
      },
    };
  }
}

/**
 * Resolves the API base URL from either an explicit apiPort or a runId (falling back to active run).
 * Carries the run's spawn-time chatProfile (tempdoc 842 §2.4) when the run record has one, so
 * callers that auto-activate can follow the stack's own profile choice; null when resolving by
 * bare apiPort or for pre-842 run records.
 * @returns {{ ok: true, apiBaseUrl: string, runId: string|null, chatProfile: string|null } | { ok: false, error: { code: string, message: string } }}
 */
async function resolveApiBaseUrl({ runId, apiPort, mainRepoRoot }) {
  if (apiPort) {
    const url = ensureLoopbackUrl(`http://127.0.0.1:${apiPort}`, 'apiPort');
    return { ok: true, apiBaseUrl: url.toString().replace(/\/$/, ''), runId: runId ?? null, chatProfile: null };
  }
  const result = await safeReadRunJson(mainRepoRoot, runId);
  if (!result.ok) return { ok: false, error: result.error };
  const apiBaseUrl = String(result.runJson?.apiBaseUrl || '').trim();
  if (!apiBaseUrl) return { ok: false, error: { code: 'NO_API_URL', message: `Run ${result.runId} has no API URL (apiBaseUrl missing from run.json). The run may not have fully started — call quick_health to check.` } };
  const chatProfile = typeof result.runJson?.chatProfile === 'string' ? result.runJson.chatProfile : null;
  return { ok: true, apiBaseUrl, runId: result.runId, chatProfile };
}

/** Default inference server port (llama-server). Read once at module load. */
const INFERENCE_PORT = parseInt(process.env.JUSTSEARCH_SERVER_PORT, 10) || 8080;

/* ── Tempdoc 844 B4a — honest JSON projection ──────────────────────────────────────────────── */

/**
 * Parse a projection expression into segments: `"a.b[0].c"` -> `['a', 'b', 0, 'c']`.
 * Returns null for a malformed expression (empty segment, stray bracket) so the caller can say
 * "that is not a path" rather than silently missing.
 */
export function parseJsonPathExpr(expr) {
  const raw = String(expr ?? '');
  if (!raw) return null;
  const out = [];
  for (const seg of raw.split('.')) {
    const m = /^([^[\]]*)((?:\[\d+\])*)$/.exec(seg);
    if (!m) return null;
    const [, name, indices] = m;
    if (name) out.push(name);
    else if (!indices) return null; // "a..b", a trailing "." or a leading "."
    for (const idx of indices.match(/\d+/g) ?? []) out.push(Number(idx));
  }
  return out.length > 0 ? out : null;
}

/** What a container actually offers, for a miss message. Key list is capped so a hint stays a hint. */
function availableAt(container) {
  if (Array.isArray(container)) {
    return {
      kind: 'array',
      length: container.length,
      hint: container.length > 0 ? `use an index [0]..[${container.length - 1}]` : 'the array is empty',
    };
  }
  if (container && typeof container === 'object') {
    const keys = Object.keys(container);
    return {
      kind: 'object',
      keys: keys.slice(0, 50),
      ...(keys.length > 50 ? { keysTotal: keys.length } : {}),
    };
  }
  return { kind: container === null ? 'null' : typeof container, hint: 'a scalar has no members' };
}

/**
 * Tempdoc 844 B4a — project a subtree, and on a miss return the keys that ARE there.
 *
 * The previous dot-path `reduce` discarded the parsed JSON on a miss and let the handler fall back
 * to the raw `textTail`, so a one-character typo returned the largest possible payload — the most
 * expensive answer to the smallest mistake. Now a miss names the deepest segment that DID resolve
 * and what is available there, and the body is withheld. Array indexing (`a.b[0].c`) is supported.
 *
 * One implementation, two callers (`fetch_api_json` and `api_call`).
 */
export function projectJsonPath(root, expr) {
  const segs = parseJsonPathExpr(expr);
  if (segs == null) {
    return {
      ok: false,
      error: {
        code: 'JSON_PATH_INVALID',
        message: `jsonPath "${expr}" is not a valid path. Use dots for keys and brackets for array indices, e.g. "a.b[0].c".`,
      },
    };
  }
  let cur = root;
  const walked = [];
  for (const seg of segs) {
    const isIndex = typeof seg === 'number';
    const missing = cur == null || typeof cur !== 'object'
      || (isIndex
        ? !Array.isArray(cur) || seg >= cur.length
        : !Object.prototype.hasOwnProperty.call(cur, seg));
    if (missing) {
      const prefix = walked.length > 0 ? walked.join('') : '(root)';
      const available = availableAt(cur);
      const shown = available.kind === 'object'
        ? `available keys at ${prefix}: ${available.keys.join(', ') || '(none)'}`
        : available.kind === 'array'
          ? `${prefix} is an array of length ${available.length} — ${available.hint}`
          : `${prefix} is ${available.kind} — ${available.hint}`;
      return {
        ok: false,
        resolvedPrefix: prefix,
        available,
        error: {
          code: 'JSON_PATH_MISS',
          message:
            `jsonPath "${expr}" did not resolve: ${isIndex ? `[${seg}]` : `"${seg}"`} is not present at ${prefix}. ${shown}. `
            + 'The response body was deliberately NOT returned — re-request with a corrected jsonPath, or omit jsonPath to see the whole response.',
        },
      };
    }
    walked.push(isIndex ? `[${seg}]` : (walked.length > 0 ? `.${seg}` : String(seg)));
    cur = cur[seg];
  }
  return { ok: true, value: cur };
}

/**
 * `justsearch.dev.fetch_api_json`'s endpoint-key -> path map. Module-scope and exported (tempdoc 844
 * P6) so `scripts/ci/check-dev-mcp-doc-sync.mjs` can compare it against the reference doc's table
 * instead of re-deriving it — the doc had `effective_config` pointing at a path that does not exist.
 * The key set must stay identical to FetchApiEndpointSchema's enum; the gate asserts that too.
 */
export const FETCH_API_ENDPOINT_MAP = {
  status: '/api/status',
  health: '/api/health',
  effective_config: '/api/debug/effective-config',
  debug_state: '/api/debug/state',
  policy_effective: '/api/policy/effective',
  inference_status: '/api/inference/status',
  gpu_capabilities: '/api/gpu/capabilities',
  ui_ready: '/api/ui/ready',
  ai_runtime_status: '/api/ai/runtime/status',
};

/**
 * `justsearch.dev.api_call`'s path allowlist. Module-scope and exported (tempdoc 844 P6) so the
 * doc-sync gate reads the array itself rather than a regex over this file.
 */
export const API_CALL_ALLOWLIST = [
  // Settings & preview
  { path: '/api/settings/v2', methods: ['GET', 'POST'] },
  { path: '/api/preview', methods: ['GET'] },
  // Indexing & migration
  { path: '/api/indexing/roots', methods: ['GET', 'POST', 'DELETE'] },
  // Tempdoc 599 — per-folder status substrate + add-time preview + folder-scoped failed jobs
  { path: '/api/indexing-roots/substrate', methods: ['GET'] },
  { path: '/api/indexing-roots/preview', methods: ['POST'] },
  { path: '/api/indexing-jobs/failed/by-prefix', methods: ['GET'] },
  { path: '/api/indexing/reindex', methods: ['POST'] },
  { path: '/api/indexing/excludes/apply', methods: ['POST'] },
  { path: '/api/indexing/migration/start', methods: ['POST'] },
  { path: '/api/indexing/migration/cutover', methods: ['POST'] },
  { path: '/api/indexing/migration/rollback', methods: ['POST'] },
  { path: '/api/indexing/migration/pause', methods: ['POST'] },
  { path: '/api/indexing/migration/resume', methods: ['POST'] },
  { path: '/api/indexing/gc', methods: ['POST'] },
  // Inference
  { path: '/api/inference/status', methods: ['GET'] },
  { path: '/api/inference/mode', methods: ['POST'] },
  { path: '/api/inference/reload', methods: ['POST'] },
  // Worker
  { path: '/api/worker/restart', methods: ['POST'] },
  // AI install
  { path: '/api/ai/install/status', methods: ['GET'] },
  { path: '/api/ai/install/manifest', methods: ['GET'] },
  // The side-effect-free plan projection. Predates tempdoc 840 (added by 657) and was never
  // allowlisted, so the one endpoint that answers "what would this machine install, and what does
  // each piece cost" was unreachable from the dev tools.
  { path: '/api/ai/install/plan-preview', methods: ['GET'] },
  { path: '/api/ai/install/start', methods: ['POST'] },
  { path: '/api/ai/install/cancel', methods: ['POST'] },
  { path: '/api/ai/install/repair', methods: ['POST'] },
  { path: '/api/ai/install/pause', methods: ['POST'] },
  { path: '/api/ai/install/resume', methods: ['POST'] },
  // Per-component intent (tempdoc 840). The package id is a path segment, so this entry matches by
  // pattern; `path` stays as the human-readable form used in the not-allowlisted message.
  {
    path: '/api/ai/install/packages/{packageId}/decline',
    pattern: /^\/api\/ai\/install\/packages\/[A-Za-z0-9._-]+\/decline$/,
    methods: ['POST', 'DELETE'],
  },
  // AI runtime
  { path: '/api/ai/runtime/status', methods: ['GET'] },
  { path: '/api/ai/runtime/activate', methods: ['POST'] },
  { path: '/api/ai/runtime/deactivate', methods: ['POST'] },
  // AI packs
  { path: '/api/ai/packs/status', methods: ['GET'] },
  { path: '/api/ai/packs/installed', methods: ['GET'] },
  { path: '/api/ai/packs/preflight', methods: ['POST'] },
  { path: '/api/ai/packs/import', methods: ['POST'] },
  // Policy
  { path: '/api/policy/validate', methods: ['GET'] },
  { path: '/api/policy/user/create', methods: ['POST'] },
  { path: '/api/policy/user/allowlist/pack-manifest/add', methods: ['POST'] },
  // Diagnostics & knowledge
  { path: '/api/diagnostics/export', methods: ['POST'] },
  { path: '/api/knowledge/status', methods: ['GET'] },
  // Debug & telemetry
  { path: '/api/debug/events', methods: ['GET'] },
  { path: '/api/debug/worker-log', methods: ['GET'] },
  { path: '/api/telemetry/health', methods: ['GET'] },
  // Action ledger — read-only activity/change feed (tempdoc 618 §8)
  { path: '/api/action-ledger', methods: ['GET'] },
];

/* ── Tempdoc 844 B3 — backends this dev-runner did not start ───────────────────────────────── */

/**
 * Well-known loopback ports that can host a JustSearch-shaped backend started OUTSIDE the
 * dev-runner. `33221` is `jseval`'s eval backend, which binds that port hardcoded and ignores
 * `JUSTSEARCH_API_PORT` (`scripts/jseval/jseval/backend.py:21`,
 * `scripts/jseval/jseval/commands/_common.py:14-22`) — the single most common way a JVM ends up
 * holding ports and the GPU while `quick_health` reports nothing running.
 *
 * Deliberately a SHORT fixed list, not a scan: tempdoc 844 §12.4 rules out building a run registry,
 * and `quick_health` must stay cheap (132 calls, 0% errors, no subprocess).
 */
export const FOREIGN_BACKEND_PORTS = [33221];

/**
 * Tempdoc 844 D3 — where a non-dev-runner producer declares its run.
 *
 * `scripts/jseval/jseval/run_register.py` writes one small versioned record per backend it
 * spawns; this is the consumer. The directory sits BESIDE the dev-runner's own state, never
 * inside it: `dev-runner.cjs` enumerates only `runs/` (`:387`, which it also prunes) and reads
 * `active.json` / `active.lock.json` / `op-leases.json` / `sessions/` / `interference-events.ndjson`
 * by exact name — it never lists its state root — so `foreign/` cannot be mistaken for one of its
 * runs and the 271/542 lease semantics are untouched.
 */
export const FOREIGN_REGISTER_RELPOSIX = 'tmp/dev-runner/foreign';

/** The record shape this reader understands. A record declaring anything else is reported as
 *  unreadable rather than interpreted on a guess. */
export const FOREIGN_RECORD_SCHEMA_VERSION = 1;

/** Bounds: `quick_health` is called ~133×/21 sessions at 0% errors and must stay cheap. */
const FOREIGN_REGISTER_MAX_RECORDS = 32;
const FOREIGN_REGISTER_MAX_BYTES = 16_000;

/**
 * Tempdoc 844 D3 — read the foreign-run register.
 *
 * Returns one entry per `*.json` file, each either `{ ok: true, recordId, record }` or
 * `{ ok: false, recordId, reason }`. A torn, oversized, non-JSON or wrong-version file becomes an
 * `ok: false` entry, never an exception and never a silent skip: "there is a record here I could
 * not read" is a state the caller must be told about, not one to hide (§12.2).
 *
 * Makes NO liveness claim — that is `probeForeignRuns`' job, from the pid and the port.
 */
export async function readForeignRegister({
  dir,
  maxRecords = FOREIGN_REGISTER_MAX_RECORDS,
  maxBytes = FOREIGN_REGISTER_MAX_BYTES,
} = {}) {
  if (!dir) return [];
  let names;
  try {
    const entries = await fsp.readdir(dir, { withFileTypes: true });
    names = entries.filter((e) => e.isFile() && e.name.endsWith('.json')).map((e) => e.name).sort();
  } catch {
    return []; // no register directory at all — nothing has ever registered here
  }

  const out = [];
  for (const name of names.slice(0, maxRecords)) {
    const recordId = name.replace(/\.json$/, '');
    const abs = path.join(dir, name);
    try {
      const st = await fsp.lstat(abs);
      if (st.isSymbolicLink()) { out.push({ ok: false, recordId, reason: 'record is a symlink' }); continue; }
      if (st.size > maxBytes) { out.push({ ok: false, recordId, reason: `record too large (${st.size} > ${maxBytes} bytes)` }); continue; }
      const record = JSON.parse(await fsp.readFile(abs, 'utf8'));
      if (record?.schemaVersion !== FOREIGN_RECORD_SCHEMA_VERSION) {
        out.push({ ok: false, recordId, reason: `unknown schemaVersion ${JSON.stringify(record?.schemaVersion)} (this reader understands ${FOREIGN_RECORD_SCHEMA_VERSION})` });
        continue;
      }
      const port = record?.ports?.api;
      if (!Number.isInteger(port) || port <= 0) {
        out.push({ ok: false, recordId, reason: `record declares no usable ports.api (${JSON.stringify(port)})` });
        continue;
      }
      out.push({ ok: true, recordId, record });
    } catch (err) {
      out.push({ ok: false, recordId, reason: String(err?.message || err).slice(0, 200) });
    }
  }
  if (names.length > maxRecords) {
    out.push({ ok: false, recordId: '(register)', reason: `${names.length} records present; only the first ${maxRecords} were read` });
  }
  return out;
}

/**
 * Tempdoc 844 B3/§6.1 — probe for backends the dev-runner did not start, and report them as
 * *observed but unowned*, never merged with the owned run.
 *
 * Why this exists: `quick_health` read only `tmp/dev-runner/active.json`, so a `runHeadlessEval`
 * Head+Worker was invisible and a "free" verdict preceded a 100%-GPU neighbour — a contaminated
 * measurement round (session shard `bccfc163`, 2026-08-14).
 *
 * The tri-state is the whole point and must not be collapsed:
 *   `null` = probing was off or the probe itself failed — I did not look.
 *   `[]`   = I looked and found nothing.
 *   `[..]` = I looked and found these, and none of them is the run I own.
 *
 * Tempdoc 844 D3 extends this with the *register* (`readForeignRegister`), which turns "something
 * is listening on 33221" into "jseval's eval backend, from tree X, pid N". The two sources are
 * merged but never conflated — every entry carries `source`:
 *
 *   `source:'registered'` — a producer declared this run. `state` then says what was verified:
 *       `live`        the declared port answered;
 *       `unreachable` the port is silent but the pid is alive (booting, or wedged);
 *       `stale`       the port is silent AND the pid is gone — a record its producer never got to
 *                     retire (a killed `jseval` never runs its cleanup). Reported, NOT deleted:
 *                     deleting another lifecycle's state on a read is exactly the kind of
 *                     confident guess §12.2 forbids, and the file path is given so removal is a
 *                     one-liner for whoever owns it;
 *       `unreadable`  the file exists but could not be parsed/understood — an explicit unknown.
 *   `source:'observed'` — a port answered with no record behind it. Identical to the P5 behaviour;
 *       all that is known is that something is listening.
 *
 * Registration is the authoritative path and probing is the fallback that keeps the register
 * honest about what never registered — so a registered port is never ALSO reported as `observed`.
 *
 * `probe`, `readRegister` and `isPidAlive` are injectable so this is unit-testable without a
 * network, and `registerDir` can point at a fixture. Still spawns no subprocess.
 */
export async function probeForeignRuns({
  enabled,
  hasActiveRun,
  ownedApiPort = null,
  aiActive = null,
  ports = FOREIGN_BACKEND_PORTS,
  inferencePort = INFERENCE_PORT,
  timeoutMs = 800,
  probe = httpGetStatusCode,
  registerDir = null,
  readRegister = readForeignRegister,
  isPidAlive = _pidAlive,
} = {}) {
  if (!enabled) return null;
  try {
    // The register is consulted only when probing is on. Without a probe a record's liveness
    // cannot be verified, and listing an unverified record as a run would be the confident
    // default §12.2 rules out — so `probe:false` keeps meaning exactly "I did not look".
    const register = registerDir ? await readRegister({ dir: registerDir }) : [];

    const registeredPorts = new Set();
    for (const entry of register) {
      if (entry.ok) registeredPorts.add(entry.record.ports.api);
    }

    // The owned run's own port is not worth a probe on its own account (nothing to learn), but a
    // record that CLAIMS it still has to be resolved — so it comes back in via registeredPorts.
    const backendPorts = new Set([...ports.filter((port) => port !== ownedApiPort), ...registeredPorts]);
    const checks = [...backendPorts]
      .map(async (port) => ({ port, kind: 'backend', probePath: '/api/status', code: await probe(`http://127.0.0.1:${port}/api/status`, timeoutMs) }));
    checks.push(
      probe(`http://127.0.0.1:${inferencePort}/health`, timeoutMs)
        .then((code) => ({ port: inferencePort, kind: 'inference', probePath: '/health', code })),
    );
    const results = await Promise.all(checks);
    const answered = new Map(results.filter((r) => r.kind === 'backend').map((r) => [r.port, r.code === 200]));

    const found = [];

    // 1. Registered records first — identity beats inference.
    for (const entry of register) {
      if (!entry.ok) {
        found.push({
          port: null,
          kind: 'backend',
          probePath: null,
          attribution: 'unknown',
          source: 'registered',
          state: 'unreadable',
          recordId: entry.recordId,
          recordFile: `${FOREIGN_REGISTER_RELPOSIX}/${entry.recordId}.json`,
          reason: entry.reason,
        });
        continue;
      }
      const rec = entry.record;
      const port = rec.ports.api;
      const portAnswered = answered.get(port) === true;
      const pidAlive = Number.isInteger(rec.pid) ? isPidAlive(rec.pid) : null;
      const state = portAnswered ? 'live' : (pidAlive ? 'unreachable' : 'stale');
      found.push({
        port,
        kind: 'backend',
        probePath: '/api/status',
        // A record claiming the port the dev-runner's own run holds cannot be attributed by this
        // probe — whichever process answered, the listener is ambiguous. Unknown, not unowned.
        attribution: port === ownedApiPort ? 'unknown' : 'unowned',
        source: 'registered',
        state,
        liveness: { portAnswered, pidAlive },
        // The port answers but the process the record names is gone: something IS up, but this
        // record's identity may no longer describe it (jseval records the launcher pid, and the
        // Worker JVM has been observed to outlive its process tree — `backend.py:26-35`). Saying
        // "live" alone would attach verified-listener status to unverified identity.
        ...(portAnswered && pidAlive === false ? { identityStale: true } : {}),
        recordId: rec.recordId ?? entry.recordId,
        recordFile: `${FOREIGN_REGISTER_RELPOSIX}/${entry.recordId}.json`,
        producer: rec.producer ?? null,
        pid: Number.isInteger(rec.pid) ? rec.pid : null,
        repoRoot: rec.repoRoot ?? null,
        dataDir: rec.dataDir ?? null,
        workload: rec.workload ?? null,
        inferenceRequested: typeof rec.inferenceRequested === 'boolean' ? rec.inferenceRequested : null,
        // Passed through verbatim. `"unverified"` is the producer saying it did not measure GPU
        // residency; this reader must not upgrade that to a claim it also cannot verify.
        gpuBound: rec.gpuBound ?? null,
        sessionId: rec.sessionId ?? null,
        startedAt: rec.startedAt ?? null,
      });
    }

    // 2. Then anything listening that nothing declared — today's P5 behaviour, unchanged.
    for (const r of results) {
      if (r.code !== 200) continue;
      if (r.kind === 'inference') {
        // The owned run's OWN llama-server answers here too. Only the run's realized AI state can
        // tell them apart, and that state is itself a tri-state — so an unknown stays unknown
        // rather than being reported as somebody else's process.
        if (hasActiveRun && aiActive === true) continue;
        found.push({
          port: r.port,
          kind: 'inference',
          probePath: r.probePath,
          attribution: !hasActiveRun || aiActive === false ? 'unowned' : 'unknown',
          source: 'observed',
        });
      } else {
        if (registeredPorts.has(r.port)) continue; // already reported, with identity
        if (r.port === ownedApiPort) continue;     // the run this dev-runner owns
        found.push({ port: r.port, kind: 'backend', probePath: r.probePath, attribution: 'unowned', source: 'observed' });
      }
    }
    return found;
  } catch {
    return null; // the probe itself failed — "I did not look" is the honest answer, not []
  }
}

/* ── Tempdoc 844 B1/B2 — the checkout `start` will actually launch from ─────────────────────── */

/** Directory names under `<main>/.claude/worktrees`, for a "which names DO exist" error message. */
async function listWorktreeNames(mainRepoRoot) {
  try {
    const entries = await fsp.readdir(path.join(mainRepoRoot, '.claude', 'worktrees'), { withFileTypes: true });
    return entries.filter((e) => e.isDirectory()).map((e) => e.name).sort();
  } catch {
    return [];
  }
}

/**
 * Resolve the effective checkout for a `distFrom`, shared by `start` (which launches from it) and
 * `preflight` (which must check the SAME tree). They were separate before: preflight validated the
 * invoking checkout's dists while start used `distFrom`'s, so preflight passed and start then
 * failed — a false green on 125 of 162 observed starts' worth of surface (tempdoc 844 §5.1).
 * Extracted rather than duplicated precisely so they cannot drift apart again.
 *
 * A bare worktree NAME (`"round14"`) resolves against `<main>/.claude/worktrees/<name>` when that
 * directory exists; when it does not, the refusal says which names do (§5.1: two measured failures
 * were bare names rejected with no hint).
 */
export async function resolveDistRoot({ distFrom, mainRepoRoot, fallbackRepoRoot, fallbackDevRunnerPath = null }) {
  if (!distFrom) {
    return { ok: true, repoRoot: fallbackRepoRoot, devRunnerPath: fallbackDevRunnerPath, distFrom: null, resolvedVia: 'caller-checkout' };
  }
  const raw = String(distFrom).trim();
  const looksBare = /^[^\\/:]+$/.test(raw) && raw !== '.' && raw !== '..';

  let resolved = path.resolve(mainRepoRoot, raw);
  let resolvedVia = 'path';
  if (looksBare) {
    const candidate = path.join(mainRepoRoot, '.claude', 'worktrees', raw);
    let isDir = false;
    try { isDir = (await fsp.stat(candidate)).isDirectory(); } catch { isDir = false; }
    if (isDir) {
      resolved = candidate;
      resolvedVia = 'worktree-name';
    }
  }

  const norm = resolved.replace(/\\/g, '/').replace(/\/+$/, '');
  const mainNorm = mainRepoRoot.replace(/\\/g, '/').replace(/\/+$/, '');
  const isMain = norm === mainNorm;
  const isWorktree = norm.startsWith(`${mainNorm}/.claude/worktrees/`);
  if (!isMain && !isWorktree) {
    const known = await listWorktreeNames(mainRepoRoot);
    return {
      ok: false,
      error: {
        code: 'INVALID_DIST_FROM',
        message: `distFrom must be the main repo or a sibling worktree under .claude/worktrees: ${raw}`
          + (looksBare ? ` (no worktree directory named "${raw}"). ` : '. ')
          + (known.length > 0
            ? `Worktrees that DO exist: ${known.join(', ')}.`
            : 'No worktrees exist under .claude/worktrees.'),
      },
    };
  }

  const devRunnerPath = path.join(resolved, 'scripts', 'dev', 'dev-runner.cjs');
  try {
    await fsp.access(devRunnerPath);
  } catch {
    return {
      ok: false,
      error: {
        code: 'INVALID_DIST_FROM',
        message: `dev-runner.cjs not found under distFrom (build the worktree first?): ${devRunnerPath}`,
      },
    };
  }
  return { ok: true, repoRoot: resolved, devRunnerPath, distFrom: raw, resolvedVia };
}

/**
 * Tempdoc 844 §4.2 R1 — where `reload` must compile and push FROM.
 *
 * The bug: `reload` read its target (data dir, signal file) from the active run record but took
 * its bytecode (Gradle wrapper, classes dir, HotSwapPush) from `process.cwd()` frozen at MCP-server
 * launch. Under `distFrom` — 125 of 162 measured starts — those are different trees by
 * construction, so agent B's classes were redefined into agent A's JVM and reported as success
 * (§5.6 case (c)).
 *
 * The fix is to derive everything from the run record's own `repoRoot` — the tree the running
 * Worker was actually launched from — validated through the SAME `resolveDistRoot` that `start`
 * and `preflight` share, so there is one root-resolution path, not three. When the run's tree
 * cannot be established this FAILS: falling back to the caller's tree is precisely the defect.
 */
export async function resolveReloadTarget({ mainRepoRoot, runJson }) {
  const rawRoot = typeof runJson?.repoRoot === 'string' ? runJson.repoRoot.trim() : '';
  if (!rawRoot) {
    return {
      ok: false,
      error: {
        code: 'RUN_ROOT_UNRESOLVED',
        message: 'The active run record does not say which checkout it was launched from '
          + '(run.json has no repoRoot), so there is no way to know which tree\'s bytecode belongs '
          + 'in it. Refusing to guess — stop and start the stack again to get a run record that '
          + 'records its own root.',
      },
    };
  }
  const resolved = await resolveDistRoot({ distFrom: rawRoot, mainRepoRoot, fallbackRepoRoot: null });
  if (!resolved.ok) {
    return {
      ok: false,
      error: {
        code: 'RUN_ROOT_UNRESOLVED',
        message: `The active run says it was launched from ${rawRoot}, which is not a usable `
          + `checkout right now: ${resolved.error.message}`,
      },
    };
  }
  const hr = runJson?.hotReload ?? null;
  if (!hr || hr.enabled !== true) {
    return {
      ok: false,
      error: {
        code: 'HOT_RELOAD_NOT_ENABLED',
        message: hr
          ? 'This stack was started with hotReload: false, so its Worker has no JDWP listener — '
            + 'there is nothing to push bytecode to. (The old instructions claimed reload still '
            + 'pushed method-body changes without it; WorkerSpawner\'s early return guards the '
            + '-agentlib:jdwp flag too, so that was never true.) Stop and start again; hotReload '
            + 'now defaults true.'
          : 'This run record predates the per-run hot-reload record (tempdoc 844 R3), so the JDWP '
            + 'port and target identity of its Worker are unknown. Refusing to attach to a port on '
            + 'the assumption that it is 5005 and belongs to this run. Stop and start again.',
      },
    };
  }
  if (!Number.isInteger(hr.debugPort) || hr.debugPort <= 0) {
    return {
      ok: false,
      error: {
        code: 'HOT_RELOAD_NOT_ENABLED',
        message: 'The run record enables hot reload but records no JDWP port, so the target port '
          + 'is unknown. Stop and start the stack again.',
      },
    };
  }
  return {
    ok: true,
    runRoot: resolved.repoRoot,
    dataDir: typeof runJson?.dataDir === 'string' && runJson.dataDir ? runJson.dataDir : null,
    debugPort: hr.debugPort,
    identityClassesDir: typeof hr.classesDir === 'string' && hr.classesDir ? hr.classesDir : null,
  };
}

/**
 * Tempdoc 844 §4.2 R2 — the Class-C middleware step §11.4 asks for: a tool that MUTATES a run
 * resolves the run, then checks ownership, then declares staleness. `reload` had none of the
 * three, so a non-owner could push bytecode into a peer's stack and tear its services down with
 * no signal to either agent.
 *
 * Shares the ONE verdict authority with `start` / `stop` / `acquire_when_free`, so the vocabulary
 * (`OWNER_CONFLICT`, `IDLE_HOLD`, `takeover`) is the existing one rather than a second dialect.
 */
export async function checkRunMutationOwnership({
  mainRepoRoot, callerRepoRoot, callerSessionId, takeover = 'deny', active, runJson, tool,
}) {
  const { ownership, decision } = await buildOwnershipProjection({
    mainRepoRoot, callerRepoRoot, callerSessionId, takeover, active, runJson,
  });
  if (decision && decision.action === 'conflict') {
    const idle = decision.verdict === 'IDLE_HOLD';
    return {
      allowed: false,
      ownership,
      decision,
      refusal: {
        ok: false,
        error: {
          code: 'OWNER_CONFLICT',
          message: `${tool} mutates the running stack, which another agent owns. `
            + decision.recommendedAction,
        },
        ...(ownership ? { ownership } : {}),
        actionRequired: idle
          ? `Owner is idle — retry with takeover: "warn" (no user approval needed). ${tool} does `
            + 'not transfer the lease; it only authorizes this call.'
          : `Ask the user before retrying with takeover: "warn". ${tool} does not transfer the `
            + 'lease; it only authorizes this call.',
      },
    };
  }
  return { allowed: true, ownership, decision };
}

/**
 * Tempdoc 844 F1 — what a structural change actually looks like in the pusher's output.
 *
 * The first pattern is HotSwapPush's own remedy sentence. The rest are the JVM's wording, and that
 * is the one that fires in practice: JDI throws `UnsupportedOperationException("<capability> not
 * implemented")` and HotSwapPush prints it verbatim — observed live 2026-08-19 as
 * `HotSwap not supported by target VM: add method not implemented`. Matching HotSwapPush's phrasing
 * alone made the gate dead: a real structural change fell through to the generic `HOTSWAP_FAILED`
 * ("no bytecode was pushed") and lost the restart remedy the output already contained.
 *
 * The family is matched, not the single observed string (JDI also reports delete method / schema
 * change / hierarchy change / class attribute change / class-file version change), but each pattern
 * is anchored either to the pusher's "not supported by target VM" line or to a named JDI
 * redefinition capability — so an unrelated failure (attach refused, timeout, ENOENT) does not land
 * here just because its text happens to contain "not implemented".
 */
const STRUCTURAL_CHANGE_PATTERNS = [
  /added\/removed methods or fields/,
  /HotSwap not supported by target VM:[^\r\n]*\bnot implemented\b/,
  /\b(?:add|delete) method not implemented\b/,
  /\b(?:schema|hierarchy|class attribute|class modifiers|method modifiers|class file version) change not implemented\b/,
];

/**
 * Tempdoc 844 §4.2 R5 — what HotSwapPush actually did, read from its exit code and its
 * machine-readable lines instead of from "it exited 0".
 *
 * §5.6 #4: the old tool printed "None of the N changed class(es) are loaded", exited 0, updated
 * its marker, and the handler recorded `hotSwapOk: true`. Two independent guards now stop that:
 * the pusher exits 4 in that case, AND a zero `REDEFINED` count is not accepted as success here
 * even on exit 0 (which also covers an older HotSwapPush copy).
 *
 * `identityRequired` maps to R3: when an identity token was passed, a push that did not print
 * IDENTITY_OK was not identity-checked, and an unverified push is not a confirmed one.
 */
export function classifyHotSwapOutcome({ exitCode, stdout = '', stderr = '', identityRequired = true }) {
  const out = `${stdout}\n${stderr}`;
  const count = (re) => { const m = out.match(re); return m ? Number(m[1]) : null; };
  const facts = {
    classesChanged: count(/^CHANGED (\d+)\s*$/m),
    // Tempdoc 844 F2: a non-zero exit reports 0 redefined classes, never the pusher's printed
    // number. This is a verified 0, not a defensive unknown: a JVMTI redefinition is atomic (all
    // classes or none), HotSwapPush never exits non-zero after `redefineClasses` returned, and the
    // live 2026-08-19 run showed the opposite — `REDEFINED 3` alongside exit 1 and a false claim
    // that three classes had been replaced. The pusher no longer prints the line before the call;
    // this guard also covers an older HotSwapPush copy in the run's tree.
    classesRedefined: exitCode === 0 ? count(/^REDEFINED (\d+)\s*$/m) : 0,
    classesNotLoaded: count(/^NOT_LOADED (\d+)\s*$/m),
    identityVerified: /^IDENTITY_OK /m.test(out),
    structuralChangeDetected: STRUCTURAL_CHANGE_PATTERNS.some((re) => re.test(out)),
  };
  if (exitCode === 5) {
    return { ...facts, hotSwapOk: false, outcome: 'IDENTITY_REFUSED', error: {
      code: 'TARGET_IDENTITY_MISMATCH',
      message: 'The JVM listening on the run\'s JDWP port was NOT launched from the tree this run '
        + 'record names — none of its classpath entries lie under that tree — so nothing was '
        + 'pushed. This is the cross-tree injection case: pushing would have replaced another '
        + 'stack\'s code with this tree\'s.',
    } };
  }
  if (exitCode === 6) {
    // Tempdoc 844 F3: same tree, wrong classpath. The live run misreported this as cross-tree
    // injection while all 183 of the VM's classpath entries were under this very worktree.
    return { ...facts, hotSwapOk: false, outcome: 'CLASSPATH_ABSENT', error: {
      code: 'HOT_RELOAD_CLASSPATH_ABSENT',
      message: 'The JVM listening on the run\'s JDWP port WAS launched from the tree this run '
        + 'record names, but WITHOUT the hot-reload classes dir on its classpath, so nothing was '
        + 'pushed. That means the process was launched from a stale distribution predating the '
        + 'hot-reload classpath. Remedy: rebuild the dist in that tree '
        + '(./gradlew.bat :modules:ui:installDist :modules:indexer-worker:installDist) and restart '
        + 'the stack.',
    } };
  }
  if (exitCode === 3) {
    return { ...facts, hotSwapOk: false, noOp: true, outcome: 'NOTHING_CHANGED' };
  }
  if (exitCode === 4) {
    return { ...facts, hotSwapOk: false, outcome: 'NO_CLASSES_REDEFINED', error: {
      code: 'NO_CLASSES_REDEFINED',
      message: `${facts.classesChanged ?? 'Some'} changed class file(s) were found, but none of `
        + 'them is loaded in the target VM, so no bytecode was replaced. Nothing about the running '
        + 'stack changed; services were NOT reconstructed.',
    } };
  }
  if (exitCode !== 0) {
    return { ...facts, hotSwapOk: false, outcome: 'PUSH_FAILED', error: {
      code: facts.structuralChangeDetected ? 'STRUCTURAL_CHANGE' : 'HOTSWAP_FAILED',
      message: facts.structuralChangeDetected
        ? 'Structural change (added/removed methods or fields) — standard HotSwap cannot apply it. '
          + 'Nothing was pushed; restart the stack for this change.'
        : 'HotSwapPush failed; no bytecode was pushed.',
    } };
  }
  if (identityRequired && !facts.identityVerified) {
    return { ...facts, hotSwapOk: false, outcome: 'IDENTITY_UNVERIFIED', error: {
      code: 'TARGET_IDENTITY_UNVERIFIED',
      message: 'The push tool did not confirm the target VM\'s identity, so it cannot be shown that '
        + 'the bytecode went into this run\'s Worker rather than another stack\'s. Treating it as '
        + 'not-confirmed rather than as success.',
    } };
  }
  if (!(facts.classesRedefined > 0)) {
    return { ...facts, hotSwapOk: false, outcome: 'NO_CLASSES_REDEFINED', error: {
      code: 'NO_CLASSES_REDEFINED',
      message: 'The push reported no redefined classes, so no bytecode was replaced.',
    } };
  }
  return { ...facts, hotSwapOk: true, outcome: 'REDEFINED' };
}

export async function main() {
  const { repoRoot, devRunnerPath } = resolveRepoRoot();
  const mainRepoRoot = resolveMainRepoRoot(repoRoot);

  const mcpServer = new McpServer(
    { name: 'justsearch-dev-mcp', version: '0.2.0' },
    {
      instructions: [
        'JustSearch dev tools — 12 tools for managing the local development stack.',
        '',
        'Categories: lifecycle (start, stop), orientation (quick_health, preflight, acquire_when_free),',
        'data (fetch_api_json, api_call, search_query, ingest),',
        'AI runtime (ai_activate), monitoring (tail_log), hot-reload (reload).',
        '',
        'Workflow: (1) quick_health to check state, (2) preflight if not running,',
        '(3) start to launch (cold: ~1 min, warm: ~15s HTTP / ~40s worker ready), (4) use tools, (5) stop when done.',
        'quick_health { detail: "full" } returns the dev-runner process/port/readiness payload too.',
        '',
        'Hot-reload: start defaults to hotReload: true (JDWP listener + DevReloadManager).',
        'Then call reload after code changes to compile + push bytecode + restart services (~2-3s);',
        'method bodies only — structural changes are reported, not applied. It keeps the ONNX',
        'encoders loaded across the change, which is the point (a warm restart reloads them: ~40s).',
        'With hotReload: false there is no JDWP listener, so reload refuses (HOT_RELOAD_NOT_ENABLED)',
        'instead of reporting a push it did not make. reload is ownership-gated like start/stop.',
        '',
        'Long campaigns: start with leaseDurationSec (30-7200) to hold ownership without frequent',
        'renewals — avoids a mid-campaign takeover when the agent is busy (jseval/gradle) for minutes',
        'with no session activity. quick_health reports remaining hold via ownership.lease.remainingSec.',
        '',
        'Prerequisites: ./gradlew.bat build must succeed before start.',
        'After compaction: call quick_health to re-orient.',
        '',
        'Common errors:',
        '- NO_ACTIVE_RUN: call start, then retry.',
        '- OWNER_CONFLICT: another agent owns the stack. Call quick_health to see the owner,',
        '  then ask the user before retrying with takeover: "warn".',
        '- RUN_NOT_FOUND: omit runId to auto-resolve the active run.',
        '- Preflight fails: fix the reported issue (build, stop stale run, check models).',
      ].join('\n'),
    },
  );

  mcpServer.registerTool(
    'justsearch.dev.start',
    {
      description: 'Launch the dev stack. Returns OWNER_CONFLICT if another agent owns it (use takeover param to override with user approval). Cold start: ~1 min; warm: ~15s (HTTP ready) / ~40s (worker ready). Blocks until readiness level reached.',
      inputSchema: StartInputSchema,
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = StartInputSchema.parse(rawArgs);
      const apiPort = input.apiPort ?? 0;
      const uiPort = input.uiPort ?? 5173;
      const clean = input.clean ?? 'soft';
      const waitLevel = input.waitLevel ?? 'ready_worker';
      const startTimeoutMs = input.startTimeoutMs ?? 600_000;
      const waitTimeoutMs = input.waitTimeoutMs ?? 60_000;

      let dataDir = null;
      if (input.dataDir) {
        // Safety boundary: repo-root only, never allow repo root itself.
        dataDir = resolveUnderRepo(repoRoot, input.dataDir, 'dataDir');
      }

      const takeover = input.takeover ?? undefined;
      const skipBuild = input.skipBuild === true;

      // Proactive ownership check (tempdoc 606): fail fast before spawning dev-runner, but
      // ONLY for genuine contention. The single verdict authority decides: abandoned/dead/
      // no-owner pass through to dev-runner (which proceeds); an IDLE owner returns a SOFT
      // conflict telling the agent it may self-authorize takeover:"warn" WITHOUT a user
      // round-trip; an ACTIVE owner (or unknown activity) is the only case that asks the user.
      // Only runs when takeover is not requested — explicit takeover goes straight to dev-runner.
      if (!takeover || takeover === 'deny') {
        try {
          const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
          const callerSessionId = input.sessionId || resolveAgentSessionIdForMcp(repoRoot);
          const { ownership, decision } = await buildOwnershipProjection({ mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId, takeover: 'deny', active });
          if (decision && decision.action === 'conflict') {
            const idle = decision.verdict === 'IDLE_HOLD';
            return toToolResult({
              ok: false,
              error: {
                code: 'OWNER_CONFLICT',
                message: decision.recommendedAction,
              },
              ownership,
              actionRequired: idle
                ? 'Owner is idle — retry with takeover: "warn" (no user approval needed).'
                : 'Ask the user before retrying with takeover: "warn".',
            });
          }
          // decision.action === 'proceed' (abandoned / dead / no-owner / self) → fall through to start.
        } catch (_) { /* active.json missing or unreadable — proceed to start */ }
      }

      // Tempdoc 844 §4.2 condition 3: hot reload defaults ON. It was opt-in on 1 of 162 measured
      // starts, so the capability — and the four silent failure modes §5.6 found in it — were
      // effectively unreachable. `hotReload: false` is the explicit opt-out.
      const hotReload = input.hotReload !== false;

      // Tempdoc 606 Piece 4: optionally launch from a specific worktree's dist. The shared
      // lease stays under the main repo (state is mainRepoRoot-scoped in the dev-runner), so a
      // worktree agent can run ITS code on the one shared stack. Validate distFrom is the main
      // repo or a sibling worktree before spawning that checkout's dev-runner.
      // Tempdoc 844 B1: resolution lives in resolveDistRoot, shared verbatim with preflight so the
      // tree preflight checks is the tree start launches from.
      const distRoot = await resolveDistRoot({
        distFrom: input.distFrom,
        mainRepoRoot,
        fallbackRepoRoot: repoRoot,
        fallbackDevRunnerPath: devRunnerPath,
      });
      if (!distRoot.ok) return toToolResult({ ok: false, error: distRoot.error });
      const effRepoRoot = distRoot.repoRoot;
      const effDevRunnerPath = distRoot.devRunnerPath;

      const args = buildDevRunnerArgsStart({
        apiPort, uiPort, clean, dataDir, takeover, skipBuild, hotReload,
        sessionId: input.sessionId,
        leaseDurationSec: input.leaseDurationSec,
        chatProfile: input.chatProfile,
      });
      maybeAppendNdjson(mainRepoRoot, { event: 'tool_start', tool: 'justsearch.dev.start', args: { apiPort, uiPort, clean, distFrom: input.distFrom ?? null } });

      let json;
      let recoveredApiBaseUrl = null;
      try {
        const result = await runCliJson({
          repoRoot: effRepoRoot,
          devRunnerPath: effDevRunnerPath,
          args,
          timeoutMs: startTimeoutMs,
          mode: 'supervisor_first_line',
        });
        json = result.json;
      } catch (err) {
        // On timeout, check if an active run was created anyway (race recovery)
        if (String(err?.message || '').includes('timed out')) {
          try {
            const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
            if (active?.runId) {
              const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: active.runId });
              recoveredApiBaseUrl = runJson?.apiBaseUrl || null;
              maybeAppendNdjson(mainRepoRoot, { event: 'tool_start_result', tool: 'justsearch.dev.start', ok: true, recovered: true });
              json = {
                ok: true,
                runId: active.runId,
                apiPort: runJson?.apiPortActual ?? 0,
                uiPort: runJson?.uiPortActual ?? 0,
                apiBaseUrl: runJson?.apiBaseUrl,
                uiUrl: runJson?.uiUrl,
                dataDir: runJson?.dataDir ?? '',
              };
            }
          } catch (_) {
            // Recovery failed, fall through to original error
          }
          if (!json) throw err;
        } else {
          throw err;
        }
      }

      const parsed = DevRunnerStartJsonSchema.parse(json);

      // Handle ownership conflict from admission (271)
      if (!parsed.ok && parsed.error?.code === 'OWNER_CONFLICT') {
        maybeAppendNdjson(mainRepoRoot, {
          event: 'tool_start_result', tool: 'justsearch.dev.start',
          ok: false, conflict: true,
        });
        return toToolResult(parsed);
      }

      // After successful start, wait for readiness if requested
      if (parsed.ok) {
        const apiBaseUrl = parsed.apiBaseUrl || recoveredApiBaseUrl;
        if (apiBaseUrl && waitLevel) {
          try {
            const readinessResult = await waitReady({ apiBaseUrl, level: waitLevel, timeoutMs: waitTimeoutMs });
            parsed.readiness = { [waitLevel]: readinessResult.ok };
            if (!readinessResult.ok) {
              parsed.waitReadyTimeout = true;
            }
          } catch (waitErr) {
            parsed.readiness = { [waitLevel]: false };
            parsed.waitReadyTimeout = true;
          }
        }
      }

      maybeAppendNdjson(mainRepoRoot, { event: 'tool_start_result', tool: 'justsearch.dev.start', ok: !!parsed?.ok });
      return toToolResult(parsed);
    },
  );

  mcpServer.registerTool(
    'justsearch.dev.tail_log',
    {
      description: 'Read recent log output to diagnose startup failures or runtime errors. Kinds: backend/frontend stdout/stderr, stop_report.',
      inputSchema: TailLogInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = TailLogInputSchema.parse(rawArgs);
      if (!input.runId) input.runId = await resolveRunId(mainRepoRoot, undefined);
      if (!input.runId) return toToolResult({ ok: false, error: { code: 'NO_ACTIVE_RUN', message: 'No active run found. Recovery: call start to launch the dev stack, then retry.' } });
      const maxBytes = input.maxBytes ?? 65_536;
      const maxLines = input.maxLines ?? 200;

      const { relPosix } = resolveAllowedRunFile({ repoRoot: mainRepoRoot, runId: input.runId, kind: input.kind });
      const tailRes = await tailTextFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix, maxBytes, maxLines });

      if (input.grepPattern && tailRes.ok) {
        try {
          const re = new RegExp(input.grepPattern);
          const lines = tailRes.text.split('\n').filter(line => re.test(line));
          tailRes.text = lines.join('\n');
          tailRes.bytesRead = Buffer.byteLength(tailRes.text);
        } catch {
          tailRes.text = `[WARNING: invalid grepPattern "${input.grepPattern}" — showing unfiltered output]\n${tailRes.text}`;
          tailRes.bytesRead = Buffer.byteLength(tailRes.text);
        }
      }

      const out = TailLogOutputSchema.parse(
        tailRes.ok
          ? {
              ok: true,
              runId: input.runId,
              kind: input.kind,
              path: relPosix,
              truncated: !!tailRes.truncated,
              bytesRead: tailRes.bytesRead,
              text: tailRes.text,
            }
          : {
              ok: false,
              runId: input.runId,
              kind: input.kind,
              error: ToolErrorSchema.parse({ code: 'NOT_FOUND', message: `log file not found: ${relPosix}` }),
            },
      );
      return toToolResult(out);
    },
  );

  mcpServer.registerTool(
    'justsearch.dev.fetch_api_json',
    {
      description: 'Fetch a diagnostic JSON endpoint (status, health, debug_state, effective_config, etc.) from the running backend.',
      inputSchema: FetchApiJsonInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = FetchApiJsonInputSchema.parse(rawArgs);
      const timeoutMs = input.timeoutMs ?? 15_000;
      const maxBytes = input.maxBytes ?? 2_000_000;

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        const out = FetchApiJsonOutputSchema.parse({
          ok: false,
          runId: resolved.runId ?? input.runId ?? null,
          endpoint: input.endpoint,
          statusCode: null,
          error: ToolErrorSchema.parse(resolved.error),
        });
        return toToolResult(out);
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      const relPath = FETCH_API_ENDPOINT_MAP[input.endpoint];
      const url = new URL(relPath, base).toString();
      const res = await httpGetTextLimited(url, { timeoutMs, maxBytes });

      // Tempdoc 844 B4b: a truncated body cannot be parsed — do not try, and do not let the caller
      // discover that from a bare textTail.
      const truncated = res.truncated === true;

      let parsedJson;
      let bodyOk = false;
      if (res.ok && !truncated) {
        try {
          parsedJson = JSON.parse(res.text || '');
          bodyOk = true;
        } catch (_) {
          bodyOk = false;
        }
      }

      // Tempdoc 844 B4a: projection, with an available-keys hint on a miss instead of the body.
      let projected = bodyOk ? parsedJson : undefined;
      let jsonPathError = null;
      let jsonPathAvailable = null;
      if (input.jsonPath && bodyOk) {
        const proj = projectJsonPath(parsedJson, input.jsonPath);
        if (proj.ok) {
          projected = proj.value;
        } else {
          bodyOk = false;
          jsonPathError = proj.error;
          jsonPathAvailable = proj.available ?? null;
        }
      }

      const isOk = res.ok && !truncated && res.statusCode === 200 && bodyOk;
      const effectiveError = truncated
        ? ToolErrorSchema.parse(truncationNotice({ bytesRead: res.bytesRead, maxBytes }))
        : res.error
          ? ToolErrorSchema.parse({ message: res.error.message })
          : jsonPathError
            ? ToolErrorSchema.parse(jsonPathError)
            : null;

      const out = FetchApiJsonOutputSchema.parse({
        ok: isOk,
        runId: effectiveRunId,
        endpoint: input.endpoint,
        url,
        statusCode: res.statusCode,
        ...(bodyOk ? { json: projected } : {}),
        // textTail only when the BODY itself is the problem (unparseable, or truncated-by-budget).
        // A jsonPath miss deliberately withholds it — returning the whole body for a typo is the
        // expensive-failure default this fixes.
        ...(!bodyOk && !jsonPathError && res.textTail ? { textTail: res.textTail } : {}),
        ...(truncated ? { truncated: true, bytesRead: res.bytesRead, maxBytesLimit: maxBytes } : {}),
        ...(jsonPathAvailable ? { jsonPathAvailable } : {}),
        ...(!isOk && effectiveError ? { error: effectiveError } : {}),
      });
      if (input.outputMode !== 'full') {
        delete out.url;
        delete out.statusCode;
        delete out.endpoint;
      }
      return toToolResult(await withStaleness(out, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId: input.sessionId || resolveAgentSessionIdForMcp(repoRoot) }));
    },
  );

  // --- Generic API Call tool ---

  mcpServer.registerTool(
    'justsearch.dev.api_call',
    {
      description: 'Call any allowlisted backend API endpoint (GET/POST/DELETE). Use when fetch_api_json does not cover the endpoint you need.',
      inputSchema: ApiCallInputSchema,
      annotations: { destructiveHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = ApiCallInputSchema.parse(rawArgs);
      const method = input.method ?? 'GET';
      const timeoutMs = input.timeoutMs ?? 15_000;
      const maxBytes = input.maxBytes ?? 2_000_000;

      // Validate path against allowlist
      // An entry matches by exact path, or by `pattern` when the route carries a path parameter.
      const entry = API_CALL_ALLOWLIST.find(
        (e) => e.path === input.path || (e.pattern instanceof RegExp && e.pattern.test(input.path)),
      );
      if (!entry) {
        return toToolResult({
          ok: false,
          method,
          path: input.path,
          statusCode: null,
          error: {
            message: `Path not allowlisted: ${input.path}. Allowed: ${API_CALL_ALLOWLIST.map((e) => e.path).join(', ')}`,
          },
        });
      }
      if (!entry.methods.includes(method)) {
        return toToolResult({
          ok: false,
          method,
          path: input.path,
          statusCode: null,
          error: {
            message: `Method ${method} not allowed for ${input.path}. Allowed: ${entry.methods.join(', ')}`,
          },
        });
      }

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        return toToolResult({
          ok: false,
          method,
          path: input.path,
          statusCode: null,
          error: resolved.error,
        });
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      const url = new URL(input.path, base).toString();

      // observations.md L158 fix: normalize body — when Claude passes body: "{}" (a string
      // literal), JSON.stringify produces double-encoded "\"{}\""  which Jackson rejects.
      // Parse string bodies to objects so httpPostJsonLimited serializes correctly.
      const normalizedBody = typeof input.body === 'string'
        ? JSON.parse(input.body)
        : (input.body ?? {});

      let res;
      if (method === 'POST') {
        res = await httpPostJsonLimited(url, normalizedBody, { timeoutMs, maxBytes });
      } else if (method === 'DELETE' && input.body != null) {
        // DELETE with body — route through POST helper with method override
        res = await httpPostJsonLimited(url, normalizedBody, { timeoutMs, maxBytes, method: 'DELETE' });
      } else {
        // GET or bodyless DELETE
        res = await httpGetTextLimited(url, { timeoutMs, maxBytes, method });
      }

      const truncated = res.truncated === true;

      let parsedJson;
      let jsonOk = false;
      // 204 No Content is a valid success with no body
      const isNoContent = res.statusCode === 204;
      if (res.ok && res.text && !truncated) {
        try {
          parsedJson = JSON.parse(res.text);
          jsonOk = true;
        } catch (_) {
          jsonOk = false;
        }
      }

      // Tempdoc 844 B4c: api_call gained jsonPath, sharing fetch_api_json's implementation — an
      // agent tried it here and got `unrecognized_keys`, on responses averaging 7.2 KB.
      let projected = jsonOk ? parsedJson : undefined;
      let jsonPathError = null;
      let jsonPathAvailable = null;
      if (input.jsonPath && jsonOk) {
        const proj = projectJsonPath(parsedJson, input.jsonPath);
        if (proj.ok) {
          projected = proj.value;
        } else {
          jsonOk = false;
          jsonPathError = proj.error;
          jsonPathAvailable = proj.available ?? null;
        }
      }

      const callError = truncated
        ? truncationNotice({ bytesRead: res.bytesRead, maxBytes })
        : res.error
          ? { message: res.error.message }
          : jsonPathError
            ? jsonPathError
            : null;

      const out = {
        ok: res.ok && !truncated && (res.statusCode >= 200 && res.statusCode < 300) && (jsonOk || isNoContent),
        runId: effectiveRunId,
        method,
        path: input.path,
        url,
        statusCode: res.statusCode,
        ...(jsonOk ? { json: projected } : {}),
        // Withheld on a jsonPath miss (844 B4a) — the available-keys hint replaces the body.
        ...(!jsonOk && !isNoContent && !jsonPathError && res.textTail ? { textTail: res.textTail } : {}),
        ...(truncated ? { truncated: true, bytesRead: res.bytesRead, maxBytesLimit: maxBytes } : {}),
        ...(jsonPathAvailable ? { jsonPathAvailable } : {}),
        ...(callError ? { error: callError } : {}),
      };
      if (input.outputMode !== 'full') {
        delete out.url;
        delete out.statusCode;
        delete out.path;
        delete out.method;
      }
      return toToolResult(await withStaleness(out, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId: input.sessionId || resolveAgentSessionIdForMcp(repoRoot) }));
    },
  );

  function slimSearchResult(r) {
    const f = r.fields || {};
    return {
      id: r.id,
      score: r.score,
      filename: f.filename,
      path: f.path,
      file_kind: f.file_kind,
      size_bytes: f.size_bytes,
      language: f.language,
      content_preview: (f.content_preview || '').slice(0, 200),
      matchedFields: r.matchedFields,
    };
  }

  mcpServer.registerTool(
    'justsearch.dev.search_query',
    {
      description: 'Run a search query against the knowledge index to test search quality or verify indexed content.',
      inputSchema: SearchQueryInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = SearchQueryInputSchema.parse(rawArgs);
      const timeoutMs = input.timeoutMs ?? 15_000;
      const maxBytes = input.maxBytes ?? 2_000_000;

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        const out = SearchQueryOutputSchema.parse({
          ok: false,
          runId: resolved.runId ?? input.runId ?? 'unknown',
          query: input.query,
          statusCode: null,
          error: ToolErrorSchema.parse(resolved.error),
        });
        return toToolResult(out);
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      const url = new URL('/api/knowledge/search', base).toString();
      const body = { query: input.query };
      if (input.cursor != null) body.cursor = input.cursor;
      if (input.limit != null) body.limit = input.limit;
      if (input.mode != null) body.mode = input.mode;
      if (input.querySyntax != null) body.querySyntax = input.querySyntax;

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_start',
        tool: 'justsearch.dev.search_query',
        runId: effectiveRunId,
        query: input.query,
      });

      const res = await httpPostJsonLimited(url, body, { timeoutMs, maxBytes });

      if (!res.ok || res.statusCode !== 200) {
        const out = SearchQueryOutputSchema.parse({
          ok: false,
          runId: effectiveRunId,
          query: input.query,
          url,
          statusCode: res.statusCode,
          error: ToolErrorSchema.parse({ message: res.error?.message || `HTTP ${res.statusCode}` }),
        });
        return toToolResult(out);
      }

      // Tempdoc 844 B4b: a maxBytes truncation must not surface as "Invalid JSON response" — that
      // reads as a backend fault when it is a budget the caller set.
      if (res.truncated === true) {
        return toToolResult(SearchQueryOutputSchema.parse({
          ok: false,
          runId: effectiveRunId,
          query: input.query,
          url,
          statusCode: res.statusCode,
          truncated: true,
          bytesRead: res.bytesRead,
          maxBytesLimit: maxBytes,
          error: ToolErrorSchema.parse(truncationNotice({ bytesRead: res.bytesRead, maxBytes })),
        }));
      }

      let parsed;
      try {
        parsed = JSON.parse(res.text || '');
      } catch {
        const out = SearchQueryOutputSchema.parse({
          ok: false,
          runId: effectiveRunId,
          query: input.query,
          url,
          statusCode: res.statusCode,
          error: ToolErrorSchema.parse({ message: 'Invalid JSON response' }),
        });
        return toToolResult(out);
      }

      const out = SearchQueryOutputSchema.parse({
        ok: true,
        runId: effectiveRunId,
        query: input.query,
        url,
        statusCode: res.statusCode,
        totalHits: parsed.totalHits ?? 0,
        tookMs: parsed.tookMs ?? 0,
        results: (parsed.results ?? []).map((r) => (input.verbose ? r : slimSearchResult(r))),
        ...(parsed.nextCursor ? { nextCursor: parsed.nextCursor } : {}),
        ...(parsed.facets ? { facets: parsed.facets } : {}),
        // Tempdoc 549 U4 (Slice 6/6b): read correction from the canonical introspection trace
        // (the flat correctionApplied field was removed from the response).
        ...(parsed.introspection?.correction?.applied ? { correctionApplied: true } : {}),
      });

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_search_query_result',
        tool: 'justsearch.dev.search_query',
        runId: effectiveRunId,
        ok: true,
        totalHits: out.totalHits,
      });
      if (input.summaryOnly) {
        delete out.results;
        delete out.facets;
        delete out.nextCursor;
        delete out.correctionApplied;
        delete out.url;
        delete out.statusCode;
        delete out.query;
      } else if (input.outputMode !== 'full') {
        delete out.url;
        delete out.statusCode;
        delete out.query;
      }
      return toToolResult(await withStaleness(out, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId: input.sessionId || resolveAgentSessionIdForMcp(repoRoot) }));
    },
  );

  mcpServer.registerTool(
    'justsearch.dev.ingest',
    {
      description: 'Index documents into the knowledge base. Paths must be repo-relative. Requires a running dev stack.',
      inputSchema: IngestInputSchema,
      annotations: { destructiveHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = IngestInputSchema.parse(rawArgs);
      const timeoutMs = input.timeoutMs ?? 30_000;
      const maxBytes = input.maxBytes ?? 2_000_000;

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        const out = IngestOutputSchema.parse({
          ok: false,
          runId: resolved.runId ?? input.runId ?? 'unknown',
          statusCode: null,
          error: ToolErrorSchema.parse(resolved.error),
        });
        return toToolResult(out);
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      // Resolve all paths to absolute under repo root for safety.
      const absPaths = input.paths.map((p) => {
        const rel = resolveUnderRepo(repoRoot, p, 'ingest path');
        return path.resolve(repoRoot, rel);
      });

      const url = new URL('/api/knowledge/ingest', base).toString();
      const body = { paths: absPaths };

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_start',
        tool: 'justsearch.dev.ingest',
        runId: effectiveRunId,
        pathCount: absPaths.length,
      });

      const res = await httpPostJsonLimited(url, body, { timeoutMs, maxBytes });

      if (!res.ok || res.statusCode !== 200) {
        const out = IngestOutputSchema.parse({
          ok: false,
          runId: effectiveRunId,
          url,
          statusCode: res.statusCode,
          error: ToolErrorSchema.parse({ message: res.error?.message || `HTTP ${res.statusCode}` }),
        });
        return toToolResult(out);
      }

      // Tempdoc 844 B4b: declare a maxBytes truncation as itself, not as a parse failure.
      if (res.truncated === true) {
        return toToolResult(IngestOutputSchema.parse({
          ok: false,
          runId: effectiveRunId,
          url,
          statusCode: res.statusCode,
          truncated: true,
          bytesRead: res.bytesRead,
          maxBytesLimit: maxBytes,
          error: ToolErrorSchema.parse(truncationNotice({ bytesRead: res.bytesRead, maxBytes })),
        }));
      }

      let parsed;
      try {
        parsed = JSON.parse(res.text || '');
      } catch {
        const out = IngestOutputSchema.parse({
          ok: false,
          runId: effectiveRunId,
          url,
          statusCode: res.statusCode,
          error: ToolErrorSchema.parse({ message: 'Invalid JSON response' }),
        });
        return toToolResult(out);
      }

      const out = IngestOutputSchema.parse({
        ok: true,
        runId: effectiveRunId,
        url,
        statusCode: res.statusCode,
        accepted: parsed.accepted ?? 0,
        ...(parsed.error ? { error: parsed.error } : {}),
      });

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_ingest_result',
        tool: 'justsearch.dev.ingest',
        runId: effectiveRunId,
        ok: true,
        accepted: out.accepted,
      });
      return toToolResult(await withStaleness(out, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId: input.sessionId || resolveAgentSessionIdForMcp(repoRoot) }));
    },
  );

  // ─── Preflight ─────────────────────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.preflight',
    {
      description: 'Check if the dev stack can be started: worker dist built, no active/stale runs, models present, no inference orphans. Pass the SAME distFrom you will pass to start — the dist checks then run against the tree start will launch from (a bare worktree name resolves against .claude/worktrees). The checked root is reported as `distCheckedRoot`.',
      inputSchema: PreflightInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = PreflightInputSchema.parse(rawArgs ?? {});
      const details = {};

      // Tempdoc 844 B1: check the tree `start` will actually use. Same resolver as start, so the
      // two cannot drift; without distFrom this is the invoking checkout exactly as before.
      const distRoot = await resolveDistRoot({
        distFrom: input.distFrom,
        mainRepoRoot,
        fallbackRepoRoot: repoRoot,
        fallbackDevRunnerPath: devRunnerPath,
      });
      if (!distRoot.ok) {
        // The checks were NOT run — say so rather than reporting them as failed.
        return toToolResult({ ok: false, error: distRoot.error, checksRun: false });
      }
      const distCheckRoot = distRoot.repoRoot;

      // 1a. Worker distribution exists
      let workerDist = false;
      const workerBin = path.join(distCheckRoot, 'modules', 'indexer-worker', 'build', 'install', 'indexer-worker', 'bin',
        process.platform === 'win32' ? 'indexer-worker.bat' : 'indexer-worker');
      try {
        await fsp.lstat(workerBin);
        workerDist = true;
        details.workerDist = `OK (${workerBin})`;
      } catch {
        details.workerDist = `Missing: ${workerBin}. Run: ./gradlew.bat assemble`;
      }

      // 1b. Head (UI) distribution exists — the dev-runner spawns from installDist, not gradlew
      let headDist = false;
      const headBin = path.join(distCheckRoot, 'modules', 'ui', 'build', 'install', 'ui', 'bin',
        process.platform === 'win32' ? 'ui.bat' : 'ui');
      try {
        await fsp.lstat(headBin);
        headDist = true;
        details.headDist = `OK (${headBin})`;
      } catch {
        details.headDist = `Missing: ${headBin}. Run: ./gradlew.bat :modules:ui:installDist`
          + ' (or, for a fresh worktree: node scripts/dev/prepare-worktree.cjs)';
      }

      // 2. No stale or active run
      let noStaleRun = true;
      try {
        const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
        if (active?.runId) {
          const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: active.runId });
          const pids = runJson?.pids || {};
          const anyAlive = Object.values(pids).some((pid) => {
            if (typeof pid !== 'number' || pid <= 0) return false;
            try { process.kill(pid, 0); return true; } catch { return false; }
          });
          if (anyAlive) {
            noStaleRun = false;
            details.noStaleRun = `Active run ${active.runId} has live processes. Stop it first or use the existing run.`;
          } else if (Object.keys(pids).length > 0) {
            noStaleRun = false;
            details.noStaleRun = `Stale run ${active.runId}: all PIDs dead but active.json remains. Use justsearch.dev.stop to clean up.`;
          } else {
            details.noStaleRun = 'OK (no active run)';
          }
        } else {
          details.noStaleRun = 'OK (no active run)';
        }
      } catch {
        details.noStaleRun = 'OK (no active.json)';
      }

      // 3. Models directory non-empty
      let modelsDir = false;
      try {
        const entries = await fsp.readdir(path.join(repoRoot, 'models'));
        modelsDir = entries.length > 0;
        details.modelsDir = modelsDir ? `OK (${entries.length} entries)` : 'Empty: models/ directory has no files';
      } catch {
        details.modelsDir = 'Missing: models/ directory not found';
      }

      // 4. No inference orphan (llama-server on default port)
      let noInferenceOrphan = true;
      try {
        const sc = await httpGetStatusCode(`http://127.0.0.1:${INFERENCE_PORT}/health`, 2000);
        if (sc === 200) {
          noInferenceOrphan = false;
          details.noInferenceOrphan = `Orphaned inference server on port ${INFERENCE_PORT}. Kill it or use justsearch.dev.stop.`;
        } else {
          details.noInferenceOrphan = 'OK';
        }
      } catch {
        details.noInferenceOrphan = 'OK';
      }

      // 5. Shared cuda12 GPU llama-server resolvable (tempdoc 656). REPORT-ONLY: the stack starts
      // fine without it (inference fails closed), so this does NOT gate `ready`. GPU-only by design:
      // there is deliberately no CPU baseline in dev (a CPU 9B fallback DOSes concurrent worktrees).
      let llamaVariantResolvable = true;
      try {
        const exe = process.platform === 'win32' ? 'llama-server.exe' : 'llama-server';
        const cuda12 = ['native-bin', 'llama-server', 'variants', 'cuda12', exe];
        const mainRepoRoot = resolveMainRepoRoot(repoRoot);
        const worktreeCuda12 = path.join(repoRoot, 'modules', 'ui', ...cuda12);
        const sharedCuda12 = path.join(mainRepoRoot, 'modules', 'ui', ...cuda12);
        let where = null;
        try { await fsp.lstat(worktreeCuda12); where = 'worktree'; } catch { /* not present */ }
        if (!where) { try { await fsp.lstat(sharedCuda12); where = 'shared main-checkout'; } catch { /* not present */ } }
        if (where) {
          details.llamaVariantResolvable = `OK (cuda12 GPU runtime resolvable — ${where})`;
        } else {
          llamaVariantResolvable = false;
          details.llamaVariantResolvable =
            'No cuda12 GPU runtime resolvable. Provision the shared runtime ONCE at the main checkout: '
            + '`./gradlew :modules:ui:stageLlamaCudaVariant` (~600 MB), then the dev-runner auto-populates '
            + 'the shared native-bin and every worktree references it. Dev is GPU-only (no CPU baseline); '
            + 'until then inference is unavailable (fails closed) but search works.';
        }
      } catch {
        details.llamaVariantResolvable = 'OK (check skipped)';
        llamaVariantResolvable = true;
      }

      const ready = workerDist && headDist && noStaleRun && modelsDir && noInferenceOrphan;
      return toToolResult(PreflightOutputSchema.parse({
        ready,
        checks: { workerDist, headDist, noStaleRun, modelsDir, noInferenceOrphan, llamaVariantResolvable },
        // Tempdoc 844 B1: self-describing — which tree the dist checks looked at, and how it was
        // resolved. `distCheckedRoot` is what `start` will launch from for the same distFrom;
        // the remaining checks (models, stale run, inference orphan) are machine/shared-state
        // scoped and are unaffected by distFrom.
        distCheckedRoot: distCheckRoot,
        distFrom: distRoot.distFrom,
        distFromResolvedVia: distRoot.resolvedVia,
        details,
      }));
    },
  );

  // ─── Quick Health ──────────────────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.quick_health',
    {
      description: 'Fast orientation — call after compaction or at session start. Returns run state and optional HTTP readiness probes; the default detail:"summary" spawns no subprocess. `foreignRuns` lists JustSearch runs this dev-runner did NOT start — `[]` means "probed, found none", `null` means "did not probe" (probe:false), so a free-looking verdict is never a claim about the whole machine. Each entry says how it is known: `source:"registered"` = its producer (e.g. `jseval`, on 33221) declared it, so it carries identity (`producer`, `repoRoot`, `pid`, `gpuBound`) and a verified `state` — `live` (port answered), `unreachable` (port silent, pid alive), `stale` (port silent, pid gone: a leaked record, nothing is running, remove `recordFile`), `unreadable` (record unparseable). `source:"observed"` = a port answered with nothing declaring it; all that is known is that something is listening. detail:"full" additionally runs the dev-runner status subprocess and returns its process/port/readiness payload under `detail` (this replaced the retired justsearch.dev.status tool).',
      inputSchema: QuickHealthInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = QuickHealthInputSchema.parse(rawArgs);
      const probe = input.probe !== false;
      const wantDetail = input.detail === 'full';

      // Read filesystem state
      let runId = null;
      let apiPort = null;
      let uiPort = null;
      let apiBaseUrl = null;
      let pidsAlive = false;
      let ownership = null;
      try {
        const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
        if (active?.runId) {
          runId = active.runId;
          const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId });
          apiPort = runJson?.apiPortActual ?? null;
          uiPort = runJson?.uiPortActual ?? null;
          apiBaseUrl = runJson?.apiBaseUrl ?? null;
          const pids = runJson?.pids || {};
          pidsAlive = Object.values(pids).some((pid) => {
            if (typeof pid !== 'number' || pid <= 0) return false;
            try { process.kill(pid, 0); return true; } catch { return false; }
          });
          // Tempdoc 606: single ownership-verdict projection (replaces the inline
          // 271 block + 542 op-lease overlay). Surfaces the prescriptive verdict +
          // recommendedAction so the agent is told what to do, not just shown raw fields.
          const callerSessionId = input.sessionId || resolveAgentSessionIdForMcp(repoRoot);
          const proj = await buildOwnershipProjection({ mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId, takeover: 'deny', active, runJson });
          ownership = proj.ownership;
        }
      } catch {
        // No active run
      }

      let httpReady = null;
      let workerReady = null;
      let inferenceOrphan = undefined;

      if (probe && apiBaseUrl) {
        try {
          const base = ensureLoopbackUrl(apiBaseUrl, 'apiBaseUrl');
          const statusCode = await httpGetStatusCode(new URL('/api/status', base).toString(), 2000);
          httpReady = statusCode === 200;
          if (httpReady) {
            const healthCode = await httpGetStatusCode(new URL('/api/health', base).toString(), 2000);
            workerReady = healthCode === 200;
          }
        } catch {
          httpReady = false;
        }
      }

      // Check for inference orphan — only when no active run or backend is dead
      if (probe && (!runId || httpReady === false)) {
        try {
          const sc = await httpGetStatusCode(`http://127.0.0.1:${INFERENCE_PORT}/health`, 2000);
          if (sc === 200) inferenceOrphan = true;
        } catch { /* no orphan */ }
      }

      // Tempdoc 842 §2.7: aiActive was hard-coded null — populate it for a running, reachable
      // stack from the AI runtime status. active ⇒ true/false; unreachable (probe off, backend
      // down, or the status call itself fails) ⇒ stays null, same honesty contract as httpReady.
      let aiActive = null;
      let model;
      if (probe && httpReady && apiBaseUrl) {
        try {
          const base = ensureLoopbackUrl(apiBaseUrl, 'apiBaseUrl');
          const statusUrl = new URL('/api/ai/runtime/status', base).toString();
          const res = await httpGetTextLimited(statusUrl, { timeoutMs: 2000, maxBytes: 100_000 });
          if (res.ok && res.text) {
            const st = JSON.parse(res.text);
            // Tempdoc 842 review D3: activation-completed alone is a false negative for engines
            // brought up by AI autostart (the activation state machine never ran). Realized
            // identity (active.modelPath) is projected only while the engine is online, so its
            // presence is an equally authoritative online signal.
            aiActive = !!(
              (st?.activation?.state === 'completed' && st?.active?.activeVariantId) ||
              st?.active?.modelPath != null
            );
            const chatProfile = st?.active?.chatProfile ?? st?.chatProfile;
            const modelPath = st?.active?.modelPath ?? st?.active?.llmModelPath ?? st?.modelPath;
            if (chatProfile != null || modelPath != null) {
              model = {
                ...(chatProfile != null ? { chatProfile } : {}),
                ...(modelPath != null ? { modelPath } : {}),
              };
            }
          }
          // res.ok === false (unreachable/non-200/parse issue never reached here) → aiActive stays null
        } catch {
          // unreachable → aiActive stays null
        }
      }

      // Tempdoc 844 B3 / §6.1: report backends this dev-runner did NOT start. quick_health read only
      // active.json, so a jseval/runHeadlessEval backend was invisible and its absence read as
      // "nothing is running" — that already contaminated a measurement round. Owned vs
      // observed-but-unowned stay separate, and `null` (did not look) stays distinct from `[]`
      // (looked, found nothing).
      // Tempdoc 844 D3: the register turns "something is on 33221" into "jseval's eval backend,
      // tree X, pid N" — and, for a record whose producer was killed before it could clean up,
      // into an explicit "stale record" rather than a phantom live backend.
      const foreignRuns = await probeForeignRuns({
        enabled: probe,
        hasActiveRun: runId !== null,
        ownedApiPort: apiPort,
        aiActive,
        registerDir: path.join(mainRepoRoot, ...FOREIGN_REGISTER_RELPOSIX.split('/')),
      });
      const foreignRunsNotice = foreignRuns && foreignRuns.length > 0
        ? `${foreignRuns.length} JustSearch run(s) on this machine were NOT started by this dev-runner `
          + `(${foreignRuns.map((f) => (f.source === 'registered'
            ? `${f.port ?? '?'}:${f.producer || 'registered'}/${f.state}`
            : `${f.port}:${f.kind}/${f.attribution}`)).join(', ')}). `
          + '`source:"registered"` entries are self-declared by their producer and carry identity (repoRoot, pid, '
          + 'gpuBound); `state:"stale"` means the record\'s port is silent and its pid is gone — nothing is running, '
          + 'delete `recordFile` if you own it. `source:"observed"` means only that a port answered. '
          + 'Ownership verdicts and `running` describe the dev-runner\'s own run only — ports, GPU and data dirs '
          + 'may be in use by a neighbour. A jseval eval backend loads the GPU-only encoder stack, so treat a live '
          + 'one as GPU contention even though `gpuBound` is "unverified". Check before starting a run or trusting '
          + 'a measurement.'
        : undefined;

      // Tempdoc 637 Layer A: one-look FRESHNESS verdict, aggregated at the dev-tooling layer — the
      // only vantage point that can see all four staleness sources. Each is a reasoned observable at
      // its OWNING layer, PROJECTED here (620 canonical-authority, never re-derived): build artifact
      // from the lease-stamp cross-check; index warmth projected from /api/status; FE binding is
      // FE-owned (self-declared via the 637 #1 banner); locks are build-owned (no cheap local probe).
      let freshness;
      if (runId) {
        const buildArtifact = ownership?.backendStale
          ? { state: 'STALE', reason: 'running an older build than source', remedy: 'gradlew :modules:ui:installDist then restart/reload' }
          : { state: 'FRESH' };
        let indexWarmth = { state: 'UNKNOWN' };
        if (probe && httpReady && apiBaseUrl) {
          try {
            const base = ensureLoopbackUrl(apiBaseUrl, 'apiBaseUrl');
            const res = await httpGetTextLimited(new URL('/api/status', base).toString(), { timeoutMs: 2000, maxBytes: 512_000 });
            const st = res?.ok && res.text ? JSON.parse(res.text) : null;
            const compat = st?.worker?.compatibility?.embeddingCompatState ?? st?.embedding?.compatState ?? null;
            const ready = st?.embedding?.ready ?? st?.embeddingReady ?? null;
            // embeddingCompatState is the authoritative warmth signal — check the BLOCKED/REBUILDING
            // states FIRST. `embedding.ready` is NOT reliable on its own: it is observed `true` even
            // when the index is BLOCKED_LEGACY/reindexRequired (verified live), so an OR on `ready`
            // would mis-report a warming index as FRESH. Only fall back to `ready` when compat is absent.
            if (compat === 'BLOCKED_LEGACY' || compat === 'BLOCKED_MISMATCH' || compat === 'REBUILDING') {
              indexWarmth = { state: 'WARMING', reason: `embeddings ${compat}`, remedy: 'wait for auto-reindex; mode:text (BM25) works during warming' };
            } else if (compat === 'COMPATIBLE' || (compat == null && ready === true)) {
              indexWarmth = { state: 'FRESH' };
            } else if (compat) {
              indexWarmth = { state: 'UNKNOWN', reason: `embeddingCompatState=${compat}` };
            }
          } catch { /* /api/status body unavailable — leave UNKNOWN */ }
        }
        freshness = {
          buildArtifact,
          indexWarmth,
          // FE↔backend binding is FE-owned (the dev tool can't see what URL a browser tab bound to);
          // the FE self-declares a dead binding via the 637 #1 'unreachable' verdict/banner.
          feBinding: { state: 'SELF_DECLARED', note: 'the FE shows a loud "Backend disconnected" banner if its binding is dead (637 #1)' },
          // Lockfile drift is build-owned; no cheap local probe exists (637 #4 / U5) — the pre-merge
          // CI gate is the sound catch.
          locks: { state: 'DEFERRED', note: 'run resolveAndLockAll locally before merge if build files changed; the pre-merge CI gate is the sound catch' },
        };
      }

      // Tempdoc 844 P1: the retired justsearch.dev.status tool, folded in as detail:"full". It is
      // the one part of quick_health that spawns a subprocess, so it stays opt-in. On failure the
      // field is present and ok:false with the reason — never silently omitted on an explicit ask.
      let detail;
      if (wantDetail) {
        try {
          const { exitCode, json } = await runCliJson({
            repoRoot,
            devRunnerPath,
            args: buildDevRunnerArgsStatus({ runId }),
            timeoutMs: 20_000,
            mode: 'oneshot',
          });
          // status returns ok:false for NO_ACTIVE_RUN; do not coerce to failure on exitCode alone.
          const parsed = DevRunnerStatusJsonSchema.parse(json);
          detail = StatusOutputSchema.parse({ ...parsed, exitCode });
          if (detail.ok && detail.runId) {
            try {
              const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: detail.runId });
              detail.apiBaseUrl = runJson?.apiBaseUrl;
              detail.uiUrl = runJson?.uiUrl;
              detail.dataDir = runJson?.dataDir;
              detail.logs = runJson?.logs;
              if (runJson?.owner) detail.owner = runJson.owner;
              if (runJson?.resourceClaims) detail.resourceClaims = runJson.resourceClaims;
            } catch (_) { /* run.json missing — the dev-runner projection still stands */ }
          }
        } catch (err) {
          detail = StatusOutputSchema.parse({
            ok: false,
            runId: runId ?? null,
            error: ToolErrorSchema.parse({
              code: 'DETAIL_UNAVAILABLE',
              message: `dev-runner status could not be read: ${err?.message || String(err)}`,
            }),
          });
        }
      }

      return toToolResult(QuickHealthOutputSchema.parse({
        running: runId !== null && (httpReady === true || (httpReady === null && pidsAlive)),
        runId,
        apiPort,
        uiPort,
        httpReady,
        workerReady,
        aiActive,
        foreignRuns,
        ...(foreignRunsNotice ? { foreignRunsNotice } : {}),
        ...(inferenceOrphan !== undefined ? { inferenceOrphan } : {}),
        ...(ownership ? { ownership } : {}),
        ...(freshness ? { freshness } : {}),
        ...(model ? { model } : {}),
        ...(detail ? { detail } : {}),
      }));
    },
  );

  /** Probe inference port for orphaned llama-server and kill it if found. */
  async function probeAndKillInferenceOrphan() {
    try {
      const sc = await httpGetStatusCode(`http://127.0.0.1:${INFERENCE_PORT}/health`, 2000);
      if (sc !== 200) return null;
      const { stdout } = await execFileP('powershell',
        ['-NoProfile', '-Command',
         `(Get-NetTCPConnection -LocalPort ${INFERENCE_PORT} -State Listen -ErrorAction SilentlyContinue).OwningProcess`],
        { timeout: 5000 });
      const pid = parseInt(stdout.trim(), 10);
      if (pid > 0) {
        await execFileP('taskkill', ['/PID', String(pid), '/F'], { timeout: 5000 });
        return { killed: true, pid };
      }
      return { killed: false, error: 'Could not determine PID' };
    } catch {
      return null; // no orphan or probe failed
    }
  }

  mcpServer.registerTool(
    'justsearch.dev.acquire_when_free',
    {
      description:
        'Tempdoc 606: wait until the shared dev stack becomes acquirable (the current owner releases, '
        + 'goes abandoned, or a critical op clears), then return HOW to take it — replacing the '
        + 'conflict→ask-user→manual-retry round-trip with one waited call. Polls the single ownership '
        + 'verdict. acquirable:true returns recommendedTakeover ("deny" = just start; "warn" = idle owner, '
        + 'self-authorize). acquirable:false on timeout (owner stayed active → ask the user or takeover:"force").',
      inputSchema: AcquireWhenFreeInputSchema,
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = AcquireWhenFreeInputSchema.parse(rawArgs);
      const timeoutMs = (input.timeoutSec ?? 120) * 1000;
      const pollMs = input.pollMs ?? 2000;
      const callerSessionId = input.sessionId || resolveAgentSessionIdForMcp(repoRoot);
      const deadline = Date.now() + timeoutMs;
      let last = null;
      for (;;) {
        let active = null;
        try { active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 }); } catch { /* none */ }
        const { ownership, decision } = await buildOwnershipProjection({ mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId, takeover: 'deny', active });
        const rt = recommendedTakeoverFor(decision);
        if (rt !== null) {
          return toToolResult(AcquireWhenFreeOutputSchema.parse({
            ok: true, acquirable: true,
            verdict: decision?.verdict ?? 'NO_OWNER',
            ...(decision?.grade ? { grade: decision.grade } : {}),
            recommendedTakeover: rt,
            recommendedAction: decision?.recommendedAction ?? 'Stack is free — start now.',
            waitedMs: timeoutMs - (deadline - Date.now()),
            ...(ownership ? { ownership } : {}),
          }));
        }
        last = { ownership, decision };
        if (Date.now() >= deadline) break;
        await delay(Math.min(pollMs, Math.max(0, deadline - Date.now())));
      }
      return toToolResult(AcquireWhenFreeOutputSchema.parse({
        ok: true, acquirable: false,
        verdict: last?.decision?.verdict ?? 'CONTENTION',
        ...(last?.decision?.grade ? { grade: last.decision.grade } : {}),
        recommendedAction: last?.decision?.recommendedAction
          ?? 'Owner still active after wait — ask the user, or use takeover:"force".',
        waitedMs: timeoutMs,
        ...(last?.ownership ? { ownership: last.ownership } : {}),
      }));
    },
  );

  mcpServer.registerTool(
    'justsearch.dev.stop',
    {
      description: 'Stop the running dev stack and optionally clean its data directory. Also detects and kills orphaned inference processes.',
      inputSchema: StopInputSchema,
      annotations: { destructiveHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = StopInputSchema.parse(rawArgs);
      const effectiveRunId = input.runId ?? await resolveRunId(mainRepoRoot, undefined);
      if (!effectiveRunId) {
        // No active run — but check for orphaned inference server before giving up
        const orphan = await probeAndKillInferenceOrphan();
        if (orphan?.killed) {
          return toToolResult({ ok: true, inferenceOrphanKilled: orphan.pid,
            message: `No active run, but killed orphaned inference server (PID ${orphan.pid}) on port ${INFERENCE_PORT}.` });
        }
        return toToolResult({ ok: false, error: { code: 'NO_ACTIVE_RUN', message: 'No active run to stop. Call quick_health to verify state.' } });
      }
      const clean = input.clean ?? 'none';

      // Always read the holder's session from active.json for the stop command.
      // The MCP caller's sessionId (from Claude Code) may differ from the session ID
      // that dev-runner.cjs recorded during start (which resolves via env var / telemetry
      // file fallbacks). Using the holder's ID ensures stop matches start.
      let effectiveSessionId = null;
      try {
        const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
        if (active?.holder?.agentSessionId) {
          effectiveSessionId = active.holder.agentSessionId;
        }
      } catch (_) { /* active.json missing or unreadable — proceed without */ }
      if (!effectiveSessionId) {
        effectiveSessionId = input.sessionId;
      }

      const args = buildDevRunnerArgsStop({ runId: effectiveRunId, force: !!input.force, sessionId: effectiveSessionId });
      maybeAppendNdjson(mainRepoRoot, { event: 'tool_start', tool: 'justsearch.dev.stop', runId: effectiveRunId });

      const { exitCode, json } = await runCliJson({
        repoRoot,
        devRunnerPath,
        args,
        timeoutMs: 45_000,
        mode: 'oneshot',
      });

      // Detect OWNER_CONFLICT from session-scoped stop gate
      if (json?.error?.code === 'OWNER_CONFLICT') {
        return toToolResult({
          ok: false,
          error: json.error,
          holder: json.error.holder ?? null,
          lease: json.error.lease ?? null,
          actionRequired: 'ask_user_to_transfer_or_force',
        });
      }

      const parsed = DevRunnerStopJsonSchema.parse(json);
      const out = coerceExitAwareOk(parsed, exitCode);

      // Merge cleanup if requested
      if (clean !== 'none') {
        try {
          const cleanArgs = buildDevRunnerArgsCleanup({ runId: effectiveRunId, clean, force: !!input.force });
          const cleanResult = await runCliJson({
            repoRoot,
            devRunnerPath,
            args: cleanArgs,
            timeoutMs: 60_000,
            mode: 'oneshot',
          });
          const cleanParsed = DevRunnerCleanupJsonSchema.parse(cleanResult.json);
          out.cleanup = coerceExitAwareOk(cleanParsed, cleanResult.exitCode);
        } catch (cleanErr) {
          out.cleanup = { ok: false, error: { message: cleanErr?.message || String(cleanErr) } };
        }
      }

      // Probe for orphaned inference server (C2 fix)
      const orphan = await probeAndKillInferenceOrphan();
      if (orphan?.killed) {
        out.inferenceOrphan = true;
        out.inferenceOrphanKilled = orphan.pid;
      } else if (orphan && !orphan.killed) {
        out.inferenceOrphan = true;
        out.inferenceOrphanError = orphan.error;
      }

      maybeAppendNdjson(mainRepoRoot, { event: 'tool_stop_result', tool: 'justsearch.dev.stop', runId: effectiveRunId, ok: out.ok, exitCode });
      return toToolResult(out);
    },
  );

  // ---------------------------------------------------------------------------
  // Tempdoc 842 §2.4/§2.7 D2: the activation flow. Pre-checks status, fires
  // POST /api/ai/runtime/activate, polls until a terminal activation state or timeoutMs elapses.
  // Returns a plain result object (not a schema-validated tool payload) so the caller shapes it
  // into its own output schema. (Tempdoc 844 P1: justsearch.dev.agent_chat, its second caller,
  // was retired — this is now reached only from justsearch.dev.ai_activate.)
  // ---------------------------------------------------------------------------
  async function activateAiRuntime({ base, variantId, chatProfile, timeoutMs, pollIntervalMs }) {
    const startMs = Date.now();
    const statusUrl = new URL('/api/ai/runtime/status', base).toString();

    // Check current state — might already be active. Tempdoc 842 review D3/N1: an engine
    // brought up by AI AUTOSTART never runs the activation state machine, so the old
    // activation-completed test was a false negative there and this pre-check re-activated a
    // healthy engine (GPU self-test + RESTART_ALWAYS) on every call. Engine-online is
    // activation-completed OR realized identity present (active.modelPath is projected only
    // while the engine is online — backend invariant). A caller explicitly requesting a
    // DIFFERENT profile than the running one still proceeds: that is a profile switch.
    const preCheck = await httpGetTextLimited(statusUrl, { timeoutMs: 10_000, maxBytes: 100_000 });
    if (preCheck.ok) {
      try {
        const pre = JSON.parse(preCheck.text);
        const engineOnline =
          (pre.activation?.state === 'completed' && pre.active?.activeVariantId) ||
          pre.active?.modelPath != null;
        const profileMatches = chatProfile == null || chatProfile === pre.active?.chatProfile;
        if (engineOnline && profileMatches) {
          return {
            ok: true,
            // Autostarted engines have no activeVariantId (activation never ran); fall back to
            // the requested variant so the non-nullable output schema holds.
            variantId: pre.active?.activeVariantId ?? variantId,
            chatProfile: pre.active?.chatProfile,
            activationState: pre.activation?.state ?? 'idle',
            phase: 'done',
            message: 'AI runtime already active.',
            durationMs: Date.now() - startMs,
            alreadyActive: true,
          };
        }
      } catch { /* proceed with activation */ }
    }

    // Fire activate
    const activateUrl = new URL('/api/ai/runtime/activate', base).toString();
    const activateBody = { variantId, ...(chatProfile ? { chatProfile } : {}) };
    const activateRes = await httpPostJsonLimited(activateUrl, activateBody, { timeoutMs: 15_000, maxBytes: 100_000 });
    if (!activateRes.ok || (activateRes.statusCode && activateRes.statusCode >= 400)) {
      const errMsg = activateRes.textTail || activateRes.error?.message || `HTTP ${activateRes.statusCode}`;
      return {
        ok: false,
        variantId,
        error: { message: errMsg },
        durationMs: Date.now() - startMs,
      };
    }

    // Poll until completed/failed/timeout
    const terminalStates = new Set(['completed', 'failed', 'idle']);
    let lastState = 'running';
    let lastPhase = '';
    let lastMessage = '';
    let lastChatProfile;

    while (Date.now() - startMs < timeoutMs) {
      await delay(pollIntervalMs);
      const poll = await httpGetTextLimited(statusUrl, { timeoutMs: 10_000, maxBytes: 100_000 });
      if (!poll.ok) continue;
      try {
        const status = JSON.parse(poll.text);
        lastState = status.activation?.state || lastState;
        lastPhase = status.activation?.phase || lastPhase;
        lastMessage = status.activation?.message || lastMessage;
        lastChatProfile = status.active?.chatProfile ?? lastChatProfile;
        if (terminalStates.has(lastState)) break;
      } catch { /* retry */ }
    }

    const elapsed = Date.now() - startMs;
    if (lastState === 'completed') {
      return {
        ok: true, variantId, chatProfile: lastChatProfile,
        activationState: lastState, phase: lastPhase, message: lastMessage, durationMs: elapsed,
      };
    }

    return {
      ok: false, variantId, chatProfile: lastChatProfile,
      activationState: lastState, phase: lastPhase, message: lastMessage,
      error: {
        message: lastState === 'failed' ? (lastMessage || 'Activation failed')
          : `Timeout after ${elapsed}ms (state: ${lastState}, phase: ${lastPhase})`,
      },
      durationMs: elapsed,
    };
  }

  // ─── AI Runtime Activate tool ──────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.ai_activate',
    {
      description: 'Start the AI runtime (llama-server) for a dev run. Polls until activation completes or fails.',
      inputSchema: AiActivateInputSchema,
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = AiActivateInputSchema.parse(rawArgs);
      const timeoutMs = input.timeoutMs ?? 60_000;
      const pollIntervalMs = input.pollIntervalMs ?? 2_000;
      const variantId = input.variantId ?? 'cuda12';

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        return toToolResult(AiActivateOutputSchema.parse({
          ok: false, runId: resolved.runId ?? input.runId ?? 'unknown', variantId,
          error: ToolErrorSchema.parse(resolved.error),
        }));
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      // Tempdoc 842: poll-until-done activation flow factored into activateAiRuntime — this
      // handler just shapes the result into AiActivateOutputSchema.
      // Tempdoc 842 review D1: an omitted chatProfile follows the STACK's spawn-time profile
      // (run.json) — without this, the habitual bare ai_activate call took
      // the backend's settings path and activated the standard 9B on a compact-default stack.
      // Explicit input wins; port-only resolution (no run record) keeps legacy behavior.
      const result = await activateAiRuntime({
        base, variantId,
        chatProfile: input.chatProfile ?? resolved.chatProfile ?? undefined,
        timeoutMs, pollIntervalMs,
      });

      if (result.ok) {
        return toToolResult(AiActivateOutputSchema.parse({
          ok: true, runId: effectiveRunId, variantId: result.variantId,
          activationState: result.activationState, phase: result.phase,
          message: result.message, durationMs: result.durationMs,
          ...(result.chatProfile != null ? { chatProfile: result.chatProfile } : {}),
        }));
      }

      return toToolResult(AiActivateOutputSchema.parse({
        ok: false, runId: effectiveRunId, variantId: result.variantId,
        activationState: result.activationState, phase: result.phase, message: result.message,
        ...(result.chatProfile != null ? { chatProfile: result.chatProfile } : {}),
        error: ToolErrorSchema.parse(result.error),
        durationMs: result.durationMs,
      }));
    },
  );

  // ─── Hot-reload (tempdoc 305) ────────────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.reload',
    {
      description: 'Hot-reload Worker code in the RUNNING stack: compile from the tree that stack '
        + 'was launched from, push method-body changes over JDWP into that stack\'s Worker (identity-'
        + 'checked), then signal a service reconstruction that keeps the ONNX encoders loaded. '
        + 'Ownership-gated. Structural changes (added/removed methods or fields) are reported, not '
        + 'applied — restart for those. Requires a stack started with hotReload (the default).',
      inputSchema: ReloadInputSchema,
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = ReloadInputSchema.parse(rawArgs);
      const module = input.module || 'worker-services';
      const skipCompile = input.skipCompile === true;
      const callerSessionId = input.sessionId || resolveAgentSessionIdForMcp(repoRoot);
      // Tempdoc 696: absolute >= 24 java (not bare PATH `java`, which may be JDK 8) for `--source 25`.
      const javaCmd = resolveJavaExe();
      const jdkEnv = { ...process.env, JAVA_HOME: resolveJdkHome() };

      const result = { ok: true, compileMs: null, hotSwapOutput: null, hotSwapOk: null, structuralChangeDetected: false, signalWritten: false };

      // ── Class-C middleware step 1 (§11.4): resolve the run ────────────────────────────────
      let active = null;
      let runJson = null;
      try {
        active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
        if (!active?.runId) throw new Error('no runId');
        runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: active.runId });
        if (!runJson) throw new Error('no run.json');
      } catch {
        return toToolResult({ ok: false, error: { code: 'NO_ACTIVE_RUN', message: 'No active dev stack. Call start first.' } });
      }

      // ── Class-C middleware step 2 (§11.4, R2): ownership ──────────────────────────────────
      const gate = await checkRunMutationOwnership({
        mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId,
        takeover: input.takeover ?? 'deny', active, runJson, tool: 'reload',
      });
      if (!gate.allowed) {
        maybeAppendNdjson(mainRepoRoot, { event: 'tool_reload', ok: false, conflict: true });
        return toToolResult(gate.refusal);
      }

      // ── R1: compile root comes from the RUN, never from this server's cwd ─────────────────
      const target = await resolveReloadTarget({ mainRepoRoot, runJson });
      if (!target.ok) return toToolResult({ ok: false, error: target.error, ...(gate.ownership ? { ownership: gate.ownership } : {}) });

      const { runRoot, dataDir, debugPort: recordedPort, identityClassesDir } = target;
      const debugPort = input.debugPort || recordedPort;
      const signalFile = dataDir ? path.join(dataDir, 'worker_signal.lock') : null;
      const classesDir = path.join(runRoot, 'modules', module, 'build', 'classes', 'java', 'main');
      // The pusher is the tool THIS server ships with, not whatever copy the run's tree happens to
      // hold: an older copy would silently skip the identity check it does not have. The bytecode
      // and the Gradle wrapper come from the run's tree; only the pusher binary is ours.
      const hotSwapScript = path.join(repoRoot, 'scripts', 'dev', 'HotSwapPush.java');
      const gradleCmd = path.join(runRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
      result.compiledFrom = runRoot;
      result.debugPort = debugPort;

      // 2. Compile — in the run's tree, so the classes pushed are that tree's classes.
      if (!skipCompile) {
        const compileStart = Date.now();
        try {
          const compileResult = await execFileP(
            gradleCmd,
            [`:modules:${module}:compileJava`],
            { cwd: runRoot, timeout: 60_000, windowsHide: true, shell: process.platform === 'win32', env: jdkEnv },
          );
          result.compileMs = Date.now() - compileStart;
          // Check for compilation errors in output
          if (compileResult.stderr && compileResult.stderr.includes('FAILED')) {
            return toToolResult({ ok: false, error: { code: 'COMPILE_FAILED', message: tail(compileResult.stderr, 2000) } });
          }
        } catch (err) {
          result.compileMs = Date.now() - compileStart;
          return toToolResult({ ok: false, error: { code: 'COMPILE_FAILED', message: tail(err.stderr || err.message, 2000) }, compileMs: result.compileMs });
        }
      }

      // 3. HotSwapPush — push bytecode into the run's Worker, after that VM proves its identity.
      const hsArgs = ['--add-modules', 'jdk.jdi', '--source', '25', hotSwapScript, String(debugPort), classesDir];
      // Tempdoc 844 F3: the run's own tree is passed as the expected repo root so the pusher can
      // tell "this VM belongs to another tree" from "this VM belongs to THIS tree but was launched
      // without the hot-reload classpath". Both refuse; only the explanation and remedy differ.
      if (identityClassesDir) hsArgs.push(identityClassesDir, runRoot);
      let hsExit = 0;
      let hsStdout = '';
      let hsStderr = '';
      try {
        const hsResult = await execFileP(javaCmd, hsArgs, { cwd: runRoot, timeout: 15_000, windowsHide: true, env: jdkEnv });
        hsStdout = hsResult.stdout || '';
        hsStderr = hsResult.stderr || '';
      } catch (err) {
        hsStdout = err.stdout || '';
        hsStderr = err.stderr || String(err.message || '');
        hsExit = typeof err.code === 'number' ? err.code : -1;
      }
      const outcome = classifyHotSwapOutcome({
        exitCode: hsExit, stdout: hsStdout, stderr: hsStderr, identityRequired: !!identityClassesDir,
      });
      result.hotSwapOutput = tail(`${hsStdout}\n${hsStderr}`.trim(), 2000).trim();
      result.hotSwapOk = outcome.hotSwapOk;
      result.hotSwapOutcome = outcome.outcome;
      result.identityVerified = identityClassesDir ? outcome.identityVerified : null;
      result.classesChanged = outcome.classesChanged;
      result.classesRedefined = outcome.classesRedefined;
      result.classesNotLoaded = outcome.classesNotLoaded;
      result.structuralChangeDetected = outcome.structuralChangeDetected;
      if (outcome.structuralChangeDetected) {
        result.restartRequired = 'Structural change (added/removed methods or fields) — standard HotSwap cannot apply it. Restart the dev stack.';
      }

      // 4. 371: If hot-swap succeeded, propagate the current build stamp to the Worker
      //    so it reports the correct stamp after reload (avoids false-positive staleness warnings).
      //    On structural-change failure, skip — the Worker is genuinely stale.
      //    MUST happen BEFORE the MMF signal: the Worker reads this file during performReload(),
      //    which starts as soon as the sentinel detects the signal byte.
      //    Tempdoc 844 §5.6 #2: the stamp is read from the RUN's tree, not the caller's — copying
      //    the caller's stamp into a peer's data dir is what defeated 371's stale-JVM detection.
      if (result.hotSwapOk && dataDir) {
        try {
          const stampPath = path.join(runRoot, 'modules', 'indexer-worker', 'build', 'install', 'indexer-worker', 'build-stamp.txt');
          const stamp = (await fsp.readFile(stampPath, 'utf8')).trim();
          if (stamp) {
            await fsp.writeFile(path.join(dataDir, 'reload-build-stamp.txt'), stamp, 'utf8');
          }
        } catch {
          // Best-effort — missing stamp file is not fatal.
        }
      }

      // 5. Write reload signal to MMF (triggers Worker's DevReloadManager).
      //    Tempdoc 844 §5.6 #3 / R5: this used to be gated only on `signalFile` being non-null,
      //    with a comment saying reconstruction should happen anyway — so a FAILED push still
      //    quiesced and reconstructed the Worker's services. Tearing services down is not a
      //    consolation prize for a push that did not land, and on a peer's stack it was an
      //    unauthorized teardown. It now happens only when new bytecode actually went in, and the
      //    skip is stated rather than silent.
      if (result.hotSwapOk && signalFile) {
        try {
          const fh = await fsp.open(signalFile, 'r+');
          try {
            const buf = Buffer.from([1]);
            await fh.write(buf, 0, 1, 29); // OFFSET_RELOAD_SIGNAL = 29
            result.signalWritten = true;
          } finally {
            await fh.close();
          }
        } catch (err) {
          result.signalError = `Failed to write signal: ${err.message}`;
        }
      } else if (!result.hotSwapOk) {
        result.signalSkippedReason = 'No new bytecode was pushed, so services were NOT reconstructed '
          + '— the running stack is unchanged.';
      } else if (!signalFile) {
        result.signalSkippedReason = 'The run record has no dataDir, so the reload signal file could '
          + 'not be located; bytecode was pushed but services were NOT reconstructed.';
      }

      if (outcome.error) {
        result.ok = false;
        result.error = outcome.error;
      } else if (outcome.noOp) {
        result.noOp = true;
        result.note = 'No class file changed since the last push — nothing was pushed and no service '
          + 'reconstruction was signalled.';
      }

      maybeAppendNdjson(mainRepoRoot, { event: 'tool_reload', ok: result.ok, compileMs: result.compileMs, hotSwapOk: result.hotSwapOk, outcome: result.hotSwapOutcome });
      // Class-C middleware step 3 (§11.4, R2): declare staleness on the result itself.
      return toToolResult(await withStaleness(result, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId }));
    },
  );

  const transport = new StdioServerTransport();
  await mcpServer.connect(transport);

  logInfo('server_started', `repoRoot=${repoRoot}`);
  maybeAppendNdjson(mainRepoRoot, { event: 'server_started', repoRoot });

  // Keep process alive; stdio transport will keep listeners open.
}

process.on('uncaughtException', (err) => {
  logError('uncaughtException', err?.stack || String(err));
});
process.on('unhandledRejection', (err) => {
  logError('unhandledRejection', err?.stack || String(err));
});
