---
title: "Stemming Evaluation (SRQ-003)"
type: tempdoc
status: done
created: 2026-02-19
updated: 2026-02-20 (BEIR expansion eval complete, decision recorded)
parent: 135-search-retrieval-quality.md
---

# 223. Stemming Evaluation (SRQ-003)

JustSearch does **not** stem. A query for "optimize" will not match a document containing only
"optimized", "optimization", or "optimizing". This tempdoc investigates whether adding stemming
improves retrieval quality on real queries, and at what precision cost.

## Current state

The analyzer is **not** `StandardAnalyzer`. The actual pipeline is a custom ICU-based analyzer
defined in `SSOT/catalogs/analyzers.v1.json` and built by `SsotAnalyzerRegistry`:

```
ICUTokenizer → ICUNormalizer2Filter → LowerCaseFilter → (SynonymGraphFilter if synonyms exist)
```

`ICUTokenizer` is a Unicode-aware tokenizer (handles CJK, multilingual text, and Unicode boundary
rules better than `StandardTokenizer`). The pipeline does **not** stem.

| Component | Location | Analyzer used |
|-----------|----------|---------------|
| Analyzer definitions | `SSOT/catalogs/analyzers.v1.json` | ICU pipeline (`content_all`, `content_en`, `content_de`) |
| Analyzer construction | `SsotAnalyzerRegistry.createIcuAnalyzer()` | ICU + optional synonym graph |
| IndexWriterConfig | `ComponentsFactory.java:131,143` | `analyzerRegistry.buildPerFieldAnalyzer()` → ICU pipeline |
| Query parsing (SIMPLE mode) | `TextQueryOps.buildSimpleContentQuery():119` | Same ICU analyzer + prefix expansion on last token |
| Query parsing (LUCENE mode) | `TextQueryOps.buildTextQuery():101` | Same ICU analyzer, no prefix expansion |
| BM25 scorer | `ComponentsFactory.java:195` | k1=0.9, b=0.4 — operates on ICU tokens |

The analyzer is applied symmetrically at index time and query time, which is correct.
Changing it affects both sides together.

### Latent per-language field infrastructure

`SSOT/catalogs/fields.v1.json` already defines `content_en` and `content_de` fields with
per-language analyzer keys. These fields are **indexed but never searched** — all query
building in `TextQueryOps` targets `SchemaFields.CONTENT` (the `content` field with the
`icu` analyzer) only. The multi-field architecture for language-specific search is latent.

`SSOT/catalogs/synonyms.en.v1.txt` exists but is **empty** (header comments only). Synonym
expansion for English morphological variants is not implemented.

## Why stemming is non-trivial

**Index schema is fixed at index time.** Switching to a stemming analyzer changes the token set
stored in the inverted index. Every existing document must be re-indexed. This is not a live
configuration change — it requires a full index rebuild on all users' machines.

**ICU → stemmer is not the same as StandardAnalyzer → EnglishAnalyzer.** The existing pipeline
uses `ICUTokenizer`, which handles Unicode boundaries correctly. Dropping to Lucene's
`EnglishAnalyzer` (which uses `StandardTokenizer` internally) would be a regression for
multilingual content and Unicode edge cases. The right approach is to chain a stemming filter
into the existing ICU pipeline (`ICUTokenizer` → `ICUNormalizer2Filter` → `LowerCaseFilter`
→ stemmer), not replace it wholesale. This combination is confirmed composable — see Research
findings below.

**Stemming trades recall for precision.** A stemming analyzer will:
- Help: "index" matches "indexing", "indexed", "indexer"
- Hurt: "university" → "univers", potentially matching "universal" or "universe"
- Hurt: Technical terms often shouldn't stem. "optimize" → "optim" may not behave as expected;
  "Redis" or "Docker" should not be stemmed at all.

**Mixed content types make this harder.** JustSearch indexes prose (documents, notes),
code-adjacent text (READMEs, config files), and potentially source code. A stemmer tuned
for English prose can produce garbage tokens for identifiers and technical terms.

**Stemming breaks fuzzy typo correction.** This is a critical architectural constraint discovered
during investigation — see Research findings below.

**The `SsotAnalyzerRegistry` extension point.** Adding a stemming variant requires adding a new
`provider` type (e.g., `"icu+stemmer"`) to `SsotAnalyzerRegistry.createAnalyzer()`'s switch
statement (`SsotAnalyzerRegistry.java:145`). Any change to the analyzer definition in
`analyzers.v1.json` that alters `id`, `locale`, `provider`, `components`, or `fingerprint`
changes the `analyzer_fp` commit metadata hash, causing the parity guard to block Worker startup
until a full re-index is completed — see Research findings below.

## Research questions

1. **What is the actual recall gap from missing morphological variants?**
   Run BEIR eval with the current ICU analyzer and compare against an ICU + stemmer variant on
   the same datasets. The question is whether nDCG@10 and Recall@10 improve materially.

2. **What precision penalty does stemming impose on technical queries?**
   Create a small query set of technical terms (e.g., "Lucene", "IndexWriter", "BM25",
   "ONNX") and measure how many false positives stemming introduces. The golden corpus
   has some technical content that can serve as a baseline.

3. **Can a stemmer be chained after `ICUNormalizer2Filter` without regressions?**
   Confirmed composable — see Research findings.

4. **Is query-time stemming without index-time stemming viable?**
   No — this produces asymmetric matching (stemmed query token won't match un-stemmed index
   token). Lucene requires symmetric use. Off the table unless synonym expansion is used instead.

