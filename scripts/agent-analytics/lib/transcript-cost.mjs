/**
 * Shared transcript-cost parsing + pricing table (extracted from cost-session.mjs,
 * tempdoc 743 Phase 1). cost-session.mjs and baseline-economics.mjs both need the
 * same per-turn token/cost extraction; this module is the single source so pricing
 * updates land once.
 *
 * Tempdoc 745 item B — four verified defects fixed here:
 *   1. dedup scope is now the CALLER's choice (optional `seen` map), not per-file;
 *   2. a repeated (message.id, requestId) keeps the LAST usage snapshot, not the
 *      first — subagent transcripts persist STREAMING PARTIALS that grow
 *      (`output_tokens: 5, 5, 5, 5, 5, 291` reproduced on a real transcript), so
 *      first-wins undercounted corpus output by ~30%;
 *   3. cache writes are priced per ephemeral tier — transcripts DO distinguish
 *      them via `usage.cache_creation.{ephemeral_5m,ephemeral_1h}_input_tokens`,
 *      and 1h writes cost 2.0x input, not the 5m 1.25x;
 *   4. pricing is date-conditional (Sonnet-5's intro window) and fails CLOSED on
 *      an unrecognized model instead of silently pricing it as Sonnet.
 */

import fs from 'node:fs';

// Pricing per 1M tokens, verified against platform.claude.com/docs/en/about-claude/pricing
// (2026-07-16). Cache-write multipliers off input: 5-minute tier 1.25x, 1-hour tier 2.0x;
// cache read 0.1x. Values are written out literally rather than derived so the table can be
// diffed straight against the published one.
// Keys are matched exact-first, then by longest prefix (findPricing), so a suffixed id like
// `claude-opus-4-8[1m]` resolves via the bare `claude-opus-4-8` entry.
const OPUS_CURRENT = { input: 5.0, output: 25.0, cache_write_5m: 6.25, cache_write_1h: 10.0, cache_read: 0.50 };
const OPUS_LEGACY = { input: 15.0, output: 75.0, cache_write_5m: 18.75, cache_write_1h: 30.0, cache_read: 1.50 };
const SONNET_STANDARD = { input: 3.0, output: 15.0, cache_write_5m: 3.75, cache_write_1h: 6.0, cache_read: 0.30 };
const SONNET_5_INTRO = { input: 2.0, output: 10.0, cache_write_5m: 2.5, cache_write_1h: 4.0, cache_read: 0.20 };
const HAIKU_4_5 = { input: 1.0, output: 5.0, cache_write_5m: 1.25, cache_write_1h: 2.0, cache_read: 0.10 };

/**
 * Sonnet-5 is $2/$10 through 2026-08-31 and $3/$15 from 2026-09-01. A turn is priced by
 * its own `entry.timestamp`; a turn with no timestamp is priced at the enduring standard
 * rate (the intro window is the exception, so the exception is what needs proof).
 */
export const SONNET_5_INTRO_ENDS_MS = Date.parse('2026-09-01T00:00:00.000Z');

/**
 * Fast mode (`/fast`) bills Opus at a premium and is recorded per-turn as
 * `message.usage.speed` — "standard" | "fast" (null on transcripts predating the
 * field). Verified corpus-wide 2026-07-16: 59,332 turns, ALL "standard", zero
 * "fast" — so this table currently prices nothing and is forward-looking only.
 * It exists because the alternative is silent: without it, one `/fast` toggle
 * would understate Opus 4.8 by 2x with no symptom, and the cheap-to-add case is
 * exactly the one that goes unnoticed for a month.
 *
 * Rates verified at platform.claude.com/docs/en/about-claude/pricing (fast mode
 * is Opus 4.8 / 4.7 only; 4.6 runs standard-speed at standard rates as of
 * 2026-06-29). Cache multipliers stack ON TOP of fast pricing, per that page:
 * 5m write = 1.25x input, 1h write = 2.0x input, cache read = 0.1x input.
 */
const OPUS_4_8_FAST = { input: 10.0, output: 50.0, cache_write_5m: 12.5, cache_write_1h: 20.0, cache_read: 1.00 };
const OPUS_4_7_FAST = { input: 30.0, output: 150.0, cache_write_5m: 37.5, cache_write_1h: 60.0, cache_read: 3.00 };

