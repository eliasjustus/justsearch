/**
 * Run-history substrate — Layer 3 §3.7a of tempdoc 530.
 *
 * After each kernel invocation, append one versioned line per gate plus one run-level repository-health
 * line to `tmp/governance-history.ndjson`, retained to the newest 5,000 rows. Readers retain the
 * unversioned V1 gate-row shape.
 *
 * History is local-only (under tmp/, gitignored). CI may retain the per-run file as an artifact,
 * but no separately committed or cross-run rolling history exists. The local file drives the
 * dashboard and API projection while that checkout exists.
 */

import {
  appendFileSync,
  closeSync,
  existsSync,
  fstatSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  renameSync,
  rmdirSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';

import { collectRepositoryHealth } from './repository-health.mjs';

export const DEFAULT_HISTORY_PATH = 'tmp/governance-history.ndjson';
export const HISTORY_SCHEMA_VERSION = 2;
export const HISTORY_MAX_LINES = 5000;

const LOCK_WAIT_MS = 10_000;
const LOCK_RETRY_MS = 10;
const INCOMPLETE_LOCK_STALE_MS = 30_000;
const MAX_LOCK_LEASE_MS = 60_000;
const REPLACE_WAIT_MS = 2_000;

export function appendRunRecord({
  repoRoot,
  path = DEFAULT_HISTORY_PATH,
  runs,
  verdicts,
  maxLines = HISTORY_MAX_LINES,
}) {
  if (!Number.isSafeInteger(maxLines) || maxLines < 1) {
    throw new TypeError('maxLines must be a positive safe integer');
  }
  const ts = new Date().toISOString();
  const full = resolve(repoRoot, path);
  mkdirSync(dirname(full), { recursive: true });
  const rows = [];
  for (const v of verdicts) {
    const matching = runs.find(r => r.categoryId === v.gate);
    const counts = { error: 0, warning: 0, note: 0 };
    for (const f of matching?.findings ?? []) {
      counts[f.level] = (counts[f.level] ?? 0) + 1;
    }
    rows.push(JSON.stringify({
      schemaVersion: HISTORY_SCHEMA_VERSION,
      kind: 'gate-run',
      ts,
      gate: v.gate,
      verdict: v.verdict,
      findings: counts,
    }));
  }
  const health = {
    schemaVersion: HISTORY_SCHEMA_VERSION,
    kind: 'repository-health',
    ts,
    metrics: collectRepositoryHealth(repoRoot),
  };
  rows.push(JSON.stringify(health));
  withHistoryLock(full, () => {
    appendFileSync(full, rows.join('\n') + '\n');
    retainLastLines(full, maxLines);
  });
}

/**
 * Serialize append + retention across Node processes. Atomic directory creation works on Windows
 * and POSIX. Stale recovery first renames the lock to a fixed recovery directory; acquisitions
 * treat that directory as a barrier, so the stale-owner check cannot admit a second writer.
 */
function withHistoryLock(full, action) {
  const lockDir = `${full}.lock`;
  const recoveryDir = `${lockDir}.recovering`;
  const deadline = Date.now() + LOCK_WAIT_MS;
  const owner = {
    pid: process.pid,
    acquiredAt: Date.now(),
  };

  while (true) {
    recoverStaleDirectory(recoveryDir);
    if (existsSync(recoveryDir)) {
      waitForLock(deadline, lockDir);
      continue;
    }

    try {
      mkdirSync(lockDir);
      writeFileSync(join(lockDir, 'owner.json'), `${JSON.stringify(owner)}\n`, { flag: 'wx' });
    } catch (error) {
      if (error?.code !== 'EEXIST') throw error;
      recoverStaleLock(lockDir, recoveryDir);
      waitForLock(deadline, lockDir);
      continue;
    }

    // A recovery can have started just before mkdir. Relinquish this acquisition before entering
    // the critical section; the fixed recovery directory remains the barrier until restoration.
    if (existsSync(recoveryDir) || !sameOwner(owner, readOwner(lockDir))) {
      removeLockDirectory(lockDir, owner);
      waitForLock(deadline, lockDir);
      continue;
    }

    try {
      return action();
    } finally {
      removeLockDirectory(lockDir, owner);
    }
  }
}

function recoverStaleLock(lockDir, recoveryDir) {
  const candidate = readOwnerWithAge(lockDir);
  if (!isStaleOwner(candidate)) return;
  try {
    renameSync(lockDir, recoveryDir);
  } catch (error) {
    if (error?.code === 'ENOENT' || error?.code === 'EEXIST') return false;
    throw error;
  }

  const moved = readOwnerWithAge(recoveryDir);
  if (sameOwner(candidate, moved) && isStaleOwner(moved)) {
    removeLockDirectory(recoveryDir, moved);
    return;
  }

  restoreMovedLock(lockDir, recoveryDir);
}

function recoverStaleDirectory(recoveryDir) {
  const owner = readOwnerWithAge(recoveryDir);
  if (isStaleOwner(owner)) removeLockDirectory(recoveryDir, owner);
}

function restoreMovedLock(lockDir, recoveryDir) {
  const deadline = Date.now() + LOCK_WAIT_MS;
  while (existsSync(recoveryDir)) {
    if (!existsSync(lockDir)) {
      try {
        renameSync(recoveryDir, lockDir);
        return;
      } catch (error) {
        if (error?.code !== 'ENOENT' && error?.code !== 'EEXIST') throw error;
      }
    }
    if (Date.now() >= deadline) {
      throw new Error(`timed out restoring governance history lock ${lockDir}`);
    }
    sleep(LOCK_RETRY_MS);
  }
}

function readOwnerWithAge(lockDir) {
  try {
    const stat = statSync(lockDir);
    const owner = readOwner(lockDir);
    return owner == null ? { pid: null, acquiredAt: stat.mtimeMs } : owner;
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

function isStaleOwner(owner) {
  if (!owner) return false;
  const age = Date.now() - owner.acquiredAt;
  const oldEnough = age >= INCOMPLETE_LOCK_STALE_MS;
  if (!Number.isSafeInteger(owner.pid) || owner.pid < 1) return oldEnough;
  // The critical section is synchronous and normally lasts milliseconds. The upper bound also
  // prevents a recycled PID from making a crashed writer's lock permanent.
  return age >= MAX_LOCK_LEASE_MS || !isProcessAlive(owner.pid);
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === 'ESRCH') return false;
    // EPERM means the process exists but cannot be signalled. Unknown platform errors are treated
    // conservatively as live; bounded waiting will surface the lock instead of deleting it.
    return true;
  }
}

function sameOwner(left, right) {
  if (!left || !right) return false;
  return left.pid === right.pid && left.acquiredAt === right.acquiredAt;
}

function readOwner(lockDir) {
  try {
    const owner = JSON.parse(readFileSync(join(lockDir, 'owner.json'), 'utf8'));
    if (
      owner == null ||
      typeof owner !== 'object' ||
      !Number.isSafeInteger(owner.pid) ||
      owner.pid < 1 ||
      !Number.isSafeInteger(owner.acquiredAt) ||
      owner.acquiredAt < 0
    ) {
      return null;
    }
    return owner;
  } catch {
    return null;
  }
}

function removeLockDirectory(lockDir, expectedOwner) {
  if (expectedOwner != null && !sameOwner(expectedOwner, readOwnerWithAge(lockDir))) return false;
  try { unlinkSync(join(lockDir, 'owner.json')); } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  try {
    rmdirSync(lockDir);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    // Never recursively remove an unexpected directory. Leaving it behind makes the bounded wait
    // fail visibly rather than deleting data that does not belong to this lock protocol.
    if (error?.code === 'ENOTEMPTY' || error?.code === 'EEXIST') return false;
    throw error;
  }
}

function waitForLock(deadline, lockDir) {
  if (Date.now() >= deadline) {
    throw new Error(`timed out waiting for governance history lock ${lockDir}`);
  }
  sleep(Math.min(LOCK_RETRY_MS, Math.max(1, deadline - Date.now())));
}

function sleep(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

/**
 * Keep only the newest complete NDJSON rows. The reverse scan reads fixed-size chunks, so the
 * one-time compaction of a legacy oversized file does not first materialize the whole file.
 */
function retainLastLines(full, maxLines) {
  const fd = openSync(full, 'r');
  let tail = null;
  try {
    const size = fstatSync(fd).size;
    if (size === 0) return;

    const chunkSize = 64 * 1024;
    const chunk = Buffer.allocUnsafe(chunkSize);
    let position = size;
    let boundaries = 0;
    let start = 0;
    let skipTerminalNewline = true;

    scan: while (position > 0) {
      const count = Math.min(chunkSize, position);
      position -= count;
      readFully(fd, chunk, count, position);
      for (let i = count - 1; i >= 0; i--) {
        if (chunk[i] !== 0x0a) continue;
        if (skipTerminalNewline && position + i === size - 1) {
          skipTerminalNewline = false;
          continue;
        }
        skipTerminalNewline = false;
        boundaries++;
        if (boundaries === maxLines) {
          start = position + i + 1;
          break scan;
        }
      }
    }

    if (start === 0) return;
    tail = Buffer.allocUnsafe(size - start);
    readFully(fd, tail, tail.length, start);
  } finally {
    closeSync(fd);
  }
  if (tail != null) atomicReplace(full, tail);
}

function atomicReplace(full, content) {
  const temporary = `${full}.tmp-${process.pid}-${randomUUID()}`;
  let fd;
  try {
    fd = openSync(temporary, 'wx');
    writeFileSync(fd, content);
    fsyncSync(fd);
  } finally {
    if (fd != null) closeSync(fd);
  }

  const deadline = Date.now() + REPLACE_WAIT_MS;
  while (true) {
    try {
      renameSync(temporary, full);
      return;
    } catch (error) {
      const transient = ['EACCES', 'EBUSY', 'EPERM'].includes(error?.code);
      if (!transient || Date.now() >= deadline) {
        try { unlinkSync(temporary); } catch (cleanupError) {
          if (cleanupError?.code !== 'ENOENT') throw cleanupError;
        }
        throw error;
      }
      sleep(LOCK_RETRY_MS);
    }
  }
}

function readFully(fd, buffer, length, position) {
  let offset = 0;
  while (offset < length) {
    const read = readSync(fd, buffer, offset, length - offset, position + offset);
    if (read === 0) throw new Error('unexpected EOF while compacting governance history');
    offset += read;
  }
}

export function readHistory({ repoRoot, path = DEFAULT_HISTORY_PATH }) {
  const full = resolve(repoRoot, path);
  if (!existsSync(full)) return [];
  const content = readFileSync(full, 'utf8');
  const out = [];
  for (const raw of content.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    try { out.push(JSON.parse(line)); } catch { /* skip malformed */ }
  }
  return out;
}

/** V1 rows had no kind/schemaVersion; a string gate keeps them readable during migration. */
export function readGateHistory(options) {
  return readHistory(options).filter(
    entry => typeof entry?.gate === 'string' && (entry.kind == null || entry.kind === 'gate-run'),
  );
}

export function readRepositoryHealthHistory(options) {
  return readHistory(options).filter(
    entry => entry?.kind === 'repository-health' && entry.schemaVersion === HISTORY_SCHEMA_VERSION,
  );
}
