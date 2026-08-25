#!/usr/bin/env node
/**
 * Tempdoc 861 W2 [A2] — process identity that survives pid reuse.
 *
 * Every kill path in 861 must call `verifyProcessIdentity` immediately before acting. A record
 * routinely outlives its process by hours (861 §2), and the reaper's whole job is killing by pid,
 * so "the pid is alive" is not evidence that the pid is still OURS.
 *
 * Three rules this module exists to enforce, none of which is optional:
 *
 *  1. **The conjunction is mandatory** — `pid` AND `creationTime` AND `fingerprint`, never any
 *     subset. The fingerprint alone is a *substring* match on a command line containing e.g.
 *     `vite`; it is safe ONLY as the third term of a conjunction whose second term is an exact
 *     creation-time equality, and dangerously permissive on its own (861 §6.2).
 *
 *  2. **Exact equality on the creation time, no tolerance window.** A tolerance is a second
 *     identity bug waiting: pid reuse on a busy Windows host can land inside any window loose
 *     enough to absorb clock jitter (861 §6.2).
 *
 *  3. **Evidence-unavailable is REFUSE, never "proceed because nothing contradicted us."** REFUSE
 *     is a THIRD verdict value, not `false`. The repo precedent is `classifyActivity`, which
 *     returns `{ known: false, generalStale: false }` so an absent signal cannot masquerade as a
 *     permissive one (`scripts/dev/lib/ownership-verdict.cjs:82-92`). It needs enforcing in code
 *     here because the neighbouring helper fails silently to empty by design —
 *     `getProcessTable` returns `[]` on ANY failure (`scripts/dev/remove-worktree.cjs:125-131`) —
 *     and a reaper reading that `[]` as "no conflicting evidence" would re-ship that defect inside
 *     a component whose job is killing things.
 *
 * **Why the creation time is carried as a decimal STRING, not a JSON number.** Measured on this
 * host, 2026-08-25: `Win32_Process.CreationDate.ToFileTimeUtc()` yields 18-digit values such as
 * `134320479841300350`. `Number.MAX_SAFE_INTEGER` is 16 digits (9007199254740991), so a FILETIME
 * cannot be represented exactly as a JSON number: `Number('134320479841300350') ===
 * Number('134320479841300351')` is `true` in Node. Two creation times 100 ns apart therefore
 * COLLAPSE under a double, which is precisely the "exact equality" rule 2 forbids weakening. So
 * PowerShell emits `.ToFileTimeUtc().ToString()`, and `normalizeCreationTime` canonicalizes via
 * `BigInt`. A creation time arriving as an unsafe JSON *number* is treated as unreadable evidence
 * (REFUSE) rather than compared as a rounded double.
 *
 * Nothing in this module kills, signals, or writes anything. It answers one question.
 */
'use strict';

const { spawnSync } = require('node:child_process');

/**
 * The ONE process-table projection. `remove-worktree.cjs` consumes this same constant so the two
 * cannot drift: rev 1 of 861 assumed the creation time was "already available" from that scan, but
 * its `Select-Object` projected `CreationDate` away (`remove-worktree.cjs:119-120` before this
 * change) — a single shared constant is what stops that assumption from being made twice.
 *
 * `CreationDate` is a `[DateTime]` on the CIM cmdlets, so `.ToFileTimeUtc()` normalizes it to a
 * locale-independent 64-bit integer instead of a CIM datetime string whose format depends on the
 * host's culture. Kernel-owned rows (`System Idle Process`) can carry a null `CreationDate`; the
 * guard emits `$null` for those rather than throwing, and the JS side reads a null as
 * evidence-unavailable.
 */
const PROCESS_TABLE_PS_COMMAND =
  'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,CommandLine,' +
  "@{Name='CreationFileTimeUtc';Expression={ if ($_.CreationDate) { $_.CreationDate.ToFileTimeUtc().ToString() } else { $null } }}" +
  ' | ConvertTo-Json -Compress -Depth 2';

