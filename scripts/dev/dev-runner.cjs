#!/usr/bin/env node
/**
 * Dev runner (Windows-first): start/stop/status/cleanup with durable run-state under tmp/dev-runner/.
 *
 * Contract:
 * - docs/tempdocs/13/02-dev-runner-cli.md
 */
/* eslint-disable no-console */

'use strict';

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const os = require('os');
const net = require('net');
const http = require('http');
const crypto = require('crypto');
const { spawn, spawnSync, execFile } = require('child_process');

const repoRoot = path.resolve(__dirname, '..', '..');
const uiWebDir = path.resolve(repoRoot, 'modules', 'ui-web');
const gradleCmd = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
const gradlePath = path.resolve(repoRoot, gradleCmd);

/**
 * Resolve the main repo root, even when running inside a git worktree.
 * In worktrees, `.git` is a file containing `gitdir: <path>` where path
 * points to `<mainRepo>/.git/worktrees/<name>`. We walk up 3 levels to
 * find the main repo. Falls back to `repoRoot` if detection fails.
 */
function resolveMainRepoRoot() {
  const gitPath = path.join(repoRoot, '.git');
  try {
    const stat = fs.statSync(gitPath);
    if (stat.isFile()) {
      const content = fs.readFileSync(gitPath, 'utf8').trim();
      const match = content.match(/^gitdir:\s*(.+)$/);
      if (match) {
        const gitDir = path.resolve(repoRoot, match[1]);
        return path.resolve(gitDir, '..', '..', '..');
      }
    }
  } catch { /* not a worktree or no .git — fall through */ }
  return repoRoot;
}

const mainRepoRoot = resolveMainRepoRoot();
// State root defaults to the SHARED main-repo location so all worktrees coordinate on one
// lease. Overridable via JUSTSEARCH_DEV_RUNNER_STATE_ROOT for an ISOLATED dev-runner — used by
// integration tests and any throwaway stack that must not touch the shared lease (tempdoc 606
// validation seam). Normally unset in the runner's own env (the runner only EMITS it to the
// Head child below), so the default holds in production.
const stateRoot = process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT
  ? path.resolve(process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT)
  : path.resolve(mainRepoRoot, 'tmp', 'dev-runner');
const runsRoot = path.join(stateRoot, 'runs');
const activePath = path.join(stateRoot, 'active.json');
// Tempdoc 542 §B Layer 2: op-leases.json is Head's lease registry. Single Java writer
// (OperationLeaseServiceImpl); read here at admission time for criticality-aware dispatch.
const opLeasesPath = path.join(stateRoot, 'op-leases.json');
// Tempdoc 606: per-session activity stamps (general + dev-stack touch), written by the
// agent-analytics hooks under the SHARED state root so the supervisor (mainRepoRoot-scoped)
// can read them. Presence/idle grades + the presence-aware renewer join against these.
const sessionsDir = path.join(stateRoot, 'sessions');
const {
  computeOwnershipVerdict,
  classifyActivity,
  readSessionActivity,
  mergeSessionActivity,
  DEFAULT_THRESHOLDS,
} = require('./lib/ownership-verdict.cjs');
const RUN_RETENTION_MS = 14 * 24 * 60 * 60 * 1000;
const RUN_RETENTION_COUNT = 200;

class NoActiveRunError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NoActiveRunError';
    this.code = 'NO_ACTIVE_RUN';
  }
}

function isNoActiveRunError(err) {
  return err instanceof NoActiveRunError || err?.code === 'NO_ACTIVE_RUN' || err?.name === 'NoActiveRunError';
}

function nowIso() {
  return new Date().toISOString();
}

function toPosix(p) {
  return String(p).split(path.sep).join('/');
}

async function mkdirp(p) {
  await fsp.mkdir(p, { recursive: true });
}

async function writeJsonAtomic(filePath, obj) {
  const tmp = `${filePath}.tmp`;
  const json = JSON.stringify(obj, null, 2) + '\n';
  await mkdirp(path.dirname(filePath));
  try {
    await fsp.writeFile(tmp, json, 'utf8');
    await fsp.rename(tmp, filePath);
  } catch (err) {
    // Clean up temp file on failure
    await fsp.rm(tmp, { force: true }).catch(() => { });
    throw err;
  }
}

