---
title: "213: Agent Search Context Quality"
type: tempdoc
status: done
created: 2026-02-18
updated: 2026-02-18
---
# 213: Agent Search Context Quality

**Date:** 2026-02-18
**Status:** in-progress (Approach A shipped; battery regression check invalid — needs ingestion fix; Approach B not started)
**Scope:** Agent search tool — content formatting for LLM consumption
**Origin:** Post-merge review of tempdoc 146 revealed the agent path (`SearchTool.formatResults`) is completely independent from `content_preview` and severely under-serves the LLM.

---

## Problem

The AI agent's search tool returns **200 characters of content per result** in a **900-character total budget**. After title/path/score overhead, the agent gets roughly 80 usable characters per result — approximately one sentence of evidence. This is far below every industry recommendation for RAG context quality.

The agent answers user questions from sentence fragments, which forces it to either hallucinate missing context or give vague non-committal answers.

### Current agent search output format

```
[1] My Document Title (score: 0.92)
    Path: /home/user/docs/my-document.md
    Excerpt: "...roughly 200 chars of query-focused text from the first excerpt region..."

[2] Another File (score: 0.74)
    Path: /home/user/docs/other.pdf
    Excerpt: "...another 200-char excerpt..."

Found 2 results (took 12ms).
```

### Key code paths

| File | Role |
|------|------|
| `modules/app-agent/.../tools/SearchTool.java` | Formats search results for LLM |
| `modules/app-agent/.../AgentLoopService.java` | Truncates tool results to 900 chars |

---

## How the agent consumes search results today

`SearchTool.execute()` (line 141) builds a `KnowledgeSearchRequest` with `includeExcerpts = true`, dispatches it to the knowledge index, and formats results via `formatResults()` (line 168).

Per result, the agent sees:
- **Title** (from `fields["title"]`, fallback to `fields["filename"]`, then `"(untitled)"`)
- **Score** (float, e.g. `0.92`)
- **Path** (from `fields["path"]`)
- **First excerpt only** — `excerptRegions[0].text()`, truncated to 200 chars, newlines replaced with spaces, quotes replaced with apostrophes

The agent does **not** see:
- `content_preview` (the 4096-char stored preview field — frontend-only)
- Multiple excerpt regions (only `[0]` is used)
- Modification date, file type, section headings
- Any structured metadata beyond title and path

`AgentLoopService.truncateForContext()` (line 892) then caps the entire tool result at `MAX_TOOL_RESULT_CHARS = 900`.

### Why `content_preview` is not used

`SearchTool` was written independently from the UI result rendering. It chose `excerptRegions` because they are query-focused (they show where the query matched), while `content_preview` is always the document start. This is a reasonable choice — but the 200-char truncation and 900-char total budget negate the benefit.

---

## Industry research: What agents need from search results

### Core principle: Self-containedness

Every major RAG framework (LlamaIndex, LangChain, Haystack, Azure AI Search) converges on the same requirement: each retrieved passage must be **semantically self-contained**. The LLM cannot click through to the source document. If a passage contains "it was updated last quarter" without identifying what "it" is or when "last quarter" was, the agent will hallucinate or refuse.

A self-contained passage answers:
- Who/what is this about? (entity identification)
- When does this apply? (temporal anchor)
- Where does this come from? (source identity)
- What does this say? (actual content)

### Anthropic's contextual retrieval

Anthropic's most-cited practical contribution: prepend a **50-100 token context header** to each retrieved chunk before it reaches the LLM. This header identifies the document, its temporal scope, and entity anchors.

```
BEFORE:
"Revenue grew 23% quarter-over-quarter, driven by enterprise adoption."

AFTER:
"[From ACME Corp Q2 2023 earnings report, Software Division]
Revenue grew 23% quarter-over-quarter, driven by enterprise adoption."
```

Result: retrieval failure rate reduced by 49%. With reranking, 67%.

Source: https://www.anthropic.com/news/contextual-retrieval

### Optimal chunk size: 300-600 tokens

Multiple sources converge on 300-600 tokens (~1200-2400 characters) per retrieved passage:

- **< 128 tokens**: Loses context, anaphoric references unresolvable
- **256-800 tokens**: Enough for semantic completeness, precise embeddings
- **> 1024 tokens**: Dilutes meaning, triggers "lost in the middle" within the chunk

JustSearch's current budget: ~50 tokens per result (200 chars). This is 6-12x below the minimum.

Sources: Pinecone chunking guide, LlamaIndex basic strategies, Databricks long-context RAG research, Chroma context rot research

### Structured format: XML tags recommended

Anthropic's prompt engineering guidance recommends `<document>` XML tags for multi-document context:

```xml
<documents>
  <document index="1">
    <source>path/to/file.md</source>
    <title>Q3 Performance Review</title>
    <content>
      [passage text]
    </content>
  </document>
</documents>
```

Benefits: prevents source blending, enables citation generation, aligns with Claude's training on XML-structured prompts.

Source: https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/use-xml-tags

### "Lost in the middle" ordering

Stanford/UW research shows LLMs exhibit a U-shaped attention curve — they attend most to the beginning and end of the context. For multiple results: place best-ranked first, second-best last, lower-ranked in the middle.

