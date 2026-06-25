// SPDX-License-Identifier: Apache-2.0
/**
 * Evidence projection — tempdoc 559 Authority IV (evidence as a registered projection).
 *
 * THE typed projection of a retrieval-evidence record (`RetrievalCitation` ←
 * Java `ContextCitation`, the RAG-retrieval sibling of `SearchTrace` in the
 * execution-surface register, governance/execution-surfaces.v1.json) into the
 * shape the citation UI renders. Before 559 the citation panel read record
 * fields ad-hoc and showed a bare "100%" — an unlabeled backend scalar with no
 * declared meaning. This collapses that: the panel renders an {@link EvidenceItem},
 * and the score is a {@link EvidenceScore} that *carries its metric's meaning*
 * (a declared label — "Relevance" — not a naked percentage). Score math, tier
 * thresholds, and filename derivation live here once, so the render site cannot
 * drift its own thresholds.
 *
 * Scope (559 §5, recorded decision): `EvidenceScore` is RETRIEVAL-evidence-only by
 * design. The search-RANKING side has no user-facing per-result relevance score —
 * `SearchHit.score` is fetched but never rendered, and the ranking "why"
 * (`searchTraceExplain.ts`) shows pipeline-stage timings/status, not a relevance
 * metric. Projecting `EvidenceScore` onto it would misrepresent stage signals as a
 * relevance score (over-DRY). The authority SHARED across ranking + RAG is the
 * execution-surface register (sibling evidence records), not a shared FE score.
 *
 * Honest limit (559 §5): the excerpt's clean sentence/word boundary is owned at
 * the PRODUCER (the worker that mints `ContextCitation`); the FE never
 * re-windows. The mid-word clip is a cross-process (Worker/Java) follow-up — see
 * docs/observations.md — not an FE deliverable. This projection carries the
 * boundary-aware fields (startLine/endLine/headingText) verbatim for navigation.
 */
import type { RetrievalCitation, CitationMatch } from './citationTypes.js';
import type { CoreInteractionShapeId } from '../../plugin-api/coreInteractionShapes.js';

/**
 * Tempdoc 577 §2.12 Move 3 — the epistemic answer FRAME authority.
 *
 * <p>The free-chat fabricated-citations defect (§2.11 #4) is the absence of provenance as a
 * first-class facet: an ungrounded model answer rendered in the IDENTICAL bubble as a grounded one,
 * letting the LLM borrow the index's credibility (Goal 3 constraint 3, here implemented
 * window-internally ahead of unification). The frame combines a shape's DECLARED grounding class
 * with the run's ACTUAL grounding outcome, so every answer is framed by what it can support.
 *
 * <p>The declared class is a per-shape PRESENTATION property (how to frame trust), so it lives here
 * as the one presentation authority — exhaustively over the {@link CoreInteractionShapeId} union
 * (the compiler's totality check IS the "every shape declares" guarantee; a new shape that omits a
 * class fails to compile).
 */
export type AnswerGroundingClass = 'grounded-index' | 'ungrounded-llm' | 'transform';

/** THE shape → declared grounding class map (exhaustive; the single declaration site). */
export function declaredGroundingClass(shapeId: CoreInteractionShapeId): AnswerGroundingClass {
  switch (shapeId) {
    case 'core.rag-ask':
    case 'core.agent-run':
    // A workflow run grounds in its tool outputs (agent-path render); treat as index-grounded.
    case 'core.workflow-run':
      return 'grounded-index';
    case 'core.free-chat':
      return 'ungrounded-llm';
    case 'core.extract':
      // A structured transform of given input — not a grounded claim, not a bare opinion.
      return 'transform';
  }
}

/**
 * The answer's epistemic frame: the declared class refined by the actual grounding outcome. An
 * index-grounded shape whose run produced NO citable sources is honestly `ungrounded` (the agent
 * zero-sources case §2.9 V1); one with partial coverage is `partially-grounded`. Ungrounded /
 * transform shapes carry their class regardless of (absent) evidence.
 *
 * <p>Tempdoc 603 D-4 — the `sourced` frame: the answer drew on retrieved DOCUMENTS but per-sentence
 * grounding was NOT verified (the sources are document-level — no chunk identity — so the answer↔source
 * matcher could not run; e.g. the agent's main BM25/keyword pipeline under BLOCKED_LEGACY). It is a
 * PEER of `grounded`, not a degraded form: provenance is real, sentence-precision is absent. This is the
 * honest middle the binary (grounded ↔ ungrounded) lacked — it is why a doc-level answer must NOT read
 * "Grounded · 0 of N" (the over-confidence) NOR "found nothing to cite" (the over-conservatism, 603 D-1).
 */
