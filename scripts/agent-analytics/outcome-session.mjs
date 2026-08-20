#!/usr/bin/env node

/**
 * Per-session OUTCOME record as a JOIN projected from canonical ground truth
 * (tempdoc 622 Layer B), COMPUTED ON DEMAND.
 *
 * Hard facts come from their canonical owner — git (session-merges link), the
 * build counter, tempdoc frontmatter, governance SARIF — never re-derived by an
 * LLM. Every field is tagged `kind: 'fact'` + `source`. The LLM-judge
 * (evaluate-session -> judge-outcomes.ndjson) is folded in ONLY as the residual
 * `inference` block (kind:'inference'); it never overwrites a hard fact.
 *
 * NOT a store (tempdoc 858 §3). A JOIN over canonical sources is a pure function
 * of them, so a persisted copy is a cache with a refresh obligation nothing meets.
 * The default is to print. `--write` emits a *report* — stamped with its
 * generation time and told to be recomputed, never read as an authority.
 * Consumers recompute rather than read: import `outcomeForSession(sessionId)`
 * (hoisting `loadJoinInputs()` if you need many sessions) instead of parsing a file
 * that, by design, may not exist.
 *
 * Every field also carries `basis` (858 §9.1 — "recompute what survives; capture
 * only what time destroys, and record which you did"):
 *   - 'derived'  — rerunning this join over its canonical source reproduces the
 *                  value; a stale copy is worthless but harmless, just recompute.
 *   - 'captured' — the value CANNOT be reproduced by rerunning this join, because
 *                  the source is destroyed by time (overwritten / deleted) or
 *                  costs money to reproduce. It is carried from `observed_at`,
 *                  and a written report is the only surviving copy.
 * `basis` is the third clause of §9.1 made checkable: a consumer can tell a
 * derived value from a captured one without reading this file.
 *
 * Usage:
 *   node outcome-session.mjs --session-id <id> [--json]
 *   node outcome-session.mjs --all [--json]
 *   node outcome-session.mjs --all --write [--out <path>]
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  TELEMETRY_DIR, OUTCOMES_FILE, JUDGE_OUTCOMES_FILE, SESSION_MERGES_FILE,
  repoRoot, loadEventsFromSource, groupBySession, loadNdjsonArray, loadNdjsonMap,
  normalizeMergeLinkRow, mergeLinkKind,
} from './lib/telemetry-io.mjs';
import { atomicWriteFileSync } from './lib/hook-base.mjs';

/** Recomputable-vs-observed axis, orthogonal to `kind` (fact/inference). */
export const BASIS = Object.freeze({ DERIVED: 'derived', CAPTURED: 'captured' });

/** A fact whose canonical source survives, so recomputing reproduces it. */
const derivedFact = (value, source, note) =>
  ({ value, kind: 'fact', basis: BASIS.DERIVED, source, ...(note ? { note } : {}) });

/**
 * A fact whose source does NOT survive: recomputing this join later cannot
 * reproduce it, so the value is an observation carried from `observed_at`.
 * The note must say WHY it is unrecomputable — that is the part a reader needs.
 */
const capturedFact = (value, source, observedAt, note) =>
  ({ value, kind: 'fact', basis: BASIS.CAPTURED, source, observed_at: observedAt,
    ...(note ? { note } : {}) });

const isoOf = (ms) => new Date(ms).toISOString();

// --- canonical-source readers ---------------------------------------------

