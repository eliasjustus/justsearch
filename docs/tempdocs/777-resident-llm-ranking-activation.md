---
title: "resident-LLM ranking activation: the measured, unexploited advantage of a local-first engine with a resident model — listwise reranking, the judge-blend default decision, and validating the gated QU/sufficiency features"
type: tempdocs
status: "measurement phase run 2026-07-22 (§D): scifact anchor reproduced (judge parity — U1 +0.042 closed), legal-clerc-200 ceiling 0.268 + fwd-only capture, arbitration divergence measured; enron + fresh arbitration A/B + en-legal (materialization-blocked) deferred; recommendations recorded, NO defaults changed, registers not yet folded."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: search-engine / ranking / local-inference
related:
  - 643 (judge-stage ranking quality, F-026 — the U1 probe + shipped default-off blend this activates or retires)
  - 366 (QU/tool consolidation — the gated features)
---

> Charter. New number per dated-history convention; 643/F-026 are the
> evidence base, not reopened. Load `/search-quality` AND
> `/inference-runtime` before work; both registers carry duties here.

# 777 — resident-LLM ranking activation

## §A. The thesis and the banked evidence

A local-first product with a resident LLM can spend inference on ranking
quality in ways cloud-priced competitors cannot. The engine already ships
the seams, all dormant:

1. **Listwise LLM reranking (F-026 U1)**: the packaged Qwen3.5-9B, used as a
   single structured listwise call, MEASURED +0.042 nDCG over the shipped
   pipeline on a 40-query scifact sample (0.874 vs 0.831; captures 36% of
   the AI-free ceiling). One sample, one corpus — "whether to revisit is a
   live open question" (F-026). This lane answers it: multi-corpus
   measurement (scifact + enron + legal-clerc + the rebuilt strata),
   latency/VRAM cost characterized, opt-in shape designed if it holds.
2. **The judge-blend default decision (F-026 E1/E2)**: shipped default-off;
   scifact net-positive, enron thin net-negative. Decide the default from
   the D-004 template (measure → default-on or retire), not leave it in
   limbo.
3. **Gated features awaiting validation**: QU boostFilters (F-018 measured
   safe; disabled for LLM scheduling contention — is that still true
   post-737 lifecycle work?), context-sufficiency (Q-007: no labeled set),
   user-facing confidence (Q-009: no calibration). Each gets a
   validate-or-retire verdict — a gated feature that never activates is
   residue (retire-with-a-sweep applies).

## §B. Constraints

- Perf co-equal: llm-gen ratchets (640) + interactive latency budgets bind;
  listwise reranking may only ever be an opt-in/async tier if it costs
  hundreds of ms.
- D-005: the LLM is a JUDGE (allowed intelligence locus), never a router.
- VRAM arbitration with embedding/CE sessions is the known hard part
  (ort-common seams; serial-swap precedent from 674).

## §C. Acceptance

Per item: a measured verdict (activate with config shape + register
baselines, or retire with the measurement cited). No item left gated-and-
undecided. Registers updated before close.

## §D. Measurement phase (2026-07-22)