export type AnswerFrame = 'grounded' | 'partially-grounded' | 'sourced' | 'ungrounded' | 'transform';

/**
 * Tempdoc 603 D-4 — the document-level sentinel (mirror of {@code AgentSession.DOC_LEVEL_SENTINEL}): a
 * source whose {@code chunkIndex} is this value is DOCUMENT-LEVEL (provenance only — no chunk identity,
 * no precise line). Any other value (a real `>= 0` ordinal, or `undefined` for a source type that does
 * not carry a chunk index — e.g. a chunk-native RAG `RetrievalCitation`) is treated as chunk-precise.
 */
export const DOC_LEVEL_CHUNK_SENTINEL = -1;

/**
 * Tempdoc 603 D-4 — are the grounding sources CHUNK-PRECISE (line-precise, matcher-eligible) or only
 * DOCUMENT-LEVEL (provenance)? TRUE unless EVERY source is the document-level sentinel — so a chunk-native
 * RAG source list (no sentinel) and any list with at least one real chunk are chunk-precise, while an
 * all-document-level agent list (every {@code chunkIndex === -1}) is not. An empty list is vacuously not
 * chunk-precise. The predicate lives in the authority (not the view) so the SOURCED↔GROUNDED distinction is
 * derived in ONE place.
 */
export function sourcesAreChunkPrecise(
  sources: ReadonlyArray<{ readonly chunkIndex?: number }>,
): boolean {
  return sources.some((s) => s.chunkIndex !== DOC_LEVEL_CHUNK_SENTINEL);
}

export function answerFrame(
  shapeId: CoreInteractionShapeId,
  sourceCount: number,
  coverage: GroundingCoverage,
  // Tempdoc 603 D-4 — whether the attached sources are chunk-precise (matcher-eligible) or document-level
  // (provenance only). Defaults TRUE so every existing caller (the Documents/RAG tier, always chunk-native)
  // keeps its behavior; the agent tier passes the real predicate so doc-level sources frame as `sourced`.
  chunkPrecise = true,
): AnswerFrame {
  const declared = declaredGroundingClass(shapeId);
  if (declared === 'transform') return 'transform';
  if (declared === 'ungrounded-llm') return 'ungrounded';
  // grounded-index: refine by the actual outcome.
  // No citable sources at all ⇒ honestly ungrounded (the agent zero-sources case, §2.9 V1).
  if (sourceCount === 0) return 'ungrounded';
  // Sources attached but SOME sentences cite and others do not ⇒ partially grounded. When no
  // sentence cites yet (cited===0), the per-sentence matcher has not run/matched — that is "marks
  // pending", not "partial"; the answer is grounded (the badge carries the coverage refinement).
  if (coverage.cited > 0 && coverage.cited < coverage.total) return 'partially-grounded';
  // Tempdoc 603 D-4 — "marks pending ⇒ grounded" holds only when chunk-precise matching is POSSIBLE
  // (the marks are coming). For DOCUMENT-LEVEL sources the matcher can NEVER run, so cited===0 is not
  // "pending" — it is provenance without per-sentence verification: the `sourced` frame, not `grounded`.
  if (coverage.cited === 0 && !chunkPrecise) return 'sourced';
  return 'grounded';
}

/**
 * Tempdoc 577 §2.16 — the precise grounding-degraded flag: TRUE when the shape was DECLARED to
 * ground in the index (a grounded-index shape: agent / RAG) yet the run produced zero citable
 * sources — i.e. it SEARCHED but found nothing above the match threshold. This is distinct from an
 * ungrounded-llm shape (free-chat), which never searches at all. Both render the `ungrounded` frame;
 * this flag lets the "Why uncited?" disclosure say WHICH happened — "searched but uncitable" vs
 * "this mode doesn't search" — instead of one ambiguous "not grounded" for two different facts.
 */
export function groundingDegraded(
  shapeId: CoreInteractionShapeId,
  sourceCount: number,
): boolean {
  return declaredGroundingClass(shapeId) === 'grounded-index' && sourceCount === 0;
}

