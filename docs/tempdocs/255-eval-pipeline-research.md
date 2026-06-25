---
title: "255: Agent Pipeline Evaluation — Context Compression Finding"
type: tempdoc
status: done
created: 2026-03-04
updated: 2026-03-05
---

# 255: Agent Pipeline Evaluation — Context Compression Finding

## Context

The JustSearch agent battery (tempdoc 227) measures end-to-end pass/fail via keyword/fact
presence in the final response. It cannot distinguish "search returned the wrong document"
from "model had the right document but failed to extract facts." This blocks informed
optimization of either layer.

This investigation started as evaluation research (§1-§5): what's the best way to decompose
failures into retrieval vs comprehension? It then validated the leading approach against real
battery data (§6), and finally traced the actual production code to understand why failures
happen (§7). That final step found the real problem.

Readers interested in the production finding can skip to §7. Sections 1-5 document the
evaluation research and remain useful reference material.

## Executive summary

Context compression was introduced intentionally (tempdoc 208) to manage an 8K context window
with a 900-char tool budget. The aggressive default `contextCompressionKeepLastResults = 0`
strips all excerpt content from every tool result — including the most recently added one —
before the LLM reads it. This was never validated against real search content because the
A/B test ran against an empty index (tempdoc 213 quality gate: "81.3% both arms" measured
infrastructure, not answer quality).

The tool budget was later raised to 4000 chars (tempdoc 213), but `keepLastResults` was not
revisited. The result: the LLM receives document titles, paths, and scores, but never the
actual document content. Changing the default to 1 improved the battery pass rate from
52.2% to 75.4% mean across 3 runs (range 65-83%), confirming this was the binding constraint.

The passing scenarios break down as: 7/12 (58%) answerable from titles/paths alone, 4/12
(33%) relying on surface-level content that survived compression (short previews, metadata
fields), and 1/12 answered from the system prompt with zero tool calls.

**Fix:** Set `contextCompressionKeepLastResults` to >=1. The infrastructure exists; only the
default needs to change. See §7 R0 for full analysis, §8 for action plan.

**Residual failures are model capability, not quantization.** After the compression fix,
Q4_K_M/Q5_K_M/Q6_K all score 75-77% mean (N=3 each). The two persistently-failing scenarios
fail identically across all quantization levels — they are 9B parameter-count limitations.

---

## 1. The RAG evaluation taxonomy

The RAG evaluation field has converged on a standard decomposition. Every major framework
(RAGAS, DeepEval, TruLens, Promptfoo) separates evaluation into two independent layers:

**Retriever metrics** (did we get the right context?):
- Context Precision: of the chunks retrieved, what fraction are relevant?
- Context Recall: of all relevant chunks that exist, what fraction did we retrieve?
- NDCG@k, MRR, Precision@k: standard IR ranking metrics

**Generator metrics** (did the model use the context correctly?):
- Faithfulness: is the response grounded in the retrieved context?
- Answer Relevancy: does the response address the query?
- Groundedness (TruLens): can claims in the response be attributed to source text?

This two-layer decomposition is exactly what the JustSearch battery is missing.

### The LLM judge problem

Almost all production RAG evaluation frameworks **require an LLM judge** for their core
metrics. RAGAS faithfulness, DeepEval contextual recall, TruLens groundedness — all use
GPT-4 or similar to score results. This is a non-starter for JustSearch:
- 8GB VRAM is fully occupied by the chat model during battery runs
- External API calls add latency, cost, and non-determinism to CI
- LLM judges disagree with themselves ~15-20% of the time

### Non-LLM alternatives that exist

