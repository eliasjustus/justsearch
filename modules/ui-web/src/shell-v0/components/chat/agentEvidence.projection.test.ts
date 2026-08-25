/**
 * Tempdoc 859 §3 — the ONE delegate-plane evidence projection.
 *
 * Each case here pins a claim the rev-1 design got wrong and rev 2 corrected, so a regression would
 * be a return to a specific, named mistake rather than a vague drift:
 *
 *  - **T8 / §3a** — the matches are PROJECTED. An empty match list is not honest silence: it makes
 *    every source `cited: false`, which the panel renders as the VERDICT "Retrieved · not cited"
 *    about sources the matcher demonstrably cited.
 *  - **§5b** — the five retrieval-only fields stay ABSENT. Zero-filling them fabricates retrieval
 *    facts into a panel that groups and grades by them.
 *  - **T3 / T4 / §4** — the producer gate fires on the agent plane, and the pre-stamp allowance
 *    still admits a record written before the stamp existed.
 */
import { describe, it, expect } from 'vitest';
import { agentAnswerEvidence, agentDeltaEvidence } from './agentEvidence.js';
import { sourceGrounding, sourceGroundingLabel } from './evidenceProjection.js';
import type {
  AgentSentenceCite,
  AgentSource,
} from '../../../api/generated/shape-handlers/shared.js';

const SOURCES: AgentSource[] = [
  {
    parentDocId: 'docs/a.md',
    chunkIndex: 4,
    path: 'f:/docs/a.md',
    title: 'Doc A',
    excerpt: 'passage A',
    startLine: 3,
    endLine: 9,
    headingText: 'Intro',
  },
  {
    parentDocId: 'docs/b.md',
    chunkIndex: 1,
    path: 'f:/docs/b.md',
    title: 'Doc B',
    excerpt: 'passage B',
    startLine: 12,
    endLine: 20,
    headingText: '',
  },
];

const CITES: AgentSentenceCite[] = [
  { sentenceText: 'The lock held.', sourceIndex: 0, similarity: 0.91 },
  // The SAME sentence, a second source — the multi-source shape the ordinal derivation exists for.
  { sentenceText: 'The lock held.', sourceIndex: 1, similarity: 0.77 },
  { sentenceText: 'The retry then succeeded.', sourceIndex: 1, similarity: 0.83 },
];

describe('agentAnswerEvidence — sources', () => {
  it('carries what the delegate producer reports and leaves the retrieval-only fields ABSENT', () => {
    const { sources } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(sources).toHaveLength(2);
    const first = sources[0]!;
    expect(first.parentDocId).toBe('docs/a.md');
    expect(first.chunkIndex).toBe(4);
    expect(first.startLine).toBe(3);
    expect(first.endLine).toBe(9);
    expect(first.excerpt).toBe('passage A');
    expect(first.headingText).toBe('Intro');
    // §5b — the forbidden repair, asserted as absence rather than as a value. `startChar: 0` on
    // every source of a document makes a followed citation resolve to that document's FIRST source
    // (the wrong-target deep link 822 §3b removed), and `score: 0` is a "low relevance" grade over a
    // number this producer never emits.
    expect(first.startChar).toBeUndefined();
    expect(first.endChar).toBeUndefined();
    expect(first.score).toBeUndefined();
    expect(first.chunkTotal).toBeUndefined();
    expect(first.headingLevel).toBeUndefined();
    // ...and absence is stated by the KEY being missing, not by a sentinel value sitting in it.
    expect(Object.keys(first).sort()).toEqual(
      ['chunkIndex', 'endLine', 'excerpt', 'headingText', 'parentDocId', 'startLine'].sort(),
    );
  });

  it('reports the SOURCE count, not the cite count — the fabricated number 859 §1(3b) found', () => {
    // The observed defect read `attributes.citations` as if it were the retrieval set, so a run with
    // 28 sources and 12 sentence-cites told the reader it had 12 sources. Two sources, three cites.
    const { sources, matches } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(sources).toHaveLength(2);
    expect(matches).toHaveLength(3);
  });
});

