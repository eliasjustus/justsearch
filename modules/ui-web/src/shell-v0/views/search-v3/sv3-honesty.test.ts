/**
 * The Search v3 window's honesty derivations (tempdoc 822 Phase F7).
 *
 * Pure module, so every case here is a MECHANISM and not an appearance. Three properties carry the
 * weight, and each is written so that the obvious wrong implementation fails:
 *
 *  - **The lock is tri-state.** The `unknown` case is asserted to CHANGE NOTHING in both directions,
 *    which is what a `state === 'locked'` boolean would get wrong.
 *  - **The corpus distinguishes zero from unreported.** A null index yields `unknown`, a reported 0
 *    yields the remedy — the shipped landing collapses those two, so a copy of it would fail here.
 *  - **The frame is derived, never composed.** Its wording is compared against the SHARED authority's
 *    own output rather than a string literal, so a hand-written sentence fails even if it reads the
 *    same today.
 */
import { describe, it, expect } from 'vitest';
import {
  deriveSv3HistoryLocked,
  projectSv3AnswerFrame,
  projectSv3Corpus,
  splitSv3FrameLabel,
  sv3ReceiptTail,
  sv3SourcesTrigger,
  sv3SourcesTriggerCount,
  sv3SourcesTriggerLabel,
  sv3TailModelLabel,
  SV3_CORPUS_UNKNOWN,
  SV3_SOURCES_COUNT_IN_TRIGGER,
} from './sv3-honesty.js';
import { CITATIONS_LABEL, SOURCES_LABEL } from './fixtures.js';
import { answerFrameLabel } from '../../components/chat/evidenceProjection.js';
import type { AnswerFrame } from '../../components/chat/evidenceProjection.js';
import type { AiState } from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import type { Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';
import type { CitationMatch, RetrievalCitation } from '../../components/chat/citationTypes.js';

const snapshot = (over: Partial<AiState>): AiState => over as AiState;

const withLock = (state: string | undefined): AiState =>
  snapshot({
    status: (state === undefined
      ? {}
      : { conversationProtection: { state } }) as unknown as StatusSnapshot,
  });

describe('the lock is read from EVERY snapshot, and only when the snapshot says so', () => {
  it('picks up a lock taken elsewhere', () => {
    expect(deriveSv3HistoryLocked(false, withLock('locked'))).toBe(true);
  });

  it('picks up the unlock that follows it', () => {
    expect(deriveSv3HistoryLocked(true, withLock('unlocked'))).toBe(false);
  });

  it('leaves a KNOWN state alone when a snapshot does not mention the lock — in both directions', () => {
    // The probe for the boolean mistake: `state === 'locked'` would answer `false` to both of these,
    // silently unlocking the view on the first status frame that omits the field.
    expect(deriveSv3HistoryLocked(true, withLock(undefined))).toBe(true);
    expect(deriveSv3HistoryLocked(false, withLock(undefined))).toBe(false);
    expect(deriveSv3HistoryLocked(true, snapshot({ status: null }))).toBe(true);
    expect(deriveSv3HistoryLocked(true, null)).toBe(true);
  });

  it('treats a state it does not recognise as nothing said', () => {
    expect(deriveSv3HistoryLocked(true, withLock('unavailable'))).toBe(true);
  });
});

describe('the corpus tells "none" apart from "not reported"', () => {
  const index = (over: Partial<NonNullable<AiState['lastSettledIndex']>>): AiState =>
    snapshot({
      lastSettledIndex: {
        documentCount: 0,
        searchableDocumentCount: null,
        indexSizeBytes: null,
        ...over,
      },
    });

  it('says nothing at all before a settled poll', () => {
    expect(projectSv3Corpus(null)).toEqual(SV3_CORPUS_UNKNOWN);
    expect(projectSv3Corpus(snapshot({ lastSettledIndex: null }))).toEqual(SV3_CORPUS_UNKNOWN);
  });

  it('prefers the DEFAULT-SCOPE population over the whole-index count', () => {
    expect(projectSv3Corpus(index({ documentCount: 900, searchableDocumentCount: 12 }))).toEqual({
      kind: 'documents',
      count: 12,
    });
  });

  it('falls back to the whole-index count when the scope population is not reported', () => {
    expect(projectSv3Corpus(index({ documentCount: 7, searchableDocumentCount: null }))).toEqual({
      kind: 'documents',
      count: 7,
    });
  });

  it('treats a REPORTED zero as real (811 C-4) — the remedy case, not the unknown one', () => {
    expect(projectSv3Corpus(index({ documentCount: 0, searchableDocumentCount: 0 }))).toEqual({
      kind: 'empty',
    });
    // ...and a scoped zero over a non-empty index is still zero to search: the number that matters
    // is the one the next question runs against.
    expect(projectSv3Corpus(index({ documentCount: 900, searchableDocumentCount: 0 }))).toEqual({
      kind: 'empty',
    });
  });
});

describe('the receipt tail states only what was measured', () => {
  it('renders seconds past a second and milliseconds below it', () => {
    expect(sv3ReceiptTail(45_700, null)).toBe('45.7 s');
    expect(sv3ReceiptTail(820, null)).toBe('820 ms');
  });

  it('omits — never fabricates — a part it was not given', () => {
    expect(sv3ReceiptTail(null, 'Qwen3')).toBe('Qwen3');
    expect(sv3ReceiptTail(null, null)).toBe('');
    expect(sv3ReceiptTail(null, '')).toBe('');
    // A clock that went backwards is not a negative duration; it is no duration.
    expect(sv3ReceiptTail(-5, 'Qwen3')).toBe('Qwen3');
  });

  it('joins the two with the donor separator, in that order', () => {
    expect(sv3ReceiptTail(45_700, 'Qwen3')).toBe('45.7 s · Qwen3');
  });
});

describe('the answer frame is derived from the shared authority, never worded here', () => {
  const source = (chunkIndex: number): RetrievalCitation =>
    ({ parentDocId: 'f:/a.md', chunkIndex, score: 0.7, excerpt: 'x' }) as RetrievalCitation;

  const evidence = (over: Partial<Sv3TurnEvidence> = {}): Sv3TurnEvidence => ({
    sources: [source(0)],
    matches: [],
    marks: [],
    retrievalMode: 'hybrid',
    ...over,
  });

  const turn = (over: Partial<Sv3Turn> = {}): Sv3Turn => ({
    id: 't1',
    kind: 'ask',
    question: 'why?',
    answer: 'Because the lock held.',
    status: 'complete',
    evidence: evidence(),
    detail: '',
    toolCalls: 0,
    activity: [],
    askedAt: 0,
    standaloneQuestion: '',
    reasoning: [],
    durationMs: 45_700,
    modelLabel: 'Qwen3',
    ...over,
  });

  /** The authority label the two rendered halves must recompose to, byte for byte. */
  const whole = (frame: { verdict: string | null; elaboration: string }): string | null =>
    frame.verdict === null
      ? null
      : frame.elaboration === ''
        ? frame.verdict
        : `${frame.verdict} — ${frame.elaboration}`;

  it('speaks the SHARED authority\'s wording — compared against its own output, not a literal', () => {
    // Sources attached, settled, no per-sentence match ⇒ the authority's `sourced` frame.
    const frame = projectSv3AnswerFrame(turn(), null);
    expect(whole(frame!)).toBe(answerFrameLabel('sourced', false));
    expect(frame?.verdict).not.toBeNull();
    expect(frame?.tail).toBe('45.7 s · Qwen3');
  });

  it('says nothing about grounding for a fully grounded answer — the marks already do', () => {
    const matches = [
      { sentenceIndex: 0, sentenceText: 'Because the lock held.', similarity: 0.9, chunkIndex: 0 },
    ] as unknown as CitationMatch[];
    const frame = projectSv3AnswerFrame(turn({ evidence: evidence({ matches }) }), null);
    expect(frame?.verdict).toBeNull();
    expect(frame?.elaboration).toBe('');
    expect(frame?.tail).toBe('45.7 s · Qwen3');
  });

  it('refuses to frame a turn the backend never sent evidence for', () => {
    // The probe for the tempting shortcut: treating `evidence === null` as zero sources would print
    // "searched your documents but found nothing to cite" over a search that may never have run.
    const frame = projectSv3AnswerFrame(turn({ evidence: null }), null);
    expect(frame?.verdict).toBeNull();
    expect(frame?.tail).toBe('45.7 s · Qwen3');
  });

  it('frames a settled answer with sources but no citable sentence as SOURCED, not grounded', () => {
    const frame = projectSv3AnswerFrame(turn(), null);
    expect(whole(frame!)).not.toBe(answerFrameLabel('grounded', false));
    expect(whole(frame!)).toBe(answerFrameLabel('sourced', false));
  });

  it('frames an answer with no sources at all as searched-but-uncitable', () => {
    const frame = projectSv3AnswerFrame(turn({ evidence: evidence({ sources: [] }) }), null);
    expect(whole(frame!)).toBe(answerFrameLabel('ungrounded', true));
  });

  it('frames nothing at all for a turn that is not a completed ask', () => {
    expect(projectSv3AnswerFrame(turn({ status: 'streaming' }), null)).toBeNull();
    expect(projectSv3AnswerFrame(turn({ status: 'halted' }), null)).toBeNull();
    expect(projectSv3AnswerFrame(turn({ status: 'failed' }), null)).toBeNull();
    expect(projectSv3AnswerFrame(turn({ kind: 'agent' }), null)).toBeNull();
  });

  it('is null when there is neither a label nor anything measured to say', () => {
    expect(
      projectSv3AnswerFrame(turn({ evidence: null, durationMs: null, modelLabel: null }), null),
    ).toBeNull();
  });

  /* ── F11: the label splits, and the model is named only when it would otherwise mislead ─────── */

  it('splits EVERY label the authority words into two non-empty halves that recompose exactly', () => {
    const frames: readonly (readonly [AnswerFrame, boolean])[] = [
      ['transform', false],
      ['partially-grounded', false],
      ['sourced', false],
      ['ungrounded', true],
      ['ungrounded', false],
    ];
    for (const [frame, degraded] of frames) {
      const label = answerFrameLabel(frame, degraded);
      expect(label, `${frame}/${degraded}`).not.toBeNull();
      const split = splitSv3FrameLabel(label as string);
      expect(split.verdict, `${frame} verdict`).not.toBe('');
      expect(split.elaboration, `${frame} elaboration`).not.toBe('');
      // Byte for byte: the window RE-WORDS NOTHING; both halves are the authority's own substrings.
      expect(`${split.verdict} — ${split.elaboration}`, `${frame} recomposition`).toBe(label);
    }
    // ...and the one frame that has no line at all still has none.
    expect(answerFrameLabel('grounded', false)).toBeNull();
  });

  it('rests the WHOLE label when the authority ever words one without the em dash', () => {
    // The fail-safe direction: the failure this window can afford is more text, never less.
    expect(splitSv3FrameLabel('Model answer')).toEqual({
      verdict: 'Model answer',
      elaboration: '',
    });
    // An en dash or a bare hyphen is NOT the authority's separator and must not be split on.
    expect(splitSv3FrameLabel('Model answer – some detail').elaboration).toBe('');
    expect(splitSv3FrameLabel('Model answer - some detail').elaboration).toBe('');
  });

  it("names the turn's model only when the composer would otherwise mislabel the answer", () => {
    expect(sv3TailModelLabel('Qwen3', 'Qwen3')).toBeNull();
    expect(sv3TailModelLabel('Qwen3', 'Llama-4')).toBe('Qwen3');
    // "Not said" is not "the same": an unknown current model cannot vouch for a stamped one.
    expect(sv3TailModelLabel('Qwen3', null)).toBe('Qwen3');
    // Nothing was stamped, so there is nothing to re-state — never a fabricated name.
    expect(sv3TailModelLabel(null, 'Qwen3')).toBeNull();
    expect(sv3TailModelLabel('', 'Qwen3')).toBeNull();
  });

  it('drops the model from the tail when the composer is already naming it', () => {
    // The mutation probe for the suppression: remove the equality check and the model stays here.
    expect(projectSv3AnswerFrame(turn(), 'Qwen3')?.tail).toBe('45.7 s');
    expect(projectSv3AnswerFrame(turn(), 'Llama-4')?.tail).toBe('45.7 s · Qwen3');
  });
});

/* ── F11: the tail's ONE disclosure ──────────────────────────────────────────────────────────── */

describe("the tail's sources trigger says only what the panel actually holds", () => {
  const source = (chunkIndex: number): RetrievalCitation =>
    ({ parentDocId: 'f:/a.md', chunkIndex, score: 0.7, excerpt: 'x' }) as RetrievalCitation;
  const match = (sentenceIndex: number): CitationMatch =>
    ({ sentenceIndex, sentenceText: 's', similarity: 0.9, chunkIndex: 0 }) as CitationMatch;

  const ev = (over: Partial<Sv3TurnEvidence> = {}): Sv3TurnEvidence => ({
    sources: [],
    matches: [],
    marks: [],
    retrievalMode: 'hybrid',
    ...over,
  });

  it('calls a retrieval set Sources and a match-only panel Citations', () => {
    expect(sv3SourcesTrigger(ev({ sources: [source(0)] }))).toBe(SOURCES_LABEL);
    // No retrieval was reported, so calling the matches "Sources" would claim one that never was.
    expect(sv3SourcesTrigger(ev({ matches: [match(0)] }))).toBe(CITATIONS_LABEL);
  });

  it('offers no trigger when there is nothing at all to open', () => {
    expect(sv3SourcesTrigger(ev())).toBeNull();
    expect(sv3SourcesTrigger(null)).toBeNull();
  });

  it('counts whichever set the trigger is speaking for', () => {
    expect(sv3SourcesTriggerCount(ev({ sources: [source(0), source(1)] }))).toBe(2);
    expect(sv3SourcesTriggerCount(ev({ matches: [match(0), match(1), match(2)] }))).toBe(3);
    expect(sv3SourcesTriggerCount(null)).toBe(0);
  });

  it('renders BOTH count shapes, so the owner-choice flip is one constant and not a rewrite', () => {
    expect(sv3SourcesTriggerLabel(SOURCES_LABEL, 5, false)).toBe('Sources');
    expect(sv3SourcesTriggerLabel(SOURCES_LABEL, 5, true)).toBe('5 Sources');
    // The shipped default is the owner's literal direction — the bare word.
    expect(SV3_SOURCES_COUNT_IN_TRIGGER).toBe(false);
    expect(sv3SourcesTriggerLabel(SOURCES_LABEL, 5)).toBe('Sources');
  });
});