| Metric | What it does | LLM required? | Ground truth required? |
|--------|-------------|---------------|----------------------|
| Precision@k / Recall@k | Compare retrieved docs against known-relevant set | No | Yes — need relevance labels |
| NDCG@k / MRR | Ranking quality of retrieved results | No | Yes — need relevance labels |
| ROUGE-L / BLEU | Token overlap between retrieved text and reference | No | Yes — need reference text |
| F1 token overlap | Harmonic mean of precision/recall on shared tokens | No | Yes — need expected answer |
| NonLLMContextPrecision (RAGAS) | String similarity between retrieved and reference contexts | No | Yes — need reference contexts |
| String/keyword presence | Check if specific strings appear in retrieved text | No | Yes — need expected keywords |
| Exact match | Binary: does output match reference exactly? | No | Yes |

**Key insight: every non-LLM metric requires some form of ground truth.** There is no free
lunch — you either pay with an LLM judge or you pay with labeled data.

---

## 2. The oracle decomposition pattern

The most relevant technique comes from "Decomposing Retrieval Failures in RAG for
Long-Document Financial Question Answering" (arXiv:2602.17981, Feb 2026).

**Core idea:** Run the pipeline normally, then re-run generation with an "oracle" retrieval
(the known-correct documents) to establish an upper bound. The gap between oracle and actual
performance is attributed to retrieval; the gap between oracle and perfect is attributed to
generation.

**Their methodology:**
1. Annotate each question with the gold document/page/chunk that contains the answer
2. Measure actual retrieval metrics (DocRec@k, PageRec@k, CtxROUGE-L@k)
3. Run generation with oracle context → establishes generation ceiling
4. Difference between actual and oracle = retrieval failure attribution

**Their key finding:** Document recall was 93% but page recall was only 46%. The dominant
failure mode was not "wrong document" but "right document, wrong chunk." This maps directly
to JustSearch: the battery corpus is small enough that the right document is often retrieved,
but the excerpt doesn't contain the needed facts.

**Applicability to JustSearch:** We don't need the oracle re-run. We already have:
- `requiredFacts` and `expectedKeywords` per scenario (= ground truth for what should appear)
- `toolTraces[].output` containing the actual search results text (= retrieved context)
- `finalResponse` (= generation output)

We can compute the decomposition post-hoc: check if the required facts appear in the search
output. If yes → generation failure. If no → retrieval failure.

---

## 3. Agent trajectory evaluation

LangSmith and LangFuse both support trajectory evaluation — scoring the full sequence of
tool calls. The key findings:

**LangSmith trajectory match** (deterministic, no LLM):
- Compares actual tool call sequence against expected sequence
- Modes: strict (exact order), unordered (same tools any order), subset/superset
- Checks tool names and arguments, NOT output content
- Cannot evaluate whether search results were relevant

**RAGAS Tool Call Accuracy** (deterministic, no LLM):
- Compares tool call sequence + arguments against reference
- Evaluates sequence alignment and argument correctness
- Does NOT inspect tool output content or relevance
- Gap: no framework evaluates intermediate tool output quality

**Anthropic's agent eval guidance:**
- Recommends separating "transcript" (how) from "outcome" (what)
- Prefers deterministic graders where possible (fast, cheap, reproducible)
- For tool calls: verify the tool was called, verify arguments, verify environment state changed
- Does NOT address evaluating search result relevance specifically

**Gap in the field:** No existing framework evaluates the *content quality* of intermediate
tool outputs. Every tool-call metric checks "was the right tool called with the right args?"
but none check "did the tool return useful results?" This is exactly JustSearch's problem.

---

## 4. Practical approaches for JustSearch

Given the constraints (8GB VRAM, 12-min battery, no external LLM, existing tool traces),
here are the viable approaches ranked by feasibility:

### Approach A: Token-overlap retrieval check (simplest, no dependencies)

For each scenario that has `requiredFacts` or `expectedKeywords`:
1. Parse the search tool output from `toolTraces[].output`
2. Check if the required facts/keywords appear in the search output text
3. If facts present in search output but absent from response → **generation failure**
4. If facts absent from search output → **retrieval failure**
5. If facts absent from search output AND response → **retrieval failure** (confirmed)