async function readJsonIfExists(filePath) {
  try {
    const raw = await fsp.readFile(filePath, 'utf8');
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function parseArgs(argv) {
  const out = {
    cmd: null,
    json: false,
    uiPort: 5173,
    apiPort: 0,
    dataDir: null,
    clean: 'soft', // soft|hard|none
    runId: null,
    active: false,
    force: false,
    takeover: 'deny',
    confirmInterrupt: null,
    skipBuild: false,
    hotReload: false,
    sessionId: null,
  };

  const args = [...argv];
  out.cmd = args[0] || null;

  for (let i = 1; i < args.length; i += 1) {
    const token = args[i];
    if (!token.startsWith('--')) continue;
    const [key, inline] = token.split('=', 2);
    const takeValue = () => {
      if (inline != null) return inline;
      const next = args[i + 1];
      if (next == null || next.startsWith('--')) {
        throw new Error(`Missing value for ${key}`);
      }
      i += 1;
      return next;
    };

    switch (key) {
      case '--json':
        out.json = true;
        break;
      case '--ui-port':
        out.uiPort = Number(takeValue());
        break;
      case '--api-port':
        out.apiPort = Number(takeValue());
        break;
      case '--data-dir':
        out.dataDir = takeValue();
        break;
      case '--clean':
        out.clean = String(takeValue() || '').toLowerCase();
        break;
      case '--run':
        out.runId = takeValue();
        break;
      case '--active':
        out.active = true;
        break;
      case '--force':
        out.force = true;
        break;
      case '--takeover':
        out.takeover = takeValue();
        break;
      case '--confirm-interrupt':
        // Tempdoc 542 Layer 4: typed confirmation token matching the live opId; required
        // when `force` interrupts an `unsafe-to-interrupt` op-lease. Prevents typo'd reclaims.
        out.confirmInterrupt = takeValue();
        break;
      case '--skip-build':
        out.skipBuild = true;
        break;
      case '--hot-reload':
        out.hotReload = true;
        break;
      case '--session-id':
        out.sessionId = takeValue();
        break;
      case '--help':
      case '-h':
        out.cmd = 'help';
        break;
      default:
        throw new Error(`Unknown flag: ${key}`);
    }
  }

  if (!['soft', 'hard', 'none'].includes(out.clean)) {
    throw new Error(`Invalid --clean: ${out.clean} (expected soft|hard|none)`);
  }
  if (!Number.isFinite(out.uiPort) || out.uiPort <= 0) throw new Error(`Invalid --ui-port: ${out.uiPort}`);
  if (!Number.isFinite(out.apiPort) || out.apiPort < 0) throw new Error(`Invalid --api-port: ${out.apiPort}`);
  return out;
}

function printUsage() {
  console.error(
    [
      'Usage: node scripts/dev/dev-runner.cjs <command> [options]',
      '',
      'Commands:',
      '  start   [--ui-port 5173] [--api-port 0|33221] [--data-dir <path>] [--clean soft|hard|none] [--json]',
      '  status  [--run <runId>|--active] [--json]',
      '  stop    [--run <runId>|--active] [--force] [--json]',
      '  cleanup [--run <runId>|--active] [--force] [--clean soft|hard|none] [--json]',
      '  doctor  [--json]   report the onramp capability tier / what is missing / next remedy',
      '',
      'Notes:',
      '  - start is a long-running supervisor (foreground). Use Ctrl+C or run stop/cleanup to tear down the stack.',
      '  - When --json is used, stdout prints exactly one JSON object (no extra human logs).',
      '',
      'State:',
      `  ${toPosix(activePath)}`,
      `  ${toPosix(runsRoot)}/<runId>/run.json`,
    ].join('\n'),
  );
}

function resolveDataDir(dataDirArg) {
  if (dataDirArg) {
    const p = path.isAbsolute(dataDirArg) ? path.resolve(dataDirArg) : path.resolve(repoRoot, dataDirArg);
    // Validate path is under repoRoot to prevent path traversal
    const normalized = path.resolve(p);
    const repoNormalized = path.resolve(repoRoot);
    if (!normalized.startsWith(repoNormalized + path.sep) && normalized !== repoNormalized) {
      throw new Error(`--data-dir must be under repo root: ${dataDirArg}`);
    }
    return p;
  }
  return path.resolve(uiWebDir, '.dev-data');
}

async function cleanDataDir(dir, mode) {
  if (mode === 'none') return;
  if (mode === 'hard') {
    // Preserve ui/ so llmModelPath and other UI settings survive a hard reset.
    // Without this, AI activation fails with MODEL_PATH_REQUIRED until the user
    // reconfigures the model path manually.
    const hardKeep = new Set(['ui']);
    await mkdirp(dir);
    const entries = await fsp.readdir(dir).catch(() => []);
    for (const ent of entries) {
      if (hardKeep.has(ent)) continue;
      await fsp.rm(path.join(dir, ent), { recursive: true, force: true }).catch(() => { });
    }
    return;
  }
  // soft: preserve config/index/watched roots/ui settings + AI models/packs/policy + GPL data.
  const keep = new Set([
    'config', 'index', 'watched_roots.json', 'ui',
    'models', 'installed-packs.v1.json', 'policy.v1.json',
    'gpl-training-triples.ndjson',     // GPL training data (hours of LLM work)
    'gpl-eval-snapshot.json',          // GPL eval snapshot (revalidation baseline)
  ]);
  await mkdirp(dir);
  const entries = await fsp.readdir(dir).catch(() => []);
  for (const ent of entries) {
    if (keep.has(ent)) continue;
    await fsp.rm(path.join(dir, ent), { recursive: true, force: true }).catch(() => { });
  }
}

async function getRunDirectoryTimestamp(runDir) {
  const runJson = await readJsonIfExists(path.join(runDir, 'run.json'));
  const candidates = [
    runJson?.updatedAt,
    runJson?.stoppedAt,
    runJson?.startedAt,
  ].filter(Boolean);
  for (const candidate of candidates) {
    const parsed = Date.parse(candidate);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  try {
    const stat = await fsp.stat(runDir);
    return stat.mtimeMs;
  } catch {
    return 0;
  }
}

async function pruneHistoricRuns({
  preserveRunIds = [],
  retentionMs = RUN_RETENTION_MS,
  keepLatestCount = RUN_RETENTION_COUNT,
  runsDirectory = runsRoot,
} = {}) {
  await mkdirp(runsDirectory);
  const preserve = new Set((preserveRunIds || []).filter(Boolean));
  const cutoffMs = Date.now() - retentionMs;
  const entries = await fsp.readdir(runsDirectory, { withFileTypes: true }).catch(() => []);
  const runs = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const runDir = path.join(runsDirectory, entry.name);
    runs.push({
      runId: entry.name,
      runDir,
      timestampMs: await getRunDirectoryTimestamp(runDir),
    });
  }
  runs.sort((left, right) => right.timestampMs - left.timestampMs);

  const toDelete = [];
  for (let index = 0; index < runs.length; index += 1) {
    const run = runs[index];
    const keepBecauseRecent = run.timestampMs >= cutoffMs;
    const keepBecauseCount = index < keepLatestCount;
    const keepBecauseExplicit = preserve.has(run.runId);
    if (keepBecauseRecent || keepBecauseCount || keepBecauseExplicit) {
      continue;
    }
    toDelete.push(run);
  }

  const deletedRunIds = [];
  for (const run of toDelete) {
    await fsp.rm(run.runDir, { recursive: true, force: true }).catch(() => { });
    deletedRunIds.push(run.runId);
  }

  return {
    scanned: runs.length,
    kept: runs.length - deletedRunIds.length,
    deleted: deletedRunIds.length,
    deletedRunIds,
  };
}

/**
 * Tempdoc 656: pure one-time populate of the shared cuda12 GPU runtime. Guarded specifically on the
 * cuda12 exe (NOT "any llama-server runtime") — an existing cuda12 (Install-AI'd or previously staged)
 * is protected, but a stray flat CPU baseline in the same native-bin does NOT block provisioning
 * (that would silently break GPU dev after a stale CPU baseline was left behind). Copies the Gradle
 * cuda stage (exe + adjacent CUDA DLLs) into the shared native-bin. Pure (params + fs) → unit testable.
 *
 * @returns the staged cuda12 exe path if it copied, else null (already present, or no stage source).
 */
function stageSharedCuda12(sharedNativeBin, cudaStageCandidates, exeName) {
  const sharedCuda12 = path.join(sharedNativeBin, 'variants', 'cuda12');
  const sharedCuda12Exe = path.join(sharedCuda12, exeName);
  // Idempotent + don't-clobber, cuda12-SPECIFIC: skip only if a cuda12 runtime is already present.
  if (fs.existsSync(sharedCuda12Exe)) return null;
  const srcDir = cudaStageCandidates.find((d) => fs.existsSync(path.join(d, exeName)));
  if (!srcDir) return null; // no cuda12 built yet — the MCP readiness message reports the remedy
  fs.mkdirSync(sharedCuda12, { recursive: true });
  // Copy the full cuda12 dir (exe + adjacent CUDA DLLs — llama-server loads them from its own dir).
  for (const ent of fs.readdirSync(srcDir, { withFileTypes: true })) {
    if (ent.isDirectory()) continue;
    fs.copyFileSync(path.join(srcDir, ent.name), path.join(sharedCuda12, ent.name));
  }
  return sharedCuda12Exe;
}

/**
 * Tempdoc 656 (Move 1 + Move 2): provision the SHARED GPU (cuda12) llama-server runtime ONCE, at the
 * MAIN checkout, so every worktree references one copy with zero per-worktree download — the same
 * share-from-the-main-checkout property models already have via JUSTSEARCH_MODELS_DIR (see below).
 *
 * This deliberately NO LONGER stages a CPU llama-server baseline (that was tempdoc 618 §3). Per the
 * settled GPU-primary product direction (tempdoc 381: CPU GGUF chat is "not degraded — it's
 * unusable") and tempdoc 656, dev inference is GPU-only: a CPU baseline in dev is a silent fallback
 * that runs the 9B model on CPU (~10x slower + saturates every core → DOSes concurrent worktrees).
 * With no CPU baseline present, inference fails CLOSED (truthful "unavailable" via the runtime
 * manifest's reason codes) instead of silently degrading onto CPU.
 *
 * Populate source: the Gradle cuda stage (`stageLlamaCudaVariant` → build/llama-server/stage/
 * variants/cuda12), produced by a one-time `./gradlew :modules:ui:stageLlamaCudaVariant` at the main
 * checkout. Target: the main checkout's shared native-bin (gitignored). Idempotent; the cuda12-specific
 * guard protects an existing cuda12 while ignoring a stray flat CPU baseline (see stageSharedCuda12).
 */
function ensureSharedCuda12Staged() {
  if (process.platform !== 'win32') return; // prebuilt llama-server staging is Windows-only in dev
  const exeName = 'llama-server.exe';
  // The ONE shared runtime location every worktree references (main checkout, gitignored).
  const sharedNativeBin = path.join(mainRepoRoot, 'modules', 'ui', 'native-bin', 'llama-server');
  // Source: a Gradle-built cuda12 stage (main checkout preferred; worktree accepted as a fallback).
  const cudaStageCandidates = [
    path.join(mainRepoRoot, 'modules', 'ui', 'build', 'llama-server', 'stage', 'variants', 'cuda12'),
    path.join(repoRoot, 'modules', 'ui', 'build', 'llama-server', 'stage', 'variants', 'cuda12'),
  ];
  try {
    const staged = stageSharedCuda12(sharedNativeBin, cudaStageCandidates, exeName);
    if (staged) console.error(`[dev] 656: staged shared cuda12 GPU runtime into ${path.dirname(staged)}`);
  } catch (err) {
    console.error(`[dev] 656: warn — failed to stage shared cuda12 runtime: ${err.message}`);
  }
}

/**
 * Tempdoc 656: pure cuda12-only server-exe resolution — a worktree's own (deliberately Install-AI'd)
 * cuda12 first, else the SHARED main-checkout cuda12. Returns the resolved exe path, or null (→
 * JUSTSEARCH_SERVER_EXE stays unset → inference fails CLOSED). NEVER returns a CPU baseline: dev is
 * GPU-only (a CPU 9B fallback DOSes concurrent worktrees). Pure (params + fs only) so it is unit
 * testable; the anti-regression it guards is "a CPU llama-server never gets resolved in dev."
 */
function resolveCuda12ServerExe(worktreeRoot, sharedRoot, exeName) {
  const cuda12 = ['modules', 'ui', 'native-bin', 'llama-server', 'variants', 'cuda12', exeName];
  const candidates = [
    path.join(worktreeRoot, ...cuda12),   // worktree's own cuda12 (rare — a deliberate local install)
    path.join(sharedRoot, ...cuda12),     // the shared main-checkout cuda12 (the normal path)
  ];
  return candidates.find((p) => fs.existsSync(p)) || null;
}

function resolveAiDevEnv() {
  const env = {};
  // Tempdoc 656: provision the shared cuda12 GPU runtime (once, at the main checkout) so this and
  // every other worktree can reference it. Deliberately NO CPU baseline staging (GPU-only dev).
  ensureSharedCuda12Staged();
  if (!process.env.JUSTSEARCH_SERVER_EXE) {
    const exeName = process.platform === 'win32' ? 'llama-server.exe' : 'llama-server';
    const found = resolveCuda12ServerExe(repoRoot, mainRepoRoot, exeName);
    if (found) env.JUSTSEARCH_SERVER_EXE = found;
  }
  if (!process.env.JUSTSEARCH_MODELS_DIR) {
    // Tempdoc 618 §2: prefer the MAIN checkout's models (holds the LFS binaries) over a
    // worktree's models/ (tracked manifests only), so a worktree dev stack finds real models.
    const mainModels = path.join(mainRepoRoot, 'models');
    const localModels = path.join(repoRoot, 'models');
    if (fs.existsSync(mainModels)) env.JUSTSEARCH_MODELS_DIR = mainModels;
    else if (fs.existsSync(localModels)) env.JUSTSEARCH_MODELS_DIR = localModels;
  }
  // Auto-detect SPLADE model under the resolved models dir (models/splade/naver-splade-v3/)
  if (!process.env.JUSTSEARCH_SPLADE_MODEL_PATH) {
    const modelsBase = env.JUSTSEARCH_MODELS_DIR || path.join(repoRoot, 'models');
    const spladeDir = path.join(modelsBase, 'splade', 'naver-splade-v3');
    const required = ['model.onnx', 'tokenizer.json', 'vocab.txt'];
    if (required.every(f => fs.existsSync(path.join(spladeDir, f)))) {
      env.JUSTSEARCH_SPLADE_MODEL_PATH = spladeDir;
      env.JUSTSEARCH_SPLADE_ENABLED = 'true';
    }
  }
  return env;
}

function resolveHolderSource() {
  if (process.env.JUSTSEARCH_AGENT_SESSION_ID) return 'claude';
  if (process.env.CI) return 'ci';
  return 'unknown';
}

function resolveAgentSessionId(cliSessionId) {
  if (cliSessionId) return cliSessionId;
  const fromEnv = (process.env.JUSTSEARCH_AGENT_SESSION_ID || '').trim();
  if (fromEnv) return fromEnv;
  try {
    const content = fs.readFileSync(
      path.join(repoRoot, 'tmp', 'agent-telemetry', 'current-session-id'),
      'utf8',
    );
    return content.trim() || null;
  } catch { return null; }
}

function resolveOwnerConfidence(agentSessionId, confirmedIbp) {
  if (!agentSessionId) return 'low';
  const fromEnv = (process.env.JUSTSEARCH_AGENT_SESSION_ID || '').trim();
  if (fromEnv && fromEnv === agentSessionId) return confirmedIbp ? 'high' : 'medium';
  return confirmedIbp ? 'medium' : 'medium';
}

function isPidAlive(pid) {
  const n = Number(pid);
  if (!Number.isFinite(n) || n <= 0) return false;
  try { process.kill(n, 0); return true; } catch { return false; }
}

// Tempdoc 606 Piece 2 (provenance): capture, at spawn, WHICH code the launched
// stack actually runs, so an arriving agent (or the owner after edits) can tell a
// stack built from its own worktree from one built elsewhere / a stale dist.
function resolveGitHead() {
  try {
    const r = spawnSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' });
    if (r.status === 0) return r.stdout.trim() || null;
  } catch { /* git unavailable */ }
  return null;
}

/**
 * Content stamp of the launched Head dist (mirrors the Worker's generateBuildStamp,
 * indexer-worker/build.gradle.kts): a short hash over the lib jars' name|size|mtime.
 * Detects both "wrong worktree" (paired with repoRoot) and "stale dist" (jar changed
 * but installDist reported UP-TO-DATE). Null when the dist dir is absent.
 */
function computeHeadDistStamp() {
  try {
    const libDir = path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'lib');
    const files = fs.readdirSync(libDir).filter((f) => f.endsWith('.jar')).sort();
    if (files.length === 0) return null;
    const h = crypto.createHash('sha256');
    for (const f of files) {
      const st = fs.statSync(path.join(libDir, f));
      h.update(`${f}|${st.size}|${Math.round(st.mtimeMs)}\n`);
    }
    return h.digest('hex').slice(0, 16);
  } catch { return null; }
}

/** The provenance block stamped on the lease/run at spawn. */
function resolveProvenance() {
  return {
    repoRoot: toPosix(repoRoot),
    gitHead: resolveGitHead(),
    headDistStamp: computeHeadDistStamp(),
  };
}

function checkHttp200(url, timeoutMs) {
  return new Promise((resolve) => {
    const u = new URL(url);
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
        resolve(res.statusCode === 200);
      },
    );
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', (err) => {
      if (process.env.JUSTSEARCH_DEV_RUNNER_DEBUG) {
        console.error(`[checkHttp200] ${url}: ${err.code || err.message}`);
      }
      resolve(false);
    });
    req.end();
  });
}