export const FAST_PRICING = {
  'claude-opus-4-8': OPUS_4_8_FAST,
  'claude-opus-4-7': OPUS_4_7_FAST,
};

export const PRICING = {
  'claude-fable-5':             { input: 10.0, output: 50.0, cache_write_5m: 12.5, cache_write_1h: 20.0, cache_read: 1.00 },
  'claude-opus-4-8':            OPUS_CURRENT,
  'claude-opus-4-7':            OPUS_CURRENT,
  'claude-opus-4-6':            OPUS_CURRENT,
  'claude-opus-4-5':            OPUS_CURRENT,
  'claude-opus-4-1':            OPUS_LEGACY,
  'claude-opus-4-20250514':     OPUS_LEGACY,
  'claude-sonnet-5':            { schedule: [{ before: SONNET_5_INTRO_ENDS_MS, pricing: SONNET_5_INTRO }, { pricing: SONNET_STANDARD }] },
  'claude-sonnet-4-6':          SONNET_STANDARD,
  'claude-sonnet-4-5-20250929': SONNET_STANDARD,
  'claude-haiku-4-5':           HAIKU_4_5,
  'claude-haiku-4-5-20251001':  HAIKU_4_5,
};
export const PER_M = 1_000_000;
// Sentinel by_model key for a usage-bearing turn with no `message.model` tag.
// Always resolves isKnownModel(...) === false, so it surfaces via unknown_models.
export const MISSING_MODEL_KEY = '(missing-model)';

const PRICING_KEYS_LONGEST_FIRST = Object.keys(PRICING).sort((a, b) => b.length - a.length);

export function round(n, decimals = 4) {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}

/**
 * Look a model id up in a pricing map: exact match, else longest-prefix match, so
 * a specific dated id is not shadowed by a shorter bare-family key when the two
 * ever diverge in price. Shared by the standard and fast tables so both resolve
 * model ids by identical rules — a second copy of this matching would be a place
 * for them to silently disagree.
 */
function findEntryIn(table, model) {
  if (!model) return null;
  if (table[model]) return table[model];
  const key = Object.keys(table).sort((a, b) => b.length - a.length).find((k) => model.startsWith(k));
  return key ? table[key] : null;
}

function findEntry(model) {
  if (!model) return null;
  if (PRICING[model]) return PRICING[model];
  const key = PRICING_KEYS_LONGEST_FIRST.find((k) => model.startsWith(k));
  return key ? PRICING[key] : null;
}

/**
 * Resolve a per-1M-token pricing row for a model id at a point in time.
 * Returns **null** for an unrecognized model — pricing FAILS CLOSED (tempdoc 745
 * item B, bug 4): the previous DEFAULT_PRICING fallback priced any unknown model
 * at Sonnet rates, producing a plausible-looking but silently wrong dollar figure.
 * Callers must treat null as "cannot price" ($0 + surfaced via isKnownModel).
 *
 * `timestampMs` selects the dated row for models with a price schedule
 * (Sonnet-5's intro window); null/omitted resolves to the undated standard row.
 *
 * `speed` is the turn's `message.usage.speed` ("standard" | "fast" | null). Only
 * "fast" changes anything, and only for the Opus models that offer it; anything
 * else — including an unknown speed string — resolves to standard pricing, which
 * is the honest default given fast mode is opt-in per turn.
 */
export function findPricing(model, timestampMs = null, speed = null) {
  if (speed === 'fast') {
    const fast = findEntryIn(FAST_PRICING, model);
    // A "fast" turn on a model with no fast row (e.g. a future model, or Opus 4.6
    // where fast was withdrawn) falls through to standard rather than guessing a
    // premium — but it is NOT silent: isFastPricedCorrectly() lets callers surface it.
    if (fast) return fast;
  }
  const entry = findEntry(model);
  if (!entry) return null;
  if (!entry.schedule) return entry;
  for (const step of entry.schedule) {
    if (step.before == null) return step.pricing;
    if (timestampMs != null && timestampMs < step.before) return step.pricing;
  }
  return null;
}

