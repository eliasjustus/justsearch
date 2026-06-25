#!/usr/bin/env node
/**
 * Tempdoc 618 §7: serve THIS worktree's frontend against the already-running dev backend.
 *
 * The MCP dev-runner serves Vite from the MAIN checkout, so a worktree's changed FE is never what
 * the running stack serves — to see it in a real browser you otherwise hand-roll a second Vite,
 * fight port collisions, and risk pointing at the wrong code (§7 cost ~15 turns). This helper:
 *   - picks a free port (from 5174; --strictPort so Vite fails fast instead of drifting silently);
 *   - pins the API proxy at the running backend (VITE_JUSTSEARCH_API_PORT; auto-detected from the
 *     shared dev-runner lease, or pass --api-port). Read-only: it BORROWS the running backend, it
 *     does NOT start one — so it works even when the stack is owned by another session;
 *   - serves from THIS worktree's modules/ui-web (Vite's cwd), so the served code IS the worktree's
 *     code by construction. It prints the branch + path so that correspondence is unmistakable
 *     (the 606 running↔worktree provenance check, applied to the FE).
 *
 * Usage (run from inside the worktree):
 *   node scripts/dev/serve-worktree-fe.cjs [--api-port <p>] [--port <p>]
 *
 * ONE serve contract, two consumer paths (tempdoc 615 §27/§29). This is the HUMAN path
 * (foreground, eyes-are-the-mount-check). The AUTOMATED path is the screenshot harness's
 * `scripts/jseval/jseval/ui_shot.py` (`_start_vite_server`), which adds detached spawn +
 * captured stderr + an app-mounted readiness gate (`ui_check._await_app_ready`) on top of
 * the same contract: FREE-PORT scan · `--strictPort` · NEUTRAL vite (no `--mode mock`) ·
 * provenance. They are two native paths bound by this contract (NOT one cross-language
 * process — that would add the very fragility §27 removes); drift is guarded by
 * `scripts/jseval/tests/test_ui_serve.py`.
 */
'use strict';
const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawn, spawnSync } = require('child_process');

const repoRoot = path.resolve(__dirname, '..', '..');
const uiWebDir = path.join(repoRoot, 'modules', 'ui-web');

function argVal(flag) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : null;
}

// Resolve the main repo root (state lives there even when we run inside a worktree).
function mainRepoRoot() {
  try {
    const gitPath = path.join(repoRoot, '.git');
    const st = fs.statSync(gitPath);
    if (st.isFile()) {
      const m = fs.readFileSync(gitPath, 'utf8').trim().match(/^gitdir:\s*(.+)$/);
      if (m) return path.resolve(repoRoot, m[1], '..', '..', '..');
    }
  } catch { /* not a worktree */ }
  return repoRoot;
}

function detectBackendPort() {
  const explicit = argVal('--api-port');
  if (explicit) return Number(explicit);
  try {
    const stateRoot = path.join(mainRepoRoot(), 'tmp', 'dev-runner');
    const active = JSON.parse(fs.readFileSync(path.join(stateRoot, 'active.json'), 'utf8'));
    if (active?.runId) {
      const run = JSON.parse(fs.readFileSync(path.join(stateRoot, 'runs', active.runId, 'run.json'), 'utf8'));
      // run.json exposes the bound port as apiPortActual (+ apiBaseUrl); older shapes used apiPort/ports.
      const port =
        run?.apiPortActual ??
        run?.apiPort ??
        run?.ports?.apiPort ??
        (run?.apiBaseUrl ? Number(new URL(run.apiBaseUrl).port) : null);
      if (port) return Number(port);
    }
  } catch { /* fall through — Vite auto-discovers the manifest if no env pin */ }
  return null;
}

// A connect-probe is more reliable than a bind-probe on Windows: a wildcard bind can coexist with
// a listener already on [::1], so a bind-probe falsely reports "free" for a port Vite (which binds
// `localhost`) will then fail on. Probe BOTH loopback stacks — free only if neither answers.
function portInUse(port, host) {
  return new Promise((resolve) => {
    const sock = net.connect({ port, host });
    sock.setTimeout(400);
    sock.once('connect', () => { sock.destroy(); resolve(true); });
    sock.once('timeout', () => { sock.destroy(); resolve(false); });
    sock.once('error', () => resolve(false)); // ECONNREFUSED / unreachable → nothing listening here
  });
}

async function isFree(port) {
  const [v4, v6] = await Promise.all([portInUse(port, '127.0.0.1'), portInUse(port, '::1')]);
  return !v4 && !v6;
}

async function pickPort(start) {
  for (let p = start; p < start + 50; p++) {
    if (await isFree(p)) return p;
  }
  throw new Error(`no free port in ${start}..${start + 50}`);
}

(async () => {
  const branch = (spawnSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).stdout || '').trim();
  const port = Number(argVal('--port')) || (await pickPort(5174));
  const apiPort = detectBackendPort();

  const env = { ...process.env };
  if (apiPort) env.VITE_JUSTSEARCH_API_PORT = String(apiPort);

  console.error('[serve-worktree-fe] serving worktree FE:');
  console.error(`  branch:  ${branch || '(unknown)'}`);
  console.error(`  source:  ${uiWebDir}`);
  console.error(`  url:     http://localhost:${port}`);
  console.error(`  backend: ${apiPort ? `port ${apiPort} (borrowed, read-only)` : 'auto-discover (no running lease found)'}`);

  const isWin = process.platform === 'win32';
  const child = spawn(isWin ? 'npx.cmd' : 'npx', ['vite', '--port', String(port), '--strictPort'], {
    cwd: uiWebDir,
    env,
    stdio: 'inherit',
    shell: isWin,
  });
  child.on('exit', (code) => process.exit(code ?? 0));
})().catch((err) => {
  console.error(`[serve-worktree-fe] ERROR: ${err.message}`);
  process.exit(1);
});
