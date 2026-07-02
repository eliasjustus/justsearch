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
  runCaptureEvidenceBundle,
  runCliJson,
  runValidateDeterminismBudgetV1,
  runValidateEvidenceBundleV1,
} from './cli.mjs';
import { pruneAgentEvidence, readJsonFileNoSymlinks, resolveAllowedRunFile, tailTextFileNoSymlinks } from './files.mjs';
import { logError, logInfo, maybeAppendNdjson } from './log.mjs';
import { ensureLoopbackUrl, resolveAgentSessionIdForMcp, resolveMainRepoRoot, resolveRepoRoot, resolveUnderRepo } from './paths.mjs';
import {
  AiActivateInputSchema,
  AiActivateOutputSchema,
  AgentChatInputSchema,
  AgentChatOutputSchema,
  ApiCallInputSchema,
  CaptureEvidenceInputSchema,
  CaptureEvidenceOutputSchema,
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
  StatusInputSchema,
  StatusOutputSchema,
  StopInputSchema,
  TailLogInputSchema,
  TailLogOutputSchema,
  ToolErrorSchema,
  ValidateEvidenceInputSchema,
  ValidateEvidenceOutputSchema,
} from './schemas.mjs';

// Tempdoc 606 — the ONE ownership-verdict authority, shared with the dev-runner
// admission gate (single-derivation fix for D3). CJS module imported via
// createRequire (the same interop pattern the dev-runner test uses).
import { createRequire } from 'node:module';
const _ownReq = createRequire(import.meta.url);
const { computeOwnershipVerdict, readSessionActivity, computeDisplacedNotice, recommendedTakeoverFor } = _ownReq('../lib/ownership-verdict.cjs');

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
    ownership.lease = active.lease;
    ownership.leaseFresh = new Date(active.lease.expiresAt) > new Date();
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

function toPosixRel(relPath) {
  return String(relPath).split(path.sep).join('/');
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
      (res) => {
        const statusCode = typeof res.statusCode === 'number' ? res.statusCode : null;
        let bytes = 0;
        const chunks = [];
        let aborted = false;

        res.on('data', (chunk) => {
          if (aborted) return;
          bytes += chunk.length;
          if (bytes > maxBytes) {
            aborted = true;
            res.destroy(new Error('response_too_large'));
            return;
          }
          chunks.push(chunk);
        });
        res.on('error', (err) => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({
            ok: false,
            statusCode,
            text,
            textTail: tail(text, 8000),
            error: { message: err?.message || String(err) },
          });
        });
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({ ok: true, statusCode, text, textTail: tail(text, 8000), error: null });
        });
      },
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) =>
      finish({ ok: false, statusCode: null, text: null, textTail: null, error: { message: err?.message || String(err) } }),
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
      (res) => {
        const statusCode = typeof res.statusCode === 'number' ? res.statusCode : null;
        let bytes = 0;
        const chunks = [];
        let aborted = false;

        res.on('data', (chunk) => {
          if (aborted) return;
          bytes += chunk.length;
          if (bytes > maxBytes) {
            aborted = true;
            res.destroy(new Error('response_too_large'));
            return;
          }
          chunks.push(chunk);
        });
        res.on('error', (err) => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({
            ok: false,
            statusCode,
            text,
            textTail: tail(text, 8000),
            error: { message: err?.message || String(err) },
          });
        });
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          finish({ ok: true, statusCode, text, textTail: tail(text, 8000), error: null });
        });
      },
    );

    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) =>
      finish({ ok: false, statusCode: null, text: null, textTail: null, error: { message: err?.message || String(err) } }),
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
 * @returns {{ ok: true, apiBaseUrl: string, runId: string|null } | { ok: false, error: { code: string, message: string } }}
 */
async function resolveApiBaseUrl({ runId, apiPort, mainRepoRoot }) {
  if (apiPort) {
    const url = ensureLoopbackUrl(`http://127.0.0.1:${apiPort}`, 'apiPort');
    return { ok: true, apiBaseUrl: url.toString().replace(/\/$/, ''), runId: runId ?? null };
  }
  const result = await safeReadRunJson(mainRepoRoot, runId);
  if (!result.ok) return { ok: false, error: result.error };
  const apiBaseUrl = String(result.runJson?.apiBaseUrl || '').trim();
  if (!apiBaseUrl) return { ok: false, error: { code: 'NO_API_URL', message: `Run ${result.runId} has no API URL (apiBaseUrl missing from run.json). The run may not have fully started — call quick_health to check.` } };
  return { ok: true, apiBaseUrl, runId: result.runId };
}

/** Default inference server port (llama-server). Read once at module load. */
const INFERENCE_PORT = parseInt(process.env.JUSTSEARCH_SERVER_PORT, 10) || 8080;