Source: Liu et al., 2023 (arxiv 2307.03172)

### What code assistants do

GitHub Copilot and Cursor both include **file path with every code snippet** as structural metadata the LLM actively reasons about. Both use semantic similarity to retrieve relevant files and present them with enough context for the LLM to understand the code's role in the project.

### Key difference: agents vs humans

| Concern | Human preview | Agent context |
|---------|--------------|---------------|
| Primary goal | Decide whether to click through | Derive complete facts without clicking |
| Length | Short (1-4 lines) | Long (300-600 tokens) |
| Highlights | Bold query terms for scannability | Unnecessary — LLM reads all text |
| Metadata | Sidebar/secondary | Must be inline — agent doesn't read sidebars |
| Self-containedness | Not required (user can open document) | Critical (agent has no other source) |
| Truncation | Tolerable (user sees enough to decide) | Devastating (agent loses evidence) |

### Depth vs breadth: fewer rich results beat more thin ones

EMNLP 2025 ("More Documents, Same Length") isolated document count from context length: increasing document count at fixed total tokens reduced performance by **up to 20%** on multi-hop QA. The "Lost in the Middle" paper found that with 20+ documents, retrieval can actively hurt the model — accuracy dropped **below closed-book performance**. NAACL 2024 industry benchmark confirmed: `TOP-K-3 > TOP-K-5 > TOP-K-20`.

**For JustSearch:** At a fixed token budget, 3 results with rich context beats 5 results with thin context. The default `limit: 5` in `SearchTool` should be reconsidered — or at minimum, content depth per result should be prioritized over result count.

Sources: Liu et al. TACL 2024 (arxiv 2307.03172), EMNLP 2025 (arxiv 2503.04388), NAACL 2024 Industry (aclanthology.org/2024.naacl-industry.23)

### Document summary + passage: use chunk-specific context, not blanket summaries

Anthropic's contextual retrieval prepends a **chunk-specific** context header (50-100 tokens) — not a generic document summary. The header identifies the document, temporal scope, and entity anchors for that specific passage. This reduced retrieval failure by 49%.

RAPTOR (ICLR 2024) takes a different approach: build a hierarchical tree of summaries and retrieve from any level. The summary serves as a **routing layer** — the agent retrieves the summary first, then drills into passages. The summary is not concatenated with the passage in the LLM's context.

**For JustSearch:** Don't concatenate `content_preview` + excerpt regions in a single result. Instead, use `content_preview` as a fallback when excerpts are unavailable, and consider prepending chunk-specific context (document title + section heading) to each excerpt. The description extracted from frontmatter (tempdoc 146) could serve as this context header.

Sources: Anthropic Contextual Retrieval (2024), RAPTOR ICLR 2024 (arxiv 2401.18059)

### Single-pass vs multi-step retrieval: agents should iterate

All three major frameworks (LlamaIndex, LangChain, Semantic Kernel) recommend **multi-step agentic retrieval** over single-pass:

1. **LlamaIndex**: Two-tier architecture — document-level summary index for routing + vector index for passage retrieval. Agent calls summary tool first, drills into relevant documents.
2. **LangChain/LangGraph**: Adaptive RAG with `grade_documents` step — retrieve → grade relevance → optionally rewrite query → retrieve again → generate.
3. **Semantic Kernel**: Pre-fetched vs dynamic retrieval functions with evaluation loops.

**For JustSearch:** The agent already has `search_index` and `file_operations` tools. The "search → grade → read full document" pattern is achievable without new tools. However, if search results are too thin (200 chars), the agent can't grade relevance and doesn't know which documents are worth reading in full. **The search tool must return enough content for the agent to make routing decisions** — even if the agent will subsequently read the full document.

Sources: LlamaIndex multi-document agents docs, LangChain agentic RAG docs, Semantic Kernel retrieval plugins docs

### Query-type-aware formatting: research supports it, but uniform is a valid baseline

Typed-RAG (NAACL 2025) achieved **~2x MRR improvement** by classifying queries into types (evidence-based, comparison, experience, reason, instruction, debate) and applying type-specific retrieval strategies. NirDiamant's Adaptive Retrieval implements four classes: factual (narrow k), analytical (broad k), opinion (diverse retrieval), contextual (background-heavy).

**For JustSearch:** Query-type classification is a later optimization. A uniform format improvement (Approach A/B) should come first — the current 200-char budget is so far below baseline that type-awareness is premature optimization. Revisit after the baseline is fixed.

Sources: Typed-RAG NAACL 2025 (arxiv 2503.15879), NirDiamant RAG Techniques (github.com/NirDiamant/RAG_Techniques)

---

## Gap analysis: JustSearch agent vs best practices

### What aligns

| Practice | Status |
|----------|--------|
| File path included per result | Done |
| Title included per result | Done |
| Numbered result delineation | Done |
| Query-focused excerpts (not static preview) | Done |
| Hybrid retrieval available | Done |

### Critical gaps