function fetchJsonHttp(url, timeoutMs) {
  return new Promise((resolve) => {
    const u = new URL(url);
    const req = http.request(
      { hostname: u.hostname, port: Number(u.port), path: u.pathname + u.search, method: 'GET', timeout: timeoutMs },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          if (res.statusCode !== 200) { resolve(null); return; }
          try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
          catch { resolve(null); }
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', () => resolve(null));
    req.end();
  });
}

function resolveExpectedIndexBasePath(dataDir) {
  const settingsPath = path.join(dataDir, 'ui', 'settings.json');
  try {
    const settings = JSON.parse(fs.readFileSync(settingsPath, 'utf8'));
    if (settings.indexBasePath && typeof settings.indexBasePath === 'string') {
      return { path: path.resolve(settings.indexBasePath), evidence: 'settings_file' };
    }
  } catch { /* no settings file or unreadable */ }
  return { path: path.resolve(dataDir, 'index', 'default'), evidence: 'derived_default' };
}

async function fetchConfirmedIndexBasePath(apiPort) {
  const base = `http://127.0.0.1:${apiPort}`;
  const config = await fetchJsonHttp(`${base}/api/debug/effective-config`, 3000);
  if (config?.keys) {
    const entry = config.keys.find((k) => k.key === 'justsearch.index.base_path');
    if (entry?.value) return { path: entry.value, evidence: 'effective_config', source: entry.source ?? null };
  }
  const status = await fetchJsonHttp(`${base}/api/status`, 3000);
  if (status?.indexBasePath) return { path: status.indexBasePath, evidence: 'status_endpoint' };
  return null;
}

async function waitForBackendReady(apiPort, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  const url = `http://127.0.0.1:${apiPort}/api/status`;
  while (Date.now() < deadline) {
    // eslint-disable-next-line no-await-in-loop
    const ok = await checkHttp200(url, 1200);
    if (ok) return true;
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

function isTcpListening(port, timeoutMs = 400) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let done = false;
    const finish = (v) => {
      if (done) return;
      done = true;
      try {
        sock.destroy();
      } catch (_) { }
      resolve(v);
    };
    sock.setTimeout(timeoutMs);
    sock.once('connect', () => finish(true));
    sock.once('timeout', () => finish(false));
    sock.once('error', () => finish(false));
    sock.connect(port, '127.0.0.1');
  });
}

function execPowerShell(command) {
  return new Promise((resolve) => {
    execFile(
      'powershell',
      ['-NoProfile', '-Command', command],
      { windowsHide: true, maxBuffer: 1024 * 1024 },
      (err, stdout, stderr) => {
        if (err) return resolve({ ok: false, stdout: stdout || '', stderr: stderr || String(err) });
        resolve({ ok: true, stdout: stdout || '', stderr: stderr || '' });
      },
    );
  });
}

function validatePort(port) {
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error(`Invalid port: ${port}`);
  }
  return port;
}

function validatePid(pid) {
  if (!Number.isInteger(pid) || pid <= 0) {
    throw new Error(`Invalid PID: ${pid}`);
  }
  return pid;
}

async function getPortOwnerWindows(port) {
  validatePort(port);
  const cmd = `Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue | Select-Object -First 1`;
  const res = await execPowerShell(cmd);
  if (!res.ok || !res.stdout.trim()) return null;

  // Parse via ConvertTo-Json to avoid formatting issues.
  const res2 = await execPowerShell(
    `Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue | ` +
    `Select-Object -First 1 LocalAddress,LocalPort,OwningProcess | ConvertTo-Json -Compress`,
  );
  if (!res2.ok || !res2.stdout.trim()) return null;
  try {
    const obj = JSON.parse(res2.stdout.trim());
    const pid = Number(obj?.OwningProcess);
    const addr = obj?.LocalAddress ? String(obj.LocalAddress) : null;
    return { pid: Number.isFinite(pid) && pid > 0 ? pid : null, address: addr };
  } catch {
    return null;
  }
}

async function getCommandLineWindows(pid) {
  validatePid(pid);
  const cmd =
    `Get-CimInstance Win32_Process -Filter "ProcessId=${pid}" -ErrorAction SilentlyContinue | ` +
    `Select-Object -First 1 -ExpandProperty CommandLine`;
  const res = await execPowerShell(cmd);
  const line = res.ok ? res.stdout.trim() : '';
  return line || null;
}

async function loadRunById(runId) {
  const runPath = path.join(runsRoot, runId, 'run.json');
  const run = await readJsonIfExists(runPath);
  return run ? { run, runPath } : null;
}

async function resolveRunTarget(opts) {
  if (opts.runId) {
    const loaded = await loadRunById(opts.runId);
    if (!loaded) throw new Error(`Run not found: ${opts.runId}`);
    return loaded;
  }
  const active = await readJsonIfExists(activePath);
  if (!active || !active.runId) throw new NoActiveRunError('No active run (active.json missing).');
  const loaded = await loadRunById(active.runId);
  if (!loaded) throw new Error(`Active run not found: ${active.runId}`);
  return loaded;
}

