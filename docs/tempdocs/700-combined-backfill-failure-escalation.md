---
title: "CombinedEnrichmentBackfillOps has no failure escalation/backoff — a deterministically-failing doc retries forever"
type: tempdoc
status: "planned — analysis complete (needs adaptation, not a clean method call); not yet implemented"
created: 2026-07-08
updated: 2026-07-08
related: [312, 334]
---

# 700 — Combined-backfill failure escalation

## What this document is

A design + implementation plan for one engine-audit finding: the
production-preferred enrichment path, `CombinedEnrichmentBackfillOps`, has **no
failure escalation and no backoff** for embedding / SPLADE / NER failures. A
document whose content deterministically fails one encoder is re-attempted every
idle cycle, forever, with no path to `FAILED` and no backoff. The individual-op
siblings (`EmbeddingBackfillOps`, `SpladeBackfillOps`, `NerBackfillOps`) all
implement the poison-pill escalation; the combined path — the one actually
preferred whenever ≥2 encoders are loaded — never got it.

This was surfaced while auditing the enrichment backfill path against source
(two findings that are the same gap: the embedding side and the NER side). A
source analysis (the "Why it is NOT a clean method call" section below)
establishes it as a bounded refactor, not a clean method call — which is why it
is a tempdoc with an implementation plan rather than a one-line PR. **Not yet
implemented.**

## The defect (confirmed against source)

`CombinedEnrichmentBackfillOps` persists per-doc status via a **single batched
write** per cycle: one shared `updatesByDocId` map (created at
`CombinedEnrichmentBackfillOps.java:200`, seeded per doc at `:207`), mutated in
place across the embedding / SPLADE / NER phases, and flushed **once** at
`:390-402` via `indexingCoordinator.updateDocumentsBatch(...)`. This single-RMW-
per-doc design is deliberate — the class Javadoc (`:36-45`) cites it as the
tempdoc-312 BUG-1 fix (eliminating per-stage read-modify-write churn).

On failure, that path does **not** escalate:

- **Embedding** failure branch resets status to `PENDING` with no retry-count
  increment (`:300-303`).
- **SPLADE** and **NER** (Phase 3c) failures are only logged and counted into
  local `spladeFailed` / `nerFailed` counters (SPLADE batch catch `:328-331`;
  NER per-doc `:380-385`) — `*_STATUS` is left untouched (`PENDING`), and no
  `*_RETRY_COUNT` is written.
- The batched pre-fetch `fieldsToFetch` (`:180-187`) never fetches any
  `*_RETRY_COUNT` field, so the combined path has no retry-count in hand to even
  make an escalation decision.

Contrast — the individual ops DO escalate, via public static handlers:
`EmbeddingBackfillOps.handleEmbeddingFailure` (`:242-280`) /
`handleChunkEmbeddingFailure` (`:434-471`), `SpladeBackfillOps.handleSpladeFailure`
(`:241-284`), `NerBackfillOps.handleNerFailure` (`:148-186`). Each increments the
retry count and, at `*_MAX_RETRIES` (= 3, `SchemaFields`), marks the doc
`*_STATUS_FAILED` so it stops being re-selected.

## Why it matters

A document that deterministically fails one encoder (a Tika-mangled string that
reliably crashes a tokenizer; a pathological input that OOMs a single ONNX call
without failing the batch) never reaches `FAILED` on the combined path. It is
re-selected and re-attempted **every idle cycle, forever**, with no backoff
(`IDLE_SLEEP_MS`/`ACTIVE_IDLE_SLEEP_MS` cadence only). Two consequences:

1. `/api/status` "how much of the corpus has vectors" never reaches 100% for a
   corpus containing even one such document, and no `FAILED` marker points the
   operator at *which* document is stuck.
2. A whole class of same-cause failures re-burns encoder CPU/GPU every cycle with
   no escalating cost signal — unlike `SpladeBackfillOps`'s systemic-failure
   path, which backs off exponentially under the same conditions.

## Why it is NOT a clean method call

The four `handle*Failure` statics **write immediately**: each does its own
single-doc `documentFieldOps.getDocumentField(RETRY_COUNT)` read + immediate
single-doc `indexingCoordinator.updateDocument(...)`. Calling them from inside
the combined loop would:

- **Double-write** every affected doc — the doc's other-stage successes already
  sit in `updatesByDocId` and get flushed again at `:399`, reintroducing exactly
  the per-stage RMW churn tempdoc 312 removed.