/**
 * The one-line trust label for a frame (the answer's epistemic header). `null` for a fully-grounded
 * answer — the inline marks + the grounding badge already carry it, so no banner is added.
 *
 * <p>Tempdoc 603 C3 — a `transform` (extraction) is NO LONGER silent. The Structured tier does not
 * retrieve from the index (no RAGContext), so its values are the model's own structuring, not sourced
 * data — yet clean schema-shaped JSON reads as authoritative to a skimming user (593 ADD2: extracted
 * `120` vs the real `5s`). The marker makes that honest and unmissable, rendered prominently on the
 * artifact (see `.answer-frame-transform`), superseding the prior "transform is its own legible form".
 *
 * <p>Tempdoc 577 §2.16 — the `ungrounded` label is refined by {@link groundingDegraded}: a shape
 * that SEARCHED but found nothing to cite is honest about having tried, distinct from a mode that
 * never searches. Callers that lack the (shapeId × sourceCount) inputs pass `degraded=false` for the
 * legacy "model answer" wording.
 */
export function answerFrameLabel(frame: AnswerFrame, degraded = false): string | null {
  switch (frame) {
    case 'grounded':
      return null;
    case 'transform':
      return 'Model-generated structure — not retrieved from your documents';
    case 'partially-grounded':
      return 'Partly grounded — some statements are not backed by your documents';
    case 'sourced':
      // Tempdoc 603 D-4 — provenance without per-sentence verification: the answer drew on these
      // documents, but the matcher could not tie each statement to a passage (document-level retrieval).
      return 'Based on your documents — per-sentence grounding not verified';
    case 'ungrounded':
      return degraded
        ? 'Searched your documents but found nothing to cite — treat this as the model’s own answer'
        : 'Model answer — this mode does not search your documents';
  }
}

/** The declared meaning of a retrieval-evidence score (not a bare scalar). */
export interface EvidenceScore {
  /** Clamped 0..1 value. */
  readonly value: number;
  /** Rounded 0..100 for display. */
  readonly pct: number;
  /** Confidence tier — the branded {@link GroundingTier} (tempdoc 565 §15.D.1 seam). */
  readonly tier: GroundingTier;
  /** The metric's MEANING — e.g. "Relevance". The fix for the unlabeled "100%". */
  readonly label: string;
}

/** Source location for navigate-to-source (carried verbatim from the record). */
export interface EvidenceLocation {
  readonly parentDocId: string;
  readonly startLine: number;
  readonly endLine: number;
  readonly startChar: number;
  readonly endChar: number;
}

/** The view-model the citation UI projects from a retrieval-evidence record. */
export interface EvidenceItem {
  readonly docId: string;
  readonly filename: string;
  readonly score: EvidenceScore;
  readonly excerpt: string;
  readonly headingText: string;
  readonly location: EvidenceLocation;
}

/** Default declared metric for retrieval evidence — the relevance of the chunk. */
export const RELEVANCE_METRIC = 'Relevance';

function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v));
}

/**
 * The ONE grounding-tier threshold authority (tempdoc 565 §15.A). Private by
 * design: no renderer may hold these literals — the only way to a tier is
 * {@link evidenceTier}, and the only way to a grounding presentation is
 * {@link groundingClass} / {@link groundingLabel}, both derived from it.
 *
 * Before §15 the score→tier mapping was forked across FOUR sites with TWO
 * threshold sets (this file 0.7/0.3; MarkdownBlock + StreamingTextBlock
 * `groundingStatus` 0.5/0.2; CitationHoverCard `scoreLabel` 0.5/0.2) for ONE
 * answer↔source similarity — proven a single scorer on a single [0,1] scale
 * (§15.G: `AgentCitationResolver`→same `matchCitations`→`CitationMatchOps`). The
 * displayed-tier divergence (a 0.6 read "grounded" inline but "medium" in the
 * rail) was DRIFT, not calibration. The numeric pair stays an evidence-backed
 * calibration (one knob here); the UNIFICATION is the structural fix.
 *
 * §15.C-fix coherence pass: the tiers are now ANCHORED TO THE MATCHER CUTOFF
 * (`DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD = 0.5`). Every *cited*
 * sentence has similarity ≥ 0.5, so `TIER_MEDIUM = 0.5` (= the cutoff) makes a
 * cited sentence read at least "weak" and never "ungrounded" (which is reserved
 * for below-cutoff, i.e. UNcited prose); `TIER_HIGH = 0.6` sits just above the
 * cutoff so a real cross-encoder match reads "grounded". The previous 0.7/0.3
 * was inherited from the pre-§15 retrieval-only use and left cited sentences in
 * [0.5,0.7) reading "weak". The EXACT high bar is still the §15.A residual
 * (the production cross-encoder distribution); this anchoring is the coherent
 * default until that calibration lands.
 */
const TIER_HIGH = 0.6;
const TIER_MEDIUM = 0.5;

