# Tempdoc 916 evidence — Part 1 chunk-size campaign: design plan (§C) and execution log (§K)

Split from `docs/tempdocs/916-lane-e-search-quality-rederivation.md` (size-cap split, 930 §19.3 F4).

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

**Recommendation: A — owner-approved 2026-09-03, on one condition, which is binding:
the PR that lands the chosen constants DELETES the three keys and re-pins `config-surface` in the
same commit.** Same "measured or removed" shape as this PR's changeset.
The keys are the instrument; if Part 1 concludes with one chosen triple, the PR that changes the
constants also **deletes the keys**, leaving the constants as constants. That keeps the permanent
config surface at +0 while making the campaign a restart rather than a rebuild. Declared cost during
the campaign: +3/+3; net cost after Part 1 merges: 0.

Chunk size is a fingerprint input, so the keys must reach the Worker through the ordinal-450 config
snapshot, not a raw `EnvRegistry` read in the Worker JVM — the mistake 885 [R1] found. Sentence
alignment is not a new knob: `ChunkSplitter` already has `SENTENCE_END` / `PARAGRAPH_END`
(`:101,104`); the sweep uses them, it does not add them.

### C.2 Arm matrix — original threshold wording superseded by §L.6

`{128, 256, 384, 500} × {0, 25, 50}` = **12 arms**, all sentence-aligned. Overlap 50 on a
128-token chunk is a 39% overlap — kept deliberately, because the 500/50 incumbent is a 10% overlap
and the interesting question is whether small chunks need *proportionally* more overlap or none.
`CHUNK_THRESHOLD_CHARS` is **not** a third sweep axis: it was held at 2000 for every arm. The
original instruction to derive it as “≈ 4× the chosen chunk size in chars” is superseded because it
mixed up a token-to-character conversion with a count of chunks. The closed follow-up in §L.6
instead identifies 2000 as
`TokenEstimation.charsForTokens(ChunkSplitter.DEFAULT_CHUNK_TOKENS)`: one 500-token window under
the canonical typical-prose estimate of four characters per token. It is a cheap writer prefilter,
not a promise that one or more chunk documents will be emitted; the content-aware splitter and the
writer's `chunks.size() <= 1` guard make that final decision. This rationale is analytic and the
unchanged value is operationally exercised, but it was not quality-optimized. CJK content can map
500 tokens to a different number of characters inside `ChunkSplitter`; the threshold remains one
locale-invariant global policy, not a per-language tuning lever.

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

RAG answer quality: **the 845/881 sets do not exist (§B.11).**

> **Part 1 step 0 (owner-approved 2026-09-03, and it is the FIRST Part 1 deliverable, not a
> by-product of the sweep):** build one committed question set — ~60 questions over
> `mixed/enron-qa` and `mixed/legal-clerc-200` documents with recorded gold spans — filed under
> `scripts/jseval/916-corpora/rag-answer-quality-v1/` alongside the other `NNN-corpora` artefacts,
> carrying a `recipe.json` (the regenerable-source pattern the register's dataset catalog requires
> of every corpus) and a **`query_gold_sha256`** digest, so the cell signatures are bound the way
> `corpus_certify.py:107,274,292` binds every other committed corpus. Judge tier: the **AI judge**
> per `docs/explanation/09-testing-strategy.md`, which needs `ai_activate {chatProfile:"standard"}`
> — quality-sensitive, so the compact dev-default profile does not qualify.
>
> It is step 0 because every later Part 1 arm reports against it: building it after the sweep would
> mean re-running all 12 arms to get the RAG column. It is **not** built in this PR.

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


## §K Part 1 execution log — preparation window (2026-09-03, NO backend)

Part 1 is a machine-days campaign. This section is the **preparation** it needs, done in a window
with a hard "no dev stack, no eval backend, no LLM" limit: the instrument, the corpus profiling that
decides what the arms even are, the question-set fixture, the driver, and the decision rule — all
committed **before** any number is produced. Nothing here is a measurement of retrieval quality;
every retrieval claim in this section would be a fabrication, and there are none.

### K.1 The instrument — four temporary keys, and where they actually land

`ChunkSplitter` and `ChunkDocumentWriter` are both non-instantiable static utilities
(`ChunkSplitter.java:26` `private ChunkSplitter() {}`, `ChunkDocumentWriter.java:36`), so "consume
the keys at construction" has no construction to hang off. The shape that fits what is actually
there:

- **`io.justsearch.indexing.chunking.ChunkingPolicy`** (new) — a pure value record
  `(targetTokens, overlapTokens, minTokens, thresholdChars)` with `DEFAULT` = today's four numbers.
  It lives in `modules/indexing`, which depends only on `modules:core`, so it carries no config
  dependency.
- **`ChunkSplitter.split(...)` / `splitWithMetadata(...)` policy overloads** — the pre-916 `int`
  overloads now delegate to them with `MIN_CHUNK_TOKENS`, so there is one body, not two.
- **`ResolvedConfig.Index`** gains four **nullable** `Integer` fields plus
  `effectiveChunkTargetTokens()` / `...OverlapTokens()` / `...MinTokens()` / `...ThresholdChars()`,
  mirroring the `effectiveVectorHnswM()` pattern already in that record. Nullable is load-bearing:
  unset must be *indistinguishable* from today, so none of the four declares a `putDefault`.
- **`ChunkDocumentWriter.activePolicy()`** resolves the policy from `ConfigStore.globalOrNull()` —
  the established accessor for a static utility that needs resolved config
  (`CommitOps.java:348-352`, `TextQueryOps.java:209`, and lane D's own
  `SsotCommitMetadataSource.java:203` all use it). Not a raw `System.getProperty`, which would fail
  `checkNoDirectJustsearchSysProp` **and** would be the 885 [R1] defect shape.

**How they reach the Worker, verified rather than assumed.** Chunking runs in the Worker. The
chain is: `ResolvedConfigBuilder.buildIndex()` resolves the four on the **Head** →
`ResolvedConfig.toWorkerSnapshot()` writes every non-null resolution
(`ResolvedConfig.java:118-124`) → `HeadlessApp.java:661-663` writes it and sets
`justsearch.worker.config_snapshot` → `ResolvedConfigBuilder.loadWorkerSnapshotFromSysprop()`
contributes it at ordinal 450 → `IndexerWorker.java:76` publishes it as the global `ConfigStore` →
`activePolicy()` reads it. `ChunkSizeSweepKeysTest.armValuesCrossTheWorkerBoundary` asserts that
crossing end-to-end, including that an **unset** key is not materialized into the snapshot.