/**
 * The verdict vocabulary. THREE values — a boolean cannot express "I have no evidence", which is
 * the whole point (861 [A2]).
 *
 *   `match`    — pid AND creation time AND fingerprint all agree. The only value that licenses a
 *                kill.
 *   `mismatch` — the evidence was READ and it says this is not our process (recycled pid, dead
 *                pid, different command line). A negative answer, not an absent one.
 *   `refuse`   — the evidence was absent, unparseable, or unavailable. NOT a negative answer, and
 *                emphatically not a positive one: callers report and stop.
 */
const IDENTITY = Object.freeze({
  MATCH: 'match',
  MISMATCH: 'mismatch',
  REFUSE: 'refuse',
});

/**
 * Canonicalize an OS process creation time to a decimal string, or `null` when the evidence is
 * absent or unreadable. `null` means REFUSE at every call site — never "zero", never "old".
 *
 * @param {unknown} value
 * @returns {string|null}
 */
function normalizeCreationTime(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') {
    // A real FILETIME never fits a double (see the header note). Refusing here is deliberate: a
    // silently rounded comparison would satisfy "exact equality" in the source and violate it in
    // fact.
    if (!Number.isSafeInteger(value) || value <= 0) return null;
    return BigInt(value).toString();
  }
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!/^[0-9]{1,20}$/.test(trimmed)) return null;
  let big;
  try {
    big = BigInt(trimmed);
  } catch {
    return null;
  }
  if (big <= 0n) return null;
  return big.toString();
}

/**
 * Read the local process table as a TRI-STATE: `{ ok: true, table }` or `{ ok: false, reason }`.
 *
 * Deliberately NOT `getProcessTable`'s `[]`-on-failure contract. That contract is right for a
 * best-effort holder *report* (it degrades to "no holder found" instead of throwing mid-teardown)
 * and wrong for an identity check, where `[]` would read as "nothing contradicts you".
 *
 * `exec` and `platform` are injectable so every branch is unit-testable without spawning
 * PowerShell.
 */
function readProcessTable({ platform = process.platform, exec = spawnSync } = {}) {
  if (platform !== 'win32') {
    return { ok: false, reason: `process-table enumeration is implemented for win32 only (platform=${platform})` };
  }
  let res;
  try {
    res = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', PROCESS_TABLE_PS_COMMAND], {
      encoding: 'utf8',
      maxBuffer: 32 * 1024 * 1024,
    });
  } catch (err) {
    return { ok: false, reason: `process-table query threw: ${String(err?.message || err).slice(0, 200)}` };
  }
  if (!res || res.status !== 0) {
    return { ok: false, reason: `process-table query exited ${res ? res.status : 'with no result'}` };
  }
  if (!res.stdout) return { ok: false, reason: 'process-table query produced no output' };
  let parsed;
  try {
    parsed = JSON.parse(res.stdout);
  } catch (err) {
    return { ok: false, reason: `process-table output is not JSON: ${String(err?.message || err).slice(0, 200)}` };
  }
  const table = Array.isArray(parsed) ? parsed : [parsed];
  if (table.length === 0) {
    return { ok: false, reason: 'process-table enumeration returned no rows; a running host always has processes, so this is a failed query, not an empty machine' };
  }
  return { ok: true, table };
}

/**
 * Accept a tri-state result, a raw array, or nothing at all, and collapse it to the tri-state —
 * with the empty array landing on the REFUSE side, which is the branch that matters.
 */
function coerceProcessTable(table) {
  if (table === null || table === undefined) {
    return { ok: false, reason: 'no process table supplied' };
  }
  if (Array.isArray(table)) {
    if (table.length === 0) {
      return {
        ok: false,
        reason: 'empty process table is NO evidence, not exculpatory evidence (getProcessTable fails silently to [] by design — remove-worktree.cjs:125-131)',
      };
    }
    return { ok: true, table };
  }
  if (typeof table === 'object' && typeof table.ok === 'boolean') {
    if (!table.ok) return { ok: false, reason: table.reason || 'process table unavailable' };
    return coerceProcessTable(table.table);
  }
  return { ok: false, reason: `unrecognized process-table shape (${typeof table})` };
}

function verdict(kind, reason, extra = {}) {
  return {
    verdict: kind,
    reason,
    matched: { pid: null, creationTime: null, fingerprint: null, ...(extra.matched || {}) },
    ...(extra.pid !== undefined ? { pid: extra.pid } : {}),
  };
}

