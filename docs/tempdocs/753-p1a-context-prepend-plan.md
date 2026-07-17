---
title: "P1a structural context-prepend — design + derisk + plan (DESIGN ONLY, not implemented)"
type: tempdocs
status: proposed
created: 2026-07-12
author: agent (design-only assignment; takeover→design→derisk→plan, stop at plan)
related:
  - 636-retrieval-buried-signal-long-documents   # owns P1a (D-4 table, line 370); this tempdoc designs it
  - docs/reference/search-quality-register.md    # Q-011/(a) — the eval-isolation prereq's resolution
note: >
  Renumbered from an uncommitted 720-numbered plan file (tempdoc-number collision with
  720-memory-read-injection.md); rescued 2026-07-17 from stale agent worktree before
  teardown. Parent/router tempdoc: 720-memory-read-injection.md.
---

# 753 — P1a structural context-prepend: design, derisk, plan

> **No implementation in this tempdoc.** Per assignment: takeover verdict → design → derisk → plan, then stop.

## 1. TAKEOVER

### 1.1 Is P1a still unbuilt?

**Yes — confirmed unbuilt.** No prepend/heading-injection logic exists anywhere in the chunk-embedding
path. `EmbeddingBackfillOps.processChunkEmbeddingBackfill` reads `CHUNK_CONTENT` verbatim and embeds it
unmodified (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java:352-388`);
`CombinedEnrichmentBackfillOps` does the same for its per-chunk fallback path
(`CombinedEnrichmentBackfillOps.java:292-312`). Grepped for any existing prefix/heading-injection call
site in the embed path — none found.

### 1.2 Is the eval-isolation prereq resolved?

**Yes, but its resolution reversed the premise, it did not clear P1a for a confident build.** Tempdoc 636's
own Phase-1 gate (`docs/tempdocs/636-retrieval-buried-signal-long-documents.md:529-561`) held **"DO NOT
build P1a now"** because jseval's `hybrid` mode showed `chunkMergeApplied=null` — looked like the
chunk-dense path wasn't exercised, so no eval could validate a chunk-vector fix. `docs/reference/search-quality-register.md`
**Q-011(a)** (lines 1449-1454) resolved this: `chunkMergeApplied=null` was a **stale response-field read**,
not the chunk branch being off — jseval `hybrid` *does* exercise production's chunk-dense path, confirmed
by the search-trace-level `chunk_stage.status=="executed"` (636 lines 977-987). So the *literal* blocker
named in Q-011(a) — "can't gate P1a because the eval doesn't reach the chunk path" — is gone.

**But 636's own "Direction investigation" (lines 969-1023), run immediately after resolving that same
blocker, found something that argues against P1a, not for it**: on the one corpus this was measured on
(`needle-burial-v1`, ~1000-word docs), the chunk-dense path **already fires and already retrieves the
buried fact** (whole-doc dense nDCG@10 0.82); the demonstrated loss is **fusion suppression** (hybrid 0.24
vs vector 0.82), not chunk-vector dilution. 636's explicit conclusion: *"Do not build Design v1
[P1a/P2] speculatively... the chunk-dense path it would improve already fires and is not the measured
bottleneck."* 636's own **most-recent** status banner (2026-06-24, supersedes the 06-23 sections per
`tempdocs-are-dated-history`) still lists the embedding seam under **"Deferred by design: ... the
very-long-doc embedding seam (needs an eval that doesn't exist)."**

Reconciling these: Q-011(a) resolved a **measurement artifact** (the eval does reach the chunk path); it
did **not** resolve 636's **evidence-based reservation** (on the one corpus measured, chunk-dense isn't
the bottleneck, and the corpus that would show a chunk-vector effect — long, heading-rich, real documents
like contracts — has never been eval'd). Both are true at once: no code-level blocker remains, and no
positive evidence of gain exists either. P1a is a **legitimate probe**, not a **validated fix**.

### 1.3 Where chunk text is assembled and embedded (the seam)

- **Chunk creation** (`ChunkDocumentWriter.regenerateChunks`, `modules/worker-services/src/main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java:77-186`):
  splits parent `content` via `ChunkSplitter.splitWithMetadata`, writes one Lucene doc per chunk with
  `CHUNK_CONTENT` (raw chunk text) plus **structural metadata already extracted per chunk**:
  `CHUNK_HEADING_TEXT` + `CHUNK_HEADING_LEVEL` (lines 136-152, via `ChunkOffsetMath.findPrecedingHeading`,
  `ChunkOffsetMath.java:57-77`) — the **nearest preceding Markdown-style heading** (`# `..`###### `),
  gated to `fileKind` ∈ {markdown, pdf, office} (structured-extraction output uses the same `## ` marker
  convention per tempdoc 252 Tier 1). **Important nuance**: this is a single nearest heading (level +
  text), **not a multi-level breadcrumb** ("H1 > H2 > H3") — no code builds a heading path today.
  `TITLE` is **not** propagated to chunk docs — it lives only on the parent doc
  (`IndexingDocumentOps.java:148-154`: extracted title, else filename stem — always non-blank).
- **Chunk embedding — two distinct production code paths, only one of which reads `CHUNK_CONTENT` as a
  string to embed:**
  1. **Late chunking (default-on, the common/majority path)** — `CombinedEnrichmentBackfillOps.java:373-429`:
     for a parent doc that has chunk children and fits under `lateChunkingMaxSeqLen`, the **whole parent
     content** is embedded **once** via `OnnxEmbeddingEncoder.embedWithSpans`
     (`modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java:771-796`),
     and each chunk's vector is **sliced from that one document-level embedding's token states** — no
     per-chunk string ever reaches the embedder. **A CHUNK_CONTENT-level prepend has zero effect on this
     path.**
  2. **Per-chunk batch embed (fallback path)** — triggered when late chunking is off, the parent overflows
     `lateChunkingMaxSeqLen`, or the encoder hits a GPU BFC-arena OOM (folds inline into `embedDocIds`/
     `embedContents`, `CombinedEnrichmentBackfillOps.java:389-392,418-429`); also the **entire** code path
     of the standalone `EmbeddingBackfillOps.processChunkEmbeddingBackfill`
     (`EmbeddingBackfillOps.java:317-388`), used by `BackfillScheduler.runIndividualBackfills`
     (`BackfillScheduler.java:245-270`) whenever the combined pass finds no combined work that cycle
     (`BackfillScheduler.java:126-175`). **This is the path a CHUNK_CONTENT-level prepend actually
     reaches** — and it is exactly `embeddingProvider.embedDocumentBatch(List<String>)`, a pure string
     transform, no signature change.
- **Batched field reads already exist**: `CombinedEnrichmentBackfillOps` uses
  `DocumentFieldOps.getDocumentFieldsBatch(docIds, fieldNames)` (one searcher acquisition for the whole
  batch, `CombinedEnrichmentBackfillOps.java:241-269`; interface at
  `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/DocumentFieldOps.java:171`)
  — the mechanism a prepend implementation would extend (add `CHUNK_HEADING_TEXT` + a parent-`TITLE`
  lookup to the fetched field set), not a new per-doc round trip.

### 1.4 Late-chunking interaction (the question the brief specifically asked)

**Partial composition, not full**: P1a as literally specified (prefix `CHUNK_CONTENT` before embedding)
**does not touch the late-chunking path at all** — it is a no-op for the majority of production chunked
documents today (any parent under the late-chunking token ceiling, which is the common case). It **only**
reaches: (a) parents that overflow the late-chunking ceiling or hit an arena-OOM fallback, and (b) any
install/cycle running the standalone per-chunk backfill. Making the prepend actually move the *default*
path's vectors requires a **second, distinct injection point** — prefixing the **parent's full content**
(title, once) before the single `embedWithSpans` pass — which is a different (if same-class, no-model,
index-time) change from the per-chunk prefix. See §2.3.

### 1.5 GO / NO-GO

**GO — but as a scoped, eval-gated probe, not a default-on quality claim.** No code-level blocker remains
(Q-011a is resolved, batched field-read infra exists, structural metadata is already half-captured). The
honest reservation is evidentiary, not technical: 636's own measurement found chunk-vector quality was
*not* the bottleneck on the one corpus tested, and no corpus exists yet for the genuinely-long,
heading-rich regime P1a is actually aimed at (contracts, long reports). Building it cheaply and *gating it
on a new eval before flipping it on* is exactly what 636's "Eval prerequisites before resuming Design v1"
section (lines 947-956) already prescribes, and is consistent with the project's `audit-without-test` /
`fix-root-causes-not-symptoms` discipline (do not ship an untested quality lever as a claimed win).

**Confidence: 6/10** for "build + eval this cheaply and learn something real"; **not** a confidence
rating on "this will improve recall" (that's what the eval in §4 is for — bounded-gain design, gain
literally could be ~0 per the D-4 table's own honest caveat, "bounded gain — only as good as the
structure present").

---

## 2. DESIGN

### 2.1 What gets prepended

**Title + nearest enclosing heading**, using fields that already exist (mostly) at index time:

```
"{title}\n{heading_text}\n\n{chunk_content}"
```

- `title` — parent doc's `SchemaFields.TITLE` (`modules/indexing/src/main/java/io/justsearch/indexing/SchemaFields.java:27`).
  Always populated (extracted title or filename-stem fallback, `IndexingDocumentOps.java:148-154`). Not
  currently stored on chunk docs — fetched via a **batched parent lookup** (dedup by `PARENT_DOC_ID`,
  already stored on every chunk, `ChunkDocumentWriter.java:119`), not a new per-chunk field, so **no SSOT
  schema change and no reindex-of-schema needed for this half**.
- `heading_text` — `SchemaFields.CHUNK_HEADING_TEXT`, already stored per chunk (`ChunkDocumentWriter.java:144-152`).
  This is the **nearest preceding heading only** (not a multi-level path). Building a true breadcrumb
  ("H1 > H2 > H3") would require walking `ChunkOffsetMath` state across all preceding heading levels, not
  just the last match — a modest, separate enhancement, **named but not required for v1** (see D-decision
  below).
- Empty/missing heading (non-markdown/pdf/office content, or a chunk with no preceding heading) → omit
  that line; still prepend title alone. A chunk whose parent has no meaningful title (rare — filename
  stem is always present) still gets *something*, never breaks the transform.

**Design decision named, not silently assumed**: ship v1 as **title + nearest heading** (zero new stored
fields beyond a batched parent-title read), not the richer "heading path" the brief's framing suggested.
Escalating to a full breadcrumb is a follow-on if the eval (§4) shows heading-text-alone insufficient —
don't build the more expensive version speculatively (per `structural-defects-no-repeat`'s inverse: don't
build ahead of an evidenced need either).

### 2.2 Where it's injected — fallback / per-chunk batch path (reaches ~all of today's non-late-chunked chunks)

Both call sites that build a `List<String>` to feed `embeddingProvider.embedDocumentBatch(...)`:

- `EmbeddingBackfillOps.processChunkEmbeddingBackfill` (`EmbeddingBackfillOps.java:347-388`, Phase 1
  "Collect chunk content" loop) — currently `getDocumentField(chunkId, CHUNK_CONTENT)` one-at-a-time (not
  even batched yet — an incidental improvement available here too, see §5).
- `CombinedEnrichmentBackfillOps`'s per-chunk fallback branch (`CombinedEnrichmentBackfillOps.java:289-313`,
  already batched via `getDocumentFieldsBatch`).