describe('agentAnswerEvidence — matches (T8, §3a)', () => {
  it('projects one match per cite, with the sentence ordinal shared with the marks', () => {
    const { matches, marks } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(matches.map((m) => m.sentenceIndex)).toEqual([0, 0, 1]);
    // The marks ran the SAME ordinal walk, so a mark and its panel card describe one sentence.
    expect(marks.map((m) => m.sentenceIndex)).toEqual([0, 0, 1]);
    expect(matches.map((m) => m.sourceIndex)).toEqual([0, 1, 1]);
    expect(matches.map((m) => m.similarity)).toEqual([0.91, 0.77, 0.83]);
    expect(matches.map((m) => m.parentDocId)).toEqual(['docs/a.md', 'docs/b.md', 'docs/b.md']);
  });

  it('states textSource CHUNK_LOOKUP rather than leaving it absent (§2)', () => {
    // The agent path scores against chunk text RE-FETCHED by (parentDocId, chunkIndex), never the
    // literal excerpt the model saw. Absent would mean "a record older than the field" — a different
    // claim, and the one that hides this asymmetry.
    const { matches } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(matches.every((m) => m.textSource === 'CHUNK_LOOKUP')).toBe(true);
  });

  it('T8 — a cited source reads "Grounds N sentences", never "Retrieved · not cited"', () => {
    const { sources, matches } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    // Source 1 is cited by two sentences; source 0 by one. This is the exact join the panel performs.
    const g0 = sourceGrounding(0, matches, sources[0]!.parentDocId);
    const g1 = sourceGrounding(1, matches, sources[1]!.parentDocId);
    expect(sourceGroundingLabel(g0)).toBe('Grounds 1 sentence');
    expect(sourceGroundingLabel(g1)).toBe('Grounds 2 sentences');
    expect(g0.cited).toBe(true);
    expect(g1.cited).toBe(true);
  });

  it('T8 counter-case — with matches thrown away the panel asserts the opposite', () => {
    // Pinning WHY `matches: []` was rejected: the empty list is not neutral, it produces a verdict.
    const { sources } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    const g = sourceGrounding(0, [], sources[0]!.parentDocId);
    expect(sourceGroundingLabel(g)).toBe('Retrieved · not cited');
  });

  it('mints no match for a cite whose sourceIndex addresses no source — same fail-closed rule as the mark', () => {
    const dangling: AgentSentenceCite[] = [
      { sentenceText: 'Dangling.', sourceIndex: 9, similarity: 0.5 },
      { sentenceText: 'Valid.', sourceIndex: 0, similarity: 0.6 },
    ];
    const { matches, marks } = agentAnswerEvidence(SOURCES, dangling, 'CROSS_ENCODER');
    expect(matches).toHaveLength(1);
    expect(matches[0]!.parentDocId).toBe('docs/a.md');
    expect(marks).toHaveLength(1);
  });
});

describe('agentAnswerEvidence — the producer gate (§4)', () => {
  it('T3 — a cosine-scored run mints NO marks AND no matches, and its sources still stand', () => {
    const { sources, matches, marks } = agentAnswerEvidence(SOURCES, CITES, 'EMBEDDING_COSINE');
    expect(marks).toEqual([]);
    // Tempdoc 847 S1 — the matches are gated by the SAME producer verdict, because gating only the
    // marks is not gating the number. `sourceGrounding` reads `match.similarity` straight into
    // `evidenceTier` (thresholds anchored on the cross-encoder cutoff) and the panel groups its
    // cards by that tier — so an ungated match list paints a verification tier beside the source
    // from the very number that was just judged unfit to mint a mark.
    expect(matches).toEqual([]);
    // Sources-without-marks is `AgentCitationResolver`'s documented degradation (565 §10) — the
    // evidence is still shown, only the grading is withheld.
    expect(sources).toHaveLength(2);
  });

  it('T3b — the panel therefore grades a cosine run at NO tier, on either surface', () => {
    // The consequence, asserted where the reader meets it. Without the match gate this source reads
    // "Grounds 2 sentences" with a tier computed from a cosine score.
    const { sources, matches } = agentAnswerEvidence(SOURCES, CITES, 'EMBEDDING_COSINE');
    const g = sourceGrounding(1, matches, sources[1]!.parentDocId);
    expect(g.cited).toBe(false);
    expect(g.groundedSentences).toBe(0);
    expect(sourceGroundingLabel(g)).toBe('Retrieved · not cited');
  });

  it('T3c — a CROSS_ENCODER run keeps BOTH surfaces: the gate withholds, it does not blank', () => {
    // The discriminator for T3/T3b: if `admittedMatches` dropped everything unconditionally, those
    // two would pass for entirely the wrong reason and the panel would be permanently ungraded.
    const { sources, matches, marks } = agentAnswerEvidence(SOURCES, CITES, 'CROSS_ENCODER');
    expect(matches).toHaveLength(3);
    expect(marks).toHaveLength(3);
    const g = sourceGrounding(1, matches, sources[1]!.parentDocId);
    expect(sourceGroundingLabel(g)).toBe('Grounds 2 sentences');
  });

  it('T4 — an ABSENT stamp still marks and still matches: the pre-stamp allowance', () => {
    // If T3 passed because the payload was empty rather than because the gate fired, this case
    // would pass too and prove nothing. Same sources, same cites, no stamp.
    for (const scorer of [null, undefined]) {
      const { matches, marks } = agentAnswerEvidence(SOURCES, CITES, scorer);
      expect(marks).toHaveLength(3);
      expect(matches).toHaveLength(3);
    }
  });

  it('an unrecognised producer fails CLOSED on both surfaces — unknown is not verified', () => {
    for (const scorer of ['SOMETHING_NEW', 'NONE']) {
      const { matches, marks } = agentAnswerEvidence(SOURCES, CITES, scorer);
      expect(marks).toEqual([]);
      expect(matches).toEqual([]);
    }
  });
});