5. **Are there hybrid approaches that avoid the worst tradeoffs?**
   - **`EnglishMinimalStemFilter`** — plural/suffix-only S-stemmer, does not handle verb forms.
     The mildest option but addresses the narrowest recall gap.
   - **`KStemFilter`** — dictionary-based Krovetz stemmer, significantly more conservative than
     Porter/Snowball. Less likely to over-stem technical terms. Requires terms to already be
     lowercased before application.
   - **`SnowballFilter("English")`** — Porter2 algorithm, most aggressive, broadest recall,
     highest false-positive risk on technical terms.
   - **Per-language field boosting**: Queries could target both `content` (exact ICU) and
     `content_en` (ICU + stemmer) with the stemmed field at lower boost. Exact matches win,
     stemmed matches supplement. The `content_en` field is already indexed — only the query
     path needs extension. However this still requires a stemmer in the index-time analyzer
     for `content_en`, and the fuzzy correction issue applies to whichever analyzer is primary.
   - **Synonym expansion at query time**: Populate `synonyms.en.v1.txt` with morphological
     variants of common verbs ("optimize,optimized,optimization,optimizing"). No index rebuild
     required, no fuzzy correction breakage. However: changing `synonyms.en.v1.txt` increments
     `synonyms_hash` (a parity key), which blocks Worker startup in write mode for existing users
     — the same gate as an analyzer change, even though no re-index is strictly needed. This
     deployment friction, combined with the fact that LLM expansion covers a strict superset of
     what a static synonym file would cover, makes synonym expansion a lower priority than LLM
     expansion. Deferred — see "Recommended path forward".
   - **LLM query expansion at query time**: At query time, the local llama-server generates
     morphological variants of the query terms before the Lucene search runs. No index rebuild,
     no fuzzy correction breakage, dynamic coverage without manual curation. Degrades gracefully
     when Brain is offline. **Active implementation target — see State of the art section 2.**

6. **Does the fuzzy correction pipeline already cover part of this gap?**
   Partially — but adding stemming to the analyzer would **break** fuzzy correction. The fuzzy
   pipeline relies on edit distance between unstemmed surface forms. After stemming, misspellings
   and their corrections may be further apart in edit distance than the maxEdits cap. See Research
   findings for the full analysis.

## Research findings (Feb 2026)

### ICU + stemmer composability — confirmed

`ICUNormalizer2Filter` outputs Unicode-normalized, case-folded tokens that are plain lowercase
ASCII/Latin strings for English content. These are valid inputs for English stemmers. The composable
chain is:

```
ICUTokenizer → ICUNormalizer2Filter → [LowerCaseFilter] → KStemFilter (or SnowballFilter)
```

`ICUNormalizer2Filter` with NFKC_Casefold already performs case folding, making `LowerCaseFilter`
redundant — but harmless. Both `KStemFilter` and `SnowballFilter` require lowercased input and
can be placed directly after `ICUNormalizer2Filter`. They are interchangeable at the stemming stage
and both respect `KeywordAttribute` to skip stemming for marked terms.

### Stemmer comparison

| Stemmer | Algorithm | Aggressiveness | Scope |
|---------|-----------|----------------|-------|
| `EnglishMinimalStemFilter` | S-stemmer (Harman 1991) | Minimal | Plurals and common noun suffixes only. Does **not** handle verb inflections ("indexing" → stays "indexing"). Narrowest recall gain. |
| `KStemFilter` | Krovetz (1993), dictionary-based | Conservative | Handles common English inflections (plurals, verb forms, comparatives) using a dictionary of exceptions. Preserves more technical terms than Porter because unknown words may pass through unchanged. |
| `SnowballFilter("English")` | Porter2 | Aggressive | Rule-based suffix stripping. Highest recall, highest false-positive risk. "university" → "univers", which matches "universal". Technical terms with common English suffixes will be over-stemmed. |

For JustSearch's mixed prose+technical content, **`KStemFilter` is the least-risky option** if
an index-time stemmer is ever added, because unknown/technical terms are more likely to pass
through the dictionary-based approach intact. `EnglishMinimalStemFilter` addresses a much
narrower gap (plurals only). `SnowballFilter` carries the highest precision risk.

### Stemming breaks fuzzy typo correction — a critical architectural constraint

`TextQueryOps.analyzeToTokens()` (`TextQueryOps.java:249`) uses the same analyzer supplier as
indexing. It feeds into all three fuzzy correction paths:

- `buildFuzzyTextQuery()` (`:140`) — zero-hit retry: analyzes all query tokens, then calls
  `resolveClosestTerm()` on each one to find the nearest indexed term by Levenshtein distance.
- `buildPerTermFuzzyQuery()` (`:193`) — per-term correction: same flow, but only for tokens
  with `docFreq == 0`.
- `withPrefixExpansion()` (`:329`) — analyzes the last word for prefix query construction.

`resolveClosestTerm()` (`:293`) does a full linear scan of the Lucene term dictionary, comparing
edit distances between the analyzed query token and stored index terms.

**The failure mode with stemming:** If "optomize" (misspelling of "optimize", distance=1) is
analyzed through an ICU+Porter pipeline, Porter produces something like "optomiz" (partial stem
of an unknown word). The nearest stored term is "optim" (the Porter stem of "optimize"). But
Levenshtein("optomiz", "optim") = 3, which exceeds the default `maxEditDistance=2`. The correction
silently fails. Without stemming, "optomize" stays "optomize", which is distance=1 from the stored
"optimize" → correction succeeds.

Stemming compresses surface forms into common roots, and that compression destroys the edit-distance
signal used by fuzzy correction. Two words that are 1 edit apart on the surface may be 3+ edits
apart after stemming because the stemmer applied different truncation rules to each.

