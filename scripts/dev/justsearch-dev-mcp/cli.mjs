import { spawn } from 'node:child_process';
import fsp from 'node:fs/promises';
import path from 'node:path';

import { logWarn } from './log.mjs';

function tail(str, maxChars) {
  const s = String(str || '');
  if (s.length <= maxChars) return s;
  return s.slice(s.length - maxChars);
}

function splitLines(s) {
  return String(s || '').split(/\r?\n/);
}

function splitNonEmptyLines(s) {
  return String(s || '')
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean);
}

function spawnDevRunner({ repoRoot, devRunnerPath, args }) {
  return spawn(process.execPath, [devRunnerPath, ...args], {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

export async function runCliJson({ repoRoot, devRunnerPath, args, timeoutMs, mode }) {
  if (mode !== 'oneshot' && mode !== 'supervisor_first_line') {
    throw new Error(`Invalid runCliJson mode: ${mode}`);
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(`Invalid timeoutMs: ${timeoutMs}`);
  }

  const child = spawnDevRunner({ repoRoot, devRunnerPath, args });

  let stdoutBuf = '';
  let stderrBuf = '';
  let settled = false;

  if (child.stdout) child.stdout.setEncoding('utf8');
  if (child.stderr) child.stderr.setEncoding('utf8');

  const kill = () => {
    try {
      child.kill();
    } catch (_) {}
  };

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      kill();
      reject(new Error(`dev-runner timed out after ${timeoutMs}ms (${mode})`));
    }, timeoutMs);

    const finishErr = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      kill();
      reject(err);
    };

    const finishOk = (payload) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };

    if (child.stdout) {
      child.stdout.on('data', (chunk) => {
        stdoutBuf += chunk;

        if (mode === 'oneshot') return;

        // supervisor_first_line: resolve on the first complete line.
        const idx = stdoutBuf.indexOf('\n');
        if (idx === -1) return;

        const firstLine = stdoutBuf.slice(0, idx).trim();
        if (!firstLine) {
          return finishErr(new Error('dev-runner produced empty first stdout line (expected JSON)'));
        }

        let obj;
        try {
          obj = JSON.parse(firstLine);
        } catch (e) {
          return finishErr(
            new Error(`dev-runner first stdout line was not JSON: ${tail(firstLine, 500)}`),
          );
        }

        // Drain any further stdout to avoid backpressure (dev-runner should not write more in --json mode).
        try {
          child.stdout.removeAllListeners('data');
          child.stdout.on('data', () => {});
          child.stdout.resume();
        } catch (_) {}

        finishOk({ exitCode: null, json: obj, stderr: tail(stderrBuf, 8000), pid: child.pid });
      });
    }

    if (child.stderr) {
      child.stderr.on('data', (chunk) => {
        stderrBuf += chunk;
        // keep only a tail to avoid unbounded memory
        stderrBuf = tail(stderrBuf, 32_000);
      });
    }

    child.on('error', (err) => {
      finishErr(new Error(`dev-runner spawn failed: ${err?.message || String(err)}`));
    });

    child.on('exit', (code) => {
      if (mode === 'supervisor_first_line') {
        if (!settled) {
          clearTimeout(timer);
          settled = true;
          return reject(
            new Error(
              `dev-runner exited before producing JSON (${code ?? 'unknown'}). stderr=${tail(
                stderrBuf,
                2000,
              )}`,
            ),
          );
        }
        return;
      }

      // oneshot mode: parse exactly one JSON line from stdout.
      const lines = splitNonEmptyLines(stdoutBuf);
      if (lines.length !== 1) {
        return finishErr(
          new Error(
            `dev-runner stdout must contain exactly one JSON line (got ${lines.length}). ` +
              `stdoutTail=${tail(stdoutBuf, 1200)} stderrTail=${tail(stderrBuf, 2000)}`,
          ),
        );
      }
      const line = lines[0];
      let obj;
      try {
        obj = JSON.parse(line);
      } catch (e) {
        return finishErr(
          new Error(
            `dev-runner stdout was not JSON. stdout=${tail(line, 500)} stderrTail=${tail(
              stderrBuf,
              2000,
            )}`,
          ),
        );
      }

      finishOk({ exitCode: code ?? 0, json: obj, stderr: tail(stderrBuf, 8000), pid: child.pid });
    });
  });
}

