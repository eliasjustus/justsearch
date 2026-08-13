#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
/**
 * answer-shape-ab.mjs — the tempdoc 822 §1.5 live A/B for `AnswerShapeGrammar` (slice S6, PHASE 2).
 *
 * WHAT IT DOES
 *   12 prompts x 2 arms x 2 repeats = 48 dispatches against a RUNNING dev stack with a LOADED
 *   model, interleaved A,B,A,B per prompt, capturing every SSE stream, then scoring M1-M9 and the
 *   four acceptance criteria from §1.5. No eyeballing: every number comes from the captured
 *   streams.
 *
 *   Arms differ ONLY by one request-body flag: arm A (the control) sends nothing, which is the
 *   shipped default while the fragment is provisional; arm B sends `answerShapeGrammar: true` to
 *   opt into the candidate wording. Same process, same corpus, same warm model for both arms —
 *   which is what makes the interleaving §1.5 mandates actually possible, and what a
 *   rebuild-per-arm switch cannot give.
 *
 * USAGE (orchestrator, phase 2)
 *   Preconditions: dev stack leased (leaseDurationSec >= 3600), `ai_activate` done (NOT
 *   AI_OFFLINE), an indexed corpus of >= 200 documents that the 12 questions have answers in,
 *   and slices S1-S3 + S5 already in the running build.
 *
 *   FRESHNESS (consult-register `dev-stack-stale-jar`): run `./gradlew.bat :modules:ui:installDist`
 *   BEFORE (re)starting the stack. Without it the head process can run the previous jar and BOTH
 *   arms would be the control — a null result that looks like a real one. Cheap witness: arm B's
 *   answers should show at least one heading somewhere in the multi-part six; 0/12 headed across
 *   the whole arm is a stale-jar smell, not a model verdict.
 *
 *   1) node scripts/bench/answer-shape-ab.mjs plan --out <dir>
 *        Writes <dir>/plan.json: the 12 prompts (6 multi-part + 6 single-fact) and the fixed
 *        48-dispatch interleaved schedule. EDIT the prompts to match the indexed corpus before
 *        running — the split is the point (arm B must gain structure on the multi-part six and
 *        must NOT gain it on the single-fact six). Record the final plan.json in the tempdoc log.
 *   2) node scripts/bench/answer-shape-ab.mjs run --out <dir> [--api http://127.0.0.1:PORT]
 *        Executes the schedule; writes <dir>/runs/<arm>-<promptId>-r<repeat>.jsonl (one JSON
 *        object per SSE frame) and <dir>/manifest.json (corpus fingerprint before + after, so a
 *        re-index mid-campaign is detectable rather than silent). Resumable: an existing capture
 *        is skipped unless --force.
 *   3) node scripts/bench/answer-shape-ab.mjs score --out <dir>
 *        Writes <dir>/metrics.json + <dir>/report.md and prints the four acceptance lines with
 *        their measured numbers. Exit code 0 = all four hold, 1 = at least one fails (then
 *        iterate the fragment wording, <= 3 cycles, and report to the owner rather than
 *        iterating further).
 *
 *   --api defaults to $JUSTSEARCH_API_BASE, else http://127.0.0.1:8080.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *   Start, stop, lease or take over the dev stack; index anything; edit any source file. Stack
 *   lifecycle stays with the orchestrator.
 */

import { mkdir, readFile, writeFile, access } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

// ---------------------------------------------------------------------------
// Constants mirrored from the shipped authorities (kept next to their source so
// a drift is a one-line fix, not a re-derivation).
// ---------------------------------------------------------------------------

/** evidenceProjection.ts: TIER_HIGH / TIER_MEDIUM — a cited sentence is similarity >= 0.5. */
const TIER_HIGH = 0.6;
const TIER_MEDIUM = 0.5;
/** evidenceProjection.ts: DOC_LEVEL_CHUNK_SENTINEL. */
const DOC_LEVEL_CHUNK_SENTINEL = -1;
/** AnswerShapeGrammar.ARM_SWITCH_KEY. */
const ARM_SWITCH_KEY = 'answerShapeGrammar';
const SHAPE_ID = 'core.rag-ask';
const REPEATS = 2;