**Publication model — owner decision 2026-09-03, and it changes what "deletion" means here.**
This branch (**PR #622**) is a **DRAFT campaign branch and is not intended to merge**. The final
Part 1 PR carries only the *chosen* constants, the chunker version string lane D asked for, the
fixture, the driver and the register/scorecard updates. Consequence: **the four temporary keys never
reach `main` at all**, so there is nothing on `main` to delete afterwards and the `config-surface`
baseline on `main` is never moved. The changeset and the baseline advance in this commit exist so
the campaign branch is self-consistent and auditable — the gate is satisfied *here*, not deferred —
but the permanent config surface is untouched by construction rather than by a promise. That is
strictly stronger than 916 Part 2's "authorised on condition of deletion" shape, because it removes
the window in which a parked key could be forgotten.

---

### K.2 The wrong-gate check (§E.2's missing leg: what does the NEXT stage read?)

§E.2's lesson is that tracing *inputs into* a computation is not enough — the question is what the
next stage consumes. Applied here, by reading the consumers rather than trusting the symbol:

| consumer | reads chunk boundaries how? | verdict |
| :--- | :--- | :--- |
| chunk documents (`chunk_index`, `chunk_start_char`, `chunk_end_char`, `chunk_total`) | written from the `ChunkSplitter.Chunk` list the arm's policy produced, `ChunkDocumentWriter.java:151,170-176` | **carries the arm** |
| chunk vectors (BGE-M3 backfill) | reads `SchemaFields.CHUNK_CONTENT` off the index, `BgeM3BackfillOps.java:101` | **carries the arm** — no re-split |
| chunk SPLADE | reads `CHUNK_CONTENT` off the index, `CombinedEnrichmentBackfillOps.java:458,537,577` | **carries the arm** — no re-split |
| `parent_token_count` | SPLADE token count of the **whole parent**, or `chars/3`, `IndexingDocumentOps.java:427-451` | **independent of chunk boundaries** — see the coupling below |
| indexing-loop threshold pre-check | `IndexingDocumentOps.java:405` — **was** a private copy of the constant | **FIXED**: now `ChunkDocumentWriter.activePolicy().thresholdChars()` |
| `chunk_min_chars` published to off-process oracles | `IndexStatusOps.java:747` — **was** the constant | **FIXED**: now the active threshold. jseval's `chunk_completeness` divides the corpus on this number, so an arm publishing 2000 while chunking at another value would have mis-scored every arm silently |
| RAG virtual chunking of a chunkless outlier | `RagContextOps.java:850,854` — **was** `ChunkSplitter.split(content)` at the shipped defaults | **FIXED**: now the active policy. Otherwise one answer's context could mix two granularities |
| RAG virtual chunking, second site | `RagContextOps.java:1055` — same defect, a second call | **FIXED**: found only by re-grepping every `ChunkSplitter.split` call site after the first fix, not by reading the first one |
| Head-side `topK` affordability | `RAGContext.java:851` — `budget.inputBudget() / ChunkSplitter.DEFAULT_CHUNK_TOKENS` | **FIXED**: reads the resolved value via `ConfigStore`. This one runs on the HEAD, so it cannot call `activePolicy()`; under a 128-token arm it would divide by 500 and under-fill the context ~4×, corrupting precisely the RAG-quality secondary this campaign uses that path for |
| NER windowing | `NerService.java:91,164` — `ChunkSplitter.split(content, NER_CHUNK_TOKENS, NER_OVERLAP_TOKENS)` | **independent by design, left alone** — NER windows a document for a sequence model with its own constants; it writes no chunk document and must not follow the sweep |

Five stale-constant consumers were found and fixed, and this is exactly the §E.2 failure re-run:
the symbol was reachable, the value was resolved, and a stage downstream still read a constant.
Two of the five (`RagContextOps.java:1055`, `RAGContext.java:851`) were found only on a **second**
sweep — after fixing the first virtual-chunking site, re-grepping every `ChunkSplitter.split` and
`DEFAULT_CHUNK_TOKENS` reference in production code rather than assuming the first fix was the
class. The Head-side one is the sharpest: it is in a different process from every other row here,
so no amount of tracing the Worker path would have surfaced it.

**`OnnxEmbeddingEncoder.chunkOverlap = 128` is NOT a second document splitter** — §B.3's reading is
confirmed, and this closes §A's "reconcile or document why they differ" item. `createChunks`
(`OnnxEmbeddingEncoder.java:942-976`) is a **model-token sliding window** over the tokenizer ids of
whatever single text it was handed, with `chunkSize = min(512, maxSeqLen)` and stride
`chunkSize - 128` (`:113-114`, `:944`). It exists so a text longer than the encoder's sequence limit
can be mean-pooled instead of truncated. It never sees a document boundary, never writes a chunk
document, and its input is either a stored `CHUNK_CONTENT` (already cut by `ChunkSplitter`) or a
whole parent for the doc-level vector. The two "overlaps" are in different unit domains — model
tokens inside one encode versus estimated tokens between two stored chunks — and reconciling the
numbers would be a category error. **The item resolves to documentation plus a named invariant, and
the invariant is: `ChunkSplitter` decides what a chunk IS; `OnnxEmbeddingEncoder` decides how a
chunk is READ by a fixed-window model. Nothing may make the encoder's stride a function of the
splitter's overlap.** (916 open item 3 asked for a second opinion on this reading before acting;
this is a second reading of the same code reaching the same conclusion, not an independent
reviewer — flagging that honestly.)

**A fallback compatibility bound the sweep must respect (new, found here).**
`IndexingDocumentOps.estimateTokenCount` divides by 3 rather than 4 so that a document at the
shipped 2000-character threshold receives a fallback estimate of about 666 tokens, above the
512-token corpus-profile boundary (`IndexingDocumentOps.java:468-488`, tempdoc 717 review Finding
2). The formerly stated “≥1536-character corpus-profile floor” is too broad: 1536 is only the
arithmetic lower bound at which this **fallback** estimator returns 512. It is not a measured corpus
property, a threshold-quality result, or a guarantee about an exact SPLADE token count. Corpus
routing also depends on aggregate token-data coverage, the median bucket and chunk rate. Holding
the threshold at 2000 during the sweep preserved the fallback's compatibility margin; §L.6 closes
the later threshold rationale without turning 1536 into an optimization constraint.

**The `min_tokens` distortion (the reason there are four keys, not three).** `ChunkSplitter`
advances by `max(chunkLength − overlapChars, minChars)` (`ChunkSplitter.java`, the
`splitWithMetadata` loop). Measured on a 14 k-char Latin fixture, mean delivered overlap in chars
and chunk count:

| target / overlap | `min_tokens=100` (shipped) | `min_tokens=target/5` | delivered overlap |
| :--- | ---: | ---: | :--- |
| 128 / 50 | 133.3 chars, 57 chunks | 190.0 chars, 79 chunks | **70% of what the arm asked for** |
| 128 / 25 | 91.1 chars, 50 chunks | 94.5 chars, 53 chunks | 96% |
| 256 / 50 | 190.0 chars, 27 chunks | 190.0 chars, 27 chunks | inert |
| 384 / 50 | 189.2 chars, 19 chunks | 189.2 chars, 19 chunks | inert |
| 500 / 50 | 189.2 chars, 13 chunks | 189.2 chars, 13 chunks | inert (`500/5 == 100`) |

