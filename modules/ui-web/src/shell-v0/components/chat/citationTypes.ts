// SPDX-License-Identifier: Apache-2.0
/**
 * Pure citation data shapes for the chat / RAG surfaces.
 *
 * Extracted from `CitationsPanel.ts` to break the import cycle
 * (tempdoc 530 UI-cycle gate): `evidenceProjection.ts` needs the
 * `RetrievalCitation` shape, but importing it from `CitationsPanel.ts`
 * (which imports `evidenceProjection.ts` for its projection helpers)
 * formed a cycle. These are plain data interfaces with no dependency
 * on the renderer/component graph, so they live in this leaf and both
 * sides import from here.
 */

/**
 * A grounded claim — the RAG path's per-sentence accumulation model (sentence + score + the source
 * indices it grounds to). Tempdoc 565 §15.B relocated this from the retired `StreamingTextBlock`; it
 * is the internal RAG model that `UnifiedChatView` builds from `rag.citation_matches` and then maps
 * into the one `Citation` render shape (`MarkdownBlock`). A leaf data type, no renderer dependency.
 *
 * Tempdoc 822 §3d (the score-scale mismatch) — the single `score` field is GONE, split by producer.
 * Two events feed this model and they do not measure the same quantity: `rag.citation_matches`
 * carries a cross-encoder relevance probability, `rag.citation_delta` carries the streaming lexical
 * matcher's word-overlap coverage ratio (`hits / significantWords`, whose denominator is the
 * passage's vocabulary size). `Math.max`-ing them into one number fed word overlap into thresholds
 * calibrated on the cross-encoder cutoff — a 2-of-4-word passage read "grounded". Keeping them
 * apart is the gate: only {@link verifiedScore} may reach a grounding tier.
 */
export interface Claim {
  sentenceIndex: number;
  sentenceText: string;
  /**
   * The cross-encoder similarity from `rag.citation_matches` — the ONLY score a grounding tier may
   * be computed from. `null` means no authoritative matcher verified this sentence, and such a claim
   * mints no citation, no mark, no underline, and no grounded/weak count.
   */
  verifiedScore: number | null;
  /**
   * The streaming lexical matcher's word-overlap ratio from `rag.citation_delta`. Kept because it is
   * what arrived, never because it is comparable: it is not on the cross-encoder scale and no
   * monotone mapping onto it exists. Diagnostic only — never a tier input.
   */
  lexicalScore: number;
  sourceRefs: number[];
}

/** Citation match (mirrors `CitationMatch` in streams.ts). */
export interface CitationMatch {
  sentenceIndex: number;
  sentenceText: string;
  chunkIndex: number;
  similarity: number;
  parentDocId: string;
  excerpt?: string;
}

/** Retrieval-time citation from rag.citations event. */
export interface RetrievalCitation {
  parentDocId: string;
  chunkIndex: number;
  chunkTotal: number;
  startChar: number;
  endChar: number;
  score: number;
  excerpt: string;
  startLine: number;
  endLine: number;
  headingText: string;
  headingLevel: number;
}

/** Emitted on citation click for navigate-to-source. */
export interface CitationSelectDetail {
  parentDocId: string;
  startLine: number;
  endLine: number;
  startChar: number;
  endChar: number;
  /** Tempdoc 526 §14.5 T2 — excerpt for G21 kind-flip into a typed citation selection. */
  excerpt: string;
}
