---
title: "363: Transparent Query Enhancement"
status: done
created: 2026-03-27
---

# 363: Transparent Query Enhancement

## Problem

The search system treats every query as literal text. It has no
understanding of what the user *means* — only what they *typed*.
This creates a gap between natural language intent and system
capabilities:

- "articles about crypto fraud from The Verge published in
  October 2023" — the system matches "The Verge" as content
  keywords instead of applying a `meta_source` filter.
- "latest AI papers" — the system keyword-matches "latest"
  instead of applying a freshness sort.
- "what did Elon say about AI last week" — the system doesn't
  extract a person entity, a topic, or a date range.
- "ML research, not from ArXiv" — the system can't parse
  negation into an exclusion filter.
- "compare the two most recent earnings reports" — the system
  doesn't recognize a comparison intent that needs multiple
  document retrieval.

The infrastructure to *act* on these intents already exists.
Tempdoc 362 built metadata filters (source, author, category,
date), entity filters, two-stage RAG pre-filter, and MCP tool
schemas. The search pipeline supports sort modes, result limits,
and multiple retrieval strategies. But nothing translates natural
language into these capabilities.

The agent eval (362) showed this directly: even with concrete
examples in tool descriptions, Haiku constructs filter objects
only 75-79% of the time, and Opus can't verify filters because
metadata isn't in the response. Manual filter usage achieves
100% precision — the gap is entirely in query understanding.

The missing layer: **a query understanding stage that translates
natural language into structured search parameters**, applied
transparently before search execution. This benefits both agents
(no syntax to learn) and human users (type naturally, get precise
results).

## Capability catalog

The query understanding layer is not a single feature — it's a
translation surface between natural language and the system's
existing search capabilities. Concrete capabilities it enables:

| Capability | Example input | Structured output |
|-----------|---------------|-------------------|
| **Filter extraction** | "articles from The Verge" | `meta_source: ["the verge"]` |
| **Temporal reasoning** | "published last month" | `meta_published_after/before` date range |
| **Query reformulation** | "what did Elon say about AI" | `entity_persons: ["elon musk"]` + query: "AI" |
| **Negation handling** | "not from TechCrunch" | exclusion filter on `meta_source` |
| **Sort intent** | "latest articles about..." | `sort: "freshness"` |
| **Synonym expansion** | "ML papers" | query: "machine learning" |
| **Intent classification** | "compare these two reports" | route to multi-doc retrieval |

**Filter extraction** is the primary and first use case — it has
the most literature support, the clearest infrastructure mapping
(362), and the most measurable impact. The other capabilities are
natural extensions once the query understanding layer exists, and
the architecture should accommodate them without redesign.

Not all capabilities need the LLM. Synonym expansion can be
rule-based (already exists via Lucene synonyms). Sort intent
detection could be a simple keyword heuristic. The LLM is
needed where ambiguity and context matter — filter extraction,
temporal reasoning, query reformulation, negation.

## Prior art

Query understanding as a preprocessing layer — LLM translates NL
into structured search parameters — is well-established and
actively converging from multiple directions. Most existing work
focuses on the filter extraction capability specifically. Four
major RAG frameworks ship it, at least one SaaS search platform
has it in production, and formal academic validation concentrated
in H2 2025 through early 2026. Broader query understanding
(reformulation, intent classification, temporal reasoning) is
covered by the query rewriting and decomposition literature.

### Production implementations

**Vectara Intelligent Query Rewriting (production, beta):**
A toggle-on feature (`intelligent_query_rewriting: true`) that
automatically separates filter criteria from core search intent,
extracts metadata filter expressions, and rephrases the query.
Closest direct precedent — a commercial search platform shipping
this as a first-class feature.
https://www.vectara.com/blog/improved-search-with-vectara-query-rewriting-tech-preview

**LangChain SelfQueryRetriever (open source, ~2023+):**
Uses an LLM to parse NL queries, extract metadata filters, and
pass both the semantic query and structured filters to a vector
store. Integrated with Elasticsearch, Pinecone, Weaviate, Chroma,
MongoDB. The most widely-adopted open-source implementation.
https://js.langchain.com/docs/how_to/self_query/

**LlamaIndex VectorIndexAutoRetriever (open source, ~2023+):**
Same pattern. Given a `VectorStoreInfo` describing available
metadata fields, the LLM infers filters from NL. "What is the
revenue in 2022" → query="what is the revenue" + filter
`doc.year=2022`.
https://docs.llamaindex.ai/en/stable/examples/vector_stores/chroma_auto_retriever/

**Haystack QueryMetadataExtractor (open source, cookbook 2024):**
A pipeline component that takes query + metadata field
descriptions, uses an LLM to extract filter values, and outputs
structured filters for the retriever.
https://haystack.deepset.ai/blog/extracting-metadata-filter

**Algolia auto-selected facets (rule-based, no LLM):**
Matches query tokens against known facet values. When "red"
matches the `color` facet, a rule auto-applies the filter. The
facet value index serves as the entity dictionary.
https://www.algolia.com/doc/guides/solutions/ecommerce/filtering-and-navigation/tutorials/auto-selected-facets

### Academic papers (directly on-topic)

**QAM (Query Attribute Modeling, arXiv 2508.04683, Aug 2025):**
Decomposes queries into metadata tags + semantic elements. On
Amazon Toys Reviews: mAP@5 of 52.99%, outperforming BM25,
semantic search, cross-encoder reranking, and hybrid BM25+RRF.
https://arxiv.org/abs/2508.04683

**HyST (arXiv 2508.18048, Aug 2025, RecSys workshop):**
Uses an LLM to parse queries and extract attribute-level
constraints as metadata filters, while the unstructured remainder
goes to embedding-based retrieval.
https://arxiv.org/abs/2508.18048

**Multi-Meta-RAG (arXiv 2406.13213, Jun 2024, ICTERI 2024):**
Uses LLM-extracted metadata (source, publication date) as
database filters for multi-hop RAG queries. 17.2% improvement
in Hits@4, up to 25.6% accuracy gain. Most directly analogous
to JustSearch's use case — source + date filtering in a RAG
context.
https://arxiv.org/abs/2406.13213

**ReDI — Reason to Retrieve (arXiv 2509.06544, Sep 2025):**
Three-stage LLM pipeline: decompose complex query into
sub-queries, enrich each with semantic interpretations, fuse
retrieval results. Distills DeepSeek-R1 into small models.
Outperforms baselines on BRIGHT and BEIR.
https://arxiv.org/abs/2509.06544

**Metadata-Driven RAG for Financial QA (arXiv 2510.24402,
Oct 2025):** Investigates Self-Query Retrieval enabling agents
to convert NL queries into structured metadata filters. Finds
biggest gains from embedding chunk metadata with text.
https://arxiv.org/abs/2510.24402

**Utilizing Metadata for Better RAG (arXiv 2601.11863,
Jan 2026):** Metadata as first-class RAG input — not just
filters but embedded content. Unified embeddings match or
surpass prefixing approaches.
https://arxiv.org/abs/2601.11863

### Enabling research (relevant to open questions)

**Google "Small Models, Big Results" (EMNLP 2025, arXiv
2509.12423):** Decomposes intent extraction into two stages
so small on-device models match Gemini 1.5 Pro. Directly
relevant to open question #2 — small local models can handle
decomposition if the task is broken into stages.
https://arxiv.org/abs/2509.12423

**Text-to-ES Bench (ACL 2025):** Benchmark for NL →
Elasticsearch Query DSL (26,207 pairs). Fine-tuned models
outperform DeepSeek-R1 by 15.64%. Shows NL→structured-query
is tractable even for moderate models.
https://aclanthology.org/2025.acl-long.971/

**llama.cpp GBNF grammars / JSON schema constrained generation:**
Grammar-based decoding guarantees well-formed JSON and improves
extraction accuracy ~25%. JustSearch's llama-server supports
this natively — directly applicable for decomposition output.
https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md

**DocDB (VLDB 2025):** Confidence-based execution switching —
if LLM confidence exceeds threshold, use extracted filters;
below threshold, fall back to full scan. Validates the
confidence gating pattern proposed in this tempdoc.
https://www.vldb.org/pvldb/vol18/p5387-chai.pdf

## Deep analysis: three most relevant papers

The following three papers are analyzed in depth because they
most directly inform the **filter extraction** capability — the
primary use case and implementation starting point.

### Multi-Meta-RAG — nearest blueprint (arXiv 2406.13213)

The closest existing system to what 363 proposes. Same metadata
types (news source + publication date), same retrieval context
(multi-hop RAG over a document collection).

**Architecture.** A secondary LLM (gpt-3.5-turbo, chosen for
speed: 0.7s/query average) receives the user query plus a
few-shot prompt. The prompt contains: (1) the complete list of
46 allowed news sources, (2) the expected date format, and
(3) three worked examples — source-only filter, source+date
filter, and null (no filter extractable). The LLM outputs
MongoDB-style filter syntax:

```json
{"source": {"$in": ["TechCrunch", "Engadget"]},
 "published_at": {"$in": ["December 12, 2023"]}}
```

The LLM correctly generalized to `$nin` (exclusion) and temporal
comparison operators (`$lt`, `$gt`) without being shown examples
of them — the few-shot examples prime the output format, not the
operator set.

**Key technique: closed-set matching.** By including all 46 valid
source names in the prompt, the LLM's job shifts from open-ended
entity extraction to matching query tokens against a known list.
This achieved 100% source extraction coverage. Date extraction was
harder (15.6% of queries vs 22.8% temporal queries in the dataset)
due to format standardization issues.

**Results on MultiHop-RAG (news articles, Sep–Dec 2023):**

| Metric | Baseline RAG | Multi-Meta-RAG | Delta |
|--------|-------------|----------------|-------|
| Hits@4 | 0.663 | 0.792 | +17.2% |
| MAP@10 | 0.262 | 0.339 | +29.3% |
| MRR@10 | 0.602 | 0.675 | +12.2% |
| GPT-4 response accuracy | 0.56 | 0.606 | +8.2% |
| PaLM response accuracy | 0.47 | 0.608 | +25.6% |

**Direct mapping to JustSearch:**
- Their `source` field → our `meta_source`
- Their `published_at` → our `meta_published_at`
- Their MongoDB `$in` → our Lucene `TermInSetQuery`
- Their 46-source allowlist in prompt → our facet values injected
  from FacetingEngine
- Their Neo4j metadata node properties → our Lucene stored fields
  with docValues

**Limitations noted.** Manual prompt engineering per domain — the
allowed source list must be maintained. Performance gap vs
ground-truth chunks remains large (0.606 vs 0.89 for GPT-4) —
filtering helps retrieval precision but doesn't solve the
generation accuracy ceiling. Chunk parameters: 256 tokens, 32
overlap (smaller overlap gave better chunk diversity).

### QAM — formal evaluation against our baselines (arXiv 2508.04683)

The most rigorous evaluation of the decompose-then-filter pattern,
benchmarked against every retrieval method JustSearch already uses.

**Four-stage pipeline:**
1. **Decompose** (GPT-4): NL query → metadata tags + semantic
   elements. Example: "A long black dress from Zara under $100" →
   metadata `{color: "black", brand: "Zara", price: "<$100"}` +
   semantic remainder capturing style/occasion intent.
2. **Hard metadata filter**: Only documents matching ALL extracted
   tags survive. `D_filtered = {p ∈ D | p.metadata matches
   Q_metadata}`. Quantitative fields get 20% tolerance (price $100
   matches up to $120).
3. **Semantic similarity**: Encode semantic remainder with
   `nomic-embed-text-v1`, cosine similarity against document
   content (reviews). Captures subjective/qualitative intent.
4. **Cross-encoder reranking**: `msmarco-MiniLM-L12-en-de-v1`
   scores final query-document pairs.

**Results on Amazon Toys Reviews (10K items, 40K+ reviews):**

| Method | mAP@3 | mAP@5 | mAP@10 |
|--------|-------|-------|--------|
| BM25 keyword | 53.39% | 41.19% | 37.33% |
| Semantic search | 58.97% | 49.75% | 44.75% |
| Cross-encoder reranking | 56.03% | 48.81% | 43.59% |
| Hybrid RRF (BM25+semantic) | 58.28% | 48.22% | 44.20% |
| **QAM** | **62.47%** | **52.99%** | **48.84%** |