export function mergeFact(sessionId, mergeRecords) {
  // basis DERIVED: the link ledger is an accumulating file, not a shared slot that
  // the next run overwrites, and 856 made it recoverable from git commit messages
  // (recover-merge-links.mjs). Rereading it later reproduces this answer, so there
  // is nothing here to capture — 858 §9.1 lists session-merges.ndjson as conforming.
  //
  // Absent evidence is not negative evidence (tempdoc 856 §3.2). The link ledger is
  // incomplete by construction, so "no row" means never-observed, not observed-not-merged
  // — same shape as buildFact below, which reports 'unknown' for a missing input.
  const hits = (mergeRecords || []).filter(r => r && r.session_id === sessionId);
  if (hits.length === 0) {
    return derivedFact('unknown', 'git/session-merges',
      'no recorded merge link for this session — absence of a link is not evidence that nothing merged');
  }
  // A session can hold many links (56 in the current ledger); report the set, not the last.
  // Per-row source/kind come from the canonical read-side normalizer, so a recovered
  // (inference) row stays distinguishable from an observed one (§3.1); a legacy row with
  // neither field normalizes to its historical meaning rather than losing its tier.
  const merges = hits.map(normalizeMergeLinkRow).map(h => ({
    commit: h.merge_commit ?? null,
    subject: h.subject ?? null,
    source: h.source,
    kind: h.kind,
  }));
  // The block tier is DERIVED from the rows, never pinned. A fact-tier block asserting a
  // merge on inference-only evidence is the `catalog-verbatim` shape §3.1 exists to
  // prevent — the fact tier silently absorbing a derived row, at ~8.9% measured error
  // (§4). One observed link is enough to make "this session merged something" an
  // observation, so any fact row keeps the block at fact; the per-row tiers still say
  // which specific links are derived.
  const observed = merges.some(m => m.kind === 'fact');
  if (observed) {
    return { value: true, count: merges.length, merges,
      kind: 'fact', basis: BASIS.DERIVED, source: 'git/session-merges' };
  }
  // A row whose tier is `unknown` cannot support `value: true`. Rows asserting a merge from
  // a provenance we cannot recognise leave us where having no rows at all does, so this
  // reports the same 'unknown' the empty case does — this function's own invariant applied
  // to itself. The rows stay in `merges` and the note says they exist, so an unrecognised
  // writer is legible rather than swallowed.
  const derived = merges.filter(m => m.kind === 'inference');
  if (derived.length === 0) {
    const unrecognised = [...new Set(merges.map(m => m.source ?? 'none'))];
    return { value: 'unknown', count: merges.length, merges,
      kind: 'fact', basis: BASIS.DERIVED, source: 'git/session-merges',
      note: `${merges.length} link(s) recorded for this session, but none from a provenance this reader recognises (${unrecognised.join(', ')}) — rows exist, unlike the no-link case, yet unrecognised provenance cannot carry a merge claim` };
  }
  // The block source is read off what the DERIVED rows declare — the rows actually carrying
  // the claim — never inferred from their tier: a row may carry a kind that disagrees with
  // its source, and picking a source from the tier would manufacture a provenance nothing
  // observed. Name a source only when those rows agree on one AND that source is itself a
  // derivation source; otherwise name none and let the note carry what was actually seen.
  const declared = [...new Set(derived.map(m => m.source ?? null))];
  const namable = declared.length === 1 && declared[0] !== null
    && mergeLinkKind(declared[0]) === 'inference';
  return { value: true, count: merges.length, merges,
    kind: 'inference',
    basis: BASIS.DERIVED,
    source: namable ? declared[0] : null,
    note: namable
      ? `every link for this session was recovered by '${declared[0]}', not observed at merge time (~8.9% false-positive rate on single-shard commits, tempdoc 856 §4)`
      : `no link for this session was observed at merge time, and the derived links declare no single derivation source (${declared.map(s => s ?? 'none').join(', ')}) — the block names none rather than pick one`,
  };
}

/**
 * The build counter is CAPTURED, not derived. `build-fails-<id>.json` is deleted at
 * SessionEnd (`hooks/dispatch.mjs:82-92`) and pruned after 24h
 * (`hooks/intervene.mjs:199-212`), so a recomputation after the session can only ever
 * answer 'unknown' — and 'unknown' then means "deleted", not "never failed". Time
 * destroys this input exactly the way it destroys the shared SARIF below.
 */
export function buildFact(sessionId, file, nowMs = Date.now()) {
  const p = file ?? path.join(repoRoot, TELEMETRY_DIR, `build-fails-${sessionId}.json`);
  let stat;
  try {
    stat = fs.statSync(p);
  } catch {
    return capturedFact('unknown', 'build-counter', isoOf(nowMs),
      'no build-fails file at read time — the counter is deleted at SessionEnd and pruned after 24h, so after a session this reads unknown whether or not builds failed; observed_at is the read, not an observation of the session');
  }
  try {
    const j = JSON.parse(fs.readFileSync(p, 'utf8'));
    const status = j.consecutiveFailures === 0 ? 'not_failing' : 'failing';
    return capturedFact(status, 'build-counter', isoOf(stat.mtimeMs),
      `consecutiveFailures=${j.consecutiveFailures} (last-build signal, not a full green); counter file is deleted at SessionEnd, so this value is not recomputable later`);
  } catch {
    return capturedFact('unknown', 'build-counter', isoOf(stat.mtimeMs),
      'unreadable build-fails file');
  }
}