function spawnCaptureEvidence({ repoRoot, args }) {
  const scriptPath = path.join(repoRoot, 'modules', 'ui-web', 'scripts', 'capture-evidence-bundle.mjs');
  return spawn(process.execPath, [scriptPath, ...args], {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

function spawnNodeScript({ repoRoot, scriptRelPath, args }) {
  const scriptPath = path.join(repoRoot, ...String(scriptRelPath).split('/'));
  return spawn(process.execPath, [scriptPath, ...args], {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
}

export async function runValidator({ repoRoot, scriptRelPath, args, timeoutMs }) {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(`Invalid timeoutMs: ${timeoutMs}`);
  }

  const child = spawnNodeScript({ repoRoot, scriptRelPath, args });

  let stdoutBuf = '';
  let stderrBuf = '';
  let settled = false;

  if (child.stdout) child.stdout.setEncoding('utf8');
  if (child.stderr) child.stderr.setEncoding('utf8');

  const kill = () => {
    try {
      child.kill();
    } catch (_) {}
  };

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      kill();
      reject(new Error(`validator timed out after ${timeoutMs}ms (${scriptRelPath})`));
    }, timeoutMs);

    const finishErr = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      kill();
      reject(err);
    };

    const finishOk = (payload) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };

    if (child.stdout) {
      child.stdout.on('data', (chunk) => {
        stdoutBuf += chunk;
        stdoutBuf = tail(stdoutBuf, 32_000);
      });
    }
    if (child.stderr) {
      child.stderr.on('data', (chunk) => {
        stderrBuf += chunk;
        stderrBuf = tail(stderrBuf, 32_000);
      });
    }

    child.on('error', (err) => {
      finishErr(new Error(`validator spawn failed (${scriptRelPath}): ${err?.message || String(err)}`));
    });

    child.on('exit', (code) => {
      finishOk({ exitCode: code ?? 0, stdoutTail: tail(stdoutBuf, 8000), stderrTail: tail(stderrBuf, 8000), pid: child.pid });
    });
  });
}