/**
 * Tempdoc 865 §7.3 — the DISCRIMINATOR, at the one site that still knows why the match list is empty.
 *
 * Three different runs hand the panel `matches: []`, and only one of them is a missing verdict. The
 * distinction is already on the wire — `AgentStepRunner.groundedDone` stamps `resolved.scorer().name()`,
 * which is `NONE` for every `AgentCitationResolver.Resolved.none()` (the `MATCH_TIMEOUT_MS` timeout
 * included), while the Worker stamps a real producer name on every response its matcher produced
 * (`CitationMatchOps.execute`). Nothing new crosses the wire for this; the fact was being dropped at
 * the read site, which is where it is now read.
 */
describe('agentAnswerEvidence — telling three empty match lists apart (865 PR-0)', () => {
  it('NONE is the only stamp that means "no producer judged these"', () => {
    // The matcher never produced a verdict — the timeout shape.
    expect(agentAnswerEvidence(SOURCES, [], 'NONE').groundingIncomplete).toBe(true);
    // A rejected producer DID judge them; 836 §4 refuses its numbers, which is a different fact.
    expect(agentAnswerEvidence(SOURCES, CITES, 'EMBEDDING_COSINE').groundingIncomplete).toBe(false);
    // The matcher ran and cited nothing — an empty list with a producer behind it.
    expect(agentAnswerEvidence(SOURCES, [], 'CROSS_ENCODER').groundingIncomplete).toBe(false);
    // All three collapse to the same match list, which is exactly why the flag has to exist.
    expect(agentAnswerEvidence(SOURCES, [], 'NONE').matches).toEqual([]);
    expect(agentAnswerEvidence(SOURCES, CITES, 'EMBEDDING_COSINE').matches).toEqual([]);
    expect(agentAnswerEvidence(SOURCES, [], 'CROSS_ENCODER').matches).toEqual([]);
  });

  it('an ABSENT stamp asserts NOTHING about the pass — the coverage precedent, verbatim', () => {
    // `sourceGrounding`'s own rule: absent ⇒ the established binary stands. A record persisted
    // before the stamp existed must not be retroactively described as a failed pass.
    for (const scorer of [null, undefined]) {
      expect(agentAnswerEvidence(SOURCES, [], scorer).groundingIncomplete).toBe(false);
    }
  });

  it('an UNRECOGNISED stamp is not the missing-verdict case either', () => {
    // `NONE` is a name the emitter writes, not a catch-all for "we could not read this". A future
    // producer name fails the mark gate closed (the case above) without claiming the pass broke.
    expect(agentAnswerEvidence(SOURCES, CITES, 'SOMETHING_NEW').groundingIncomplete).toBe(false);
  });

  it('the flag reaches the per-source state and the words the reader sees', () => {
    const { sources, matches, groundingIncomplete } = agentAnswerEvidence(SOURCES, [], 'NONE');
    const g = sourceGrounding(0, matches, sources[0]!.parentDocId, null, groundingIncomplete);
    expect(g.state).toBe('grounding-incomplete');
    expect(sourceGroundingLabel(g)).toBe('Retrieved · grounding check did not complete');
    // §7.3's tier decision, made explicit: a tier is minted from a similarity and there is none, so
    // the source carries no tier at all rather than `evidenceTier(0)`'s "low" verdict.
    expect(g.tier).toBeNull();
    expect(g.similarity).toBe(0);
    expect(g.groundedSentences).toBe(0);
  });
});

/**
 * Tempdoc 865 PR-1 (F1) — the SIBLING of the missing-verdict defect, one level down.
 *
 * `groundingIncomplete` is a fact about the PASS. This is a fact about the SOURCE: a document-level
 * agent source (603 D-3's sentinel — no chunk ordinal, because the hit carried no `parentDocId`) has
 * no chunk for any matcher to fetch, so nothing ever read it. The panel nonetheless rendered
 * "Retrieved · not cited" over it, beside a `CROSS_ENCODER` stamp — a verdict on a source nothing
 * examined, delivered with the full authority of a pass that really did run on the OTHER sources.
 */
