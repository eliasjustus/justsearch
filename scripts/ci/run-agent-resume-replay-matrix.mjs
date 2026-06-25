#!/usr/bin/env node

import http from 'node:http';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

import {
  buildDevRunnerArgsStart,
  buildDevRunnerArgsStop,
  readRunJson,
  runCliJson,
} from '../dev/justsearch-dev-mcp/cli.mjs';
import { ensureLoopbackUrl, resolveRepoRoot } from '../dev/justsearch-dev-mcp/paths.mjs';

const DEFAULT_REPS = 5;
const DEFAULT_START_TIMEOUT_MS = 180_000;
const DEFAULT_READY_TIMEOUT_MS = 90_000;
const DEFAULT_MAX_BYTES = 500_000;

const MATRIX_STATES = [
  { state: 'WAITING_APPROVAL', expectedResumeErrorCode: 'NO_TOOLS' },
  { state: 'TOOL_EXECUTING', expectedResumeErrorCode: 'UNSUPPORTED_RESUME_STATE' },
  { state: 'AFTER_TOOL_RESULT', expectedResumeErrorCode: 'NO_TOOLS' },
  { state: 'LLM_STREAMING', expectedResumeErrorCode: 'UNSUPPORTED_RESUME_STATE' },
];

function usage() {
  console.error(
    'Usage: node scripts/ci/run-agent-resume-replay-matrix.mjs ' +
      '--out-json <path> [--reps <n>] [--start-timeout-ms <ms>] [--ready-timeout-ms <ms>] [--max-bytes <n>]',
  );
  process.exit(2);
}

function parseArgs(argv) {
  const out = {
    outJson: null,
    reps: DEFAULT_REPS,
    startTimeoutMs: DEFAULT_START_TIMEOUT_MS,
    readyTimeoutMs: DEFAULT_READY_TIMEOUT_MS,
    maxBytes: DEFAULT_MAX_BYTES,
  };

  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--reps' && argv[i + 1]) out.reps = Number.parseInt(argv[++i], 10);
    else if (arg === '--start-timeout-ms' && argv[i + 1]) {
      out.startTimeoutMs = Number.parseInt(argv[++i], 10);
    } else if (arg === '--ready-timeout-ms' && argv[i + 1]) {
      out.readyTimeoutMs = Number.parseInt(argv[++i], 10);
    } else if (arg === '--max-bytes' && argv[i + 1]) {
      out.maxBytes = Number.parseInt(argv[++i], 10);
    } else if (arg === '--help') usage();
    else usage();
  }

  if (!out.outJson) usage();
  if (!Number.isFinite(out.reps) || out.reps <= 0) usage();
  if (!Number.isFinite(out.startTimeoutMs) || out.startTimeoutMs <= 0) usage();
  if (!Number.isFinite(out.readyTimeoutMs) || out.readyTimeoutMs <= 0) usage();
  if (!Number.isFinite(out.maxBytes) || out.maxBytes <= 0) usage();
  return out;
}

async function writeJson(filePath, value) {
  const abs = path.resolve(filePath);
  await fsp.mkdir(path.dirname(abs), { recursive: true });
  await fsp.writeFile(abs, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function parseJsonSafe(text) {
  try {
    return JSON.parse(String(text || ''));
  } catch {
    return null;
  }
}

function httpGet(urlStr, { timeoutMs, maxBytes }) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      resolve({ ok: false, statusCode: null, text: null, error: 'invalid_url' });
      return;
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
        const chunks = [];
        let bytes = 0;
        res.on('data', (chunk) => {
          bytes += chunk.length;
          if (bytes > maxBytes) {
            req.destroy(new Error('response_too_large'));
            return;
          }
          chunks.push(chunk);
        });
        res.on('end', () =>
          resolve({
            ok: true,
            statusCode: typeof res.statusCode === 'number' ? res.statusCode : null,
            text: Buffer.concat(chunks).toString('utf8'),
            error: null,
          }),
        );
        res.on('error', (err) =>
          resolve({
            ok: false,
            statusCode: typeof res.statusCode === 'number' ? res.statusCode : null,
            text: Buffer.concat(chunks).toString('utf8'),
            error: err?.message || String(err),
          }),
        );
      },
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (err) =>
      resolve({ ok: false, statusCode: null, text: null, error: err?.message || String(err) }),
    );
    req.end();
  });
}

