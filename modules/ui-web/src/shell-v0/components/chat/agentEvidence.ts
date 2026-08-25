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
import type { AnswerEvidenceSource, CitationMatch, SourceCoverage } from './citationTypes.js';
import type { Citation } from './MarkdownBlock.js';
import { agentSentenceOrdinals, resolveAnswerCitations } from './citationResolve.js';
// Tempdoc 847 S1 — the ONE producer gate over a MATCH LIST. Imported, never re-implemented: a second
// copy of "may this match be shown" would be free to disagree with the record path's copy, which is
// the 561 P-A divergence the shared authority exists to prevent.
import { admittedMatches } from './recordEvidence.js';
// Tempdoc 865 §7.5 — the ONE reader of the inclusion wire value, imported rather than re-tested
// here. Its refusals are the point: an absent field and an unrecognised one both yield `null`, so a
// vocabulary drift on the delegate plane cannot become a false claim about what the model was shown.
import { contextInclusionOf } from './evidenceProjection.js';

/** What one delegate answer stood on — the same three parts `Sv3TurnEvidence` carries. */
export interface AgentAnswerEvidence {
  readonly sources: readonly AnswerEvidenceSource[];
  readonly matches: readonly CitationMatch[];
  readonly marks: readonly Citation[];
  /**
   * Tempdoc 865 §7.3 — did this run's grounding pass fail to complete? The fact that tells an empty
   * match list apart from a verdict, at the ONE place the verdict is minted.
   *
   * <p>Three different runs land an empty `matches`, and only the third may not be described as
   * "not cited": the matcher ran and cited nothing (`CROSS_ENCODER` + no cites); a known
   * non-cross-encoder producer scored them and {@link admittedMatches} refused its numbers; and the
   * matcher never produced a verdict at all. This flag isolates the third.
   */
  readonly groundingIncomplete: boolean;
  /**
   * Tempdoc 865 PR-1 (F1) — the per-source examination facts this plane can state, for the sources
   * NO matcher could have read.
   *
   * <p>The defect it closes is the sibling of {@link groundingIncomplete}: a DOCUMENT-LEVEL agent
   * source (the 603 D-3 sentinel — no chunk ordinal, because the hit carried no `parentDocId`) is
   * structurally unexaminable, and the panel rendered "Retrieved · not cited" over it — a verdict on
   * a source nothing looked at. Where `groundingIncomplete` is a fact about the PASS, this is a fact
   * about the SOURCE, and the two are independent: a run whose matcher completed perfectly can still
   * carry a source the matcher was never able to read.
   *
   * <p>Only the structurally-unexaminable subset is reported. The producer also reports BUDGET-
   * starved sources (`CitationMatchResult.sourceCoverage`, tempdoc 836), but `AgentCitationResolver`
   * drops that list at its return — the same shape of defect 859 §4 fixed for the scorer stamp — and
   * carrying it needs a new `done` wire field. So this is an UNDER-report, never a contradiction: a
   * source it stays silent about keeps the established binary.
   */
  readonly sourceCoverage: readonly SourceCoverage[];
}

/**
 * The wire name {@code AgentEvent.AgentDone.SCORER_NONE} stamps when NO producer scored the answer
 * (`AgentStepRunner.groundedDone` → `AgentCitationResolver.Resolved.none()`, which is what the
 * `MATCH_TIMEOUT_MS` timeout and every other failure degrade to — `AgentCitationResolver.java:117-123`).
 *
 * <p>It is the discriminator this module needs and it is ALREADY on the wire: the Worker stamps a
 * real producer name on every response its matcher actually produced (`CitationMatchOps.execute`
 * sets `CROSS_ENCODER`/`EMBEDDING_COSINE`), and leaves the field empty — which
 * `ScorerKind.fromWire` maps to `NONE` — on exactly the paths where nothing scored.
 */
const SCORER_NONE = 'NONE';

/**
 * Tempdoc 865 §7.3 — did the grounding pass fail to complete, as the producer reported it?
 *
 * <p>ABSENT (`null`/`undefined`) is `false`, and that is the precedent, not an oversight: an absent
 * stamp means a record persisted BEFORE the field existed (the same narrow allowance
 * {@link isVerifiedProducer} makes), and a producer that says nothing about its pass does not get
 * "it did not complete" asserted on its behalf.
 */
function groundingIncompleteFor(scorer: string | null | undefined): boolean {
  return scorer === SCORER_NONE;
}

/**
 * Tempdoc 603 D-3 — the chunk-identity-absent sentinel, mirrored from Java
 * `AgentSession.DOC_LEVEL_SENTINEL`. A NEGATIVE chunk ordinal means the hit carried no
 * `parentDocId`, so the source is the whole DOCUMENT and there is no chunk to fetch.
 */
const DOC_LEVEL_SENTINEL_MAX = -1;