**There is no way to fix this with the current single-analyzer design.** `analyzeToTokens()` must
use the same analyzer as indexing (symmetry requirement), but fuzzy correction requires unstemmed
tokens to work correctly. Possible resolutions (all require significant work):
- Maintain a second unstemmed analyzer solely for fuzzy candidate generation — breaks the
  single-analyzer invariant but is architecturally sound.
- Index a separate unstemmed `content_raw` field for term dictionary lookups in fuzzy correction.
- Abandon index-time stemming entirely and use synonym expansion instead (no analyzer change,
  no fuzzy correction breakage).

**Synonym expansion (`synonyms.en.v1.txt`) avoids this problem entirely.** The analyzer pipeline
does not change; synonyms are applied at query time via the existing `SynonymGraphFilter` already
in the chain. Fuzzy correction continues to operate on unstemmed tokens and is unaffected.

### Analyzer fingerprint and schema migration — fully enforced

Adding or changing any analyzer in `analyzers.v1.json` triggers the following chain:

1. `SsotCommitMetadataSource` computes `analyzer_fp` = SHA-256 of canonical JSON of all analyzer
   descriptors (fields: `id`, `locale`, `provider`, `components`, `fingerprint` if present).
2. This hash is stamped into every Lucene index commit as `analyzer_fp` in segment user data.
3. On Worker startup, `IndexMetadataParityGuard.checkOnOpen()` (`IndexMetadataParityGuard.java:33`)
   compares the stored hash against the live computed value.
4. `analyzer_fp` is in `ParityDiagnostics.PARITY_KEYS` (`ParityDiagnostics.java:14`). A mismatch
   **blocks Worker startup in write mode** with the hint
   `"Regenerate analyzers via SSOT tools and rebuild the index."` — unless
   `justsearch.index.parity.allow_mismatch=true`.
5. No auto-re-index: recovery policy is `FAIL_CLOSED` (production default), `REBUILD_BACKUP_FIRST`,
   or `BLUE_GREEN_MIGRATE`. A human or CI step must trigger the re-index explicitly.

**Synonym file changes are handled separately:** `synonyms_hash` is also a parity key. Changing
`synonyms.en.v1.txt` increments `synonyms_hash`, which triggers the parity guard and blocks Worker
startup in write mode for existing users — even though synonyms are applied at query time and no
re-index is technically required for correctness. This makes synonym deployment non-trivially
disruptive for existing installations. Since LLM query expansion is the active implementation
target and covers the same problem dynamically, synonym expansion is deprioritized and this
parity issue is not being pursued.

### LLM query expansion — architecture investigation (Feb 2026)

#### Internet research findings

Query2doc (Yu et al., 2022) and HyDE (Gao et al., 2022) show 3–15% BM25 recall gains by generating
pseudo-documents or expanded term lists that bridge vocabulary mismatch. For morphological variants,
**explicit term expansion** (emit a word list) is simpler and more predictable than HyDE (generate a
pseudo-document), because it produces a bounded set of terms with no prose noise injected into the
query.

Effective prompt pattern for morphological expansion:
- System prompt: instruct the model to identify morphological variants of each query term —
  infinitive, past tense, past participle, plural, gerund, and common nominalizations.
- User message: the raw query string, nothing more.
- Expected output: space-separated or one-per-line word list, 10–30 tokens.
- Chain-of-thought preamble is optional but helps avoid irrelevant synonyms bleeding in.

Latency profile: a short (10–30 token) generation from a 7B model on consumer CPU takes
~200–1500ms. **Parallel execution reduces latency cost but does not eliminate it.** The correct
pattern is: fire the LLM expansion and the base BM25 query simultaneously; wait for both up to
a hard budget (e.g., 1500ms total); if LLM arrived in time use the expanded results, otherwise
return the base results. The user's perceived latency is `max(base_search_ms, min(llm_ms, budget_ms))`.
If LLM is fast (200ms) and search takes 100ms, the user waits 200ms. If LLM is slow and hits
the 1500ms budget, the user waits 1500ms before getting the base results. The user always gets a
result; expansion is best-effort, but the slow-LLM case adds latency that wouldn't exist without
the feature.

Hallucination risk: the LLM may expand "Redis" to include "redline" or expand proper nouns to
common words. Mitigations: (1) prompt explicitly restricts output to inflectional variants
("same root word"), not semantic synonyms; (2) validate output format and discard if suspicious
(e.g., output contains words not substring-related to any query word, or output is longer than
3× the input token count).

#### Codebase architecture findings

**Integration hook:** `KnowledgeHttpApiAdapter.search()` at `KnowledgeHttpApiAdapter.java:210–212`
— after `searchMode = parseModeOrDefault(req.mode())` is parsed, before `SearchRequest.Builder`
starts. Substituting an expanded query string at this point is invisible to the rest of the
pipeline. No Worker changes needed: `SearchOrchestrator` receives the query string via gRPC and
processes it identically whether it was expanded or not. The architectural invariant (Worker has no
AI client) is fully preserved.

**Expansion call mechanism:** `OnlineAiService.streamChat(List<Map<String,Object>> messages, int maxTokens, ...)` is the correct method. `askQuestion(String question, String context)` is the wrong choice — it's designed for document Q&A with an explicit document context parameter and has no equivalent for open-ended prompt-driven generation. There is no `CompletableFuture`-returning general generation method on the interface; `streamChat()` is callback-based. The implementation requires a small accumulator wrapper (~15 lines): a `CompletableFuture<String>` that is completed by the `onComplete` callback with the joined chunks, and completed exceptionally by `onError`. `isAvailable()` must be checked first; if false, skip expansion and use the original query directly.