function httpPostSse(urlStr, bodyObj, { timeoutMs, maxBytes }) {
  return new Promise((resolve) => {
    let u;
    try {
      u = new URL(urlStr);
    } catch {
      resolve({ ok: false, statusCode: null, events: [], error: 'invalid_url', raw: '' });
      return;
    }
    const bodyStr = bodyObj ? JSON.stringify(bodyObj) : '';

    const req = http.request(
      {
        hostname: u.hostname,
        port: Number(u.port),
        path: u.pathname + u.search,
        method: 'POST',
        timeout: timeoutMs,
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(bodyStr),
        },
      },
      (res) => {
        if (res.statusCode !== 200) {
          const chunks = [];
          res.on('data', (c) => chunks.push(c));
          res.on('end', () => {
            resolve({
              ok: false,
              statusCode: res.statusCode ?? null,
              events: [],
              error: `HTTP_${res.statusCode}`,
              raw: Buffer.concat(chunks).toString('utf8'),
            });
          });
          return;
        }

        const chunks = [];
        let bytes = 0;
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          bytes += Buffer.byteLength(chunk, 'utf8');
          if (bytes > maxBytes) {
            req.destroy(new Error('response_too_large'));
            return;
          }
          chunks.push(chunk);
        });
        res.on('end', () => {
          const raw = chunks.join('');
          const events = [];
          for (const block of raw.split('\n\n')) {
            if (!block || !block.trim()) continue;
            let eventName = '';
            let dataText = '';
            for (const line of block.split('\n')) {
              const normalized = line.replace(/\r$/, '');
              if (normalized.startsWith('event:')) eventName = normalized.slice(6).trim();
              else if (normalized.startsWith('data:')) dataText = normalized.slice(5).trim();
            }
            if (!eventName) continue;
            events.push({
              event: eventName,
              data: parseJsonSafe(dataText) || {},
            });
          }
          resolve({ ok: true, statusCode: 200, events, error: null, raw });
        });
        res.on('error', (err) =>
          resolve({
            ok: false,
            statusCode: res.statusCode ?? null,
            events: [],
            error: err?.message || String(err),
            raw: chunks.join(''),
          }),
        );
      },
    );

    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (err) =>
      resolve({ ok: false, statusCode: null, events: [], error: err?.message || String(err), raw: '' }),
    );

    req.write(bodyStr);
    req.end();
  });
}

async function waitReady(apiBaseUrl, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  const statusUrl = new URL('/api/status', apiBaseUrl).toString();
  const healthUrl = new URL('/api/health', apiBaseUrl).toString();

  let lastStatus = null;
  let lastHealth = null;
  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    const status = await httpGet(statusUrl, { timeoutMs: 1200, maxBytes: 8192 });
    lastStatus = status.statusCode;
    if (status.statusCode === 200) {
      // eslint-disable-next-line no-await-in-loop
      const health = await httpGet(healthUrl, { timeoutMs: 1200, maxBytes: 8192 });
      lastHealth = health.statusCode;
      if (health.statusCode === 200 || health.statusCode == null) {
        return { ok: true, lastStatus, lastHealth };
      }
    }
    // eslint-disable-next-line no-await-in-loop
    await delay(250);
  }
  return { ok: false, lastStatus, lastHealth };
}