/**
 * True when a turn's (model, speed) pair is priced by a row that actually matches
 * it. False only for a "fast" turn on a model with no fast row — i.e. the one case
 * where findPricing knowingly falls back to standard and would understate. Lets a
 * caller bucket the tokens loudly instead of shipping a plausible wrong number,
 * the same contract isKnownModel provides for unknown models.
 */
export function isFastPricedCorrectly(model, speed) {
  if (speed !== 'fast') return true;
  return Boolean(findEntryIn(FAST_PRICING, model));
}

/**
 * Whether `model` resolves to a real pricing-table row (exact or prefix match).
 * A model id absent from the table is priced at $0 by the parser and its tokens
 * are surfaced as "unknown" so a report doesn't hide a mispriced model behind a
 * plausible-looking dollar figure (tempdoc 743 Phase 1 / 745 item B).
 */
export function isKnownModel(model) {
  if (!model) return true; // absent model info is a different failure mode, not an unknown-model one
  return findEntry(model) != null;
}

export function emptyModelBucket() {
  return { input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0, turns: 0, cost_usd: 0 };
}

export function emptyTotals() {
  return {
    input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0,
    cost_usd: 0, turns: 0,
  };
}

export function addTotals(acc, r) {
  acc.input_tokens += r.input_tokens;
  acc.output_tokens += r.output_tokens;
  acc.cache_write_tokens += r.cache_write_tokens;
  acc.cache_read_tokens += r.cache_read_tokens;
  acc.cost_usd += r.cost_usd;
  acc.turns += r.turns;
}

export function mergeByModel(target, source) {
  for (const [model, bucket] of Object.entries(source || {})) {
    if (!target[model]) target[model] = emptyModelBucket();
    const t = target[model];
    t.input_tokens += bucket.input_tokens;
    t.output_tokens += bucket.output_tokens;
    t.cache_write_tokens += bucket.cache_write_tokens;
    t.cache_read_tokens += bucket.cache_read_tokens;
    t.turns += bucket.turns;
    t.cost_usd += bucket.cost_usd;
  }
  return target;
}

/**
 * Split a turn's cache-creation tokens into the 5-minute and 1-hour ephemeral
 * tiers. `usage.cache_creation` is an object carrying both counts (verified on
 * real transcripts, tempdoc 745 item B bug 3 — where ~100% of writes were 1h).
 *
 * The tiered object is the SOURCE OF TRUTH; the flat `cache_creation_input_tokens`
 * is only a fallback for a transcript carrying no tiered form. Do not assume the
 * flat field equals the tiers' sum — measured on the 125-session corpus, **1,313
 * snapshots carry tiered writes with flat == 0**, hiding **16,992,717 cache-write
 * tokens (2.34%)** from any flat-only reader (sonnet-5 9.9M, opus-4-8 7.1M). That
 * is what the pre-745 parser did, so bug 3's fix recovers those tokens outright,
 * not merely their tier. Anything deciding "is this snapshot real?" must read
 * through THIS function, never the flat field (see usageIsAllZero).
 *
 * An un-tiered write is charged at the cheaper 5m rate rather than guessed upward.
 */
function splitCacheWrite(usage) {
  const cc = usage.cache_creation;
  if (cc && typeof cc === 'object') {
    const w5 = cc.ephemeral_5m_input_tokens ?? 0;
    const w1 = cc.ephemeral_1h_input_tokens ?? 0;
    if (w5 || w1) return { w5, w1 };
  }
  return { w5: usage.cache_creation_input_tokens ?? 0, w1: 0 };
}

/**
 * True when a snapshot reports no usage at all — a re-carried placeholder rather
 * than a measurement (see the displacement rule in parseTranscriptTokens).
 *
 * It reads cache writes through `splitCacheWrite`, deliberately: reading the flat
 * `cache_creation_input_tokens` directly would call a tiered-only snapshot
 * "all-zero" and let a true placeholder displace it. The two functions must agree
 * on where cache writes live, or the guard protects a different field than the
 * pricing does.
 */
function usageIsAllZero(usage) {
  const { w5, w1 } = splitCacheWrite(usage);
  return !(usage.input_tokens || 0) && !(usage.output_tokens || 0) &&
    !(usage.cache_read_input_tokens || 0) && !w5 && !w1;
}