describe('agentAnswerEvidence — a source no matcher could examine (865 PR-1 / F1)', () => {
  const DOC_LEVEL: AgentSource = {
    // The 603 D-3 shape: identity is the path, chunk ordinal + lines are the sentinel.
    parentDocId: 'f:/docs/c.md',
    chunkIndex: -1,
    path: 'f:/docs/c.md',
    title: 'Doc C',
    excerpt: 'whole-document provenance',
    startLine: -1,
    endLine: -1,
    headingText: '',
  };

  it('reports the document-level source as unexaminable and says NOTHING about the others', () => {
    const { sourceCoverage } = agentAnswerEvidence([...SOURCES, DOC_LEVEL], CITES, 'CROSS_ENCODER');
    expect(sourceCoverage).toEqual([{ sourceIndex: 2, windowsConsidered: 0, windowsScored: 0 }]);
  });

  it('THE DISCRIMINATING CASE: it no longer claims a verdict the matcher never delivered', () => {
    const evidence = agentAnswerEvidence([...SOURCES, DOC_LEVEL], CITES, 'CROSS_ENCODER');
    const coverageAt = (i: number) =>
      evidence.sourceCoverage.find((c) => c.sourceIndex === i) ?? null;

    // The document-level source: examined by nothing, and now says so.
    const docLevel = sourceGrounding(2, evidence.matches, DOC_LEVEL.parentDocId, coverageAt(2));
    expect(docLevel.state).toBe('unexamined');
    expect(sourceGroundingLabel(docLevel)).toBe('Retrieved · not examined');
    // RED BEFORE this fix, pinned as the input rather than described: the read sites passed `null`
    // coverage for a delegate source, so the state fell through to the binary and the pane
    // delivered a verdict. Same source, same matches — only the fact the projection now supplies.
    expect(sourceGroundingLabel(sourceGrounding(2, evidence.matches, DOC_LEVEL.parentDocId, null))).toBe(
      'Retrieved · not cited',
    );
    expect(sourceGroundingLabel(docLevel)).not.toBe('Retrieved · not cited');

    // A chunk-precise source the matcher DID cite is untouched — the fix is per-source, so it
    // cannot smear "not examined" over the sources the pass really judged.
    const cited = sourceGrounding(0, evidence.matches, SOURCES[0]!.parentDocId, coverageAt(0));
    expect(cited.state).toBe('cited');
    expect(sourceGroundingLabel(cited)).toBe('Grounds 1 sentence');
  });

  it('is INDEPENDENT of the pass-level flag — a completed pass can still carry an unread source', () => {
    // The two facts are orthogonal, and conflating them is exactly what §7.3 refused to do when it
    // declined to reuse `unexamined` for the timeout case.
    const completed = agentAnswerEvidence([DOC_LEVEL], [], 'CROSS_ENCODER');
    expect(completed.groundingIncomplete).toBe(false);
    expect(completed.sourceCoverage).toHaveLength(1);
    // When the pass ALSO failed, the pass-level fact wins: a run whose matcher never completed
    // cannot have produced per-source examination facts from that pass.
    const failed = agentAnswerEvidence([DOC_LEVEL], [], 'NONE');
    const g = sourceGrounding(0, failed.matches, DOC_LEVEL.parentDocId, failed.sourceCoverage[0]!, failed.groundingIncomplete);
    expect(g.state).toBe('grounding-incomplete');
  });
});

/**
 * Tempdoc 865 §7.1 — the run that reached no grounded terminal, reconstructed from its deltas.
 */
describe('agentDeltaEvidence — what a run established, with no terminal verdict', () => {
  it('keeps the sources and refuses to deliver a verdict no pass produced', () => {
    const evidence = agentDeltaEvidence(SOURCES);
    expect(evidence.sources).toHaveLength(2);
    expect(evidence.matches).toEqual([]);
    expect(evidence.marks).toEqual([]);
    // No answer existed, so no matcher ran, so nothing judged these. That is the missing-verdict
    // state, not "retrieved and found wanting".
    expect(evidence.groundingIncomplete).toBe(true);
    const g = sourceGrounding(0, evidence.matches, SOURCES[0]!.parentDocId, null, evidence.groundingIncomplete);
    expect(sourceGroundingLabel(g)).toBe('Retrieved · grounding check did not complete');
  });
});