The floor binds **only at the small-target arms and only at high overlap** — an asymmetric
distortion that would have looked exactly like a real effect of chunk size. `min_tokens = target/5`
removes it and reproduces the incumbent exactly at 500. Pinned by
`ChunkingPolicyTest.minTokensCapsOverlapAtSmallTargets`.

### K.3 Doc-length distributions — which corpora are arms and which are controls

916 §C.3's open question, answered. Measured offline from `corpus.jsonl` (BEIR via the shared
`ir_datasets` cache) with `scripts/jseval/916_doc_length_profile.py`; JSON under
`scripts/jseval/tmp/916-part1/doc-length-profile.json` (gitignored — this table is the durable
record). Token estimates use `ChunkSplitter`'s own rule: 3.846 chars/token unless a document is
CJK-dominant (>50% of non-whitespace chars in a CJK block), in which case 1.0. That keys on the
document's character set, not on a declared language, so it introduces no per-language lever
(invariant 6). Only `ohr-bench-clean` has any CJK-dominant documents: **16 of 1000**.

| corpus | docs | chars p50 | p90 | p99 | max | est. tokens p50 | docs ≥ 2000 chars | share | est. chunks @500/50 | @128/50 |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `beir/scifact` | 5183 | 1426 | 2124 | 3144 | 10127 | 371 | 748 | **14.4%** | 1534 | 6063 |
| `mixed/enron-qa` | 5485 | 2150 | 10073 | 63718 | 89556 | 559 | 2925 | **53.3%** | 17340 | 92368 |
| `mixed/legal-clerc-200` | 198 | 27852 | 70347 | 121342 | 129915 | 7324 | 194 | **98.0%** | 4130 | 23397 |
| `mixed/miracl-de-2k` | 3103 | 387 | 920 | 1746 | 3908 | 101 | 19 | **0.6%** | 39 | 162 |
| `mixed/miracl-fr-2k` | 5407 | 284 | 770 | 1534 | 4225 | 74 | 17 | **0.3%** | 36 | 147 |
| `mixed/ohr-bench-clean` | 1000 | 2536 | 5281 | 10541 | 22313 | 666 | 628 | **62.8%** | 1814 | 8887 |

Chunk counts are **estimates** from the splitter's advance recurrence
(`advance = max(targetChars − overlapChars, minChars)`), not from running the splitter: its
boundary search shifts each cut by up to ±200 chars to land on a sentence or paragraph, which moves
spans but not counts materially. Labelled as estimates wherever they appear.

**Decisions this forces.**

1. **The two multilingual corpora are CONTROLS, not arms.** `miracl-de-2k` chunks 19 documents and
   `miracl-fr-2k` chunks 17. Twelve arms there would be measuring 19 documents' worth of chunking
   at a cost of ~2.2 machine-hours. Under ADR-0043 they are still **mandatory** — but as a
   *no-regression check*, which needs the incumbent and the winning arm, not the whole matrix. That
   is 2 runs each instead of 12: **−20 runs.**
2. **`scifact` is a control, and §J's "scifact is chunk-free" needs a caveat.** 14.4% of scifact
   documents exceed the threshold and would produce ~1534 chunk documents at the incumbent — so it
   is not chunk-*free* at the writer. §J's claim is about the corpus-profile gate (median 371
   estimated tokens is far below the 512 short/long boundary, so `CorpusProfile` classifies scifact
   "short" and the chunk-merge leg is skipped at *search* time). Both statements can be true and
   they are about different stages; recording the tension rather than asserting §J was wrong,
   because confirming the profile gate needs a live run. Either way scifact's role is unchanged:
   the collateral-damage control.
3. **`ohr-bench-clean` is promoted from stretch goal to third arm corpus.** 62.8% above threshold,
   1000 docs, ~1814 chunks at the incumbent — it is genuinely chunked, it is multi-domain
   extractive, and at 1000 documents it is the *cheapest* of the three arm corpora, not a doubling.
   §C.3 called it a stretch goal on a 962-query cost estimate made without the length profile.
4. **Total arm count: 3 arm corpora × 12 arms = 36 reindex runs**, plus 3 control-replicate runs on
   the incumbent for σ, plus 4 no-regression runs (2 multilingual × {incumbent, winner}) and 2
   scifact runs ({incumbent, winner}) at the end. **36 + 3 + 6 = 45 runs**, versus 72 for the naive
   "12 arms × 6 corpora".

### K.4 The RAG question-set fixture

`scripts/jseval/916-corpora/rag-qa-v1/` — `recipe.json`, `README.md`, `generate.py`. **No corpus
text is committed**, matching the stated convention of every other real-external-dataset member
(`789-corpora/enron-qa-answers/recipe.json` says so outright) and `.gitignore`'s wholesale exclusion
of `datasets/`. The materialized fixture lands in gitignored `scripts/jseval/tmp/916-part1/`.

50 triples per corpus, derived **deterministically from existing qrels** — no LLM, no sampling, no
seed (the fixture is the first 50 eligible qrel ids in sorted order, so the recipe reproduces it;
two runs to different directories produced identical digests).

| corpus | question shape | n | gold docs | qrels skipped (gold < 2000 chars) | `query_gold_sha256` |
| :--- | :--- | ---: | ---: | ---: | :--- |
| `mixed/enron-qa` | natural question | 50 | 50 | 13 | `ca75031b89bc6406…` |
| `mixed/legal-clerc-200` | citation-retrieval passage | 50 | 50 | 1 | `10fb979f1fd27eb3…` |
| `beir/scifact` | claim to verify | 50 | 43 | 133 | `a0181d6524d85035…` |

**Gold spans are a projection, not a fork.** The span sidecar uses schema `evidence-offsets.v1`
(`corpus_inject.py:488-526`) and computes offsets with `jseval.evidence_offset.locate_offset`
(tempdoc 783 §B.1) — the same primitive the injection writer and `offset_recall.py` use. Inventing a
second span representation was the obvious wrong move here and was checked for first.

**There is no gold ANSWER anywhere, and the fixture says so.** Every `legal-clerc-200`
`queries.json` `answer` field is empty; `enron-qa` and BEIR carry no answer field. The gold **span**
therefore fills the `answer` slot of the MultiHop-RAG shape, which makes `correct_exact` and
`correct_substring` meaningless by construction. Only `correct_has_intersection` and the judge tier
are readable.

**Instrument named.** `python -m jseval tier2-eval --queries <…>/queries.json --base-url … --llm-url
… --top-k 10 --max-context-tokens 4096 --output-dir …`
(`scripts/jseval/jseval/commands/eval_cmds.py:154-202` → `agent_retrieval_eval.py:832-885`). It is
the only instrument that takes an arbitrary question file with a gold-doc field.
**`python -m jseval rag-eval` cannot be used**: it is a Gradle wrapper around `RagQualityEvalTest`
whose question set is a baked-in Java resource (`rag-eval-truth.v1.json`), not a CLI argument — and
its `--profile` flag is inert, because `modules/system-tests/build.gradle.kts` never reads
`ragEvalProfile`.