function spawnLogged(command, args, opts, stdoutStream, stderrStream, splitter) {
  const shellEnabled = !!opts?.shell;
  const argsList = Array.isArray(args) ? args : [];

  const quoteForShell = (value) => {
    const s = String(value ?? '');
    if (process.platform === 'win32') {
      if (s.length === 0) return '""';
      return `"${s.replace(/(["^%&|<>])/g, '^$1')}"`;
    }
    if (s.length === 0) return "''";
    return `'${s.replace(/'/g, `'\\''`)}'`;
  };

  let spawnCommand = command;
  let spawnArgs = argsList;
  // Node DEP0190 warns when passing args with shell=true. Compose a quoted command line instead.
  if (shellEnabled && argsList.length > 0) {
    // DO NOT quote the command executable on Windows. Quoting it forces `cmd.exe` to execute it
    // such that `%~dp0` inside batch files (like npm.cmd) resolves to the current working directory
    // instead of the executable's real path.
    spawnCommand = [command, ...argsList.map(quoteForShell)].join(' ');
    spawnArgs = [];
  }

  const child = spawn(spawnCommand, spawnArgs, opts);

  const writeChunk = (stream, chunk) => {
    try {
      stream.write(chunk);
    } catch (_) {
      // ignore
    }
  };

  if (child.stdout) {
    child.stdout.on('data', (buf) => {
      if (splitter) splitter('stdout', buf);
      else writeChunk(stdoutStream, buf);
    });
  }
  if (child.stderr) {
    child.stderr.on('data', (buf) => {
      if (splitter) splitter('stderr', buf);
      else writeChunk(stderrStream, buf);
    });
  }
  return child;
}

const lockPath = path.join(stateRoot, 'active.lock.json');

/**
 * Tempdoc 542 §B Layer 2: read op-leases.json (Head's lease registry) and return ACTIVE entries
 * — i.e., entries whose expiresAt is in the future. The Head-side OperationLeaseService writes
 * this file; stale entries are also reaped Head-side on every write, but the admission gate
 * runs its own expiry filter as a belt-and-suspenders against time skew or crashed Head.
 *
 * Returns: { entries: [...], byCriticality: { mustComplete: [...], unsafeToInterrupt: [...] } }.
 */
async function readActiveOpLeases(overridePath = null) {
  const doc = await readJsonIfExists(overridePath ?? opLeasesPath);
  if (!doc || !Array.isArray(doc.opLeases)) {
    return { entries: [], byCriticality: { mustComplete: [], unsafeToInterrupt: [], interruptibleWithLoss: [] } };
  }
  const now = Date.now();
  const active = doc.opLeases.filter((e) => {
    if (!e?.expiresAt) return false;
    const t = new Date(e.expiresAt).getTime();
    return Number.isFinite(t) && t > now;
  });
  const byCriticality = {
    mustComplete: active.filter((e) => e.criticality === 'MUST_COMPLETE'),
    unsafeToInterrupt: active.filter((e) => e.criticality === 'UNSAFE_TO_INTERRUPT'),
    interruptibleWithLoss: active.filter((e) => e.criticality === 'INTERRUPTIBLE_WITH_LOSS'),
  };
  return { entries: active, byCriticality };
}

async function acquireAdmission({ takeover = 'deny', sessionId, confirmInterrupt = null } = {}) {
  // Step 1: Acquire sidecar lockfile with exclusive create (271 A3.3)
  const lockPayload = JSON.stringify({ pid: process.pid, acquiredAt: nowIso() });
  try {
    await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
  } catch (err) {
    if (err.code !== 'EEXIST') throw err;
    // Lock exists — check if holder is alive
    let existingLock;
    try {
      existingLock = JSON.parse(await fsp.readFile(lockPath, 'utf8'));
    } catch {
      // Unreadable lock — remove and retry once
      await fsp.rm(lockPath, { force: true });
      await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
      existingLock = null;
    }
    if (existingLock) {
      // Lock is only held for milliseconds during admission. If it's older than 2 minutes,
      // the holder crashed — treat as stale regardless of PID liveness (prevents PID-reuse deadlock).
      const lockAgeMs = existingLock.acquiredAt
        ? Date.now() - new Date(existingLock.acquiredAt).getTime()
        : Infinity;
      if (lockAgeMs < 120_000 && isPidAlive(existingLock.pid)) {
        return { action: 'conflict', reason: 'lock_held', holder: existingLock };
      }
      // Dead or stale holder — remove lock and re-acquire
      await fsp.rm(lockPath, { force: true });
      await fsp.writeFile(lockPath, lockPayload, { flag: 'wx' });
    }
  }

  // We now hold the admission lock. Everything below must be in try/finally.
  try {
    const active = await readJsonIfExists(activePath);

    // Gather facts for the single ownership-verdict authority (tempdoc 606).
    const leaseExpired = active?.lease?.expiresAt
      ? new Date(active.lease.expiresAt) < new Date()
      : true; // No lease → treat as stale (pre-271 format)
    let runJson = null;
    if (active?.runPath) {
      try {
        runJson = JSON.parse(await fsp.readFile(
          path.join(mainRepoRoot, active.runPath), 'utf8'));
      } catch { /* run.json missing or unreadable */ }
    }
    const ownerPid = runJson?.pids?.runnerPid ?? null;
    const supervisorAlive = ownerPid ? isPidAlive(ownerPid) : false;
    // Tempdoc 542 §B Layer 4: op-lease registry (criticality-aware dispatch).
    const opLeases = await readActiveOpLeases();
    // Tempdoc 606 D1: owner-session activity (presence/idle grades).
    const ownerActivity = readSessionActivity(sessionsDir, active?.holder?.agentSessionId);

    // The ONE decision. selfCheck:false on the gate — the CLI applies takeover
    // policy regardless of caller (self-owner handling lives in the MCP layer);
    // this preserves pre-606 gate semantics.
    const decision = computeOwnershipVerdict({
      active,
      selfCheck: false,
      supervisorAlive,
      leaseExpired,
      ownerActivity,
      opLeases,
      takeover,
      confirmInterrupt,
      now: Date.now(),
    });

    if (decision.action === 'conflict') {
      process.stderr.write(
        `[dev-runner] Admission conflict (${decision.verdict}/${decision.reason}) for run ${active?.runId}\n`);
      return {
        action: 'conflict',
        reason: decision.reason,
        verdict: decision.verdict,
        holder: active?.holder ?? null,
        lease: active?.lease ?? null,
        runId: active?.runId ?? null,
        ...(decision.criticalOps ? { criticalOps: decision.criticalOps } : {}),
        ...(decision.message ? { message: decision.message } : {}),
        resourceClaims: runJson?.resourceClaims ?? null,
        recommendedAction: decision.recommendedAction,
      };
    }

    // proceed
    if (decision.disposition) {
      process.stderr.write(
        `[dev-runner] Admission proceed (${decision.verdict}/${decision.disposition}) ` +
        `over run ${active?.runId} owned by ${active?.holder?.source ?? 'unknown'}\n`);
      await stopRun({
        runId: active.runId,
        disposition: decision.disposition,
        actor: { source: resolveHolderSource(), agentSessionId: resolveAgentSessionId(sessionId) },
        victim: decision.victim,
        ...(decision.criticalOpsInterrupted ? { criticalOpsInterrupted: decision.criticalOpsInterrupted } : {}),
        ...(decision.interruptibleWithLossInterrupted
          ? { interruptibleWithLossInterrupted: decision.interruptibleWithLossInterrupted } : {}),
      }).catch(() => {});
    }
    return {
      action: 'proceed',
      verdict: decision.verdict,
      ...(decision.disposition ? { disposition: decision.disposition } : {}),
      ...(decision.victim ? { victim: decision.victim } : {}),
      ...(decision.criticalOpsInterrupted ? { criticalOpsInterrupted: decision.criticalOpsInterrupted } : {}),
      ...(decision.interruptibleWithLossInterrupted
        ? { interruptibleWithLossInterrupted: decision.interruptibleWithLossInterrupted } : {}),
    };
  } finally {
    await fsp.rm(lockPath, { force: true }).catch(() => {});
  }
}

async function cmdStart(opts) {
  // Tempdoc 606 3a: ownership epoch — monotonic, bumped on every custody episode (each
  // start replaces the prior holder). Read BEFORE admission (stopRun may clear active.json).
  const _priorActive = await readJsonIfExists(activePath);
  const ownershipEpoch = (Number(_priorActive?.ownershipEpoch) || 0) + 1;
  // Lease-aware admission: check ownership before starting (271, extended by 542 §B Layer 4)
  const admission = await acquireAdmission({
    takeover: opts.takeover,
    sessionId: opts.sessionId,
    confirmInterrupt: opts.confirmInterrupt,
  });
  if (admission.action === 'conflict') {
    // Tempdoc 542 §B Layer 4: criticality-aware error codes.
    //   handshake_required        — MUST_COMPLETE or UNSAFE_TO_INTERRUPT op-lease blocks `warn`
    //   requires_confirmation     — `force` against UNSAFE_TO_INTERRUPT without --confirm-interrupt
    //   fresh_owner / lock_held   — routine OWNER_CONFLICT (existing semantics)
    const code = admission.reason === 'handshake_required'
        ? 'HANDSHAKE_REQUIRED'
        : admission.reason === 'requires_confirmation'
            ? 'REQUIRES_CONFIRMATION'
            : 'OWNER_CONFLICT';
    let message;
    if (admission.reason === 'handshake_required') {
      message = admission.message
          ?? `Backend has ${admission.criticalOps?.length ?? 0} critical op-lease(s) active`;
    } else if (admission.reason === 'requires_confirmation') {
      message = admission.message ?? 'force-interrupt requires --confirm-interrupt=<opId>';
    } else {
      message = `Backend owned by ${admission.holder?.source ?? 'unknown'}` +
          (admission.reason === 'lock_held'
            ? ' (admission lock held by another process)'
            : ' (fresh lease, use --takeover=warn to override)');
    }
    const conflict = {
      ok: false,
      error: {
        code,
        message,
        holder: admission.holder ?? null,
        lease: admission.lease ?? null,
        runId: admission.runId ?? null,
        resourceClaims: admission.resourceClaims ?? null,
        ...(admission.criticalOps ? { criticalOps: admission.criticalOps } : {}),
      },
    };
    if (opts.json) {
      process.stdout.write(JSON.stringify(conflict) + '\n');
    } else {
      process.stderr.write(`[dev-runner] ${conflict.error.message}\n`);
    }
    return;
  }

  const activeAfterStop = await readJsonIfExists(activePath);
  await pruneHistoricRuns({
    preserveRunIds: [admission.victim?.runId, activeAfterStop?.runId].filter(Boolean),
  }).catch(() => { });

  const runId = crypto.randomUUID();
  const startedAt = nowIso();
  const uiPort = opts.uiPort;
  const apiPortRequested = opts.apiPort;
  const dataDir = resolveDataDir(opts.dataDir);

  const runDir = path.join(runsRoot, runId);
  const logsDir = path.join(runDir, 'logs');
  await mkdirp(logsDir);

  const backendStdoutPath = path.join(logsDir, 'backend.stdout.log');
  const backendStderrPath = path.join(logsDir, 'backend.stderr.log');
  const frontendStdoutPath = path.join(logsDir, 'frontend.stdout.log');
  const frontendStderrPath = path.join(logsDir, 'frontend.stderr.log');

  const backendStdout = fs.createWriteStream(backendStdoutPath, { flags: 'a' });
  const backendStderr = fs.createWriteStream(backendStderrPath, { flags: 'a' });
  const frontendStdout = fs.createWriteStream(frontendStdoutPath, { flags: 'a' });
  const frontendStderr = fs.createWriteStream(frontendStderrPath, { flags: 'a' });

  // Session-scoped clean gate: reject clean if another session owns the stack
  if (opts.clean !== 'none') {
    const active = await readJsonIfExists(activePath);
    if (active && active.holder?.agentSessionId) {
      const callerSession = resolveAgentSessionId(opts.sessionId);
      if (callerSession && callerSession !== active.holder.agentSessionId) {
        const err = new Error('OWNER_CONFLICT: clean rejected — another session owns the stack');
        err.code = 'OWNER_CONFLICT';
        err.holder = active.holder;
        err.lease = active.lease;
        throw err;
      }
    }
  }
  await cleanDataDir(dataDir, opts.clean);

  const aiEnv = resolveAiDevEnv();

  // Ensure distribution is up-to-date before direct launch (S7: bypass Gradle at runtime).
  // Config-cached assemble: ~3s warm, ~15s cold. Skippable with --skip-build when dist is known-good.
  if (!opts.skipBuild) {
    process.stderr.write('[dev-runner] Ensuring distribution is up-to-date (assemble)...\n');
    const buildResult = spawnSync(
      gradlePath,
      ['assemble', '-PskipWebBuild=true'],
      { cwd: repoRoot, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'inherit'] },
    );
    if (buildResult.status !== 0) {
      throw new Error(`Gradle assemble failed with exit code ${buildResult.status}`);
    }
  }

  // Launch directly from installDist output instead of `gradlew runHeadless`.
  // The start script includes the correct classpath and JVM args; env vars are inherited.
  const headDistBin = path.join(repoRoot, 'modules', 'ui', 'build', 'install', 'ui', 'bin');
  const startScript = process.platform === 'win32'
    ? path.join(headDistBin, 'ui.bat')
    : path.join(headDistBin, 'ui');

  // S1: Dev-mode AOT cache — pass -XX:AOTCache= to the Head JVM if available.
  // The cache is generated by: ./gradlew generateDevHeadAotCache
  let headAotOpts = '';
  const headAotCache = path.resolve(repoRoot, 'modules', 'ui', 'build', 'aot-dev', 'head', 'head.aot');
  if (fs.existsSync(headAotCache)) {
    headAotOpts = `-XX:AOTCache=${headAotCache}`;
    process.stderr.write(`[dev-runner] Using dev AOT cache: ${headAotCache}\n`);
  }

  // Fail fast if the Head dist doesn't exist (e.g. --skip-build without prior installDist).
  // Without this check, spawn() fails silently and the only feedback is a 60s timeout.
  if (!fs.existsSync(startScript)) {
    const gradleCmd = process.platform === 'win32' ? './gradlew.bat' : './gradlew';
    throw new Error(
      `Head dist not found at ${startScript}. Make this checkout dev-ready (tempdoc 618 §3):\n` +
        `  node scripts/dev/prepare-worktree.cjs           # one command: npm ci + both installDists\n` +
        `  or: ${gradleCmd} :modules:ui:installDist :modules:indexer-worker:installDist\n` +
        `Then retry start (or drop --skip-build to build automatically).`,
    );
  }

  // Tempdoc 606 Piece 2: capture provenance of the dist we are about to launch.
  // installDist has run by now, so the lib dir exists for the content stamp.
  const devStackProvenance = resolveProvenance();
  process.stderr.write(
    `[dev-runner] Launching dist: repoRoot=${devStackProvenance.repoRoot} ` +
    `gitHead=${devStackProvenance.gitHead ?? '?'} headDistStamp=${devStackProvenance.headDistStamp ?? '?'}\n`);

  const spawnBackend = {
    cwd: repoRoot,
    command: startScript,
    args: [],
    shell: process.platform === 'win32',
  };

  let apiPortActual = apiPortRequested;
  let portEmitted = false;

  // Tempdoc 501 §3.1 closure-pass: stdout was previously parsed for
  // JUSTSEARCH_API_PORT=<n> as a fast-path discovery channel. That violates
  // the design's closure rule ("one mechanism per concern" — manifest is
  // the canonical discovery path). The stdout line still flows to the log
  // for human observation; only the consumer-side parse-to-state is gone.
  // Port comes exclusively from <dataDir>/runtime/manifest.json read in the
  // wait loop below.

  const backend = spawnLogged(
    spawnBackend.command,
    spawnBackend.args,
    {
      cwd: spawnBackend.cwd,
      env: {
        ...process.env,
        ...aiEnv,
        JUSTSEARCH_API_PORT: String(apiPortRequested),
        JUSTSEARCH_DATA_DIR: dataDir,
        JUSTSEARCH_HOME: dataDir,
        // Tempdoc 542 §B Layer 3: Head reads this to know where to write op-leases.json.
        // Absent → Head's OperationLeaseService is a no-op (production / non-dev-runner).
        JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
        // Hot-reload: enable JDWP + DevReloadManager on Worker (tempdoc 305)
        ...(opts.hotReload ? {
          JUSTSEARCH_DEV_HOTRELOAD: 'true',
          JUSTSEARCH_DEV_DEBUG_PORT: String(process.env.JUSTSEARCH_DEV_DEBUG_PORT || '5005'),
        } : {}),
        // Head startup flags: SerialGC (small heap, no throughput need),
        // -XX:-UsePerfData (skip hsperfdata file).
        // TieredStopAtLevel=1 is only used when AOT cache is absent — it conflicts
        // with AOT (bypasses C2, wasting the pre-linked classes and method profiles).
        // S1: Pass dev AOT cache flag when available.
        JAVA_OPTS: [
          process.env.JAVA_OPTS,
          headAotOpts ? '-XX:+UseSerialGC -XX:-UsePerfData' : '-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData',
          headAotOpts,
          // Tempdoc 606 Piece 2b: the Head echoes this on /api/runtime/manifest so a
          // stale old Head answering on a reused port is detectable (build mismatch).
          devStackProvenance.headDistStamp ? `-Djustsearch.head.stamp=${devStackProvenance.headDistStamp}` : null,
        ].filter(Boolean).join(' '),
        // NOTE: justsearch.repo.root is NOT set here. In Tauri production, lib.rs sets it to
        // headless_dir where sidecar ONNX models live. In dev mode, OnnxModelDiscovery's sidecar
        // step is a no-op, so reranker/citation-scorer are inactive. To enable them, set
        // JUSTSEARCH_RERANK_MODEL_PATH and JUSTSEARCH_CITATION_SCORER_MODEL_PATH explicitly.
      },
      shell: spawnBackend.shell,
      windowsHide: spawnBackend.shell,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
    backendStdout,
    backendStderr,
    (streamKind, buf) => {
      // Tempdoc 501 §3.1: stdout is for human-readable logs only; discovery
      // happens through <dataDir>/runtime/manifest.json (read in wait loop).
      if (streamKind === 'stdout') {
        backendStdout.write(buf);
      } else {
        backendStderr.write(buf);
      }
    },
  );

  const spawnFrontend = () => ({
    cwd: uiWebDir,
    command: 'npm',
    args: ['run', 'dev', '--', '--host', '--port', String(uiPort), '--strictPort'],
    shell: process.platform === 'win32',
  });

  const readTimeoutMs = (envKey, fallbackMs) => {
    const raw = process.env[envKey];
    if (raw == null || String(raw).trim() === '') return fallbackMs;
    const n = Number(String(raw).trim());
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : fallbackMs;
  };

  // Self-hosted runners can have cold-start builds (Gradle) that exceed 60s.
  // Use a longer default in CI, and allow explicit override via env vars.
  // Local: direct launch ~1.5s + app startup ~4.5s = ~6s to port emit,
  //        ~38s to worker ready (measured Mar 2026, tempdoc 275 S7).
  const defaultPortEmitTimeoutMs = process.env.CI ? 300_000 : 15_000;
  // GPU model initialization (ONNX CUDA session creation) routinely takes >60s.
  // Auto-detect GPU intent from env vars and use a longer default.
  const gpuRequested = !!(
    (process.env.JUSTSEARCH_EMBED_GPU_LAYERS && process.env.JUSTSEARCH_EMBED_GPU_LAYERS !== '0')
    || process.env.JUSTSEARCH_SPLADE_GPU_ENABLED === 'true'
    || (process.env.JUSTSEARCH_GPU_LAYERS && process.env.JUSTSEARCH_GPU_LAYERS !== '0')
  );
  const defaultBackendReadyTimeoutMs = process.env.CI ? 300_000
    : gpuRequested ? 180_000
    : 60_000;
  const portEmitTimeoutMs = readTimeoutMs('JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS', defaultPortEmitTimeoutMs);
  const backendReadyTimeoutMs = readTimeoutMs('JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS', defaultBackendReadyTimeoutMs);

  // Tempdoc 501 Phase 6: HeadlessApp writes <dataDir>/runtime/manifest.json
  // (the producer-published runtime manifest) and the legacy
  // <dataDir>/runtime/api-port.txt (thin mirror, to be removed in Phase 8).
  // Both serve as fallbacks when stdout piping is delayed; the manifest is
  // preferred because it carries instanceId (cross-linked into run.json so
  // restarts are detectable across orchestrator views).
  //
  // Delete any stale files from a previous run to prevent reading the wrong
  // port when using --clean=none (the previous backend may have bound a
  // different ephemeral port).
  const runtimeDir = path.join(dataDir, 'runtime');
  const manifestPath = path.join(runtimeDir, 'manifest.json');
  // Pre-spawn cleanup. api-port.txt is the deprecated mirror (Phase 8) but
  // we still unlink it so a stale --clean=none restart doesn't leave a
  // misleading file around for any legacy consumer.
  try { fs.unlinkSync(path.join(runtimeDir, 'api-port.txt')); } catch { /* ok if absent */ }
  try { fs.unlinkSync(manifestPath); } catch { /* ok if absent */ }

  let manifestInstanceId = null;
  const tryReadManifest = () => {
    try {
      const content = fs.readFileSync(manifestPath, 'utf8');
      const parsed = JSON.parse(content);
      const p = parsed?.head?.apiPort;
      if (Number.isFinite(p) && p > 0) {
        manifestInstanceId = parsed.instanceId ?? null;
        return p;
      }
    } catch { /* not yet written or malformed */ }
    return 0;
  };

  // Tempdoc 501 §3.1: manifest is the sole discovery path. The legacy
  // api-port.txt fallback the wait-loop used to consult was dead code in
  // the dev-runner context (the worktree always builds the current
  // HeadlessApp, which writes both files); removing it tightens the
  // closure ("one mechanism per concern").
  const waitForPortDeadline = Date.now() + portEmitTimeoutMs;
  while ((apiPortRequested <= 0 || apiPortActual <= 0) && Date.now() < waitForPortDeadline) {
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 100));
    if (!portEmitted && apiPortActual <= 0) {
      const p = tryReadManifest();
      if (p > 0) {
        apiPortActual = p;
        portEmitted = true;
      }
    }
  }

  if (!Number.isFinite(apiPortActual) || apiPortActual <= 0) {
    const seconds = Math.max(1, Math.round(portEmitTimeoutMs / 1000));
    throw new Error(
      `Backend did not emit JUSTSEARCH_API_PORT=<port> within ${seconds}s (requested=${apiPortRequested}).`,
    );
  }

  const apiBaseUrl = `http://127.0.0.1:${apiPortActual}`;
  const uiUrl = `http://localhost:${uiPort}`;

  const readyHttp = await waitForBackendReady(apiPortActual, backendReadyTimeoutMs);
  if (!readyHttp) {
    const seconds = Math.max(1, Math.round(backendReadyTimeoutMs / 1000));
    throw new Error(`Backend did not become ready at ${apiBaseUrl}/api/status within ${seconds}s`);
  }

  // indexBasePath capture (271 stage 4)
  const expectedIbp = resolveExpectedIndexBasePath(dataDir);
  let confirmedIbp = null;
  try {
    confirmedIbp = await fetchConfirmedIndexBasePath(apiPortActual);
  } catch { /* best-effort */ }

  const spawnF = spawnFrontend();
  const frontend = spawnLogged(
    spawnF.command,
    spawnF.args,
    {
      cwd: spawnF.cwd,
      env: {
        ...process.env,
        VITE_JUSTSEARCH_API_PORT: String(apiPortActual),
        VITE_API_PORT: String(apiPortActual),
      },
      shell: spawnF.shell,
      windowsHide: spawnF.shell,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
    frontendStdout,
    frontendStderr,
    null,
  );

  const runJson = {
    schemaVersion: 1,
    runId,
    startedAt,
    apiPortRequested: apiPortRequested,
    apiPortActual,
    uiPortRequested: uiPort,
    uiPortActual: uiPort,
    apiBaseUrl,
    uiUrl,
    dataDir: toPosix(dataDir),
    repoRoot: toPosix(repoRoot),
    spawn: {
      backend: { cwd: toPosix(spawnBackend.cwd), command: path.basename(spawnBackend.command), args: spawnBackend.args, shell: spawnBackend.shell },
      frontend: { cwd: toPosix(spawnF.cwd), command: spawnF.command, args: spawnF.args, shell: spawnF.shell },
    },
    pids: {
      runnerPid: process.pid,
      backendRootPid: backend.pid,
      frontendRootPid: frontend.pid,
    },
    logs: {
      backendStdout: toPosix(path.relative(repoRoot, backendStdoutPath)),
      backendStderr: toPosix(path.relative(repoRoot, backendStderrPath)),
      frontendStdout: toPosix(path.relative(repoRoot, frontendStdoutPath)),
      frontendStderr: toPosix(path.relative(repoRoot, frontendStderrPath)),
    },
    debug: {
      // Tempdoc 501 §3.1 closure: the historic `portLine` field carried the
      // raw JUSTSEARCH_API_PORT= stdout line that the dev-runner parsed for
      // discovery. Stdout-as-discovery is gone (manifest is the canonical
      // path); we record the manifest's instance identity here instead so
      // run.json still has the equivalent "what did we observe at startup"
      // breadcrumb for post-hoc audit.
      portSource: manifestInstanceId ? 'runtime-manifest' : 'unresolved',
      portSourceInstanceId: manifestInstanceId,
    },
    owner: (() => {
      const sid = resolveAgentSessionId(opts.sessionId);
      return {
        source: resolveHolderSource(),
        agentSessionId: sid,
        confidence: resolveOwnerConfidence(sid, confirmedIbp),
      };
    })(),
    resourceClaims: {
      apiPort: apiPortActual,
      uiPort,
      dataDir: toPosix(dataDir),
      justsearchHome: toPosix(dataDir),
      settingsStorePath: toPosix(path.join(dataDir, 'ui', 'settings.json')),
      runtimeDir: toPosix(path.join(dataDir, 'runtime')),
      workerConfigSnapshotPath: toPosix(path.join(dataDir, 'runtime', 'worker-config-snapshot.json')),
      // Tempdoc 501 §3.7: cross-link the orchestrator's run.json with the
      // producer-published manifest's instanceId. Restarts changing instanceId
      // are detectable from either view; stale orchestrator state becomes a
      // mechanical instanceId mismatch rather than a silent re-bind.
      runtimeManifestPath: toPosix(path.join(dataDir, 'runtime', 'manifest.json')),
      runtimeManifestInstanceId: (() => {
        // Re-read at write-time so we capture the manifest the producer wrote
        // (the in-loop read above may have missed it if stdout fired first).
        try {
          const content = fs.readFileSync(path.join(dataDir, 'runtime', 'manifest.json'), 'utf8');
          const parsed = JSON.parse(content);
          return parsed?.instanceId ?? manifestInstanceId;
        } catch {
          return manifestInstanceId;
        }
      })(),
      expectedIndexBasePath: toPosix(expectedIbp.path),
      expectedIndexBasePathEvidence: expectedIbp.evidence,
      confirmedIndexBasePath: confirmedIbp ? toPosix(confirmedIbp.path) : null,
      confirmedIndexBasePathEvidence: confirmedIbp?.evidence ?? null,
      confirmedIndexBasePathSource: confirmedIbp?.source ?? null,
    },
    cleanupPolicy: 'shared-stack',
  };

  const runPath = path.join(runDir, 'run.json');
  await writeJsonAtomic(runPath, runJson);
  const leaseNow = nowIso();
  const holderSessionId = resolveAgentSessionId(opts.sessionId);
  await writeJsonAtomic(activePath, {
    kind: 'backend-shared-lease.v1',
    schemaVersion: 1,
    runId,
    runPath: toPosix(path.relative(repoRoot, runPath)),
    launcherFamily: 'dev-runner',
    mode: 'shared',
    holder: {
      source: resolveHolderSource(),
      agentSessionId: holderSessionId,
    },
    takeoverPolicy: 'warn',
    // Tempdoc 606 3a: ownership epoch (bumps on each custody transfer → notification basis).
    ownershipEpoch,
    // Tempdoc 606 Piece 2: provenance of the code this stack actually runs.
    provenance: devStackProvenance,
    lease: {
      durationSec: 30,
      renewedAt: leaseNow,
      expiresAt: new Date(Date.now() + 30_000).toISOString(),
      sequence: 1,
    },
    updatedAt: leaseNow,
  });
  // Tempdoc 606 3a: record the epoch this holder acquired, so if it is later displaced
  // it can detect that (pull-at-next-action notification via the ownership projection).
  if (holderSessionId) mergeSessionActivity(sessionsDir, holderSessionId, { ownedEpoch: ownershipEpoch });

  const startResult = {
    ok: true,
    runId,
    apiPort: apiPortActual,
    uiPort,
    apiBaseUrl,
    uiUrl,
    dataDir: toPosix(dataDir),
    pids: runJson.pids,
    readiness: { ready_http: true },
  };

  if (opts.json) {
    process.stdout.write(JSON.stringify(startResult) + '\n');
  } else {
    console.error(`Started run ${runId}`);
    console.error(`Backend: ${apiBaseUrl}/api/status`);
    console.error(`Frontend: ${uiUrl}`);
    console.error(`Logs: ${toPosix(path.relative(repoRoot, logsDir))}/`);
  }

  let renewalInterval;

  const onExit = () => {
    clearInterval(renewalInterval);
    try {
      backendStdout.end();
      backendStderr.end();
      frontendStdout.end();
      frontendStderr.end();
    } catch (_) { }
  };

  let shuttingDown = false;

  const shutdown = () => {
    // Guard against double-shutdown (e.g., SIGINT followed by SIGTERM)
    if (shuttingDown) return;
    shuttingDown = true;
    clearInterval(renewalInterval);

    // Close log streams first to flush buffered data
    try {
      backendStdout.destroy();
      backendStderr.destroy();
      frontendStdout.destroy();
      frontendStderr.destroy();
    } catch (_) { }

    // Best-effort: delegate cleanup to stop command; but ensure we don't strand children on Ctrl+C.
    // Note: This function is intentionally synchronous - signal handlers cannot await async functions.
    // spawn() returns immediately, so the cleanup is fire-and-forget.
    try {
      if (process.platform === 'win32') {
        // taskkill /T kills entire process tree
        spawn('taskkill', ['/PID', String(process.pid), '/T', '/F'], {
          stdio: 'ignore',
          windowsHide: true,
          detached: true,
        });
      } else {
        // Send SIGTERM to children - they should exit cleanly
        try { process.kill(backend.pid, 'SIGTERM'); } catch (_) { }
        try { process.kill(frontend.pid, 'SIGTERM'); } catch (_) { }
      }
    } catch (_) { }
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  let leaseSequence = 1;
  // Tempdoc 606 3b: set while the reaper deliberately tears the stack down, so the
  // backend/frontend exit handlers below don't process.exit() mid-cleanup (killing the
  // supervisor before stopRun clears active.json + writes the reaped_abandoned report).
  let reaping = false;
  renewalInterval = setInterval(async () => {
    try {
      leaseSequence += 1;
      const current = await readJsonIfExists(activePath);
      if (current?.runId !== runId) return;
      // Tempdoc 606 1d: presence-aware renewal. The lease is renewed by THIS detached
      // supervisor, decoupled from the owning agent session — so freshness is a false
      // liveness signal. Re-couple it: if the owner's general activity has gone stale
      // (session ended/crashed/silent), STOP renewing so the lease expires and the
      // existing stale-reclaim admission path frees the stack. Absence of any activity
      // stamp is treated as present (conservative — never reap on missing signal).
      const ownerActivity = readSessionActivity(sessionsDir, current?.holder?.agentSessionId);
      const { known, generalStale } = classifyActivity(ownerActivity, Date.now(), DEFAULT_THRESHOLDS);
      if (known && generalStale) {
        // Tempdoc 606 3b reaper: after a grace period of total silence, the supervisor
        // self-terminates to free VRAM/RAM/ports (a zombie stack from a long-gone session
        // is a real cost on a memory-pressured single-GPU host). Until grace, just pause
        // renewal so the lease lapses and a waiter polling the verdict can reclaim sooner.
        const lastT = ownerActivity?.lastActivityAt ? new Date(ownerActivity.lastActivityAt).getTime() : 0;
        const abandonedMs = Date.now() - lastT;
        const REAPER_GRACE_MS = Number(process.env.JUSTSEARCH_DEV_REAPER_GRACE_MS) > 0
          ? Number(process.env.JUSTSEARCH_DEV_REAPER_GRACE_MS)
          : 5 * 60_000;
        if (abandonedMs > DEFAULT_THRESHOLDS.abandonedAfterMs + REAPER_GRACE_MS) {
          process.stderr.write(
            `[dev-runner] Reaping abandoned stack (owner silent ${Math.round(abandonedMs / 1000)}s, ` +
            `no successor) — freeing resources.\n`);
          reaping = true; // suppress the backend-exit auto-exit so stopRun finishes cleanup
          clearInterval(renewalInterval);
          await stopRun({
            runId,
            disposition: 'reaped_abandoned',
            actor: { source: 'dev-runner', agentSessionId: current?.holder?.agentSessionId ?? null },
            victim: { runId, holder: current?.holder ?? null },
          }).catch(() => {});
          process.exit(0); // cleanup done (active.json removed + stop-report written)
          return;
        }
        process.stderr.write(
          `[dev-runner] Owner ${current?.holder?.agentSessionId ?? '?'} is silent — ` +
          `pausing lease renewal so the stack can be reclaimed.\n`);
        return; // skip this renewal; lease lapses within its 30s TTL
      }
      const now = nowIso();
      await writeJsonAtomic(activePath, {
        ...current,
        lease: {
          durationSec: 30,
          renewedAt: now,
          expiresAt: new Date(Date.now() + 30_000).toISOString(),
          sequence: leaseSequence,
        },
        updatedAt: now,
      });
    } catch (_) { /* best-effort renewal */ }
  }, 10_000);

  backend.on('exit', (code) => {
    if (reaping) return; // deliberate reap owns teardown + exit (avoids racing stopRun cleanup)
    onExit();
    if (code != null && code !== 0) process.exit(code);
    process.exit(0);
  });
  frontend.on('exit', (code) => {
    if (reaping) return; // deliberate reap owns teardown + exit
    onExit();
    if (code != null && code !== 0) process.exit(code);
    process.exit(0);
  });

  // Keep runner alive as a supervisor.
  // eslint-disable-next-line no-empty
  await new Promise(() => { });
}

async function cmdStatus(opts) {
  let run = null;
  try {
    ({ run } = await resolveRunTarget(opts));
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(
        JSON.stringify({ ok: false, runId: null, error: { code: 'NO_ACTIVE_RUN', message: 'No active run' } }) + '\n',
      );
      return;
    }
    throw err;
  }
  const apiPortRaw = Number(run?.apiPortActual);
  const uiPortRaw = Number(run?.uiPortActual);
  // Treat invalid ports as 0 (not listening) rather than letting NaN propagate
  const apiPort = Number.isFinite(apiPortRaw) && apiPortRaw > 0 ? apiPortRaw : 0;
  const uiPort = Number.isFinite(uiPortRaw) && uiPortRaw > 0 ? uiPortRaw : 0;

  const alive = {
    runner: isPidAlive(run?.pids?.runnerPid),
    backendRoot: isPidAlive(run?.pids?.backendRootPid),
    frontendRoot: isPidAlive(run?.pids?.frontendRootPid),
  };

  const ports = {
    api: { port: apiPort, listening: apiPort > 0 ? await isTcpListening(apiPort) : false },
    ui: { port: uiPort, listening: uiPort > 0 ? await isTcpListening(uiPort) : false },
  };

  const readiness = {
    ready_http: apiPort > 0 ? await checkHttp200(`http://127.0.0.1:${apiPort}/api/status`, 1200) : false,
  };

  const res = { ok: true, runId: run.runId, alive, ports, readiness };
  process.stdout.write(JSON.stringify(res) + '\n');
}