export async function runCaptureEvidenceBundle({ repoRoot, args, timeoutMs }) {
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new Error(`Invalid timeoutMs: ${timeoutMs}`);
  }

  const child = spawnCaptureEvidence({ repoRoot, args });

  let stdoutBuf = '';
  let stderrBuf = '';
  let settled = false;

  if (child.stdout) child.stdout.setEncoding('utf8');
  if (child.stderr) child.stderr.setEncoding('utf8');

  const kill = () => {
    try {
      child.kill();
    } catch (_) {}
  };

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      kill();
      reject(new Error(`capture-evidence-bundle timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    const finishErr = (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      kill();
      reject(err);
    };

    const finishOk = (payload) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };

    if (child.stdout) {
      child.stdout.on('data', (chunk) => {
        stdoutBuf += chunk;
        // avoid unbounded memory
        stdoutBuf = tail(stdoutBuf, 32_000);
      });
    }

    if (child.stderr) {
      child.stderr.on('data', (chunk) => {
        stderrBuf += chunk;
        // keep only a tail to avoid unbounded memory
        stderrBuf = tail(stderrBuf, 32_000);
      });
    }

    child.on('error', (err) => {
      finishErr(new Error(`capture-evidence-bundle spawn failed: ${err?.message || String(err)}`));
    });

    child.on('exit', (code) => {
      const lines = splitNonEmptyLines(stdoutBuf);
      if (lines.length !== 1) {
        return finishErr(
          new Error(
            `capture-evidence-bundle stdout must contain exactly one line (bundleDir). got=${lines.length} ` +
              `stdoutTail=${tail(stdoutBuf, 1200)} stderrTail=${tail(stderrBuf, 2000)}`,
          ),
        );
      }
      const bundleDir = lines[0];
      finishOk({ exitCode: code ?? 0, bundleDir, stderr: tail(stderrBuf, 8000), pid: child.pid });
    });
  });
}

export async function readRunJson({ repoRoot, runId }) {
  const p = path.join(repoRoot, 'tmp', 'dev-runner', 'runs', runId, 'run.json');
  const raw = await fsp.readFile(p, 'utf8');
  return JSON.parse(raw);
}

export function buildDevRunnerArgsStart({ apiPort, uiPort, clean, dataDir, takeover, skipBuild, hotReload, sessionId }) {
  const out = [
    'start',
    '--json',
    `--api-port=${apiPort}`,
    `--ui-port=${uiPort}`,
    `--clean=${clean}`,
  ];
  if (dataDir) out.push(`--data-dir=${dataDir}`);
  if (takeover) out.push(`--takeover=${takeover}`);
  if (skipBuild) out.push('--skip-build');
  if (hotReload) out.push('--hot-reload');
  if (sessionId) out.push(`--session-id=${sessionId}`);
  return out;
}

export function buildDevRunnerArgsStop({ runId, force, sessionId }) {
  const out = ['stop', '--json', `--run=${runId}`];
  if (force) out.push('--force');
  if (sessionId) out.push(`--session-id=${sessionId}`);
  return out;
}

export function buildDevRunnerArgsStatus({ runId }) {
  const out = ['status', '--json'];
  if (runId) out.push(`--run=${runId}`);
  else out.push('--active');
  return out;
}

export function buildDevRunnerArgsCleanup({ runId, clean, force }) {
  const out = ['cleanup', '--json', `--run=${runId}`, `--clean=${clean}`];
  if (force) out.push('--force');
  return out;
}

export function coerceExitAwareOk(json, exitCode) {
  const devRunnerOk = !!json?.ok;
  const ok = devRunnerOk && exitCode === 0;
  if (!ok && devRunnerOk) {
    logWarn('dev-runner exited non-zero despite ok:true (treating as failure)', `exitCode=${exitCode}`);
  }
  return { ...json, ok, ...(devRunnerOk ? { devRunnerOk } : {}), exitCode };
}

function parseValidatorErrorsFromStderr(stderrTail) {
  const lines = splitLines(stderrTail);
  const out = [];
  for (const line of lines) {
    const m = String(line || '').match(/^\s*-\s+(.*)$/);
    if (m && m[1]) out.push(m[1].trim());
  }
  return out.length > 0 ? out : null;
}

export async function runValidateEvidenceBundleV1({ repoRoot, bundleDir, timeoutMs }) {
  const res = await runValidator({
    repoRoot,
    scriptRelPath: 'scripts/evidence/validate-evidencebundle-v1.mjs',
    args: [bundleDir],
    timeoutMs,
  });
  const errors = res.exitCode === 0 ? null : parseValidatorErrorsFromStderr(res.stderrTail);
  return { ...res, errors };
}

export async function runValidateDeterminismBudgetV1({ repoRoot, bundleDir, timeoutMs, strictReasons, allowReasons }) {
  const args = [bundleDir];
  if (strictReasons === false) args.push('--strict-reasons=false');
  if (Array.isArray(allowReasons)) {
    for (const r of allowReasons) {
      if (!r) continue;
      args.push(`--allow-reason=${r}`);
    }
  }

  const res = await runValidator({
    repoRoot,
    scriptRelPath: 'scripts/evidence/validate-determinism-budget-v1.mjs',
    args,
    timeoutMs,
  });
  const errors = res.exitCode === 0 ? null : parseValidatorErrorsFromStderr(res.stderrTail);
  return { ...res, errors };
}