**Pros:** Zero dependencies. Deterministic. Works on existing captured data. Adds <1s to
evaluation. Directly answers "which layer failed?"

**Cons:** Keyword matching is imprecise — the required fact might be present in a paraphrased
form that keyword match misses. But this is the same limitation the battery already has for
the final response check, so it's consistent.

**Implementation:** ~30-50 lines in `evaluateTranscript()`.

### Approach B: ROUGE-L/F1 overlap between search output and expected answer

Compute token-level F1 between the search tool output text and a reference answer or
reference document content.

**Pros:** More nuanced than binary keyword presence. Captures partial matches.

**Cons:** Requires a reference answer per scenario (currently only `requiredFacts` keywords
exist, not full reference text). ROUGE-L implementation adds a dependency or ~50 lines of
code. Harder to interpret threshold.

**Implementation:** ~80-100 lines + reference answers in scenario definitions.

### Approach C: Ground truth document annotations per scenario

Add a `expectedDocPaths` field to each scenario definition listing the documents that
*should* be returned by search. Parse search output for document paths. Compute
Precision@k / Recall@k / MRR.

**Pros:** Standard IR metrics. Directly measures retrieval quality. Easy to understand.
Cleanly separates retrieval from generation.

**Cons:** Requires manual annotation of expected documents for each scenario. Corpus-dependent
(annotations break if corpus changes). More upfront work.

**Implementation:** ~50 lines in evaluation + annotations in scenario JSON.

### Approach D: Oracle re-run (gold-context generation test)

For each failed scenario, re-run the agent with the search tool stubbed to return the
correct document. If the scenario now passes → retrieval was the problem. If still fails →
generation/comprehension problem.

**Pros:** Definitive attribution. No heuristics.

**Cons:** Doubles battery runtime. Requires search stub infrastructure. Complex.

**Implementation:** Major effort — new execution mode in battery runner.

---

## 5. Comparison table

| Approach | LLM judge? | Ground truth? | Works on traces? | Complexity | Attribution quality |
|----------|-----------|--------------|-----------------|-----------|-------------------|
| **A: Keyword in search output** | No | Uses existing `requiredFacts` | Yes (post-hoc) | ~30 lines | Good for binary: "was the info retrievable?" |
| **B: ROUGE-L/F1 overlap** | No | Needs reference text | Yes (post-hoc) | ~100 lines | Better gradient, but needs reference corpus |
| **C: Expected doc paths** | No | Needs doc annotations | Yes (post-hoc) | ~50 lines + annotations | Best for IR metrics, corpus-dependent |
| **D: Oracle re-run** | No | Needs gold docs | No (requires re-execution) | Major | Definitive, but expensive |
| **RAGAS w/ LLM judge** | Yes (GPT-4) | Optional | Yes | Framework dep | Best quality, but impractical for CI |
| **LangSmith trajectory** | Optional | Needs expected sequence | Yes | Framework dep | Tool sequence only, not output quality |

---

## 6. Empirical validation against battery data

**Note:** The "extraction_miss" classifications below predate the R0 discovery (§7). They
are now understood as "context_loss" — see *Corrected interpretation* below.

Approach A was validated against actual battery results (Qwen3.5-9B Q4_K_M, text mode 11
failures, hybrid mode 10 failures). Each failing scenario was manually classified by checking
whether `requiredFacts`/`expectedKeywords` appear in `toolTraces[].output` for search_index
calls.

### Results (pre-R0-discovery classification)

| Classification | Text mode | Hybrid mode | Description |
|---|---|---|---|
| **extraction_miss** | 6 (55%) | 6 (60%) | Keywords in search output, absent from response |
| **retrieval_miss** | 2 (18%) | 1 (10%) | Keywords genuinely absent from search output |
| **behavioral** | 2 (18%) | 2 (20%) | Handoff failures (forbidden word, action-keyword) |
| **infra_failure** | 1 (9%) | 1 (10%) | DEADLINE_EXCEEDED or tool execution failure |

