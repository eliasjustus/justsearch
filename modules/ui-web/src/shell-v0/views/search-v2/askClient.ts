// SPDX-License-Identifier: Apache-2.0
/**
 * askClient — the ONE ASK issuance site of the Search v2 window (tempdoc 818 slice 2).
 *
 * Every grounded answer this window produces is dispatched from {@link askDocuments} and nowhere
 * else. That is the point of the module boundary: the shipped window's L9 defect is not that its
 * 423 handling is wrong, it is that ONE of its send paths (the agent branch,
 * `UnifiedChatView.ts:5688-5706`) dispatches without any 423 handling at all — an asymmetry only a
 * second issuance site can have. With a single issuance site the refusal is structural: there is no
 * second path to forget.
 *
 * Shared authorities consumed, not re-authored:
 *  - `buildRequestBody('core.rag-ask', …)` — the per-shape POST body (question + docIds).
 *  - `consumeShapeStream` + `dispatchShapeEventToHandlers` — the ONE SSE consumer. No hand-rolled
 *    parsing, no second fetch; the connection-budget accounting in `streams.ts` stays correct.
 *  - the GENERATED `CoreRagAskHandlers` interface — so a new shape event is a compile-time fact.
 *
 * L5 — `docIds` is the caller's frozen set, verbatim: the answer is retrieval-scoped to exactly the
 * committed snapshot, not to whatever the live search has drifted to.
 *
 * Registered in `governance/execution-surfaces.v1.json` (`sv2-ask-client`) as an opaque carrier of
 * the `RetrievalCitation` sibling evidence record: it hands the backend's citation set to its caller
 * unchanged and projects no field of it.
 */

import {
  consumeShapeStream,
  dispatchShapeEventToHandlers,
  type RagMetaPayload,
} from '../../../api/streams.js';
import type { CoreRagAskHandlers } from '../../../api/generated/shape-handlers/core-rag-ask.js';
import { buildRequestBody } from '../unifiedChatRequest.js';
import type {
  CitationMatch,
  Claim,
  RetrievalCitation,
} from '../../components/chat/citationTypes.js';

/** The one shape this window's ask route dispatches. */
export const ASK_SHAPE_ID = 'core.rag-ask';

export interface AskRequest {
  readonly apiBase: string;
  readonly question: string;
  /** The window's session id — stamped on every dispatch so the backend records the turn. */
  readonly conversationId: string;
  /** L5 — the frozen set's document ids. Empty array = open retrieval (the backend's fallback). */
  readonly docIds: readonly string[];
  readonly signal?: AbortSignal;
}

/** Everything a completed ask turn knows about itself. Nothing here is authored by the FE. */
export interface AskOutcome {
  readonly text: string;
  readonly claims: readonly Claim[];
  readonly citations: readonly CitationMatch[];
  readonly sources: readonly RetrievalCitation[];
  readonly retrievalMode: string | null;
  readonly chunksUsed: number | null;
  readonly grounding: { readonly sentencesMatched: number; readonly sentencesTotal: number } | null;
  readonly promptTokens: number | null;
}

export interface AskSink {
  /** Streaming text, delta by delta. Accumulates in VIEW state — never in the records array (L4). */
  onDelta(text: string): void;
  onDone(outcome: AskOutcome): void;
  /** L9 — the session lock refused this send (HTTP 423). The ONLY 423 consumer in this window. */
  onLocked(): void;
  onError(message: string): void;
}

/** Accumulator for the per-sentence grounding model the two citation events both contribute to. */
interface ClaimAcc {
  text: string;
  verifiedScore: number | null;
  lexicalScore: number;
  /** Tempdoc 822 §3b — split by producer; only verified refs may resolve a mark (deltas arrive first). */
  verifiedRefs: Set<number>;
  lexicalRefs: Set<number>;
}

/** Tempdoc 822 §3d — which event scored this sentence (the handler knows; the payload need not). */
type ScoreProvenance = 'verified' | 'lexical';

function mergeClaim(
  acc: Map<number, ClaimAcc>,
  sentenceIndex: number,
  sentenceText: string,
  score: number,
  provenance: ScoreProvenance,
  sourceIndex: number | null,
): void {
  const existing = acc.get(sentenceIndex);
  if (existing) {
    if (provenance === 'verified') {
      existing.verifiedScore = Math.max(existing.verifiedScore ?? 0, score);
      if (sourceIndex !== null) existing.verifiedRefs.add(sourceIndex);
    } else {
      existing.lexicalScore = Math.max(existing.lexicalScore, score);
      if (sourceIndex !== null) existing.lexicalRefs.add(sourceIndex);
    }
    return;
  }
  const verifiedRefs = new Set<number>();
  const lexicalRefs = new Set<number>();
  if (sourceIndex !== null) {
    (provenance === 'verified' ? verifiedRefs : lexicalRefs).add(sourceIndex);
  }
  acc.set(sentenceIndex, {
    text: sentenceText,
    verifiedScore: provenance === 'verified' ? score : null,
    lexicalScore: provenance === 'lexical' ? score : 0,
    verifiedRefs,
    lexicalRefs,
  });
}

