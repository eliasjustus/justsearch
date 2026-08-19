// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 849 §3-§4 — how a followed citation becomes something the reading pane can anchor on.
 *
 * Two callers, one authority, because the resolution happens TWICE and the two answers must agree:
 * once when the reader follows the citation (`Sv3Main`), and again when `rag.citation_matches`
 * arrives afterwards for a source whose pane is already open (`SearchV3View`). `rag.citations` is
 * emitted at retrieval time and the matches only after the answer streams, so the second pass is
 * the common case, not a repair.
 *
 * What each tier means (§4):
 *
 *  - The **chunk** is the citation's own `startChar`/`endChar` — the passage the retriever selected.
 *  - The **matched sentence** is the answer sentence a claim match tied to this source, which the
 *    reader then locates INSIDE that chunk. No claim match ⇒ no sentence, and the pane tints the
 *    chunk without inventing one.
 */
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';
import type { CitationSelectDetail } from '../../components/chat/citationTypes.js';
import type { Sv3Turn } from './sv3-sessions.js';

/** The source is not part of this turn's retrieval set (or the turn is gone). */
export const SV3_SOURCE_INDEX_ABSENT = -1;

/**
 * Which source in `turn.evidence.sources` a followed citation is, matched on the identity the
 * producer minted it with — the document plus its character span. Position in the array IS the
 * `sourceIndex` a `CitationMatch` links by (`components/chat/citationTypes.ts`), so this lookup is
 * what joins the two events.
 */
export function sv3SourceIndex(turn: Sv3Turn | null, detail: CitationSelectDetail): number {
  const sources = turn?.evidence?.sources;
  if (!sources) return SV3_SOURCE_INDEX_ABSENT;
  return sources.findIndex(
    (source) =>
      source.parentDocId === detail.parentDocId &&
      source.startChar === detail.startChar &&
      source.endChar === detail.endChar,
  );
}

/**
 * The answer sentence a claim match tied to this source, or `null` when none did. The FIRST match is
 * taken when several sentences ground to the same source: the pane emphasises one span, and the
 * alternative — emphasising the union — would tint text between two matched sentences that nothing
 * matched.
 */
export function sv3MatchedSentence(turn: Sv3Turn | null, sourceIndex: number): string | null {
  if (sourceIndex === SV3_SOURCE_INDEX_ABSENT) return null;
  const match = turn?.evidence?.matches.find((candidate) => candidate.sourceIndex === sourceIndex);
  const sentence = match?.sentenceText ?? '';
  return sentence.length > 0 ? sentence : null;
}

/**
 * The citation's character anchor, or `null` when it carried no usable span — in which case the pane
 * shows the document and highlights nothing, rather than falling back to the line numbers the
 * producer derived. Those are 1-based and this reader is 0-based, and re-admitting them here would
 * re-admit exactly the off-by-one the char anchor removes.
 */
export function sv3CitationAnchor(
  detail: CitationSelectDetail,
  sentenceText: string | null,
): DocumentCitationAnchor | null {
  const { startChar, endChar } = detail;
  if (!Number.isFinite(startChar) || !Number.isFinite(endChar) || endChar <= startChar) return null;
  return { startChar, endChar, excerpt: detail.excerpt ?? '', sentenceText };
}
