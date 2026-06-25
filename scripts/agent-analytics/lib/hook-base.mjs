/**
 * Shared utilities for the Claude Code hooks under
 * `scripts/agent-analytics/hooks/` (tempdoc 520 P1a).
 *
 * Every hook previously duplicated: stdin reading, JSON parsing, Windows
 * path normalization, repoRoot resolution, telemetry-dir resolution, the
 * `import.meta` direct-run guard, and (P0e/P0f) an atomic-write helper. This
 * module consolidates them, and centralizes the JUSTSEARCH_DISABLE_HOOKS kill
 * switch (P1c).
 *
 * Pure/synchronous helpers are unit-tested in `hook-base.test.mjs`.
 */

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// Resolve repo root from this lib's location: lib/ → agent-analytics/ →
// scripts/ → repo. Hooks live at the same depth (hooks/), so this resolves
// identically whether the caller is a hook or another lib consumer.
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;

export const repoRoot = path.resolve(scriptDir, '..', '..', '..');
export const telemetryDir = path.join(repoRoot, 'tmp', 'agent-telemetry');

/**
 * Resolve the MAIN repo root even from inside a git worktree (tempdoc 606).
 * In a worktree, `.git` is a file `gitdir: <mainRepo>/.git/worktrees/<name>`;
 * walk up 3 levels. Falls back to `repoRoot`. Mirrors the dev-runner's
 * `resolveMainRepoRoot` so both agree on the SHARED dev-stack state root —
 * essential because the dev-stack supervisor is mainRepoRoot-scoped while these
 * hooks may run from a worktree.
 */
export function resolveMainRepoRoot(from = repoRoot) {
  try {
    const gitPath = path.join(from, '.git');
    const stat = fs.statSync(gitPath);
    if (stat.isFile()) {
      const m = fs.readFileSync(gitPath, 'utf8').trim().match(/^gitdir:\s*(.+)$/);
      if (m) {
        const gitDir = path.resolve(from, m[1]);
        return path.resolve(gitDir, '..', '..', '..');
      }
    }
  } catch { /* not a worktree or no .git — fall through */ }
  return from;
}

export const mainRepoRoot = resolveMainRepoRoot();
/** Shared dev-stack per-session activity dir (read by the dev-runner supervisor + verdict). */
export const devSessionsDir = path.join(mainRepoRoot, 'tmp', 'dev-runner', 'sessions');

/**
 * Tempdoc 606 1a: merge a patch into the shared per-session activity stamp
 * (`<mainRepoRoot>/tmp/dev-runner/sessions/<sessionId>.json`). Best-effort,
 * atomic, last-writer-wins; honors no error escape. Used to record
 * `lastActivityAt` (any tool call) and `lastDevStackTouchAt` (justsearch-dev
 * call) so the dev-stack ownership verdict can sense owner presence/idle.
 */
export function stampSessionActivity(sessionId, patch) {
  if (!sessionId) return;
  try {
    fs.mkdirSync(devSessionsDir, { recursive: true });
    const file = path.join(devSessionsDir, `${sessionId}.json`);
    let cur = {};
    try { cur = JSON.parse(fs.readFileSync(file, 'utf8')) || {}; } catch { /* fresh */ }
    atomicWriteFileSync(file, JSON.stringify({ ...cur, ...patch }));
  } catch {
    /* best-effort — never throw from a telemetry stamp */
  }
}

/** Remove a session's activity stamp (SessionEnd accelerator; staleness is the authority). */
export function clearSessionActivity(sessionId) {
  if (!sessionId) return;
  try { fs.unlinkSync(path.join(devSessionsDir, `${sessionId}.json`)); } catch { /* may not exist */ }
}

/**
 * Kill switch (P1c): `JUSTSEARCH_DISABLE_HOOKS=1` disables all hooks. A hook
 * that does no work produces no output and exits 0 — i.e. "allow / no-op",
 * the correct disabled behavior for both blocking guards and hint hooks.
 * Fast recovery path when a hook misbehaves, without editing settings.json.
 */
export function hooksDisabled() {
  return process.env.JUSTSEARCH_DISABLE_HOOKS === '1';
}

/**
 * Skeleton-only telemetry mode (tempdoc 622 Phase 5 / A3, "freeze behind a flag").
 * `JUSTSEARCH_DISPATCH_SKELETON_ONLY=1` makes dispatch.mjs emit only the
 * irreducible lifecycle/skeleton events (the unique carriers blocking hooks +
 * subagent/transcript discovery need) and SKIP the rich tool-summarizing
 * (input_summary / response_summary / error_summary / prompt excerpt /
 * instructions_loaded) that native OTel logs now supersede. Default (unset) keeps
 * the full parallel-run capture. The cutover to "1" is gated on confirming
 * interactive OTel logs+metrics emission (see tempdoc 622 §13).
 */