async function writePersistedSnapshot({
  dataDir,
  sessionId,
  state,
  resumable,
  events,
}) {
  const runRoot = path.join(dataDir, 'agent-runs');
  const sessionDir = path.join(runRoot, sessionId);
  await fsp.mkdir(sessionDir, { recursive: true });

  const now = new Date().toISOString();
  const meta = {
    sessionId,
    startedAt: now,
    updatedAt: now,
    state,
    resumable,
    resumeNote: `Injected matrix state ${state}`,
    selectedToolNames: ['__matrix_missing_tool__'],
    maxIterations: 3,
    initialBudget: 4096,
    iterationsUsed: 1,
    toolCallsExecuted: 1,
    totalTokensUsed: 100,
    messages: [
      { role: 'system', content: 'matrix test' },
      { role: 'user', content: `resume state ${state}` },
    ],
  };
  await fsp.writeFile(path.join(sessionDir, 'meta.json'), `${JSON.stringify(meta, null, 2)}\n`, 'utf8');
  await fsp.writeFile(path.join(runRoot, 'last-session.txt'), `${sessionId}\n`, 'utf8');

  const lines = events.map((event) =>
    JSON.stringify({
      timestamp: now,
      eventType: event.eventType,
      payload: event.payload,
    }),
  );
  await fsp.writeFile(path.join(sessionDir, 'events.ndjson'), `${lines.join('\n')}\n`, 'utf8');
}

function expectedResumable(state) {
  return state === 'WAITING_APPROVAL' || state === 'READY_FOR_LLM' || state === 'AFTER_TOOL_RESULT';
}

