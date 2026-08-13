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
  sv3ReceiptTail,
  SV3_CORPUS_UNKNOWN,
} from './sv3-honesty.js';
import { answerFrameLabel } from '../../components/chat/evidenceProjection.js';
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

  it('speaks the SHARED authority\'s wording — compared against its own output, not a literal', () => {
    // Sources attached, settled, no per-sentence match ⇒ the authority's `sourced` frame.
    const frame = projectSv3AnswerFrame(turn());
    expect(frame?.label).toBe(answerFrameLabel('sourced', false));
    expect(frame?.label).not.toBeNull();
    expect(frame?.tail).toBe('45.7 s · Qwen3');
  });

  it('says nothing about grounding for a fully grounded answer — the marks already do', () => {
    const matches = [
      { sentenceIndex: 0, sentenceText: 'Because the lock held.', similarity: 0.9, chunkIndex: 0 },
    ] as unknown as CitationMatch[];
    const frame = projectSv3AnswerFrame(turn({ evidence: evidence({ matches }) }));
    expect(frame?.label).toBeNull();
    expect(frame?.tail).toBe('45.7 s · Qwen3');
  });

  it('refuses to frame a turn the backend never sent evidence for', () => {
    // The probe for the tempting shortcut: treating `evidence === null` as zero sources would print
    // "searched your documents but found nothing to cite" over a search that may never have run.
    const frame = projectSv3AnswerFrame(turn({ evidence: null }));
    expect(frame?.label).toBeNull();
    expect(frame?.tail).toBe('45.7 s · Qwen3');
  });

  it('frames a settled answer with sources but no citable sentence as SOURCED, not grounded', () => {
    const frame = projectSv3AnswerFrame(turn());
    expect(frame?.label).not.toBe(answerFrameLabel('grounded', false));
    expect(frame?.label).toBe(answerFrameLabel('sourced', false));
  });

  it('frames an answer with no sources at all as searched-but-uncitable', () => {
    const frame = projectSv3AnswerFrame(turn({ evidence: evidence({ sources: [] }) }));
    expect(frame?.label).toBe(answerFrameLabel('ungrounded', true));
  });

  it('frames nothing at all for a turn that is not a completed ask', () => {
    expect(projectSv3AnswerFrame(turn({ status: 'streaming' }))).toBeNull();
    expect(projectSv3AnswerFrame(turn({ status: 'halted' }))).toBeNull();
    expect(projectSv3AnswerFrame(turn({ status: 'failed' }))).toBeNull();
    expect(projectSv3AnswerFrame(turn({ kind: 'agent' }))).toBeNull();
  });

  it('is null when there is neither a label nor anything measured to say', () => {
    expect(
      projectSv3AnswerFrame(turn({ evidence: null, durationMs: null, modelLabel: null })),
    ).toBeNull();
  });
});
