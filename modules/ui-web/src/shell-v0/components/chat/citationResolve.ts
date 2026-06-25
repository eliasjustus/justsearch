// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 565 §15.B — the ONE RAG claim→Citation resolver.
 *
 * The RAG path accumulates per-sentence {@link Claim}s (sentence + score + the source indices it
 * grounds to) and retrieval {@link RetrievalCitation} sources. Both `UnifiedChatView` and
 * `SummarizeView` render that through the one `MarkdownBlock` weave, so the claim→`Citation` mapping
 * lives here once (not forked across the two views). Mirrors the retired `cite-ref-click` source-index
 * lookup, so RAG marks gain the deep-link + cross-surface selection key the flat-text block lacked.
 * Ungrounded sentences (`sourceRefs` empty) get no mark — neutral prose (the §15.B medium-appropriate
 * take on the flat-text dimming; the §15.A cutoff already filtered to grounded sentences).
 */
import type { Claim, RetrievalCitation } from './citationTypes.js';
import type { Citation } from './MarkdownBlock.js';
import { filenameOf } from './evidenceProjection.js';
import type {
  AgentSource,
  AgentSentenceCite,
} from '../../../api/generated/shape-handlers/shared.js';

export function claimsToCitations(
  claims: readonly Claim[],
  sources: readonly RetrievalCitation[],
): Citation[] {
  if (claims.length === 0 || sources.length === 0) return [];
  const out: Citation[] = [];
  for (const cl of claims) {
    if (cl.sourceRefs.length === 0) continue;
    const refIdx = cl.sourceRefs[0] ?? 0;
    const s = sources[refIdx] ?? sources[0];
    if (!s) continue;
    out.push({
      sentenceText: cl.sentenceText,
      similarity: cl.score,
      sourceRefs: cl.sourceRefs,
      label: refIdx + 1,
      detail: {
        parentDocId: s.parentDocId,
        startLine: s.startLine,
        endLine: s.endLine,
        startChar: s.startChar,
        endChar: s.endChar,
        excerpt: s.excerpt,
      },
      hover: { excerpt: s.excerpt, title: filenameOf(s.parentDocId), headingText: s.headingText },
    });
  }
  return out;
}

/**
 * Tempdoc 577 Goal 1 Phase 1 (Move F) — the ONE agent-answer resolver: the `done`-event grounding
 * record (`AgentSource[]` + `AgentSentenceCite[]`) → the `MarkdownBlock` citation weave. Extracted
 * verbatim from `UnifiedChatView.resolveAnswerCitations` (tempdoc 565 §3.C / §15.B) so the
 * Inspector's Answer tab and the chat surface resolve through one authority instead of forking the
 * mapping. The `[n]` label is the source's 1-based position (cross-references the sources list);
 * the deep-link detail reuses the same `citation-select` contract the Sources pane and RAG path use.
 */
export function resolveAnswerCitations(
  sources: readonly AgentSource[],
  cites: readonly AgentSentenceCite[],
): Citation[] {
  if (sources.length === 0 || cites.length === 0) return [];
  const out: Citation[] = [];
  for (const c of cites) {
    const s = sources[c.sourceIndex];
    if (!s) continue;
    out.push({
      sentenceText: c.sentenceText,
      similarity: c.similarity,
      label: c.sourceIndex + 1,
      detail: {
        parentDocId: s.parentDocId,
        startLine: s.startLine,
        endLine: s.endLine,
        startChar: 0,
        endChar: 0,
        excerpt: s.excerpt,
      },
      hover: { excerpt: s.excerpt, title: s.title, headingText: s.headingText },
    });
  }
  return out;
}