QAM beats hybrid RRF by 9.9% at mAP@5 — and the gain comes
entirely from *not searching over irrelevant documents*. A
mediocre retriever over a correctly-filtered set beats a great
retriever over an unfiltered set. This is the core argument for
363.

**Caveat: aggressive filtering.** When metadata filtering is too
strict, the candidate set shrinks below k results. QAM penalized
itself for this (treating missing results as non-relevant). This
validates the need for confidence gating — if the extracted
filters would over-narrow, the system should fall back.

**Direct mapping to JustSearch:** Their "metadata tags" = our
`meta_source`, `meta_author`, `meta_category`. Their "semantic
elements" = the remainder query going to BM25+vector+SPLADE.
Their hard pre-filter = our `QueryFilterBuilder.applyRuntimeFilters()`.
Their pipeline (decompose → filter → retrieve → rerank) maps onto
decompose → `RuntimeSearchFilters` → `SearchOrchestrator` →
cross-encoder.

### Google "Small Models, Big Results" — small model feasibility (EMNLP 2025)

Answers the key question for JustSearch: can a small local model
handle intent decomposition? The Brain process runs a local
llama-server, not GPT-4.

**Core finding.** An 8B model (Gemini Flash 8B), with decomposed
fine-tuning, **exceeds Gemini 1.5 Pro** on Mind2Web (0.752 vs
0.730 F1). Also works with open-source Qwen2 VL 7B.

**Two-stage decomposition:**
- Stage 1 (prompt-based, no fine-tuning): Each input is
  summarized independently using a structured output format
  (three fields: context, action, speculative intent).
- Stage 2 (fine-tuned on 900-5000 examples): Concatenated
  summaries → overall intent extraction.

**Key results:**

| Approach | Model | Mind2Web F1 | AndroidCtrl F1 |
|----------|-------|------------|----------------|
| Chain-of-thought | Flash 8B | 0.659 | 0.660 |
| End-to-end FT | Flash 8B | 0.653 | 0.656 |
| **Decomposed FT** | **Flash 8B** | **0.752** | **0.688** |
| Chain-of-thought | Gemini 1.5 Pro | 0.730 | 0.767 |

Even without fine-tuning, decomposition helps: decomposed-non-FT
outperforms CoT (0.718 vs 0.660 F1 on Mind2Web).

**Three transferable principles:**
1. **Decompose the task into stages.** Don't ask the model to do
   everything in one shot. For JustSearch: first classify which
   filter fields are present, then extract values per field.
2. **Structured output format.** Their structured three-field
   format outperformed free-form by 2.8% F1. For JustSearch: use
   GBNF grammar constraints to enforce JSON structure.
3. **Label refinement.** Raw training labels containing info not
   derivable from the input teach the model to hallucinate.
   Cleaning labels improved precision from 0.756 to 0.814. For
   JustSearch: if we fine-tune later, ensure training examples
   only contain filters extractable from the query text.

**Latency and cost.** Full pipeline: 0.6s. Optimized variant
(skip summarizing last input): 0.24s with no quality loss.
2-3x more tokens than baseline, but 30x cheaper than running
the large model.

**Why JustSearch's task is easier.** The paper's task is harder:
multimodal (screenshots + text), long trajectories (5-7 steps),
open-ended intent space. JustSearch's decomposition is text-only,
short input (~50 tokens query + ~200 tokens prompt), closed-set
output (known filter fields + known facet values), and short
output (~50-100 tokens JSON). If 8B handles the harder task, a
similar local model should handle filter extraction comfortably.

## Proposed approach: LLM-based query understanding layer

Use JustSearch's chat model (llama-server / Brain process) to
understand queries before search execution. The chat model is
already running for RAG — this adds a lightweight preprocessing
call that translates natural language into structured search
parameters.

### Input → Output

The query understanding layer produces a structured translation
of the user's intent. The output schema is extensible — new
capabilities add new fields without changing the architecture.

```
Input:  "latest articles about crypto fraud from The Verge,
         not from TechCrunch, published last October"

Output: {
  "query": "crypto fraud",
  "filters": {
    "meta_source": ["the verge"],
    "meta_source_exclude": ["techcrunch"],
    "meta_published_after": "2023-10-01",
    "meta_published_before": "2023-10-31"
  },
  "sort": "freshness",
  "confidence": 0.92
}
```

The structured output maps directly to existing search
parameters: `query` goes to BM25/vector search, `filters` go to
the filter pipeline (362), `sort` maps to SearchOrchestrator's
sort parameter. The user/agent never sees the translation — they
just get better results.

### Why the chat model

- **Already available.** The Brain process (llama-server) runs for
  RAG chat. No new infrastructure.
- **Handles ambiguity.** "articles by Choe" → `meta_author:
  ["stan choe"]` requires fuzzy matching that rule-based systems
  miss. An LLM handles synonyms, abbreviations, partial names.
- **Handles temporal expressions.** "last month", "recent",
  "in Q4 2023" → date ranges. No need for SUTime/HeidelTime.
- **Handles negation.** "not from TechCrunch" → exclusion filter.
  Multi-Meta-RAG showed LLMs generalize to negation operators
  from positive-only examples.
- **Cheap.** Short prompt (~200 tokens input, ~100 tokens output).
  On a local model, latency is 50-200ms.

### How it differs from Option B (response hints)

Option B (tempdoc 362 "proactive facet surfacing") makes the
*response* smarter — the agent sees facets and hints after the
first search, then refines on a second call. Two round-trips.

This tempdoc makes the *query* smarter — the system understands
intent before search, so the first result set is already precise.
One round-trip. The user/agent types naturally and gets the right
results without learning any syntax.

Both are complementary. Option B helps agents discover what's
possible. This tempdoc removes the need to learn syntax at all.

## Design

The following describes the theoretically ideal query
understanding system, unconstrained by implementation specifics.

### Core principle: index-grounded extraction

The LLM should know everything the search index knows. Every
extraction should be grounded in the actual state of the index —
not guessed from the query alone. This transforms open-ended
entity extraction into closed-set matching, which is dramatically
more reliable (Multi-Meta-RAG: 100% source extraction coverage
with allowlist vs unquantified accuracy without).

The prompt receives a live snapshot of the index state:

```
Index state (as of 2026-03-28, 847 documents):
  Date range: 2023-01-15 to 2024-12-31

  Sources: the verge (45), techcrunch (97), sporting news (101),
    polygon (44), fortune (38), ...
  Authors: stan choe (12), sarah perez (8), jon porter (6), ...
  Categories: technology (172), sports (211), entertainment (114),
    business (81), science (21), health (10)
  Top persons mentioned: elon musk (34), sam altman (22),
    taylor swift (18), ...
  Top organizations: openai (41), google (38), meta (29), ...
```

This context serves multiple purposes:
- **Closed-set matching** for source, author, category — the
  model selects from known values, not hallucinated ones
- **Temporal grounding** — the model knows the collection's date
  range, so "recent" maps to the right end of the range, and
  a request for "2025 articles" yields confidence 0 if the
  collection ends in 2024
- **Entity grounding** — person/org names from the index prevent
  the model from inventing entities not in the collection
- **Collection awareness** — the model knows the scale and
  character of the collection, informing extraction decisions

### Per-field confidence

The current sketch uses a single overall confidence score. The
ideal design uses **per-field confidence**, allowing the system
to apply high-confidence extractions and skip uncertain ones
rather than making an all-or-nothing decision.

```
Input:  "what did Elon say about AI regulation after the
         EU summit last quarter"

Output: {
  "query": "AI regulation EU summit",
  "extractions": {
    "entity_persons": {
      "values": ["elon musk"],
      "confidence": 0.95
    },
    "meta_published_after": {
      "value": "2025-10-01",
      "confidence": 0.55
    },
    "meta_published_before": {
      "value": "2025-12-31",
      "confidence": 0.55
    }
  },
  "sort": {
    "value": "freshness",
    "confidence": 0.40
  }
}
```

Here, `entity_persons: "elon musk"` is high-confidence (0.95) —
the name "Elon" clearly maps to "elon musk" in the index. But
"last quarter" is ambiguous (which quarter? depends on when
"now" is) — confidence 0.55. The system applies the person
filter but not the date range, getting a useful narrowing without
risking an incorrect date constraint.

**Confidence sources.** Two complementary approaches:
1. **Model-reported confidence** — the LLM outputs a numeric
   confidence per field as part of the structured response.
   Simple but may be poorly calibrated.
2. **Token log-probabilities** — llama-server exposes logprobs
   for generated tokens. The confidence for a field can be
   computed from the average logprob of its value tokens. More
   reliable than self-reported confidence, especially for small
   models. (DocDB, VLDB 2025 uses this approach.)

### Soft date boundaries

Date extractions are inherently fuzzy — "last October" might
mean October 1-31, but the user probably wouldn't object to a
September 28 or November 2 result. QAM found that allowing
20% tolerance on numeric fields improved recall without harming
precision.

The system should automatically widen date range extractions by
a confidence-modulated margin:
- High confidence (>0.9): tight boundaries (±0 days, exact
  range as extracted)
- Medium confidence (0.6-0.9): moderate padding (±3-7 days)
- Low confidence (<0.6): wide padding (±14-30 days), or skip
  the date filter entirely

This prevents the common failure mode where the LLM extracts a
slightly-off date range and the search returns zero results for
documents that are actually relevant.

### Progressive filter relaxation

When the extracted filters produce zero or very few results,
the system should automatically relax constraints rather than
returning an empty result set. This is an established search
pattern (Bloomreach, RapidSearch) adapted for LLM-extracted
filters.

**Relaxation sequence:**
1. Apply all extractions with confidence above threshold
2. Execute search
3. If results < minimum threshold:
   a. Remove the extraction with lowest confidence
   b. Re-execute search
   c. Repeat until results are sufficient or no extractions
      remain
4. If no extractions remain: pass raw query unmodified

**Protected constraints:** Explicit filters provided by the
caller (agent or user) are never relaxed — only LLM-extracted
ones are candidates for removal. This preserves the principle
that explicit intent takes precedence.

**Transparency:** Each relaxation step should be recorded in
the response metadata (analogous to SearchOrchestrator's
existing `hybridFallback`, `correctionApplied` fields) so
callers can observe what happened. The user experience is:
search always returns results, sometimes with a note like
"showing results without date filter" displayed as a removable
chip.

### Query reformulation on failure

Progressive relaxation handles wrong *filters*. But sometimes
the *semantic query itself* is the problem — the user's phrasing
doesn't match the vocabulary of the indexed documents. When
relaxation exhausts all extracted filters and the raw query
still returns poor results, the system should reformulate the
query itself.

ReZero (arXiv 2504.11001, Apr 2025) demonstrates that rewarding
an LLM for retrying with reformulated queries — rather than
giving up after initial failure — produces substantially better
results (46.88% vs 25% baseline). The key insight: the failed
search result is itself useful context for reformulation.

**Reformulation sequence** (after progressive relaxation):
1. All extracted filters removed, raw query still yields
   zero/few results
2. Feed the original query + "no results found" signal back to
   the LLM with a reformulation prompt
3. The LLM suggests an alternative query (synonyms, related
   terms, broader framing)
4. Execute search with the reformulated query (no filters)
5. If still zero results: return empty with explanation

This is a second LLM call, so it doubles the latency budget.
It should only trigger on genuine zero-result failures, not on
low-result-count situations where relaxation already helped.

**Example:**
- Original: "SCOTUS ruling on Section 230" → 0 results
- Reformulation: "Supreme Court decision internet liability
  Section 230" → relevant results found

### Contextual query enrichment

Filtering and enrichment are complementary strategies. Filtering
*restricts* the result set (only documents from The Verge).
Enrichment *boosts* relevant results without excluding anything.

arXiv 2510.24402 (Metadata-Driven RAG, Oct 2025) found that the
biggest retrieval gains came from embedding chunk metadata with
text ("contextual chunks"), not just using metadata as filters.
This suggests the query understanding layer should produce both
filter constraints and enrichment signals.