### Corrected interpretation

The "extraction_miss" cases are actually **context_loss**: the search pipeline returned
relevant content, but context compression destroyed it before the LLM could read it. The
model didn't fail to comprehend the content — it never received the content.

The "retrieval_miss" and "behavioral" classifications remain valid.

**Approach A accuracy: ~70%** for the *battery's* view of the data. But the battery's view
doesn't match the LLM's view, so the classifications are misleading for diagnosing the
agent's actual problem.

### Five structural problems identified

**P1: Truncation confound (most serious).** 50% of search_index calls across failing
scenarios hit the 4110-char output truncation ceiling. exp-005 demonstrates this directly:
in text mode "environment" is absent from truncated output → classified as retrieval_miss.
In hybrid mode the same word IS present → classified as extraction_miss. **Same scenario,
different attribution, because truncation cut off relevant content.**

**P2: Generic keywords match in irrelevant context.** Most `requiredFacts` are single common
words ("agent", "system", "process", "head"). In exp-006, "head" matches "beachhead" via
substring. In exp-007, "agent" matches a filename reference `186-llm-agentic-*`, not
substantive content. Simple `includes()` produces false extraction_miss classifications.

**P3: Action keywords vs content keywords.** Handoff scenarios have `requiredFacts: ["ingest"]`
where "ingest" is an action the agent should *perform*, not content to *find* in search
results. 2-3 failures per run fall in this category and cannot be classified by Approach A.

**P4: Infrastructure failures masquerade as retrieval failures.** DEADLINE_EXCEEDED (gRPC
timeout) produces empty output → looks like retrieval_miss but is actually infra.

**P5: Browse-only scenarios invisible.** exp-015 uses only browse_folders (zero search_index
calls). Must check all tool outputs, not just search_index.

### Implementation requirements (beyond naive 30-line version)

To reach production quality, Approach A needs:
1. **Word-boundary matching** (`\bhead\b` not `includes("head")`) — prevents substring false positives
2. **Truncation flag** — if output length ≥ 4110 chars, mark classification as `uncertain_truncated`
3. **Error detection** — check for `DEADLINE_EXCEEDED`, `Search error:` → classify as `infra_failure`
4. **All-tool coverage** — check browse_folders and ingest_files output, not just search_index
5. **Scenario-type awareness** — handoff scenarios with action-keywords need different logic

Realistic estimate: ~80-100 lines for robust implementation. Still zero dependencies,
still deterministic, still post-hoc on existing traces.

---

## 7. Root issues in the production code

The §6 analysis measured what the battery sees. This section traces what the LLM actually
receives — following the search result from `SearchTool.execute()` through context
compression to the LLM's conversation window.

### Design history: why compression exists

Context compression was introduced in tempdoc 208 (Feb 17) to manage an 8K context window
with a 900-char tool result budget. The design was intentional: `keepLastResults = 0` was
explicitly chosen as the aggressive default. The code comment on `stripSearchExcerpts` says
excerpts "are only useful for the current iteration" — implying the author expected
`keepLast >= 1`, but the default was set to 0.

Tempdoc 213 (Feb 18) raised the tool budget from 900 to 4000 chars and introduced the
per-result budget distribution in `SearchTool.formatResults()`. It did NOT re-evaluate
`keepLastResults`. The A/B quality gate showed "quality non-regression PASS (81.3% both
arms)" — but the battery at that time had an empty index, so both arms measured
infrastructure readiness, not answer quality. The `keepLastResults` parameter became stale:
designed for a 900-char budget, never validated against the 4000-char budget or real search
content.

This is not a "bug" in the traditional sense. It is a design parameter that was appropriate
for its original context (tiny budget, need to preserve context space) but no longer fits
the current budget (4000 chars with 8K+ context). The fix is to update the default, not to
redesign the compression system.