**Judge tier and chat profile.** Tier 4 — AI Judge (Semantic Eval),
`docs/explanation/09-testing-strategy.md`: agent-driven, on demand, not an automated pipeline.
Profile must be **`standard`**, not the dev-default `compact`: `tier2-eval` refuses a compact model
outright (`CompactModelNotAllowedError`, `agent_retrieval_eval.py:879-885`, markers at `:57`), which
independently corroborates CLAUDE.md's `use-every-verification-tier` rule. Do **not** pass
`--allow-compact-model` for a number that will be cited.

**Known limit that must be closed in the first sweep window.** `tier2-eval` binds a gold document by
matching `evidence_list[].title` against the retrieved `parent_doc_id` **path**
(`_doc_id_matches_title`). The fixture writes the corpus id into `title` on the expectation that it
is the ingested filename stem. That expectation is **not verifiable offline**. Index one arm, run
one query, confirm the returned `parent_doc_id` contains the gold id — before any retrieval-recall
figure from this fixture is cited.

### K.5 Pre-registered decision rule for Part 1 — COMMITTED BEFORE ANY ARM RUNS

Written and committed in this PR, before a single sweep arm exists. The Part 2 precedent (§D.7,
§I.3, §J.3) is that a rule written after the numbers are visible is worthless.

**Primary metric.** Fused (`hybrid`) **nDCG@10** and **R@10** on the three arm corpora
(`mixed/enron-qa`, `mixed/legal-clerc-200`, `mixed/ohr-bench-clean`), with `leg_union_recall` from
`staged_recall_accounting` as the deeper-recall reading.

> **Deviation from the brief and from §C.4, named rather than quietly substituted: `R@50` is not
> available.** jseval's emitted metric set is `nDCG@10, AP@10, RR@10, R@10, P@1`
> (`scripts/jseval/jseval/scoring.py:9`; the same five at `metric_families.py:63`; a repo-wide grep
> for `R@50` under `scripts/jseval/jseval/` returns nothing), and the retriever asks for
> `limit = top_k` with `top_k = 10` by default (`retriever.py:105,197`). A true R@50 means
> retrieving 50 per query on every arm — a different pipeline shape from every baseline and
> envelope in the register, and a materially slower one. Substituting R@10 while *calling* it R@50
> would be exactly the kind of reporting failure this lane already had to correct once, so the
> clause is stated as what it is: **R@10** (which is in the calibrated `QUALITY` family,
> `metric_families.py:59-67`) plus `leg_union_recall`, which reads recall over the union of the
> legs' candidate pools and is therefore the deeper-recall signal R@50 was wanted for. If the owner
> wants a literal R@50 it is a separate decision: add `R@50` to `DEFAULT_METRICS` and run every arm
> at `--top-k 50`, roughly doubling the query cost and invalidating comparison with every existing
> baseline.

**σ, and why it cannot be borrowed.** Per `docs/how-to/envelope-staleness-policy.md` the cohort
envelope at `scripts/jseval/tmp/cohort_baselines/<hash>/envelope.json` rotates on cohort-identity
change — and chunk size changes the index, hence the identity, hence the envelope. **Part 1
therefore establishes its own σ**: n=3 replicate reindexes of the **incumbent arm (500/50/100)** on
`mixed/legal-clerc-200`, run FIRST, before any non-incumbent arm. σ is the sample standard deviation
of nDCG@10 and of R@10 across those three, and the effective noise band is `max(σ, --floor)`.
Note §J's warning that σ(R@10) was 0 on clean arms while σ(nDCG@10) was not — so on the recall
axis the ±2σ band may collapse onto the floor; quote the metric's own σ, never the other one's.
Where a replicate set is n=1, the run-level noise floor is used instead and the row is marked.

**Admissibility, checked before any number is read.** An arm counts only if
`ce_coverage.verdict == "ok"` AND `per_mode.hybrid.comparable is true`. A void arm is re-run, never
cited, never averaged in. F-056 finding 4 is why this is a hard filter: above the 2% `ce_coverage`
tolerance on `legal-clerc-200` a degraded arm is biased **upward**.

**ADOPT a non-incumbent triple only if ALL of:**

1. It beats the incumbent by **> 2σ on nDCG@10 on at least two of the three arm corpora**, and is
   **not worse than −2σ on the third**. (Two-of-three rather than three-of-three because
   `ohr-bench-clean` is extractive-OCR and `legal-clerc-200` is citation retrieval; demanding
   unanimity across three genuinely different tasks would make the rule unsatisfiable by
   construction, which is a rule that cannot lose rather than a rule that can.)
2. **R@10 does not regress beyond −2σ on any arm corpus, and `leg_union_recall` does not fall.**
   Recall is the property chunking is supposed to buy; an nDCG gain paid for in recall is not the
   trade this campaign is looking for, and `leg_union_recall` is the clause that notices a loss
   deeper in the candidate pool than R@10 can see.
3. **The multilingual check passes (ADR-0043):** `miracl-de-2k` and `miracl-fr-2k` are within ±2σ on
   nDCG@10 and R@10. These are a **check, not a tuning target** — no per-language chunk size may be
   proposed whatever they show, and their near-zero chunk share (§K.3) means a *large* movement here
   is evidence of a bug, not of a language effect.
4. **`beir/scifact` (collateral control) is within ±2σ** on both metrics.
5. **Primary-indexing throughput does not regress by more than 10%** versus the incumbent.
6. **Costs are recorded and bounded**: SPLADE truncation rate and index size on disk are reported
   for every arm. They are *not* veto conditions on their own — but an arm that wins on quality
   while more than doubling index size is escalated to the owner rather than adopted silently.

**Ties and near-ties resolve to the incumbent.** If two arms both satisfy every clause, prefer the
one closest to today's `(500, 50)` in `(target, overlap)` — smaller `|Δtarget| + 4·|Δoverlap|`,
overlap weighted because it costs index size linearly. If the winner is the incumbent, Part 1's
answer is "500/50 was right", the four keys are deleted, and that is a result, not a failure.

**What is NOT in the rule, deliberately.** RAG answer quality (K.4) is a **secondary** reading. It
is reported for the incumbent and for every arm that survives clauses 1-5, and it can *veto* an
adoption (an arm that wins retrieval while measurably degrading answer groundedness is not adopted)
but it cannot *cause* one. Reason: the fixture is new, its gold-doc binding is unverified (K.4), and
the judge tier is agent-driven rather than a calibrated instrument. Promoting an uncalibrated
instrument to primary is how a campaign measures its own instrument instead of its subject.

**`threshold_chars` is out of scope for this quality-adoption rule.** It is held at 2000 for all
arms. Section L.6 closes its separate follow-up analytically: 2000 is one canonical typical-prose
500-token window and preserves K.2's fallback-estimator compatibility margin. It is not a measured
quality optimum, and K.2's 1536 arithmetic is not a corpus-profile floor.