Measured by an isolated worktree agent, synchronous, ~$0 (local model only). Dev stack from main
dist (gitHead `cd951e9b`; scifact eval ran on the worktree base `f77ee503` — docs-only delta, search
code identical). Judge = packaged `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, GPU cuda12, 4096 ctx, single-slot
(`-np 1`). Judge == engine generator (single local model → self-preference not removable; same honest
caveat as F-026 U1). Registers NOT edited this pass (measurement fold happens at lane close).
Artifacts: `tmp/analysis-624/777/` (per-corpus JSON + item write-ups).

### Item 1 — Listwise resident-LLM reranking (the F-026 U1 revisit)

Method: `jseval run --modes vector,lexical,splade,hybrid --ce --embedding --splade --pipeline`
(CE-on hybrid = final), then `jseval judge-ceiling` reranks the leg-union pool. ~40-50 q/corpus.

| Corpus | q | pipeline final nDCG@10 | llm_ndcg | realized | ceiling | capture | top1-agree |
|---|---|---|---|---|---|---|---|
| beir/scifact | 37 | 0.8720 | 0.8736 (fwd+rev) | +0.0017 | 0.078 | 2.1% | 0.68 |
| mixed/legal-clerc-200 | 50 | 0.6517 | not cleanly obtained† | — | 0.268 | not obtained† | — |
| mixed/enron-qa | — | not run (per-corpus cost; see §note) | | | | | |
| mixed/en-legal-clerc-1k-verbose | — | MATERIALIZATION BLOCKED | | | | | |

**Latency (activation-cost axis):** per listwise call on a CLEAN server — scifact **median 1.08 s**
(mean 1.38, pool 18-27); legal **~1.8 s** median for well-behaved queries BUT a **severe tail**:
many verbose-legal queries drive the 9B model to near-`max_tokens` generation (tens of seconds), and
because llama-server runs single-slot, one such call stalls the batch. A full-pool fwd+rev legal
probe did not complete in 20 min; even a forward-only, 25 s-capped pass is broadly slow. All ≥6x the
interactive hybrid p50 (~180 ms), and the legal tail is far worse.
> Interrogation note: an initial "80 s/call" legal reading was a **server-degradation artifact** —
> killing in-flight requests to the single-slot llama-server orphaned generations that backed up the
> queue; a fresh server gives ~1.8 s median. The *tail* (many-token generations on legal) is real.

**scifact anchor — the one-sample question, answered:** the judge's *capability* reproduces U1
almost exactly (llm_ndcg **0.8736** vs U1 0.874), but the **+0.042 advantage did NOT reproduce**. U1
compared 0.874 vs a **0.831** pipeline; the current pipeline is **0.872** → **parity (+0.0017,
capture 2.1%)**. The entire delta is in the pipeline baseline (0.831→0.872, post-U1 chunk-vector /
late-chunking fixes and/or query-sample), not the judge. **On academic factoid the listwise rerank
is now at parity — no activation case.** Value can only come from weak-pipeline regimes.

**legal-clerc-200 — the weak-pipeline regime:** large AI-free ceiling (judge_headroom **0.268**,
leg_union_recall 0.92, final 0.652) — the case the lane cares about. **†Legal LLM capture could not be cleanly measured: the packaged 9B
model failed to return a concise listwise ranking within a 25 s/call budget for the large majority
of legal queries** — a forward-only, per-call-capped pass over 50 queries ran GPU-pinned for ~18 min
without completing (vs scifact's 37-query forward+reverse probe finishing in ~2 min). The first two
legal calls on a clean server were fast (~1.8 s), so it is not a uniform slowness but a high-rate
degeneration into near-`max_tokens` generation on verbose-legal prompts (CLERC citing-sentence
queries + numeric doc-ids under strict JSON-schema decoding). This is itself decision-relevant: on
the exact domain where the pipeline is weak and a judge is most needed, the **packaged judge model
cannot reliably perform full-pool listwise reranking**. Any real tier here would need (a) a bounded
top-N window rather than the full leg-union pool, (b) a hard per-call deadline, and (c) likely a
smaller/faster or output-constrained judge model — and must then be re-measured. The AI-free
`judge_headroom_ceiling` of 0.268 remains the standing "what a perfect judge could add" bound.

**en-legal-clerc-1k-verbose — MATERIALIZATION BLOCKED (documented, not skipped):** per the 767
certification-runbook §B2 (HARD STOP), the exact `corpus-inject-real` invocations for the leak-free
rebuild are "not recorded verbatim anywhere in the repo"; `datasets/` is gitignored, so no
materialized copy exists on disk, and `corpus-build` needs an already-injected source (the runbook
explicitly warns against reconstructing the inject flags — wrong-flag → wrong corpus). Real CLERC
`legal-clerc-200` is used as the legal-domain datapoint instead.

**enron-qa — not run this pass:** each corpus costs a full ingest+enrich (enron 5485 docs ≈ 15 min)
plus a slow judge probe; the LLM-probe latency proved far higher than the U1 one-sample budget
implied. Materialized + ready (`scripts/search/convert-enronqa-to-beir.py`, 5485 docs / 300 q) for a
follow-up pass. F-002/F-008 (CE hurts email by demoting gold) predict the judge could help here by
NOT demoting — the highest-value untested cell.

### Item 2 — Judge-blend / arbitration default decision (E3 instrument)

Single-run divergence (AI-free, from the default runs' `judgeSignals`, `jseval
judge-arbitration-report`):

| Corpus | n | fusion_protect_rate | perf_skip_firing_rate |
|---|---|---|---|
| beir/scifact | 40 | 0.075 (3/40) | 0.30 (12/40) |
| mixed/legal-clerc-200 | 50 | **0.000 (0/50)** | 0.02 (1/50) |

The arbitration gate is a no-op on legal (0%) — its "legs-agree" precondition rarely holds where
lexical is strong but dense/splade are near-dead (legs disagree). It fires 7.5% on scifact. The
gold-rank regression @20 (on vs off, `tmp/gold_rank_regression.py`, predictedDocIds — the corrected
§9-4 method) was NOT freshly run for scifact/enron this pass (same per-corpus cost + the on-arm needs
a `--start-backend` run with `JUSTSEARCH_RERANK_JUDGE_ARBITRATION_ENABLED=true`). F-026's historical
top-20 numbers stand: scifact 5.67%→4.00% (net win), **enron-qa 8.33%→8.67% (thin net-negative)** —
which is the decision-relevant cell and already argues against a default flip.

### Item 3 — Gated-feature status audit (evidence only; verdicts, not decisions)

| Feature | Gate today (file:line) | Blocker still holds? | Cheapest validation | Verdict |
|---|---|---|---|---|
| QU boostFilters | `EnvRegistry.java:74` `JUSTSEARCH_QU_ENABLED` (default off), read `QueryUnderstandingService.java:110-115`; LLM call `:137-138`; fired `KnowledgeSearchEngine.java:631-639` | **YES** — 737 is lifecycle authority, not request concurrency; `LlamaServerOps.java:228-236` sets `-np 1` only for VDU, chat/search runs single-slot → a per-search QU call serializes vs any chat completion | ~20-min live p95-latency-under-concurrency probe (quality already proven safe, F-018); no labels | **needs-contention-test** (not retire) |
| context-sufficiency | NO gate — live at `RetrieveContextController.java:59` / `:227-236`, only `isAvailable()`-gated (`ContextSufficiencyService.java:97-99`); prompt rule 5 conservative-revert (`sufficiency.v1.txt:12`) | YES — no labeled set in repo; **shipping unvalidated today** | ~115-example labeled (query,context)→answerable set; offline `classify()` P/R | **needs-labeled-eval** |
| user-facing confidence (Q-009) | NO gate — `RagContextOps.java:667-690` always emits `best_chunk_score`/`score_gap`; FE `retrievalSignals.ts:27-42` renders relative/uncalibrated | YES — no calibration code; register verbatim | shares Feature-2's labeled set (grounding label); AUC of CE score vs label | **needs-labeled-eval** |

One small human-labeled grounding set unblocks BOTH #2 and #3; #1 is independent and cheapest. None
is a retire candidate on current evidence.

### Recommendations (recommendations, NOT decisions — defaults untouched this pass)

- **Item 1 — do NOT activate a naive full-pool listwise tier as designed.** Two independent blockers:
  (1) on the strong-pipeline regime (scifact) it is at **parity** (no benefit); (2) on the
  weak-pipeline regime that motivates it (legal), the **packaged judge model cannot reliably perform
  the task** — it degenerates into near-`max_tokens` generation on the majority of verbose-legal
  prompts, so the full-pool probe is both unusably slow and its ranking unreliable. Latency is 6-10x
  interactive even in the good case. The licensed contextualization design (777 §A.1, still open)
  should therefore NOT reuse this shape. If a listwise judge tier is pursued at all it must be
  re-scoped: **opt-in/async, default-OFF, bounded top-N window (not the leg-union pool), hard per-call
  deadline, and a smaller/faster or output-constrained judge model**, then re-measured — the current
  packaged-9B/full-pool form is measurement-rejected. Meanwhile the contextual-enrichment tier that
  already showed SIGNAL (title-prepend) is the better-evidenced lever for the weak-legal regime.
- **Item 2 — keep judge-arbitration default OFF.** It is a no-op exactly where the pipeline is weak
  (legal 0%), and F-026's enron cell (the decision-relevant one) is a thin net-negative. Flip only on
  a clean one-directional win on a fresh scifact+enron gold-rank-@20 A/B (not yet run).
- **Item 3 — validate, don't retire, all three.** QU: run the ~20-min contention probe. Sufficiency +
  confidence: one shared ~115-example labeling pass unblocks both.

## §E. Correction (2026-07-22, same day): the "title-prepend SIGNAL" citation in §D is leak-tainted

§D's binding context cited a title-prepend A/B (camouflaged-legal top-10
20%→50%) as licensing the contextualization tier. 774 §J.7 subsequently
proved the 767 strata's `title` field is a GOLD-ONLY leak (only golds are
titled, and their titles are the structure descriptors — the probe was
prepending the answer key), so that arm is INVALID and its publication was
killed. The contextualization license survives on the honest evidence:
774 §J.7's leak-free Variant C (uniform 150-char doc-lead prepend) is
directional on both camouflaged cells (legal-10k R@100 0.20→0.42, median
gold rank 887→188) but fails the pre-registered real-task control — so the
tier is licensed as a RECIPE-SEARCH lane (find a contextualization that
keeps the camouflaged gain without the real-task cost; F-040), weaker than
§D's original framing. The 777 recommendation set is otherwise unchanged.

## §F. Deferral decision (2026-07-22)

Founder decision (766 §G.5): the contextualization recipe lane does not open
until hero v1 has run. 777 is dormant until then except the shared labeling
pass (sufficiency + Q-009 confidence), which remains a post-freeze candidate
on its own product merits.