### R0: Context compression destroys search content before the LLM reads it (CRITICAL)

When the agent searches, the search tool returns document titles, file paths, relevance
scores, and text excerpts from matching documents. The excerpts are the important part —
they contain the actual document content the model needs to answer questions.

A compression function runs immediately after the search result is added to the conversation.
With `contextCompressionKeepLastResults = 0` (the default), it compresses every tool result,
including the one just added. Compression does two things: strips all `Excerpt:` lines
(the document content), then reduces the remainder to ~150-400 chars. By the time the LLM
reads the search result on its next turn, all it sees is:

```
[compressed-tool-output originalChars=3500 keptChars=250]
[1] Config Reference (score: 0.85)
    Path: docs/reference/configuration/environment-variables.md
Found 3 results (took 45ms).
```

The model has to answer "what does the configuration documentation explain?" using only
a title and a file path. The paragraphs explaining environment variables, system properties,
and startup flags — all of that was in the excerpts, and the excerpts are gone.

**What the search tool returns** (full output, also what the battery captures):
```
[1] Environment Variables Reference (score: 0.85)
    Path: docs/reference/configuration/environment-variables.md
    Excerpt: "JUSTSEARCH_DATA_DIR — Base directory for all data storage.
     Default: ./data. JUSTSEARCH_AI_EMBED_ENABLED — Enable GPU-accelerated
     embedding at ingest time. JUSTSEARCH_MODEL_PATH — Path to the GGUF
     model file for the chat model."
[2] System Properties Guide (score: 0.72)
    Path: docs/reference/configuration/system-properties.md
    Excerpt: "justsearch.worker.port — gRPC port for the Worker process.
     Default: 50051. justsearch.head.api.port — HTTP port for the Head
     REST API. Default: 37240."
Found 2 results (took 38ms).
```

**What the LLM receives** (after compression with `keepLastResults = 0`):
```
[compressed-tool-output originalChars=680 keptChars=180]
[1] Environment Variables Reference (score: 0.85)
    Path: docs/reference/configuration/environment-variables.md
Found 2 results (took 38ms).
```

The user asked about environment variables. The answer (`JUSTSEARCH_DATA_DIR`,
`JUSTSEARCH_AI_EMBED_ENABLED`, etc.) was in the deleted excerpt lines.

**Confirmed: this was the primary cause of the 52% pass rate.** Changing `keepLastResults`
from 0 to 1 improved the battery from 52.2% to 75.4% mean (N=3 runs). Passing scenarios
were answerable from document titles and paths alone; failing scenarios required specific
facts that only existed in the deleted excerpts. See §8 Step 2 for full results.

**Why the battery missed this.** The battery captures search output via SSE events, which
are emitted *before* compression runs. So the battery's `toolTraces[].output` contains the
full excerpts — content the LLM never saw. The §6 analysis found keywords in this
pre-compression output and concluded "the model saw the content but failed to extract it."
The model never saw the content.

**Technical details:**
- `AgentLoopService.compressToolMessagesForContext()` (line 1383) runs at line 990
- SSE event emitted at line 983 (before compression) — this is what the battery captures
- `contextCompressionKeepLastResults` defaults to 0 via `agent().contextCompressionKeepLastResults()`
- `stripSearchExcerpts()` uses regex `^\s+Excerpt:.*$` to remove all excerpt lines
- `compressToolOutput()` keeps first 3 lines, up to 3 keyword-matching lines, last 2 lines,
  capped at `min(400, max(150, originalLength / 5))` chars

**Fix:** Set `contextCompressionKeepLastResults` to ≥1. The infrastructure already supports
this — only the default needs to change.

### R1: Excerpts show search-query matches, not necessarily the needed facts

This is a search pipeline issue (production code), distinct from the battery evaluation
issues in R4.