### K.6 Machine-time plan

Per-arm wall clock scaled from the only measurement on record — 885's scifact full pipeline at
112.6 docs/s primary indexing, 45.9 s for 5168 docs, all enrichment stages complete at 231-275 s
(`885:499-505`) — adjusted for document length and estimated chunk count, since chunk embedding and
chunk SPLADE dominate the tail on a chunked corpus. **These are estimates, not measurements**, and
the first three incumbent replicates are also the calibration that replaces them.

| corpus | docs | est. chunks @500/50 | est. per arm | arms | subtotal |
| :--- | ---: | ---: | ---: | ---: | ---: |
| `mixed/enron-qa` | 5485 | 17 340 | ~12 min | 12 | ~2.4 h |
| `mixed/legal-clerc-200` | 198 | 4 130 | ~6 min | 12 | ~1.2 h |
| `mixed/ohr-bench-clean` | 1000 | 1 814 | ~5 min | 12 | ~1.0 h |
| incumbent replicates for σ (legal, n=3) | | | ~6 min | 3 | ~0.3 h |
| multilingual no-regression (de + fr × {incumbent, winner}) | | | ~5 min | 4 | ~0.3 h |
| scifact collateral control × {incumbent, winner} | | | ~5 min | 2 | ~0.2 h |
| **retrieval subtotal** | | | | **45** | **≈ 5.4 h** |
| RAG secondary (`tier2-eval`, 50 q, surviving arms only — assume ≤ 6) | | | ~10 min | ~6 | ~1.0 h |
| **total** | | | | | **≈ 6.5 h** |

Smaller-target arms cost more (128/50 is ~5× the chunk count of 500/50 on enron), so the per-arm
figure is a mean across the twelve; the 128-token arms should be expected to run roughly double.
A realistic overnight budget is **8-9 h**, matching §C.5's independent estimate by a different route.

**Order (this is part of the pre-registration, not an implementation detail):**

1. **Incumbent replicates first** — 3× `mixed/legal-clerc-200` at `(500, 50, 100)`. Without σ, no
   later number can be read. If the three replicates do not agree to within the F-055 method's
   expectations, stop and diagnose rather than proceeding.
2. **`mixed/legal-clerc-200`, 12 arms** — 198 documents, so the cheapest full matrix, and the most
   chunk-dominated corpus (98% above threshold). If chunk size does nothing here it does nothing.
3. **`mixed/ohr-bench-clean`, 12 arms.**
4. **`mixed/enron-qa`, 12 arms** — most expensive, run last so a driver failure costs the least.
5. **Controls last**, after a candidate exists: `miracl-de-2k`, `miracl-fr-2k`, `beir/scifact` at
   incumbent + winner only.
6. **RAG secondary** on the surviving arms, with `ai_activate {chatProfile:"standard"}`.

**Driver invocation** (detached, per `agent-lessons.md`'s ~60-minute background-task kill and 885's
method — a tracked background task will not survive this campaign):

```powershell
Start-Process -FilePath "python" -ArgumentList @(
  "916_chunk_sweep.py","run",
  "--out","tmp/916-part1/sweep-<YYYYMMDD>",
  "--corpora","mixed/legal-clerc-200,mixed/ohr-bench-clean,mixed/enron-qa",
  "--reps","1"
) -WorkingDirectory "F:\justsearch-public\.claude\worktrees\lane-E\scripts\jseval" -WindowStyle Hidden
```

with `JSEVAL_HEALTH_TIMEOUT_SEC=300` (open item 7: the 120 s default is below a ~150 s cold worktree
backend boot and cost three arms in a previous campaign). The driver is resumable per arm via an
`ARM.done` marker, so a killed run is restarted with the same command rather than from scratch, and
writes `RUN.done` at the end for the poll to key on.

### K.7 Falsification record (§F discipline, Part 1)