async function stopRun(opts) {
  const { run, runPath } = await resolveRunTarget(opts);
  const disposition = opts.disposition ?? null;
  const actor = opts.actor ?? null;
  const victim = opts.victim ?? null;
  // Tempdoc 542 §B Layer 4: when a `force` takeover interrupts MUST_COMPLETE or
  // UNSAFE_TO_INTERRUPT ops, the caller passes the list here so the stop-report names them.
  const criticalOpsInterrupted = opts.criticalOpsInterrupted ?? null;
  const interruptibleWithLossInterrupted = opts.interruptibleWithLossInterrupted ?? null;
  const runId = run.runId;
  const apiPort = Number(run?.apiPortActual);
  const uiPort = Number(run?.uiPortActual);

  const killedPids = [];
  const errors = [];

  let taskkillExitCode = null;
  let taskkillStderrTail = '';

  const taskkill = async (pid) => {
    if (!pid || !Number.isFinite(pid) || pid <= 0) return;
    if (process.platform !== 'win32') {
      try {
        process.kill(pid, 'SIGKILL');
        killedPids.push(pid);
      } catch (_) { }
      return;
    }
    await new Promise((resolve) => {
      const p = spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true });
      let stderr = '';
      if (p.stderr) {
        p.stderr.on('data', (b) => {
          stderr += b.toString('utf8');
        });
      }
      p.on('close', (code) => {
        taskkillExitCode = code;
        taskkillStderrTail = stderr.split(/\r?\n/).slice(-10).join('\n');
        killedPids.push(pid);
        resolve();
      });
    });
  };

  // Prefer killing the runner (tree) if recorded; it owns backend/frontend stdin pipes.
  // Tempdoc 606 3b: but when stopRun is invoked IN-PROCESS by the supervisor's own reaper
  // (process.pid === runnerPid), taskkill /T on runnerPid would nuke this very process tree
  // before the cleanup below (stop-report + active.json removal) runs. Skip the self-kill in
  // that case — the frontend/backend taskkills still free all resources, cleanup completes,
  // and the reaper then process.exit(0)s itself. Cross-process callers (stop / takeover) have
  // a different pid and still kill the victim's runner tree.
  const stopRunnerPid = Number(run?.pids?.runnerPid);
  if (stopRunnerPid && stopRunnerPid !== process.pid) await taskkill(stopRunnerPid);
  await taskkill(Number(run?.pids?.frontendRootPid));
  await taskkill(Number(run?.pids?.backendRootPid));

  const portInfo = async (port) => {
    const listening = port > 0 ? await isTcpListening(port, 500) : false;
    if (!listening) return { port, closed: true };
    if (process.platform !== 'win32') return { port, closed: false };
    const owner = await getPortOwnerWindows(port);
    const ownerPid = owner?.pid ?? null;
    const cmd = ownerPid ? await getCommandLineWindows(ownerPid) : null;
    return {
      port,
      closed: false,
      ownerPid,
      ownerCommandLine: cmd,
      boundAddresses: owner?.address ? [owner.address] : [],
    };
  };

  // Verify ports closed (bounded).
  const deadline = Date.now() + 10_000;
  let apiInfo = await portInfo(apiPort);
  let uiInfo = await portInfo(uiPort);

  while (Date.now() < deadline && (!apiInfo.closed || !uiInfo.closed)) {
    // If a port is still open, try killing the owning PID (best-effort).
    for (const inf of [apiInfo, uiInfo]) {
      if (!inf || inf.closed) continue;
      if (process.platform === 'win32' && inf.ownerPid) {
        // eslint-disable-next-line no-await-in-loop
        await taskkill(inf.ownerPid);
      }
    }
    // eslint-disable-next-line no-await-in-loop
    await new Promise((r) => setTimeout(r, 250));
    // eslint-disable-next-line no-await-in-loop
    apiInfo = await portInfo(apiPort);
    // eslint-disable-next-line no-await-in-loop
    uiInfo = await portInfo(uiPort);
  }

  if (!apiInfo.closed) errors.push(`API port ${apiPort} still listening after stop timeout`);
  if (!uiInfo.closed) errors.push(`UI port ${uiPort} still listening after stop timeout`);

  const stopReport = {
    schemaVersion: 2,
    runId,
    stoppedAt: nowIso(),
    disposition,
    actor,
    victim,
    taskkillExitCode,
    taskkillStderrTail,
    killedPids,
    ports: { api: apiInfo, ui: uiInfo },
    portsClosed: apiInfo.closed && uiInfo.closed,
    errors,
    // Tempdoc 542 §B Layer 4 — make interrupted critical/loss op-leases part of the
    // permanent audit record. Tells the operator what was lost on a `force` takeover.
    ...(criticalOpsInterrupted ? { criticalOpsInterrupted } : {}),
    ...(interruptibleWithLossInterrupted ? { interruptibleWithLossInterrupted } : {}),
  };

  const stopReportPath = path.join(path.dirname(runPath), 'stop-report.json');
  await writeJsonAtomic(stopReportPath, stopReport);

  // Append interference events to a shared NDJSON log for aggregate analysis.
  // Tempdoc 542 §B Layer 4: include forcibly_interrupted_critical_op so the disposition is
  // visible in the shared interference NDJSON log alongside warned_takeover/forced_reclaim.
  const INTERFERENCE_DISPOSITIONS = new Set([
    'stale_reclaim',
    'warned_takeover',
    'forced_reclaim',
    'forcibly_interrupted_critical_op',
  ]);
  if (disposition && INTERFERENCE_DISPOSITIONS.has(disposition)) {
    const event = {
      ts: stopReport.stoppedAt,
      event: 'interference_stop',
      disposition,
      runId,
      actor,
      victim,
      portsClosed: stopReport.portsClosed,
    };
    const logPath = path.join(stateRoot, 'interference-events.ndjson');
    try { fs.appendFileSync(logPath, JSON.stringify(event) + '\n'); } catch (_) {}
  }

  // Clear active pointer if it points to this run.
  const active = await readJsonIfExists(activePath);
  if (active?.runId === runId) {
    await fsp.rm(activePath, { force: true }).catch(() => { });
  }

  return {
    ok: true,
    runId,
    killedPids,
    portsClosed: stopReport.portsClosed,
    stopReportPath: toPosix(path.relative(repoRoot, stopReportPath)),
  };
}