Even if R0 is fixed and the LLM sees excerpts, those excerpts are ~400-char windows centered
on where the search query terms appear in the document. If the model searches for
"configuration", the excerpts highlight paragraphs containing "configuration". But the fact
the model actually needs might be "environment variables" — which could be in a completely
different section of the same document with no "configuration" terms nearby.

The excerpt engine (`HighlightingOps`) is well-designed for its purpose: it loads the full
document, finds all query-term matches, clusters them, scores clusters using BM25 IDF +
term diversity, and returns the top 3 best-matching ~400-char windows with sentence-boundary
snapping. But "best-matching for the search query" and "contains the fact the user asked
about" are different things. The search query is what the model chose to type; the needed
fact is what the user's original question requires.

This is a secondary issue — it only matters for scenarios where R0 is fixed and the model
sees excerpts but the relevant fact happens to be far from any search-query term match.

### R2: Multiple truncation layers limit how much content the model sees

Even with R0 fixed, the search output goes through two truncation stages before reaching
the LLM:

1. **SearchTool formatting** (Layer 1): The total output budget is ~4000 chars, divided
   evenly across results. With the default k=3 results, each document gets ~1333 chars for
   its title, path, score, and up to 3 excerpt regions. This is enough to convey one topic
   per document but thin for multi-fact extraction.

2. **Hard context cut** (Layer 2): A 4000-char hard cap on the entire tool output. Rarely
   fires because Layer 1 already stays within budget, but acts as a safety net.

With R0 in place, Layer 3 (compression) further reduces this to ~150-400 chars. Fixing R0
removes Layer 3 from the equation, leaving ~4000 chars of useful content — a reasonable
amount for 3 search results.

### R3: Vector-only search returns no excerpts at all

Pure vector search (semantic search) skips excerpt generation entirely. Instead of
query-focused text windows, it falls back to `content_preview` — the first 4096 chars of
the document stored at ingest time. This gives the model the document's opening paragraphs
regardless of what was searched for.

Hybrid mode (the current default for the agent) does generate excerpts, so this only affects
pure-vector configurations. Noted here for completeness.

### R4: Battery evaluation issues (secondary)

These are real but don't affect the agent's actual performance — only our ability to measure
it:

- **The battery sees content the LLM doesn't.** SSE events capture pre-compression output,
  making battery analysis unreliable for diagnosing what the model actually received.
- **Evaluation keywords are too generic.** `requiredFacts` like "agent", "gpu", "system" are
  topic checks, not precision checks. They match in irrelevant contexts (filenames, substrings).
- **Evaluation is single-exit.** A scenario that fails keyword check is never checked for
  behavioral issues. Can't classify along multiple dimensions.

These matter for building better evaluation later, but R0 is the binding constraint on
agent quality. Fixing evaluation without fixing R0 would give us better measurement of a
problem we already understand.

---

## 8. Recommendation

### Step 1: Fix the compression default ✓

Changed `contextCompressionKeepLastResults` default from 0 to 1 in
`ResolvedConfigBuilder.java` and `agent-battery-core.mjs`. The model now retains its most
recent tool result uncompressed.

### Step 2: Re-run the battery (empirical confirmation) ✓

**R0 confirmed.** Three battery runs with `keepLastResults=1` (text mode, Qwen3.5-9B Q4_K_M):

| Run | Passed | Rate |
|-----|--------|------|
| Baseline (keepLast=0) | 12/23 | 52.2% |
| Run 1 (keepLast=1) | 18/23 | 78.3% |
| Run 2 (keepLast=1) | 19/23 | 82.6% |
| Run 3 (keepLast=1) | 15/23 | 65.2% |
| **Mean (keepLast=1)** | **17.3/23** | **75.4%** |

Per-scenario classification across the 3 runs:

| Classification | Count | Scenarios |
|---|---|---|
| stable_pass | 11 | diag-001, exp-001/002/004/010/012/013/014/016, handoff-001/005 |
| fixed (3/3 pass) | 2 | exp-003, handoff-002 |
| improved (2/3 pass) | 4 | exp-005, exp-008, exp-011, handoff-006 |
| flaky (1/3 pass) | 3 | exp-009, handoff-003, handoff-004 |
| stable_fail (0/3) | 2 | exp-006, exp-015 |
| regressed (1 run) | 1 | exp-007 (noise — passed 2/3) |

### Step 3: Root cause analysis of remaining failures

The fix moved 6 previously-failing scenarios into passing territory (2 reliably, 4 mostly).
Five scenarios remain problematic. Trace analysis reveals three distinct root cause
categories — none related to search retrieval or context compression.

#### Category 1: Evaluation brittleness (exp-006, exp-009)

The model answers correctly but doesn't echo the exact words the evaluator checks.

- **exp-006**: The model lists architecture sections by file title ("System Overview",
  "Knowledge Server") but never says "head" or "process" — the `requiredFacts` terms.
  A semantically correct answer fails the literal substring check. In one run, the first
  search returned irrelevant results (a tempdoc about UX), and the model's attempted third
  search was emitted as text rather than executed (see Category 3).

- **exp-009**: Search returns `VramDetector.java` in excerpts in both passing and failing
  runs — identical tool output. When it passes, the model says "VRAM detection"; when it
  fails, it says "CUDA backend" or "GPU layers". Pure generation nondeterminism against a
  brittle substring check for "vram".

**Fix:** More flexible `requiredFacts` (synonym lists) or semantic evaluation.

#### Category 2: Generation truncation (exp-015)

The browse tool returns all 9 documentation folders. The model consistently lists only the
first 4 alphabetically, then elaborates on the `business` subfolder in detail — never
mentioning "reference" or "how-to". Reproduced identically across all 3 runs. Not
nondeterminism; a consistent behavioral pattern of prioritizing depth over completeness.

**Fix:** Not a quantization issue (identical failure across Q4/Q5/Q6 — see Step 5).
Requires system prompt guidance to enumerate all items before elaborating.

#### Category 3: Tool call format drift (handoff-003, handoff-004)

The model identifies the right action (handoff to organizer → ingest) but sometimes emits
the tool call as raw XML text in the response body instead of a proper function invocation.
The runtime doesn't parse tool calls embedded in text, so the action never executes.

- **handoff-003**: When the organizer fails to execute, the primary agent enters a
  repetition loop — same confirmation paragraph ~15 times until budget exhaustion.
- **handoff-004**: The model's third action (handoff) is emitted as text. Only 2 tool
  calls execute instead of the required 3.

Both pass in other runs when the model stays in tool-calling mode. Nondeterministic.

**Fix:** Not a quantization issue (identical failure across Q4/Q5/Q6 — see Step 5).
Could be mitigated by runtime-side detection of tool call patterns in text responses.

### Step 4: Battery runner optimizations ✓

Added two CLI flags to `dag-runner-agent-battery.mjs` to reduce iteration time:

- **`--reuse-backend`**: Probes for a running dev stack via `dev-runner-lifecycle.mjs status`.
  If healthy, injects `skipIf` on `start-backend` and `stop-backend` steps and pre-patches
  API URL placeholders. Saves ~302s per run (46% of wall time).

- **`--scenario-exclude <ids>`**: Comma-separated scenario ID prefixes to skip. Mirrors the
  existing `--scenario-filter` with opposite logic. Forwarded to `agent-battery-core.mjs`
  which applies include-then-exclude filtering. Saves ~60s per run by skipping known-failing
  scenarios during regression testing.

Both flags also appear in `--dry-run` output. Tests: sections G (exclude propagation) and
H (reuse-backend skipIf injection) added to `dag-runner-agent-battery.test.mjs` (101 total).

### Step 5: Quantization comparison (Q4 vs Q5 vs Q6) ✓