Change in both: extend the fetched field set to include `CHUNK_HEADING_TEXT` (already stored) +
batch-resolve each distinct `PARENT_DOC_ID`'s `TITLE` once (dedup — many chunks share one parent), then
build the prefixed string in place of raw `chunkContent` before the `embedDocumentBatch`/`embedDocument`
call. **The `EmbeddingProvider` interface itself does not change** — it already accepts arbitrary strings;
this is purely what string is constructed before the call. This is the "one pooled embed of a longer
string, no interface change" property the brief named, and it is real **for this path**.

### 2.3 Where it's injected — late-chunking single-pass path (reaches the *default*/majority path)

Per §1.4, the fallback-only version above leaves the dominant production path untouched. To make the
lever apply broadly, **prepend `title` (title only — headings don't make sense for a single whole-document
pass with one shared prefix) once to the front of the parent `content`** before
`OnnxEmbeddingEncoder.embedWithSpans` is called (`CombinedEnrichmentBackfillOps.java:425-429` builds
`lcContent`; that string is what needs the prefix). This requires tracking the prefix's character-length
offset so the existing chunk-to-token-span slicing (which is anchored to the *original* content's char
offsets, `CHUNK_START_CHAR`/`CHUNK_END_CHAR`) still lines up — either (a) shift all span math by the
prefix length, or (b) keep the prefix out of span-offset accounting entirely and rely on the encoder's
attention naturally letting the prefix's tokens influence pooled sub-vectors without being a "spanned"
region themselves (needs verification against `embedWithSpans`'s actual slicing implementation, not
assumed — flagged in §3 as an implementation-phase check, not resolved here since this is design-only).