/**
 * Dedup key for a usage-bearing assistant line. Claude Code persists a single
 * turn as N JSONL lines (one per content block) and re-carries prior turns
 * verbatim into a RESUMED session's file, so the same turn appears many times
 * both within and across transcripts. `(message.id, requestId)` — requestId
 * lives at ENTRY level, not inside `message` — is the true per-turn identity
 * (matches ccusage). A line with no message.id has no usable identity and is
 * counted individually rather than risking a silent drop.
 */
function dedupKey(entry) {
  const msgId = entry.message?.id;
  if (!msgId) return null;
  return `${msgId}|${entry.requestId ?? ''}`;
}

/**
 * Parse a JSONL transcript and extract token usage from assistant messages.
 * Computes cost per-turn using each turn's actual model and timestamp, so
 * mixed-model transcripts (e.g. Opus main + Haiku subagents) are priced
 * accurately.
 *
 * `by_model` breaks the same totals down per model id seen in the transcript,
 * so callers can report model mix and flag unknown models (via isKnownModel)
 * without re-parsing.
 *
 * `seen` (optional Map, key -> true) makes the dedup SCOPE the caller's choice
 * (tempdoc 745 item B, bug 1): keys already present are skipped, and every key
 * this call counted is added on the way out. Pass a per-session map to dedup a
 * main transcript against its subagents; pass a corpus-scoped map, feeding
 * sessions oldest-first, to stop a resumed session's re-carried history from
 * being counted a second time under its new session id. Omit it for per-file
 * scope.
 */
export function parseTranscriptTokens(transcriptPath, { seen } = {}) {
  const result = {
    input_tokens: 0,
    output_tokens: 0,
    cache_write_tokens: 0,
    cache_read_tokens: 0,
    cost_usd: 0,
    turns: 0,
    model: null,
    error: null,
    by_model: {},
  };

  try {
    if (!transcriptPath || !fs.existsSync(transcriptPath)) {
      result.error = 'file_not_found';
      return result;
    }

    // A repeated key's snapshots are STREAMING PARTIALS THAT GROW, not identical
    // copies (verified on a real subagent transcript: output_tokens 5,5,5,5,5,291),
    // so the LAST snapshot is the turn's true usage. That forces buffering: slots
    // are aggregated after the read, not accumulated inline. The last snapshot is
    // taken WHOLESALE rather than a per-field max, which would fabricate a snapshot
    // that never existed.
    //
    // EXCEPT: last-wins is not unconditional, because the growth premise has a
    // documented counterexample. Transcripts also re-carry a turn with an ALL-ZERO
    // usage snapshot after its real one:
    //     in=2 out=760 cr=804035 cw=290   (x3)
    //     in=0 out=0   cr=0      cw=0     (x3)   <- naive last-wins takes this
    // So an all-zero snapshot NEVER displaces a non-zero one. Zero is a placeholder
    // for "no usage reported on this line", not a measurement of zero.
    //
    // This is an artifact of OUR design, not of the transcripts: the zero copy is
    // only reachable because we dedup GLOBALLY (across files/sessions), so a turn's
    // real usage in one file can be displaced by its zero re-carry in another.
    // Measured over 227 in-scope sessions: 1,455 keys, recovering 1.288G cache_read
    // + 16.5M cache_write + 1.62M output — and a differential reconciliation showed
    // the guard's effect is explained by those keys with residual EXACTLY 0.
    //
    // NOTE FOR THE NEXT AGENT: this rule moves us TOWARD ccusage, not away
    // (cache_read −4.78% -> −0.43% against it). ccusage does NOT have this bug — it
    // sidesteps it by not deduping globally, which costs it its own over-count.
    // Do not delete this rule to chase the residual (tempdoc 745 F-11).
    const slots = [];
    const slotByKey = new Map();

    const content = fs.readFileSync(transcriptPath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let entry;
      try { entry = JSON.parse(line); } catch { continue; }

      if (entry.type !== 'assistant') continue;

      const usage = entry.message?.usage;
      if (!usage) continue;

      const tsMs = entry.timestamp ? Date.parse(entry.timestamp) : NaN;
      const slot = {
        usage,
        model: entry.message?.model ?? null,
        tsMs: Number.isNaN(tsMs) ? null : tsMs,
      };

      const key = dedupKey(entry);
      if (!key) {
        slots.push(slot);
        continue;
      }
      if (seen?.has(key)) continue; // already counted by an earlier file/session in this scope

      const existing = slotByKey.get(key);
      if (existing) {
        // Last snapshot wins, in the turn's original position — unless it is an
        // all-zero placeholder displacing a real measurement (see above).
        if (!(usageIsAllZero(slot.usage) && !usageIsAllZero(existing.usage))) {
          Object.assign(existing, slot);
        }
      } else {
        slotByKey.set(key, slot);
        slots.push(slot);
      }
    }

    // Claim a key in the cross-file scope ONLY if what we recorded for it is real.
    // A key whose only snapshot here is an all-zero placeholder must stay UNCLAIMED,
    // or it suppresses the real turn in a later file — the same displacement the
    // in-loop guard prevents within a file, one scope up. Marking unconditionally
    // made the result depend on file-visit order: a zero-only copy in file B
    // silently deleted the real turn in file C (804,035 cache_read on the repro).
    for (const [key, slot] of slotByKey.entries()) {
      if (!usageIsAllZero(slot.usage)) seen?.set(key, true);
    }

    for (const slot of slots) accumulate(result, slot);
  } catch (err) {
    result.error = err.message;
  }

  return result;
}