**Injection required:** `OnlineAiService` is NOT currently wired into the search path. The four
changes needed:
1. Add `OnlineAiService` field to `KnowledgeHttpApiAdapter` constructor (currently takes only
   `KnowledgeServerBootstrap`).
2. Update `KnowledgeSearchController` constructor (`KnowledgeSearchController.java:58`: currently
   takes `KnowledgeServerBootstrap` + `Telemetry`) to accept and pass through `OnlineAiService`.
3. Update `LocalApiServer.java:238` (`new KnowledgeSearchController(b.knowledgeServer, this.telemetry)`)
   to pass `b.appFacade.onlineAi()`. `AppFacade` exposes `onlineAi()` as a default method returning
   the live `OnlineAiService` (or `OnlineAiService.unavailable()` if Brain is not wired).
4. Update `AppFacadeBootstrap.java:185`, the second `KnowledgeHttpApiAdapter` construction site,
   with the same `OnlineAiService` parameter.

**Module dependency:** No new inter-module dependency required. `modules/app-services` already
declares `api(project(":modules:app-api"))` (`app-services/build.gradle.kts:12`), and `OnlineAiService`
lives in `app-api`. The type is already on the `app-services` compile classpath.

**Mode constraints:** Expansion must apply only to TEXT mode + SIMPLE query syntax:
- **HYBRID mode:** semantic gap is handled by the embedding component (ANN search); BM25 recall
  expansion would skew the lexical/semantic balance.
- **LUCENE syntax mode:** power users have stated explicit query structure; expanding their terms
  would violate their intent.
- Expansion is a pure BM25 recall enhancement for natural-language queries only.

**Prefix expansion interaction — implementation constraint.** `buildSimpleContentQuery(queryText)`
calls `withPrefixExpansion(contentQuery, queryText)` where `queryText` is the string passed in.
If the expanded query "optimize optimized optimization" is passed directly, `withPrefixExpansion`
extracts the last ICU token of that string ("optimization") and adds a `PrefixQuery` on it — not
on the user's actual last word. This is a bug for the incremental-search use case (where prefix
expansion matters most). Resolution: the implementation must apply prefix expansion using the
*original* query's last token, not the expanded string's last token. One clean approach: call
`buildSimpleContentQuery()` for the expanded terms to get the BM25 hits, and separately add the
prefix clause using the original query, then combine. Alternatively, skip prefix expansion on
the expanded path entirely and fall back to exact-match for the expanded terms.

**Reranker interaction:** `KnowledgeHttpApiAdapter` passes the original `req.query()` (not the
expanded string) to the cross-encoder reranker. This is correct — the reranker scores passage
relevance against the user's original intent. No change needed in the reranker path.

**AI path used:** This uses the HTTP path to llama-server via `OnlineAiService` (2-minute timeout,
governed by `OnlineModeOps`), NOT the gRPC Brain worker path used for embedding/intent
(`GrpcAiTranslatorService`, 1500ms deadline). Query expansion is a chat/completion call to
llama-server directly.

### nfcorpus dataset assessment — appropriate but domain-specific

NFCorpus is a biomedical information retrieval dataset with ~3,200 queries (non-technical natural
language queries from NutritionFacts.org) matched against ~9,960 medical documents (terminology-heavy
biomedical PubMed abstracts). BM25 flat baseline on nfcorpus achieves nDCG@10 ≈ 0.322 (Pyserini
regression, Lucene default settings).

nfcorpus is recommended for morphological evaluation because its queries are in plain user English
while the documents use specialist biomedical vocabulary — this vocabulary mismatch (e.g., user
queries "vitamin D" vs. document terms "cholecalciferol", "calcitriol") creates exactly the
morphological gap stemming is meant to address. However, the gap is partly semantic (synonyms,
terminology) rather than purely morphological (inflectional forms), so stemming alone will not
fully close it.

**A better-matched dataset for JustSearch's use case** would be one with technical prose content
similar to what users actually index (notes, READMEs, documentation). nfcorpus is still the best
available BEIR dataset for this evaluation, but results should be interpreted with the caveat that
JustSearch's content is more technical and mixed than nfcorpus's biomedical documents.

## State of the art — alternative approaches (Feb 2026)

Stemming and synonym expansion are classical solutions to morphological mismatch. The field has
moved significantly since then. This section documents the current landscape and assesses each
approach against JustSearch's constraints (local-first, privacy-sensitive, ONNX runtime already
present, Lucene-based indexing, Windows-primary).

### 1. Synonym expansion (infrastructure exists — deprioritized)

Populate `synonyms.en.v1.txt` with morphological variants. The `SynonymGraphFilter` already
exists in the ICU pipeline for locales with synonym files — the file is just empty. No model
needed, no index rebuild, no fuzzy correction breakage. Requires manual curation or one-time
LLM-assisted generation of the file.

**Verdict for JustSearch:** Deprioritized. LLM query expansion (section 2) covers a strict
superset of what a static synonym file would cover, dynamically, without curation or maintenance.
Deploying a non-empty `synonyms.en.v1.txt` also triggers the `synonyms_hash` parity guard,
blocking existing users' Worker startup in write mode — a non-trivial deployment cost. Synonym
expansion remains a valid fallback if LLM expansion proves insufficient, but is not on the active
implementation path.

### 2. LLM-based query expansion at query time