| Gap | Severity | Current | Recommended |
|-----|----------|---------|-------------|
| **Total tool result budget** | **Critical** | 900 chars (configurable via env var) | 4000-6000 chars (~12-18% of 8K context) |
| **Per-excerpt length** | **Critical** | 200 chars (~50 tokens) | 800-1200 chars (~200-300 tokens) per region |
| **Vector search: zero content** | **Critical** | No excerpt, no fallback — agent sees only title/path | Use `content_preview` as fallback (stored field, available via `hit.fields()`) |
| **Excerpt regions per result** | **High** | 1 of 3 available (backend computes 3, agent uses 1) | Use all 3 regions — they're already computed |
| **Structured format** | **Moderate** | Flat indented text | XML `<document>` tags |
| **Temporal/type metadata** | **Moderate** | None (but `modified_at`, `file_kind` are stored fields) | Include modification date and file kind |
| **Default result count** | **Moderate** | 5 results | 3 results (research: TOP-K-3 > TOP-K-5 at fixed budget) |
| **Newline preservation** | **Low-moderate** | Stripped (`.replace("\n", " ")`) | Preserve paragraph structure |
| **Result ordering for LLM** | **Low** | Score descending | Best-first, second-best-last |

---

## Approaches

Three approaches in increasing scope:

### Approach A: Raise limits + use all regions + add fallback (minimal change)

Changes to `SearchTool.java`:
1. Increase per-excerpt truncation from 200 to 800-1200 chars
2. Loop over all `excerptRegions` (up to 3) instead of only `[0]`
3. Add `content_preview` fallback when `excerptRegions` is empty (vector search)
4. Reduce default result limit from 5 to 3 (research: TOP-K-3 > TOP-K-5 at fixed budget)

Changes to `AgentLoopService.java`:
5. Increase `MAX_TOOL_RESULT_CHARS` default from 900 to 4000-6000

Five targeted edits. No new classes, no new fields, no architectural changes.

**Pros:** Minimal code change, immediate improvement, fixes the vector-search-zero-content bug.
**Cons:** Still flat text, no metadata enrichment, no self-containedness guarantee.

### Approach B: Structured format + metadata (moderate change)

Rewrite `formatResults()` to emit XML-structured output with metadata. Include modification date and document type from stored fields. Use multiple excerpt regions. Raise total budget.

```xml
<search_results query="...">
  <result index="1" score="0.92">
    <title>My Document Title</title>
    <path>/home/user/docs/my-document.md</path>
    <modified>2026-01-15</modified>
    <type>markdown</type>
    <excerpts>
      <excerpt line="42">relevant passage from the document...</excerpt>
      <excerpt line="187">another relevant passage...</excerpt>
    </excerpts>
  </result>
</search_results>
```

**Pros:** Aligns with Anthropic's XML guidance, enables citation, includes metadata.
**Cons:** More code change, need to verify stored fields include date/type.

### Approach C: Agent-specific content assembly (larger change)

Build an agent-optimized content pipeline separate from the UI path. Prepend context headers (Anthropic contextual retrieval style). Use `content_preview` as supplementary context when excerpts are insufficient. Implement sentence-boundary-aware truncation.

**Pros:** Full alignment with research, best possible agent answer quality.
**Cons:** Significant new code, new content assembly path to maintain.

### Recommendation

Start with **Approach A** as an immediate fix — the 900-char budget is the bottleneck that makes everything else moot. Research strongly supports this: the "More Documents, Same Length" paper (EMNLP 2025) shows that even at fixed context length, fewer richer results beat more thin ones. Approach A should also reduce the default result count from 5 to 3, consistent with the `TOP-K-3 > TOP-K-5` finding.

Approach B (structured format) is the natural follow-up — XML tags align with how the LLM was trained and enable citation generation. The metadata fields are already stored in Lucene.

Approach C (multi-step retrieval) is the long-term direction. All major frameworks recommend it. JustSearch already has the tools (`search_index` + `file_operations`) — but the search tool must return enough content for the agent to make routing decisions about which documents to read in full. Approach A is a prerequisite for Approach C to work.

---

## Relationship to other tempdocs

- **Tempdoc 146** (frontmatter stripping): Complete. The `content_preview` pipeline it built is used by UI/reranker/summary, not by the agent. The agent path is independent.
- **Tempdoc 197** (agent system expansion): Covers broader agent capabilities. This tempdoc focuses narrowly on search result formatting.
- **Tempdoc 210** (agent infra/external fit research): Research on agent frameworks. Orthogonal — this is about content quality, not infrastructure.

---

## Existing eval infrastructure survey

Five separate eval frameworks exist. None test the specific thing this tempdoc wants to change.

### What exists

| System | Tempdoc | What it measures | Key artifacts |
|--------|---------|-----------------|---------------|
| **RAG Quality Eval** | 198 | Answer quality: 7 metrics (fact coverage, faithfulness, citations, similarity) across 24 queries | `RagQualityEvalTest.java`, `diff-rag-eval-suite.mjs`, baseline JSON, ratchet policy |
| **Agent Live Battery** | 208 | Task completion: 16 scenarios, pass/fail, trajectory conformance, loop detection | `run-agent-live-battery.mjs`, `build-agent-live-scorecard.mjs`, schema v2, Mode B gate at 81.25% |
| **Search Ranking** | 135 | Retrieval quality: BEIR (Recall@10=0.825 hybrid), golden corpus (Recall@3≥0.8), hand-labeled rank eval | `beir-eval-win.ps1`, `GoldenCorpusIntegrationTest.java`, baselines for scifact/arguana/nfcorpus |
| **Performance Claims** | — | Throughput (A/B), UX latency (C), LLM inference (D) | `EngineIndexBench.java`, suite runners, `build-benchmark-scorecard.mjs` |
| **Agent Efficiency** | 118 | Process hygiene: 7 behavioral signals, cost tracking across 52 sessions | `dispatch.mjs`, `analyze-session.mjs`, `score-session.mjs`, acknowledged non-predictive of outcomes |