function claimsOf(acc: Map<number, ClaimAcc>): Claim[] {
  return [...acc.entries()]
    .map(([sentenceIndex, v]) => ({
      sentenceIndex,
      sentenceText: v.text,
      verifiedScore: v.verifiedScore,
      lexicalScore: v.lexicalScore,
      verifiedRefs: [...v.verifiedRefs],
      lexicalRefs: [...v.lexicalRefs],
    }))
    .sort((a, b) => a.sentenceIndex - b.sentenceIndex);
}

function messageOf(err: unknown): string {
  if (err instanceof Error && err.message) return err.message;
  return 'The answer could not be produced.';
}

/**
 * Dispatch one grounded ask and drive `sink` from the stream. Never throws: every terminal —
 * completion, refusal, failure — arrives as exactly one sink call, so a caller can rely on the
 * answer slot always reaching a terminal state.
 */
export async function askDocuments(req: AskRequest, sink: AskSink): Promise<void> {
  const body = buildRequestBody(ASK_SHAPE_ID, req.question, req.conversationId, '', [
    ...req.docIds,
  ]);
  // The turn is recorded against THIS window's session, exactly as the shipped window stamps it.
  body.conversationId = req.conversationId;

  let text = '';
  let sources: RetrievalCitation[] = [];
  let citations: CitationMatch[] = [];
  let retrievalMode: string | null = null;
  let chunksUsed: number | null = null;
  let grounding: AskOutcome['grounding'] = null;
  let promptTokens: number | null = null;
  const claimAcc = new Map<number, ClaimAcc>();

  const handlers: CoreRagAskHandlers = {
    onChunk(payload: unknown) {
      const p = payload as { text?: unknown } | string | null;
      const delta = typeof p === 'string' ? p : typeof p?.text === 'string' ? p.text : '';
      if (!delta) return;
      text += delta;
      sink.onDelta(delta);
    },
    onRagMeta(payload: unknown) {
      const p = payload as RagMetaPayload | null;
      if (!p) return;
      if (typeof p.retrieval_mode === 'string') retrievalMode = p.retrieval_mode;
      if (typeof p.chunks_used === 'number') chunksUsed = p.chunks_used;
    },
    onRagCitations(payload: unknown) {
      const p = payload as { citations?: RetrievalCitation[] } | null;
      if (p && Array.isArray(p.citations)) sources = p.citations;
    },
    onRagCitationDelta(payload: unknown) {
      const p = payload as {
        sentenceIndex?: number;
        sentenceText?: string;
        citations?: Array<{ sourceIndex?: number; score?: number }>;
      } | null;
      if (!p || typeof p.sentenceText !== 'string' || !Array.isArray(p.citations)) return;
      const best = Math.max(0, ...p.citations.map((c) => (typeof c.score === 'number' ? c.score : 0)));
      const ref = p.citations[0]?.sourceIndex;
      mergeClaim(
        claimAcc,
        p.sentenceIndex ?? 0,
        p.sentenceText,
        best,
        'lexical',
        typeof ref === 'number' ? ref : null,
      );
    },
    onRagCitationMatches(payload: unknown) {
      const p = payload as {
        matches?: CitationMatch[];
        sentencesMatched?: number;
        sentencesTotal?: number;
      } | null;
      if (!p) return;
      if (Array.isArray(p.matches)) {
        citations = p.matches;
        for (const m of p.matches) {
          mergeClaim(
            claimAcc,
            m.sentenceIndex ?? 0,
            m.sentenceText ?? '',
            typeof m.similarity === 'number' ? m.similarity : 0,
            'verified',
            typeof m.sourceIndex === 'number' ? m.sourceIndex : null,
          );
        }
      }
      // L6 — the grounding counts come from the payload that measured them. The FE never counts
      // sentences itself, so the line cannot claim a measurement the backend did not make.
      if (typeof p.sentencesMatched === 'number' && typeof p.sentencesTotal === 'number') {
        grounding = { sentencesMatched: p.sentencesMatched, sentencesTotal: p.sentencesTotal };
      }
    },
    onDone(payload: unknown) {
      const p = payload as { promptTokens?: unknown } | null;
      if (p && typeof p.promptTokens === 'number') promptTokens = p.promptTokens;
    },
  };

  try {
    await consumeShapeStream(
      `${req.apiBase}/api/chat/dispatch`,
      body,
      (event, payload) =>
        dispatchShapeEventToHandlers(handlers as Record<string, unknown>, event, payload),
      req.signal,
    );
  } catch (err) {
    // L9 — the ONE refusal path. `consumeShapeStream` stamps the HTTP status on the thrown error,
    // so a lock taken between this window's render and its submit is a typed outcome here, not a
    // generic failure the caller has to sniff for.
    const status = (err as Error & { status?: number }).status;
    if (status === 423) {
      sink.onLocked();
      return;
    }
    sink.onError(messageOf(err));
    return;
  }

  sink.onDone({
    text,
    claims: claimsOf(claimAcc),
    citations,
    sources,
    retrievalMode,
    chunksUsed,
    grounding,
    promptTokens,
  });
}