/**
 * The grounding tier — tempdoc 565 §15.D.1 typed seam. BRANDED (the `present.ts` `DisplayLabel`
 * idiom) so it is only constructible by {@link evidenceTier}: a renderer cannot fabricate a tier and
 * pass it where a tier is expected; it must obtain it from this authority. Paired with the
 * `groundingSemantics` register-gate (which forbids a re-derivation `score >= 0.X ? 'grounded'`
 * outside this file), the tier leaf is unforkable by construction, not by convention.
 */
export type GroundingTier = ('high' | 'medium' | 'low') & { readonly __groundingTier: unique symbol };

/** The actual threshold logic — internal, unbranded, so this file can switch on the raw tier. */
function computeTier(value: number): 'high' | 'medium' | 'low' {
  const s = clamp01(value);
  if (s >= TIER_HIGH) return 'high';
  if (s >= TIER_MEDIUM) return 'medium';
  return 'low';
}

/** The single tier-threshold authority — the ONLY mint of a {@link GroundingTier}. */
export function evidenceTier(value: number): GroundingTier {
  return computeTier(value) as GroundingTier;
}

/** Grouping vocabulary for the rail/panel (the CitationsPanel "High confidence / Supporting / Weak"). */
export function tierGroup(tier: GroundingTier): 'high' | 'supporting' | 'weak' {
  switch (tier as 'high' | 'medium' | 'low') {
    case 'high':
      return 'high';
    case 'medium':
      return 'supporting';
    default:
      return 'weak';
  }
}

/** The CSS-class stem for a tier (so the raw tier never leaks into a template as an authority bypass). */
export function tierClass(tier: GroundingTier): 'high' | 'medium' | 'low' {
  return tier as 'high' | 'medium' | 'low';
}

/**
 * The grounding CSS-class stem for an inline citation mark — the ONE replacement
 * for `MarkdownBlock.groundingStatus` / `StreamingTextBlock.groundingStatus`
 * (tempdoc 565 §15.A). Derived from {@link evidenceTier} so the inline marks and
 * the rail/chips classify one similarity identically.
 */
export function groundingClass(value: number): 'grounded' | 'weak' | 'ungrounded' {
  switch (computeTier(value)) {
    case 'high':
      return 'grounded';
    case 'medium':
      return 'weak';
    default:
      return 'ungrounded';
  }
}

/**
 * The grounding human label for a hover card — the ONE replacement for
 * `CitationHoverCard`'s inline `score >= 0.5 ? 'strong' : …` (tempdoc 565 §15.A).
 * Same tier authority as {@link groundingClass}; only the words differ.
 */
export function groundingLabel(value: number): 'strong' | 'moderate' | 'weak' {
  switch (computeTier(value)) {
    case 'high':
      return 'strong';
    case 'medium':
      return 'moderate';
    default:
      return 'weak';
  }
}

/**
 * Tempdoc 565 §14 ④/⑤ — the grounding-HONESTY read: "is this answer grounded, and how much of it?"
 * The §14/§15.A design dissolves the two backlog items (④ a grounding-readiness signal, ⑤ an
 * "N of M sentences grounded" coverage indicator) into ONE read of the grounding verdict over the
 * answer's per-sentence cites — NOT a new signal bolted on. `cited` are the sentences the matcher
 * grounded (similarity ≥ the cutoff, so each classifies `grounded` or `weak` via {@link groundingClass});
 * `total` is the answer's sentence count (M). `ready` is ④ (the answer carries grounding at all).
 */
export interface GroundingCoverage {
  /** ④ — the answer is grounded (at least one sentence is backed by a source). */
  readonly ready: boolean;
  /** Cited sentences classified `grounded` (similarity ≥ the high bar). */
  readonly grounded: number;
  /** Cited sentences classified `weak` (above the cutoff, below the high bar). */
  readonly weak: number;
  /** Sentences carrying any citation (`grounded` + `weak`) — the N in "N of M". */
  readonly cited: number;
  /** ⑤ — the answer's total sentence count (M). Always ≥ `cited`. */
  readonly total: number;
  /** A human one-liner: "Grounded · 3 of 5 sentences" / "Not grounded". */
  readonly label: string;
}

/**
 * Count an answer's sentences (the M in "N of M") — a best-effort prose split on terminating
 * punctuation followed by whitespace/end. Approximate by design (it is an honesty HINT, not an exact
 * metric); returns ≥ 1 for any non-empty text.
 */
export function countSentences(text: string): number {
  const t = (text ?? '').trim();
  if (t.length === 0) return 0;
  const terminators = t.match(/[.!?]+(?=\s|$)/g);
  return Math.max(terminators ? terminators.length : 0, 1);
}