Every new test was broken once at its production site, observed failing, and restored by
copy-aside (`shutil.copy2`, never `git checkout --` — postmortem #29). Harnesses committed under
`scripts/jseval/tmp/916-part1/` are gitignored; the record is here.

| # | test | mutation | observed |
| ---: | :--- | :--- | :--- |
| 1 | `ChunkingPolicyTest.defaultMatchesShippedConstants` | `DEFAULT_THRESHOLD_CHARS` 2000→2048 | FAILED |
| 2 | `…defaultPolicyReproducesPre916` | int overload passes `targetTokens / 2` | FAILED |
| 3 | `…nullPolicyFallsBack` | null policy → `(128,0,26,2000)` instead of `DEFAULT` | FAILED |
| 4 | `…smallerTargetChunksMore` | splitter ignores `policy.targetTokens()` | FAILED |
| 5 | `…minTokensCapsOverlapAtSmallTargets` | same mutation | FAILED |
| 6 | `…clamping` | `Math.max(0, …)` → `Math.max(-9, …)` on the policy floor | FAILED |
| 7 | `ChunkSizeSweepKeysTest.unsetReproducesShippedConstants` | `DEFAULT_CHUNK_TARGET_TOKENS` 500→512 | FAILED |
| 8 | `…armValuesResolve` | builder passes `null` for `target_tokens` | FAILED |
| 9 | `…armValuesCrossTheWorkerBoundary` | same mutation | FAILED |
| 10 | `…garbageFallsBack` | `resolveNullableInt` returns 0 on parse error | FAILED |
| 11 | `…declaredWithoutDefaults` | give `CHUNKING_SWEEP_TARGET_TOKENS` a `"500"` default | FAILED |
| 12 | `ChunkingPolicyResolutionTest.mirroredDefaultsDoNotDrift` | `DEFAULT_CHUNK_TARGET_TOKENS` 500→512 | FAILED |
| 13 | `…noConfigStoreIsTheShippedPolicy` | `activePolicy()` returns `(1,1,1,1)` | FAILED |
| 14 | `…defaultConfigStoreIsTheShippedPolicy` | same mutation | FAILED |
| 15 | `…armReachesTheWriter` | `activePolicy()` ignores the resolved index | FAILED |
| 16 | `…armChangesTheEmittedBoundaries` | same mutation | FAILED |
| 17 | `…thresholdDecidesChunkingAtAll` | same mutation | FAILED |
| 18 | `test_recipe_schema` | `recipe.llm_used` → true | FAILED |
| 19 | `test_recipe_per_corpus_entries_are_complete_and_self_consistent` | `questions` 50→49 | FAILED |
| 20 | `test_the_two_digests_of_a_corpus_differ` | copy one digest into the other slot | FAILED |
| 21 | `test_span_rule_is_deterministic_and_prefers_overlap` | argmax → argmin | FAILED |
| 22 | `test_span_rule_returns_none_rather_than_a_silent_first_sentence` | unresolved → first 80 chars | FAILED |
| 23 | `test_sqrt_denominator_stops_a_long_sentence_winning_on_length_alone` | drop the `sqrt` denominator | FAILED |
| 24-25 | `test_regenerating_reproduces_the_pinned_digests[enron, legal]` | qrel iteration order reversed | FAILED |

**Five mutations initially did NOT bite, and each exposed a real defect rather than a bad
mutation.** (a) `min_tokens * 2` on the int overload changed nothing, because at target 500 the
floor is inert — which is itself the K.2 finding, and the mutation was replaced with a behavioural
one. (b) A self-assignment clamp mutation was rejected by error-prone at compile time, so it proved
nothing; replaced. (c) `noConfigStoreIsTheShippedPolicy` **published an all-defaults store and then
claimed to test the no-store branch** — the name lied. Fixed: the test now clears the global,
asserts the precondition, and a second test covers the all-defaults case. (d) The span rule's
argmax→argmin mutation survived because the test fixture had only ONE overlapping sentence, so
"best" and "only" were indistinguishable; a second overlapping sentence was added. (e) Dropping the
`sqrt` denominator survived because the two candidate sentences did not tie on raw overlap; the
fixture now makes them tie so only the normalization can separate them. Three test-precision
defects and two inert mutations, all found by falsification and none by review — which is the
argument for §F.

**And one PRODUCTION defect that falsification surfaced sideways.** Chasing (b) revealed that the
policy record's original `Math.max(1, targetTokens)` floor was NOT a no-op for the pre-916 int
overloads: `tokensToChars` maps every non-positive token count to 1 char, so clamping a caller's
`0` up to `1` widened the window from 1 char to 3 and made the "pure delegation" claim false for
degenerate inputs (the `modules/indexing` jqwik property tests generate exactly those). The floor
is now 0, and `clamping` asserts four degenerate `(target, overlap)` pairs route identically through
both entry points.

**And one defect the FULL SUITE caught that no module-level run could.** The first version of
`ChunkingPolicyResolutionTest`'s fixture stored the store it had just published as `previous` when
there had been no prior global, so `@AfterEach` re-installed its own store instead of clearing —
leaking an all-defaults `ConfigStore` into every later test class in the same Gradle fork. It turned
`EnrichmentCompletenessProjectionTest.perStageArithmeticAndTierDeclarations` red with
`IndexRuntimeIOException: status_without_artifact`, because the Lucene runtime resolves its
validation mode through `ConfigStore.globalOrNull()` (`RuntimeSession.java:459`) and the leaked
store flipped a deliberately-invalid fixture write into a hard failure. Green per module, red in the
suite — `subset-isnt-the-suite`, worked. Fixed with an explicit `published` flag so "there was no
global" is restored as *no global*; the whole-suite run is green after it.

**A second self-inflicted red, recorded because the lesson is cheap and the failure was alarming.**
An earlier full-suite run reported ~30 `NoClassDefFoundError` failures across `worker-services`.
None of them were real: a `:modules:indexing:test` invocation had been started in the same worktree
while the suite was running, rewriting the `indexing` jar under a live classpath. That is the
"one Gradle build at a time" convention, and the failure it produces looks exactly like a broad
regression. Re-run serially before believing a wave of `NoClassDefFoundError`.

### K.9 Smoke arms — the driver's first live run (owner-released, 2026-09-03)

Two real arms on `mixed/legal-clerc-200` through `916_chunk_sweep.run_arm`, to check that the keys
reach the writer **live** rather than only through the unit chain, and that `arm-metrics.json`
populates. Both exited 0. Wall clock: **500/50 in 334.6 s (5.6 min)**, **256/50 in 406.9 s
(6.8 min)** — §K.6 estimated ~6 min for this corpus, so the estimate holds and the smaller-target
arms are ~20% slower, as expected.

| | 500/50 (incumbent) | 256/50 |
| :--- | ---: | ---: |
| index size | 56.65 MB | **113.07 MB** |
| nDCG@10 | 0.5789 | 0.6207 |
| R@10 | 0.805 | 0.825 |
| `leg_union_recall` | 0.925 | 0.915 |
| `leak_rate` | 0.13 | 0.10 |
| SPLADE truncation | *(not captured — see below)* | 0.6101 |
| docs/s | 0.9 (`ingest.docs_per_sec`) | 8.4 (`run_metrics.primary_docs_s`) |
| `ce_coverage` | `ok` | **`degraded-ce`** |
| admissible | YES | **NO — VOID** |
| chunk branch ran | yes | yes |

**The keys reach the writer live: the index doubled.** 56.65 MB → 113.07 MB for the same 199
documents is exactly what halving the target chunk size does, and nothing else in the two runs
differed. That is the end-to-end proof the unit chain could not give — Head resolution → ordinal-450
snapshot → Worker `ConfigStore` → `ChunkDocumentWriter.activePolicy()` → chunk documents on disk.

**The 256 arm's numbers must NOT be read as a quality result.** It is `degraded-ce` and therefore
**VOID** by the pre-registered admissibility filter, and F-056 finding 4 says a degraded arm on
`legal-clerc-200` is biased **upward** — which is exactly the direction its nDCG moved. The filter
did its job on the first live arm it was given. Quoting +0.042 nDCG here would have been the whole
failure mode this lane documented.

**And that void is a campaign-blocking finding, not bad luck.** The cause is visible per query: the
256 arm hit `DEADLINE_EXCEEDED` on **21 of 200** queries against 1 of 200 for the incumbent. Halving
the chunk size roughly doubles the passages the cross-encoder must rerank, so the CE deadline is
reached more often, coverage degrades, and the arm voids. **A campaign that voids most of its
small-chunk arms measures nothing.** The sweep window must decide this before launching: either
raise the CE deadline to a fixed, arm-invariant value large enough for the smallest arm (and record
it as a campaign constant), or accept re-runs and budget for them. It cannot be left to chance, and
it cannot be tuned per arm — an arm-varying deadline is a second lever inside the experiment.

**Three driver defects the smoke found, all fixed:**

1. **SPLADE evidence path was relative.** `trunc_available: false` on the first arm with the key
   correctly forwarded — the path is resolved inside the **Worker**, whose working directory is not
   jseval's, so nothing was written anywhere the driver looked. Now absolute; the second arm
   reported `truncation_rate = 0.6101`.
2. **`pipeline_summary.primary_indexing` is not emitted on every run shape.** It was absent on the
   500 arm (which has only a `stages` phase-completion block) and present on the 256 arm — so a
   single-source read silently reports `null` for some arms and a number for others. `arm-metrics`
   now records `docs_s` plus **`docs_s_source`**, because a 10%-throughput clause comparing two
   different quantities across arms is worse than no clause.
3. **A tri-state conflation I wrote an hour earlier.** The first version of the chunk-branch check
   counted per-query `chunkMergeApplied` truthiness and reported `applied: 0` for **both** arms —
   i.e. "the chunk branch never fired", which would have invalidated the entire campaign premise.
   It is `None` on every row (`artifacts.py:248` copies it from a search response that does not
   carry it in this mode), and the authoritative signal is
   `per_mode.<mode>.pipeline_tracking.observed`, which contains `chunk_merge` on both arms. Absent
   read as false. Now an explicit three-state (`ran: true | false | null`) with its evidence named.
   Four new tests cover it; the "did not run" fixture deliberately contains `branch_fusion` without
   `chunk_merge`, because the first version of that test could not tell the two stages apart and
   survived the mutation that swapped them.

**Still unverified, and not claimed: the `tier2-eval` title binding.** Confirming it needs the
`tier2-eval` path with a standard-profile LLM, which this window did not run. There is an
*indirect* positive signal — jseval's own run artifacts return bare corpus ids (`10515350`), the
same form the fixture writes into `evidence_list[].title` — but `tier2-eval` matches against
`parent_doc_id` **paths** via `_doc_id_matches_title`, which is a different surface, so the signal
does not settle it. The check stays first-action for the sweep window: index one arm, run one
`tier2-eval` query, confirm the returned `parent_doc_id` contains the gold id.

Backend stopped; no listener on 33221 (`netstat` shows only `TIME_WAIT` client sockets).

---

### K.8 What this window did NOT do

No dev stack, no eval backend, no LLM, per the window's hard limit. Consequently: **no retrieval
number, no RAG number, no throughput number and no index-size number exists yet for any arm**, and
none is claimed. The sweep driver has never driven a real arm; its roll-up math is tested against
synthetic records only. The fixture's gold-doc binding to `parent_doc_id` is unverified (K.4). The
per-arm wall-clock figures in K.6 are scaled estimates from one 885 measurement of a different
corpus. Every one of these is a task for the sweep window, not a gap in this one.

---

### K.10 Sweep-window decisions — RECORDED BEFORE THE FIRST ARM RAN (2026-09-03)

Owner decisions taken into the sweep window, plus the two measurements the window itself had to
make before any of them could be applied. Everything in this subsection was written and committed
**before** the first campaign arm started; §L holds the results.

#### K.10.1 The CE deadline is an arm-invariant campaign constant, and it reaches both legs

**Decision.** Every arm — including the incumbent replicates — runs with
`JUSTSEARCH_RERANK_DEADLINE_MS=2000`. This closes open item 9b: §K.9's 256/50 smoke arm voided on
`degraded-ce` from 21/200 `DEADLINE_EXCEEDED` against the incumbent's 1/200, and a campaign whose
small-chunk arms mostly void measures nothing. The value is F-054's measured-harmless arm (2000 ms:
0 drops, nDCG unchanged). It is a **constant, not an axis** — a per-arm deadline would put a second
lever inside the experiment. `916_chunk_sweep.arm_yaml` therefore writes the key on **every** arm,
pinned by `test_every_arm_carries_the_constant_including_the_incumbent`: a constant written on only
some arms is an axis wearing a constant's name.