/**
 * The single function every kill path calls immediately before acting (861 §6.3).
 *
 * @param {object} args
 * @param {object} args.record - a record carrying `pid`, `creationFileTimeUtc`, `cmdlineFingerprint`.
 * @param {object|Array|null} args.table - a `readProcessTable` result, a raw row array, or nothing.
 * @returns {{verdict: 'match'|'mismatch'|'refuse', reason: string, matched: object, pid?: number}}
 */
function verifyProcessIdentity({ record, table } = {}) {
  const pid = record?.pid;
  if (!Number.isInteger(pid) || pid <= 0) {
    return verdict(IDENTITY.REFUSE, `record declares no usable pid (${JSON.stringify(record?.pid)})`);
  }
  const recordedTime = normalizeCreationTime(record?.creationFileTimeUtc);
  if (recordedTime === null) {
    return verdict(
      IDENTITY.REFUSE,
      `record's creationFileTimeUtc is absent or unreadable (${JSON.stringify(record?.creationFileTimeUtc)}); identity cannot be established, so no kill path may proceed`,
      { pid },
    );
  }
  const fingerprint = typeof record?.cmdlineFingerprint === 'string' ? record.cmdlineFingerprint.trim() : '';
  if (!fingerprint) {
    return verdict(IDENTITY.REFUSE, 'record declares no cmdlineFingerprint; the third conjunct cannot be evaluated', { pid });
  }

  const resolved = coerceProcessTable(table);
  if (!resolved.ok) {
    return verdict(IDENTITY.REFUSE, `process table unavailable: ${resolved.reason}`, { pid });
  }

  const row = resolved.table.find((r) => Number(r?.ProcessId) === pid);
  if (!row) {
    // The table WAS read, and this pid is not in it. That is a read negative, not an absent one:
    // the process is gone, so nothing can be killed and nothing needs refusing.
    return verdict(IDENTITY.MISMATCH, `pid ${pid} is not present in the process table (the process is gone)`, {
      pid,
      matched: { pid: false },
    });
  }

  const liveTime = normalizeCreationTime(row.CreationFileTimeUtc);
  if (liveTime === null) {
    return verdict(
      IDENTITY.REFUSE,
      `pid ${pid} is present but its creation time is absent or unreadable (${JSON.stringify(row.CreationFileTimeUtc)}); pid reuse cannot be ruled out`,
      { pid, matched: { pid: true } },
    );
  }
  if (liveTime !== recordedTime) {
    // The recycled-pid branch. Exact equality, no tolerance window (rule 2).
    return verdict(
      IDENTITY.MISMATCH,
      `pid ${pid} was recycled: the live process was created at ${liveTime}, the record names ${recordedTime}`,
      { pid, matched: { pid: true, creationTime: false } },
    );
  }

  const cmdline = typeof row.CommandLine === 'string' ? row.CommandLine : null;
  if (cmdline === null) {
    return verdict(
      IDENTITY.REFUSE,
      `pid ${pid} matches on creation time but its CommandLine is unavailable, so the fingerprint conjunct cannot be evaluated`,
      { pid, matched: { pid: true, creationTime: true } },
    );
  }
  if (!cmdline.includes(fingerprint)) {
    return verdict(
      IDENTITY.MISMATCH,
      `pid ${pid} matches on creation time but its command line does not contain the recorded fingerprint ${JSON.stringify(fingerprint)}`,
      { pid, matched: { pid: true, creationTime: true, fingerprint: false } },
    );
  }

  return verdict(IDENTITY.MATCH, `pid ${pid} matches on pid, creation time, and command-line fingerprint`, {
    pid,
    matched: { pid: true, creationTime: true, fingerprint: true },
  });
}

/**
 * The ONLY safe way to read the verdict at a kill site. Written as an explicit `=== MATCH` so a
 * caller cannot accidentally spell it `!== MISMATCH` and turn a REFUSE into a licence.
 */
function isVerifiedMatch(result) {
  return result?.verdict === IDENTITY.MATCH;
}

module.exports = {
  PROCESS_TABLE_PS_COMMAND,
  IDENTITY,
  normalizeCreationTime,
  readProcessTable,
  coerceProcessTable,
  verifyProcessIdentity,
  isVerifiedMatch,
};
