---
title: "Lane E — search-quality re-derivation: chunking, small-to-big, parameter sweeps (Part 2 shipped, Parts 1/3/4 designed)"
type: tempdocs
status: "IN PROGRESS (2026-09-03) — Part 2 (aggregate-then-cut parent collapse) implemented, falsified and measured; Parts 1/3/4 have a pre-implementation pass and a campaign plan but are NOT executed. Part 3 is blocked on lane A."
created: 2026-09-03
updated: 2026-09-03
lane: E (decision re-examination programme, wave 2)
model: opus (implementation)
parent: 00-program-overview (decision re-examination programme, 2026-09-01 audit)
coordination: "→ lane D waits on Part 1's chosen (chunk_tokens, overlap, threshold) plus a chunker version string for its fingerprint; Part 2 ships without it and needs nothing from D. → tempdoc 854 (CHARTER, not started) owns the 854 W2-fix pool/limit coupling guard; this lane sweeps the fusion numbers in Part 4 only if 854 is still idle then. → Part 3 needs lane A's per-request ContextBudget merged."
related:
  - 774-passage-first-retrieval-program   # the chunk-branch levers this Part 2 lever sits beside
  - 854-fusion-residue-lane               # CHARTER; owns the W2-fix, not this
  - 912-wave1-residue-worker-watcher-and-commit-floor  # commit-floor facts; cadence numbers here are recorded, not acted on
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation  # detached-driver method, game-contamination rule
---

# Lane E — search-quality re-derivation

The 2026-09-01 audit found four decision clusters in the retrieval path that were set early, mostly
by weaker agents, and never re-derived. This tempdoc is lane E's contract. It restates the brief's
items as a checklist (§A), records a source-verbatim verification of every `file:line` claim the
brief makes (§B — the brief was written before wave 1 landed, so every citation is a hypothesis),
plans the chunk-size campaign without executing it (§C), and implements and measures Part 2 (§D–§G).

---

## §A Scope checklist

### Part 1 — chunk-size campaign (PENDING; lane D waits on its number)

Prerequisite: none technical; it is deferred behind Part 2 only because Part 2 ships independently
and Part 1 costs machine-days. Campaign plan in §C.

- [ ] Sweep `DEFAULT_CHUNK_TOKENS ∈ {128, 256, 384, 500} × overlap ∈ {0, 25, 50}` with
      sentence-aligned boundaries, on the register's eval sets including the multilingual ones.
- [ ] Metrics per arm: nDCG@10 + recall@50 for the chunk leg alone and fused; SPLADE truncation
      rate; index size; indexing throughput.
- [ ] RAG answer quality on the "845/881 question sets" — **see §B.11: these do not exist as
      artefacts.** Replacement instrument named in §C.
- [ ] Reconcile `OnnxEmbeddingEncoder.chunkOverlap` (128) with the splitter overlap (50) or
      document why they differ.
- [ ] Deliver `(chunk_tokens, overlap, threshold)` with σ-aware evidence, a PR touching only the
      `ChunkSplitter` / `ChunkDocumentWriter` constants, and a chunker version string lane D reads.

### Part 2 — parent collapse fix (DONE, this PR)

- [x] Over-fetch chunk hits by a configurable multiple before cutting.
- [x] Aggregate per parent with `max + λ·Σ(decayed rest)`, λ configurable, λ=0 ≡ today.
- [x] Cut to the collapse cap only after aggregation.
- [x] Collapse stays deterministic for the eval gate (stable sort; purity and permutation tests).
- [x] Two config keys through `EnvRegistry` / `ResolvedConfigBuilder`, `config-surface` changeset
      and baseline advanced in the same commit.
- [x] Unit tests for the aggregator, incl. a pre-916 oracle for the λ=0 equivalence claim.
- [x] Integration test through the collapse on a real chunked Lucene index.
- [x] Falsification: every new test broken once, observed failing, restored (§F).
- [x] Same-index A/B on the chunked corpora + a short-corpus control, ratchets green (§G).
- [x] Pre-registered ship/park rule written before the measurement ran (§D.7).
- [x] Register finding `F-056` (§H).

### Part 3 — small-to-big retrieval (BLOCKED on lane A)

Prerequisite: lane A's per-request `ContextBudget` merged. Not started; no design here beyond the
brief, because the budget's shape determines the window sizing.

- [ ] Separate retrieval unit from context unit in `RagContextOps` assembly.
- [ ] Neighbouring-chunk or heading-bounded window sized by `ContextBudget`.
- [ ] `--gate wire` if the `RetrieveContext` response shape changes.
- [ ] Re-evaluate the `parent_token_count` ramps once small-to-big exists; retire with a sweep if
      their reason is gone (coordinate with 854, which owns the branch-weight ramp).
- [ ] Evidence: RAG answer quality + `context_truncated` rates before/after.

### Part 4 — parameter sweep (PENDING; after Part 1's chunk size is chosen)

- [ ] `index.similarity.text.k1/.b` — **§B.6 overturns the brief's premise here**: they are already
      settable from YAML. The item becomes "give them env/sysprop reach and defaults", not "wire a
      dead lever". Then grid `k1 ∈ {0.6, 0.9, 1.2, 1.5} × b ∈ {0.3, 0.4, 0.6, 0.75}` (no reindex).
- [ ] Fusion numbers (`rrf_k`, `vector_rrf_weight`, `bm25_score_boost_weight`, candidate
      multipliers, `candidate_limit_max`) — only if 854 is still idle; check before starting.
- [ ] `efSearch ∈ {64, 100, 200}` (query-time, no reindex). `efConstruction` / `M` are fingerprint
      inputs → recommend to lane D only, do not change.
- [ ] `title_boost ∈ {1, 2, 3, 5}`; reranker `top_k ∈ {20, 40}`; chunk-tier `top_k ∈ {10, 20}`
      against the register's latency budget. **Caveat from F-055: `JUSTSEARCH_RERANK_TOP_K` is not
      a pure window knob** — it also moves the worker `searchLimit` for every mode.
- [ ] One results table, updated baselines/scorecard, and tempdoc 235's "No sweep" items closed
      explicitly in the register.

---

## §B Pre-implementation pass

The lane brief was written before wave 1 merged. Every `file:line` in it is treated here as a
hypothesis and checked against this worktree (base `39d38f73`). Verdicts: **verified** (line and
content match), **moved** (content correct, line stale), **wrong** (content is not what the brief
says), **superseded** (the shape the brief describes was deliberately replaced).

**Counts over the 13 checkable claims (B.1-B.13; B.14 is a re-check of wave-1 facts, not a brief
claim): 4 verified · 6 moved · 2 wrong · 2 superseded.** One claim (B.6) is counted once as
**wrong**, because although its line number merely moved, its substantive assertion — that the
k1/b lever is dead — is false; a claim whose line is right and whose content is wrong is a wrong
claim, not a moved one. B.12 is counted as **moved** and separately carries two corrections that do
not change its verdict.

### B.1 `ChunkSplitter.java:91-122` constants — **verified**

`modules/indexing/src/main/java/io/justsearch/indexing/chunking/ChunkSplitter.java:92,95,98,112`:

```java
  public static final int DEFAULT_CHUNK_TOKENS = 500;
  public static final int DEFAULT_OVERLAP_TOKENS = 50;
  public static final int MIN_CHUNK_TOKENS = 100;
  private static final double LATIN_CHARS_PER_TOKEN = 5.0 / 1.3;
```

All four values match. `5.0 / 1.3 = 3.846` is the brief's "3.85 chars/token". The class javadoc
still carries the original rationale verbatim (`:20-22`): *"Why chunk? Large documents exceed LLM
context limits."* — i.e. the justification the audit says was written for a different job is still
the only one in the file. Sentence/paragraph patterns the brief says already exist do exist
(`:101,104`), so sentence alignment in Part 1 is a use of existing machinery, not new code.

### B.2 `ChunkDocumentWriter.java:28-30` `CHUNK_THRESHOLD_CHARS = 2000` — **verified**