**Name what this is**: **D-4's P1a and P2 (late chunking) are not actually separable once P1a is asked to
apply broadly** — P1a-broad is "P2's single-pass window, with a title prefix," i.e. a small enhancement
*on top of* the already-shipped P2 mechanism, not an independent alternative to it. The D-4 table's framing
of P1a as a zero-interface-change *alternative* to P2 is accurate only for the narrow fallback path; for
the default path it is additive to P2, not a substitute. This is worth flagging back into 636/the D-4
table as a design-table correction if this tempdoc's design is picked up.

### 2.4 Why (still) no interface change

- `EmbeddingProvider.embedDocument` / `embedDocumentBatch` signatures: unchanged (`String`/`List<String>`
  in, `float[]`/`List<float[]>` out) — `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/EmbeddingProvider.java`.
  the fallback-path design (§2.2) purely changes the *content* of the strings passed in.
- The late-chunking path (§2.3) similarly doesn't change `embedWithSpans`'s signature — it changes what
  string is passed as `content`. The genuinely new work there is verifying span-offset math survives a
  shared prefix, which is a small, bounded correctness check, not a new interface.
- No gRPC/proto change, no new stored field required for the fallback path (title read via existing
  `PARENT_DOC_ID`); the late-chunking path needs no new field either.

### 2.5 Reindex requirement