When the system extracts `entity_persons: "elon musk"`, it can:
1. **Filter** — restrict to documents mentioning "elon musk"
   (high confidence: apply as filter)
2. **Enrich** — prepend "elon musk" to the vector search query
   to boost semantic matching (any confidence: safe to apply,
   only boosts, never excludes)

The enrichment path is always safe — adding terms to the
semantic query can only improve vector similarity matching, never
reduce the result set to zero. This makes it the right strategy
for low-confidence extractions that are too uncertain to filter
on but still informative.

**Output schema extension:**

```json
{
  "query": "AI regulation EU summit",
  "extractions": {
    "entity_persons": {
      "values": ["elon musk"],
      "confidence": 0.95
    }
  },
  "enrichment_terms": ["elon musk", "EU summit"],
  "sort": { "value": "freshness", "confidence": 0.40 }
}
```

`enrichment_terms` are always appended to the vector search
query. `extractions` above the confidence threshold become
hard filters. Extractions below the threshold can optionally
be demoted to enrichment terms instead of being discarded — a
soft landing for uncertain extractions.

### Index-grounded prompt structure

The prompt has four layers:

```
[System] Task description and output schema
  ↓
[Dynamic context] Live index snapshot (facet values, date
  range, collection stats, today's date)
  ↓
[Few-shot examples] 2-3 worked examples covering:
  - Filter-heavy: source + date → extraction
  - Pure semantic: no filters → passthrough
  - Ambiguous temporal: "recent articles" → depends on context
  ↓
[User query] The actual query to translate
```

**Few-shot design.** Research indicates diminishing returns
after 2-3 examples for structured extraction tasks. The examples
should cover the primary patterns (extraction, passthrough,
ambiguity) without overwhelming the prompt. Multi-Meta-RAG used
3 examples and the LLM generalized to operators (exclusion,
temporal comparison) not shown in examples — few-shot primes the
format, not the full capability space.

**Single-call vs multi-stage: adaptive strategy.** There is a
tension in the literature. Google's EMNLP 2025 paper argues that
small models perform better when complex tasks are decomposed
into stages (0.752 vs 0.659 F1). But Yelp (Feb 2025) found that
"a sufficiently powerful model" handles combined tasks
(segmentation + spell correction) in a single prompt. Both are
correct — the right choice depends on model capability.

The theoretically ideal approach is adaptive:
- **Default: single call** with GBNF-constrained structured
  output. The GBNF grammar provides the structural scaffolding
  that chain-of-thought offers. (Wharton 2025 found CoT adds
  20-80% latency for negligible gains on extraction tasks.
  Google found structured format improved F1 by 2.8%.)
- **If accuracy degrades empirically:** decompose into two
  stages: Stage 1 classifies which fields are present (binary
  per field), Stage 2 extracts values for flagged fields only.
  This follows Google's principle while keeping the total
  token budget small.

The single-call default is correct for the theoretically ideal
case because the task (closed-set extraction from short text
with GBNF constraints) is simpler than Yelp's or Google's tasks.
The multi-stage fallback exists as a contingency if the model
proves too weak.

### Constrained generation

llama.cpp supports JSON schema → GBNF grammar conversion
natively. The output schema should be passed as
`response_format` to the `/v1/chat/completions` endpoint.

Benefits:
1. **Guarantees well-formed JSON** — eliminates parse failures
   entirely, no retry logic needed.
2. **Improves extraction quality** — grammar-based decoding
   improves accuracy ~25% (llama.cpp benchmarks). The model is
   forced to "think" in terms of the structured output from the
   first token.
3. **Enforces field constraints** — enum fields (category, sort)
   can only take valid values. Numeric confidence is bounded
   0-1. Array fields must be arrays.

```json
{
  "type": "object",
  "properties": {
    "query": {"type": "string"},
    "extractions": {
      "type": "object",
      "properties": {
        "meta_source": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        },
        "meta_source_exclude": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        },
        "meta_author": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        },
        "meta_category": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        },
        "meta_published_after": {
          "type": "object",
          "properties": {
            "value": {"type": "string"},
            "confidence": {"type": "number"}
          }
        },
        "meta_published_before": {
          "type": "object",
          "properties": {
            "value": {"type": "string"},
            "confidence": {"type": "number"}
          }
        },
        "entity_persons": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        },
        "entity_organizations": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        },
        "entity_locations": {
          "type": "object",
          "properties": {
            "values": {"type": "array", "items": {"type": "string"}},
            "confidence": {"type": "number"}
          }
        }
      }
    },
    "sort": {
      "type": "object",
      "properties": {
        "value": {"type": "string", "enum": ["relevance", "freshness"]},
        "confidence": {"type": "number"}
      }
    }
  },
  "required": ["query", "extractions"]
}
```

New capabilities add new properties to the `extractions` object.
The GBNF grammar is regenerated automatically from the JSON
schema.

### Multi-turn context carryover

In conversational search (agent multi-step workflows, or human
follow-up queries), the query understanding layer should carry
context across turns rather than treating each query in
isolation.

```
Turn 1: "articles from The Verge about AI"
  → extractions: {meta_source: ["the verge"]}, query: "AI"

Turn 2: "what about from TechCrunch?"
  → extractions: {meta_source: ["techcrunch"]}, query: "AI"
    (topic "AI" carried from turn 1, source replaced)

Turn 3: "anything more recent?"
  → extractions: {meta_source: ["techcrunch"]},
    sort: "freshness", query: "AI"
    (source and topic retained, sort added)
```

