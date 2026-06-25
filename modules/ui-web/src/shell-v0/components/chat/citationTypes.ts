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
 */
export interface Claim {
  sentenceIndex: number;
  sentenceText: string;
  score: number;
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