/**
 * CAPTURED. The touched-tempdoc SET is read off session events, and BOTH event lanes
 * destroy their own history:
 *   - the ndjson lane keeps exactly ONE generation — `lib/event-writer.mjs:8-14,24-33`
 *     renames `events.ndjson` onto `.prev`, overwriting the previous `.prev`, and
 *     `lib/telemetry-io.mjs:138` reads only those two files;
 *   - the OTLP `logs` stream — the events source, `telemetry-io.mjs:241` — is pruned to
 *     `RETENTION["logs"] = 2` archived generations by `otlp-sink.py:133,202-216`
 *     (`os.remove`). Its horizon is longer than the ndjson lane's; it is not unbounded.
 *
 * So a recompute after rotation sees no events for the session, and an empty list would
 * be indistinguishable from "this session touched no tempdocs" — the ambiguity §3.1
 * exists to make impossible. The no-events case therefore reports `'unknown'`, never
 * `[]`. Same shape as buildFact's deleted counter: an emptiness that means "destroyed".
 *
 * Only the SET is captured. Each row's status/checkboxes are re-read live from a file
 * that survives, so the rows stay `basis: 'derived'` — and `source` names the lane(s)
 * the evidence actually came from, so a reader can judge which horizon applies.
 */
export function tempdocFacts(sessionEvents, nowMs = Date.now()) {
  const lanes = [...new Set(sessionEvents.map(e => e?._lane).filter(Boolean))].sort();
  const source = lanes.length ? `session-events/${lanes.join('+')}` : 'session-events';
  if (sessionEvents.length === 0) {
    return capturedFact('unknown', source, isoOf(nowMs),
      'no surviving events for this session, so the touched-tempdoc set is UNKNOWN — not "no tempdocs touched". Both event lanes destroy history (ndjson keeps one generation; otlp logs keeps 2 archives), so an absence here is rotation, not evidence.');
  }
  // tempdocs this session edited (source-agnostic: scan Edit/Write file paths)
  const touched = new Set();
  for (const e of sessionEvents) {
    if (e.event !== 'post_tool_use') continue;
    if (e.tool_name !== 'Edit' && e.tool_name !== 'Write') continue;
    const fp = e.input_summary?.file_path || '';
    const m = fp.replace(/\\/g, '/').match(/docs\/tempdocs\/(\d+)[^/]*\.md$/);
    if (m) touched.add(m[1]);
  }
  // `status` is read LIVE, so a recomputation reports the tempdoc's CURRENT status, not
  // its status while the session ran. That is a value changing over time, not one
  // destroyed by it, so recompute is right for the row — the note keeps a reader from
  // mistaking it for a session-time observation.
  const liveNote = 'status/checkboxes are read live from the tempdoc — current truth, not the value while the session ran';
  const out = [];
  for (const num of touched) {
    const matches = (() => {
      try { return fs.readdirSync(path.join(repoRoot, 'docs', 'tempdocs'))
        .filter(f => f.startsWith(`${num}-`) && f.endsWith('.md')); } catch { return []; }
    })();
    if (matches.length === 0) {
      out.push({ number: num, status: 'unknown', kind: 'fact', basis: BASIS.DERIVED,
        source: 'tempdoc-frontmatter', note: 'no tempdoc file with this number at read time' });
      continue;
    }
    const body = fs.readFileSync(path.join(repoRoot, 'docs', 'tempdocs', matches[0]), 'utf8');
    const fm = body.match(/^---\n([\s\S]*?)\n---/);
    let status = fm ? (fm[1].match(/^status:\s*["']?([^"'\n]+)/m)?.[1]?.trim() ?? null) : null;
    // some tempdocs write a paragraph-length status; keep a short token for the join
    if (status && status.length > 50) status = status.slice(0, 47).trimEnd() + '…';
    const open = (body.match(/^\s*[-*]\s*\[ \]/gm) || []).length;
    const done = (body.match(/^\s*[-*]\s*\[[xX]\]/gm) || []).length;
    out.push({ number: num, status, checkboxes_done: done, checkboxes_total: open + done,
      kind: 'fact', basis: BASIS.DERIVED, source: 'tempdoc-frontmatter', note: liveNote });
  }
  return capturedFact(out, source, isoOf(nowMs),
    'the touched-tempdoc SET is captured from destructible session events — an empty list means none were touched WITHIN the surviving event window, which rotation can truncate. Each row\'s status is derived (re-read live).');
}

/**
 * Governance SARIF is CAPTURED — the one exception 858 §3.1 had to admit.
 * `scripts/governance/run.mjs:52` defaults `--out` to `tmp/governance-report.sarif`:
 * ONE shared file, overwritten by every gate run and not session-keyed. Attribution is
 * an mtime-inside-the-session-window comparison, and the next gate run destroys the
 * mtime that made it attributable. Recomputed a week later this says nothing — so the
 * value is stamped with its observation time and marked captured rather than silently
 * re-derived into a false answer.
 */
export function gatesFact(window, file, nowMs = Date.now()) {
  const p = file ?? path.join(repoRoot, 'tmp', 'governance-report.sarif');
  const why = 'governance SARIF is a single shared file overwritten by every gate run (scripts/governance/run.mjs:52), so this observation cannot be recomputed later';
  let mtime;
  try {
    mtime = fs.statSync(p).mtimeMs;
  } catch {
    return capturedFact('unknown', 'governance-sarif', isoOf(nowMs),
      `no SARIF present at read time — ${why}`);
  }
  if (!window || mtime < window.start || mtime > window.end) {
    return capturedFact('unknown', 'governance-sarif', isoOf(mtime),
      `SARIF mtime outside session window — not attributable to this session; ${why}`);
  }
  try {
    const j = JSON.parse(fs.readFileSync(p, 'utf8'));
    const results = (j.runs || []).flatMap(r => r.results || []);
    return capturedFact(results.length === 0 ? 'pass' : 'fail', 'governance-sarif', isoOf(mtime),
      `${results.length} findings; SARIF mtime within session window; ${why}`);
  } catch {
    return capturedFact('unknown', 'governance-sarif', isoOf(mtime), `unreadable SARIF; ${why}`);
  }
}

export function inferenceBlock(judge) {
  if (!judge) return null;
  // demote every judge field to kind:'inference'.
  // basis CAPTURED for a different reason than the two above: the judge verdict is a PAID
  // derivation. This join cannot reproduce it — it reads evaluate-session's cache — and
  // reproducing it costs money rather than milliseconds, which 858 §3.1 names as the other
  // legitimate reason to carry an observation instead of recomputing it.
  return {
    kind: 'inference', basis: BASIS.CAPTURED, source: 'llm-judge',
    observed_at: judge.ts ?? null,
    note: 'paid LLM derivation, cached by evaluate-session; recomputing costs money, not milliseconds (tempdoc 858 §3.1)',
    task_completion: judge.task_completion ?? null,
    task_completion_rationale: judge.task_completion_rationale ?? null,
    task_type: judge.task_type ?? null,
    tests_added: judge.tests_added ?? null,
    build_passed: judge.build_passed ?? null,
    confidence: judge.confidence ?? null,
  };
}

// --- basis bookkeeping -----------------------------------------------------

function collectBasis(node, prefix, wanted, out) {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node)) {
    node.forEach((v, i) => collectBasis(v, `${prefix}[${i}]`, wanted, out));
    return;
  }
  // A tagged node is a leaf for this walk: its children are values, not sub-facts.
  if (typeof node.basis === 'string') {
    if (node.basis === wanted) out.push(prefix);
    return;
  }
  for (const [k, v] of Object.entries(node)) {
    collectBasis(v, prefix ? `${prefix}.${k}` : k, wanted, out);
  }
}

/** Dotted paths of every field in `record` that is `basis: 'captured'`. */
export function capturedFields(record) {
  const out = [];
  collectBasis(record, '', BASIS.CAPTURED, out);
  return out;
}

/**
 * The generation stamp. It exists so a reader who finds this record in a file can tell
 * it is a REPORT and not maintained state.
 *
 * Per-record, not a file header, for two reasons. NDJSON has no header slot: every
 * consumer reads this file through the shared line-per-record loaders (`loadNdjsonMap` /
 * `loadNdjsonArray`, lib/telemetry-io.mjs), which would parse a header line as a record
 * and — having no session_id — drop or mis-key it. And the write path upserts, so rows
 * carried over from an earlier run must keep THEIR generation time; a file-level header
 * would stamp the newest time onto rows it never regenerated.
 *
 * Naming the loaders rather than their callers is deliberate: a consumer list is a
 * maintained list and decays (858 §9.1 — this comment's first draft already named a
 * file retired later the same day). The loaders are the choke point all of them share.
 *
 * `captured_fields` is the part that does real work: in a stale copy those are the only
 * values that cannot simply be recomputed, so they are the only reason to keep the file.
 */
export function reportStamp(record, generatedAt) {
  return {
    kind: 'report',
    generated_at: generatedAt,
    generator: 'scripts/agent-analytics/outcome-session.mjs',
    recompute: `node scripts/agent-analytics/outcome-session.mjs --session-id ${record.session_id} --json`,
    captured_fields: capturedFields(record),
    note: 'generated report, not maintained state — outcomes are a view (tempdoc 858 §3). Every field except captured_fields is recomputable from its canonical source; recompute rather than trust this copy.',
  };
}

// --- join ------------------------------------------------------------------

export function buildOutcome(sessionId, sessionEvents, mergeRecords, judgeMap, nowMs = Date.now()) {
  const tsMs = sessionEvents.map(e => Date.parse(e.ts)).filter(n => !Number.isNaN(n));
  const window = tsMs.length ? { start: Math.min(...tsMs), end: Math.max(...tsMs) } : null;
  const record = {
    session_id: sessionId,
    // legacy alias of report.generated_at, kept for pre-858 readers
    ts: isoOf(nowMs),
    facts: {
      merged: mergeFact(sessionId, mergeRecords),
      build_last_status: buildFact(sessionId, undefined, nowMs),
      tempdocs: tempdocFacts(sessionEvents, nowMs),
      gates: gatesFact(window, undefined, nowMs),
    },
    inference: inferenceBlock(judgeMap.get(sessionId)),
  };
  record.report = reportStamp(record, isoOf(nowMs));
  return record;
}

/**
 * Load the three shared inputs of the join, once. Exported separately from
 * `outcomeForSession` because loading dominates by ~35x: measured 729-828 ms to load,
 * against 21-24 ms to join ALL 11 sessions it returned. A consumer computing outcomes
 * for many sessions must hoist this out of its loop.
 *
 * Measured 2026-08-19 against a copy of the main checkout's corpus — 206.1 MB / 29,208
 * events: 12.8 MB in the ndjson lane plus 193.3 MB across the OTLP `logs` AND `metrics`
 * streams, both of which loadEventsFromOtlp reads (telemetry-io.mjs:241,251). Reproduce
 * by copying those files under a scratch repo root and timing loadJoinInputs() there.
 * (An earlier figure of ~0.5s/87 MB in this comment was measured against a corpus that
 * omitted the metrics stream, so it under-counted the input by more than half.)
 */
export function loadJoinInputs() {
  // events from both sources (parallel-run transition): union by session. The lane is
  // tagged on the way in because the two lanes have DIFFERENT destruction horizons
  // (one generation vs 2 archives), and tempdocFacts reports which one its evidence
  // came from. The loaders return freshly parsed objects, so tagging mutates nothing shared.
  const tag = (events, lane) => { for (const e of events) if (e) e._lane = lane; return events; };
  const events = [
    ...tag(loadEventsFromSource('ndjson'), 'ndjson'),
    ...tag(loadEventsFromSource('otlp'), 'otlp'),
  ];
  return {
    sessions: groupBySession(events),
    mergeRecords: loadNdjsonArray(path.join(repoRoot, TELEMETRY_DIR, SESSION_MERGES_FILE)),
    judgeMap: loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, JUDGE_OUTCOMES_FILE)),
  };
}