Plus overnight automation (`overnight-rag-ai-queue-win.ps1`) sequencing RAG eval + Claim D + Agent battery.

### The blind spot

- **RAG eval (198)** calls `searchHybrid()` directly and assembles context itself — it **bypasses `SearchTool.formatResults()` entirely**. Tests retrieval + LLM answer quality but not the agent's content formatting path.
- **Agent live battery (208)** runs the full agent loop including `SearchTool`, but measures **task completion (pass/fail)**, not answer factual accuracy. A scenario passes if the agent calls the right tools and produces a plausible response — not whether the answer contains correct facts.
- **Search ranking (135)** tests whether the right documents appear in results, not what content from those documents reaches the LLM.

**Result:** No existing system measures agent answer quality as a function of search result formatting. This is the exact measurement needed to validate tempdoc 213 changes.

### No prior unification attempt

The five systems evolved independently for good reason — they measure genuinely different things. The overnight script sequences them but doesn't unify them. No evidence of a failed unification attempt in git history or tempdocs.

### Two paths to close the blind spot

**Path 1: Extend agent live battery with answer quality metrics.**
Add fact-checking assertions to search-dependent scenarios (exp-003/004/005/006/008/009/010). Instead of just pass/fail, check whether the agent's response contains expected facts from specific documents. Reuses the existing `agent-live-battery-scenarios.v1.json` format + `KeywordPresenceChecker` from RAG eval.

**Pros:** Tests the actual agent path including `SearchTool.formatResults()`. Measures what we want to change.
**Cons:** Requires live LLM, nondeterministic, slower iteration than unit tests.

**Path 2: Extend RAG eval with a `SearchTool.formatResults()` variant.**
Add a test mode to `RagQualityEvalTest` that formats context through `SearchTool.formatResults()` (with configurable excerpt length, k, format) instead of direct `searchHybrid()` context assembly. Compare answer quality metrics across formatting configurations.

**Pros:** Controlled experiment — same queries, same model, different formatting. 7 existing metrics. Faster iteration (no live backend needed).
**Cons:** Requires refactoring `SearchTool.formatResults()` to be callable from test context. May not capture the full agent loop (multi-turn, tool chaining).

### Path 2 feasibility (investigated)

The RAG eval's context assembly is **cleanly separated** and easily swappable:

```
evaluateQuery() pipeline (RagQualityEvalTest.java):
  1. retrieveDocuments(question, 5)     → List<String> docIds     [line 278]
  2. buildContext(query.corpusDocs)      → ContextResult            [line 287]
  3. buildRagPrompt(ctx.text, question)  → String prompt            [line 289]
  4. session.summarizeChunk(request)     → ChunkResponse            [line 291]
  5. score(answer, ...)                  → QueryEvalResult           [lines 311-375]
```

`buildContext()` (lines 430-452) formats as `[Document N]\n<content>\n\n` with 1000-char truncation per doc. This is the only method that touches content formatting — steps 1, 3, 4, 5 are format-agnostic.

**To test tempdoc 213 changes:** Add a variant `buildAgentStyleContext()` that formats like `SearchTool.formatResults()` — title, path, score, excerpt(s). Parameterize the test to run the same 24 queries through both formatters. Compare all 7 metrics. The diff directly answers: "does giving the model 800 chars of excerpt vs 200 chars improve fact coverage, faithfulness, and citation quality?"

**Estimated scope:** ~100-200 lines added to `RagQualityEvalTest.java`. No new files, no new dependencies. The 7-metric scoring pipeline, baseline diff tool, and ratchet policy all work unchanged — they operate on the output, not the formatting.

**Important nuance:** The RAG eval currently feeds `query.corpusDocs` (expected docs, not retrieved docs) into context assembly — deliberately isolating generation quality from retrieval quality. To also test the retrieval→formatting interaction, you'd feed `retrievedDocIds` instead. Both variants are useful:
- Expected docs + agent formatting = "does format affect answer quality?"
- Retrieved docs + agent formatting = "does format affect end-to-end quality?"

### Path 1 feasibility (investigated)

12 of 16 agent battery scenarios are search-dependent (`search_index` in `expectedToolSubset`). But assertions are **too shallow** for tempdoc 213:

| Gate | What it checks | Useful for 213? |
|------|---------------|-----------------|
| Error present | Agent crashed | No |
| Empty response | Agent returned nothing | No |
| Missing keywords | Does word "gpu" appear anywhere in answer? | **No** — passes regardless of excerpt length |
| Min tool calls | Were N+ tools called? | No |

