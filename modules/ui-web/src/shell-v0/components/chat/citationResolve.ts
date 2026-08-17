// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 565 §15.B — the ONE RAG claim→Citation resolver.
 *
 * The RAG path accumulates per-sentence {@link Claim}s (sentence + score + the source indices it
 * grounds to) and retrieval {@link RetrievalCitation} sources. Both `UnifiedChatView` and
 * `SummarizeView` render that through the one `MarkdownBlock` weave, so the claim→`Citation` mapping
 * lives here once (not forked across the two views). Mirrors the retired `cite-ref-click` source-index
 * lookup, so RAG marks gain the deep-link + cross-surface selection key the flat-text block lacked.
 * Ungrounded sentences (no verified ref) get no mark — neutral prose (the §15.B medium-appropriate
 * take on the flat-text dimming; the §15.A cutoff already filtered to grounded sentences).
 *
 * Tempdoc 822 §3d — the PROVENANCE GATE lives here, because this is the one place a `Claim` becomes a
 * `Citation` and `Citation.similarity` is what every downstream tier read (`groundingClass` for the
 * mark + the sentence underline, `groundingCoverage` for the grounded/weak counts) consumes. A claim
 * the cross-encoder never verified yields NO citation, so a lexical word-overlap ratio has no path to
 * a threshold calibrated on the cross-encoder cutoff. The gate is structural, not a check: there is
 * no field on `Citation` a lexical score could be written into.
 */
import type { Claim, RetrievalCitation } from './citationTypes.js';
import type { Citation } from './MarkdownBlock.js';
import { filenameOf, isVerifiedProducer } from './evidenceProjection.js';
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
    // 822 §3d — the SCORE gate. Only a cross-encoder-verified claim can mint a mark; a lexical-only
    // claim is dropped whole rather than being handed over with a score on the wrong scale. The
    // check is `typeof number`, not `!== null`: an untyped/legacy claim object carries no verified
    // score at all, and a missing score must fail closed (no mark) exactly like an explicit null.
    if (typeof cl.verifiedScore !== 'number') continue;
    // Tempdoc 836 §4 — the PRODUCER gate, beside the score gate and for the same reason. A cosine
    // fallback score is a number on a different scale (its supported and unsupported bands overlap
    // at a 0.0049 margin, §9.7), so admitting it here would put it straight into
    // `Citation.similarity` — the field every downstream tier reads as a cross-encoder
    // probability. Same authority the write sites use, so the two cannot diverge.
    if (!isVerifiedProducer(cl.scorer)) continue;
    // 822 §3b — the REF gate, and the honest failure that replaced `sources[refIdx] ?? sources[0]`.
    // A claim resolves ONLY through a ref the authoritative matcher supplied (`lexicalRefs` are the
    // streaming guess and may not target a mark), and an index that addresses no source mints NO
    // citation. There is therefore no path from an unresolvable index to a `Citation`, so no
    // `.cite-ref` can carry another source's `parentDocId` — the wrong-target deep link is not
    // fixed here, it is unconstructible. The dropped claim is visible: coverage counts what
    // renders, so the frame degrades to `partially-grounded` because the evidence degraded.
    // Fails CLOSED on a claim with no verified ref list at all (a legacy/untyped object), exactly as
    // the score gate above does: a missing producer is not a verified one.
    const refIdx = Array.isArray(cl.verifiedRefs) ? cl.verifiedRefs[0] : undefined;
    if (refIdx === undefined) continue;
    const s = sources[refIdx];
    if (!s) continue;
    out.push({
      sentenceText: cl.sentenceText,
      similarity: cl.verifiedScore,
      sourceRefs: cl.verifiedRefs,
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