export async function main() {
  const { repoRoot, devRunnerPath } = resolveRepoRoot();
  const mainRepoRoot = resolveMainRepoRoot(repoRoot);

  const mcpServer = new McpServer(
    { name: 'justsearch-dev-mcp', version: '0.2.0' },
    {
      instructions: [
        'JustSearch dev tools — 15 tools for managing the local development stack.',
        '',
        'Categories: lifecycle (start, stop, status), orientation (quick_health, preflight),',
        'data (fetch_api_json, api_call, search_query, ingest), agent (agent_chat),',
        'AI runtime (ai_activate), monitoring (tail_log), evidence (capture_evidence, validate_evidence),',
        'hot-reload (reload).',
        '',
        'Workflow: (1) quick_health to check state, (2) preflight if not running,',
        '(3) start to launch (cold: ~1 min, warm: ~15s HTTP / ~40s worker ready), (4) use tools, (5) stop when done.',
        '',
        'Hot-reload: start with hotReload: true for full service restart support.',
        'Then call reload after code changes to compile + push bytecode + restart services (~2-3s).',
        'Without hotReload on start, reload still pushes bytecode (method-body changes only).',
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

      const hotReload = input.hotReload === true;

      // Tempdoc 606 Piece 4: optionally launch from a specific worktree's dist. The shared
      // lease stays under the main repo (state is mainRepoRoot-scoped in the dev-runner), so a
      // worktree agent can run ITS code on the one shared stack. Validate distFrom is the main
      // repo or a sibling worktree before spawning that checkout's dev-runner.
      let effRepoRoot = repoRoot;
      let effDevRunnerPath = devRunnerPath;
      if (input.distFrom) {
        const resolved = path.resolve(mainRepoRoot, input.distFrom);
        const norm = resolved.replace(/\\/g, '/').replace(/\/+$/, '');
        const mainNorm = mainRepoRoot.replace(/\\/g, '/').replace(/\/+$/, '');
        const isMain = norm === mainNorm;
        const isWorktree = norm.startsWith(`${mainNorm}/.claude/worktrees/`);
        if (!isMain && !isWorktree) {
          return toToolResult({ ok: false, error: { code: 'INVALID_DIST_FROM',
            message: `distFrom must be the main repo or a sibling worktree under .claude/worktrees: ${input.distFrom}` } });
        }
        const cand = path.join(resolved, 'scripts', 'dev', 'dev-runner.cjs');
        try { await fsp.access(cand); } catch {
          return toToolResult({ ok: false, error: { code: 'INVALID_DIST_FROM',
            message: `dev-runner.cjs not found under distFrom (build the worktree first?): ${cand}` } });
        }
        effRepoRoot = resolved;
        effDevRunnerPath = cand;
      }

      const args = buildDevRunnerArgsStart({ apiPort, uiPort, clean, dataDir, takeover, skipBuild, hotReload, sessionId: input.sessionId });
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
    'justsearch.dev.status',
    {
      description: 'Check detailed dev-runner status including process state and ports. Use quick_health for faster orientation.',
      inputSchema: StatusInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = StatusInputSchema.parse(rawArgs);
      const runId = input.runId ?? null;

      const args = buildDevRunnerArgsStatus({ runId });
      maybeAppendNdjson(mainRepoRoot, { event: 'tool_start', tool: 'justsearch.dev.status', ...(runId ? { runId } : {}) });

      const { exitCode, json } = await runCliJson({
        repoRoot,
        devRunnerPath,
        args,
        timeoutMs: 20_000,
        mode: 'oneshot',
      });

      // status returns ok:false for NO_ACTIVE_RUN; do not coerce to failure on exitCode alone.
      const parsed = DevRunnerStatusJsonSchema.parse(json);
      const out = StatusOutputSchema.parse({ ...parsed, exitCode });

      // Best-effort convenience enrichment (do not fail the status tool if run.json is missing).
      if (out.ok && out.runId) {
        try {
          const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: out.runId });
          out.apiBaseUrl = runJson?.apiBaseUrl;
          out.uiUrl = runJson?.uiUrl;
          out.dataDir = runJson?.dataDir;
          out.logs = runJson?.logs;
          if (runJson?.owner) out.owner = runJson.owner;
          if (runJson?.resourceClaims) out.resourceClaims = runJson.resourceClaims;
        } catch (_) {}
        // Ownership from active lease (271) — tempdoc 606 single verdict projection.
        try {
          const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
          const callerSessionId = input.sessionId || resolveAgentSessionIdForMcp(repoRoot);
          const proj = await buildOwnershipProjection({ mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId, takeover: 'deny', active });
          if (proj.ownership) out.ownership = proj.ownership;
        } catch (_) {}
      }

      maybeAppendNdjson(mainRepoRoot, { event: 'tool_status_result', tool: 'justsearch.dev.status', ok: !!out.ok, ...(out.runId ? { runId: out.runId } : {}) });
      return toToolResult(out);
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

      const endpointMap = {
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

      const relPath = endpointMap[input.endpoint];
      const url = new URL(relPath, base).toString();
      const res = await httpGetTextLimited(url, { timeoutMs, maxBytes });

      let parsedJson = undefined;
      let jsonOk = false;
      if (res.ok) {
        try {
          parsedJson = JSON.parse(res.text || '');
          jsonOk = true;
        } catch (_) {
          jsonOk = false;
        }
      }

      // jsonPath extraction — extract subtree before building output
      let jsonPathError = null;
      if (input.jsonPath && jsonOk && parsedJson != null) {
        parsedJson = input.jsonPath.split('.').reduce((obj, key) => obj?.[key], parsedJson);
        if (parsedJson === undefined) {
          jsonOk = false;
          jsonPathError = { message: `jsonPath "${input.jsonPath}" resolved to undefined. Check the path against the full response (omit jsonPath to see it).` };
        }
      }

      const isOk = res.ok && res.statusCode === 200 && jsonOk;
      const effectiveError = res.error
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
        ...(jsonOk ? { json: parsedJson } : {}),
        // Only include textTail when JSON parsing failed (saves ~50% tokens on success)
        ...(!jsonOk && res.textTail ? { textTail: res.textTail } : {}),
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

  const API_CALL_ALLOWLIST = [
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
    // Worker & offline
    { path: '/api/worker/restart', methods: ['POST'] },
    { path: '/api/offline/process', methods: ['POST'] },
    // AI install
    { path: '/api/ai/install/status', methods: ['GET'] },
    { path: '/api/ai/install/start', methods: ['POST'] },
    { path: '/api/ai/install/cancel', methods: ['POST'] },
    { path: '/api/ai/install/repair', methods: ['POST'] },
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
      const entry = API_CALL_ALLOWLIST.find((e) => e.path === input.path);
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

      let parsedJson = undefined;
      let jsonOk = false;
      // 204 No Content is a valid success with no body
      const isNoContent = res.statusCode === 204;
      if (res.ok && res.text) {
        try {
          parsedJson = JSON.parse(res.text);
          jsonOk = true;
        } catch (_) {
          jsonOk = false;
        }
      }

      const out = {
        ok: res.ok && (res.statusCode >= 200 && res.statusCode < 300) && (jsonOk || isNoContent),
        runId: effectiveRunId,
        method,
        path: input.path,
        url,
        statusCode: res.statusCode,
        ...(jsonOk ? { json: parsedJson } : {}),
        // Only include textTail when JSON parsing failed (saves ~50% tokens on success)
        ...(!jsonOk && !isNoContent && res.textTail ? { textTail: res.textTail } : {}),
        ...(res.error ? { error: { message: res.error.message } } : {}),
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

  mcpServer.registerTool(
    'justsearch.dev.validate_evidence',
    {
      description: 'Validate an EvidenceBundle directory for correct structure and determinism budget compliance.',
      inputSchema: ValidateEvidenceInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = ValidateEvidenceInputSchema.parse(rawArgs);
      const timeoutMs = input.timeoutMs ?? 60_000;
      const enforceDeterminism = input.enforceDeterminism ?? false;

      const bundleRelNative = resolveUnderRepo(repoRoot, input.bundleDir, 'bundleDir');
      const bundleRelPosix = toPosixRel(bundleRelNative);
      if (!(bundleRelPosix === 'tmp/agent-evidence' || bundleRelPosix.startsWith('tmp/agent-evidence/'))) {
        throw new Error(`bundleDir must be under tmp/agent-evidence (repo-only). got=${bundleRelPosix}`);
      }

      const [ev, det] = await Promise.all([
        runValidateEvidenceBundleV1({ repoRoot, bundleDir: bundleRelPosix, timeoutMs }),
        runValidateDeterminismBudgetV1({
          repoRoot,
          bundleDir: bundleRelPosix,
          timeoutMs,
          strictReasons: input.strictReasons,
          allowReasons: input.allowReasons,
        }),
      ]);

      const errors = [];
      const warnings = [];

      const evidenceOk = ev.exitCode === 0;
      const determinismOk = det.exitCode === 0;
      const ok = evidenceOk && (enforceDeterminism ? determinismOk : true);

      if (!evidenceOk && Array.isArray(ev.errors)) errors.push(...ev.errors.map((e) => `evidencebundle: ${e}`));

      if (!determinismOk && Array.isArray(det.errors)) {
        const prefixed = det.errors.map((e) => `determinism: ${e}`);
        if (enforceDeterminism) errors.push(...prefixed);
        else warnings.push(...prefixed);
      }

      const out = ValidateEvidenceOutputSchema.parse({
        ok,
        bundleDir: path.resolve(repoRoot, bundleRelNative),
        evidenceBundle: {
          ok: evidenceOk,
          exitCode: ev.exitCode,
          stdoutTail: ev.stdoutTail || '',
          stderrTail: ev.stderrTail || '',
          ...(ev.errors ? { errors: ev.errors } : {}),
        },
        determinismBudget: {
          ok: determinismOk,
          exitCode: det.exitCode,
          stdoutTail: det.stdoutTail || '',
          stderrTail: det.stderrTail || '',
          ...(det.errors ? { errors: det.errors } : {}),
        },
        ...(errors.length > 0 ? { errors } : {}),
        ...(warnings.length > 0 ? { warnings } : {}),
      });

      return toToolResult(out);
    },
  );

  mcpServer.registerTool(
    'justsearch.dev.capture_evidence',
    {
      description: 'Capture a snapshot of dev run state as an EvidenceBundle including API responses and allowlisted log files.',
      inputSchema: CaptureEvidenceInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = CaptureEvidenceInputSchema.parse(rawArgs);
      if (!input.runId) input.runId = await resolveRunId(mainRepoRoot, undefined);
      if (!input.runId) return toToolResult({ ok: false, error: { code: 'NO_ACTIVE_RUN', message: 'No active run found. Recovery: call start to launch the dev stack, then retry.' } });

      const runId = input.runId;
      const scenario = input.scenario ?? `dev-run-${runId}`;
      const timeoutMs = input.timeoutMs ?? 60_000;
      const trace = input.trace ?? true;
      const include = input.include ?? [];

      const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId });
      const apiBaseUrl = String(runJson?.apiBaseUrl || '').trim();
      if (!apiBaseUrl) throw new Error('run.json missing apiBaseUrl');
      ensureLoopbackUrl(apiBaseUrl, 'apiBaseUrl');

      const uiUrlRaw = String(runJson?.uiUrl || '').trim();
      const uiUrl = uiUrlRaw ? ensureLoopbackUrl(uiUrlRaw, 'uiUrl').toString() : null;

      const toPosixRel = (relPath) => String(relPath).split(path.sep).join('/');

      // Output root: repo-only AND under tmp/agent-evidence/**.
      const outRootInput = input.outRoot ?? path.join('tmp', 'agent-evidence', 'dev-runner', runId);
      const outRootRel = resolveUnderRepo(repoRoot, outRootInput, 'outRoot');
      const outRootRelPosix = toPosixRel(outRootRel);
      if (!(outRootRelPosix === 'tmp/agent-evidence' || outRootRelPosix.startsWith('tmp/agent-evidence/'))) {
        throw new Error(`outRoot must be under tmp/agent-evidence (repo-relative). got=${outRootRelPosix}`);
      }

      // Attachment allowlist: only well-known dev-runner outputs for THIS runId.
      const allowedAttachments = [
        `tmp/dev-runner/runs/${runId}/logs/backend.stdout.log`,
        `tmp/dev-runner/runs/${runId}/logs/backend.stderr.log`,
        `tmp/dev-runner/runs/${runId}/logs/frontend.stdout.log`,
        `tmp/dev-runner/runs/${runId}/logs/frontend.stderr.log`,
        `tmp/dev-runner/runs/${runId}/stop-report.json`,
      ];
      const allowedSet = new Set(allowedAttachments);

      const defaultAttachments = allowedAttachments.filter((p) => !p.endsWith('/stop-report.json'));
      const requested = input.attachments && input.attachments.length > 0 ? input.attachments : defaultAttachments;
      const attachFiles = [];
      for (const p0 of requested) {
        // Attachments live under mainRepoRoot (shared dev-runner state), not worktree-local repoRoot.
        const rel = resolveUnderRepo(mainRepoRoot, p0, 'attachment');
        const relPosix = toPosixRel(rel);
        if (!allowedSet.has(relPosix)) {
          throw new Error(`attachment not allowlisted for runId=${runId}: ${p0}`);
        }

        const abs = path.resolve(mainRepoRoot, rel);
        let st;
        try {
          // lstat is required: we explicitly reject symlinked attachments to avoid following links outside the repo.
          // eslint-disable-next-line no-await-in-loop
          st = await fsp.lstat(abs);
        } catch (err) {
          throw new Error(`attachment does not exist: ${relPosix}`);
        }
        if (st.isSymbolicLink()) {
          throw new Error(`attachment must not be a symlink: ${relPosix}`);
        }
        if (!st.isFile()) {
          throw new Error(`attachment must be a file: ${relPosix}`);
        }

        // Ensure the resolved physical path is still under mainRepoRoot (defense-in-depth for junctions/symlinks).
        // eslint-disable-next-line no-await-in-loop
        const real = await fsp.realpath(abs).catch(() => null);
        if (real) {
          resolveUnderRepo(mainRepoRoot, real, 'attachmentRealPath');
        }

        attachFiles.push(relPosix);
      }

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_start',
        tool: 'justsearch.dev.capture_evidence',
        runId,
        scenario,
        outRoot: outRootRelPosix,
        attachments: attachFiles,
        include,
        trace,
        timeoutMs,
      });

      const args = [
        '--scenario',
        scenario,
        '--api-base-url',
        apiBaseUrl,
        ...(uiUrl ? ['--ui-url', uiUrl] : []),
        '--out-root',
        outRootRelPosix,
        '--timeout-ms',
        String(timeoutMs),
        '--trace',
        String(trace),
        ...(include.length > 0 ? ['--include', include.join(',')] : []),
        '--attach-label',
        'dev-runner',
        ...attachFiles.flatMap((p) => ['--attach-file', p]),
      ];

      // External timeout should exceed the internal budget by a little, but still remain bounded.
      const externalTimeoutMs = Math.max(120_000, timeoutMs + 60_000);
      const { exitCode, bundleDir, stderr } = await runCaptureEvidenceBundle({
        repoRoot,
        args,
        timeoutMs: externalTimeoutMs,
      });

      const out = CaptureEvidenceOutputSchema.parse({
        ok: exitCode === 0,
        runId,
        bundleDir,
        exitCode,
        outRoot: outRootRelPosix,
        attachments: attachFiles,
        ...(exitCode !== 0 ? { stderrTail: stderr } : {}),
      });

      // Best-effort evidence retention: keep only the last N bundles under tmp/agent-evidence/**.
      // Never fail the capture tool if pruning fails (record warnings instead).
      try {
        const retention = await pruneAgentEvidence({ repoRoot, keepLastN: 20 });
        out.retention = retention;
      } catch (err) {
        out.retention = { keepLastN: 20, found: 0, deleted: 0, warnings: [String(err?.message || err)] };
      }

      maybeAppendNdjson(mainRepoRoot, { event: 'tool_capture_evidence_result', tool: 'justsearch.dev.capture_evidence', runId, ok: out.ok, exitCode });
      return toToolResult(await withStaleness(out, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId: input.sessionId || resolveAgentSessionIdForMcp(repoRoot) }));
    },
  );

  // ─── Preflight ─────────────────────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.preflight',
    {
      description: 'Check if the dev stack can be started: worker dist built, no active/stale runs, models present, no inference orphans.',
      inputSchema: PreflightInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async () => {
      const details = {};

      // 1a. Worker distribution exists
      let workerDist = false;
      const workerBin = path.join(repoRoot, 'modules', 'indexer-worker', 'build', 'install', 'indexer-worker', 'bin',
        process.platform === 'win32' ? 'indexer-worker.bat' : 'indexer-worker');
      try {
        await fsp.lstat(workerBin);
        workerDist = true;
        details.workerDist = 'OK';
      } catch {
        details.workerDist = `Missing: ${workerBin}. Run: ./gradlew.bat assemble`;
      }

      // 1b. Head (UI) distribution exists — the dev-runner spawns from installDist, not gradlew
      let headDist = false;
      const headBin = path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'bin',
        process.platform === 'win32' ? 'ui.bat' : 'ui');
      try {
        await fsp.lstat(headBin);
        headDist = true;
        details.headDist = 'OK';
      } catch {
        details.headDist = `Missing: ${headBin}. Run: ./gradlew.bat :modules:ui:installDist`;
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
        details,
      }));
    },
  );

  // ─── Quick Health ──────────────────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.quick_health',
    {
      description: 'Fast orientation — call after compaction or at session start. Returns run state and optional HTTP readiness probes without spawning subprocesses.',
      inputSchema: QuickHealthInputSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = QuickHealthInputSchema.parse(rawArgs);
      const probe = input.probe !== false;

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

      return toToolResult(QuickHealthOutputSchema.parse({
        running: runId !== null && (httpReady === true || (httpReady === null && pidsAlive)),
        runId,
        apiPort,
        uiPort,
        httpReady,
        workerReady,
        aiActive: null,
        ...(inferenceOrphan !== undefined ? { inferenceOrphan } : {}),
        ...(ownership ? { ownership } : {}),
        ...(freshness ? { freshness } : {}),
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
  // Agent Chat — SSE consumer for interacting with the built-in agent
  // ---------------------------------------------------------------------------

  /**
   * Consume the agent SSE stream and return a structured transcript.
   * Auto-approves tool calls when opts.autoApprove is true.
   */
  function consumeAgentSse(streamUrl, body, opts) {
    const { timeoutMs, totalTimeoutMs, maxBytes, autoApprove, approveBaseUrl, verbose } = opts;
    return new Promise((resolve) => {
      const startMs = Date.now();
      let settled = false;
      let totalTimer = null;
      const finish = (transcript) => {
        if (settled) return;
        settled = true;
        if (totalTimer) clearTimeout(totalTimer);
        transcript.durationMs = Date.now() - startMs;
        resolve(transcript);
      };

      // Iteration tracking: progress events mark iteration boundaries
      let currentIteration = 0;
      let currentPhase = '';
      const iterationTextChunks = []; // text chunks accumulated since last progress event
      const iterationDetails = [];    // [{iteration, phase, textBefore, toolCallIds}]

      const transcript = {
        sessionId: null,
        toolCalls: {},
        chunks: [],
        finalResponse: '',
        iterationsUsed: null,
        toolCallsExecuted: null,
        totalTokensUsed: null,
        budgetUpdates: [],
        error: null,
        durationMs: 0,
        iterations: [],
      };

      // Queue of callIds needing approval before sessionId is known
      const pendingApproveQueue = [];

      function fireApprove(callId) {
        const sid = transcript.sessionId;
        if (!sid) {
          pendingApproveQueue.push(callId);
          return;
        }
        // Tempdoc 565 §15.C — the unified approval endpoint dispatches agent-gate → workflow-gate
        // (the forked /api/chat/agent/approve was retired; AgentRoutes.java).
        const url = new URL('/api/chat/approve', approveBaseUrl).toString();
        // Fire-and-forget — SSE stream will confirm via tool_call_approved
        httpPostJsonLimited(url, { sessionId: sid, callId }, { timeoutMs: 10_000, maxBytes: 4096 }).catch(() => {});
      }

      function flushApproveQueue() {
        while (pendingApproveQueue.length > 0) {
          fireApprove(pendingApproveQueue.shift());
        }
      }

      function toAgentTrace(rawTrace, fallbackToolCallId) {
        if (!rawTrace || typeof rawTrace !== 'object') return undefined;
        const out = {};
        if (typeof rawTrace.runId === 'string' && rawTrace.runId) out.runId = rawTrace.runId;
        if (typeof rawTrace.stepId === 'string' && rawTrace.stepId) out.stepId = rawTrace.stepId;
        if (typeof rawTrace.spanId === 'string' && rawTrace.spanId) out.spanId = rawTrace.spanId;
        if (typeof rawTrace.parentSpanId === 'string' && rawTrace.parentSpanId) out.parentSpanId = rawTrace.parentSpanId;
        if (typeof rawTrace.agentId === 'string' && rawTrace.agentId) out.agentId = rawTrace.agentId;
        if (typeof rawTrace.toolCallId === 'string' && rawTrace.toolCallId) {
          out.toolCallId = rawTrace.toolCallId;
        } else if (typeof fallbackToolCallId === 'string' && fallbackToolCallId) {
          out.toolCallId = fallbackToolCallId;
        }
        if (Number.isFinite(rawTrace.iteration)) out.iteration = Number(rawTrace.iteration);
        return Object.keys(out).length > 0 ? out : undefined;
      }

      function dispatchEvent(eventType, dataStr) {
        let data;
        try {
          data = dataStr ? JSON.parse(dataStr) : {};
        } catch {
          return; // Malformed JSON — skip
        }
        const trace = toAgentTrace(
          data.trace,
          typeof data.callId === 'string' ? data.callId : undefined,
        );

        switch (eventType) {
          case 'session_started':
            transcript.sessionId = data.sessionId || null;
            flushApproveQueue();
            break;
          case 'progress': {
            // New iteration boundary — flush accumulated text and start new iteration
            const iter = data.iteration ?? currentIteration;
            if (iter !== currentIteration || iterationDetails.length === 0) {
              const textBefore = iterationTextChunks.join('').trim();
              iterationDetails.push({
                iteration: iter,
                phase: data.phase || '',
                textBefore,
                toolCallIds: [],
                ...(trace ? { trace } : {}),
              });
              iterationTextChunks.length = 0;
            }
            currentIteration = iter;
            currentPhase = data.phase || '';
            break;
          }
          case 'chunk':
            if (data.text) {
              transcript.chunks.push(data.text);
              iterationTextChunks.push(data.text);
            }
            break;
          case 'tool_call_proposed': {
            transcript.toolCalls[data.callId] = {
              callId: data.callId,
              toolName: data.toolName || '',
              arguments: data.arguments || '{}',
              risk: data.risk || null,
              approved: false,
              success: null,
              output: null,
              iteration: currentIteration,
              ...(trace ? { trace } : {}),
            };
            // Associate this call with the current iteration detail
            const iterDetail = iterationDetails[iterationDetails.length - 1];
            if (iterDetail) iterDetail.toolCallIds.push(data.callId);
            break;
          }
          case 'tool_call_pending':
            if (autoApprove && data.callId) {
              if (transcript.toolCalls[data.callId]) {
                transcript.toolCalls[data.callId].approved = true;
                if (trace && !transcript.toolCalls[data.callId].trace) {
                  transcript.toolCalls[data.callId].trace = trace;
                }
              }
              fireApprove(data.callId);
            }
            break;
          case 'tool_call_approved':
            if (transcript.toolCalls[data.callId]) {
              transcript.toolCalls[data.callId].approved = true;
              if (trace && !transcript.toolCalls[data.callId].trace) {
                transcript.toolCalls[data.callId].trace = trace;
              }
            }
            break;
          case 'tool_call_rejected':
            if (transcript.toolCalls[data.callId]) {
              transcript.toolCalls[data.callId].approved = false;
              if (trace && !transcript.toolCalls[data.callId].trace) {
                transcript.toolCalls[data.callId].trace = trace;
              }
            }
            break;
          case 'tool_exec_completed':
            if (transcript.toolCalls[data.callId]) {
              transcript.toolCalls[data.callId].success = !!data.success;
              transcript.toolCalls[data.callId].output = tail(String(data.output ?? ''), 8000);
              if (trace && !transcript.toolCalls[data.callId].trace) {
                transcript.toolCalls[data.callId].trace = trace;
              }
            }
            break;
          case 'budget_update':
            transcript.budgetUpdates.push({
              phase: data.phase || '',
              tokensConsumed: Number.isFinite(data.tokensConsumed) ? Number(data.tokensConsumed) : 0,
              tokensRemaining: Number.isFinite(data.tokensRemaining) ? Number(data.tokensRemaining) : 0,
              ...(trace ? { trace } : {}),
            });
            break;
          case 'done':
            transcript.finalResponse = data.finalResponse || transcript.chunks.join('');
            transcript.iterationsUsed = data.iterationsUsed ?? null;
            transcript.toolCallsExecuted = data.toolCallsExecuted ?? null;
            transcript.totalTokensUsed = data.totalTokensUsed ?? null;
            transcript.iterations = iterationDetails;
            finish(transcript);
            break;
          case 'error':
            transcript.error = {
              message: data.error || data.errorCode || 'unknown_error',
              code: data.errorCode,
              errorClass: typeof data.errorClass === 'string' ? data.errorClass : undefined,
              retryAction: typeof data.retryAction === 'string' ? data.retryAction : undefined,
              retryAttempt: Number.isInteger(data.retryAttempt) ? data.retryAttempt : undefined,
            };
            transcript.finalResponse = transcript.chunks.join('');
            finish(transcript);
            break;
          // progress, tool_exec_started — informational, ignored
          default:
            break;
        }
      }

      // SSE line parser state
      let buffer = '';
      let currentEvent = '';
      let currentData = '';
      let totalBytes = 0;

      const bodyStr = JSON.stringify(body);
      let u;
      try {
        u = new URL(streamUrl);
      } catch {
        transcript.error = { message: 'invalid_stream_url' };
        return finish(transcript);
      }

      const req = http.request(
        {
          hostname: u.hostname,
          port: Number(u.port),
          path: u.pathname + u.search,
          method: 'POST',
          timeout: timeoutMs,
          headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(bodyStr),
            Accept: 'text/event-stream',
          },
        },
        (res) => {
          if (res.statusCode !== 200) {
            const errChunks = [];
            res.on('data', (c) => errChunks.push(c));
            res.on('end', () => {
              const errText = Buffer.concat(errChunks).toString('utf8');
              transcript.error = { message: `HTTP ${res.statusCode}: ${tail(errText, 2000)}` };
              finish(transcript);
            });
            return;
          }

          res.setEncoding('utf8');
          res.on('data', (chunk) => {
            totalBytes += Buffer.byteLength(chunk, 'utf8');
            if (totalBytes > maxBytes) {
              transcript.error = { message: 'response_too_large' };
              req.destroy();
              finish(transcript);
              return;
            }

            buffer += chunk;
            const lines = buffer.split('\n');
            buffer = lines.pop(); // Keep incomplete last line

            for (const line of lines) {
              const trimmed = line.replace(/\r$/, '');
              if (trimmed.startsWith('event:')) {
                currentEvent = trimmed.slice(6).trim();
              } else if (trimmed.startsWith('data:')) {
                currentData = trimmed.slice(5).trim();
              } else if (trimmed === '') {
                if (currentEvent) {
                  dispatchEvent(currentEvent, currentData);
                  if (settled) return; // done or error resolved the promise
                }
                currentEvent = '';
                currentData = '';
              }
            }
          });

          res.on('end', () => {
            // Stream closed without done/error event — return what we have
            if (!settled) {
              transcript.finalResponse = transcript.finalResponse || transcript.chunks.join('');
              if (!transcript.error) transcript.error = { message: 'stream_closed_unexpectedly' };
              finish(transcript);
            }
          });

          res.on('error', (err) => {
            transcript.error = { message: err?.message || 'stream_error' };
            transcript.finalResponse = transcript.finalResponse || transcript.chunks.join('');
            finish(transcript);
          });
        },
      );

      req.on('timeout', () => {
        transcript.error = { message: 'timeout' };
        transcript.finalResponse = transcript.chunks.join('');
        req.destroy();
        finish(transcript);
      });

      req.on('error', (err) => {
        transcript.error = { message: err?.message || 'request_error' };
        finish(transcript);
      });

      req.write(bodyStr);
      req.end();

      if (totalTimeoutMs) {
        totalTimer = setTimeout(() => {
          transcript.error = { message: 'total_timeout' };
          transcript.finalResponse = transcript.chunks.join('');
          req.destroy();
          finish(transcript);
        }, totalTimeoutMs);
      }
    });
  }

  mcpServer.registerTool(
    'justsearch.dev.agent_chat',
    {
      description:
        'Send a prompt to the built-in agent and get the full conversation transcript including tool calls, results, and final response.' +
        ' Set verbose=true for per-iteration reasoning detail.',
      inputSchema: AgentChatInputSchema,
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = AgentChatInputSchema.parse(rawArgs);
      const timeoutMs = input.timeoutMs ?? 120_000;
      const maxBytes = input.maxBytes ?? 2_000_000;
      const autoApprove = input.autoApprove ?? true;
      const maxIterations = input.maxIterations ?? 10;

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        const out = AgentChatOutputSchema.parse({
          ok: false,
          runId: resolved.runId ?? input.runId ?? 'unknown',
          prompt: input.prompt,
          sessionId: null,
          error: ToolErrorSchema.parse(resolved.error),
        });
        return toToolResult(out);
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      // Agent run-stream route (AgentRoutes.java:24). The legacy /api/agent/*
      // prefix was removed; handleRunStream now lives at /api/chat/agent. Same
      // request body + SSE event shape (the browser AgentSessionController posts
      // the identical `messages` body to this route) — path-only change.
      const streamUrl = new URL('/api/chat/agent', base).toString();
      const body = {
        messages: [{ role: 'user', content: input.prompt }],
        maxIterations,
      };

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_start',
        tool: 'justsearch.dev.agent_chat',
        runId: effectiveRunId,
        prompt: input.prompt,
        maxIterations,
        autoApprove,
      });

      const verbose = input.verbose ?? false;
      const transcript = await consumeAgentSse(streamUrl, body, {
        timeoutMs,
        totalTimeoutMs: input.totalTimeoutMs,
        maxBytes,
        autoApprove,
        approveBaseUrl: base,
        verbose,
      });

      // Convert toolCalls map to sorted array
      const toolCallsArr = Object.values(transcript.toolCalls);

      if (transcript.error) {
        const errorPayload = {
          ok: false,
          runId: effectiveRunId,
          prompt: input.prompt,
          sessionId: transcript.sessionId,
          toolCalls: toolCallsArr,
          finalResponse: transcript.finalResponse || '',
          totalTokensUsed: transcript.totalTokensUsed,
          durationMs: transcript.durationMs,
          error: ToolErrorSchema.parse({
            message: transcript.error.message,
            code: transcript.error.code,
            errorClass: transcript.error.errorClass,
            retryAction: transcript.error.retryAction,
            retryAttempt: transcript.error.retryAttempt,
          }),
        };
        if (verbose && transcript.iterations?.length > 0) {
          errorPayload.iterations = transcript.iterations;
        }
        if (verbose && transcript.budgetUpdates?.length > 0) {
          errorPayload.budgetUpdates = transcript.budgetUpdates;
        }
        const out = AgentChatOutputSchema.parse(errorPayload);
        maybeAppendNdjson(mainRepoRoot, {
          event: 'tool_agent_chat_result',
          tool: 'justsearch.dev.agent_chat',
          runId: effectiveRunId,
          ok: false,
          error: transcript.error.message,
          durationMs: transcript.durationMs,
        });
        return toToolResult(out);
      }

      const successPayload = {
        ok: true,
        runId: effectiveRunId,
        prompt: input.prompt,
        sessionId: transcript.sessionId,
        toolCalls: toolCallsArr,
        finalResponse: transcript.finalResponse,
        iterationsUsed: transcript.iterationsUsed,
        toolCallsExecuted: transcript.toolCallsExecuted,
        totalTokensUsed: transcript.totalTokensUsed,
        durationMs: transcript.durationMs,
      };
      if (verbose && transcript.iterations?.length > 0) {
        successPayload.iterations = transcript.iterations;
      }
      if (verbose && transcript.budgetUpdates?.length > 0) {
        successPayload.budgetUpdates = transcript.budgetUpdates;
      }
      const out = AgentChatOutputSchema.parse(successPayload);

      maybeAppendNdjson(mainRepoRoot, {
        event: 'tool_agent_chat_result',
        tool: 'justsearch.dev.agent_chat',
        runId: effectiveRunId,
        ok: true,
        toolCalls: toolCallsArr.length,
        iterationsUsed: transcript.iterationsUsed,
        durationMs: transcript.durationMs,
      });
      return toToolResult(await withStaleness(out, { mainRepoRoot, callerRepoRoot: repoRoot, callerSessionId: input.sessionId || resolveAgentSessionIdForMcp(repoRoot) }));
    },
  );

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
      const startMs = Date.now();

      const resolved = await resolveApiBaseUrl({ runId: input.runId, apiPort: input.apiPort, mainRepoRoot });
      if (!resolved.ok) {
        return toToolResult(AiActivateOutputSchema.parse({
          ok: false, runId: resolved.runId ?? input.runId ?? 'unknown', variantId,
          error: ToolErrorSchema.parse(resolved.error),
        }));
      }
      const base = ensureLoopbackUrl(resolved.apiBaseUrl, 'apiBaseUrl');
      const effectiveRunId = resolved.runId ?? 'port-only';

      // Check current state — might already be active
      const statusUrl = new URL('/api/ai/runtime/status', base).toString();
      const preCheck = await httpGetTextLimited(statusUrl, { timeoutMs: 10_000, maxBytes: 100_000 });
      if (preCheck.ok) {
        try {
          const pre = JSON.parse(preCheck.text);
          if (pre.activation?.state === 'completed' && pre.active?.activeVariantId) {
            return toToolResult(AiActivateOutputSchema.parse({
              ok: true, runId: effectiveRunId, variantId: pre.active.activeVariantId,
              activationState: 'completed', phase: 'done',
              message: 'AI runtime already active.', durationMs: Date.now() - startMs,
            }));
          }
        } catch { /* proceed with activation */ }
      }

      // Fire activate
      const activateUrl = new URL('/api/ai/runtime/activate', base).toString();
      const activateRes = await httpPostJsonLimited(activateUrl, { variantId }, { timeoutMs: 15_000, maxBytes: 100_000 });
      if (!activateRes.ok || (activateRes.statusCode && activateRes.statusCode >= 400)) {
        const errMsg = activateRes.textTail || activateRes.error?.message || `HTTP ${activateRes.statusCode}`;
        return toToolResult(AiActivateOutputSchema.parse({
          ok: false, runId: effectiveRunId, variantId,
          error: ToolErrorSchema.parse({ message: errMsg }),
          durationMs: Date.now() - startMs,
        }));
      }

      // Poll until completed/failed/timeout
      const terminalStates = new Set(['completed', 'failed', 'idle']);
      let lastState = 'running';
      let lastPhase = '';
      let lastMessage = '';

      while (Date.now() - startMs < timeoutMs) {
        await delay(pollIntervalMs);
        const poll = await httpGetTextLimited(statusUrl, { timeoutMs: 10_000, maxBytes: 100_000 });
        if (!poll.ok) continue;
        try {
          const status = JSON.parse(poll.text);
          lastState = status.activation?.state || lastState;
          lastPhase = status.activation?.phase || lastPhase;
          lastMessage = status.activation?.message || lastMessage;
          if (terminalStates.has(lastState)) break;
        } catch { /* retry */ }
      }

      const elapsed = Date.now() - startMs;
      if (lastState === 'completed') {
        return toToolResult(AiActivateOutputSchema.parse({
          ok: true, runId: effectiveRunId, variantId,
          activationState: lastState, phase: lastPhase,
          message: lastMessage, durationMs: elapsed,
        }));
      }

      return toToolResult(AiActivateOutputSchema.parse({
        ok: false, runId: effectiveRunId, variantId,
        activationState: lastState, phase: lastPhase, message: lastMessage,
        error: ToolErrorSchema.parse({
          message: lastState === 'failed' ? (lastMessage || 'Activation failed')
            : `Timeout after ${elapsed}ms (state: ${lastState}, phase: ${lastPhase})`,
        }),
        durationMs: elapsed,
      }));
    },
  );

  // ─── Hot-reload (tempdoc 305) ────────────────────────────────────

  mcpServer.registerTool(
    'justsearch.dev.reload',
    {
      description: 'Hot-reload Worker code: compile changed classes, push bytecode via JDWP, signal service restart. Requires running dev stack. Call after editing Worker-side code (search, indexing, pipeline logic).',
      inputSchema: ReloadInputSchema,
      annotations: { destructiveHint: false, openWorldHint: false },
    },
    async (rawArgs) => {
      const input = ReloadInputSchema.parse(rawArgs);
      const module = input.module || 'worker-services';
      const debugPort = input.debugPort || 5005;
      const skipCompile = input.skipCompile === true;
      const gradleCmd = path.join(repoRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
      const javaCmd = 'java';

      const result = { ok: true, compileMs: null, hotSwapOutput: null, hotSwapOk: null, structuralChangeDetected: false, signalWritten: false };

      // 1. Find active run and data dir
      let dataDir = null;
      try {
        const active = await readJsonFileNoSymlinks({ repoRoot: mainRepoRoot, relPosix: 'tmp/dev-runner/active.json', maxBytes: 200_000 });
        if (!active?.runId) throw new Error('no runId');
        const runJson = await readRunJson({ repoRoot: mainRepoRoot, runId: active.runId });
        dataDir = runJson?.dataDir ?? null;
      } catch {
        return toToolResult({ ok: false, error: { code: 'NO_ACTIVE_RUN', message: 'No active dev stack. Call start first.' } });
      }

      const signalFile = dataDir ? path.join(dataDir, 'worker_signal.lock') : null;
      const classesDir = path.join(repoRoot, 'modules', module, 'build', 'classes', 'java', 'main');
      const hotSwapScript = path.join(repoRoot, 'scripts', 'dev', 'HotSwapPush.java');

      // 2. Compile
      if (!skipCompile) {
        const compileStart = Date.now();
        try {
          const compileResult = await execFileP(
            gradleCmd,
            [`:modules:${module}:compileJava`],
            { cwd: repoRoot, timeout: 60_000, windowsHide: true, shell: process.platform === 'win32' },
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

      // 3. HotSwapPush — push bytecode to running Worker via JDWP
      try {
        const hsResult = await execFileP(
          javaCmd,
          ['--add-modules', 'jdk.jdi', '--source', '25', hotSwapScript, String(debugPort), classesDir],
          { cwd: repoRoot, timeout: 15_000, windowsHide: true },
        );
        result.hotSwapOutput = (hsResult.stdout || '').trim();
        result.hotSwapOk = true;
      } catch (err) {
        // HotSwapPush failed — JDWP not available or structural change rejected.
        // Continue to signal write so service reconstruction still happens.
        const combinedOutput = ((err.stdout || '') + (err.stderr || '')).trim();
        result.hotSwapOutput = tail(combinedOutput, 1000).trim();
        result.hotSwapOk = false;
        // 371: Detect structural changes (new/removed methods or fields) for clear messaging.
        if (combinedOutput.includes('added/removed methods or fields')) {
          result.structuralChangeDetected = true;
          result.restartRequired = 'Structural changes detected — hot-swap only updated method bodies. Restart the dev stack for full effect.';
        }
      }

      // 4. 371: If hot-swap succeeded, propagate the current build stamp to the Worker
      //    so it reports the correct stamp after reload (avoids false-positive staleness warnings).
      //    On structural-change failure, skip — the Worker is genuinely stale.
      //    MUST happen BEFORE the MMF signal: the Worker reads this file during performReload(),
      //    which starts as soon as the sentinel detects the signal byte.
      if (result.hotSwapOk && dataDir) {
        try {
          const stampPath = path.join(repoRoot, 'modules', 'indexer-worker', 'build', 'install', 'indexer-worker', 'build-stamp.txt');
          const stamp = (await fsp.readFile(stampPath, 'utf8')).trim();
          if (stamp) {
            await fsp.writeFile(path.join(dataDir, 'reload-build-stamp.txt'), stamp, 'utf8');
          }
        } catch {
          // Best-effort — missing stamp file is not fatal.
        }
      }

      // 5. Write reload signal to MMF (triggers Worker's DevReloadManager)
      if (signalFile) {
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
      }

      maybeAppendNdjson(mainRepoRoot, { event: 'tool_reload', ok: result.ok, compileMs: result.compileMs, hotSwapOk: result.hotSwapOk });
      return toToolResult(result);
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