/**
 * §1.5's corpus: six multi-part questions, six single-fact ones. These defaults are shaped for
 * THIS repository's own docs; replace them in plan.json with questions the indexed corpus
 * actually answers before running, keeping the 6/6 split and the `kind` field.
 */
const DEFAULT_PROMPTS = [
  { id: 'mp1', kind: 'multi-part', text: 'How does the head-worker split work, and what crosses the boundary?' },
  { id: 'mp2', kind: 'multi-part', text: 'What are the differences between the ask tier and the agent tier?' },
  { id: 'mp3', kind: 'multi-part', text: 'Walk me through what happens when a document is indexed.' },
  { id: 'mp4', kind: 'multi-part', text: 'How does retrieval combine lexical and semantic scoring?' },
  { id: 'mp5', kind: 'multi-part', text: 'What are the differences between the summarize and extract shapes?' },
  { id: 'mp6', kind: 'multi-part', text: 'How does the citation pipeline turn a model answer into cited sentences?' },
  { id: 'sf1', kind: 'single-fact', text: 'What address does the local API bind to?' },
  { id: 'sf2', kind: 'single-fact', text: 'Which file is the head process entry point?' },
  { id: 'sf3', kind: 'single-fact', text: 'What is the default maximum token count for a chat response?' },
  { id: 'sf4', kind: 'single-fact', text: 'Which module manages the inference server process?' },
  { id: 'sf5', kind: 'single-fact', text: 'What is the default number of retrieved chunks?' },
  { id: 'sf6', kind: 'single-fact', text: 'Which command runs the unit test suite?' },
];

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const [, , cmd, ...rest] = argv;
  const opts = { cmd, out: null, api: process.env.JUSTSEARCH_API_BASE || 'http://127.0.0.1:8080', force: false };
  for (let i = 0; i < rest.length; i += 1) {
    const a = rest[i];
    if (a === '--out') opts.out = rest[++i];
    else if (a === '--api') opts.api = rest[++i];
    else if (a === '--force') opts.force = true;
    else throw new Error(`unknown argument: ${a}`);
  }
  if (!['plan', 'run', 'score'].includes(opts.cmd ?? '')) {
    throw new Error('usage: answer-shape-ab.mjs <plan|run|score> --out <dir> [--api <base>] [--force]');
  }
  if (!opts.out) throw new Error('--out <dir> is required');
  opts.api = opts.api.replace(/\/+$/, '');
  return opts;
}

/** The fixed schedule: per prompt, A,B,A,B — interleaved, never blocked by arm (§1.5 reporting). */
function buildSchedule(prompts) {
  const schedule = [];
  for (const p of prompts) {
    for (let repeat = 1; repeat <= REPEATS; repeat += 1) {
      for (const arm of ['A', 'B']) {
        schedule.push({ arm, promptId: p.id, kind: p.kind, repeat });
      }
    }
  }
  return schedule;
}

async function cmdPlan(opts) {
  await mkdir(opts.out, { recursive: true });
  const planPath = path.join(opts.out, 'plan.json');
  if (existsSync(planPath) && !opts.force) {
    console.log(`plan.json already exists at ${planPath} (use --force to overwrite)`);
    return 0;
  }
  const plan = {
    tempdoc: '822 §1.5',
    shapeId: SHAPE_ID,
    armSwitchKey: ARM_SWITCH_KEY,
    arms: { A: 'flag absent (control = the shipped default)', B: `body.${ARM_SWITCH_KEY} = true` },
    repeats: REPEATS,
    prompts: DEFAULT_PROMPTS,
    schedule: buildSchedule(DEFAULT_PROMPTS),
  };
  await writeFile(planPath, `${JSON.stringify(plan, null, 2)}\n`, 'utf8');
  console.log(`wrote ${planPath} — EDIT the 12 prompts to match the indexed corpus, then re-run 'run'.`);
  console.log(`dispatches: ${plan.schedule.length}`);
  return 0;
}

// ---------------------------------------------------------------------------
// run
// ---------------------------------------------------------------------------