/**
 * Project the answer's per-sentence cites + text into the ④/⑤ grounding-coverage read. Reads the ONE
 * §15.A grounding verdict ({@link groundingClass}) per cite — no second classifier.
 */
export function groundingCoverage(
  citations: ReadonlyArray<{ readonly similarity: number }>,
  answerText: string,
): GroundingCoverage {
  let grounded = 0;
  let weak = 0;
  for (const c of citations) {
    const verdict = groundingClass(c.similarity);
    if (verdict === 'grounded') grounded += 1;
    else if (verdict === 'weak') weak += 1;
  }
  const cited = grounded + weak;
  const total = Math.max(countSentences(answerText), cited);
  const ready = cited > 0;
  const label = ready ? `Grounded · ${cited} of ${total} sentences` : 'Not grounded';
  return { ready, grounded, weak, cited, total, label };
}

/** The metric label for a source's GROUNDING (faithfulness) — distinct from retrieval {@link RELEVANCE_METRIC}. */
export const GROUNDING_METRIC = 'Grounding';

/**
 * Tempdoc 603 C1 — a source's FAITHFULNESS: how much it actually grounded the answer, joined from the
 * per-sentence citation-matches (`rag.citation_matches`) — NOT the BM25 retrieval score. `cited:false` = the
 * source was retrieved but never grounded a sentence, so it MUST NOT read "high confidence" (the §1 mis-calibration).
 * The representative `similarity` is the strongest sentence match, fed to the ONE {@link evidenceTier} authority
 * for the trust tier (uncited → low → the "not cited" group).
 */
export interface SourceGrounding {
  readonly cited: boolean;
  readonly groundedSentences: number;
  /** Strongest matching-sentence similarity; 0 when uncited. */
  readonly similarity: number;
  /** The faithfulness tier (the ONE authority), so the SOURCES panel agrees with the inline citations. */
  readonly tier: GroundingTier;
}

/**
 * Join a source to its grounding via the answer's citation-matches (603 C1, corrected per PART X.B).
 *
 * <p>The match's `chunkIndex` is the source's **array position in the `rag.citations` list** — the ONE
 * established convention across the citation system: the inline `[n]` marks + their label + `Claim.sourceRefs`
 * (`citationResolve.claimsToCitations` does `sources[refIdx]`) all index sources by that position, and the worker
 * emits exactly that position. So a source is grounded by the matches whose `chunkIndex` equals its POSITION in the
 * panel's sources list (NOT a document-ordinal compare — that was the §1 "everything uncited" bug). `parentDocId`
 * is a cheap correctness guard (a position's match shares the source's document).
 */
export function sourceGrounding(
  sourceIndex: number,
  matches: ReadonlyArray<CitationMatch>,
  parentDocId?: string,
): SourceGrounding {
  let count = 0;
  let best = 0;
  for (const m of matches) {
    if (m.chunkIndex === sourceIndex && (parentDocId === undefined || m.parentDocId === parentDocId)) {
      count += 1;
      if (m.similarity > best) best = m.similarity;
    }
  }
  const cited = count > 0;
  return { cited, groundedSentences: count, similarity: best, tier: evidenceTier(cited ? best : 0) };
}

/** The per-source trust badge text — "Grounds N sentence(s)" when cited, else the honest "Retrieved · not cited". */
export function sourceGroundingLabel(g: SourceGrounding): string {
  if (!g.cited) return 'Retrieved · not cited';
  return g.groundedSentences === 1 ? 'Grounds 1 sentence' : `Grounds ${g.groundedSentences} sentences`;
}

/** Filename tail of a doc id (path-separator agnostic). */
export function filenameOf(docId: string): string {
  const i = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf('\\'));
  return i >= 0 ? docId.substring(i + 1) : docId;
}

/** Project a raw score into a labeled, declared metric. */
export function evidenceScore(value: number, label: string = RELEVANCE_METRIC): EvidenceScore {
  const v = clamp01(value);
  return { value: v, pct: Math.round(v * 100), tier: evidenceTier(value), label };
}

/** Project a retrieval-evidence record into the citation view-model. */
export function toEvidenceItem(c: RetrievalCitation): EvidenceItem {
  return {
    docId: c.parentDocId,
    filename: filenameOf(c.parentDocId),
    score: evidenceScore(c.score),
    excerpt: c.excerpt ?? '',
    headingText: c.headingText ?? '',
    location: {
      parentDocId: c.parentDocId,
      startLine: c.startLine,
      endLine: c.endLine,
      startChar: c.startChar,
      endChar: c.endChar,
    },
  };
}