**A full re-embed of existing chunk vectors is required** for the prepend to take effect — this changes
what string is embedded, and existing `CHUNK_VECTOR` values were computed from the old (un-prefixed)
string. Per the "Stale index after field changes" pitfall (`CLAUDE.md`), this is not a schema change but
is the same class of staleness: **existing indices must be rebuilt** (`jseval run --reset` in eval mode,
or `jseval run --start-backend --clean` for the dev stack) — flipping the config gate alone does nothing
to already-embedded chunks; only newly (re-)embedded ones get the new vector. The correct operational
framing for the config gate (§4) is "affects embeddings computed from now on," and the plan (§5) must
include an explicit reindex step, not assume the backfill loop will silently reprocess `COMPLETED` chunks
(it won't — `CHUNK_EMBEDDING_STATUS=COMPLETED` chunks are not re-enrolled without a status reset or a
fresh ingest).

### 2.6 What is superseded / not superseded

- **Nothing existing is superseded.** `CHUNK_VECTOR` stays the same field representing the same thing (a
  vector for the same chunk) — per 636's own D-3 framing (line 356-358), this is "a projection improved at
  its source," not a new representation needing a governance-register entry.
- Late chunking (P2, already shipped default-on) is **not replaced** — §2.3 makes P1a additive to it, not
  a fork/alternative implementation.
- The narrow fallback-path version (§2.2) and the late-chunking-path version (§2.3) are **two parts of one
  lever**, not competing designs — both are needed for the prepend to reach "most chunks," not just the
  minority fallback population.

---

## 3. DERISK