/**
 * Tempdoc 865 PR-1 (F1) — the sources no matcher could have examined, as the coverage facts
 * {@link sourceGrounding} already knows how to read.
 *
 * <p>This is a projection of a fact the wire already carries, not a second opinion about it. The
 * matcher's own contract says so: `CitationMatchOps.prepareWindows` refuses to look up a negative
 * chunk ordinal ("a source that supplies no text and has no ordinal is unverifiable"), and
 * `PassageWindows.prepare` then contributes NO window for it — so the Worker itself reports exactly
 * `windowsConsidered: 0, windowsScored: 0` for these. The agent path always supplies blank literal
 * text (`DocumentService.matchCitations` maps every citation to `VerificationSource(c, "")`), so the
 * lookup arm is the only arm a delegate source can take.
 *
 * <p>Chunk-precise sources are OMITTED rather than reported as examined: whether the matcher reached
 * one is a fact only the matcher has, and this plane does not carry it. Absent ⇒ the established
 * binary, per `sourceGrounding`'s own absence discipline.
 */
function unexaminableSourceCoverage(sources: readonly AgentSource[]): SourceCoverage[] {
  const out: SourceCoverage[] = [];
  sources.forEach((source, sourceIndex) => {
    if (source.chunkIndex <= DOC_LEVEL_SENTINEL_MAX) {
      out.push({ sourceIndex, windowsConsidered: 0, windowsScored: 0 });
    }
  });
  return out;
}

/**
 * The delegate plane's `AgentSource` → the shared {@link AnswerEvidenceSource}.
 *
 * <p>Tempdoc 859 §5b — the five retrieval-only fields (`chunkTotal`, `startChar`, `endChar`,
 * `score`, `headingLevel`) are LEFT ABSENT, never zero-filled. This producer does not report them,
 * and the panel groups and grades by them: `score: 0` is a "low relevance" verdict about a number
 * nobody produced, and `startChar: 0` is a claim about the document's opening characters that the
 * citation-anchor join would then act on.
 *
 * <p>Tempdoc 865 §7.5 — the inclusion axis is FORWARDED, and it is the whole read side of this
 * slice. 849 built the predicate (`suppressGroundingFor`), the badge (`inclusionBadge`) and the
 * words ("Retrieved · never sent to the model") and wired them into both surfaces; the delegate
 * plane inherited none of it for one reason only — no producer ever gave the predicate an input, so
 * `suppressGroundingFor(null)` was permanently `false`. Nothing new is invented here: the two wire
 * keys are the RAG plane's own, and passing them through is what makes the existing machinery fire.
 *
 * <p><b>PRECEDENCE — inclusion is decided BEFORE the grounding axis, and it wins.</b> A delegate
 * source can now be simultaneously dropped, unexamined (865 PR-1's `sourceCoverage`) and part of a
 * run whose grounding pass never completed (PR-0's `groundingIncomplete`). `citationHeader` already
 * resolves that: `suppressGroundingFor` withholds the ENTIRE grounding label — "not cited", "not
 * examined" and "grounding check did not complete" alike — and the card shows only the inclusion
 * badge. That is right rather than merely established, because every state on the grounding axis is
 * a statement about a matcher's relationship to text the model never saw: `not examined` would
 * invite "so examine it", and `grounding check did not complete` would imply a completed check
 * could have said something. The one fact worth the reader's attention is that the passage was not
 * in the prompt, and 849's reduction says exactly that and stops.
 */
function toAnswerEvidenceSource(source: AgentSource): AnswerEvidenceSource {
  const inclusion = contextInclusionOf(source);
  return {
    parentDocId: source.parentDocId,
    chunkIndex: source.chunkIndex,
    excerpt: source.excerpt,
    startLine: source.startLine,
    endLine: source.endLine,
    headingText: source.headingText,
    // Absent stays absent — `undefined`, not a spread of a `null`, so the field is missing rather
    // than present-and-empty and `contextInclusionOf` downstream reads it as "said nothing".
    ...(inclusion === null
      ? {}
      : { contextInclusion: inclusion, contextIncludedChars: source.contextIncludedChars ?? 0 }),
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
    // Tempdoc 865 §7.3 — minted HERE, beside the two surfaces it qualifies, because this is where an
    // empty match list is produced and therefore the only place that still knows WHY it is empty.
    // Downstream, `[]` is `[]`.
    groundingIncomplete: groundingIncompleteFor(scorer),
    // Tempdoc 865 PR-1 (F1) — same reason, one level down: the per-source half of "an empty match
    // list is not always a verdict".
    sourceCoverage: unexaminableSourceCoverage(sources),
  };
}

/**
 * Tempdoc 865 §7.1 — what a run established, RECONSTRUCTED from the per-call grounding deltas, for
 * a run that reached no grounded terminal.
 *
 * <p>This is the read side of the incremental mint. A cancelled, errored or iteration-exhausted run
 * emits no grounded `done` — `AgentDone.ofDisposition` hardcodes an empty source list and that is
 * its contract, not a bug — but every tool call already stamped what it established onto its own
 * `tool_exec_completed`. Concatenating those deltas in call order reproduces the terminal list
 * exactly (pinned by `AgentLoopServiceTest.concatenatedDeltas_equalTheGroundedTerminalsSources`), so
 * the reader keeps the evidence the run really drew on instead of an empty pane.
 *
 * <p>The scorer is `SCORER_NONE`, which is the truth and not a fallback: no answer existed, so no
 * matcher ran, so nothing judged these sources. That lands them in `grounding-incomplete` —
 * "Retrieved · grounding check did not complete" — rather than the "not cited" verdict a pass that
 * never ran has no standing to deliver.
 */
export function agentDeltaEvidence(sources: readonly AgentSource[]): AgentAnswerEvidence {
  return agentAnswerEvidence(sources, [], SCORER_NONE);
}