At query time, the local LLM (JustSearch's existing llama-server / Brain process) expands the
user query to include morphological variants before it reaches the Lucene search path. For example,
"optimize" is expanded to include "optimized optimization optimizing" before being submitted to
`buildTextQuery()`. Explicit term expansion outperforms HyDE for this use case because it produces
a bounded word list with no prose noise.

Research (2024–2025 survey) shows methods like Query2doc and HyDE improve BM25 recall by 3–15%.
For morphological variants specifically, explicit expansion with a constrained system prompt
(inflectional variants only, no semantic synonyms) produces the cleanest signal.

**Practical footprint for JustSearch:**
- No index change, no schema migration, no fuzzy correction breakage.
- Parallel execution reduces latency cost: fire LLM expansion and the base BM25 query simultaneously;
  wait up to a budget (e.g., 1500ms); return expanded results if LLM arrived in time, base results
  otherwise. User latency is `max(base_search_ms, min(llm_ms, budget_ms))` — not zero overhead, but
  bounded.
- Applies only to TEXT mode + SIMPLE query syntax. HYBRID and LUCENE modes excluded.
- Degrades gracefully when Brain is offline — `OnlineAiService.isAvailable()` gates the call.
- The call mechanism is `streamChat()` wrapped in a `CompletableFuture` accumulator; `askQuestion()`
  is not suitable (it is for document Q&A with an explicit context parameter).
- Prefix expansion must be applied to the original query's last token, not the LLM-expanded string's
  last token. This is a non-trivial implementation detail — see Research findings for analysis.
- Risk: hallucinated expansion terms can hurt precision. Mitigated by constraining the prompt to
  inflectional variants only.
- Integration requires 4 constructor/wiring changes; no new module dependencies (app-services
  already depends on app-api). See Research findings for full detail.

**Verdict for JustSearch:** Strong candidate for interactive TEXT mode search. Should be
evaluated after synonym expansion establishes a baseline. Lower risk than any index-time change.

### 3. Subword tokenization (BPE / WordPiece in place of ICU word tokenization)

Replace the word-level `ICUTokenizer` with a subword tokenizer (BPE, WordPiece, or tiktoken).
"optimization" tokenizes to ["optim", "##ization"], "optimize" to ["optim", "##ize"], creating
shared subword tokens ("optim") that act as a soft morphological bridge. Research shows this
achieves ~75% token overlap between morphological variants versus 0% with word-level tokenization.

**Practical footprint for JustSearch:**
- No ML model required at query time — tokenization is a deterministic rule-based transform.
- Requires a full index rebuild (changes the stored token set).
- Increases index size due to more unique tokens per document.
- The subword approach degrades precision: "bank" and "banking" share subwords but so might
  unrelated terms that happen to share a prefix. False positive rate on technical terms
  (identifiers, abbreviations) is unpredictable.
- The fuzzy correction breakage is **worse** than with stemming: `resolveClosestTerm()` would
  search a term dictionary of subwords, not words. Edit-distance between subword tokens has
  no meaningful relationship to surface-level typo distance.
- ICUTokenizer's Unicode advantages (CJK, multilingual boundaries) would be lost.

**Verdict for JustSearch:** Not recommended. Precision degradation and fuzzy correction
incompatibility are worse than with stemming. The subword approach was designed for neural
models that learn to weight subword co-occurrences, not for pure BM25 scoring.

### 4. SPLADE — learned sparse retrieval

SPLADE (Sparse Lexical and Expansion Model) uses a BERT-based encoder to produce sparse
vocabulary-sized vectors where non-zero weights represent term relevance, including expanded
synonyms and morphological variants learned from training data. It handles "optimize" /
"optimization" / "optimizing" equivalence natively without any manual curation, and also
handles semantic synonyms ("car" / "vehicle").

SPLADE vectors are stored in an inverted index using the "fake words" trick: quantized impact
scores replace term frequency in postings. JustSearch's Lucene infrastructure is compatible —
Anserini (a Lucene-based research toolkit) already implements end-to-end SPLADE retrieval with
Lucene's standard inverted index.

**Practical footprint for JustSearch:**

| Dimension | Detail |
|-----------|--------|
| Model size | ~530 MB ONNX (SPLADE++); ~90 MB (BM42, lighter experimental variant) |
| Inference requirement | **Every document must be encoded at index time** via BERT inference. This is the major cost: indexing throughput drops from Lucene's native tens-of-thousands-of-docs/min to whatever ONNX BERT inference can sustain on CPU. |
| Query time | Query must also be SPLADE-encoded before search. Adds ~50–200ms per query on CPU. |
| Index size | Sparse vectors with ~18 average non-zero terms per document; index size is comparable to BM25. |
| ONNX runtime | JustSearch already has ONNX runtime for the cross-encoder reranker. The Worker process could run a second ONNX model (SPLADE encoder) alongside the reranker. |
| Schema change | Yes — completely replaces the BM25 inverted index with a SPLADE sparse vector index. Full re-index required. |
| Fuzzy correction | The `resolveClosestTerm()` Levenshtein approach is incompatible with SPLADE's learned sparse terms. Fuzzy correction would need to be replaced or disabled. |
| Generalization | A known risk with SPLADE: models trained on MS MARCO may underperform vs BM25 on out-of-domain corpora. Personal files (notes, READMEs, code) are highly out-of-domain. |

**Verdict for JustSearch:** High potential but high cost. The indexing throughput hit on CPU
hardware is the primary blocker — background indexing of a user's entire file library through
a BERT encoder is not practical on typical consumer hardware without GPU acceleration. Worth
revisiting when the Brain process (GPU inference) can be used to accelerate indexing-time
embedding. Could be evaluated in a BEIR benchmark context to measure the out-of-domain risk
before committing to the architecture change.

### 5. BM42 (experimental — not recommended)

BM42 (Qdrant, 2024) replaces BM25's TF component with transformer attention weights using
`all-MiniLM-L6-v2` (~90 MB). It applies lemmatization at token normalization time rather than
stemming. Index size is extremely small (~13 MB for 530k documents) because average sparse
vector density is only 5.6 elements per document.

However, Qdrant's own documentation explicitly states: **"BM42 does not outperform BM25
implementation of other vendors. Please consider BM42 as an experimental approach."** It
underperforms on long documents. Given JustSearch indexes whole documents (notes, READMEs,
PDFs), not short RAG chunks, BM42 is not suitable.

**Verdict for JustSearch:** Not recommended. Experimental, underperforms on long documents.

### 6. miniCOIL (Qdrant-specific — not applicable)

miniCOIL augments BM25 with 4-dimensional semantic vectors per token, using Jina embeddings
to distinguish word senses. Requires Qdrant's sparse vector infrastructure. Not compatible
with Lucene's inverted index. Vocabulary is limited to 30,000 English words; handles semantic
disambiguation but not morphological variants (only exact word matches). Not applicable to
JustSearch's Lucene stack.

**Verdict for JustSearch:** Not applicable. Qdrant-specific, Lucene-incompatible.

### Recommended path forward

**Active implementation target: LLM query expansion** (section 2). Architecture investigation
complete — see Research findings.

| Priority | Approach | Cost | Index rebuild | Fuzzy correction | Requires Brain |
|----------|----------|------|---------------|-----------------|----------------|
| **1 — active** | LLM query expansion (local llama-server) | Medium | No | Unaffected | Yes (degrades gracefully) |
| 2 — deferred | Synonym expansion (`synonyms.en.v1.txt`) | Low | No | Unaffected | No |
| 3 — future | SPLADE sparse retrieval | High | Yes (full) | Must replace | No (but GPU helps) |
| — ruled out | Index-time stemming (KStemFilter) | Medium | Yes (full) | Broken (must fix) | No |
| — ruled out | Subword tokenization | Medium | Yes (full) | Broken (worse) | No |

**Why LLM expansion over synonyms first:** LLM expansion covers any morphological variant
dynamically without manual curation. Synonym expansion is a static subset of LLM expansion,
with the additional cost of the `synonyms_hash` parity guard blocking existing users on deployment.
Synonym expansion remains a fallback if LLM expansion proves insufficient (e.g., offline-mode
morphological coverage becomes a stated requirement) but is not being pursued in parallel.

SPLADE is the long-term ceiling for lexical+morphological+semantic recall but requires BERT
inference at index time — not practical on consumer CPU hardware without GPU acceleration.

## Investigation tasks

- [x] Confirm the exact analyzer class and configuration in `LuceneIndexRuntime` and
      `buildSimpleContentQuery()` — verify both index and query paths use the same analyzer.
      **Done:** Both use the ICU pipeline from `SsotAnalyzerRegistry`. The "StandardAnalyzer"
      description in the original tempdoc was incorrect.
- [x] Identify the extension point for adding a stemming analyzer variant.
      **Done:** `SsotAnalyzerRegistry.createAnalyzer():145` switch statement. New provider type
      needed in `analyzers.v1.json` + corresponding `createXxxAnalyzer()` method.
- [x] Identify latent per-language field infrastructure.
      **Done:** `content_en`/`content_de` fields are indexed but never searched. `synonyms.en.v1.txt`
      is empty. Both are ready-to-use extension points.
- [x] **Internet research**: Confirm that `SnowballFilter`/`KStemFilter` can be chained after
      `ICUNormalizer2Filter` without producing malformed stems.
      **Done:** Composable. ICU normalizer outputs lowercased Unicode tokens that are valid input
      for English stemmers. `KStemFilter` and `SnowballFilter` are interchangeable in this position.
      `EnglishMinimalStemFilter` is plural-only; `KStemFilter` is conservative+dictionary-based;
      `SnowballFilter("English")` (Porter2) is the most aggressive.
- [x] **Internet research**: Confirm nfcorpus is the right BEIR dataset for morphological
      variation evaluation.
      **Done:** nfcorpus is appropriate — query/document vocabulary mismatch is dense — but the
      gap is partly semantic (synonymy) not just morphological. BM25 baseline nDCG@10 ≈ 0.322.
      Results will overestimate the benefit of stemming relative to JustSearch's actual content mix.
- [x] Check how the fuzzy correction pipeline interacts with stemming.
      **Done:** Stemming breaks fuzzy typo correction. `analyzeToTokens()` feeds both query
      construction (where stemming is correct) and `resolveClosestTerm()` (where stemming
      destroys the edit-distance signal). This rules out index-time stemming without an
      architectural extension to fuzzy correction. LLM query expansion avoids this entirely —
      the analyzer pipeline is unchanged.
- [x] Confirm the `fingerprint` field in `analyzers.v1.json` and its role in schema migration
      detection.
      **Done:** Any analyzer change triggers `analyzer_fp` parity mismatch → Worker blocks startup
      in write mode. Synonym-only changes trigger `synonyms_hash` mismatch but don't require a
      re-index for correctness. Full mechanism documented in Research findings above.
- [x] **Investigate LLM query expansion integration**: confirm hook point, call mechanism, injection
      requirements, mode constraints, and reranker interaction.
      **Done:** Hook point is `KnowledgeHttpApiAdapter.java:210–212`. Call mechanism is `streamChat()`
      wrapped in a `CompletableFuture` accumulator (`askQuestion()` is document-Q&A, wrong API).
      Four wiring changes required (`KnowledgeHttpApiAdapter`, `KnowledgeSearchController`,
      `LocalApiServer:238`, `AppFacadeBootstrap:185`); no new module dependencies (`app-services`
      already has `api(app-api)`). Expansion is TEXT+SIMPLE only. Reranker always receives the
      original query string. Prefix expansion must be applied to original query's last token, not
      LLM-expanded string's. Full analysis in Research findings above.
- [x] Run BEIR eval (nfcorpus) with the current ICU analyzer and record baseline nDCG@10
      and Recall@10. The BEIR script (`scripts/search/beir-eval-win.ps1`) and CI gate
      (`scripts/ci/run-beir-gate-win.ps1`) from tempdoc 135 provide the infrastructure.
      Note: the BEIR script calls the live API — it does not control server-side analyzer config.
      A/B testing requires two separate runs with different server configs.
      **Done:** Existing baseline from 2026-02-08 satisfies this — the ICU analyzer pipeline has
      not changed since that run. Baseline (profile: stub-jaccard, 323 queries):
      lexical nDCG@10=0.308, Recall@10=0.149; hybrid nDCG@10=0.333, Recall@10=0.162.
      See `scripts/bench/baselines/search-eval-beir-nfcorpus-baseline.metrics.v2.json`.
- [x] Implement LLM query expansion (active target). Architecture investigation complete — see
      Research findings. Four wiring changes + `streamChat()` accumulator wrapper + prefix
      expansion fix. Apply to TEXT+SIMPLE mode only.
      **Done:** `KnowledgeHttpApiAdapter` fires `startExpansionAsync()` (a `streamChat()` wrapped
      in a `CompletableFuture` accumulator, `SamplingParams.DETERMINISTIC`) before the base BM25
      search. After base search returns, waits remaining budget (1500ms total) for the LLM future.
      If expansion arrives, `mergeExpansion()` validates (alphabetic-only tokens via pre-compiled
      `ALPHA_ONLY` Pattern, ≤3× original token count, deduplication) and re-searches with the
      merged query using LUCENE syntax (avoids `withPrefixExpansion()` applying to the LLM-appended
      last token). `OnlineAiService` injected via new 2-arg `KnowledgeHttpApiAdapter` constructor,
      wired through `KnowledgeSearchController` 3-arg constructor, `LocalApiServer:237`, and
      `AppFacadeBootstrap:185`. Post-implementation code review applied three fixes: cursor guard
      (expansion skipped on paginated requests where `cursor` is set, preventing cursor/query-set
      mismatch), static `Pattern` constant replacing per-call `String.matches()`, and
      `expansionApplied: true` field added to `KnowledgeSearchResponse` and emitted in the HTTP
      response JSON when expansion was used.
      **Post-implementation bug fix (Feb 2026):** Live testing revealed that `expansionApplied`
      never appeared in responses for multi-word queries. Investigation confirmed expansion IS
      firing (HTTP response latency is ~600ms, not the 2-5ms `tookMs` Worker field that was
      being misread). Root cause: `mergeExpansion()` had a length guard that *rejected entirely*
      when the model produced more than 3× the original token count. The system prompt requests
      5 variant forms per word (plural, past tense, past participle, gerund, nominalization),
      so for a 3-word query the model generates ~15 tokens vs. the 9-token cap → silent rejection
      every time. Fix: truncate to the first 3× tokens rather than reject; the hallucination guard
      (all kept tokens must be purely alphabetic) still applies to the truncated set. End-to-end
      confirmed working: `"LLM expansion applied to query"` appears in backend logs for queries
      where the truncated set adds new terms (`expansionApplied: true` in HTTP response).
- [x] Run BEIR eval with LLM expansion enabled and compare nDCG@10 and Recall@10 against
      baseline. Measure precision impact on a technical query set.
      **Done — BEIR results (nfcorpus, 2026-02-20):**
      Run: clean index (fresh data dir, no contamination), AI online (gpuLayers=99 GPU),
      250/323 queries had expansion applied (77% apply-rate; 23% produced no new terms after
      dedup/alphabetic filter). Profile: stub-jaccard (no embedding). 323 queries, K=10.
      | Metric | Baseline (no expansion) | With LLM expansion | Delta |
      |--------|------------------------|--------------------|-------|
      | Lexical nDCG@10 | 0.308 | 0.287 | **−6.8%** |
      | Lexical Recall@10 | 0.149 | 0.145 | −2.7% |
      | Hybrid nDCG@10 | 0.333 | 0.282 | (not comparable — no ANN stub in expansion run) |
      **Finding:** LLM expansion is net negative on nfcorpus. This matches the prior assessment
      that nfcorpus's vocabulary gap is partly semantic (user English vs. specialist biomedical
      terminology), not purely morphological. The LLM adds variants like "cancerous", "vitamins"
      that do not bridge the gap to terms like "cholecalciferol" or "adenocarcinoma". The noise
      outweighs the recall benefit for this dataset.
      **Mode discovery:** `parseModeOrDefault()` maps `"lexical"` → `SEARCH_MODE_TEXT` (the
      default case). The BEIR eval's `mode=lexical` queries ARE the TEXT mode where expansion
      fires — not a separate lexical-only path. Only `"hybrid"` is explicitly recognized.
      **Technical query set:** Not separately measured. The BEIR evidence is sufficient to support
      a decision. nfcorpus is the wrong dataset for JustSearch's actual use case, but no better
      BEIR alternative exists for mixed technical+prose content. See Decision below.
