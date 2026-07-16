/**
 * Shared transcript-cost parsing + pricing table (extracted from cost-session.mjs,
 * tempdoc 743 Phase 1). cost-session.mjs and baseline-economics.mjs both need the
 * same per-turn token/cost extraction; this module is the single source so pricing
 * updates land once.
 */

import fs from 'node:fs';

// Pricing per 1M tokens (per platform.claude.com/docs pricing; current-model table refreshed 2026-07).
// cache_write uses the 5-minute tier (1.25x input); cache_read ~0.1x input; transcripts don't distinguish tiers.
// Keys are matched exact-first, then by prefix (findPricing), so a suffixed id like
// `claude-opus-4-8[1m]` resolves via the bare `claude-opus-4-8` entry.
export const PRICING = {
  'claude-fable-5':             { input: 10.0, output: 50.0, cache_write: 12.5,  cache_read: 1.00 },
  'claude-opus-4-8':            { input: 5.0,  output: 25.0, cache_write: 6.25,  cache_read: 0.50 },
  'claude-opus-4-7':            { input: 5.0,  output: 25.0, cache_write: 6.25,  cache_read: 0.50 },
  'claude-opus-4-6':            { input: 5.0,  output: 25.0, cache_write: 6.25,  cache_read: 0.50 },
  'claude-opus-4-20250514':     { input: 15.0, output: 75.0, cache_write: 18.75, cache_read: 1.50 },
  // Standard rate shown; Sonnet-5 has a $2/$10 intro discount through 2026-08-31, so
  // this slightly OVERstates Sonnet-5 spend during the intro window (accepted — a
  // date-branch isn't worth it for a cost-report script, and Sonnet-5 is rare here).
  'claude-sonnet-5':            { input: 3.0,  output: 15.0, cache_write: 3.75,  cache_read: 0.30 },
  'claude-sonnet-4-6':          { input: 3.0,  output: 15.0, cache_write: 3.75,  cache_read: 0.30 },
  'claude-sonnet-4-5-20250929': { input: 3.0,  output: 15.0, cache_write: 3.75,  cache_read: 0.30 },
  'claude-haiku-4-5':           { input: 1.0,  output: 5.0,  cache_write: 1.25,  cache_read: 0.10 },
  'claude-haiku-4-5-20251001':  { input: 1.0,  output: 5.0,  cache_write: 1.25,  cache_read: 0.10 },
};
export const DEFAULT_PRICING = PRICING['claude-sonnet-4-5-20250929'];
export const PER_M = 1_000_000;
// Sentinel by_model key for a usage-bearing turn with no `message.model` tag.
// Always resolves isKnownModel(...) === false, so it surfaces via unknown_models.
export const MISSING_MODEL_KEY = '(missing-model)';

export function round(n, decimals = 4) {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}

/**
 * Resolve a per-1M-token pricing row for a model id. Exact match first, then
 * longest-prefix match (so a specific dated id is not shadowed by a shorter
 * bare-family key when the two ever diverge in price). Falls back to
 * DEFAULT_PRICING for an unrecognized model — callers that need to know
 * whether the fallback fired should use `isKnownModel()` alongside this.
 */
export function findPricing(model) {
  if (!model) return DEFAULT_PRICING;
  if (PRICING[model]) return PRICING[model];
  for (const [key, pricing] of Object.entries(PRICING).sort((a, b) => b[0].length - a[0].length)) {
    if (model.startsWith(key)) return pricing;
  }
  return DEFAULT_PRICING;
}

/**
 * Whether `model` resolves to a real pricing-table row (exact or prefix match),
 * as opposed to silently falling back to DEFAULT_PRICING. A model id absent from
 * the table is priced at the Sonnet-4.5 default by findPricing() but its tokens
 * should still be surfaced as "unknown" so a report doesn't hide a mispriced model
 * behind a plausible-looking dollar figure (tempdoc 743 Phase 1).
 */
export function isKnownModel(model) {
  if (!model) return true; // absent model info is a different failure mode, not an unknown-model one
  if (PRICING[model]) return true;
  return Object.keys(PRICING).some((key) => model.startsWith(key));
}

function emptyModelBucket() {
  return { input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0, turns: 0, cost_usd: 0 };
}

/**
 * Parse a JSONL transcript and extract token usage from assistant messages.
 * Computes cost per-turn using each turn's actual model, so mixed-model
 * transcripts (e.g. Opus main + Haiku subagents) are priced accurately.
 *
 * `by_model` breaks the same totals down per model id seen in the transcript,
 * so callers can report model mix and flag unknown models (via isKnownModel)
 * without re-parsing.
 */
export function parseTranscriptTokens(transcriptPath) {
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
    if (!fs.existsSync(transcriptPath)) {
      result.error = 'file_not_found';
      return result;
    }

    // A single assistant turn is persisted as N JSONL lines when the response has
    // N content blocks (text/tool_use/...) — every one of those lines carries an
    // IDENTICAL `message.usage` snapshot for the turn. Summing per-line double/N-
    // counts usage (confirmed: 2.34x on main transcripts, 2.43x on subagent files
    // — session 034d5f2d had 592 usage lines but only 231 unique message ids).
    // `message.id` is the true per-turn key; count each id's usage exactly once.
    // Lines with no message.id (not observed in practice, but not guaranteed)
    // fall back to counting individually rather than risking silent undercount.
    const seenMessageIds = new Set();

    const content = fs.readFileSync(transcriptPath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let entry;
      try { entry = JSON.parse(line); } catch { continue; }

      if (entry.type !== 'assistant') continue;

      const usage = entry.message?.usage;
      if (!usage) continue;

      const msgId = entry.message?.id;
      if (msgId) {
        if (seenMessageIds.has(msgId)) continue; // duplicate usage snapshot for an already-counted turn
        seenMessageIds.add(msgId);
      }

      result.turns++;
      const inp = usage.input_tokens ?? 0;
      const out = usage.output_tokens ?? 0;
      const cw = usage.cache_creation_input_tokens ?? 0;
      const cr = usage.cache_read_input_tokens ?? 0;

      result.input_tokens += inp;
      result.output_tokens += out;
      result.cache_write_tokens += cw;
      result.cache_read_tokens += cr;

      // Per-turn cost using this turn's actual model. A turn with no model tag
      // is NOT priced at DEFAULT_PRICING (that would silently mask an unpriced
      // turn behind a plausible-looking dollar figure) — its tokens are routed
      // into the MISSING_MODEL_KEY bucket at $0 and surfaced via unknown_models
      // by the caller (isKnownModel(MISSING_MODEL_KEY) is false), same as any
      // other unrecognized model id.
      const turnModel = entry.message?.model;
      let turnCost = 0;
      if (turnModel) {
        const pricing = findPricing(turnModel);
        turnCost = (inp / PER_M) * pricing.input
          + (out / PER_M) * pricing.output
          + (cw / PER_M) * pricing.cache_write
          + (cr / PER_M) * pricing.cache_read;
        result.cost_usd += turnCost;
        result.model = turnModel; // track most recent known model
      }

      const modelKey = turnModel || MISSING_MODEL_KEY;
      if (!result.by_model[modelKey]) result.by_model[modelKey] = emptyModelBucket();
      const bucket = result.by_model[modelKey];
      bucket.input_tokens += inp;
      bucket.output_tokens += out;
      bucket.cache_write_tokens += cw;
      bucket.cache_read_tokens += cr;
      bucket.turns += 1;
      bucket.cost_usd += turnCost;
    }
  } catch (err) {
    result.error = err.message;
  }

  return result;
}
