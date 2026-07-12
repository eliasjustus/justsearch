#!/usr/bin/env node

/**
 * PreToolUse hook on Bash — surface the shared dataset-fetch cache (tempdoc 709).
 *
 * The cache exists (`scripts/jseval/jseval/dataset_cache.py`: `cache_root()` resolves a
 * cross-worktree cache dir under the MAIN checkout; `corpus-fetch-clerc`/`corpus-fetch-miracl`
 * route their raw multi-GB fetch through it) — but no agent knows it does. The concrete failure
 * that motivated this hook: an agent was about to trigger a redundant 6.7 GB CLERC download,
 * unaware the shared cache should serve it. That gap is a DISCOVERABILITY gap, not a code gap,
 * and the moment of relevance is precisely when an agent issues a corpus fetch/download command.
 *
 * A jseval-skill note reaches only agents who LOADED the skill — but a fetching agent may not
 * have. Always-loaded CLAUDE.md prose is budget-capped and low-adherence, and this guidance is
 * not broadly applicable (only eval/corpus work needs it). A PreToolUse hint hook delivers it at
 * the moment of relevance to EVERY agent regardless of skill loading — the highest-adherence,
 * smallest-scope channel for this gap (`.claude/rules/tier-register.md` tier ordering).
 *
 * Two trigger classes (see `classifyCorpusFetch` + the dataset-cache-hint.test.mjs precision
 * corpus):
 *   - `cache-backed`: the command already IS `jseval corpus-fetch-clerc|corpus-fetch-miracl`.
 *     The hint reassures that this routes through the shared cross-worktree cache (a prior
 *     fetch in any worktree already warmed it → near-instant hit) and names the env kill switch.
 *   - `ad-hoc`: a raw download verb (`curl`/`wget`/`huggingface-cli download`/`git clone`/…)
 *     targeting a known corpus/dataset host — i.e. a fetch that BYPASSES the cache. The hint
 *     redirects to the cache-backed command so the multi-GB bytes are deduped across worktrees.
 * Requiring BOTH a download verb AND a corpus signal for `ad-hoc` keeps false positives low
 * (a model/gguf download or a `cat datasets/…` read does not fire).
 *
 * Advisory: never blocks, fail-open on any error, honors `JUSTSEARCH_DISABLE_HOOKS=1`.
 */

import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';

/** The two cache-backed jseval fetchers whose raw fetch routes through `dataset_cache` (709). */
const CACHE_BACKED = /\bcorpus-fetch-(?:clerc|miracl)\b/i;

/** A download verb — a command that pulls bytes over the network to disk. */
const DOWNLOAD_VERB = /(?:^|[\s;&|(])(?:curl|wget|aria2c|axel)\b|\bgit\s+clone\b|\bgit\s+lfs\b|\bhuggingface-cli\s+download\b|\bhf\s+download\b/i;

/**
 * A known corpus/dataset signal. Deliberately tight (the HF *datasets* subpath, specific dataset
 * ids/repos, ir_datasets, the CLERC collection artifact) so a model/gguf download or an unrelated
 * `curl` never matches.
 */
const CORPUS_SIGNAL = /huggingface\.co\/datasets\/|jhu-clsp\/CLERC|miracl-corpus|macavaney\/miracl|\bir_datasets\b|\/beir\/|collection\.doc\.tsv/i;

/**
 * Classify a Bash command: `'cache-backed'` (already using the cache-routed jseval fetcher),
 * `'ad-hoc'` (a raw corpus download that bypasses the cache), or `null` (irrelevant). Pure;
 * unit-tested.
 */
export function classifyCorpusFetch(cmd) {
  const c = String(cmd || '');
  if (!c.trim()) return null;
  if (CACHE_BACKED.test(c)) return 'cache-backed';
  if (DOWNLOAD_VERB.test(c) && CORPUS_SIGNAL.test(c)) return 'ad-hoc';
  return null;
}

export const HINT_CACHE_BACKED = [
  'Shared dataset cache (tempdoc 709): `corpus-fetch-clerc`/`corpus-fetch-miracl` route the raw',
  'multi-GB fetch through a cross-worktree, integrity-verified cache under the MAIN checkout',
  '(`scripts/jseval/tmp/dataset-fetch-cache/`, gitignored). The same recipe fetched in ANY',
  'worktree is already cached and will NOT re-download — a prior warm run makes this a near-',
  'instant cache hit. Leave it on; `JUSTSEARCH_DATASET_CACHE=0` disables it only when you',
  'deliberately want an isolated, uncached fetch.',
].join('\n');

export const HINT_AD_HOC = [
  'This looks like a raw corpus/dataset download that BYPASSES the shared dataset cache',
  '(tempdoc 709). Prefer the cache-backed `python -m jseval corpus-fetch-clerc` /',
  '`corpus-fetch-miracl`: they dedupe the multi-GB raw bytes in a cross-worktree,',
  'integrity-verified cache under the MAIN checkout, so the download runs ONCE for all',
  'worktrees instead of once per worktree. `JUSTSEARCH_DATASET_CACHE` controls it (0/empty',
  'disables). If jseval lacks a fetcher you need, extend jseval rather than hand-rolling a download.',
].join('\n');

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Bash') return;
  const kind = classifyCorpusFetch(input.tool_input?.command);
  if (!kind) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        additionalContext: kind === 'cache-backed' ? HINT_CACHE_BACKED : HINT_AD_HOC,
      },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