async function cmdCleanup(opts) {
  let run = null;
  try {
    ({ run } = await resolveRunTarget(opts));
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(JSON.stringify({ ok: true, runId: null, portsClosed: true, note: 'no_active_run' }) + '\n');
      return;
    }
    throw err;
  }
  const stopRes = await stopRun({ ...opts, runId: run.runId, disposition: 'normal_stop' });
  if (opts.clean !== 'none') {
    const dir = run?.dataDir ? path.resolve(repoRoot, run.dataDir) : null;
    if (dir) await cleanDataDir(dir, opts.clean);
  }
  process.stdout.write(JSON.stringify({ ok: true, runId: run.runId, portsClosed: stopRes.portsClosed }) + '\n');
  if (!stopRes.portsClosed && !opts.force) process.exit(1);
}

async function cmdStop(opts) {
  // Session-scoped ownership gate: reject stop if another session owns the stack
  if (!opts.force) {
    const active = await readJsonIfExists(activePath);
    if (active && active.holder?.agentSessionId) {
      const callerSession = resolveAgentSessionId(opts.sessionId);
      if (callerSession && callerSession !== active.holder.agentSessionId) {
        const err = new Error('OWNER_CONFLICT: stop rejected — another session owns the stack');
        err.code = 'OWNER_CONFLICT';
        err.holder = active.holder;
        err.lease = active.lease;
        throw err;
      }
    }
  }
  let res = null;
  try {
    res = await stopRun({ ...opts, disposition: 'normal_stop' });
  } catch (err) {
    if (isNoActiveRunError(err)) {
      process.stdout.write(
        JSON.stringify({
          ok: true,
          runId: null,
          killedPids: [],
          portsClosed: true,
          stopReportPath: null,
          note: 'no_active_run',
        }) + '\n',
      );
      return;
    }
    throw err;
  }
  process.stdout.write(JSON.stringify(res) + '\n');
  if (!res.portsClosed && !opts.force) process.exit(1);
}