| # | Risk | Severity | Notes |
|---|---|---|---|
| R1 | **Gain may be ~0 or unmeasurable on the corpora we have.** 636's own Direction investigation found chunk-dense already retrieves the needle on `needle-burial-v1`; the bottleneck there is fusion, not chunk-vector quality. P1a is aimed at a regime (long, heading-rich, real documents) that has **no existing eval**. | High | Bounded by design: D-4 itself calls P1a's gain "bounded — only as good as the structure present." Mitigate by building the eval *first* (§4) and gating the config default on its result, not shipping default-on speculatively. |
| R2 | **The default/majority path (late chunking) is untouched by the naive prepend.** A P1a implementation that stops at §2.2 would quietly not affect most production chunks — an easy "looks shipped, isn't reaching the path that matters" trap (the exact shape of `wrong-gate` / `audit-without-test`). | High | §2.3 names the fix; a regression test must assert the prepend actually changes the *late-chunking* embedded string too, not just the fallback path (see §4 test list) — not just that the code compiles. |
| R3 | **Reindex cost.** Every existing chunk vector must be recomputed; this is a full embedding pass over all chunk docs (GPU-bound, not free) — same cost class as any embedding-affecting change (e.g. a model swap), not novel to this lever, but real for large personal-file corpora. | Medium | No new mitigation beyond the existing reindex tooling (`jseval run --clean`); size the eval-gate decision on a representative corpus size before recommending default-on for real user indices. |
| R4 | **Late-chunking span-offset math with a shared prefix.** §2.3's char-offset assumption (`CHUNK_START_CHAR`/`CHUNK_END_CHAR` anchored to un-prefixed content) needs verification against `embedWithSpans`'s actual slicing — not confirmed in this design pass (design-only scope; flagged for implementation-phase verification, not resolved here). | Medium | First implementation task must include a targeted unit test proving span slice boundaries are unaffected (or correctly shifted) by a shared title prefix, using `OnnxEmbeddingEncoderLateChunkingTest.java` as the existing test home. |
| R5 | **Token budget headroom.** Adding a title (typically short, a handful of tokens) to the late-chunking single-pass content nudges documents closer to `lateChunkingMaxSeqLen`; for documents already near the ceiling this could tip some into the windowed-fallback path more often. | Low | Title is short (~5-15 tokens typically); ceiling is per-model-configured (hundreds to low-thousands of tokens) — bound the practical effect, don't assume it's zero. Confirm empirically during eval (§4), not asserted here. |
| R6 | **Structural metadata reliability.** `CHUNK_HEADING_TEXT` extraction is gated to `fileKind` ∈ {markdown, pdf, office} and depends on `##`-style markers surviving structured extraction (tempdoc 252 Tier 1) — plain-text/code/CSV files never get a heading, and PDF/office heading fidelity depends on the extractor's annotation quality, not verified in this design pass. | Medium | The design already handles "no heading" gracefully (omit line, title-only prefix) — not a correctness risk, but bounds expected gain further on non-structured file kinds (the bulk of "logs," per 636's own headline example, likely get title-only). |

**Implementation model/effort recommendation**: **Sonnet**, bounded chunks — (a) fallback-path prepend +
batched-title-lookup (§2.2) is a small, mechanical, well-scoped change; (b) late-chunking prefix + span-math
verification (§2.3) is the higher-risk piece and should be its own chunk with the span-offset test as an
explicit acceptance item, not folded silently into (a). Do not delegate the eval-design/interpretation
step (§4) — that's judgment work per `delegating-to-subagents`.

---

## 4. PLAN

### 4.1 Files / classes to touch (when implementation is authorized)

| File | Change |
|---|---|
| `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java` | `processChunkEmbeddingBackfill` Phase 1 (lines ~347-375): batch-fetch `CHUNK_HEADING_TEXT` + resolve parent `TITLE` (dedup by `PARENT_DOC_ID`) alongside `CHUNK_CONTENT`; build prefixed string before `embedDocumentBatch`/fallback `embedDocument` calls. |
| `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java` | Per-chunk fallback branch (lines ~289-313): extend `fieldsToFetch` with `CHUNK_HEADING_TEXT`; add a parent-title batch resolve step; build prefixed `chunkContent` before adding to `embedContents`. Late-chunking branch (lines ~373-429): prepend parent `TITLE` to `lcContent` before the `embedWithSpans` call; verify/adjust span-offset math. |
| `modules/worker-core/.../embed/onnx/OnnxEmbeddingEncoder.java` | No signature change expected; verify `embedWithSpans`'s span-to-content-offset assumptions hold with a prefixed `content` string (read, don't assume). |
| new config key (config module, `EnvRegistry.java` + `ResolvedConfigBuilder.java`, pattern-matched on `justsearch.embed.late_chunking_enabled`) | e.g. `justsearch.embed.context_prepend_enabled` (default **false** until the eval in §4.3 clears it) — the gate this whole lever ships behind. |

### 4.2 Tests

