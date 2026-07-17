---
title: "Content-addressed eval index cache: reuse a built index if and only if (corpus_signature × config_cohort_key) match byte-exactly — keep the fresh-build validity guarantee while amortizing the ~50-min/10k-doc rebuild that eval campaigns currently pay repeatedly for identical inputs"
type: tempdocs
status: "open — charter/stub (2026-07-17). Founder-commissioned lane for a new agent; no implementation here. The takeover/theorize/design passes expand this document."
created: 2026-07-17
author: agent (Fable orchestration), chartered at founder direction during the Phase-2 utility campaign ("open a new tempdoc for this. ill set a new agent on it")
category: eval-infrastructure / measurement-economics / index-lifecycle
related:
  - 704-measurement-substrate-correct-data-program   # pillar 4 (measurement economics: iteration speed bounds affordable correctness) + pillar 6 (isolated eval lane) — this lane serves both
  - 716-jseval-run-artifact-coherence                # RETIRED the old --clean protected-set index reuse — that was UNKEYED reuse; understand why before designing (this doc's central distinction)
  - 676-headless-eval-product-contract               # the eval-lane contract this cache would live inside
  - 624-agentic-retrieval-eval-rebuild               # the consumer whose campaigns motivated this (Step-2 + Phase-2, 2026-07-16/17)
  - 713-dense-authority-consolidation                # §M-3/M-5: the B-confidence incremental-reuse anomaly — the concrete cost of ambiguous index provenance
  - 717-intermittent-fresh-build-chunk-death         # fresh builds are not risk-free either; the cache must not mask this class
---

> NOTE: Noncanonical working tempdoc. STUB + charter. Verify every inherited claim against the
> cited tempdocs and current `main` before building on it.

# 751 — Content-addressed eval index cache

## The problem (measured, 2026-07-16/17)

Every eval run rebuilds its corpus index from scratch: backend `--clean` → ingest → full
enrichment (embeddings, SPLADE, chunks, NER). On a 10k-doc corpus that is **~50 minutes of GPU
wall-clock per build**. During the Step-2 + Phase-2 utility campaigns, `mixed/en-legal-clerc-10k-verbose`
was built **three times in ~12 hours** for the byte-identical corpus (`corpus_signature`
`7b108fc4…`) under the byte-identical engine config (same `config_cohort_key`). The rebuilds
bought nothing: same inputs, same config, same expected index modulo HNSW nondeterminism.
Campaign wall-clock is dominated by ingest, not by measured cells (Phase-2: ~50 min ingest vs
~40 min of cells per 10k run).

## Why fresh-build is currently correct (do not regress this)

A from-scratch ingest makes the run's identity pins **true by construction**: the index provably
derives from exactly the committed corpus bytes + the current engine config. Index reuse without
that proof has bitten repeatedly:

- **tempdoc 716** retired jseval's `--clean` protected-set reuse — *unkeyed* reuse ("hope
  nothing changed") produced runs whose artifacts were ambiguous about what they measured.
- **tempdoc 713 §M-3/M-5**: an incremental-reuse arm had to be downgraded to B-confidence and
  hand-probed (4293/4293 chunk vectors) to be usable at all.
- **F-032-class silent state**: an index can carry destroyed-but-status-COMPLETED data; reuse
  inherits unknowns invisibly.

## The design thesis (what makes this different from what 716 retired)

**Keyed reuse is the opposite of unkeyed reuse.** Cache key = `(corpus_signature ×
config_cohort_key [× index-relevant engine version])`. Cache HIT → adopt the built index
(provenance identical by construction, same guarantee as a fresh build); any mismatch or any
doubt → MISS → fresh build (fail-closed). The validity guarantee is preserved; only redundant
identical builds are eliminated. Bonus, not just economics: **within-campaign comparability
improves** — arms/runs sharing one physical index eliminate HNSW rebuild variance (the F-037
ranking-instability class) instead of resampling it per run.

## Hard requirements (inherit; the design must satisfy all)

1. **Fail-closed**: any pin mismatch, any unverifiable cache entry, any doubt → fresh build.
   Never a flag that forces adoption of a stale entry.
2. **Key completeness is the crux**: `config_cohort_key` must actually cover every index-shaping
   input (analyzer config, chunking params, embedding model identity, SPLADE model, field
   schema/`fields.v1.json`, enrichment settings, engine version where index format changes).
   Audit what the cohort key covers TODAY vs what shapes the index — any index-shaping input
   outside the key is a silent-staleness hole (the whole 716 failure class re-entering through
   the key). This audit is the first real work item.
3. **Post-build content attestation**: a cache entry should store verifiable facts from its
   build (doc count, chunk-vector count, per-field coverage — the 713/717 probe set) and
   re-verify cheap invariants at adoption time, so a 717-class silently-degenerate build cannot
   be immortalized in the cache.
4. **Run records stay honest**: a record produced against a cached index must carry the cache
   provenance (entry id, built-at, attestation) — a projection of the same pins, never a
   weakening of them.
5. **Locking/concurrency**: one Gradle/backend at a time is the repo convention; the cache must
   tolerate concurrent agents at least fail-closed (lock or copy-on-adopt, never share a live
   data dir between two backends).

## Scope / boundary

- Eval lane only (`runHeadlessEval` / jseval data dirs) — production index lifecycle is NOT this
  doc (676 owns the eval-lane contract this slots into).
- No change to what `--clean` means for a MISS: a miss is exactly today's behavior.
- The 624 campaigns are the first consumer; the powered-run economics (pillar 4) the motivation.

## First work items for the incoming agent

1. **Key-completeness audit** (requirement 2): enumerate index-shaping inputs vs
   `config_cohort_key` coverage; file the gap list. This decides feasibility — if the key can't
   be made complete cheaply, the cache is unsafe and this doc should close as won't-do with that
   finding.
2. Inventory where the eval backend's data dir lives per run (`jseval/backend.py`,
   `serve-eval-backend.py` in the step2-powered worktree's campaign tooling) and what "adopt an
   index" mechanically means (copy vs point-at; startup validation hooks available).
3. Design the entry layout + attestation record; then the standard theorize → design → derisk
   pipeline before any implementation.

## Evidence pointers

- Rebuild timings: step2-powered worktree `scripts/jseval/tmp/step2-powered/chain-step2.log` +
  `tmp/phase2/chain-phase2.log` (ingest phases, readiness progress lines).
- The founder-visible cost instance: legal-10k built 3× in 12h (2026-07-16 21:41, 2026-07-17
  03:21, 2026-07-17 08:02 chains).
- Observations inbox note (2026-07-17, session 109145ac): the keyed-vs-unkeyed distinction.