`modules/worker-services/src/main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java:28-30`:

```java
  public static final int CHUNK_THRESHOLD_CHARS = 2000;
  public static final int CHUNK_TARGET_TOKENS = ChunkSplitter.DEFAULT_CHUNK_TOKENS;
  public static final int CHUNK_OVERLAP_TOKENS = ChunkSplitter.DEFAULT_OVERLAP_TOKENS;
```

Useful for Part 1: the writer *aliases* the splitter constants rather than duplicating them, and the
split call site (`:116`) forwards them, so one edit moves both. But `CHUNK_THRESHOLD_CHARS` is
referenced in 12 further files, and two of them keep private copies of the literal
(`modules/system-tests/.../corpus/PassageRetrievalVectorGenerator.java`,
`.../PassageRetrievalIntegrationTest.java`, both `= 2000`). Part 1's PR must sweep those or they
become false authority.

### B.3 `OnnxEmbeddingEncoder.chunkOverlap` hardcoded 128 at `:114` — **verified**

`modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java:113-114`:

```java
    this.chunkSize = Math.min(512, maxSeqLen);
    this.chunkOverlap = 128;
```

Truly hardcoded: a repo-wide grep for `chunkOverlap` / `chunk_overlap` / `CHUNK_OVERLAP` finds only
this field, its stride use at `:944` (`Math.max(1, chunkSize - chunkOverlap)`), and test-local
mirrors. No config key, no setter. Confirms the brief's "nothing reconciles it to the 50-token
splitter overlap" — and note the two overlaps are not even in the same unit domain: this one is a
model-token window stride inside the encoder, the splitter's is an estimated-token overlap between
*stored* chunks. Part 1 must say which of "reconcile" or "document why they differ" applies; on this
reading it is the latter, and the item is a comment plus a named invariant, not a number change.

### B.4 `ResolvedConfigBuilder.java:1609-1615` — the seven never-swept defaults — **moved**

Real location `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java:1696-1702`
(pre-916 numbering; this branch adds two lines above them). All seven **values** are exactly as the
brief states:

```java
        resolveInt("index.hybrid.rrf_k", 60),                                    // :1696
        resolveInt("index.hybrid.vector_skip_min_chars", 4),                     // :1697
        Math.max(1, resolveInt("index.hybrid.candidate_limit_max", 100)),        // :1698
        Math.max(1, resolveInt("index.hybrid.text_candidate_multiplier", 10)),   // :1699
        Math.max(1, resolveInt("index.hybrid.vector_candidate_multiplier", 10)), // :1700
        Math.max(0.0, Math.min(1.0, resolveDouble("index.hybrid.vector_rrf_weight", 0.75))), // :1701
        Math.max(0.0, resolveDouble("index.hybrid.bm25_score_boost_weight", 0.002)),         // :1702
```

Lines 1609-1615 today hold `buildInfraHealth()` (poll interval, NRT stale ms, …) — unrelated.

### B.5 `ResolvedConfigBuilder.java:392-393` / `:1465-1466` for `index.similarity.text.k1/.b` — **moved**

Real: `:413-414` (`putYamlDouble("index.similarity.text.k1"/".b", …)`) and `:1549-1550`
(`resolveNullableDouble("index.similarity.text.k1"/".b")`). Nullable, no in-builder default literal.

### B.6 `ComponentsFactory.java:243-244` "the fallback always wins" — **moved (line) and WRONG (claim)**

Real location `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ComponentsFactory.java:243-246`:

```java
    float k1 =
        idx.similarityTextK1() != null ? idx.similarityTextK1().floatValue() : 0.9f;
    float b = idx.similarityTextB() != null ? idx.similarityTextB().floatValue() : 0.4f;
    cfg.setSimilarity(new org.apache.lucene.search.similarities.BM25Similarity(k1, b));
```

The values 0.9 / 0.4 are right. **The claim that the lever is dead is wrong.** This is an ordinary
null-coalescing fallback, and `index.similarity.text.k1` / `.b` *are* settable today — from
`config.yaml`. They are registered as `ConfigKey.INDEX_SIMILARITY_TEXT_K1` / `_B` (`ConfigKey.java:53-54`),
read at `ResolvedConfigBuilder.java:413-414`, resolved at `:1549-1550`, and the generated ownership
matrix records the precedence as `YAML > default`
(`docs/reference/configuration/runtime-config-ownership-matrix.md:93-94`). What is genuinely absent
is an env var / system property: `EnvRegistry.java` has zero hits for `K1` / `SIMILARITY_TEXT` —
they are YAML-only keys (`ResolvedConfigBuilder.java:1607` comment, tempdoc 347 D1). Nor are they in
`gates/config-surface/dead-config-baseline.txt`, which is consistent: they are read.