- Unit: `EmbeddingBackfillOps` / `CombinedEnrichmentBackfillOps` — assert the exact prefixed string
  reaches `embedDocumentBatch` (a spy/capture on the `EmbeddingProvider`), covering: title-only (no
  heading), title+heading, missing-title-fallback-to-filename-stem (should never happen per §2.1 but
  assert defensively), and gate-off (byte-identical to today's un-prefixed behavior — a regression guard
  proving the config default doesn't silently change existing behavior).
  Homes: `CombinedEnrichmentBackfillOpsTest.java`, a new/extended `EmbeddingBackfillOpsTest` (check if one
  exists before creating — `explore-before-implementing`).
  hint: `BgeM3BackfillOpsTest.java` shows this test-doubling pattern already exists for a sibling backfill
  op — mirror it, don't reinvent.
- Unit: late-chunking span-offset correctness with a prefix — extend
  `OnnxEmbeddingEncoderLateChunkingTest.java` (R4's acceptance test).
- Integration: a chunked-parent round trip through `IndexingLoop` with the gate on, asserting
  `CHUNK_VECTOR` differs from the gate-off vector for the same content (proves the prepend actually
  changed what got embedded end-to-end, not just that a string was constructed — the `audit-without-test`
  discipline applied to this specific lever).

### 4.3 The eval (before flipping the config default)

Per 636's own "Eval prerequisites before resuming Design v1" (lines 947-956), reuse existing instruments,
don't fork a new one:

1. **Build (or extend) a buried-signal corpus that is structurally realistic** — unlike `needle-burial-v1`
   (synthetic, ~1000-word, mostly headless paraphrase docs), this needs **long, heading-rich documents**
   (contracts/reports with real section structure) with a distinctive fact buried under a specific
   heading, per 636's own honest gap ("no eval exists for this [very-long-doc] regime," line 1010). This
   is new corpus-authoring work, not reusable as-is from 635/636's existing corpora.
2. **Pin keyword/BM25 retrieval as a no-regression guard** (636's binding constraint, lines 943-945) — this
   lever must be verified keyword-neutral, not merely assumed so because it's index-time. Since it only
   changes the dense leg's input, this is very likely true, but 636 explicitly says "must be verified, not
   assumed" (line 941) — Design v2's−2.1%/−2.7% regression is the cautionary precedent for skipping this
   check.
3. **Use the existing per-leg isolation instrument**: `jseval recall-profile` /
   `staged_recall_accounting.py` (`scripts/jseval/jseval/recall_profile.py`,
   `scripts/jseval/jseval/projections/staged_recall_accounting.py`) already reports per-leg (`vector`
   isolated) union recall — exactly the "chunk-dense-isolating" measurement Q-011(a) built. Run it
   gate-off vs gate-on on the same shared index (**shared-index A/B methodology**, 636's own
   post-hoc-learned lesson from Design v2's regression, lines 952-953) — build once, flip the config, only
   the flip differs.
4. **Metric**: recall lift (leg-union recall / nDCG@10 on the `vector` leg specifically) on the new
   structured-long-doc corpus, gate-off vs gate-on; keyword/BM25 leg unchanged (±noise floor) as the
   guardrail from step 2.
5. **Decision rule**: only flip `justsearch.embed.context_prepend_enabled` default to `true` if the new
   corpus shows a measured, reproducible recall lift with no BM25/lexical-leg regression. If the lift is
   null (plausible — R1), this tempdoc's honest outcome is the same shape as 636's Design v1 arc: a cheap,
   correctly-deferred probe that produced a negative-or-null result rather than a shipped win. That is a
   legitimate, complete outcome — not a failure to fix silently or re-litigate (`structural-defects-no-repeat`'s
   sibling: a null result on an honestly-run eval is data, not an excuse to keep pushing under a new name).

### 4.4 Reindex step (operational, not code)

After implementation + eval-driven default flip: `jseval run --start-backend --clean` (dev stack) or
`jseval run --reset` (eval corpora) to force full re-embedding — per §2.5, the backfill loop does not
silently re-enroll `COMPLETED` chunks; a stale index keeps emitting old vectors until a clean rebuild.

---

## Summary for the parent

**GO** (confidence 6/10) — build P1a as a scoped, eval-gated probe, default-off until the eval in §4.3
clears it. The literal code-level blocker (Q-011a) is resolved; what remains is evidentiary, not
technical: 636's own measurement found chunk-vector quality wasn't the demonstrated bottleneck on the one
corpus tested, and the regime P1a actually targets (long, heading-rich real documents) has no eval yet —
building that eval is this plan's first deliverable, not an afterthought. The single most important design
finding: **the naive "prefix CHUNK_CONTENT" version only reaches the late-chunking *fallback* path — the
default/majority production path (late chunking, already on) needs a second, distinct injection (a title
prefix on the whole-parent single-pass content, §2.3) to be touched at all.** Shipping only §2.2 would be
a silent, easy-to-miss non-fix for most real indices.