async function runOneCase({
  repoRoot,
  devRunnerPath,
  dataDir,
  state,
  expectedResumeErrorCode,
  startTimeoutMs,
  readyTimeoutMs,
  maxBytes,
}) {
  const sessionId = `session_matrix_${state.toLowerCase()}_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  const injectedEvents = [
    { eventType: 'session_started', payload: { sessionId } },
    { eventType: 'progress', payload: { phase: 'matrix', message: state, iteration: 1, maxIterations: 3 } },
  ];

  await writePersistedSnapshot({
    dataDir,
    sessionId,
    state,
    resumable: expectedResumable(state),
    events: injectedEvents,
  });

  let runId = null;
  let apiBaseUrl = null;
  const result = {
    state,
    expectedResumeErrorCode,
    sessionId,
    runId: null,
    apiBaseUrl: null,
    checks: {
      sessionLast: false,
      sessionEvents: false,
      resumeErrorCode: false,
    },
    observed: {
      sessionLastStatus: null,
      sessionLastState: null,
      sessionEventsStatus: null,
      sessionEventsCount: null,
      resumeStatus: null,
      resumeErrorCode: null,
      resumeEventTypes: [],
    },
    pass: false,
    error: null,
  };

  try {
    const started = await runCliJson({
      repoRoot,
      devRunnerPath,
      args: buildDevRunnerArgsStart({
        apiPort: 0,
        uiPort: 5173,
        clean: 'none',
        dataDir,
      }),
      timeoutMs: startTimeoutMs,
      mode: 'supervisor_first_line',
    });
    runId = started?.json?.runId || null;
    result.runId = runId;
    if (!runId) throw new Error('dev-runner start did not return runId');

    const runJson = await readRunJson({ repoRoot, runId });
    apiBaseUrl = ensureLoopbackUrl(String(runJson?.apiBaseUrl || '').trim(), 'apiBaseUrl');
    result.apiBaseUrl = apiBaseUrl;

    const ready = await waitReady(apiBaseUrl, readyTimeoutMs);
    if (!ready.ok) {
      throw new Error(`ready_timeout status=${ready.lastStatus} health=${ready.lastHealth}`);
    }

    const lastUrl = new URL('/api/chat/sessions/last', apiBaseUrl).toString();
    const lastRes = await httpGet(lastUrl, { timeoutMs: 5000, maxBytes });
    result.observed.sessionLastStatus = lastRes.statusCode;
    if (lastRes.ok && lastRes.statusCode === 200) {
      const lastJson = parseJsonSafe(lastRes.text);
      result.observed.sessionLastState = String(lastJson?.state || '');
      result.checks.sessionLast =
        String(lastJson?.sessionId || '') === sessionId && String(lastJson?.state || '') === state;
    }

    const eventsUrl = new URL(`/api/chat/sessions/${encodeURIComponent(sessionId)}/events`, apiBaseUrl).toString();
    const eventsRes = await httpGet(eventsUrl, { timeoutMs: 5000, maxBytes });
    result.observed.sessionEventsStatus = eventsRes.statusCode;
    if (eventsRes.ok && eventsRes.statusCode === 200) {
      const eventsJson = parseJsonSafe(eventsRes.text);
      const events = Array.isArray(eventsJson?.events) ? eventsJson.events : [];
      result.observed.sessionEventsCount = events.length;
      result.checks.sessionEvents =
        String(eventsJson?.sessionId || '') === sessionId && events.length === injectedEvents.length;
    }

    const resumeUrl = new URL('/api/chat/sessions/resume-last', apiBaseUrl).toString();
    const resumeRes = await httpPostSse(resumeUrl, {}, { timeoutMs: 30_000, maxBytes });
    result.observed.resumeStatus = resumeRes.statusCode;
    result.observed.resumeEventTypes = resumeRes.events.map((e) => e.event);
    const errorEvent = resumeRes.events.find((e) => e.event === 'error');
    result.observed.resumeErrorCode = String(errorEvent?.data?.errorCode || '');
    result.checks.resumeErrorCode = result.observed.resumeErrorCode === expectedResumeErrorCode;

    result.pass = result.checks.sessionLast && result.checks.sessionEvents && result.checks.resumeErrorCode;
    if (!result.pass) {
      result.error = 'matrix_check_failed';
    }
  } catch (err) {
    result.error = String(err?.message || err);
  } finally {
    if (runId) {
      try {
        await runCliJson({
          repoRoot,
          devRunnerPath,
          args: buildDevRunnerArgsStop({ runId, force: true }),
          timeoutMs: 60_000,
          mode: 'oneshot',
        });
      } catch (err) {
        result.error = result.error || `stop_failed:${err?.message || err}`;
      }
    }
  }

  return result;
}

async function main() {
  const args = parseArgs(process.argv);
  const { repoRoot, devRunnerPath } = resolveRepoRoot();
  const now = new Date();
  const timestamp = now.toISOString().replace(/[:.]/g, '-');

  const matrixResults = [];
  for (let rep = 1; rep <= args.reps; rep += 1) {
    for (const item of MATRIX_STATES) {
      const dataDir = path.join(
        repoRoot,
        'tmp',
        'dev-runner-data',
        'agent-resume-replay-matrix',
        timestamp,
        `rep-${rep}`,
        item.state.toLowerCase(),
      );
      // eslint-disable-next-line no-await-in-loop
      const run = await runOneCase({
        repoRoot,
        devRunnerPath,
        dataDir,
        state: item.state,
        expectedResumeErrorCode: item.expectedResumeErrorCode,
        startTimeoutMs: args.startTimeoutMs,
        readyTimeoutMs: args.readyTimeoutMs,
        maxBytes: args.maxBytes,
      });
      matrixResults.push({ rep, ...run });
    }
  }

  const passed = matrixResults.filter((r) => r.pass).length;
  const total = matrixResults.length;
  const failed = total - passed;

  const byState = new Map();
  for (const row of matrixResults) {
    if (!byState.has(row.state)) {
      byState.set(row.state, { state: row.state, total: 0, passed: 0, failed: 0 });
    }
    const bucket = byState.get(row.state);
    bucket.total += 1;
    if (row.pass) bucket.passed += 1;
    else bucket.failed += 1;
  }

  const manifest = {
    kind: 'agent-resume-replay-matrix-manifest.v1',
    generatedAt: now.toISOString(),
    config: {
      reps: args.reps,
      states: MATRIX_STATES,
      startTimeoutMs: args.startTimeoutMs,
      readyTimeoutMs: args.readyTimeoutMs,
      maxBytes: args.maxBytes,
    },
    aggregate: {
      total,
      passed,
      failed,
      passRate: total > 0 ? passed / total : 0,
      gatePass: failed === 0,
    },
    byState: [...byState.values()],
    runs: matrixResults,
  };

  await writeJson(args.outJson, manifest);
  console.log(
    JSON.stringify(
      {
        ok: manifest.aggregate.gatePass,
        outJson: path.resolve(args.outJson),
        aggregate: manifest.aggregate,
      },
      null,
      2,
    ),
  );

  if (!manifest.aggregate.gatePass) {
    process.exitCode = 2;
  }
}

main().catch((err) => {
  console.error(err?.stack || String(err));
  process.exit(1);
});