Tested whether higher quantization improves the remaining failures. Downloaded Q5_K_M
(6.73 GB) and Q6_K (7.69 GB) alongside the existing Q4_K_M (5.87 GB). All runs used
`keepLastResults=1` and the same 23-scenario battery (N=3 per quantization level).

| Metric | Q4_K_M | Q5_K_M | Q6_K |
|--------|--------|--------|------|
| Per-run scores | 18/19/15 | 16/18/18 | 19/17/17 |
| **Mean pass rate** | **75.4%** | **75.4%** | **76.8%** |
| VRAM usage | 7.1 GB | 7.1 GB | 7.9 GB |

Per-scenario detail on originally-failing scenarios:

| Scenario | Q4 | Q5 | Q6 | Verdict |
|----------|----|----|----|----|
| exp-006 (eval brittleness) | 1/3 | 0/3 | 1/3 | Broken across all |
| exp-009 (eval brittleness) | 2/3 | 3/3 | 2/3 | Flaky, noise |
| exp-015 (generation truncation) | 0/3 | 0/3 | 0/3 | **Persistently broken** |
| handoff-003 (tool format drift) | 1/3 | 3/3 | 3/3 | Improved in Q5+Q6 |
| handoff-004 (tool format drift) | 0/3 | 0/3 | 0/3 | **Persistently broken** |

**Conclusion: quantization is not the bottleneck.** Q4→Q5→Q6 produces no meaningful
improvement in aggregate pass rate. The two persistently-broken scenarios (exp-015,
handoff-004) fail identically across all quantization levels. The remaining failures
are model capability limitations at the 9B parameter count, not precision loss from
quantization. No model change warranted — the default Q4_K_M remains appropriate.

### How we got here

The investigation started with the question "how do we decompose agent failures into
retrieval vs comprehension?" The research (§1-§5) found the standard approaches. Empirical
validation (§6) appeared to show that most failures were comprehension problems. But tracing
the actual production code (§7) revealed that the model never received the content we thought
it was failing to comprehend. The real problem was upstream: a compression default that
destroys search content before the model reads it.

---

## Sources

### RAG evaluation frameworks
- [RAGAS documentation — available metrics](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/)
- [RAGAS paper (arXiv:2309.15217)](https://arxiv.org/abs/2309.15217)
- [DeepEval RAG evaluation guide](https://deepeval.com/guides/guides-rag-evaluation)
- [TruLens RAG Triad](https://www.trulens.org/getting_started/core_concepts/rag_triad/)
- [ARES automated evaluation (arXiv:2311.09476)](https://arxiv.org/abs/2311.09476)
- [Evidently AI RAG evaluation guide](https://www.evidentlyai.com/llm-guide/rag-evaluation)
- [Qdrant RAG evaluation best practices](https://qdrant.tech/blog/rag-evaluation-guide/)
- [RAG Evaluation 2026 — Label Your Data](https://labelyourdata.com/articles/llm-fine-tuning/rag-evaluation)

### Agent evaluation
- [Anthropic — Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [LangSmith trajectory evaluations](https://docs.langchain.com/langsmith/trajectory-evals)
- [LangFuse agent evaluation cookbook](https://langfuse.com/guides/cookbook/example_pydantic_ai_mcp_agent_evaluation)
- [RAGAS agent metrics (Tool Call Accuracy)](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/agents/)
- [DeepEval CI/CD integration for RAG](https://www.confident-ai.com/blog/how-to-evaluate-rag-applications-in-ci-cd-pipelines-with-deepeval)

### Retrieval failure decomposition
- [Decomposing Retrieval Failures in RAG (arXiv:2602.17981)](https://arxiv.org/abs/2602.17981)
- [Promptfoo RAG evaluation](https://www.promptfoo.dev/docs/guides/evaluate-rag/)
- [Deepset — metrics for QA evaluation](https://www.deepset.ai/blog/metrics-to-evaluate-a-question-answering-system)

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 74 days at audit time.