**Consequence for Part 4.** The item is not "wire a dead lever". It is "these keys have no env/
sysprop reach, and jseval sets arms by env" — so the sweep either writes a per-arm `config.yaml` or
the keys get `EnvRegistry` entries (a `config-surface` growth of 2, same shape as this PR's). That
is a smaller and differently-shaped change than the brief assumed, and it does not need a `putDefault`
at all: the consumer-side fallback is already the documented default and duplicating it into the
builder would create two authorities for one number. Recorded so Part 4 does not "fix" a non-defect.

### B.7 `title_boost = 3.0`, HNSW `efConstruction`/`M`/`efSearch` — **moved**

- `title_boost` 3.0: `ResolvedConfigBuilder.java:1427` (`resolveDouble("justsearch.search.title_boost", 3.0)`).
- `M = 16`: `ComponentsFactory.java:180`. `efConstruction = 200`: `ComponentsFactory.java:181-182`.
- `efSearch = 100`: `ComponentsFactory.java:273`.

All three HNSW numbers live in the **consumer** as null-coalescing fallbacks, the same pattern as
k1/b — not in the builder. The brief's framing ("`efConstruction` cites tempdoc 37, unlocatable")
is unaffected; the location is.

### B.8 Reranker `20 / 200 ms / 5` at `:1275-1277`, chunk tier `10 / 50 / 150 ms / 3` at `:1303-1306` — **moved**

Real: `ResolvedConfigBuilder.java:1350-1352` (`justsearch.rerank.top_k` 20, `.deadline_ms` 200,
`.min_hits` 5) and `:1378-1381` (`justsearch.rerank.chunks.top_k` 10, `.max_gpu_candidates` 50,
`.deadline_ms` 150, `.min_hits` 3). All seven values match. Note the brief's "50" in the chunk tier
is `max_gpu_candidates`, not a second top_k. Also: F-054 already established that `deadline_ms` is a
CPU-side **pre-check**, not a timeout — Part 4 must not sweep it as if it were one.

### B.9 `ResolvedConfigBuilder.java:1345` — **superseded (stale pointer)**

Line 1345 is `resolveNullableBoolean("justsearch.rerank.enabled")`, the first field of
`buildReranker()`. It is not a tuning number and the brief cites it without saying what it is; the
rerank triple it presumably meant is 5 lines lower (§B.8). No action.

### B.10 `HybridFusionUtils.java:24-27, 851-870, 933-937` — the two `parent_token_count` ramps — **superseded by tempdoc 854 W1**

The brief describes a shape that was deliberately replaced. The two ramps used to read the *same*
static constants, which was itself the F-036 §K wrong-gate defect: tuning the SPLADE fade silently
retuned the chunk-branch ramp. 854 W1 split them. Current shape:

```java
  private static final long SPLADE_FULL_WEIGHT_MAX_TOKENS =              // :24-25
      Long.getLong("justsearch.splade.full_weight_max_tokens", 1024L);
  private static final long SPLADE_ZERO_WEIGHT_MIN_TOKENS =              // :26-27
      Long.getLong("justsearch.splade.zero_weight_min_tokens", 4096L);
  private static final long BRANCH_RAMP_FULL_WEIGHT_MAX_TOKENS_DEFAULT = 1024L;  // :38
  private static final long BRANCH_RAMP_ZERO_WEIGHT_MIN_TOKENS_DEFAULT = 4096L;  // :40
```

Ramp implementations: `spladeParentLengthMultiplier` at `:866-870` (still on the SPLADE statics),
`chunkBranchParentLengthMultiplier` overloads at `:877-937` (own bounds, populated from resolved
config at the production call site, `SearchExecutor.java:797-801`). Constants at the top now span
`:24-40`, not `:24-27`. **Part 3's "re-evaluate the ramps" item must be written against the split
shape**, and the ramp whose reason small-to-big might remove is the branch one, which 854 owns.

### B.11 The "845/881 question sets" — **wrong (they do not exist)**

No dataset artefact exists for either. `find scripts/jseval docs -iname "*845*" -o -iname "*881*"`
returns only the two tempdoc `.md` files; `docs/tempdocs/845-rag-budget-and-prompt-scope.md` has zero
`.json`/`.jsonl` references and its arms are inline prose probes (A1/A2/A3, e.g. `:233` *"can you
see my files?"*) against a live corpus of JustSearch's own docs (`:320`, `docs/explanation`). Lane A's
tempdoc says so outright: *"the 845 procedure, spelled out since no harness survives"*
(`883-decision-review-lane-a-config-and-context-budget.md:240-241`). 881 has no question set either.

**Consequence for Part 1 and Part 3.** Both briefs ask for "RAG answer quality on the 845/881
question sets". There is nothing to run. §C names the replacement instrument. This is not a licence
to drop the RAG-quality item — it is a licence to build the fixture once, in Part 1, and reuse it in
Part 3.

### B.12 `SearchExecutor.collapseChunkHitsToParents:1030-1052`, "first-wins and breaks at `limit`" — **moved, and partially superseded**

Real: `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java:1030-1052`
(pre-916) — the line numbers are exactly right. The mechanism is as described:

```java
      if (bestPerParent.size() >= limit) {
        break;
      }
```

Two corrections that matter for the fix:

1. **`limit` here is not the request limit.** The only call site (`:968`) passes `collapseLimit`,
   which tempdoc 774 Stage 1 already made a lever: `Math.max(limit, limit * chunkCollapseLimitMultiplier())`,
   default 2 (`:678`). So the cut is at 2×limit parents, not limit.
2. **Over-fetching the chunk *hits* is already done.** `candidateBudget = limit * CHUNK_INITIAL_CANDIDATE_MULTIPLIER`
   with the multiplier `= 10` (`:63`, `:687`), plus a saturation retry at `CHUNK_RETRY_MULTIPLIER = 2`
   (`:64`, `:744`). The brief's "over-fetch chunk hits by a multiple of `limit` (start ×5)" is
   therefore **already satisfied at ×10 for the leg fetch**. The over-fetch that is missing is on the
   *collapse scan*, which stops at 2×limit distinct parents regardless of how many hits were fetched.

The defect is real but its location is one stage later than the brief says: the leg over-fetch feeds
the collapse plenty of candidates; the collapse throws them away. §D reuses both existing levers
rather than forking a third.

Also verified: `mergeCollapsedChunkParentHit` (`:1074-1088`) keeps `winner.score()`, and because
`fuseWithCC3` emits `(score desc, docId asc)` (`:762-767`), first-seen *is* max. So today's collapse
is already "max per parent" — the brief's "first-wins/max" is accurate; what is absent is the rest
of the evidence and the post-aggregation cut.

### B.13 tempdoc 854 status — **verified**

`docs/tempdocs/854-fusion-residue-lane.md:3` is present and reads
`status: "CHARTER (2026-08-19) … not started."` Part 4's fusion sweep is therefore lane E's unless
854 starts first; the 854 W2-fix (pool `top_n` / `limit` coupling guard) is **not** in this lane.

### B.14 Wave-1 facts re-checked

- Commit floor: `BackfillScheduler.CYCLE_BUDGET_MS = 5_000` per 912 §E-live. Cadence numbers in §G
  are **recorded, not acted on** — the fix is 912 open items 8/9, another lane.
- `config-surface` pins live at `gates/config-surface/baseline.txt` (repo-root `gates/`, not
  `governance/`): `yaml_keys 111`, `env_sysprop_pairs 250`, `config_keys 56` at base. Growth is
  declared by a `classification: declared-growth` changeset under `gates/config-surface/.changesets/`
  **with the baseline advanced in the same commit** (910 item 9).
- `EnvRegistry` ordinal 400 = environment variable, in the precedence sysprop 500 > env 400 >
  YAML 200 > code default 100 (`EnvRegistry.java:24-28`).

---

## §C Part 1 campaign plan (design only — NOT executed)

### C.1 How the constants become sweepable without shipping a user-facing knob

Three options were considered against the `config-surface` cost and against the fact that chunk size
is a **fingerprint input** (every arm is a full reindex, so the knob must be readable by the Worker
at index time, not just at query time).

| Option | Mechanism | `config-surface` cost | Verdict |
| :--- | :--- | ---: | :--- |
| A. `EnvRegistry` keys at ordinal 400 | `index.chunk.target_tokens` / `.overlap_tokens` / `.threshold_chars` as ordinary resolved config | +3 `env_sysprop_pairs`, +3 `yaml_keys` | **Recommended** |
| B. Test-only system property | `Long.getLong("justsearch.chunk.…")` read directly in `ChunkSplitter` | 0 declared, but fails `checkNoDirectJustsearchSysProp` outside `io.justsearch.configuration` | Rejected — the build gate forbids it, and routing around a gate to avoid declaring a key is the evasion the gate exists for |
| C. Build-time constant override | Recompile per arm | 0 | Rejected — a recompile between arms is a confounder (885 item 19's argument), and 12 arms × a full build is slower than a restart |

**Recommendation: A, with the same "measured or removed" commitment this PR's changeset makes.**
The keys are the instrument; if Part 1 concludes with one chosen triple, the PR that changes the
constants also **deletes the keys**, leaving the constants as constants. That keeps the permanent
config surface at +0 while making the campaign a restart rather than a rebuild. Declared cost during
the campaign: +3/+3; net cost after Part 1 merges: 0.

Chunk size is a fingerprint input, so the keys must reach the Worker through the ordinal-450 config
snapshot, not a raw `EnvRegistry` read in the Worker JVM — the mistake 885 [R1] found. Sentence
alignment is not a new knob: `ChunkSplitter` already has `SENTENCE_END` / `PARAGRAPH_END`
(`:101,104`); the sweep uses them, it does not add them.

### C.2 Arm matrix

`{128, 256, 384, 500} × {0, 25, 50}` = **12 arms**, all sentence-aligned. Overlap 50 on a
128-token chunk is a 39% overlap — kept deliberately, because the 500/50 incumbent is a 10% overlap
and the interesting question is whether small chunks need *proportionally* more overlap or none.
`CHUNK_THRESHOLD_CHARS` is **not** a third sweep axis: at 2000 chars ≈ 520 tokens it interacts with
chunk size only by deciding which documents chunk at all, so it is derived after the fact (set it to
≈ 4× the chosen chunk size in chars, and pin the choice with the short-corpus control).

### C.3 Eval sets

`beir/scifact` (short-corpus control — chunk merge is skipped there, so it detects *collateral*
damage), `mixed/enron-qa`, `mixed/legal-clerc-200` (the two genuinely chunked English corpora),
`mixed/miracl-de-2k`, `mixed/miracl-fr-2k` (multilingual — mandatory under ADR-0043; note the
locale-invariance invariant means these are a *check*, not a tuning target, and no per-language
chunk size may be proposed whatever they show). `mixed/ohr-bench-clean` optional, as a
multi-domain extractive third opinion; it is 962 queries and roughly doubles the campaign, so it is
a stretch goal, not a gate.

Open question the register does not answer (§B research turned up no statement either way): whether
`miracl-de-2k` / `miracl-fr-2k` / `ohr-bench-clean` documents actually exceed
`CHUNK_THRESHOLD_CHARS` often enough to chunk at all. **First action of Part 1** is to measure doc
length distributions per corpus and record them in the register's dataset catalog; a corpus that
never chunks is a control, not an arm, and running 12 arms against it would be machine-days of
measuring nothing.

### C.4 Metrics per arm

nDCG@10 and recall@50 for the chunk leg alone (`--modes` includes the leg modes) and fused;
SPLADE truncation rate (F-033's mechanism — this is the metric most likely to *move* with chunk
size, since 512 tokens is above 128/256/384 and below 500); index size on disk; primary-indexing
docs/s; `leg_union_recall`, `leak_rate`, `chunk_completeness`; CE stage p50.

RAG answer quality: **the 845/881 sets do not exist (§B.11).** Replacement: build one committed
question set of ~60 questions over `mixed/enron-qa` and `mixed/legal-clerc-200` documents with
recorded gold spans, filed under `scripts/jseval/916-corpora/rag-answer-quality-v1/`, and judge with
the AI tier per `docs/explanation/09-testing-strategy.md`. Needs `ai_activate {chatProfile:"standard"}`
— quality-sensitive, so the compact profile does not qualify. Cost: one build pass plus ~12 judged
arms; this is the single largest line item in Part 1 and the owner should confirm it is wanted
before it is built, since it is a new durable fixture rather than a measurement.

### C.5 Wall-clock and total machine time

The only wall-clock on record in 885 is scifact: full pipeline `--pipeline --start-backend --clean`
at **112.6 docs/s primary indexing, 45.9 s for 5168 docs, embedding/SPLADE/chunk stages complete at
231-275 s** (`885:499-505`), i.e. ≈ **280-320 s per scifact arm** as the brief states. 885 measured
no other corpus, so the rest are estimates scaled by document count and length, and should be
treated as such:

| corpus | docs | est. per arm | × 12 arms |
| :--- | ---: | ---: | ---: |
| beir/scifact | 5183 | ~5 min | ~1.0 h |
| mixed/enron-qa | 5485 | ~7 min | ~1.4 h |
| mixed/legal-clerc-200 | 198 (long) | ~6 min | ~1.2 h |
| mixed/miracl-de-2k | 3103 | ~4 min | ~0.8 h |
| mixed/miracl-fr-2k | 5407 | ~7 min | ~1.4 h |
| **subtotal, retrieval only** | | | **≈ 6 h** |
| RAG-quality judged arms (if built) | 60 q × 12 | ~10 min | ~2 h |
| replicates for σ (n=3 on one corpus) | | | ~0.6 h |
| **total** | | | **≈ 8-9 h of machine time** |

Every arm is a **reindex** (chunk size is a fingerprint input), so `--clean` is mandatory and the
same-index trick this PR's Part 2 A/B uses is unavailable. Runs go through the detached
`Start-Process` driver pattern with a `.done` marker (885 §Method, and the 60-minute task-kill in
`agent-lessons.md`), overnight, leaving daytime windows to lanes A/C/D.

### C.6 σ-aware decision rule (to be pre-registered in full before Part 1 runs)

Per `docs/how-to/envelope-staleness-policy.md`, σ comes from the cohort envelope at
`scripts/jseval/tmp/cohort_baselines/<hash>/envelope.json`, and deltas inside ±2σ are noise. The
envelope rotates automatically on cohort-identity change — and **chunk size changes the index, hence
the cohort identity, hence the envelope**. So Part 1 cannot lean on a pre-existing envelope: it must
establish σ from replicates (n≥3 on at least one corpus per chunk size, the F-055 method) and use
that as the noise reference. The shape of the rule: adopt a non-incumbent triple only if it beats
500/50 by > 2σ on **both** chunked English corpora, is within ±2σ on scifact and on both multilingual
corpora, and does not regress indexing throughput by more than 10%; ties go to the incumbent.

### C.7 Handoff to lane D

Lane D needs (a) the chosen `(chunk_tokens, overlap, threshold)` and (b) a **chunker version string**
its commit fingerprint reads, so that an index built by a different chunker is detected as
incompatible rather than silently mixed. Proposal for D to accept or amend: a constant
`ChunkSplitter.CHUNKER_VERSION = "v2-<tokens>-<overlap>-<mode>"` derived from the three constants at
class-init, surfaced through the same path the other fingerprint inputs use. Lane E does not edit
the fingerprint (programme rule 4: "Lane E hands lane D numbers, not diffs") — this is a request, and
it is repeated under Cross-lane requests below.

---

## §D Part 2 — design and implementation log

### D.1 What the defect actually is (after §B.12)

Not "no over-fetch": the chunk legs are already fetched at 10×limit with a saturation retry. The
defect is that `collapseChunkHitsToParents` walks that list and `break`s the moment it has
`collapseLimit` distinct parents, so every remaining hit is discarded **unscanned**. Two losses
follow, and only the first is the one the brief names:

1. A parent whose chunks all rank below the first `collapseLimit` distinct parents never enters the
   result, however many chunks corroborate it.
2. Even for parents that *do* enter, the siblings appearing after the break are never merged, so the
   evidence merge (`chunk_sparse`/`chunk_vector`/`chunk_splade` max, best positive rank) is
   truncated at an arbitrary point determined by how quickly distinct parents happened to arrive.

### D.2 Where the over-fetch multiplier lives — reuse, not fork

`CHUNK_INITIAL_CANDIDATE_MULTIPLIER = 10` and the `CHUNK_RETRY_MULTIPLIER = 2` saturation retry are
left exactly as they are: they govern how many *chunk hits* the legs return, which is not the
starved stage. The new multiplier governs how many *distinct parents* the collapse scans, expressed
as a multiple of the existing 774 collapse cap:

```
scanCap = collapseLimit × chunk_collapse_overfetch_multiplier
        = limit × chunk_collapse_limit_multiplier × chunk_collapse_overfetch_multiplier
```

So at defaults (2 × 1) the scan cap is 2×limit — byte-identical to today — and the ON arm's 2 × 5
scans 10×limit parents, which is exactly the number of hits the legs already fetch. The three levers
compose instead of competing, and no fourth candidate-budget concept enters the file.

### D.3 The aggregation function, stated

For a parent whose chunk scores in fused order are `s₀ ≥ s₁ ≥ s₂ …` (descending is guaranteed —
`fuseWithCC3` emits `(score desc, docId asc)`, `HybridFusionUtils.java:762-767`):

```
aggregate = s₀ + λ · Σ_{i≥1} 0.5^(i-1) · sᵢ
```

Geometric with ratio `0.5`, accumulated incrementally (`restContribution += λ · nextDecay · score;
nextDecay *= 0.5`) so it is O(1) memory per parent and the floating-point addition order is fixed.

The ratio is a constant (`SearchExecutor.CHUNK_COLLAPSE_REST_DECAY = 0.5`), not a third key: λ
already spans the "how much does corroboration count" axis and a second free parameter would double
Part 4's matrix for no separable effect. **Deviation from the brief**, which left the decay
unspecified; recorded here rather than silently chosen.

Because `Σ_{i≥1} 0.5^(i-1) = 2`, the remainder is bounded by `2λ·s₀`, so the aggregate lies in
`[s₀, s₀·(1+2λ)]` — **chunk count alone can never win**. At λ=0.3 the ceiling is 1.6×. This is
asserted, not just claimed (`remainderIsBounded`, 40 chunks at 0.30 stay below a single 0.60).

### D.4 Score scale — why exceeding [0,1] is safe, and where it is not

The chunk branch's scores feed the whole-vs-chunk branch fusion, which **min-max normalizes each
branch independently** before blending (`HybridFusionUtils.fuseWithCCNamed:521-524`,
`minScore`/`scoreRange`/`normalizeScore`). Absolute scale is therefore absorbed; only within-branch
order and relative spacing reach the blend, which is exactly what this changes. The delivered
`SearchHit.score()` is left as the best chunk's score — only the *ordering key* aggregates — so
nothing downstream sees an inflated number.

**A real limit found during implementation, not predicted by the brief (§E.1).** The same
normalization means `fuseWithCC3` maps the *worst* candidate in the chunk pool to exactly `0.0`. A
parent whose every chunk sits at that floor aggregates to `0 + λ·0 = 0` and no λ can lift it. So the
lever reaches parents in the middle of the score distribution, not the bottom of it. That is
defensible — a parent at the pool floor has no evidence worth aggregating — but it bounds the
effect size, and it is why the integration fixture needed a score tail to be honest (§E.1).

### D.5 Determinism and tie-breaks — **deviation from the brief, with reason**

The brief asks for "score desc, then parent doc id". Implemented instead: **stable sort by aggregate
score descending**, which resolves ties by first-seen fused order. Reason: first-seen fused order is
already fully deterministic (it is `fuseWithCC3`'s own `(score desc, chunk docId asc)`), and it is
the *only* tie-break under which λ=0 / multiplier=1 reproduces today bit-for-bit. Sorting ties by
*parent* docId would silently reorder equal-scoring parents at λ=0, destroying the control arm — the
one property the whole A/B rests on. Determinism is what the eval gate needs; parent-docId ordering
was a means to it, and a worse one here. Pinned by `tiesKeepFusedOrder` and
`permutationOfEqualScoredSiblings`.

### D.6 Config keys — **deviation from the brief's names, with reason**

Brief suggested `index.chunk.collapse.overfetch_multiplier` / `index.chunk.collapse.aggregation_lambda`.
Shipped: **`index.hybrid.chunk_collapse_overfetch_multiplier`** and
**`index.hybrid.chunk_collapse_aggregation_lambda`** — same namespace and word order as the lever
they sit beside (`index.hybrid.chunk_collapse_limit_multiplier`, 774 Stage 1). A second naming
convention for adjacent knobs is drift.

Threading (the 774 template, verbatim): `EnvRegistry.java:1172-1177` → `ResolvedConfigBuilder`
`putYamlInt`/`putYamlDouble` + `putDefault` + `resolveInt`/`resolveDouble` with clamps →
`ResolvedConfig.HybridSearch` record components → `SearchExecutor.mergeChunkResults:688-691` →
`executeChunkBranchFusion` → the collapse. Clamps: multiplier `>= 1`, λ into `[0,1]`.

`governance/execution-surfaces.v1.json` is **not** touched: no new representation of `SearchTrace`
is created. The collapse produces the same `SearchResult` it always did, with the same fields; only
the membership and order change. Checked before writing, per `explore-before-implementing`.

### D.7 Pre-registered ship/park rule — WRITTEN BEFORE THE MEASUREMENT RAN

> **Ship** the ON arm as the default `(5, 0.3)` **only if all three hold:**
> 1. **R@10 improves on BOTH chunked corpora** (`mixed/enron-qa`, `mixed/legal-clerc-200`) by
>    **> 2σ** of the noise reference;
> 2. `beir/scifact` (short-corpus control, chunk merge skipped) is **within ±2σ** — i.e. no
>    collateral damage where the lever should be inert;
> 3. all four ratchets green on the ON arm, `comparable: true`, `ann_proof PASS`, `ce_coverage`
>    clean, `chunk_completeness` not degraded, and CE stage p50 not worse by more than 20%.
>
> **Park** (keys ship, defaults stay `(1, 0.0)`) if any of the three fails. A split result — one
> chunked corpus up, the other down — is a **park**, not a "mixed but promising": F-055 parked on
> exactly that shape and the reason has not changed.
>
> **Noise reference.** No cohort envelope exists for this cohort on this machine (same situation
> F-055 faced). σ is taken as **legal σ ≈ 0.0034** measured at n=3 in the 854 W2 campaign
> (register F-055, "Method"), cross-checked against the relevance-ratchet's 0.02 band. 2σ ≈ 0.007.
> Where a metric's own replicate spread is not available, the 0.02 ratchet band is the fallback and
> the weaker of the two tests governs. If the measured effect lands between 0.007 and 0.02 on a
> metric with no replicates, that is **not** a pass — it is a call for replicates, and absent them
> it parks.
>
> **Why this rule and not "ship if it helps on average".** The lever's whole risk is that
> corroboration-weighting demotes a document with one excellent passage (the integration test shows
> exactly that: `p-spread` passes `p-focused`). An average that hides a per-corpus regression is the
> failure mode this rule exists to catch.

### D.8 Files changed

| File | Change |
| :--- | :--- |
| `modules/worker-services/.../execute/SearchExecutor.java` | `CHUNK_COLLAPSE_REST_DECAY`; `collapseChunkHitsToParents` rewritten (4 args); `CollapsedParent` accumulator; levers read at `:688-691` and threaded through `executeChunkBranchFusion` |
| `modules/configuration/.../EnvRegistry.java` | two enum constants |
| `modules/configuration/.../resolved/ResolvedConfig.java` | two `HybridSearch` record components |
| `modules/configuration/.../resolved/ResolvedConfigBuilder.java` | yaml wiring, defaults, clamped resolution |
| `modules/worker-services/src/test/.../SearchExecutorChunkCollapseAggregationTest.java` | new, 10 tests |
| `modules/worker-services/src/test/.../SearchExecutorChunkCollapseIndexIntegrationTest.java` | new, 3 tests on a real chunked index |
| `modules/worker-services/src/test/.../SearchExecutorChunkBranchLeversTest.java` | existing collapse-cap test updated to the 4-arg call |
| `modules/configuration/src/test/.../ResolvedConfigBuilderTest.java` | 4 new config tests |
| `docs/reference/configuration/environment-variables.md` | two rows |
| `docs/reference/configuration/runtime-config-ownership-matrix.md` | regenerated |
| `gates/config-surface/.changesets/916-chunk-collapse-aggregation-keys.md` | new, `declared-growth` |
| `gates/config-surface/baseline.txt` | `yaml_keys` 111→113, `env_sysprop_pairs` 250→252 |
| `docs/reference/search-quality-register.md` | F-056 |

---

## §E Post-implementation critical pass

### E.1 The normalization floor — found by a failing test, not by review

The first version of the integration fixture had `p-spread` as the lowest-scoring parent in the
pool. The test failed. The cause was **not** the fixture being badly tuned in the ordinary sense:
`fuseWithCC3` min-max normalizes, so the pool minimum is *exactly* `0.0`, and `0 + λ·0 = 0` for
every λ. Measured directly rather than assumed —

```
DIAG p-focused#chunk_0  score=1.000000
DIAG p-fill-a#chunk_0   score=0.927617
DIAG p-spread#chunk_0   score=0.000000   ← the pool floor
```

This is a genuine bound on the lever's reach (recorded in §D.4) and it invalidates a naive reading of
the brief, which assumed the collapse operates on raw comparable scores. The fixture was corrected by
adding a realistic tail of weak parents — real corpora always have one — after which `p-spread`
scores 0.7966 and the mechanism works. The test now carries a comment saying *why* the tail must
exist, so a future reader cannot delete it as noise.

**This is also the honest answer to "was the pre-implementation pass sufficient?"** — no. §B verified
every line the brief cited, but the score *scale* was not something the brief cited, so nothing
prompted a check. A test caught it. Recorded as evidence for `audit-without-test`.

### E.2 Wrong-gate check

The risk: `chunkCollapseOverfetchMultiplier()` exists as a symbol but never reaches the collapse.
Checked three ways rather than trusting the symbol: (a) grepped the set-site chain and read
`SearchExecutor.java:688-691, 713-714, 750-751, 920-921, 988` — the levers are read in
`mergeChunkResults` and passed at **both** `executeChunkBranchFusion` call sites, including the
saturation-retry one (a lever applied on the first call but not the retry would be exactly this
defect class); (b) `configReachesTheCollapseParameters` asserts the ordinal-400 resolution onto the
accessors; (c) the live A/B is the end-to-end proof — if the ON arm had produced numbers identical
to OFF, that would have been the wrong-gate signature, and §G shows it did not.

### E.3 Test precision — right reason vs wrong reason

`defaultsReproducePre916` asserts against a **reimplementation of the pre-916 loop as an oracle**,
across limits 1..5, not against hand-copied expected lists. A hand-written expectation would pass if
both the code and the expectation were wrong in the same way; the oracle cannot. The integration
test additionally pins its own precondition (`fixturePrecondition`) so that "ON recovered the parent"
cannot pass trivially because OFF happened to include it already.

### E.4 Asymmetries and residue

No `start()`/`stop()` asymmetry (pure function). No new suppression, no widened catch. The 2-arg
`collapseChunkHitsToParents` overload was **not** kept as a compatibility shim — the one existing
caller and the one existing test were updated to pass the explicit control values, which also makes
the control arm visible at the call site. No retiree to sweep: nothing was replaced by name, the
method's body changed.

### E.5 Post-campaign additions to the critical pass

- **The saturation retry still behaves.** `mergeChunkResults` retries when
  `parentResult().hits().size() < limit && anyLegSaturated()`. With over-fetch the scan finds *more*
  distinct parents, so the output size is unchanged or larger, and the retry fires no more often than
  before. No regression; slightly fewer retries at ON. Checked because a wider scan changing a
  retry predicate is exactly the kind of second-order effect a diff review misses.
- **The ON arm merges more siblings.** At `overfetch > 1` a parent absorbs chunks the pre-916 loop
  never reached, so its `fields` / `debugScores` (max evidence score, best positive rank) are richer.
  That is §D.1's loss #2 being fixed, and it is ON-only — at defaults the merge set is identical.
- **Integer overflow on `scanCap`** would need `collapseLimit × multiplier > 2³¹`; `collapseLimit` is
  bounded by `candidate_limit_max` (100) × the collapse multiplier, so this is unreachable in
  practice. Left unguarded rather than adding a check with no reachable trigger.
- **I extrapolated a rate linearly across a phase change and was wrong.** Mid-campaign I measured
  chunk-embedding backfill at 102/min and projected 2.8 h remaining for enron. It finished in ~25
  min: the rate accelerated sharply once doc-embedding and SPLADE freed the GPU. The measurement was
  correct, the extrapolation was not — a straight line through one phase of a multi-stage pipeline.
  Recorded because the near-miss decision it fed was "abandon the corpus", which would have been
  wrong.

### E.6 Remaining known gap

The integration test drives the production chunk leg and the production CC fusion, then calls the
collapse directly, because `mergeChunkResults` is private and reaching it needs a `PipelineConfig`
protobuf and a full `SearchApplyInputs`. The seam between "config resolved" and "collapse called
with those values" is covered by (b) and (c) in §E.2 rather than by a single test. Making
`mergeChunkResults` package-private purely for a test was judged the worse trade; the live A/B is the
stronger evidence anyway.

---

## §F Falsification record

Every new test was broken once with a targeted mutation, run in isolation, observed failing, and
restored. Drivers: `tmp/falsify-916.py`, `tmp/falsify-916b.py` (gitignored; they restore the file in
a `finally` block). **17 of 17 failed as expected; 0 did-not-fail.**

| test | mutation applied | verdict | observed |
| :--- | :--- | :--- | :--- |
| `defaultsReproducePre916` | expected top score 0.90f → 0.91f | FAILED-AS-EXPECTED | `AssertionFailedError at …AggregationTest.java:87` |
| `overfetchWithoutLambdaIsOrderPreserving` | expected order p-mid/p-spread swapped | FAILED-AS-EXPECTED | `…:97` |
| `spreadEvidenceOvertakesAtLambda` | expected order reverted to max-only | FAILED-AS-EXPECTED | `…:107` |
| `cutAfterAggregation` | ON expectation set to the OFF result | FAILED-AS-EXPECTED | `…:121` |
| `remainderIsBounded` | expected order reversed | FAILED-AS-EXPECTED | `…:136` |
| `permutationOfEqualScoredSiblings` | compared against a wrong literal order | FAILED-AS-EXPECTED | `…:149` |
| `deterministicAcrossInvocations` | first call switched to (1, 0.0) | FAILED-AS-EXPECTED | `…:160` |
| `tiesKeepFusedOrder` | expected docId order instead of fused order | FAILED-AS-EXPECTED | `…:172` |
| `parentlessHitIsItsOwnParent` | expected order reversed | FAILED-AS-EXPECTED | `…:182` |
| `overfetchClampedToOne` | comparison arm changed to (5, 0.9) | FAILED-AS-EXPECTED | `…:191` |
| `configReachesTheCollapseParameters` | expected multiplier 5 → 1 | FAILED-AS-EXPECTED | `…IndexIntegrationTest.java:156` |
| `fixturePrecondition` | `>= COLLAPSE_LIMIT` → `<` | FAILED-AS-EXPECTED | `…:169` |
| `aggregationRecoversTheSpreadParent` | ON expectation set to the OFF list | FAILED-AS-EXPECTED | `…:192` |
| `chunkCollapseAggregationDefaults` | expected default multiplier 1 → 5 | FAILED-AS-EXPECTED | `ResolvedConfigBuilderTest.java:967` |
| `chunkCollapseAggregationFromYaml` | expected YAML-resolved 5 → 1 | FAILED-AS-EXPECTED | `…:984` |
| `chunkCollapseAggregationClamped` | expected clamp 1.0 → 4.5 (unclamped) | FAILED-AS-EXPECTED | `…:1002` |
| `chunkCollapseAggregationEnvRegistryKeys` | config key string corrupted | FAILED-AS-EXPECTED | `…:1008` |

---

## §G Measurements

### G.1 Protocol as executed

Per corpus, one index, three sequential jseval runs: a `--clean --pipeline` **build** arm (its own
query pass discarded — a pass taken straight after ingest is not comparable to one after a cold
restart), then **OFF** and **ON** as `--skip-ingest --start-backend` restarts. `--modes
lexical,vector,splade,hybrid` throughout, so `staged_recall_accounting` and the union/leak
projections populate. Driver `tmp/916-ab-driver.py` (detached `Start-Process`, `.done` marker,
per-arm machine signatures), extractor `tmp/916-extract.py`.

**Machine signature — the honest version.** A game client (League of Legends) was launched by the
machine's owner during the enron **build** arm and closed before it finished. Signatures per arm
(`tmp/916-ab/machine-signatures.jsonl`):

| arm | GPU | games |
| :-- | :-- | :-- |
| enron pre | 2730 MiB, 46 % | LeagueClient + Riot |
| enron post-build / post-off / post-on | 936 / 952 / 959 MiB, 4 / 3 / 6 % | **none** |
| legal pre → post-on | 949-959 MiB, 5-8 % | **none** |
| scifact pre → post-on | 871-949 MiB, 3-5 % | **none** |

So **all six measured arms ran on a quiet machine**; the contamination is confined to one index
build, whose only output is the index both arms then share. Its cost is visible and is reported,
not hidden: enron `primary_docs_s` **56.8** against 885's 112.6 baseline and `ingest_docs_s` **3.6**
against 18.2. No throughput number from this campaign is comparable to 885's, and none is used.

### G.2 The first legal pair was void, and the guard is what said so

| corpus | arm | `ce_coverage` | detail |
| :-- | :-- | :-- | :-- |
| legal-clerc-200 | OFF | **degraded-ce** | CE applied to 186/200, **13 silent `DEADLINE_EXCEEDED` drops**, silent-drop rate 0.0650 > 0.02 tolerance |
| legal-clerc-200 | ON | ok | CE applied to 198/200, 1 silent drop, rate 0.0050 |

The harness states the consequence itself: *"these queries were delivered in pure fusion order with
no deterministic reason, so this run's ranking is a blend of two pipelines and its metrics are not
comparable"*. The first legal delta (−0.0132 nDCG@10) was therefore **not a measurement of this
change** — it compared a degraded OFF against a clean ON. It is reported here because discarding it
silently would be the more dangerous habit. Legal was rebuilt and re-run with 3 replicates per arm
(`tmp/916-legal/`, driver `tmp/916-legal-replicates.py`).

### G.3 Replicate spread — the pipeline is deterministic

| arm | nDCG@10 (n=3) | R@10 | P@1 | leak | guards |
| :-- | :-- | :-- | :-- | :-- | :-- |
| legal OFF | 0.5816, 0.5816, 0.5816 — **sd 0.00000** | 0.8100 ×3 | 0.3600 ×3 | 0.1300 ×3 | 3/3 `ok`, comparable |
| legal ON (λ=0.3) | 0.5826, *0.5888*, 0.5826 | 0.815, *0.810*, 0.815 | 0.3600, *0.3700*, 0.3600 | 0.1250, *0.1300*, 0.1250 | 2/3 `ok`; *replicate 1 `degraded-ce`* |

**σ on this machine, at fixed configuration, is 0.** Three identical OFF runs produced bit-identical
metrics. The only variance observed anywhere in the campaign came from cross-encoder deadline drops,
which `ce_coverage` flags and excludes. This **supersedes the σ ≈ 0.0034 borrowed from F-055 in
§D.7** — that figure was measured on a contended machine and is an upper bound, not this cohort's
noise. Consequence for the rule: a "> 2σ" test degenerates when σ = 0, so the deltas below are
**real, not noise** — they are simply very small, and far inside the relevance ratchet's 0.02 band.
Reading σ=0 as "every nonzero delta is significant, therefore ship" would be exactly the
opportunistic re-reading the pre-registration exists to prevent.

### G.4 The A/B table (mode `hybrid`; OFF = shipped defaults 1 / 0.0, ON = 5 / 0.3)

| corpus | arm | run id | nDCG@10 | P@1 | R@10 | union | leak | CE p50 | comparable | ce_cov | chunk_compl |
| :-- | :-- | :-- | --: | --: | --: | --: | --: | --: | :-- | :-- | :-- |
| enron-qa | OFF | `20260903T003601_mixed_enron-qa` | 0.8034 | 0.6733 | 0.9200 | 0.9633 | 0.0500 | 151 ms | True | ok | ok |
| enron-qa | ON | `20260903T003814_mixed_enron-qa` | 0.7981 | 0.6700 | 0.9133 | 0.9633 | 0.0533 | 153 ms | True | ok | ok |
| legal-clerc-200 | OFF | `tmp/916-legal/off-{0,1,2}` | 0.5816 | 0.3600 | 0.8100 | 0.9250 | 0.1300 | — | True | ok ×3 | ok |
| legal-clerc-200 | ON | `tmp/916-legal/on-{0,2}` (clean) | 0.5826 | 0.3600 | 0.8150 | 0.9250 | 0.1250 | — | True | ok | ok |
| scifact (control) | OFF | `20260903T005443_scifact` | 0.7591 | 0.6300 | 0.8942 | 0.9333 | 0.0267 | 148 ms | True | ok | chunk-free |
| scifact (control) | ON | `20260903T005634_scifact` | 0.7591 | 0.6300 | 0.8942 | 0.9333 | 0.0267 | 147 ms | True | ok | chunk-free |

Deltas (ON − OFF):

| corpus | Δ nDCG@10 | Δ P@1 | Δ R@10 | Δ union | Δ leak |
| :-- | --: | --: | --: | --: | --: |
| **enron-qa** | −0.0053 | −0.0033 | **−0.0067** | 0.0000 | +0.0033 |
| **legal-clerc-200** | +0.0010 | 0.0000 | **+0.0050** | 0.0000 | −0.0050 |
| scifact (control) | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |

Three facts worth more than the deltas themselves:

1. **`leg_union_recall` is unchanged on every corpus** (0.9633 / 0.9250 / 0.9333). The retrieval legs
   are byte-identical across arms; only the collapse moved. That is the causal isolation the
   experiment needed, and it is what makes these deltas attributable.
2. **scifact is bit-identical across arms** — every metric to 4 dp. scifact is `chunk-free` (short
   corpus, chunk merge skipped), so the lever is *provably inert exactly where it should be*. This is
   both the negative control passing and a second, independent refutation of a wrong-gate mistake.
3. **The numbers moved at all on the chunked corpora**, and `summary.json.env_overrides` records
   `{OVERFETCH_MULTIPLIER: "5", AGGREGATION_LAMBDA: "0.3"}` on every ON arm. Together with §E.2 this
   closes the wrong-gate question empirically: the lever reaches the Worker.

### G.5 λ response on legal (supporting evidence; does NOT change the verdict)

| λ | nDCG@10 | R@10 | leak | `ce_coverage` | usable |
| --: | --: | --: | --: | :-- | :-- |
| 0.0 (OFF) | 0.5816 | 0.8100 | 0.1300 | ok | yes |
| 0.1 | 0.5918 | 0.8250 | 0.1150 | **degraded-ce** | **no** |
| 0.2 | 0.5827 | 0.8150 | 0.1250 | ok | yes |
| 0.3 | 0.5826 | 0.8150 | 0.1250 | ok | yes |

λ=0.1 is the most attractive row in this whole tempdoc and it is **discarded**: its arm carries the
same degraded-CE defect that voided the first legal pair. Accepting it because it says what the
change would like it to say is precisely the failure the guard exists to prevent, and it is recorded
here so the next agent re-runs it rather than citing it. On the two usable rows the response is flat
between 0.2 and 0.3.

### G.6 Verdict against the pre-registered rule (§D.7)

| criterion | result |
| :-- | :-- |
| 1. R@10 improves > 2σ on **both** chunked corpora | **FAIL** — legal +0.0050, enron **−0.0067** |
| 2. scifact within ±2σ | **PASS** — exactly 0.0000 on every metric |
| 3. ratchets + guards green on ON | **PASS** for the applicable ones (§G.7) |

**PARK.** The rule's split-result clause is explicit and was written before any number existed:
*"A split result — one chunked corpus up, the other down — is a park, not a 'mixed but promising':
F-055 parked on exactly that shape and the reason has not changed."* That is this result exactly.
Shipped defaults stay `(1, 0.0)`, which is the pre-916 behaviour bit-for-bit.

The mechanism is not refuted — legal gains recall *and* drops leak, scifact is provably inert, and
the effect is causally isolated. What is refuted is **λ=0.3 as a shipped default**, on one point of a
one-dimensional axis whose starting value came from the brief rather than from evidence.

### G.7 Ratchets

Run against the ON arms (`python -m jseval <gate> --dataset <ds> --run-dir <arm>`):

| gate | enron ON | scifact ON |
| :-- | :-- | :-- |
| `relevance-gate` (`ndcg10-no-regression`) | **ok** | **ok** |
| `leak-gate` (`leak-rate-no-regression`) | **ok** | **ok** |
| `union-recall-gate` (`union-recall-no-regression`) | **ok** | **ok** |
| `perf-gate` | **not applicable** — see below | not applicable |

`perf-gate` is **not evaluable on this A/B's arms, and not because of this change**: the OFF/ON arms
are `--skip-ingest` query-only runs, so `primary_docs_s` and `enrich_docs_s` are literally absent
(`None` in both arms' `summary.json`, verified) and their checks cannot pass. The two checks that
*are* meaningful for a query-path change both pass on the ON arm: **`ce_p50_ms: ok`,
`retrieval_p50_ms: ok`**. The OFF arm additionally exits 2 on an engine-set mismatch against the perf
baseline (`realized ['reranker']` vs baseline `['dense','reranker','splade']`) — a property of
query-only arms, present in the control as well, i.e. not attributable here. Claiming "perf-gate
green" would have been false; claiming a regression would also have been false.

### G.8 Cadence (RECORDED, NOT ACTED ON)

`commit_total` / `reopen_total`: enron 261 / 1132 (identical in both arms), legal 42 / 202
(identical), scifact 56 / 215 (identical). Identical across arms, as expected for a query-only
difference. Per 912 §E-live the floor is `BackfillScheduler.CYCLE_BUDGET_MS = 5_000`; the fix is 912
open items 8/9 and is **not** this lane's.

---

## §H Register updates made

- **`F-056`** added to `docs/reference/search-quality-register.md` — the Part 2 result: the
  aggregate-then-cut collapse is PARKED at λ=0.3 by the pre-registered rule on a split
  (legal +0.0050 R@10 / −0.0050 leak, enron −0.0067 R@10), with the two structural findings that
  outlast the verdict (the CC normalization floor; σ=0 on clean arms).
- **No baseline row changed.** The verdict is PARK, so every shipped default is unchanged and no
  canonical number moved. This is the register's own rule working as intended: numbers change only
  with a run id and a σ statement, and here the σ statement says do not change them.
- Skill mirror regenerated with `node scripts/docs/skills-sync.mjs`.

---

## Cross-lane requests

- **Lane D (index identity + migration).** (1) Part 1 will hand you a chosen
  `(chunk_tokens, overlap, threshold)`; it is not ready yet and Part 2 does not block on it.
  (2) Please accept or amend a **chunker version string** as a fingerprint input —
  proposal `ChunkSplitter.CHUNKER_VERSION = "v2-<tokens>-<overlap>-<mode>"`, derived from the three
  constants so it cannot drift from them. Lane E will not edit the fingerprint (programme rule 4).
  (3) Part 2 changes **no** index shape and needs no rebuild — it is query-time only, so it can merge
  in either order relative to D.
- **Tempdoc 854 (fusion residue, CHARTER).** Part 4's fusion-number sweep is lane E's only if 854 is
  still idle when Part 1 finishes; lane E will re-check status first. The 854 W2-fix (pool `top_n` /
  `limit` splice coupling guard) is **not** taken here. Note for 854: §D.4 records that the branch
  fusion min-max normalizes each branch, so this PR's aggregate cannot leak scale into the blend.
- **Owner.** Two decisions requested: (1) §C.4 proposes building a new committed RAG question-set
  fixture because the 845/881 sets do not exist (§B.11) — confirm before ~2 h of machine time plus a
  durable artefact is spent. (2) §C.1 recommends temporary `EnvRegistry` keys for the chunk-size
  campaign, deleted in the PR that lands the chosen constants; confirm the temporary +3
  `config-surface` growth is acceptable.

## Open items

1. **Part 1, Part 3, Part 4 are not executed.** Part 3 is blocked on lane A; Parts 1 and 4 are
   machine-time, planned in §C.
2. **Doc-length distributions per corpus are unmeasured** (§C.3). First action of Part 1.
3. **The `OnnxEmbeddingEncoder.chunkOverlap` reconciliation** (§B.3) is a Part 1 item and, on the
   reading recorded there, resolves to documentation plus a named invariant rather than a number
   change — but that reading needs a second opinion before it is acted on.
4. **Two private copies of `CHUNK_THRESHOLD_CHARS = 2000`** in `modules/system-tests` (§B.2) must be
   swept by Part 1's PR or they become false authority.
5. **Commit/reopen cadence numbers recorded in §G are not acted on** — the floor is
   `BackfillScheduler.CYCLE_BUDGET_MS`, chartered as 912 open items 8/9.
6. **`prepare-worktree.cjs` does not make `datasets/` available in a worktree** (lane 0's file, so
   not edited here). `jseval` resolves `<repo-root>/datasets/mixed/<name>/corpus.jsonl`, which only
   exists in the main checkout, so every `mixed/*` corpus fails in a worktree. Fix: seed a junction
   the way models and the cuda runtime already are (`node scripts/dev/prepare-worktree.cjs` already
   resolves both from the main checkout — this is the same pattern, one more path).
7. **`JSEVAL_HEALTH_TIMEOUT_SEC` defaults to 120 s**, below a cold worktree backend boot (~150 s
   observed). The first campaign attempt lost three arms to it before the cause was clear. Either
   raise the default or have `backend.py` distinguish "still starting" from "failed".
8. **The λ=0.1 arm is unmeasured, not negative.** It produced the campaign's best numbers and was
   discarded for `degraded-ce`. A clean λ∈{0.05, 0.1, 0.15} sweep on **both** chunked corpora is the
   arm that would settle whether the keys stay or come out (see the changeset's commitment).

## Report-back

- **PRs:** one, open, green-and-ready, **not merged** —
  `feat(916): aggregate-then-cut parent collapse for the chunk branch (lane E part 2)`.
- **Items:** Part 2 **12/12 done**. Parts 1/3/4 **not started** (Part 3 blocked on lane A; Parts 1
  and 4 are machine-time, planned in §C). Three deviations, each with its reason in §D:
  (1) tie-break is first-seen fused order, not parent docId — it is the only tie-break under which
  λ=0 reproduces today bit-for-bit, and the brief's goal was determinism, which it delivers;
  (2) key names use the neighbouring `index.hybrid.chunk_collapse_*` namespace rather than the
  brief's `index.chunk.collapse.*`, so adjacent knobs share one convention;
  (3) the decay ratio is a constant, not a third key.
  One brief premise is **wrong and was not implemented as written**: over-fetching the chunk *hits*
  already existed at ×10 (§B.12); the starved stage was the collapse scan.
- **Evidence:** 17 new tests, every one broken once and observed failing (§F table). Run ids in
  §G.4. Drivers and artefacts under `tmp/916-ab/`, `tmp/916-legal/`, `tmp/916-lambda/` (gitignored;
  §G and F-056 are the durable record).
- **Measurements:** legal-clerc-200 **+0.0010 nDCG / +0.0050 R@10 / −0.0050 leak**; enron-qa
  **−0.0053 / −0.0067 / +0.0033**; scifact control **0.0000 on every metric**. `leg_union_recall`
  unchanged everywhere. **PARK** by the pre-registered split-result clause; no default changed, no
  baseline row moved.
- **Cross-lane requests raised:** lane D — chunker version string + the Part 1 triple (neither ready;
  Part 2 needs nothing from D and changes no index shape); 854 — Part 4's fusion sweep is lane E's
  only if 854 is still idle, and §D.4 records that branch fusion min-max normalizes so this change
  cannot leak scale into the blend; owner — two decisions in §C (build a RAG question-set fixture?
  temporary EnvRegistry keys for the chunk sweep?) plus whether to keep or remove the two parked keys.
- **Residue found outside scope and where it was routed:**
  1. **`prepare-worktree.cjs` does not provide `datasets/`.** Every `mixed/*` corpus lives only in
     the main checkout, so any worktree running jseval fails with
     `FileNotFoundError: corpus.jsonl not found at <worktree>/datasets/mixed/<name>/corpus.jsonl`.
     Worked around here with a gitignored junction. Routed to this tempdoc's open items as item 6
     with the concrete fix, since `prepare-worktree.cjs` is lane 0's file, not lane E's.
  2. **`JSEVAL_HEALTH_TIMEOUT_SEC` defaults to 120 s, which is too short for a cold worktree boot**
     (observed ~150 s; the first campaign lost three arms to it). Open item 7.
  3. Both are the same shape — a worktree-only eval gap — and neither is a defect in the product.
- **What the next lane must know:** (a) **check `ce_coverage` before believing any delta on this
  machine** — it voided two arms here and one of them would have reversed the sign of the headline;
  (b) **σ is 0 on clean arms**, so F-055's 0.0034 is an upper bound, not this cohort's noise;
  (c) the **CC pool floor is exactly 0.0**, which bounds every "aggregate the passage evidence"
  design, not just this one; (d) scifact is `chunk-free` and therefore a genuine inert control for
  any chunk-branch change — use it.