`expectedToolSubset` is **informational only** — never enforced as pass/fail. No fact checking, no citation checking, no accuracy checking.

To make Path 1 useful for tempdoc 213, each search-dependent scenario would need `required_facts` (like RAG eval's `KeywordPresenceChecker`) — a more invasive format change to `agent-live-battery-scenarios.v1.json` and the evaluator.

**Recommendation:** Path 2 is clearly the faster, more controlled approach for validating parameter choices. ~100-200 lines vs restructuring 12 scenarios + the evaluator. Path 1 is the right long-term investment for ongoing regression tracking but needs its own enrichment work first. They're not mutually exclusive — Path 2 first, Path 1 later.

---

## Resolved questions

### 1. LLM context window

Default context: **8192 tokens** (`EnvRegistry.CONTEXT_SIZE` default), overridden at runtime by llama-server's `/props` endpoint if available. Per-response generation budget: **8192 tokens** (`DEFAULT_MAX_TOKENS`). The model is whatever llama-server has loaded — not hardcoded.

At 8K context, after system prompt + conversation history, the search tool result budget of 900 chars (~225 tokens) is conservatively small. Even accounting for multi-turn conversations, **4000-6000 chars (~1000-1500 tokens)** of search results would be safe — roughly 12-18% of the context window.

Code: `AgentLoopService.java:45` (`DEFAULT_MAX_TOKENS`), `AgentLoopService.java:153-158` (context window computation), `InferenceConfig.java:80` (default 8192).

### 2. Stored fields available for metadata enrichment

All needed fields are **stored in Lucene** and available via `hit.fields()`:

| Field | Stored | Available to agent |
|-------|--------|-------------------|
| `modified_at` | Yes (long, unix epoch) | Yes — needs formatting |
| `file_kind` | Yes (keyword) | Yes |
| `mime` | Yes (keyword) | Yes |
| `chunk_heading_text` | Yes (keyword) | Yes — section heading for chunks |
| `chunk_heading_level` | Yes (long) | Yes |
| `filename` | Yes (keyword) | Yes |
| `content_preview` | Yes (text, 4096 chars) | Yes — but not currently used by `SearchTool` |

Schema: `SSOT/catalogs/fields.v1.json`

### 3. Vector search produces zero content for the agent

When `mode == SEARCH_MODE_VECTOR`, `SearchOrchestrator.java:484` skips excerpt computation entirely. `SearchTool.formatResults()` then skips the excerpt line when `excerptRegions` is empty. The agent sees **only title, score, and path** — no content whatsoever.

This is the worst case: the agent is told a document is relevant (by vector similarity) but given zero evidence about what it contains. It must either hallucinate content or refuse to answer.

**`content_preview` should be the fallback** when excerpt regions are empty. It's a stored field available via `hit.fields().get("content_preview")`.

### 4. Backend returns 3 excerpt regions, agent uses 1

`SearchOrchestrator.java:494` passes `maxRegions = 3` to `HighlightingOps.computeExcerptRegions()`. Each region is a ~400-char window with sentence-boundary snapping. But `SearchTool.formatResults()` only takes `excerptRegions.get(0)` and truncates it to 200 chars.

The agent discards ~1000 chars of query-relevant context that the backend already computed.

## Confidence Assessment

### What I'm confident about

The code changes are trivial — constant adjustments, a loop change, a fallback branch. The direction is right: more content per result, vector fallback, use all computed regions. The research consistently points the same way across multiple independent sources.

### What I'm NOT confident about

**1. The numbers are ungrounded for this system.** All research tested cloud LLMs (GPT-4, Claude, Llama-70B+). JustSearch runs a local model via llama-server — likely 7B-13B parameters. Smaller models have weaker attention, different optimal chunk sizes, and may not benefit from XML formatting the way Claude does. The "Lost in the Middle" effect is model-dependent. I have zero calibration data for the specific model this system uses.

**2. Context budget math is hand-wavy.** I proposed "4000-6000 chars is safe at 8K context" without tracing an actual multi-turn conversation. If the agent does 3 search calls across a conversation, each returning 5000 chars, that's 15K chars of search results competing with history. Context pressure in multi-turn interactions could be severe and is unmeasured.

**3. `MAX_TOOL_RESULT_CHARS` is global.** Raising it from 900 to 4000-6000 affects ALL tools (browse_folders, file_operations, ingest_files), not just search. Other tools may generate large outputs that shouldn't consume that much context. The impact on non-search tools is unanalyzed.

**4. The k=5→3 recommendation assumes a fixed budget.** The research finding (TOP-K-3 > TOP-K-5) was at constant total tokens. We're simultaneously raising the budget, which relaxes the constraint that made k=3 better. With a larger budget, k=5 with richer content might be fine.

**5. XML formatting overhead on small models.** Anthropic's XML guidance is for Claude (specifically trained on XML). A 7B local model may not parse XML any better than flat text. The 50-80 tokens of XML overhead is proportionally larger in an 8K window than in 128K.

**6. No eval framework exists.** Without before/after answer quality measurement, I'd be deploying research-backed hypotheses as validated changes. The research provides direction but not calibration for this specific system.

**7. Multi-step retrieval is aspirational.** A 7B-13B local model may not reliably decide "I should read document X in full" — multi-step retrieval requires strong tool-use reasoning that smaller models may lack.

### Implication

The direction is right. The calibration is guesswork. **An eval framework is a prerequisite for confident implementation** — without measurement, any parameter choice is faith-based engineering.

---

## Implementation Progress

### Step 1: RAG eval context format extension

Extended `RagQualityEvalTest.java` with an agent-style context formatter, parameterized via system property `-Drag.eval.context.format`.

**Initial version (v1):** Varied excerpt length only (`agent-excerpt-200`, `agent-excerpt-400`, etc.). Added `buildAgentStyleContext()`, `parseExcerptChars()`, context format switching in `evaluateQuery()`, metadata in output JSON, differentiated output filenames, and workload comparability check in `diff-rag-eval-suite.mjs`.

**Critical self-review found the v1 eval doesn't answer the right questions:**

| Question tempdoc 213 needs answered | v1 eval answers it? | Why not |
|--------------------------------------|---------------------|---------|
| Does the 900-char total truncation destroy answer quality? | **No** | v1 gives the LLM all formatted results without any total budget cap. 5 results at agent-excerpt-200 produces ~1500 chars; the real agent truncates at 900 mid-text. |
| Is k=3 better than k=5 at a fixed total budget? | **No** | v1 feeds `query.corpusDocs` (fixed count). Never varies result count. |
| What's the optimal allocation of a fixed budget across result count and excerpt length? | **No** | v1 varies excerpt length in isolation. The real design question is: "given X total chars, 3 results at 800 chars or 5 at 400?" |
| Does vector search zero-content degrade answers? | **No** | No title-path-only variant. |
| Does more content per result help? | Yes | But the answer is obviously "yes" — this isn't useful without budget constraints. |

**The fundamental issue:** v1 tests format changes in isolation from the budget constraint that defines the agent's actual experience. It answers "does more content help?" (obvious: yes) instead of "which allocation of a fixed budget produces the best answers?" (the actual design question).

**Fix (v2):** Reworked the format string to encode all three dimensions: `agent-kN-excM-budB` (e.g. `agent-k3-exc800-bud4000`). `buildAgentStyleContext()` now accepts `maxResults`, `maxExcerptChars`, and `maxTotalChars`. The total budget truncation simulates `AgentLoopService.truncateForContext()` — cutting the formatted output at `maxTotalChars`, exactly as the real agent does.

**Usage (v2):**
```bash
# Current agent experience: k=5, 200-char excerpts, 900-char total budget
-Drag.eval.context.format=agent-k5-exc200-bud900

# Raise budget only
-Drag.eval.context.format=agent-k5-exc200-bud4000

# Fewer results, richer excerpts, same raised budget
-Drag.eval.context.format=agent-k3-exc800-bud4000

# Title+path only (simulates vector search zero-content)
-Drag.eval.context.format=agent-k5-exc0-bud900
```

**Compilation verified.** Spotless clean. No new dependencies.

### Step 2: Run baseline comparison (partial - variant B complete)

Run matrix:

| Run | Format | Status | Tests |
|-----|--------|--------|-------|
| A | `document-block` | Done (prior runs) | Existing baseline (1000 chars/doc, no truncation) |
| B | `agent-k5-exc200-bud900` | **Done** | Current agent experience |
| C | `agent-k5-exc200-bud4000` | Pending | Budget raise only - isolates budget vs format |
| D | `agent-k3-exc800-bud4000` | Pending | Fewer results, richer excerpts - tests allocation |
| E | `agent-k5-exc0-bud900` | Pending | Zero-content - simulates vector search gap |

#### Variant B results (current agent experience)

Run completed 2026-02-18. Model: Qwen3-4B-Instruct-2507-Q4_K_M. 24 queries. Output: `build/test-results/rag-eval/rag-eval-result.v1.agent-k5-exc200-bud900.json`.

**Aggregate comparison with document-block baseline:**

| Metric | Doc-block (best) | Doc-block (recent) | Agent B (k5-exc200-bud900) | Delta vs recent |
|--------|-----|-----|-----|------|
| fact_coverage | 0.747 | 0.653 | **0.424** | **-35%** |
| forbidden_fact_rate | 0.0 | 0.0 | 0.0 | 0% |
| answer_similarity | 0.852 | 0.721 | 0.795 | +10%* |
| retrieval_recall | 1.0 | 0.833 | 1.0 | +20%* |
| faithfulness | 0.403 | 0.375 | **0.215** | **-43%** |
| citation_precision | 0.458 | 0.417 | **0.333** | **-20%** |
| citation_recall | 0.438 | 0.417 | **0.313** | **-25%** |

*Answer similarity and retrieval recall are higher for agent format because the model correctly refuses to answer when given insufficient context - responding "Not found" which matches the golden answer for trick/negative queries. This inflates these two metrics but doesn't reflect useful performance.

**Key finding: 200-char excerpts with 900-char total budget lose 35-43% of fact coverage and faithfulness** compared to document-block format (1000 chars/doc, no total budget). The model repeatedly answers "The provided context does not include..." or "Not found." for questions that document-block format handles successfully.

**Per-query patterns observed:**
- Queries about specific facts (rag-002 attention formula, rag-003 Adam beta values, rag-008 Transformer params) get 0.0 fact_coverage with agent format - the 200-char excerpt doesn't reach those details
- Queries about general concepts (rag-005 type erasure, rag-015 connection pooling) still succeed - the excerpt captures the concept even if truncated
- Multi-source queries (rag-011 HikariCP+Kafka reliability, rag-020 timeout+retry) consistently fail - 900-char total budget cuts off before the second source's content appears

**Implication:** The research prediction (section "What I'm NOT confident about", point 1) was partially validated - the small model (4B) shows the same directional effect as the large-model research: more content per result improves fact extraction. The magnitude (-35% fact coverage) is larger than expected, likely because the 4B model is less capable of inferring facts from fragments.

#### Variant D results (proposed Approach A)

Run completed 2026-02-18. Model: Qwen3-4B-Instruct-2507-Q4_K_M. 24 queries. Output: `build/test-results/rag-eval/rag-eval-result.v1.agent-k3-exc800-bud4000.json`. Note: ran with stub-jaccard similarity (env var issue), so answer_similarity is not comparable to B's embedding-based value. All other metrics are directly comparable.

**B vs D comparison (current agent vs proposed Approach A):**

| Metric | B (k5-exc200-bud900) | D (k3-exc800-bud4000) | Delta | vs Doc-block (recent) |
|--------|------|------|-------|------|
| fact_coverage | 0.424 | **0.649** | **+53%** | -0.6% (equal) |
| faithfulness | 0.215 | **0.417** | **+94%** | +11% (better) |
| citation_precision | 0.333 | **0.500** | **+50%** | +20% (better) |
| citation_recall | 0.313 | **0.479** | **+53%** | +15% (better) |
| answer_similarity | 0.795 (embed) | 0.281 (jaccard) | N/A | N/A |
| retrieval_recall | 1.0 | 1.0 | 0% | +20% vs recent |

**Key finding: Approach A doesn't just recover the quality loss - it matches or exceeds document-block format** on fact_coverage, faithfulness, citation_precision, and citation_recall. The agent-style format with richer excerpts is competitive with full 1000-char document blocks.

**Per-query highlights (fact_coverage B -> D):**

| Query | Topic | B | D | Change |
|-------|-------|---|---|--------|
| rag-001 | HikariCP pool size formula | 0.5 | 1.0 | Now finds the formula |
| rag-002 | Attention formula | 0.0 | 1.0 | Extracts the full formula |
| rag-004 | Puppy rabies vaccination | 0.333 | 1.0 | Finds all facts |
| rag-006 | ETL vs ELT | 0.0 | 1.0 | B couldn't answer at all |
| rag-013 | Immunization schedule | 0.0 | 1.0 | From zero to complete |
| rag-016 | HikariCP defaults | 0.0 | 0.333 | Partial improvement |

**Queries where D still struggles (k=3 limitation):**

| Query | Topic | B | D | Why |
|-------|-------|---|---|-----|
| rag-003 | Adam beta values | 0.0 | 0.0 | Specific values beyond 800 chars in gradient-descent doc |
| rag-008 | Transformer params | 0.0 | 0.0 | Parameter count deep in document |
| rag-009 | Kafka reliable delivery | 0.333 | 0.333 | k=3 may exclude data-pipeline doc content for this detail |
| rag-011 | HikariCP+Kafka strategies | 0.5 | 0.0 | k=3 means fewer cross-source comparisons possible |

The rag-011 regression (0.5 -> 0.0) is notable: reducing k from 5 to 3 hurts multi-source queries where the model needs to compare information across documents. This is a known tradeoff from the "depth vs breadth" research - fewer richer results beat more thin ones for most queries, but cross-document comparison queries suffer.

#### Summary: Eval data supports Approach A

The empirical case for Approach A is strong:
- **53% improvement in fact coverage** (0.424 -> 0.649)
- **94% improvement in faithfulness** (0.215 -> 0.417, nearly doubled)
- **50-53% improvement in citations** (precision and recall)
- Matches document-block baseline on fact coverage, exceeds it on faithfulness/citations
- Tradeoff: k=3 slightly hurts multi-source comparison queries (1 regression in 24 queries)

Recommended parameters for Approach A implementation: **k=3, 800-char excerpts, 4000-char total budget**. These are empirically validated, not research-derived guesses.

### Step 3: Implement Approach A (DONE)

All changes implemented and verified (189 tests passing):

1. `SearchTool.java` (3 edits): DEFAULT_LIMIT 5→3, PARAMETER_SCHEMA updated, `formatResults()` rewritten to loop all excerpt regions (800-char truncation) with `content_preview` fallback for vector search
2. `AgentLoopService.java` (1 edit): `MAX_TOOL_RESULT_CHARS` 900→4000
3. `SearchToolTest.java` (1 edit): New `executeFormatsContentPreviewFallback_whenNoExcerpts` test
4. `AgentLoopServiceTest.java` (1 fix): Updated truncation test expectations for 4000-char limit

### Step 4: Agent live battery regression check (INVALID — battery doesn't test search quality)

Run 2026-02-18T19:00:02Z. Model: Qwen3VL-8B-Thinking-Q4_K_M. 16 scenarios.

Pass rate: 81.25% pre and post — but **the result is meaningless for validating search context changes**.

**Root cause: The battery script never ingests documents.** It starts a fresh dev-runner with `clean: soft` (empty data dir), configures the model, activates AI, then runs scenarios against an empty or barely-indexed store. Inspection of the 16 agent-run transcripts shows:

- Only 5 of 16 scenarios got any search results at all
- The other 11 (including all 3 "failures") hit `"No results found"` or `"No indexed folders found."` on every tool call
- The 13 "passes" are false positives — expected keywords (`search`, `configuration`, `gpu`, etc.) appear in the LLM's "I tried searching for X but found nothing" text, not in actual knowledge-grounded answers
- The 3 "failures" failed because their expected keywords (`agent`, `/api/agent`, `explanation/reference/how-to`) don't naturally appear in error-recovery phrasing

**What the battery actually validates:** Agent infrastructure (startup, AI activation, SSE protocol, tool dispatch). It does NOT validate answer quality against indexed content.

**What's needed:** The battery script needs a document ingestion step (POST `/api/knowledge/ingest` with the docs folder) after AI activation and before running scenarios. Without this, the battery cannot test whether search context format changes affect agent answer quality.

Output: `build/test-results/agent-live/battery-post-213.json`
Transcripts: `tmp/dev-runner-data/agent-live/2026-02-18T19-00-02-267Z/agent-runs/*/meta.json`

**Why this wasn't caught before declaring the step done:**

1. **The aggregate pass rate was treated as ground truth.** 13/16 pass, same 3 failures pre and post — that pattern matches "no regressions." The numbers were never interrogated.

2. **The battery script was read to understand how to run it, not to audit it.** All 1075 lines were read. The absence of an ingest call wasn't noticed because the read had a goal (find the CLI flags) not a verification goal (confirm the test is sound).

3. **The agent-run transcripts were not read until asked.** The manifest stores `responseChars`, `toolSequence`, `iterationsUsed` — metadata that looks plausible whether the index is full or empty. Those numbers were narrated as evidence the changes were working ("richer search context is flowing through") without checking what the tools actually returned.

4. **The pre/post identical result reinforced the wrong conclusion.** Identical results from a broken test prove consistency, not correctness. The pattern matched "stable system" rather than "test measures nothing."

5. **Verification of a test's validity was skipped.** Before reporting a test result, the question "does this test actually measure what I claim it measures?" should be answered explicitly. It wasn't asked.

**Structural analysis of the battery:** The invalidity above is one of 15 structural weaknesses in the battery script. The comprehensive analysis, research on long-term state, and the improvement roadmap are documented in **tempdoc 216** (eval harness consolidation), Part III. This tempdoc (213) only tracks the ingestion fix as a prerequisite for Approach B regression checking.

**Minimum viable fix for 213:** Add a POST to `/api/knowledge/ingest` (with `docs/` folder) after `maybeActivateAi()` and before the scenario loop in `run-agent-live-battery.mjs`. This makes the battery useful as an infra check with real indexed content. Broader improvements (ground truth assertions, semantic eval, tool tracing) are 216's scope.

---

## Open questions

1. Should `MAX_TOOL_RESULT_CHARS` be raised globally or only for search results? Other tools (browse_folders, file_operations) may have different needs. Currently configurable via `JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS` env var.
2. What model and context size does JustSearch actually run in practice? The RAG eval uses Qwen3-4B with 2048-token context. The agent loop defaults to 8192. Research findings were tested on 70B+ models — applicability to 4B-8B is unvalidated.

---

## Overall Assessment

### What this investigation found

The agent's search tool gives the LLM ~50 tokens of evidence per result in a 225-token total budget. This is 6-12x below every research recommendation. Vector search gives the agent zero content. The backend computes 3 excerpt regions per result; the agent uses 1 and truncates it from ~400 chars to 200. The fix is straightforward code (5 edits in 2 files) but the right parameter values require empirical testing.

### Implementation progress

1. **Extend RAG eval** — DONE. Agent-format context variant added to `RagQualityEvalTest.java`. Variants B (k5-exc200-bud900) and D (k3-exc800-bud4000) run and compared.
2. **Implement Approach A** — DONE. Parameters: k=3, 800-char excerpts, 4000-char total budget. All 189 unit tests passing.
3. **Run agent live battery** — INVALID. Battery runs against empty index (no ingestion step). Results are infrastructure validation only, not search quality validation. Needs fix: add ingestion step to `run-agent-live-battery.mjs`, then re-run.
4. **Evaluate Approach B** (XML format) — Not started.

### What this tempdoc does NOT recommend

- Building a "truly unified" eval system — the existing separation into 5 frameworks makes architectural sense.
- Implementing parameter changes without measurement — the confidence assessment documents why the research-derived numbers may not apply to 4B-8B local models.
- Pursuing query-type-aware formatting, multi-step retrieval, or contextual retrieval headers before the baseline is fixed — the current 200-char budget makes all of these premature optimizations.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 88 days at audit time.