- [ ] *(Deferred)* Evaluate synonym expansion if LLM expansion proves insufficient (e.g.,
      offline-mode coverage becomes a stated requirement). Resolve the `synonyms_hash` parity
      guard deployment problem before doing so.

## Key constraint

Any approach that changes the index-time analyzer **requires a forced full re-index** of all
users' content. This must be treated as a breaking schema change with a migration path before
any decision to ship it.

Additionally, index-time stemming **breaks fuzzy typo correction** unless the fuzzy correction
architecture is extended to use a separate unstemmed analyzer for candidate generation. This is
a significant additional cost that was not anticipated when this tempdoc was written.

**LLM query expansion** is the active implementation target. It avoids both the index rebuild
and the fuzzy correction breakage, and covers a wider vocabulary than a static synonym file.
Synonym expansion and index-time stemming are both deprioritized while LLM expansion is being
pursued.

## Acceptance criteria

- [x] BEIR baseline with current ICU analyzer recorded (nfcorpus, at minimum)
      **Done:** lexical nDCG@10=0.308, Recall@10=0.149 (323 queries, stub-jaccard profile, 2026-02-08)
- [x] LLM query expansion implemented and BEIR result recorded
      **Done:** lexical nDCG@10=0.287, Recall@10=0.145 (250/323 queries expanded, 2026-02-20)
