---
title: "Two-Pronged Citation Strategy"
type: decision
status: stable
description: "LLM-generated citations backed by RAG metadata, supplemented by CPU cross-encoder post-hoc matching."
date: 2026-02-07
---

# ADR-0006: Two-Pronged Citation Strategy

## Status
Accepted

## Context

JustSearch needs to attribute AI-generated answers to source documents so users can verify claims. Research (2025-2026) showed that small models (7B-13B) have <5% citation task completion rate, motivating a post-hoc matching architecture. However, dogfooding revealed that capable models (8B+ with good instruction following) produce correct `[N]` citation syntax when prompted, and the existing RAG retrieval metadata already provides rich citation data (excerpts, character offsets, section headings).

Meanwhile, the original post-hoc approach (embedding cosine similarity) was architecturally blocked on single-GPU systems: the embedding model and LLM compete for VRAM, and the GPU transition logic evicts the embedding model when the LLM goes online for Q&A. Post-hoc matching via embeddings could never fire during normal Q&A.

The system needed a citation strategy that works reliably across model capabilities and hardware configurations.

## Decision

Use a **two-pronged citation strategy**:

1. **LLM-generated citations (primary path):** Instruct the LLM to place `[N]` markers inline via prompt engineering. RAG retrieval provides numbered passages (`<passage id="N" source="file">` XML), and the `meta` SSE event delivers rich `ContextCitation[]` metadata (excerpts, `startChar`/`endChar` offsets, section headings, BM25 scores) for hover cards and click-to-jump. This path requires no embedding service and works with any model capable of following citation instructions.

2. **Post-hoc cross-encoder matching (supplementary path):** After the LLM finishes streaming, a CPU-only ONNX cross-encoder (`CitationScorer`, ms-marco-MiniLM-L2-v2) scores each answer sentence against source chunks. Results arrive via a `citation_matches` SSE event. The frontend **enriches** existing RAG citations with cross-encoder similarity scores rather than replacing them. This path adds verified sentence-to-chunk attribution and works independently of the embedding service.

The two approaches are **complementary, not mutually exclusive**. When both fire, the cross-encoder markers replace LLM-generated markers (stripping existing `[N]` patterns first to prevent duplication), and RAG citation metadata is preserved with upgraded scores.

## Consequences

**Positive:**
- Citations work out of the box with any capable LLM model, no additional ONNX model required
- Cross-encoder runs on CPU, eliminating the GPU contention that blocked embedding-based matching
- RAG metadata (excerpts, offsets, headings) flows through both paths, enabling hover cards and click-to-jump
- Cross-encoder is optional — feature degrades gracefully when model files aren't present
- Frontend enrichment pattern preserves all RAG metadata while upgrading scores

**Negative:**
- LLM-generated citations depend on model quality — small/weak models may not follow instructions
- Cross-encoder requires ONNX model files (~16 MB INT8 ONNX) that must be obtained separately (see ONNX Model Distribution in `docs/explanation/05-ai-architecture.md`)
- Two citation numbering systems (LLM vs cross-encoder) can conflict — resolved by stripping LLM markers when cross-encoder results arrive
- Cross-encoder scores (sigmoid-normalized logits) have different semantics than BM25 scores or cosine similarity — threshold calibration is empirical

**Key files:**
- Prompt engineering: `InferenceLifecycleManager.java`, `summary.rag.v1.mustache`, `summary.refine.v1.mustache`
- Cross-encoder: `modules/reranker/CitationScorer.java`, `CitationScorerConfig.java`
- Integration: `GrpcSearchService.matchCitations()`, `SummaryController` (SSE events)
- Frontend: `useAppAI.ts` (`onCitationMatches`, `injectCitationMarkers`), `CitationHoverCard.tsx`, `MarkdownRenderer.tsx`

## Alternatives Considered

### Alternative A: Embedding cosine similarity only
The original approach: embed answer sentences and source chunks, compute cosine similarity. **Rejected** because the embedding model and LLM compete for GPU on single-GPU systems — post-hoc matching could never fire during Q&A. Also, cosine similarity measures topical relatedness, not entailment.

### Alternative B: NLI (Natural Language Inference) model
Use a DeBERTa-NLI model to classify whether each sentence is entailed by a source chunk. **Deferred** — closer to semantic correctness than similarity, but ms-marco cross-encoder proved sufficient and reuses existing ONNX/tokenizer infrastructure from the search reranker. NLI remains a future upgrade path.

### Alternative C: Self-RAG (reflection tokens)
Train the model to emit special tokens indicating whether it's citing vs. generating. **Rejected** — requires fine-tuned models, incompatible with standard llama-server and user-provided models.

### Alternative D: Grammar-constrained decoding (GBNF)
Use llama.cpp GBNF grammars to force citation syntax in the output. **Deferred** — feasible but prompt engineering proved sufficient in testing. Complex grammars can slow inference. Reserved as insurance if prompt engineering fails with some models.

## Reassess When

- LLM citation accuracy exceeds 95% on an eval suite, making the cross-encoder supplementary path unnecessary overhead.
- Cross-encoder latency becomes a UX bottleneck (e.g., answer streams complete faster than cross-encoder scoring), degrading the perceived responsiveness.

*Added by tempdoc 269 trigger audit (2026-03).*