export function dispatchSkeletonOnly() {
  return process.env.JUSTSEARCH_DISPATCH_SKELETON_ONLY === '1';
}

/** Read all of stdin as a UTF-8 string. */
export async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

/** Read + parse stdin as JSON; returns `null` on empty input or parse error. */
export async function readJsonStdin() {
  try {
    const raw = await readStdin();
    if (!raw.trim()) return null;
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/**
 * Atomic write: write to a per-PID temp file, then rename it over the target.
 * rename(2) is atomic on POSIX and uses MoveFileEx(MOVEFILE_REPLACE_EXISTING)
 * on Windows, so a concurrent reader sees either the complete old file or the
 * complete new file — never a torn partial write (tempdoc 520 P0e/P0f). The
 * per-PID temp name avoids collisions between parallel hook invocations;
 * last-writer-wins on the final rename is inherent (and acceptable for the
 * best-effort per-session state these hooks keep).
 */
export function atomicWriteFileSync(filePath, data) {
  const tmp = `${filePath}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, data, 'utf8');
  try {
    fs.renameSync(tmp, filePath);
  } catch (e) {
    try { fs.unlinkSync(tmp); } catch {}
    throw e;
  }
}

/**
 * True when the module identified by `importMetaUrl` was executed directly
 * (e.g. `node hook.mjs`) rather than imported (e.g. by a test). Lets a hook
 * export its pure logic while only running `main()` on direct invocation.
 */
export function isDirectRun(importMetaUrl) {
  return !!process.argv[1] && importMetaUrl === pathToFileURL(process.argv[1]).href;
}

/** Derive a hook id (`X`) from a module url ending in `.../X.mjs`. */
export function hookIdFromUrl(importMetaUrl) {
  try {
    return path.basename(new URL(importMetaUrl).pathname).replace(/\.mjs$/, '');
  } catch {
    return 'unknown';
  }
}

/**
 * Look up a hook's role from the single-authority manifest (tempdoc 592). Returns
 * 'blocking' | 'advisory' | 'telemetry'; defaults to 'advisory' if the manifest is
 * unreadable or the id is absent. Read lazily (only on the failure path), so the
 * happy path pays nothing.
 */
export function hookRoleFromManifest(id, manifestPath = path.join(repoRoot, 'governance', 'agent-hooks.v1.json')) {
  try {
    const m = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    return m.hooks?.[id]?.role ?? 'advisory';
  } catch {
    return 'advisory';
  }
}

/**
 * Pure core of the no-silent-downgrade contract (tempdoc 592 / 576 §5). Given a
 * failed hook's id + role + message, produce the telemetry event and — for a
 * BLOCKING hook only — a loud, attributed stderr line. We cannot change the
 * harness rule that a non-2 exit lets the tool proceed (Wall 2), but we guarantee
 * the failure of a guard is never SILENT.
 */
export function describeHookFailure({ id, role, message, phase = 'run' }) {
  const event = { event: 'hook_failure', hookId: id, role, phase, message };
  const loud =
    role === 'blocking'
      ? `[hook:${id}] FAILED to ${phase} — its guard is OFF for this call (fail-open): ${message}`
      : null;
  return { event, loud };
}

/** Append one event object as an NDJSON line to the telemetry stream (best-effort). */
export function appendTelemetryEvent(event) {
  try {
    fs.mkdirSync(telemetryDir, { recursive: true });
    fs.appendFileSync(path.join(telemetryDir, 'events.ndjson'), JSON.stringify(event) + '\n', 'utf8');
  } catch {
    /* telemetry is best-effort — never throw from the failure path */
  }
}

/**
 * Standard hook entrypoint guard. Call at the bottom of a hook module:
 *
 *   runHook(import.meta.url, main);
 *
 * Runs `main()` only on direct invocation, skips it entirely when hooks are
 * disabled, and never lets a thrown error escape (hooks must fail open). When
 * `main()` throws, a BLOCKING hook (per the 592 manifest) now emits a loud,
 * attributed stderr line + a `hook_failure` telemetry event rather than failing
 * SILENTLY open; advisory hooks stay quiet (telemetry only).
 */
export function runHook(importMetaUrl, main) {
  if (!isDirectRun(importMetaUrl)) return;
  if (hooksDisabled()) return;
  Promise.resolve()
    .then(main)
    .catch((err) => {
      try {
        const id = hookIdFromUrl(importMetaUrl);
        const role = hookRoleFromManifest(id);
        const { event, loud } = describeHookFailure({ id, role, message: String(err?.message ?? err) });
        appendTelemetryEvent({ ...event, ts: new Date().toISOString() });
        if (loud) process.stderr.write(loud + '\n');
      } catch {
        /* never let the failure-reporting path itself break fail-open */
      }
      process.exit(0);
    });
}