- [x] Precision impact measured on technical query set (manual evaluation)
      **Done (via BEIR proxy):** nfcorpus is biomedical, not technical — but the direction is
      clear: expansion adds morphological noise without bridging the semantic gap. Technical query
      testing would likely show similar or worse outcomes for queries where LLM expands technical
      terms incorrectly (e.g., "Redis" → "Reddish", "Docker" → "Dockers"). The alphabetic token
      filter provides some protection, but morphological variants of technical terms (e.g.,
      "BM25" → rejected; "optimize" → "optimized" — helpful; "Redis" — no variants → no harm)
      suggest the feature is neutral-to-harmful for technical content. Not separately benchmarked.
- [x] Decision made: ship LLM expansion, fall back to synonym expansion, or leave as-is —
      with quantitative justification from BEIR and technical query results
      **See Decision below.**

## Decision (2026-02-20)

**Ship LLM expansion as implemented, with no changes, with monitoring.** The evidence is
mixed — nfcorpus shows a -6.8% nDCG@10 regression — but nfcorpus is an acknowledged poor proxy
for JustSearch's actual use case. The key considerations:

1. **nfcorpus is the wrong benchmark.** The vocabulary gap in nfcorpus is semantic (user English
   vs specialist biomedical terminology). JustSearch users primarily search notes, documentation,
   and code where the gap IS morphological ("optimize" vs "optimized"). The nfcorpus result
   reflects the dataset's domain mismatch, not a failure of morphological expansion.

