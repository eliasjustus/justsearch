// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 859 §3 — the ONE delegate-plane evidence projection: a `done` event's grounding record
 * (`AgentSource[]` + `AgentSentenceCite[]` + the producer stamp) → the same three-part evidence
 * record the RAG plane produces (sources + matches + inline marks).
 *
 * <p>TWO callers, one function, because the projection happens twice and the two answers must agree:
 * the live Search v3 terminal (`SearchV3View.concludeRun`) and the Search v3 record reader
 * (`sv3-record.recordEvidenceOf`). Before this module the live path dropped the evidence outright and
 * the record path re-derived it from the wrong wire key, which is how a delegate turn could show a
 * source count the backend never reported.
 *
 * <p>HONEST LIMIT: the legacy window (`UnifiedChatView`) does NOT yet draw through this module. It
 * received the producer stamp (859 §4 / amendment 6) so its own `resolveAnswerCitations` calls are
 * gated, but its source/match derivation was not migrated here — so the "one projection" claim holds
 * for the Search v3 window and not yet product-wide. Stating it rather than implying the migration
 * happened: a module doc that names a caller it does not have is the register-asserting-a-false-claim
 * failure this slice removed from `sv3-record-evidence`.
 *
 * <p>It is a PROJECTION, not a carrier: it derives every field of its output from the wire record,
 * so it is registered as such in `governance/execution-surfaces.v1.json`.
 */
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';
import type { AnswerEvidenceSource, CitationMatch } from './citationTypes.js';
import type { Citation } from './MarkdownBlock.js';
import { agentSentenceOrdinals, resolveAnswerCitations } from './citationResolve.js';
// Tempdoc 847 S1 — the ONE producer gate over a MATCH LIST. Imported, never re-implemented: a second
// copy of "may this match be shown" would be free to disagree with the record path's copy, which is
// the 561 P-A divergence the shared authority exists to prevent.
import { admittedMatches } from './recordEvidence.js';

/** What one delegate answer stood on — the same three parts `Sv3TurnEvidence` carries. */
export interface AgentAnswerEvidence {
  readonly sources: readonly AnswerEvidenceSource[];
  readonly matches: readonly CitationMatch[];
  readonly marks: readonly Citation[];
}

/**
 * The delegate plane's `AgentSource` → the shared {@link AnswerEvidenceSource}.
 *
 * <p>Tempdoc 859 §5b — the five retrieval-only fields (`chunkTotal`, `startChar`, `endChar`,
 * `score`, `headingLevel`) are LEFT ABSENT, never zero-filled. This producer does not report them,
 * and the panel groups and grades by them: `score: 0` is a "low relevance" verdict about a number
 * nobody produced, and `startChar: 0` is a claim about the document's opening characters that the
 * citation-anchor join would then act on.
 */
function toAnswerEvidenceSource(source: AgentSource): AnswerEvidenceSource {
  return {
    parentDocId: source.parentDocId,
    chunkIndex: source.chunkIndex,
    excerpt: source.excerpt,
    startLine: source.startLine,
    endLine: source.endLine,
    headingText: source.headingText,
  };
}

/**
 * The delegate plane's per-sentence cites → the shared `CitationMatch[]`.
 *
 * <p>Tempdoc 859 §3a — these are PROJECTED, never left empty. `sourceGrounding` counts the matches
 * whose `sourceIndex` equals a source's position; with an empty list every source lands `cited:
 * false` and the panel renders **"Retrieved · not cited"** — a verdict, on every source the matcher
 * demonstrably DID cite. The matcher reported; throwing the report away and letting the panel assert
 * the opposite is not honesty, it is a confident wrong answer.
 *
 * <p>A cite whose `sourceIndex` addresses no source mints NO match — the same fail-closed rule the
 * mark follows (`citationResolve.resolveAnswerCitations`), and the rule `sourceGrounding`'s own
 * `parentDocId` correctness guard needs an answer for.
 */
function toCitationMatches(
  sources: readonly AgentSource[],
  cites: readonly AgentSentenceCite[],
): CitationMatch[] {
  const out: CitationMatch[] = [];
  for (const { cite, sentenceIndex } of agentSentenceOrdinals(cites)) {
    const source = sources[cite.sourceIndex];
    if (!source) continue;
    out.push({
      sentenceText: cite.sentenceText,
      sourceIndex: cite.sourceIndex,
      similarity: cite.similarity,
      sentenceIndex,
      parentDocId: source.parentDocId,
      // Tempdoc 859 §2 — STATED, not left absent. `AgentCitationResolver` calls the 3-arg
      // `matchCitations` overload, which supplies a blank `literalText`, so the Worker re-fetches
      // each chunk by `(parentDocId, chunkIndex)` and scores against re-fetched chunk text — never
      // the literal excerpt the model saw. Absent would mean "a record older than the field", which
      // is a different claim.
      textSource: 'CHUNK_LOOKUP',
    });
  }
  return out;
}

/**
 * Project a delegate run's grounding record into the window's one evidence shape.
 *
 * @param scorer which producer wrote the similarities (`DocumentService.ScorerKind`'s wire name), or
 *   `null`/`undefined` for a record persisted before the stamp existed. It gates BOTH derived
 *   surfaces, because both read the similarity: {@link resolveAnswerCitations} for the inline marks
 *   and {@link admittedMatches} for the panel's per-source tier. A known non-cross-encoder producer
 *   yields neither (the sources still stand, which is true and is all that was reported); an absent
 *   stamp keeps the pre-stamp allowance on both.
 */
export function agentAnswerEvidence(
  sources: readonly AgentSource[],
  cites: readonly AgentSentenceCite[],
  scorer: string | null | undefined,
): AgentAnswerEvidence {
  return {
    sources: sources.map(toAnswerEvidenceSource),
    // Tempdoc 847 S1 — gating the marks alone is not the gate. `sourceGrounding` reads
    // `match.similarity` straight into `evidenceTier`, whose thresholds are anchored on the
    // cross-encoder cutoff, and `CitationsPanel` groups the cards by that tier — so an ungated match
    // list paints "the same cosine number that mints no mark" as a verification tier beside the
    // source. One producer verdict, two surfaces, one outcome.
    matches: admittedMatches(toCitationMatches(sources, cites), scorer),
    marks: resolveAnswerCitations(sources, cites, scorer),
  };
}