**One extra control**, so the campaign can show the deadline change is itself neutral on this
cohort rather than citing F-054's different one: the incumbent constants at the **shipped 200 ms**
(`--deadline-ms 200`). It gets its own arm tag (`t500-o50-d200-r0`) so it cannot collide with the
campaign-constant incumbent (`t500-o50-r0`).

**Consequence, stated plainly:** every number this campaign produces is **campaign-internal**. The
register's shipped-deadline baseline rows were measured at 200 ms and are **not** directly
comparable to any campaign arm. F-057 says so, and the driver's `analyze` footer says so on every
table it prints.

**The chain was verified live, not assumed** (`916_binding_probe.py`, run before the first arm).
The brief's expectation was that the key reaches the Worker at ordinal 450. It does — *and* that is
not the only leg, so both were checked:

| leg | mechanism | evidence |
| :--- | :--- | :--- |
| Head resolution | `ResolvedConfigBuilder.java:1351` `resolveInt("justsearch.rerank.deadline_ms", 200)` | `/api/debug/effective-config`: `{"key":"justsearch.rerank.deadline_ms","value":"2000","source":"env_var","ordinal":400,"detail":"JUSTSEARCH_RERANK_DEADLINE_MS"}`, with `default 200` demoted to a losing candidate |
| Head → Worker, on the wire | `SearchRpcOps.java:390` `setDeadlineMs(...)` from `rerankConfig.deadlineBudgetMs()` (`KnowledgeSearchEngine.java:994`), read at `GrpcSearchService.java:484` (`deadlineMs > 0 ? deadlineMs : 200`) | this is the leg that actually governs the CE call |
| Head → Worker, config snapshot | `ResolvedConfig.toWorkerSnapshot()` → ordinal 450 | `worker.log`: `Config: justsearch.rerank.deadline_ms=2000 (worker_snapshot:…\worker-config-snapshot.json, ordinal=450)` |

**A pre-check the campaign needed before trusting the fix.** F-054's third bullet is that
`DEADLINE_EXCEEDED` is stamped on *any* CE skip, including BFCArena OOM — in the W2 arms 199/200
"deadline misses" were OOM, and no deadline value would have fixed them. If the smoke arm's 21 drops
were OOM, raising the deadline would change nothing and every small-chunk arm would still void. They
are not: the smoke arm's `arm.log` and the Worker log carry **zero** `BFCArena` / "smaller than
requested" / `OrtException` occurrences, and every `Combined backfill` line reports
`arenaOomWindowed=0`. The drops are genuine CPU-side pre-check misses, which is the class 2000 ms
addresses.

#### K.10.2 Quiet-machine gate: WAIT, not void — and the threshold this machine forced

**Decision.** Before each arm the driver blocks until the machine is quiet; an arm whose window was
dirty is moved aside (`<armdir>.dirty<n>`, never deleted) and re-run, not counted. `ARM.done` is
written only for a clean attempt, so a dirty arm that exhausts its retries stays resumable instead
of being skipped-and-counted by a resumed driver. Three witnesses, not one: the pre signature, a
mid-arm monitor thread sampling every 30 s (a pre/post pair cannot see a game that starts and exits
inside a twelve-minute arm), and the post signature.

**Deviation 1 — the GPU threshold, with the measurement that forced it.** The brief specifies
"GPU util < 10 % for 30 s". Measured on this box with no game, no backend and no arm in flight,
utilization oscillates **9-15 %** and memory sits at **1107 MiB**, driven by `explorer.exe`,
`msedgewebview2.exe`, `SearchHost.exe` and PowerToys (`nvidia-smi --query-compute-apps`). A 10 %
ceiling is **below this machine's floor**: the gate could never open and the campaign would have
spent its whole window waiting while measuring nothing. The ceiling is raised to **25 %** (above the
observed 15 % peak of desktop noise, far below a game client, which pins 60-100 %) and a **VRAM
ceiling of 3000 MiB** is added, because memory is the sharper discriminator — a game holds GB, the
idle desktop holds ~1.1 GB. The exact, non-threshold discriminator remains the process check.