- **Bypass the batched read**, reintroducing per-doc `getDocumentField` calls the
  `getDocumentFieldsBatch` pre-fetch (`:189-192`) exists to eliminate.
- **Race** its own immediate write against the later batch RMW of the same doc.

## Proposed fix

Keep the single-batched-write invariant; adapt the escalation into it.

1. **Extend the batched fetch.** Add `EMBEDDING_RETRY_COUNT`, `SPLADE_RETRY_COUNT`,
   `NER_RETRY_COUNT` (and `CHUNK_EMBEDDING_RETRY_COUNT` for the chunk-embed path)
   to `fieldsToFetch` (`:180-187`) so the decision is made from already-fetched
   data — no new per-doc reads.
2. **Split decision from write.** Extract a pure helper from each `handle*Failure`
   — e.g. `EmbeddingBackfillOps.computeFailureUpdate(currentRetryCount) →
   {retryCount, status}` — that returns the field updates without writing. Have
   BOTH the individual op (which then writes immediately, unchanged behavior) and
   the combined path (which merges the result into `updatesByDocId`) call the
   same helper. This preserves parity by construction and avoids a hand-ported
   copy that can drift.
3. **Apply in each combined failure branch.** In the embedding (`:300-303`),
   SPLADE (`:322-331`), and NER Phase 3c (`:355-388`) failure branches, look up
   the fetched retry count, call the pure helper, and write the returned
   `{*_RETRY_COUNT, *_STATUS}` into that doc's `updatesByDocId` entry — never
   calling `updateDocument` directly.
4. **Preserve the single flush.** All escalation writes go through the existing
   `:390-402` batch flush; no mid-phase writes.

### Secondary (decide during implementation, may be a follow-up)

Systemic whole-batch backoff. The individual SPLADE path signals batch-level
exponential backoff via `BackfillScheduler.recordSpladeBackfillResult` →
`BackoffPolicy.spladeBackoffMs`. The combined path has no analogous
whole-batch-failure backoff. Decide whether to add one (an analogous
`recordCombinedBackfillResult` signal) or to leave it to the per-doc poison-pill
above. Per-doc escalation is the primary correctness fix; systemic backoff is a
cost-optimization and may be split into a follow-up.

## Regression risk (the reason this is medium-high, not low)

- The pure-helper extraction touches code the **individual ops** also call —
  their behavior must not change. Their existing tests (`EmbeddingBackfillOpsTest`,
  `NerBackfillOpsTest`, SPLADE) must stay green.
- The single-RMW-per-doc guarantee (tempdoc 312 BUG-1) must be preserved — the
  most likely way to get this wrong is to reintroduce a mid-phase
  `updateDocument` and double-write.
- `CombinedEnrichmentBackfillOps` has **zero existing tests** — the regression
  test is built from scratch.

## Verification plan

- **Pure-helper unit test:** `computeFailureUpdate(N)` returns `{N+1, PENDING}`
  for `N+1 < MAX`, and `{MAX, FAILED}` at `N+1 == MAX`. Cheap, exhaustive.
- **Combined-path regression test:** construct a `CombinedEnrichmentBackfillOps`
  with a fake `EmbeddingProvider` whose `embedDocumentBatch` deterministically
  returns `null` for one specific doc across repeated `processCombinedBackfill`
  calls; assert that doc's `embedding_status` transitions to `FAILED` after
  `EMBEDDING_MAX_RETRIES` cycles (and `embedding_retry_count` increments each
  cycle), while other docs are unaffected. Repeat with a `NerService` fake that
  throws for one doc. **This test must fail against today's code** (which never
  leaves `PENDING`).
- **Single-write assertion:** assert exactly one `updateDocumentsBatch` call per
  cycle (spy the fake `IndexingCoordinator`), and zero `updateDocument` calls —
  pinning the tempdoc-312 invariant.
- **Full:** `./gradlew.bat build -x test` + `:modules:worker-services:test` +
  the individual-op test classes green; `spotlessApply`.

### Test scaffold cost (known)