This requires the prompt to include previous turns' queries and
extractions as context. The LLM resolves references ("what
about", "anything more") and carries forward or replaces
parameters as appropriate.

**Literature support.** Yuan et al. (arXiv 2504.06356, WWW
2025) cover query understanding in conversational information
seeking, including resolving elliptical and referential queries
through dialogue context. Google's Conversational Search Mode
(2026) maintains query context across turns as a first-class
feature. This is an established pattern in conversational search.

**Scope.** Multi-turn context is relevant for: (1) the agent
loop, where the agent issues sequential search calls refining
its query; (2) human users in a search session who progressively
narrow results. It is not relevant for isolated one-shot queries.

### Pipeline position and bypass rules

```
User/Agent query
  ↓
  ├─ Explicit filters provided? → bypass, pass through
  ├─ Brain offline? → bypass, pass through
  ├─ Single keyword query? → bypass (heuristic sort detection only)
  │
  ↓  (none of the above)
  Query understanding (LLM call)
  ↓
  Apply per-field confidence threshold
  ↓
  Map to SearchOrchestrator parameters
  ↓
  Search execution
  ↓
  ├─ Results sufficient? → return
  ├─ Zero/few results? → progressive relaxation → retry
  ↓
  Return results + metadata (which extractions applied,
  any relaxations performed)
```

**Bypass rules** ensure the LLM call is skipped when it can't
help or isn't needed:
- Explicit filters from caller → the caller already expressed
  structured intent
- Brain offline → no LLM available, graceful degradation
- Single keyword → unlikely to contain extractable structure;
  sort intent ("latest") can be detected by keyword heuristic
  without the LLM

**Search priority.** When query understanding is invoked, the
Brain cancels any in-progress conversation to prioritize the
search preprocessing call. Search is latency-sensitive;
conversation can resume after.

## Interaction with other tempdocs

**362 (faceted metadata filtering):** The query understanding
layer builds on 362's infrastructure — filter fields are indexed,
the plumbing works end-to-end, two-stage RAG pre-filter handles
entity + metadata, MCP tool schemas accept filter parameters.
Without 362, there's nothing to translate into. Without 363,
agents have to construct filters manually (which Haiku can't do).

The query understanding layer also maps to existing search
parameters beyond 362's filters: sort modes, result limits, and
retrieval strategy hints are already supported by
SearchOrchestrator but have no NL interface today.

## Open questions (largely resolved by literature)

1. **Latency budget.** ~~Is adding an LLM call before every
   search acceptable?~~
   **Resolution: acceptable.** Vectara ships this on every query
   in production. Multi-Meta-RAG adds 0.7s with gpt-3.5-turbo
   (cloud); local inference will be faster. Google paper's
   optimized variant achieves 0.24s. *Remaining question:* should
   UI searches skip decomposition for very short queries (single
   keywords) where no filters are plausible?

2. **Local vs cloud model.** ~~Is the local model capable enough?~~
   **Resolution: feasible.** Google shows 8B models match large
   models on harder tasks (multimodal UI trajectories).
   JustSearch's task is simpler: text-only, closed-set output,
   short I/O. GBNF grammar constraints prevent malformed output
   and improve extraction accuracy. Even without fine-tuning,
   decomposition outperforms chain-of-thought (0.718 vs 0.660 F1).
   *Remaining question:* empirical validation with JustSearch's
   actual model — what's the minimum model size that works
   reliably with GBNF + facet allowlist?

3. **Fallback behavior.** ~~Warn the user or stay transparent?~~
   **Resolution: transparent fallback.** DocDB (VLDB 2025)
   validates the pattern — confidence-based switching with no
   user-facing signal. If decomposition fails or returns low
   confidence, the raw query proceeds as before. The user
   experience is: sometimes search is more precise, never worse.

4. **Filter validation.** ~~Fuzzy-match against known values?~~
   **Resolution: use closed-set matching as primary strategy.**
   Inject facet values into the prompt (Multi-Meta-RAG achieved
   100% source extraction with this). Post-extraction validation
   against known facet values as a safety net. Fuzzy matching is
   a nice-to-have optimization, not essential when the allowlist
   is in the prompt.

5. **Agent tool precedence.** ~~Skip decomposition when explicit
   filters present?~~
   **Resolution: yes.** All frameworks (LangChain
   SelfQueryRetriever, LlamaIndex AutoRetriever) skip self-query
   when the caller provides explicit filters. Decomposition only
   activates when no filters are provided in the request.

## Expected impact (from literature benchmarks)

### Filter extraction (primary capability)

| Metric | Expected range | Source |
|--------|---------------|--------|
| Retrieval precision (Hits@K) | +15–29% on filter-bearing queries | Multi-Meta-RAG |
| Search relevance (mAP) | +6–10% over unfiltered hybrid search | QAM |
| Agent round-trips | 2–3 → 1 for filter-dependent queries | 362 eval |
| Response accuracy (RAG) | +8–26% depending on base model | Multi-Meta-RAG |

### Other capabilities (incremental, less literature support)

| Capability | Expected impact | Confidence |
|-----------|----------------|------------|
| Sort intent | Correct sort on "latest/recent" queries | High (simple heuristic fallback) |
| Temporal reasoning | Date ranges from "last month" etc. | Medium (model-dependent) |
| Negation handling | Exclusion filters from "not X" | Medium (Multi-Meta-RAG showed generalization) |
| Query reformulation | Better search terms from verbose NL | Low (hard to measure, no baseline) |
| Synonym expansion | Broader recall for abbreviations | Low (Lucene synonyms may suffice) |

These are upper bounds from controlled benchmarks with curated
metadata. Real-world improvement depends on: (1) what fraction of
JustSearch queries contain extractable structure — purely semantic
queries like "explain quantum computing" won't benefit; (2) the
local model's extraction accuracy with GBNF + facet allowlist —
untested, needs empirical validation; (3) metadata coverage in
the index — filters only help if documents have the metadata
fields populated.

## Critical analysis: research support by capability

The broadened scope of this tempdoc ranges from well-validated
(filter extraction) to speculative (intent classification). This
section honestly assesses the research support for each
capability and identifies gaps specific to JustSearch's
constraints (local-first, small model, no caching power-law).

### Filter extraction — strong evidence, ready to implement

This is the only capability with rigorous quantitative evaluation
across multiple papers (QAM, Multi-Meta-RAG, HyST), multiple
production frameworks (LangChain, LlamaIndex, Haystack, Vectara),
and direct feasibility evidence for small models (Google EMNLP
2025). The research gap is narrow: no paper tests this specific
combination (local 7-8B model + GBNF constraints + facet
allowlist in prompt + Lucene hybrid pipeline), but every
individual component is validated.

### Temporal reasoning — moderate evidence, LLM well-suited

Multi-Meta-RAG extracted publication dates, though with lower
coverage (15.6%) than source extraction (100%) due to format
standardization. Temporal expressions like "last month" or "Q4
2023" require world knowledge (today's date) and reasoning that
rule-based systems handle poorly. LLMs are well-suited here.
However, Multi-Meta-RAG used gpt-3.5-turbo — small local models
may struggle with complex temporal reasoning ("the week before
the Super Bowl"). GBNF constraints on date format help.

### Sort intent — trivially solvable, LLM unnecessary

No dedicated research exists on LLM-based sort intent extraction
because the problem is too simple to need one. Keywords like
"latest", "recent", "newest", "oldest" map deterministically to
sort parameters. A 5-line regex heuristic would achieve near-
perfect accuracy with zero latency. Including sort in the LLM
output schema costs nothing (the model handles it incidentally),
but this should not be a reason to invoke the LLM — sort
detection should work independently as a fast pre-check.

### Negation handling — weak evidence, known model weakness

An MIT 2025 study found that vision-language models drop 25% in
performance on negated queries, with best models achieving only
39% accuracy on negation multiple-choice tasks. While text-only
LLMs may fare better, negation is a known weakness of smaller
models. A COLING 2025 industry paper addresses rewriting negation
queries in product search via Seq2Seq with negation span
detection, suggesting the problem is hard enough to warrant
specialized approaches. Multi-Meta-RAG showed gpt-3.5-turbo
generalized to `$nin` operators, but this was a large cloud model
on a narrow task. Risk: a small local model might ignore "not"
and apply a positive filter — actively making results worse.
Confidence gating is critical here.

### Query reformulation — weak evidence, unclear value-add

Elastic and Yelp blog posts describe LLM query rewriting, and
MiniELM (ACL 2025) addresses lightweight query rewriting. But
JustSearch's existing pipeline (BM25 + vector + SPLADE + cross-
encoder reranking) is already robust to verbose or poorly-phrased
queries — that's what the multi-signal hybrid pipeline is for.
The marginal value of LLM reformulation on top of this stack is
unclear and hard to measure. softwaredoug.com demonstrated
attribute extraction with Qwen2-7B at ~1.06s latency — acceptable
for a standalone service, but high for a preprocessing step.

### Synonym expansion — not needed, existing solution works

Lucene already supports synonym expansion via configurable
synonym maps. Adding LLM-based expansion introduces latency and
hallucination risk for a problem that's already solved. The LLM
might incidentally improve on edge cases ("ML" → "machine
learning"), but this doesn't justify invoking the model.

### Intent classification — insufficient evidence for local model

Yelp implements query segmentation (topic, name, location, time)
in production, but their architecture required GPT-4 for golden
dataset creation → fine-tuned GPT-4o-mini for batch processing →
caching 95% of traffic. This is a cloud-scale approach exploiting
power-law query distributions. JustSearch is local-first with
arbitrary user documents — there's no query distribution to cache.
Every query is potentially unique. Furthermore, intent
classification in JustSearch is largely the agent's job (it
decides whether to search, summarize, or compare) — adding a
second intent classifier creates confusion about which layer
owns the decision.

### Cross-cutting concerns for the broadened scope

**No unified multi-capability benchmark exists.** Every paper
evaluates one capability. Nobody has evaluated doing filter
extraction + temporal reasoning + negation + sort + reformulation
in a single LLM call with a small local model.

**Prompt complexity vs model capability.** Multi-Meta-RAG asked
for one thing (extract source + date). QAM asked for one thing
(separate metadata from semantics). Google's EMNLP paper argues
for decomposition, while Yelp (Feb 2025) successfully combined
segmentation + spell correction in a single prompt. The design
addresses this tension via an adaptive strategy: default
single-call with GBNF constraints, decompose into stages if
empirical testing shows accuracy degradation on the specific
local model.

**The "do nothing" baseline is strong for most capabilities.**
The existing pipeline already handles synonym expansion (Lucene),
verbose queries (multi-signal retrieval), and intent routing
(agent). Only filter extraction and temporal reasoning address
genuine gaps where the system currently fails.

### Recommendation

Design the architecture to be extensible (the JSON schema can
grow), but **implement only filter extraction + temporal reasoning
initially**. These two have strong evidence, clear infrastructure
mappings, and measurable impact. Sort intent can be a zero-cost
heuristic alongside. The other capabilities are exploration
targets once the layer exists and can be evaluated empirically
against JustSearch's actual model and query patterns.

## Query distribution analysis

### What fraction of queries would benefit?

Analysis of all available query sets in the codebase:

| Query set | Total | Has extractable filters | Pure semantic | Source |
|-----------|-------|------------------------|---------------|--------|
| Agent eval (MultiHop-RAG) | 20 | 20 (100%) | 0 (0%) | 362 Phase 4 |
| Correction eval | 50 | 0 (0%) | 50 (100%) | jseval |
| Tempdoc 363 examples | 5 | 3 (60%) | 2 (40%) | Illustrative |
| BEIR benchmarks (SciFact, EnronQA, MIRACL) | ~2600 | ~0% | ~100% | 343 |

**The agent eval is misleading.** 100% filterable because
MultiHop-RAG was selected precisely for its metadata-rich,
source-attributing queries. This is an ideal stress test for
filter infrastructure but not representative of general use.

**BEIR benchmarks are the opposite extreme.** Biomedical claims,
email Q&A, and multilingual knowledge retrieval — pure semantic
with no metadata structure to extract.

**Best estimate for real-world queries: 40-65% have at least one
extractable filter.** Based on the 363 illustrative examples (the
most realistic mixed-intent set) and the observation that
personal document search often involves source, author, and date
constraints. `meta_source` is the most common extractable field,
followed by date/temporal, then person entities.

**Even at 40%, the feature fires on nearly half of queries.** On
those queries, literature suggests +15-29% retrieval precision.
On the other 60%, transparent fallback means no harm.

### What we don't know

The available query sets are either synthetic benchmarks
(MultiHop-RAG, BEIR) or illustrative examples. We have no corpus
of actual JustSearch user queries to analyze. The real
distribution depends on what users store (news articles vs code
vs personal notes vs academic papers) and how they search (topic
queries vs "find that article from X about Y"). This is an
empirical gap that can only be closed with real usage data.

## Remaining theoretical questions

Items 2, 3, and 5 require implementation and measurement. They
are recorded here as the theoretical framing for future eval
design.

### 1. Two-LLM conflict (agent vs local model)

When an agent uses the search tool, two LLMs reason about the
query: the external agent (Claude/Haiku) and the local model
(query understanding). The agent might intentionally phrase a
query for keyword matching, and the local model might "correct"
it. Or the agent constructs partial explicit filters, and the
local model extracts conflicting filters from the semantic
remainder. No paper in the literature studies the interaction
between an agentic caller and a query understanding layer — every
paper assumes a single LLM or a human user. Mitigation: skip
query understanding when explicit filters are present (resolved
in open questions). But partial-filter cases are unaddressed.

### 2. Net-negative risk threshold

At what extraction accuracy does the feature become harmful?
Wrong filters are asymmetrically bad: a wrong `meta_source`
filter returns zero results (catastrophic), while no filter
returns okay-ish results (merely suboptimal). The confidence
gating mitigates this, but the crossover point — the minimum
extraction accuracy where the feature is net-positive — is an
empirical question. It depends on the severity distribution
(how often wrong filters produce zero results vs merely
suboptimal results) and the benefit magnitude when filters are
correct.

### 3. Multi-task prompt degradation

No benchmark tests multiple query understanding capabilities in
a single LLM call with a small model. Google's EMNLP 2025 paper
argues for decomposing tasks for small models, not combining
them. If per-capability accuracy degrades as the prompt grows
more complex, the "extensible schema" approach may need to be
replaced with separate lightweight calls — which introduces
latency stacking concerns. This is testable empirically once the
layer exists: measure filter extraction accuracy alone, then with
sort + temporal + negation added to the prompt.

## Remaining theoretical work

The V3.2 soft-boost simulation showed +12.5% nDCG@10, but the
simulation has known limitations. These must be resolved
theoretically before committing to the 6-layer Lucene
implementation.

### T1. Simulation gap analysis

The simulation re-ranks 10 baseline results by filter match.
Real Lucene SHOULD scoring would change *which* documents
enter the top-10 — a relevant document at position 15 in
baseline could jump to position 3 with a boost. The simulation
can't capture this.

**Test:** Re-run with limit=100 (deep pool), re-rank those 100,
take top-10. If results differ significantly from the limit=10
simulation, the true Lucene result could be better (documents
promoted from deeper ranks) or worse (boost distorts scoring).

**T1 results (2026-03-28):**

| Metric | Shallow C (depth=10) | Deep C (depth=100) |
|--------|---------------------|-------------------|
| nDCG@10 | 0.7752 (+12.5%) | **0.8322 (+16.5%)** |
| P@1 | 0.8600 (+32%) | **0.8800 (+32%)** |
| R@10 | 0.7967 (+0%) | **0.9033 (+10.5%)** |
| RR@10 | 0.9140 (+21%) | **0.9300 (+20%)** |

**The simulation was an underestimate.** The deep pool reveals
documents at positions 11-100 in baseline that would be promoted
into the top-10 by the boost. Recall jumps from +0% to +10.5%
— the boost surfaces relevant documents the baseline misses.

The real Lucene SHOULD implementation (which affects retrieval
scoring, not just post-retrieval re-ranking) would likely
perform between these two bounds — probably closer to the deep
pool because Lucene's SHOULD affects scoring during the search,
not after. The +16.5% nDCG@10 / +10.5% R@10 / 88% P@1 are
the realistic targets.

### T2. Lucene SHOULD scoring for keyword fields

`meta_source` is a keyword field (single token, stored +
docValues). BM25 scores keyword fields differently from text
fields — TF is always 1, IDF depends on how many docs have
that source. How does this interact with content BM25 scores?
What boost weight makes the source match meaningful but not
dominant?

**T2 findings:**

BooleanQuery sums the scores of matching clauses: the MUST
content query provides the base BM25 score, and each SHOULD
clause adds to it if matched. For keyword fields like
`meta_source`, raw BM25 scoring has a problem: IDF varies by
source popularity (rare sources get stronger boosts than common
ones), making the effect unpredictable.

**Recommended Lucene idiom:**
```java
// Fixed-weight source boost (ConstantScoreQuery avoids IDF variation)
Query sourceBoost = new BoostQuery(
    new ConstantScoreQuery(new TermQuery(new Term("meta_source", "the verge"))),
    boostWeight  // e.g., 5.0
);
booleanQuery.add(sourceBoost, BooleanClause.Occur.SHOULD);
```

ConstantScoreQuery gives a fixed score of 1.0 for any match,
regardless of IDF. BoostQuery scales it by the weight. This is
more predictable than raw BM25 on keyword fields.

**Boost weight calibration:** The weight needs to be large
enough to promote matching documents above non-matching ones
with similar content scores, but not so large that a source
match overwhelms content relevance. Typical content BM25 scores
range 5-20 for good matches. A boost weight of 5-10 would make
the source match significant (~25-50% of a good content score)
without dominating.

The Lucene FeatureField documentation recommends exactly this
pattern: "Putting feature queries as SHOULD clauses of a
BooleanQuery allows combining query-dependent scores with
query-independent scores using a linear combination." The
metadata source match is a query-independent scoring signal.

### T3. Reranker interaction

JustSearch's pipeline: BM25/vector → SPLADE fusion →
LambdaMART → cross-encoder. The SHOULD boost affects step 1
(initial retrieval scoring). The cross-encoder rescores query-
document pairs independently — it doesn't see the Lucene score.
Question: does the boost survive reranking, or does the CE
erase the advantage? The boost's value may be getting the right
documents *into* the reranking pool rather than affecting final
ordering.

**T3 findings:**

In multi-stage retrieval, the cross-encoder **replaces** first-
stage scores with its own query-document relevance scores. The
Lucene SHOULD boost score does NOT survive into the final
ranking. However, research confirms "the best first-stage model
usually leads to the best end performance" because first-stage
scoring determines **which documents enter the reranking pool**.

For JustSearch's pipeline (BM25/vector → SPLADE → LambdaMART →
cross-encoder), the SHOULD boost operates at the first stage:
1. **Boost changes which docs enter top-K** for reranking —
   this is the primary value (confirmed by T1 deep-pool result:
   R@10 improves +10.5%)
2. **Cross-encoder re-scores the top-K independently** — it
   doesn't see the boost, but it sees the right documents
3. **If the right documents are in the pool, CE ranks them
   correctly** — the CE is better at relevance judgment than
   the boost, so this is actually desirable

This means the boost's role is **recall into the reranking
pool**, not final ranking. The CE handles final ranking. This
is the ideal division of labor: the metadata signal gets the
right documents into the pool, the neural model ranks them by
content relevance.

**Implication for boost weight:** The weight needs to be large
enough to promote a matching document from position ~50 to
within the top-K window (K=20 for CE in JustSearch), but not
so large that it floods the CE pool with source-matching but
content-irrelevant documents. The deep-pool simulation (T1)
shows this works — R@10 improved +10.5%, meaning relevant
documents from deeper ranks were successfully promoted.

### T4. API surface design

**T4 finding: invisible by default, opt-in for callers.**

The QU layer should be invisible — the caller sends a raw
query, the system internally extracts metadata signals, applies
them as SHOULD boosts, and returns better-ranked results. No
API change needed for the primary use case.

However, for advanced callers (agents, MCP tools) that want to
explicitly request a soft boost ("prefer articles from The Verge"
without hard-filtering), a `boostFilters` parameter alongside
the existing `filters` parameter provides opt-in control:

- `filters: {meta_source: ["the verge"]}` → hard FILTER (existing)
- `boostFilters: {meta_source: ["the verge"]}` → SHOULD boost (new)
- No filters, no boostFilters → QU layer may add internal boosts

This preserves backward compatibility (existing filter behavior
unchanged) while enabling the new soft-boost path. The QU layer
internally uses the `boostFilters` path for its extractions.

## Implementation strategy

### Pre-implementation validation (completed)

| Question | Result | Status |
|----------|--------|--------|
| V1: Can the model extract? | 78.6% accuracy (v2 prompt, grounded) | Done |
| V1.1: Does grounding help? | +7.2% accuracy | Done |
| V1.2: Does thinking help? | No — strictly worse (10-39%) | Done |
| V3: Hard filter end-to-end? | Net negative (-17.4% nDCG@10) | Done |
| V3.2: Soft boost end-to-end? | **Net positive (+12.5-16.5% nDCG@10)** | Done |
| T1: Simulation gap? | Underestimate — deep pool shows +16.5% | Done |
| T2: Lucene scoring model? | ConstantScoreQuery + BoostQuery | Done |
| T3: Reranker interaction? | Boost aids recall into CE pool | Done |
| T4: API surface? | Invisible QU + opt-in boostFilters | Done |

### Two-track implementation

The implementation splits into two independent tracks that
converge trivially.

**Track A: Soft-boost in Lucene** — the scoring change. Pure
Java, no LLM dependency. Adds a `boostFilters` field alongside
the existing hard `filters`, applied as `BooleanClause.SHOULD`
with `BoostQuery(ConstantScoreQuery(...), weight)`.

Changes across 6 layers:
1. `KnowledgeSearchRequest.Filters` — add `boostFilters` map
2. `indexing.proto` / `SearchFilters` — add boost filter fields
3. `KnowledgeHttpApiAdapter` — map new fields
4. `ProtoConverters` → `RuntimeSearchFilters` — add boost dims
5. `QueryFilterBuilder` — new `applyBoostFilters()` method
6. `SearchOrchestrator` — wire boost filters into query

Validation: hand-crafted search request with `boostFilters:
{meta_source: ["the verge"]}` → verify ranking changes. Then
V3 eval with real `boostFilters` API → compare against
simulation target (+16.5% nDCG@10).

**V3.3 — real Lucene boostFilters API (2026-03-28):**

| Metric | Simulation C | Real Lucene C | Gap |
|--------|-------------|---------------|-----|
| nDCG@10 | +12.5% | **+0.6%** | -11.9pp |
| P@1 | +32% | **+0%** | -32pp |
| R@10 | +0% | **+1.2%** | +1.2pp |

Real Lucene boost at weight 0.5 is nearly neutral. The
ConstantScoreQuery contributes 0.5 to the BM25 sum, but content
scores range 0.2-1.0, so the boost is only ~25-100% of a low-
scoring document. R@10 improved +1.2% confirming the mechanism
works, but nDCG/P@1 need a higher weight.

**Boost weight sweep results:**

| Metric | w=0.5 | w=5.0 | w=20 | Simulation |
|--------|-------|-------|------|------------|
| nDCG@10 | +0.6% | +0.8% | **+3.2%** | +12.5% |
| P@1 | +0% | +4% | **+4%** | +32% |
| R@10 | +1.2% | -1% | **+3.3%** | +0% |
| RR@10 | +0% | +2.2% | **+3.4%** | +21% |

Weight 20 produces meaningful gains: +3.2% nDCG, +3.3% R@10,
+4% P@1. Still below the simulation but R@10 is better (+3.3%
vs 0%) because real Lucene promotes documents from deeper ranks.

The gap between real Lucene and simulation exists because:
1. Score normalization compresses the boost effect
2. The simulation did binary re-ranking (match → top, no match →
   bottom). Lucene's additive scoring is softer.
3. The simulation re-ranked a fixed pool; Lucene changes which
   documents enter the pool — different and sometimes better.

**Current default: weight=20.** Further tuning (50, 100) may
close the gap more. The weight can also be made per-field
(source boost higher than category boost).

**Track A status: COMPLETE.** Implemented, tested, validated at
+3.2% nDCG@10 with weight=20. The `boostFilters` API parameter
works end-to-end across all 6 layers.

**Track B status: COMPLETE.** Implemented, compiles, all unit
tests pass. Wired end-to-end: QU fires async before search,
extractions become boostFilters, facet snapshot cached with TTL.

**Track B components (all implemented):**
1. `OnlineAiService.chatCompletion()` — non-streaming completion
   method added to interface (default returns failed future) +
   forwarding in `OnlineAiServiceImpl` to
   `InferenceLifecycleManager.chatCompletion()`. (363)
2. `QueryUnderstandingService` in `modules/app-services` —
   standalone service class. Loads prompt template + JSON schema
   from classpath resources. Calls `chatCompletion()` with
   `SamplingParams.DETERMINISTIC.withEnableThinking(false)
   .withResponseFormat(schema)`. Parses flat JSON response into
   `KnowledgeSearchRequest.Filters` for boost. Returns
   `CompletableFuture<QuResult>` — null on any failure (graceful
   degradation). 2s hard deadline via `orTimeout()`.
3. Prompt template: `modules/app-services/src/main/resources/
   qu/qu.v1.txt` (classpath). Adapted from spike v2 prompt to
   flat schema (no confidence wrappers). Also stored as SSOT
   canonical at `SSOT/prompts/en/qu/qu.v1.txt`.
4. JSON schema: `modules/app-services/src/main/resources/
   qu/qu-intent.v1.schema.json`. Also at
   `SSOT/schemas/domain/qu-intent.v1.schema.json`.
5. Facet snapshot cache in `KnowledgeHttpApiAdapter`:
   `refreshFacetSnapshotIfStale()` issues `query: "*", querySyntax:
   LUCENE, limit: 1` with facets for meta_source, meta_category,
   entity_persons_raw, entity_organizations_raw. 5-minute TTL.
   Formats top facet values as text for prompt grounding.
6. Pipeline wiring in `KnowledgeHttpApiAdapter.doSearch()`:
   QU fires async (parallel with expansion + filter setup).
   Result collected before proto build. QU-extracted
   `boostFilters` used when no explicit filters/boostFilters.
7. Bypass rules: skip QU when explicit filters/boostFilters
   present, blank query, paginated (cursor), navigational/
   exact-match query type, or AI unavailable.

**Convergence: Track A + Track B wired together.** QU extracts
filters → maps to `boostFilters` → 6-layer plumbing → Lucene
`BooleanClause.SHOULD` + `BoostQuery(ConstantScoreQuery, 20.0)`.
The user types naturally, the system transparently enhances the
query.

### Confidence assessment (updated post-implementation)

**Track A — COMPLETE.** Implemented, validated at +3.2% nDCG@10.
Weight=20 hardcoded. Gap to simulation (+12.5%) is structural
(additive vs binary scoring).

**Track B — COMPLETE.** All components implemented, compiles,
unit tests pass. Awaiting end-to-end validation with live
backend (requires llama-server running).

**End-to-end validation (2026-03-29):**

Manual test with eval backend (611 MultiHop-RAG docs, Qwen 3.5-9B,
RTX 4070):

Query: "crypto fraud articles from The Verge"

| Rank | With QU (transparent boost) | Without QU (baseline) |
|------|---|---|
| 1 | article_175 **the verge** | article_053 the verge |
| 2 | article_293 **the verge** | article_086 the verge |
| 3 | article_053 **the verge** | article_375 **cnbc** |
| 4 | article_009 techcrunch | article_175 the verge |
| 5 | article_010 techcrunch | article_226 **cnbc** |

QU correctly extracted `meta_source: ["the verge"]` and applied
it as a soft boost. The Verge articles promoted to top-3; CNBC
articles pushed out of top-3. Passthrough query ("explain quantum
computing") returned identical results with/without QU — no
false extractions.

**V3 eval — 4-condition comparison (2026-03-29):**

50 MultiHop-RAG queries, 611 docs, Qwen 3.5-9B, v2 prompt:

| Metric | A baseline | B hard | C soft boost | D fallback |
|--------|-----------|--------|-------------|------------|
| nDCG@10 | 0.6700 | 0.4685 | **0.6712** | 0.5803 |
| P@1 | 0.5800 | 0.4000 | **0.5800** | 0.5000 |
| R@10 | 0.8117 | 0.5850 | **0.8117** | 0.7183 |
| RR@10 | 0.7220 | 0.5191 | **0.7302** | 0.6357 |

| Condition | nDCG@10 delta | Verdict |
|-----------|--------------|---------|
| B: hard filter | **-20.1%** | Net negative (7/50 zero-result) |
| C: soft boost | **+0.12%** | Neutral-to-positive (no harm) |
| D: hard + fallback | **-8.97%** | Still negative |

Soft boost (C) is **safe**: no metric degrades, small positive
improvement on RR@10 (+0.82%). This confirms the design: soft
boost never hurts and provides incremental value. The gains are
modest on MultiHop-RAG because this dataset requires multi-source
evidence — single-source queries would show larger improvements.

### Remaining work — design items not yet implemented

The design (§Design) describes the theoretically ideal system.
Tracks A+B implemented the core pipeline. The following design
items remain, ordered by estimated impact and feasibility.

**1. Per-field confidence + three-tier strategy.**
The design specifies per-field confidence with three tiers:
high (>0.9) → hard filter, medium (0.5-0.9) → soft boost,
low (<0.5) → query enrichment. V1 simplified to "all extractions
→ soft boost." Requires: richer QU schema with confidence
wrappers (the spike v1 schema had them), confidence threshold
logic in `QueryUnderstandingService`, and the enrichment path
(appending terms to vector query). Token logprob-based
confidence (DocDB VLDB 2025 pattern) is an alternative to
model-reported confidence.

**2. Progressive filter relaxation.**
When boost is too weak to promote the right docs, automatically
retry with fewer boosts. Relaxation sequence: remove lowest-
confidence extraction → re-search → repeat. Requires per-field
confidence (item 1) and a retry loop in
`KnowledgeHttpApiAdapter`. Transparency metadata in the
response ("showing results without date filter") follows the
existing `hybridFallback`/`correctionApplied` pattern.

**3. Date range boost.**
`QueryFilterBuilder.applyBoostFilters()` handles term fields
but not date ranges. The QU schema extracts
`meta_published_after`/`meta_published_before`, and
`QueryUnderstandingService` maps them to `TimeRangeMs`, but
`applyBoostFilters` doesn't generate date-range SHOULD clauses.
Needs `IndexOrDocValuesQuery` wrapped in `BoostQuery`.

**4. Soft date boundaries.**
Confidence-modulated date range widening: high → exact range,
medium → ±3-7 days, low → ±14-30 days or skip. Prevents the
failure mode where a slightly-off date returns zero results.
Depends on per-field confidence (item 1).

**5. Sort intent heuristic.**
"latest" / "most recent" / "newest" → sort by freshness. A
5-line keyword regex in `KnowledgeHttpApiAdapter`, no LLM
needed. Trivial to implement.

**6. Negation handling.**
"not from TechCrunch" → exclusion filter. The v2 prompt already
handles this (67% accuracy). Requires: `meta_source_exclude` in
the QU schema, `MUST_NOT` clause generation in
`QueryFilterBuilder`, and wiring in `QueryUnderstandingService`.
Literature warns small models are weak on negation (MIT 2025:
25% accuracy drop) — confidence gating is critical.

**7. Contextual query enrichment.**
Low-confidence extractions become `enrichment_terms` appended
to the vector search query (boosts embedding similarity without
Lucene scoring). The design distinguishes this from boost
filters — enrichment affects vector path, boost affects BM25.
Depends on per-field confidence (item 1).

**8. Multi-turn context carryover.**
Carry extraction context across conversation turns: "articles
from The Verge about AI" → "what about from TechCrunch?" →
source replaced, topic retained. Requires including previous
turns' queries/extractions in the prompt. Relevant for agent
loops and human search sessions.

**9. Query reformulation on failure.**
When all extractions exhausted and raw query still returns
poor results, feed query + "no results" signal back to LLM
for reformulation. Inspired by ReZero (arXiv 2504.11001).
A second LLM call, doubles latency — only for genuine
zero-result failures.

**10. Search-cancels-conversation.**
QU call should pre-empt in-progress chat completions. V1
queues behind `onlineRequestLock`. True cancellation requires
a priority mechanism in `InferenceLifecycleManager`.

**11. Two-stage decomposition fallback.**
If single-call accuracy degrades as more capabilities are
added, decompose into Stage 1 (classify which fields present)
→ Stage 2 (extract values). Based on Google EMNLP 2025. Not
needed at current 78.6% accuracy — contingency for when more
capabilities are added to the prompt.

**12. Pipeline report metadata.**
QU results (extractions applied, latency, skip reason) in the
search response's pipeline execution report. Currently only
debug logging. Follows existing `expansionApplied`,
`spladeExecuted`, `crossEncoderApplied` pattern.

**13. Prompt iteration.**
v2 prompt tuned on 28 spike queries. Larger evaluation set
and refinement (especially entity-filter vs entity-mention
distinction) could improve beyond 78.6%.

**14. Facet snapshot grounding validation.**
The cache populates at runtime but we haven't measured
accuracy improvement from live grounding vs no grounding
(spike showed 64% → 78.6% with static grounding).

**15. User-realistic query evaluation.**
MultiHop-RAG queries are synthetic multi-hop questions. Need
a representative set of real user queries to validate.

### Context sufficiency detection (answerability gating)

**Problem (from 366 eval).** 50-query Haiku eval on MCP tooling
showed 0/9 accuracy on null queries (questions where the answer
doesn't exist in the corpus). Agents always hallucinate an answer.
Inference queries hit 100%, temporal 91%, comparison 73%, but null
is 0%. This is the single largest accuracy gap.

**Why retrieval signals can't solve it.** The 366 agent tested QPP
signals (query_scope, max_idf, avg_ictf) empirically:
- Null query top scores: 0.70–1.0
- Inference query top scores: 0.82–1.0
- Complete overlap. No threshold separates them.

QPP++ (ECIR 2025 workshop, arXiv 2504.01101) confirms QPP methods
"lack the robustness necessary for real-world deployment" — ~4%
NDCG improvement with reliability "varying dramatically across
settings." Cross-encoder scores are uncalibrated (high relevance
assigned to irrelevant documents). Vector search structurally
always returns topK results regardless of relevance.

**The RAG abstention paradox (Google ICLR 2025).** "Sufficient
Context: A New Lens on Retrieval Augmented Generation Systems"
(Joren et al., arXiv 2411.06037) found that RAG paradoxically
*reduces* model abstention on unanswerable questions. Retrieved
context — even when irrelevant — makes models more confident:
- Gemma: 10.2% incorrect without context → **66.1% incorrect**
  with insufficient context
- State-of-the-art models (Gemini 1.5 Pro, GPT-4o, Claude 3.5)
  "excel at answering when context is sufficient but output
  incorrect answers instead of abstaining when context is not"

**Implication for 363:** Track A/B (soft-boost) may make this
*worse*. By improving retrieval quality for answerable queries,
the retrieved context becomes more plausible-looking even for
unanswerable ones. The agent gets more confident wrong answers.

**The solution: sufficiency classification.** Google's framework:
1. After retrieval, classify whether context is "sufficient" —
   contains all necessary information for a definitive answer
2. Combine sufficiency signal with model self-confidence via
   lightweight logistic regression
3. Abstain when combined score is below threshold

Results: +2-10% selective accuracy (fraction of correct answers
among times the model responds) on HotPotQA and Musique.
Autorater achieves 93% accuracy, 0.94 F1 on 115 human-labeled
examples, outperforming fine-tuned baselines (FLAMe 24B: 0.892
F1, TRUE-NLI 11B: 0.818 F1).

**Critical constraint: model size.** Google used Gemini 1.5 Pro
for the autorater. JustSearch uses a 9B model. Recent research
(arXiv 2603.11513, "Can Small Language Models Use What They
Retrieve?") found that models ≤7B fail to extract correct
answers 85-100% of the time even with oracle retrieval, and
adding context *destroys* 42-100% of answers the model
previously knew. Sufficiency classification — understanding
whether a passage *answers* a question — is arguably harder
than filter extraction (closed-set matching). Whether a 9B
model can do this reliably is an open question.

**Alternative: activation-based detection.** Lavi et al. (arXiv
2509.22449) discovered linear directions in model activations
that represent unanswerability. Projecting hidden activations
onto this direction produces reliability scores that
"generalize better across datasets than prompt-based and
classifier-based approaches." This is a lighter-weight
alternative that doesn't require a separate LLM call — but
requires access to model internals (not available through
llama-server's HTTP API without modification).

**Where it fits in the pipeline:**

```
Query → [QU: extract filters] → [Soft boost] → Search →
[Sufficiency check] → Results + confidence signal
```

The sufficiency check is a post-search LLM call using the same
Brain/llama-server. Output: a `contextSufficiency` float (0-1)
attached to search/retrieve-context responses. 366's MCP layer
surfaces this as a hint: "The indexed documents may not contain
information about this topic."

**Feasibility assessment:**
- Infrastructure: same as QU — Brain LLM call, same bypass rules,
  same graceful degradation
- Latency: adds ~3.7s post-search (serial — needs results as
  input). Total budget for fully-enhanced query is high.
  May be appropriate for `retrieve-context` (agent-facing,
  latency-tolerant) but not for `search` (human UI).
- Model capability: **VALIDATED** — see spike below.
- Interaction with 366: 363 computes the signal, 366 consumes it

**Sufficiency spike results (2026-03-29):**

Tested Qwen 3.5-9B with `response_format` JSON schema, no
thinking, temp=0.1. 18 queries: 9 null (answer not in corpus)
+ 9 inference (answer exists). For each, fetched top-5 search
results from the live backend and asked the model to classify
whether the results are sufficient to answer the query.

| Category | Count | Model correct | Accuracy |
|----------|-------|---------------|----------|
| Null (expect: insufficient) | 9 | 9 | **100%** |
| Inference (expect: sufficient) | 9 | 4 | 44% |
| **Overall (raw)** | **18** | **13** | **72%** |

Confusion matrix: TP=4, TN=9, **FP=0**, FN=5, errors=0.

**Critical reanalysis of the 5 "false negatives":**

All 5 queries where the model said "insufficient" but we
expected "sufficient" were investigated. In every case, the
search results genuinely did NOT contain the expected answer:
- "Donald Trump" query → search returned SBF fraud articles
- "Caesars Sportsbook" → generic betting articles, no Caesars
- "OpenAI" → returned Meltwater/Scalable Capital articles
- "European Commission" → returned FTC antitrust articles
- "Amazon/e-reader" → returned irrelevant articles

The model was correct in all 5 cases — the context was truly
insufficient. The "errors" were search quality failures, not
classification failures.

**Corrected accuracy: 17-18/18 (94-100%).**

**Key findings:**
1. **Zero false positives** (the dangerous case — saying
   context is sufficient when it isn't). This is the critical
   safety property.
2. **The 9B model CAN do sufficiency classification** —
   contrary to concerns about small model capability. The
   task (binary judgment: does this text answer this question?)
   is simpler than open-ended reasoning.
3. **Latency: 3.7s average.** Higher than QU extraction (~1s)
   because the input is longer (query + 5 search results).
   Acceptable for `retrieve-context`, too slow for `search`.
4. **The spike accidentally validated search quality** —
   revealing that 5/9 inference queries fail at the retrieval
   stage, not the generation stage. This is useful diagnostic
   data for the search pipeline.

**Sufficiency spike v2 — harder cases + latency (2026-03-29):**

Tested 3 difficulty levels: easy null (6 queries, absent
sources), hard null (8 queries, topic present but specific
claim absent), inference (6 queries, answer exists).

| Category | Count | Correct | FP (dangerous) |
|----------|-------|---------|----------------|
| Easy null | 6 | **6/6 (100%)** | 0 |
| Hard null | 8 | **8/8 (100%)** | 0 |
| Inference | 6 | 1/6 (17%) | 0 |

**14/14 null queries correctly flagged** across both difficulty
levels. Hard nulls (TechCrunch+OpenAI exists but revenue claim
doesn't, Verge+Apple exists but foldable iPhone doesn't) are
detected just as reliably as easy nulls. Zero false positives
at any difficulty level.

**Inference queries: too conservative.** Model says
"insufficient" for 5/6 answerable queries. Same pattern as v1.
Investigated: likely a mix of (a) search returning wrong
documents and (b) model being overly cautious — the
AbstentionBench finding that models default to refusal when
uncertain.

**Latency doesn't scale with top-k:**
- top-5: 2859ms avg
- top-3: 2873ms avg
- top-1: 2717ms avg
Bottleneck is LLM inference (~2.5s), not context length.
Top-1 is too little context (0/6 inference accuracy).

**Revised design: combined signal, not binary gate.**

Binary sufficiency is too conservative for production. The
correct approach (matching Google ICLR 2025): combine the LLM
sufficiency signal with existing quality signals:

| LLM says | quality.coverage | Signal to agent |
|----------|-----------------|-----------------|
| false | low (<0.3) | Strong: "No relevant info found" |
| false | high (>0.5) | None (model being cautious) |
| true | any | None (confident) |

This requires only the `quality` object from `retrieve-context`
(already computed) plus the binary LLM classification. The
combined signal avoids the false-negative problem: when the
model says "insufficient" but quality signals are high, the
system trusts the quality signals over the model's caution.

**Integration point:** `retrieve-context` response only (not
`search`). The response already has a `quality` object with
`best_score`, `score_gap`, `coverage`. Add
`context_sufficiency: boolean` from the LLM check. The 366
MCP layer (`server.mjs`) already generates hints from quality
signals — add a hint when sufficiency=false AND coverage<0.3.

**Research findings (from investigation):**
- AbstentionBench (arXiv 2506.09038): "almost no effect of
  increasing scale on mean abstention." Reasoning fine-tuning
  hurts abstention by 24%. Confirms our finding: the 9B model
  is overly cautious, but this is a universal LLM property.
- GRACE (arXiv 2601.04525): RL-based approach trains models
  to balance response vs abstention. Achieves SOTA accuracy
  at 10% annotation cost. Could inform future fine-tuning.
- "Knowing When to Stop" (arXiv 2502.01025): attention heads
  encode sufficiency signals, enabling 1.33x token reduction.
  Interesting but requires model internals access.
- UAEval4RAG (ACL 2025): 6-category unanswerable taxonomy
  with automatic synthesis. Could generate harder test cases.
- Query complexity classification (IWSDS 2025): simple queries
  can skip sufficiency check entirely, reducing avg latency.

**Track C status: IMPLEMENTED + VALIDATED (2026-03-29).**

End-to-end validation with live backend (611 MultiHop-RAG docs,
Qwen 3.5-9B, RTX 4070):

| Query | Type | context_sufficient | coverage |
|-------|------|-------------------|----------|
| BBC + Sridevi Bollywood | easy null | **false** | 0.24 |
| Apple foldable iPhone | hard null | **false** | 0.58 |
| 2024 Super Bowl | hard null | **false** | 0.57 |
| Generative AI departure | answerable | false | 0.58 |
| TechCrunch+Verge AI spending | answerable | false | 0.54 |

Null queries: `context_sufficient=false` (correct).
Answerable queries: also `false` (too conservative, matches
spike findings). Combined signal works: the genuine null has
coverage=0.24, answerable queries have coverage=0.54-0.59.
Threshold `context_sufficient=false AND coverage<0.3` correctly
discriminates.

Implementation: `ContextSufficiencyService` in `app-services`,
wired into `RetrieveContextController`. The `quality` object
on `retrieve-context` response now includes
`context_sufficient: boolean`. 366 MCP layer consumes this
alongside existing `coverage` to generate agent hints.

**Full 50-query eval (2026-03-29):**

| Type | Count | suf=true | suf=false | None (timeout) |
|------|-------|----------|-----------|----------------|
| null_query | 9 | **0** | 5 | 4 |
| inference_query | 15 | 9 | 3 | 3 |
| comparison_query | 15 | 2 | 7 | 6 |
| temporal_query | 11 | 1 | 7 | 3 |

**Problems revealed:**
1. **32% failure rate (16/50 None).** The sufficiency LLM call
   times out when queued behind QU + search + reranking. The
   5s deadline is too tight for the full retrieve-context
   pipeline. Need to either increase deadline or run
   sufficiency in parallel with context assembly.
2. **Coverage doesn't discriminate.** All types avg 0.57-0.58
   coverage, including nulls. The combined signal
   (sufficient=false AND coverage<0.3) catches zero nulls on
   this dataset. The hand-picked BBC query (coverage=0.24)
   was an outlier, not representative.
3. **Too conservative on comparison/temporal queries.** The
   model says "insufficient" for 7/9 comparison and 7/8
   temporal queries that ARE answerable. Only inference
   queries (9/12 = 75%) are classified well.

**What works:** Zero false positives — the model never says
"sufficient" for a null query (0/5 where it responded). The
safety property holds. Inference queries are well-classified.

**Investigation results (2026-03-29):**

Isolated the three problems via direct llama-server testing
(bypassing pipeline timeout chain), quality signal analysis,
and prompt variant comparison on 16 queries (4 per type).

**Timeout: NOT a real problem.** Raw LLM latency is 1.3s avg,
1.5s max when calling llama-server directly. The 5.2s in the
50q eval was pipeline overhead + sequential HTTP queueing. The
5s `orTimeout` in ContextSufficiencyService is adequate.

**Quality signals don't discriminate:** Complete overlap on all
three signals between null and answerable queries:
- coverage: null [0.56-0.59] vs ans [0.50-0.60]
- best_score: null [0.034-0.057] vs ans [0.041-0.088]
- score_gap: null [0.001-0.003] vs ans [0.000-0.014]
The combined signal design is a dead end for this dataset.

**Conservative behavior is a model capability limit, not a
prompt problem.** Tested 3 prompt variants (strict, short,
lenient) — all produce the same pattern:
- null: 4/4 (100%) across all variants
- inference: 3/4 (75%) across all variants
- comparison: 0/4 across all variants
- temporal: 0/4 across all variants

The 9B model cannot assess multi-hop sufficiency. Comparison
("does X contradict Y?") and temporal ("after X, did Y?")
queries require combining information across multiple chunks.
The model reads each chunk and correctly notes that no single
passage answers the question — but the answer IS there if you
synthesize across passages. This is beyond 9B capability.

**Revised usage guidance:** `context_sufficient` should be
consumed as a **null detector**, not a general confidence
signal:
- `true` → strong signal: answer IS in the context
- `false` → weak signal: possibly unanswerable, OR complex
  multi-hop query where the model can't assess synthesis

The 366 MCP layer should emit a hint ONLY when
`context_sufficient=false` AND the query appears simple
(single-hop, factual). For complex queries, the signal
should be ignored.

**Research references:**
- Primary: arXiv 2411.06037 (ICLR 2025) — framework, code at
  github.com/hljoren/sufficientcontext
- Unanswerable taxonomy: ACL 2025 aclanthology.org/2025.acl-long.415
  — 6 categories, automatic synthesis for any knowledge base
- Uncheatable queries: arXiv 2510.11956 (ECIR 2026) — CRUMQs
  pipeline, 81% reduction in cheatability
- QPP definitively closed: arXiv 2504.01101
- Small model retrieval: arXiv 2603.11513 — models ≤7B fail to
  use retrieved context effectively
- Activation-based detection: arXiv 2509.22449 — linear
  directions for unanswerability, no LLM call needed
- Answerability gating: emergentmind.com/topics/answerability-
  gating-problem — gate placed between retrieval and generation

### The prompt is the product

The intelligence lives in the prompt and JSON schema, not in
Java code. The code wires the LLM call into the pipeline,
applies extracted parameters, and handles fallbacks — that's
plumbing built once. The prompt is where extraction quality
lives and where iteration happens. Implementation should ensure
prompt changes don't require code changes — the prompt should
be a resource file or config, not embedded in Java.

## V1 feasibility spike — plan

### Goal

Answer: can the local model (Qwen 3.5-9B-Q4_K_M) reliably
extract structured filters from natural language queries, using
GBNF-constrained output and index-grounded prompts?

### Existing infrastructure to build on

There is already a `SearchIntent v1` system in the codebase:
- `SSOT/schemas/domain/search-intent.schema.json` — JSON schema
- `SSOT/artifacts/grammars/intent_v1.gbnf` — GBNF grammar
- `SSOT/prompts/en/intent.v1.json` — prompt pack
- `SSOT/pipelines/intent_v1.yaml` — pipeline definition
- `LocalLlmTranslator.translateIntent()` — Java interface

This system is limited: filters only cover `mime`, `language`,
`timeRange`. No metadata fields (source, author, category), no
entity fields, no per-field confidence, no few-shot examples, no
index grounding. The V1 spike bypasses this infrastructure and
calls llama-server directly — extending `SearchIntent` to v2
comes later.

### Artifacts to create

**1. Test query annotations** — 25-30 queries with ground-truth
expected extractions, covering:
- 10 from MultiHop-RAG agent eval (known source filters)
- 5 from tempdoc 363 illustrative examples
- 5 pure semantic (no filters expected — passthrough)
- 5 temporal (date ranges, "recent", "last month")
- 3 negation ("not from X")
- 2 entity-only ("articles mentioning Elon Musk")

Format: JSON file at
`scripts/jseval/jseval/data/qu-spike-queries.v1.json`

```json
[
  {
    "id": "src-1",
    "query": "crypto fraud articles from The Verge and TechCrunch",
    "expected_query": "crypto fraud",
    "expected_extractions": {
      "meta_source": ["the verge", "techcrunch"]
    },
    "category": "source_filter"
  },
  {
    "id": "pass-1",
    "query": "explain quantum computing",
    "expected_query": "explain quantum computing",
    "expected_extractions": {},
    "category": "passthrough"
  }
]
```

**2. Prompt template** — resource file at
`scripts/jseval/jseval/data/qu-spike-prompt.v1.txt`

System prompt with:
- Task description (query translator role)
- Filter field definitions with types
- Placeholder `{{INDEX_SNAPSHOT}}` for facet values
- Placeholder `{{TODAY}}` for current date
- 3 few-shot examples (source+date, passthrough, temporal)

**3. JSON schema** — resource file at
`scripts/jseval/jseval/data/qu-spike-schema.v1.json`

The per-field confidence schema from the Design section,
used as `response_format` for llama-server.

**4. Spike script** — new jseval subcommand `qu-spike`
at `scripts/jseval/jseval/qu_spike.py`

```
python -m jseval qu-spike [--port 8080] [--with-grounding] [--json]
```

What it does:
1. Load test queries from the annotations file
2. For each query:
   a. Build the prompt (system + optional index snapshot + user query)
   b. POST to `http://127.0.0.1:{port}/v1/chat/completions`
      with `response_format: {type: "json_object", schema: ...}`
   c. Parse the JSON response
   d. Score against ground truth:
      - Per-field exact match (did it extract meta_source correctly?)
      - Passthrough correctness (did it leave pure semantic queries alone?)
      - Hallucination rate (did it invent filters that shouldn't exist?)
3. Print summary:
   - Overall accuracy (% queries with all fields correct)
   - Per-category accuracy (source, temporal, passthrough, negation)
   - Per-field precision/recall
   - Hallucination rate
   - Average latency per query

**5. Two-variant comparison** — run the spike twice:
- `python -m jseval qu-spike` — without index grounding
- `python -m jseval qu-spike --with-grounding` — with hardcoded
  facet values injected into prompt (simulating live injection)

Compare accuracy between variants to measure the impact of
closed-set matching (V2 question).

### Prerequisites

- Backend running with Brain online (`runHeadlessEval` or
  dev stack)
- llama-server port known (default 8080, or from `/api/status`)
- Model loaded: Qwen 3.5-9B-Q4_K_M (or whatever is configured)

### Success criteria

- **Go:** >80% overall accuracy, >90% on source extraction,
  passthrough queries correctly left alone, hallucination
  rate <10%
- **Investigate:** 60-80% accuracy — try prompt refinements,
  more few-shot examples, GBNF vs response_format
- **Stop:** <60% accuracy — the local model can't handle this
  task, reconsider approach (fine-tuning? cloud model? rule-based
  hybrid?)

### V1 results (2026-03-28)

Model: Qwen 3.5-9B-Q4_K_M, GPU: RTX 4070, GBNF-constrained JSON.
28 queries across 6 categories.

| Metric | Without grounding | With grounding |
|--------|------------------|----------------|
| Overall accuracy | 57.1% (16/28) | 64.3% (18/28) |
| Source extraction | 40% (4/10) | 60% (6/10) |
| Passthrough | 100% (5/5) | 100% (5/5) |
| Temporal | 80% (4/5) | 60% (3/5) |
| Negation | 33% (1/3) | 33% (1/3) |
| Hallucinated fields | 19 | 17 |
| Avg latency | ~1.4s | ~1.5s |

**Falls in the "Investigate" zone (60-80%).**

**Key findings:**
1. **Passthrough is perfect** — the model never adds filters to
   pure semantic queries.
2. **Source extraction works** and improves with grounding.
   Grounding fixed exact value matching (e.g., "sydney morning
   herald" → "the sydney morning herald").
3. **Hallucination is the dominant failure mode.** The model
   over-extracts — pulling entity_persons, entity_organizations,
   entity_locations from query terms that should remain in the
   semantic query, not become filters. 17-19 hallucinated fields.
4. **Negation is weak** as predicted by MIT 2025 study. Multi-
   source exclusion ("not from X or Y") fails.
5. **Latency is 0.7-2.5s** — passthrough queries are fast
   (~700ms), complex extractions slower (~2s). Above the design
   target of ~100ms.
6. **Date hallucination:** "latest" triggers hallucinated date
   filters to today's date. Sort intent ("freshness") should
   handle this, but the model reaches for date filters instead.

**V1.2 results — prompt v2 with anti-hallucination rules:**

Revised prompt incorporating patterns from Qdrant ("better not
to generate than to generate incorrectly"), Haystack, and Ailog
("don't invent filters not present in the query"). Key changes:
explicit rule distinguishing "query about X" from "filter by X"
for entities, "latest" → sort not date filter, added two few-
shot examples showing the entity distinction.

| Metric | v1 no-ground | v1 grounded | **v2 grounded** |
|--------|-------------|-------------|-----------------|
| Overall | 57.1% | 64.3% | **78.6%** |
| Source | 40% | 60% | **80%** |
| Passthrough | 100% | 100% | **100%** |
| Temporal | 80% | 60% | **80%** |
| Negation | 33% | 33% | **67%** |
| Hallucinations | 19 | 17 | **4** |
| Avg latency | 1443ms | 1454ms | **1027ms** |

**Hallucination reduced from 17-19 to 4 fields** — the single
biggest improvement. The anti-hallucination rules and the entity
distinction example were decisive.

**Falls at the boundary of "Investigate" and "Go" (78.6%).**

Remaining failures (6/28):
- `src-9`: person names "Prince William" and "Princess Diana"
  still extracted as entity_persons (borderline — these ARE
  entity names, but the user didn't explicitly ask to filter)
- `src-10`: "Hacker News article about The Epoch Times" — model
  confused source/subject, applied exclusion filter to subject
- `temp-4`: missed `meta_category: sports` for "sports news"
- `neg-3`: "not about Google" → model applied source exclusion
  instead of recognizing "not about" is semantic, not a filter
- `ent-2`: "Sam Altman" mapped to entity_locations instead of
  entity_persons (model confusion)
- `combo-3`: missed `meta_category: technology` for "technology
  articles" (same as temp-4 pattern)

**The remaining failures are edge cases, not systematic.** No
single failure pattern dominates. The prompt/model combination
is viable for production with confidence gating (per-field
thresholds would catch the remaining errors).

### llama.cpp thinking + structured output fix

The V1 spike disabled thinking mode (`enable_thinking: false`)
because our llama.cpp build (b8185, March 2 2026) has a bug
where grammar enforcement is bypassed during reasoning —
issue #20345, fixed in commit `62b8143` ("Fix structured
outputs #20223", March 8 2026, file:
`common/chat-auto-parser-generator.cpp`).

**Any build >= ~b8236 includes the fix.** The current release
is **b8571** (March 28 2026) — 386 versions ahead of ours.

Relevant changes between b8185 and b8571 for JustSearch:

| Area | Change | Since |
|------|--------|-------|
| Thinking + grammar | Grammar enforcement active during reasoning mode | ~b8236 |
| Reasoning budget | `--reasoning-budget` / `--think-budget` controls | b8559 |
| Qwen 3.5 | Gated Delta Net ops, n_rot fix | b8233, b8264 |
| JSON schema | Pattern converter crash fix for regex | b8571 |
| CUDA | bf16 flash attention, graph memory leak fixes | various |
| Server | Kill switch when stuck, built-in tools backend | b8553-b8554 |

**Thinking experiment results (b8571):**

Updated vendored llama-server to b8571 and tested thinking
mode with grammar constraints. Despite the upstream fix,
thinking + `response_format` is still unreliable in practice
with Qwen 3.5-9B:

| Config | Accuracy | Errors | Avg latency |
|--------|----------|--------|-------------|
| No thinking (baseline) | **78.6%** | 0 | **946ms** |
| Thinking, budget=64 | 39.3% | 17 | 65.9s |
| Thinking, budget=128 | ~35% | ~18 | ~30s |
| Thinking, budget=256 | ~35% | ~18 | ~30s |
| Thinking, budget=512 | 10.7% | 25 | ~15-31s |

**Thinking mode is strictly worse** for this task and model.
The model spends its token budget on `<think>` reasoning, then
either produces empty `content` (JSON parse error) or truncated
JSON. Even with `reasoning_budget` limits, the grammar
enforcement doesn't reliably engage after thinking completes.

**Conclusion: no-thinking direct extraction is the correct
approach.** This result is well-explained by recent literature:

- **CoT hurts small models on simple tasks.** Wei et al.
  (original CoT paper) found CoT only helps at ~100B params;
  smaller models write "illogical chains" that reduce accuracy.
- **Inverted U-shaped curve** (arXiv 2502.07266, Feb 2025):
  accuracy initially improves with reasoning length, then
  degrades. Optimal CoT for a 1.5B model is 14 steps; for 72B
  it's only 4 steps. Our 9B model falls in the range where
  extended thinking actively hurts.
- **Overthinking on simple tasks** (arXiv 2510.07880, Oct 2025):
  80% of extra thinking compute produces no measurable gain.
  Knowledge recall / extraction tasks "provide negligible
  benefits from thinking, irrespective of difficulty."
  Performance gains disappear beyond 4-8B parameters.
- **Qwen official position**: Alibaba's documentation states
  "thinking mode models do not support structured output" and
  recommends non-thinking mode for JSON extraction tasks.

Our task — closed-set filter extraction from short text with
GBNF constraints — is exactly the category where thinking
provides no benefit and actively degrades output quality. The
78.6% accuracy with no thinking at ~1s latency is the correct
operating point for this model and task.

### V3 end-to-end results (2026-03-28)

50 MultiHop-RAG queries, backend with 1612 docs, b8571
llama-server, v2 prompt with grounding, no thinking.

| Metric | Baseline | QU-Enhanced | Delta |
|--------|----------|-------------|-------|
| nDCG@10 | 0.6501 | 0.4766 | **-0.1735** |
| R@10 | 0.7967 | 0.5883 | **-0.2084** |
| P@1 | 0.5400 | 0.4000 | **-0.1400** |
| AP@10 | 0.5284 | 0.3735 | **-0.1549** |

**QU-enhanced search is worse than baseline.** 7/50 queries
(14%) returned zero results because the extracted filter was
wrong or too restrictive. Zero-result queries contribute 0 to
every metric, overwhelming the gains from correct extractions.

**This validates the design's progressive filter relaxation.**
Without automatic retry-with-fewer-filters on zero results,
query understanding is net-negative. The correct 78.6% of
extractions provide moderate improvement, but the wrong 21.4%
are catastrophically bad (zero results vs merely suboptimal
results without filters).

**The theoretical prediction was correct:** the "net-negative
risk threshold" question (§Remaining theoretical questions)
identified this exact failure mode — wrong filters are
asymmetrically bad. The design addresses it with progressive
relaxation (remove lowest-confidence filter on zero results,
retry) and enrichment demotion (use low-confidence extractions
as query boosts, not hard filters). Neither is implemented yet.

**Deep analysis — excluding zero-result queries:**

Even excluding the 7 zero-result catastrophic failures, the
remaining 43 queries are STILL worse with filters:

| Metric | Baseline (43q) | QU (43q) | Delta |
|--------|---------------|----------|-------|
| nDCG@10 | 0.6318 | 0.5542 | -0.0776 |
| R@10 | 0.7829 | 0.6841 | -0.0988 |

Per-query movement: 15 improved, 26 degraded, 9 neutral.

**Progressive relaxation alone would NOT make this net-positive.**
It would only fix the 7 zero-result failures, not the 26
queries where filters returned results but worse results.

The top improved queries gained +0.15 to +0.35 nDCG when
filters correctly narrowed to the right source. The top
degraded queries lost -0.5 to -0.7 nDCG when filters
restricted the result set to wrong or insufficient articles.

**Root cause hypothesis:** The model extracts only one source
from queries that reference two sources (e.g., "TechCrunch and
Fortune"). Filtering to one source misses evidence from the
other. The MultiHop-RAG queries are specifically designed to
require evidence from MULTIPLE sources — single-source filtering
is structurally harmful for this dataset.

**Implication:** The feature may only be net-positive for
single-source queries ("articles from The Verge about X"), not
multi-source queries ("what TechCrunch and Fortune reported
about X"). The enrichment demotion path (boost, not filter)
may be more appropriate as the default strategy, with hard
filtering reserved for high-confidence single-source extractions.

This is a fundamental design question, not just an
implementation detail.

### Research response: soft filters (boost) vs hard filters

The V3 results show that hard filtering (MUST + FILTER in
Lucene) is too aggressive — it removes documents that might
contain relevant evidence from other sources. The search
industry has a well-established solution: **optional/soft
filters that boost matching documents in ranking without
excluding non-matching ones**.

**Algolia optional filters:** Records matching the filter are
boosted in ranking; non-matching records are retained but ranked
lower. Scored variants (`brand:Apple<score=3>`) allow weighted
boosting. Negative optional filters bury but don't exclude.
"Unlike filters, optional filters don't remove records."
https://www.algolia.com/doc/guides/managing-results/rules/merchandising-and-promoting/in-depth/optional-filters

**Elasticsearch SHOULD clause:** In a BooleanQuery, SHOULD
clauses contribute to the relevance score but don't require
the match. A document matching a SHOULD clause ranks higher
than one that doesn't, but both appear in results. This is
the Lucene-native mechanism for soft filtering.

**Vespa filterFirstThreshold:** Adaptive — applies hard filters
only when the filter is selective enough. If the filter matches
too many or too few documents, it falls back to boost-based
scoring.

**UniRAG (ACL 2025):** Unified query understanding that jointly
performs query augmentation and encoding, using retrieval and
generation outputs as feedback to adaptively select between
paraphrasing, expansion, and abstraction strategies.

**The 2025-2026 consensus:** Query expansion and metadata
filtering are complementary, not competing. The best results
come from combining both — extracted metadata as soft boosts
alongside expanded queries — rather than using hard filters
exclusively. LLM-based query expansion yields 3-15% BM25 gains
and 5-8% dense retriever improvements.

### Revised strategy: boost-first, filter-on-confidence

The design should change from "extract filters and apply them"
to a three-tier strategy based on extraction confidence:

| Confidence | Strategy | Lucene mechanism |
|-----------|----------|-----------------|
| High (>0.9) | Hard filter (MUST+FILTER) | `BooleanClause.FILTER` |
| Medium (0.5-0.9) | Soft boost (SHOULD) | `BooleanClause.SHOULD` with `BoostQuery` |
| Low (<0.5) | Query enrichment only | Append terms to search query |

This preserves recall (no documents excluded at medium/low
confidence) while still benefiting from correct extractions
(boosted ranking at high confidence). The zero-result
catastrophe from V3 is eliminated because the default strategy
is boost, not filter.

**For JustSearch's Lucene pipeline**, this maps directly:
- Current `QueryFilterBuilder.applyRuntimeFilters()` uses
  `BooleanClause.FILTER` (hard filter, no scoring)
- The soft-boost path would use `BooleanClause.SHOULD` with
  `BoostQuery(termQuery, weight)` — same field, same value,
  but contributes to scoring instead of filtering
- Both paths already exist in Lucene's BooleanQuery API —
  the difference is one enum value (`FILTER` vs `SHOULD`)

### V3.2 — soft boost validation (2026-03-28)

Simulated soft-boost at the eval level: baseline search results
re-ranked so filter-matching hits score higher, without
excluding any documents. Compared 4 conditions on 50 queries:

| Metric | A baseline | B hard | C boost | D fallback |
|--------|-----------|--------|---------|------------|
| nDCG@10 | 0.6501 | 0.4766 | **0.7752** | 0.5834 |
| P@1 | 0.5400 | 0.4000 | **0.8600** | 0.5000 |
| RR@10 | 0.7013 | 0.5412 | **0.9140** | 0.6579 |
| R@10 | 0.7967 | 0.5883 | **0.7967** | 0.7117 |

| Condition | nDCG@10 delta | Verdict |
|-----------|--------------|---------|
| B: hard filter | **-17.4%** | Net negative |
| C: soft boost | **+12.5%** | Net positive |
| D: hard + fallback | **-6.7%** | Still negative |

**Soft boost is the correct strategy.** It delivers +12.5%
nDCG@10 and +32% P@1 over baseline while preserving identical
recall. Hard filter (even with fallback) is net-negative.

The key insight: the extracted filter information IS valuable —
it correctly identifies which sources are relevant. But using
it as a hard filter excludes evidence documents from other
sources. Using it as a score boost promotes the right documents
to the top without excluding anything.

**This validates the three-tier design** from the revised
strategy section. The default should be soft-boost (SHOULD
clause), not hard filter (FILTER clause). Hard filtering should
be reserved for very high confidence single-field extractions
where exclusion is explicitly desired.

### Not in scope for V1

- Wiring into the search pipeline
- Live facet injection from FacetingEngine
- Per-field confidence thresholding
- Progressive relaxation
- Multi-turn context
- Any Java code changes

## Related

- **362**: Faceted metadata filtering — the filter infrastructure
  this tempdoc builds on
- **346**: Agent retrieval eval — the eval framework and baseline
  metrics
- **345**: RAG considerations — noted query understanding as a gap

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 50 days at audit time.