**Deviation 2 — two Riot processes are observed but do not block.** The first launch of the gate
blocked immediately on `RiotClientCrashHandler` + `RiotClientServices`. These are Riot's launcher
**back-end services**: they run from boot and idle for days with no game. Measured with exactly those
two up and nothing else: GPU **17 % / 1161 MiB** (this machine's baseline) and working sets of
148 MB / 8 MB — they render nothing. Blocking on them is a gate that never opens. They are therefore
**recorded in every signature** (`games`) but excluded from the blocking set (`games_blocking`);
`RiotClientUx`, the launcher *window*, is deliberately **not** excluded, because it renders and its
presence means someone is at the launcher. `TFT` was added to the pattern per the brief. Pinned by
`test_riot_launcher_helpers_are_observed_but_do_not_block`,
`test_a_real_game_alongside_the_helpers_still_blocks` and `test_the_launcher_window_is_not_excluded`
— the middle one is the one that matters, because an exclusion that swallowed a real game sharing
the launcher would be exactly the failure this gate exists to prevent.

An unreadable probe is **dirty, not clean**: "we could not tell" and "nothing is running" are
different states and only one of them is a green light for an eight-hour campaign.

**Deviation 3, made MID-CAMPAIGN at 14:45 and recorded as such: the utilization ceiling was raised
25 % → 45 % for the remaining phases.** The first 23 arms (every legal arm, the three σ replicates,
the shipped-deadline control, and eleven of twelve `ohr-bench-clean` arms) ran under the **25 %**
ceiling; only `ohr-bench-clean` 500/50 and the `enron-qa` arms ran under 45 %. The trigger: the gate
blocked for **455 s** on one legal arm and then for **23 continuous minutes** before
`ohr-bench-clean` 500/50, at `gpu_util` 29-38 % with `gpu_mem` 1327-1372 MiB. Diagnosed rather than
assumed: **no backend was running** (no listener on 33221, only two idle Gradle daemons), VRAM was
at this machine's baseline, and `nvidia-smi --query-compute-apps` named the holders — `explorer`,
`msedgewebview2`, `SearchHost`, PowerToys and `thorium.exe`, a Chromium browser opened on the
desktop during the campaign. Desktop compositing, not a competing compute workload.

Why raising it is defensible rather than a gate weakened to make a delay disappear: **F-054 already
measured this exact question.** Under a *saturating* synthetic GPU load (30.8 TFLOP/s, util pinned
100 %) the CE stage p50 went 201 → 406 ms and coverage drops went to **zero** — GPU contention is a
latency effect, not a quality effect, and quality is what clause 1 and 2 read. The two criteria that
actually discriminate a game are untouched: the process check (exact) and the 3000 MiB VRAM ceiling
(a game holds GB; the observed baseline held 1.36 GB throughout). And nothing about arm
**admissibility** depends on this parameter at all — admissibility is `ce_coverage.verdict == "ok"`
AND `per_mode.hybrid.comparable`, both read from the run.

Stated plainly as a limitation: the `docs/s` and index-size **cost** columns of the `enron-qa` arms
were collected under a marginally noisier desktop than the `legal-clerc-200` arms. Both columns are
already reported with campaign-measured noise that dwarfs this (σ(docs/s) = 13.9 %, σ(index MB) =
47 % — §L.1), so the change cannot be read as improving or degrading a cost comparison.

#### K.10.3 §K.4's instrument attribution was WRONG, and the binding check is cheaper than stated

§K.4 recorded the fixture's known limit as: "`tier2-eval` binds a gold document by matching
`evidence_list[].title` against the retrieved `parent_doc_id` path (`_doc_id_matches_title`)". That
is **not what `tier2-eval` does.** Read at source: `run_tier2_eval`
(`scripts/jseval/jseval/agent_retrieval_eval.py:832-1060`) reads only `q["query"]` and `q["answer"]`
— it posts the query to `/api/knowledge/retrieve-context`, sends the context to the LLM, and scores
the answer with `_score_tier2`. It never touches `evidence_list`. A repo-wide grep puts every
`_doc_id_matches_title` call in **`retrieval-eval` (Tier 1)** (`:502`, `:509`) and in
`rag_reachability_probe` (`:400`).

Consequences: (a) the gold-id binding needs **no LLM** and was verified before any arm; (b) the RAG
secondary that `tier2-eval` supplies is answer-quality only, which is consistent with §K.4's own
finding that the fixture has no gold answer, so only `correct_has_intersection` and the judge tier
are readable there; (c) §K.4's sentence is corrected here rather than edited in place, per
`tempdocs-are-dated-history`.

**The binding check, run and passed** (`916_binding_probe.py`, 10 legal queries against a live
backend on the persisted legal index):

- `parent_doc_id` shape is
  `f:\…\scripts\jseval\tmp\eval-corpora\mixed\legal-clerc-200\3673789.txt`; the fixture writes
  `3673789` into `evidence_list[].title`. `_doc_id_matches_title` takes the filename stem, lowercases
  both, and matches — **exactly**, not by the 60-char prefix fallback.
- **9 of 10** sampled queries retrieved their gold document inside top-10 chunks. The one miss is a
  retrieval outcome, not a binding failure: the shape check (`shape_would_match_own_stem`) is `true`
  for every observed `parent_doc_id`.
- Verdict: the fixture's gold ids resolve against what the matcher matches. No fixture or driver
  change was needed.

#### K.10.4 What the driver records per arm, and the campaign-stop it enforces

Added for this window on top of §K.9's three fixes: `rerank_deadline_ms` (the driver's intent) and
`env_deadline_ms` (read back from the run's **own** `summary.env_overrides`, `run.py:222` — so a
constant that failed to reach an arm is visible in that arm's metrics rather than assumed);
`machine_dirty`; `P@1`; `ce_coverage_frac` / `ce_silent_drops` / `ce_eligible` (the pair
`degraded-ce` is computed from, which is what the deadline control is *about*); and `chunk_docs` /
`chunk_parents_expected` from the run's `chunk_completeness` block.

`decision_bearing_nulls()` names the columns the pre-registered rule reads
(`ndcg, r10, p1, leak, union, index_bytes, docs_s, trunc_rate, chunk_docs`). Per the brief this is
checked after the first sweep arm, and a null there is a **campaign stop**, not a footnote: the
roll-up would print `-` for a clause the rule reads, and a rule evaluated over `-` is not the rule
that was pre-registered.

#### K.10.5 Execution shape

The campaign is several `run` invocations into **one** `--out` tree, in §K.6's order, each launched
detached via `916_launch_phase.ps1` (a `Start-Process` under `scripts/dev/run-watcher.mjs`, per
`agent-lessons.md`'s ~60-minute tracked-task kill) and supervised with `run-watcher.mjs check` plus
self-terminating condition polls. `--arms` selects the subset for a phase; `--phase` prefixes the
`CORPUS.done` / `RUN.done` markers, because a single `RUN.done` at the root would be written by the
first phase and read by the watcher as "the campaign finished". Per-arm `ARM.done` resumability is
unchanged.

---