/** Fold one deduped turn's final usage snapshot into the running result. */
function accumulate(result, { usage, model, tsMs }) {
  result.turns++;
  const inp = usage.input_tokens ?? 0;
  const out = usage.output_tokens ?? 0;
  const { w5, w1 } = splitCacheWrite(usage);
  const cr = usage.cache_read_input_tokens ?? 0;

  result.input_tokens += inp;
  result.output_tokens += out;
  result.cache_write_tokens += w5 + w1;
  result.cache_read_tokens += cr;

  // Per-turn cost using this turn's actual model and date. A turn whose model is
  // absent OR unrecognized is NOT priced at a default (that would silently mask a
  // mispriced turn behind a plausible-looking dollar figure) — its tokens are
  // routed into their model's bucket at $0 and surfaced via unknown_models by the
  // caller (isKnownModel is false for both cases).
  const pricing = findPricing(model, tsMs, usage.speed ?? null);
  let turnCost = 0;
  if (pricing) {
    turnCost = (inp / PER_M) * pricing.input
      + (out / PER_M) * pricing.output
      + (w5 / PER_M) * pricing.cache_write_5m
      + (w1 / PER_M) * pricing.cache_write_1h
      + (cr / PER_M) * pricing.cache_read;
    result.cost_usd += turnCost;
    result.model = model; // track most recent priced model
  }

  const modelKey = model || MISSING_MODEL_KEY;
  if (!result.by_model[modelKey]) result.by_model[modelKey] = emptyModelBucket();
  const bucket = result.by_model[modelKey];
  bucket.input_tokens += inp;
  bucket.output_tokens += out;
  bucket.cache_write_tokens += w5 + w1;
  bucket.cache_read_tokens += cr;
  bucket.turns += 1;
  bucket.cost_usd += turnCost;
}

/**
 * Parse one session: its main transcript plus every subagent transcript, sharing
 * ONE dedup scope across them. This is the single combine (tempdoc 745 item B,
 * D7) — cost-session.mjs's costSession() and baseline-economics.mjs's
 * computeSessionCost() previously each carried their own copy of this loop, so a
 * fix to one left a knowingly-wrong twin.
 *
 * `seen` widens the scope beyond this session (see parseTranscriptTokens); when
 * omitted, the session is its own scope. `main` is always a result object — a
 * missing/absent mainPath yields `error: 'file_not_found'`, as before.
 */
export function parseSessionTokens({ mainPath, subagentPaths = [], seen } = {}) {
  const scope = seen ?? new Map();
  const main = parseTranscriptTokens(mainPath, { seen: scope });

  const subagents = { found: 0, missing: 0, totals: emptyTotals(), by_model: {} };
  for (const p of subagentPaths || []) {
    const r = parseTranscriptTokens(p, { seen: scope });
    if (r.error) {
      subagents.missing += 1;
      continue;
    }
    subagents.found += 1;
    addTotals(subagents.totals, r);
    mergeByModel(subagents.by_model, r.by_model);
  }

  return { main, subagents };
}