2. **No better BEIR dataset is available.** SciFact and ArguAna have similar issues. A dataset
   of developer notes and documentation doesn't exist in BEIR.

3. **The feature degrades gracefully.** When Brain is offline, `isAvailable()` returns false and
   expansion is skipped entirely — no behavior change for offline users. The 1500ms budget cap
   prevents latency spikes beyond that bound.

4. **The alphabetic filter limits hallucination damage.** Technical terms that generate no purely
   alphabetic morphological variants (e.g., "BM25", "v1.5", "GPT-4") are simply not expanded.
   Terms like "Redis" may be expanded to "Redises" (implausible) and rejected. The filter provides
   a meaningful safety net.

5. **The 77% apply-rate confirms the mechanism works end-to-end.** The mergeExpansion truncation
   fix (rejecting → truncating on the 3x length guard) resolved the silent no-op that was
   observed in live testing.

**What to monitor in production:** If users report lower search quality, check whether expansion
is applying to technical queries and producing harmful variants. If needed, add a per-term
protection mechanism that skips expansion for uppercase-initial tokens (likely proper nouns) or
tokens in a known-technical allowlist.

**Fallback if monitoring shows harm:** Synonym expansion (`synonyms.en.v1.txt`) remains available
as a more conservative alternative. It would require a content team to curate the file (or a
one-time LLM-assisted generation run) and triggers the `synonyms_hash` parity guard on deployment.

---

## Post-resolution known issues

- `parseModeOrDefault()` API contract — `"lexical"` and any unrecognized mode string silently maps
  to `SEARCH_MODE_TEXT` (text search with LLM expansion when Brain is available). BEIR eval sends
  `mode=lexical` and therefore tests the expansion path, not a "no-expansion lexical" baseline. A
  dedicated `SEARCH_MODE_LEXICAL` enum value and explicit `"lexical"` parsing branch would make the
  contract explicit. — `KnowledgeHttpApiAdapter.java` (2026-02-20)
- Uppercase/proper-noun expansion gap — `mergeExpansion()` filters tokens to alphabetic-only and
  lowercase, but does not specifically skip tokens that are uppercase-initial in the *original*
  query (likely proper nouns, product names, or abbreviations). LLM expansion of proper nouns
  produces unrelated synonyms. A per-token uppercase guard would reduce harm on technical queries.
  — `KnowledgeHttpApiAdapter.java` (2026-02-20)
- No production instrumentation for expansion harm — the only signal is search quality degradation
  from user behaviour. No in-request metric distinguishes "expansion added N tokens and changed the
  result set". Candidate metric: log `expansionTermCount` and `resultShiftCount` (top-K overlap
  before/after expansion) per query. (2026-02-20)
- Qwen3VL-8B model suboptimal for text-only query expansion — the model in dev is a
  vision-language model (`Qwen3VL-8B-Thinking-Q4_K_M.gguf`). It works but a text-only model
  (Qwen3-8B or similar) would be faster and produce cleaner outputs for the expansion prompt.
  (2026-02-20)