`BackfillContext` (`:49-64`) requires ~15 collaborators to fake: `documentFieldOps`,
`indexingCoordinator`, `commitOps`, `signalBus`, three provider `Supplier`s
(`embeddingProviderSupplier`, `spladeEncoderSupplier`, `nerServiceSupplier`),
`runningSupplier`, `allowEmbeddingWritesSupplier`, `batchSize`, `log`,
`chunkVectorsEnabled`, two `ArrayDeque` caches, `batchesSinceCommit`. Plus a
static singleton `OperationalMetrics.getInstance()` (`:456`, in a `finally`) and
`EncoderOrtRunSpans.maybeEnrichmentBatch()` (`:89`, OTel no-op) that are not
passed via context — the static `OperationalMetrics` is the testability wrinkle
(may need a test-friendly reset or an accept-as-no-op). Budget for this up front.

## Acceptance criteria

- A deterministically-failing doc reaches `FAILED` via the combined path after
  `*_MAX_RETRIES` cycles (no infinite retry); `*_RETRY_COUNT` increments per
  cycle.
- Exactly one batched write per cycle; no per-stage `updateDocument` (tempdoc-312
  invariant intact).
- Individual-op paths behaviorally unchanged (their tests green).
- Covers embedding, SPLADE, and NER failure branches.

## Open questions & unverified assumptions

Assumptions in the plan above that are NOT yet verified — resolve each during
implementation:

- The pure-helper extraction is *assumed* to serve both the individual ops and
  the combined path with no behavioral change to the individual ops. This holds
  only if `EmbeddingBackfillOps` / `NerBackfillOps` / SPLADE tests stay green
  after the extraction — not yet done.
- `CHUNK_EMBEDDING_RETRY_COUNT` is inferred by analogy to the doc-embed path;
  confirm the field constant actually exists in `SchemaFields` before relying on
  it.
- The `*_MAX_RETRIES` constant names and their value (3) are from the audit
  reading; re-confirm against `SchemaFields`.
- Unit-testability of the static `OperationalMetrics.getInstance()` (used in a
  `finally`) is unverified — it may need a test reset seam or an accept-as-no-op.
  Not yet attempted.
- Whether the systemic whole-batch backoff (the "Secondary" item) is needed at
  all is an open decision, deferred to implementation.

## Related work from the same audit

This is one item in a set of fixes from the same source-reading audit of the
search / indexing engine. Already shipped as separate PRs: (1) canonical
doc-drift corrections; (2) three correctness fixes — entity-filter canonical
expansion, `SyncDirectoryOps` walk-time skip policy, and reranked-hit
excerpt/title enrichment — plus a stale-comment correction. Deferred siblings,
each needing its own follow-up: CRTRT NRT-config wiring (needs-design — see below),
the eval-gated search-quality items, and two descoped governance gates.

## Out of scope

- **CRTRT NRT-config wiring** — a *separate* engine-audit item, reclassified to
  needs-design after its own regression test showed the config-path
  `nrtHardMaxStaleMs` default is `Long.MAX_VALUE` (not the `RuntimeSession`
  field's `50L`), so naive wiring violates Lucene's `targetMaxStaleSec >=
  targetMinStaleSec` constraint. Its own tempdoc when picked up.
- Eval-gated search-quality items (dense EUCLIDEAN calibration, CE recall-pool /
  Design v3) — route through `/search-quality`.
- The two descoped "systemic gates" (config-wiring drift gate; reason-code
  vocabulary unification).

## Difficulty & model recommendation

**Medium-high.** The shape is understood, but it refactors shared handlers,
must preserve a load-bearing invariant, and builds a heavy test scaffold from
scratch. Recommend **Opus, high effort**, with an independent review of the
single-RMW invariant before merge — this is the one Wave-0 item where a sloppy
pass would introduce a real regression. (Implementation note: this is
loop/ops failure-handling, not encoder inference code, so `/inference-runtime`
likely does not apply — confirm at implementation time.)

## Evidence anchors

_All `file:line` references in this document are as of 2026-07-08 against
then-current source; this area is actively developed — re-confirm line numbers
against current source before editing._

- `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java`
  — `updatesByDocId` (`:200,:207`), batched flush (`:390-402`), `fieldsToFetch`
  (`:180-187`), embed-failure branch (`:300-303`), SPLADE (`:322-331`), NER Phase
  3c (`:355-388`), `BackfillContext` (`:49-64`).
- Individual-op escalation:
  `EmbeddingBackfillOps.java:242-280,434-471`,
  `SpladeBackfillOps.java:241-284`, `NerBackfillOps.java:148-186`.
- Systemic backoff precedent: `SpladeBackfillOps.java:148-163`,
  `BackfillScheduler.java:275-289`.
- Single-RMW design this must preserve: tempdoc 312 (BUG-1); the class was
  introduced in tempdoc 334.