async function loadPlan(outDir) {
  const planPath = path.join(outDir, 'plan.json');
  try {
    await access(planPath);
  } catch {
    throw new Error(`no plan.json in ${outDir} — run the 'plan' subcommand first`);
  }
  const plan = JSON.parse(await readFile(planPath, 'utf8'));
  const kinds = plan.prompts.map((p) => p.kind);
  const multi = kinds.filter((k) => k === 'multi-part').length;
  const single = kinds.filter((k) => k === 'single-fact').length;
  if (multi !== 6 || single !== 6) {
    throw new Error(`plan must hold 6 multi-part + 6 single-fact prompts; found ${multi}/${single}`);
  }
  return plan;
}

/** Corpus fingerprint — the `interrogate-results` guard: a re-index between arms is not silent. */
async function corpusFingerprint(api) {
  try {
    const res = await fetch(`${api}/api/status`, { signal: AbortSignal.timeout(15_000) });
    if (!res.ok) return { error: `status ${res.status}` };
    const body = await res.json();
    return {
      documentCount: body.documentCount ?? body.totalDocuments ?? body.indexedDocuments ?? null,
      raw: body,
      at: new Date().toISOString(),
    };
  } catch (err) {
    return { error: String(err), at: new Date().toISOString() };
  }
}

/** One dispatch: POST /api/chat/dispatch, capture every SSE frame as a JSONL line. */
async function dispatch(api, prompt, arm, conversationId) {
  const body = { shapeId: SHAPE_ID, question: prompt.text, docIds: [], conversationId };
  if (arm === 'B') body[ARM_SWITCH_KEY] = true;

  const res = await fetch(`${api}/api/chat/dispatch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(300_000),
  });
  if (!res.ok || !res.body) {
    throw new Error(`dispatch failed: HTTP ${res.status} ${await res.text().catch(() => '')}`);
  }

  const frames = [];
  const decoder = new TextDecoder();
  const reader = res.body.getReader();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = drainFrames(buffer, frames);
  }
  buffer += decoder.decode();
  buffer = drainFrames(buffer, frames);
  const tail = parseFrame(buffer);
  if (tail) frames.push(tail);
  return frames;
}

/** Split complete SSE frames off the front of `buffer`; returns the unconsumed remainder. */
function drainFrames(buffer, out) {
  const boundary = /\r\n\r\n|\n\n|\r\r/g;
  let rest = buffer;
  for (;;) {
    boundary.lastIndex = 0;
    const m = boundary.exec(rest);
    if (!m) return rest;
    const frame = parseFrame(rest.slice(0, m.index));
    if (frame) out.push(frame);
    rest = rest.slice(m.index + m[0].length);
  }
}

function parseFrame(raw) {
  let event = 'message';
  const dataLines = [];
  for (const line of raw.split(/\r\n|\n|\r/)) {
    if (line.startsWith(':') || line.length === 0) continue;
    const idx = line.indexOf(':');
    const field = idx === -1 ? line : line.slice(0, idx);
    let value = idx === -1 ? '' : line.slice(idx + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') dataLines.push(value);
  }
  if (dataLines.length === 0) return null;
  const data = dataLines.join('\n');
  let payload;
  try {
    payload = JSON.parse(data);
  } catch {
    payload = { _unparsed: data };
  }
  return { event, payload };
}

const captureName = (r) => `${r.arm}-${r.promptId}-r${r.repeat}.jsonl`;

async function cmdRun(opts) {
  const plan = await loadPlan(opts.out);
  const runsDir = path.join(opts.out, 'runs');
  await mkdir(runsDir, { recursive: true });
  const byId = new Map(plan.prompts.map((p) => [p.id, p]));

  const manifest = {
    api: opts.api,
    startedAt: new Date().toISOString(),
    corpusBefore: await corpusFingerprint(opts.api),
    dispatches: [],
  };

  let n = 0;
  for (const item of plan.schedule) {
    n += 1;
    const file = path.join(runsDir, captureName(item));
    if (existsSync(file) && !opts.force) {
      console.log(`[${n}/${plan.schedule.length}] skip (already captured) ${captureName(item)}`);
      continue;
    }
    const prompt = byId.get(item.promptId);
    if (!prompt) throw new Error(`schedule references unknown prompt ${item.promptId}`);
    const conversationId = `ab-${item.arm}-${item.promptId}-r${item.repeat}-${Date.now()}`;
    const startedAt = Date.now();
    const frames = await dispatch(opts.api, prompt, item.arm, conversationId);
    const took = Date.now() - startedAt;
    await writeFile(file, `${frames.map((f) => JSON.stringify(f)).join('\n')}\n`, 'utf8');
    manifest.dispatches.push({ ...item, conversationId, tookMs: took, frames: frames.length });
    console.log(`[${n}/${plan.schedule.length}] ${captureName(item)} — ${frames.length} frames, ${took} ms`);
  }

  manifest.corpusAfter = await corpusFingerprint(opts.api);
  manifest.finishedAt = new Date().toISOString();
  await writeFile(path.join(opts.out, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  console.log(`captures in ${runsDir}; manifest written.`);
  return 0;
}

// ---------------------------------------------------------------------------
// score — M1..M9 and the four acceptance criteria
// ---------------------------------------------------------------------------

/** evidenceProjection.ts countSentences, verbatim in behaviour. */
function countSentences(text) {
  const t = (text ?? '').trim();
  if (t.length === 0) return 0;
  const terminators = t.match(/[.!?]+(?=\s|$)/g);
  return Math.max(terminators ? terminators.length : 0, 1);
}

/** evidenceProjection.ts groundingCoverage, for a RAG (grounded-index) answer. */
function groundingCoverage(similarities, answerText) {
  let grounded = 0;
  let weak = 0;
  for (const s of similarities) {
    if (s >= TIER_HIGH) grounded += 1;
    else if (s >= TIER_MEDIUM) weak += 1;
  }
  const cited = grounded + weak;
  return { grounded, weak, cited, total: Math.max(countSentences(answerText), cited) };
}

/**
 * evidenceProjection.ts answerFrame for core.rag-ask (grounded-index), settled = true. With
 * `settled` true both of the cited === 0 branches (doc-level and chunk-precise) resolve to
 * `sourced`, so `chunkPrecise` cannot change the verdict here — it is reported for the record.
 */
function answerFrame(sourceCount, coverage) {
  if (sourceCount === 0) return 'ungrounded';
  if (coverage.cited > 0 && coverage.cited < coverage.total) return 'partially-grounded';
  if (coverage.cited === 0) return 'sourced';
  return 'grounded';
}

/** Count balanced inline-code spans, ignoring fenced blocks. */
function inlineCodeRuns(text) {
  const withoutFences = text.replace(/```[\s\S]*?```/g, '');
  const matches = withoutFences.match(/`[^`\n]+`/g);
  return matches ? matches.length : 0;
}

function metricsFor(frames) {
  const done = frames.find((f) => f.event === 'done')?.payload ?? {};
  const chunkText = frames
    .filter((f) => f.event === 'chunk')
    .map((f) => f.payload?.text ?? '')
    .join('');
  const answer = typeof done.finalResponse === 'string' && done.finalResponse.length > 0
    ? done.finalResponse
    : chunkText;

  const citationsEvent = frames.find((f) => f.event === 'rag.citations')?.payload?.citations;
  const sources = Array.isArray(done.citations) && done.citations.length > 0
    ? done.citations
    : Array.isArray(citationsEvent) ? citationsEvent : [];
  const sourceCount = sources.length;
  const chunkPrecise = sources.some((s) => (s?.chunkIndex ?? DOC_LEVEL_CHUNK_SENTINEL) !== DOC_LEVEL_CHUNK_SENTINEL);

  const claimMatches = done.claimMatches
    ?? frames.find((f) => f.event === 'rag.citation_matches')?.payload
    ?? {};
  const matches = Array.isArray(claimMatches.matches) ? claimMatches.matches : [];
  const coverage = groundingCoverage(matches.map((m) => Number(m.similarity) || 0), answer);

  const brackets = [...answer.matchAll(/\[(\d+)\]/g)].map((m) => Number(m[1]));
  const surviving = brackets.filter((n) => n < 1 || n > sourceCount).length;

  return {
    m1Headings: (answer.match(/^#{1,6} /gm) || []).length,
    m2InlineCode: inlineCodeRuns(answer),
    m3Fences: Math.floor((answer.match(/```/g) || []).length / 2),
    m4ListLines: (answer.match(/^\s*([-*]|\d+\.)\s/gm) || []).length,
    m5Chars: answer.length,
    m5Sentences: countSentences(answer),
    m6VerifiedClaims: matches.length,
    m7Coverage: coverage.total === 0 ? 0 : coverage.cited / coverage.total,
    m8Frame: answerFrame(sourceCount, coverage),
    chunkPrecise,
    m9RawBrackets: surviving,
    sourceCount,
    // §1.6 falsifier: an answer cut off mid-structure at the token wall.
    truncatedMidStructure:
      (answer.match(/```/g) || []).length % 2 === 1 || /(^|\n)#{1,6} [^\n]*$/.test(answer.trimEnd()),
  };
}

const median = (xs) => {
  if (xs.length === 0) return 0;
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
};
const pctDelta = (b, a) => (a === 0 ? (b === 0 ? 0 : Infinity) : (b - a) / a);

async function cmdScore(opts) {
  const plan = await loadPlan(opts.out);
  const runsDir = path.join(opts.out, 'runs');
  const byId = new Map(plan.prompts.map((p) => [p.id, p]));

  const rows = [];
  for (const item of plan.schedule) {
    const file = path.join(runsDir, captureName(item));
    if (!existsSync(file)) throw new Error(`missing capture ${file} — 'run' is incomplete`);
    const frames = (await readFile(file, 'utf8'))
      .split('\n')
      .filter((l) => l.trim().length > 0)
      .map((l) => JSON.parse(l));
    rows.push({ ...item, kind: byId.get(item.promptId).kind, ...metricsFor(frames) });
  }

  const twin = (arm, promptId, repeat) =>
    rows.find((r) => r.arm === arm && r.promptId === promptId && r.repeat === repeat);
  const pick = (arm, kind) => rows.filter((r) => r.arm === arm && (!kind || r.kind === kind));

  // 1 — structure gained where it belongs (AMENDED post-cycle-1, owner-ratified; §1.5).
  // Heading half: only QUALIFYING multi-part twins count. A prompt qualifies iff at least one of
  // its four runs (either arm, either repeat) headed at all — a prompt the corpus answers in one
  // part is disqualified, not failed. The qualifier is lenient by construction: arm A's headings
  // qualify a prompt exactly as arm B's do, so it can never manufacture a pass.
  const multiPromptIds = [...new Set(pick('B', 'multi-part').map((r) => r.promptId))];
  const qualifyingPrompts = multiPromptIds.filter((id) =>
    rows.some((r) => r.promptId === id && r.m1Headings >= 1),
  );
  const disqualifiedPrompts = multiPromptIds.filter((id) => !qualifyingPrompts.includes(id));
  const qualifyingTwins = pick('B', 'multi-part').filter((r) => qualifyingPrompts.includes(r.promptId));
  const headedB = qualifyingTwins.filter((r) => r.m1Headings >= 1).length;
  const enoughQualifying = qualifyingTwins.length >= 6;
  const headingHalf = enoughQualifying && headedB >= Math.ceil((2 / 3) * qualifyingTwins.length);
  // Backtick half: NON-REGRESSION against a saturated baseline — ties and wins both pass.
  const backtickRegressions = pick('B').filter(
    (r) => r.m2InlineCode < twin('A', r.promptId, r.repeat).m2InlineCode,
  );
  const backtickHalf = backtickRegressions.length <= 2;
  const c1 = headingHalf && backtickHalf;

  // 2 — no fabricated structure (single-fact).
  const bSingle = pick('B', 'single-fact');
  const aSingle = pick('A', 'single-fact');
  const headedSingle = bSingle.filter((r) => r.m1Headings >= 1).length;
  const lenGrowth = pctDelta(median(bSingle.map((r) => r.m5Chars)), median(aSingle.map((r) => r.m5Chars)));
  const c2 = headedSingle <= 1 && lenGrowth <= 0.25;

  // 3 — substance does not regress.
  const claimsDelta = pctDelta(median(pick('B').map((r) => r.m6VerifiedClaims)), median(pick('A').map((r) => r.m6VerifiedClaims)));
  const coverageDelta = pctDelta(median(pick('B').map((r) => r.m7Coverage)), median(pick('A').map((r) => r.m7Coverage)));
  // AMENDED: a collapse is a within-twin drop of >= 2 claims AND >= 50 % relative. A 1 -> 0 twin is
  // matcher variance at a knife edge, not a substance regression; the medians above are the signal.
  const claimCollapses = pick('B').filter((r) => {
    const a = twin('A', r.promptId, r.repeat).m6VerifiedClaims;
    const drop = a - r.m6VerifiedClaims;
    return a > 0 && drop >= 2 && drop / a >= 0.5;
  });
  const c3 = Math.abs(claimsDelta) <= 0.15 && Math.abs(coverageDelta) <= 0.15 && claimCollapses.length === 0;

  // 4 — frame verdicts unchanged distributionally.
  const buckets = ['grounded', 'partially-grounded', 'sourced', 'ungrounded'];
  const histogram = (arm) =>
    Object.fromEntries(buckets.map((b) => [b, pick(arm).filter((r) => r.m8Frame === b).length]));
  const histA = histogram('A');
  const histB = histogram('B');
  const worstShift = Math.max(...buckets.map((b) => Math.abs(histB[b] - histA[b])));
  const newlyUngrounded = pick('B').filter(
    (r) => r.m8Frame === 'ungrounded' && twin('A', r.promptId, r.repeat).m8Frame !== 'ungrounded',
  );
  const bracketsA = pick('A').reduce((s, r) => s + r.m9RawBrackets, 0);
  const bracketsB = pick('B').reduce((s, r) => s + r.m9RawBrackets, 0);
  const c4 = worstShift <= 2 && newlyUngrounded.length === 0 && bracketsB <= bracketsA;

  const truncated = pick('B').filter((r) => r.truncatedMidStructure).length;

  const result = {
    generatedAt: new Date().toISOString(),
    runs: rows,
    acceptance: {
      c1_structureWhereItBelongs: {
        pass: c1,
        headingHalf: {
          pass: headingHalf,
          qualifyingPrompts,
          disqualifiedPrompts,
          qualifyingTwins: qualifyingTwins.length,
          headed: headedB,
          required: enoughQualifying ? Math.ceil((2 / 3) * qualifyingTwins.length) : null,
          floorMet: enoughQualifying,
        },
        backtickHalf: {
          pass: backtickHalf,
          regressionsOf24: backtickRegressions.length,
          allowed: 2,
          regressed: backtickRegressions.map((r) => captureName(r)),
        },
      },
      c2_noFabricatedStructure: { pass: c2, headedSingleFactOf12: headedSingle, medianLengthGrowth: lenGrowth },
      c3_substanceHolds: {
        pass: c3,
        medianClaimsDelta: claimsDelta,
        medianCoverageDelta: coverageDelta,
        claimCollapses: claimCollapses.map((r) => captureName(r)),
      },
      c4_framesUnchanged: {
        pass: c4,
        histogramA: histA,
        histogramB: histB,
        worstBucketShift: worstShift,
        newlyUngrounded: newlyUngrounded.map((r) => captureName(r)),
        rawBracketsA: bracketsA,
        rawBracketsB: bracketsB,
      },
    },
    // §1.6: > 1 of 24 truncated arm-B runs is the evidence to raise DEFAULT_MAX_TOKENS.
    tokenWall: { armBTruncatedMidStructure: truncated, raiseBudgetEvidence: truncated > 1 },
  };

  await writeFile(path.join(opts.out, 'metrics.json'), `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  await writeFile(path.join(opts.out, 'report.md'), renderReport(result), 'utf8');

  const all = [c1, c2, c3, c4];
  console.log(renderReport(result));
  return all.every(Boolean) ? 0 : 1;
}

function renderReport(result) {
  const a = result.acceptance;
  const pct = (x) => `${(x * 100).toFixed(1)} %`;
  const mark = (ok) => (ok ? 'PASS' : 'FAIL');
  return [
    '# 822 §1.5 — AnswerShapeGrammar A/B',
    '',
    `Generated ${result.generatedAt} over ${result.runs.length} dispatches.`,
    '',
    '| # | Acceptance criterion | Measured | Verdict |',
    '|---|---|---|---|',
    `| 1 | AMENDED — headings >= 1 in >= 2/3 of qualifying twins (floor 6); backtick spans regress in <= 2/24 | ${a.c1_structureWhereItBelongs.headingHalf.headed}/${a.c1_structureWhereItBelongs.headingHalf.qualifyingTwins} qualifying headed (need ${a.c1_structureWhereItBelongs.headingHalf.required ?? 'n/a'}), ${a.c1_structureWhereItBelongs.backtickHalf.regressionsOf24}/24 backtick regressions | ${mark(a.c1_structureWhereItBelongs.pass)} |`,
    `| 2 | single-fact: arm B headings >= 1 in <= 1/12, median length growth <= 25 % | ${a.c2_noFabricatedStructure.headedSingleFactOf12}/12 headed, ${pct(a.c2_noFabricatedStructure.medianLengthGrowth)} | ${mark(a.c2_noFabricatedStructure.pass)} |`,
    `| 3 | substance: median M6/M7 within +-15 %, no collapse (AMENDED: drop >= 2 claims AND >= 50 %) | claims ${pct(a.c3_substanceHolds.medianClaimsDelta)}, coverage ${pct(a.c3_substanceHolds.medianCoverageDelta)}, ${a.c3_substanceHolds.claimCollapses.length} collapses | ${mark(a.c3_substanceHolds.pass)} |`,
    `| 4 | frames: worst bucket shift <= 2/24, no new ungrounded, M9 not up | shift ${a.c4_framesUnchanged.worstBucketShift}, ${a.c4_framesUnchanged.newlyUngrounded.length} new ungrounded, M9 ${a.c4_framesUnchanged.rawBracketsA} -> ${a.c4_framesUnchanged.rawBracketsB} | ${mark(a.c4_framesUnchanged.pass)} |`,
    '',
    `Qualifying multi-part prompts: ${a.c1_structureWhereItBelongs.headingHalf.qualifyingPrompts.join(', ') || 'none'}`
      + ` — disqualified (no heading in any arm or repeat): ${a.c1_structureWhereItBelongs.headingHalf.disqualifiedPrompts.join(', ') || 'none'}`,
    `Frame histogram — A: ${JSON.stringify(a.c4_framesUnchanged.histogramA)}; B: ${JSON.stringify(a.c4_framesUnchanged.histogramB)}`,
    `Token wall (§1.6): ${result.tokenWall.armBTruncatedMidStructure}/24 arm-B runs truncated mid-structure`
      + `${result.tokenWall.raiseBudgetEvidence ? ' — this IS the evidence to raise DEFAULT_MAX_TOKENS' : ''}.`,
    '',
    '## Per-run metrics',
    '',
    '| capture | kind | M1 head | M2 code | M3 fence | M4 list | M5 chars | M6 claims | M7 cov | M8 frame | M9 raw |',
    '|---|---|---|---|---|---|---|---|---|---|---|',
    ...result.runs.map(
      (r) =>
        `| ${r.arm}-${r.promptId}-r${r.repeat} | ${r.kind} | ${r.m1Headings} | ${r.m2InlineCode} | ${r.m3Fences} | ${r.m4ListLines} | ${r.m5Chars} | ${r.m6VerifiedClaims} | ${r.m7Coverage.toFixed(2)} | ${r.m8Frame} | ${r.m9RawBrackets} |`,
    ),
    '',
  ].join('\n');
}

// ---------------------------------------------------------------------------

async function main() {
  const opts = parseArgs(process.argv);
  if (opts.cmd === 'plan') return cmdPlan(opts);
  if (opts.cmd === 'run') return cmdRun(opts);
  return cmdScore(opts);
}

main().then(
  (code) => process.exit(code),
  (err) => {
    console.error(err.message ?? err);
    process.exit(2);
  },
);