/**
 * The consumer entry point for 858 §3's "consumers recompute rather than read":
 * ONE session's record, computed from the canonical sources. Writes nothing, never
 * calls process.exit, parses no argv — safe to import. Deterministic given its inputs
 * and `nowMs`, which is the property that lets a consumer stop caching the result.
 *
 * The CLI below goes through this same function, so there is no second code path to
 * drift from what a consumer gets.
 */
export function outcomeForSession(sessionId, { inputs, nowMs = Date.now() } = {}) {
  const src = inputs ?? loadJoinInputs();
  return buildOutcome(sessionId, src.sessions.get(sessionId) || [],
    src.mergeRecords, src.judgeMap, nowMs);
}

/**
 * Opt-in report emission. Atomic (`atomicWriteFileSync`, same reason record-merge.mjs
 * uses it): the previous full-rewrite left a torn-file window in which a concurrent
 * reader saw a truncated NDJSON.
 */
export function writeReport(records, file) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const existing = loadNdjsonMap(file);
  for (const r of records) existing.set(r.session_id, r);
  atomicWriteFileSync(file, [...existing.values()].map(r => JSON.stringify(r)).join('\n') + '\n');
}

function main() {
  const args = process.argv.slice(2);
  const json = args.includes('--json');
  const all = args.includes('--all');
  const write = args.includes('--write');
  const sidIdx = args.indexOf('--session-id');
  const sessionId = sidIdx !== -1 ? args[sidIdx + 1] : null;
  const outIdx = args.indexOf('--out');
  const outFile = outIdx !== -1 && args[outIdx + 1]
    ? path.resolve(args[outIdx + 1])
    : path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  if (!all && !sessionId) {
    console.error('Usage: node outcome-session.mjs --session-id <id> | --all [--json] [--write [--out <path>]]');
    process.exit(1);
  }

  const inputs = loadJoinInputs();
  const ids = sessionId ? [sessionId] : [...inputs.sessions.keys()];
  // one generation timestamp for the whole batch, so a report's rows agree with each other
  const nowMs = Date.now();
  const records = ids.map(id => outcomeForSession(id, { inputs, nowMs }));

  if (write) writeReport(records, outFile);

  if (json) {
    process.stdout.write(JSON.stringify(sessionId ? records[0] : records, null, 2) + '\n');
  } else {
    let anyCaptured = false;
    for (const r of records) {
      const td = r.facts.tempdocs;
      // 'unknown' (evidence rotated away) must not print like an empty set
      const t = Array.isArray(td.value)
        ? (td.value.map(x => `${x.number}:${x.status ?? '?'}(${x.checkboxes_done ?? 0}/${x.checkboxes_total ?? 0})`).join(' ') || 'none')
        : String(td.value);
      const m = r.facts.merged;
      const mergedCol = m.value === true
        ? `true(${m.count}${m.kind === 'inference' ? ',inferred' : ''})`
        : `${m.value}${m.count ? `(${m.count} unrecognised)` : ''}`;
      const mark = f => (f && f.basis === BASIS.CAPTURED ? '*' : '');
      if (r.report.captured_fields.length > 0) anyCaptured = true;
      console.log(`${r.session_id.slice(0, 8)}  merged=${mergedCol}  build=${r.facts.build_last_status.value}${mark(r.facts.build_last_status)}  gates=${r.facts.gates.value}${mark(r.facts.gates)}  tempdocs=[${t}]${mark(td)}  inference=${r.inference ? r.inference.task_completion : 'none'}${mark(r.inference)}`);
    }
    if (anyCaptured) {
      console.log('\n* captured at generation time — not recomputable later (see report.captured_fields)');
    }
    if (write) {
      console.log(`\nReport (generated, not an authority) written to ${path.relative(repoRoot, outFile) || outFile}`);
    } else {
      console.log('\nOutcomes are a view — computed on demand, nothing written. Use --write [--out <path>] to emit a report.');
    }
  }
}

// CLI entry only when run directly, so the test can import mergeFact without
// triggering a real telemetry read on import.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