/**
 * Tempdoc 656: `dev-runner doctor` — a discoverable entry point for the onramp doctor. Delegates to
 * scripts/dev/doctor.mjs (ESM), inheriting stdio and propagating its exit code, so `--json` and the
 * informational/exit-2-when-broken contract pass through unchanged. Kept a thin passthrough so the
 * tier/remedy logic has exactly one home.
 */
function cmdDoctor() {
  const doctorPath = path.join(__dirname, 'doctor.mjs');
  const passthrough = process.argv.slice(3); // args after `doctor` (e.g. --json)
  const res = spawnSync(process.execPath, [doctorPath, ...passthrough], { stdio: 'inherit' });
  process.exit(res.status == null ? 1 : res.status);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const cmd = opts.cmd;
  if (!cmd || cmd === 'help' || cmd === '--help' || cmd === '-h') {
    printUsage();
    process.exit(cmd ? 0 : 2);
  }

  await mkdirp(runsRoot);

  if (cmd === 'start') return cmdStart(opts);
  if (cmd === 'status') return cmdStatus(opts);
  if (cmd === 'stop') return cmdStop(opts);
  if (cmd === 'cleanup') return cmdCleanup(opts);
  if (cmd === 'doctor') return cmdDoctor();

  throw new Error(`Unknown command: ${cmd}`);
}

if (require.main === module) {
  main().catch((err) => {
    const wantsJson = process.argv.includes('--json');
    if (wantsJson) {
      process.stdout.write(
        JSON.stringify({
          ok: false,
          error: {
            code: err?.code || 'UNHANDLED',
            message: err?.message || String(err),
            ...(err?.stack ? { stack: String(err.stack) } : {}),
          },
        }) + '\n',
      );
    } else {
      console.error(err?.stack || String(err));
    }
    process.exit(1);
  });
} else {
  module.exports = {
    __test: {
      pruneHistoricRuns,
      resolveDataDir,
      cleanDataDir,
      acquireAdmission,
      readActiveOpLeases,
      computeOwnershipVerdict,
      classifyActivity,
      readSessionActivity,
      isPidAlive,
      lockPath,
      activePath,
      resolveExpectedIndexBasePath,
      resolveOwnerConfidence,
      resolveMainRepoRoot,
      mainRepoRoot,
      repoRoot,
      resolveCuda12ServerExe,
      stageSharedCuda12,
    },
  };
}
