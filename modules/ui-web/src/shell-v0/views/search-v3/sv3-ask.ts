// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-ask — the ONE ask-issuance site of the Search v3 window (tempdoc 822 Phase F1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Every answer this window produces is dispatched from {@link sv3Ask} and nowhere else, and
 * `sv3-ask.forbid.test.ts` fails the build if a second `consumeShapeStream` caller appears in
 * `search-v3/`. That is the point: the shipped window's L9 defect is not that its 423 handling is
 * wrong, it is that ONE of its send paths dispatches without any — an asymmetry only a second
 * issuance site can have. With one site the refusal is structural: there is no second path to forget.
 *
 * Shared authorities consumed, not re-authored (the charter's "from-scratch components, shared
 * authorities"; the PATTERN was mined from the since-retired search-v2 window's ask client — never
 * imported, because a dev window importing another dev window's module is the coupling this arc
 * exists to avoid):
 *  - `buildRequestBody('core.rag-ask', …)` — the per-shape POST body.
 *  - `consumeShapeStream` + `dispatchShapeEventToHandlers` — the ONE SSE consumer, so the
 *    connection-budget accounting in `streams.ts` stays correct.
 *  - the GENERATED `CoreRagAskHandlers` interface — a new shape event is a compile-time fact.
 *  - `claimsToCitations` (Phase F4) — the ONE claim→mark resolver, so this window's inline `[n]`
 *    marks are the same resolution the shipped surfaces render.
 *  - `friendlyStreamError` (Phase F7) — the ONE stream-error vocabulary.
 *
 * Phase F7 also opened the two channels this shape declares and F1 left unread: `reasoning_chunk`
 * (the model's thinking) and `rag.rewrite` (the standalone question retrieval ran on). Both are
 * handed to the caller rather than interpreted here — the reasoning payload goes to the shared
 * `ReasoningController` verbatim.
 *
 * Registered in `governance/execution-surfaces.v1.json` (`sv3-ask-client`) as an opaque carrier of
 * the `RetrievalCitation` evidence record: it accumulates the backend's citation payloads and hands
 * them to its caller unchanged, deriving nothing from a citation's fields.
 *
 * FOUR TERMINALS, EXACTLY ONE OF THEM, ALWAYS. `sv3Ask` never throws: completion, refusal (the
 * session lock's 423), a halt the reader asked for, and failure are four distinct sink calls, so the
 * caller's turn can always reach a terminal state and can always say WHICH one it reached. A halt is
 * not a failure and must not be worded as one — the reader stopped the answer on purpose.
 */

import {
  consumeShapeStream,
  dispatchShapeEventToHandlers,
  type RagMetaPayload,
} from '../../../api/streams.js';
import type { CoreRagAskHandlers } from '../../../api/generated/shape-handlers/core-rag-ask.js';
import { buildRequestBody } from '../unifiedChatRequest.js';
// The ONE stream-error vocabulary (slice 497; inventory E9). A technical code becomes the same
// sentence here as it does in the shipped window; an abort never reaches it (see the catch below).
import { friendlyStreamError } from '../../utils/streamError.js';
import type {
  CitationMatch,
  Claim,
  RetrievalCitation,
} from '../../components/chat/citationTypes.js';
// The ONE claim→mark resolver (565 §15.B). The window resolves nothing itself: it hands the
// backend's claims + sources to the shared authority and stores what comes back.
import { claimsToCitations } from '../../components/chat/citationResolve.js';
import type { Sv3TurnEvidence } from './sv3-sessions.js';

/** The one shape this window's ask route dispatches. */
export const SV3_ASK_SHAPE_ID = 'core.rag-ask';

/**
 * How much work the next question asks for (tempdoc 822 Phase F10; the §4b ADAPTATION RATIFIED —
 * the spec's per-session provider picker maps to an effort control, because there is ONE local
 * model and therefore no provider to pick).
 *
 * Every rung is a REAL request parameter the shared dispatch path already reads. Verified at the
 * source rather than assumed, because a control that changes nothing is the exact failure this
 * window exists to avoid:
 *  - `enableThinking` — `ConversationEngine.java:781-786` turns it into `SamplingParams`, and
 *    `OnlineModeOps.java:611-614` forwards it to llama-server as
 *    `chat_template_kwargs={"enable_thinking": …}`. It is the switch behind the reasoning block
 *    Phase F7 wired (inventory C9).
 *  - `maxTokens` — `ConversationEngine.java:772-778`; absent means the engine's own
 *    `DEFAULT_MAX_TOKENS = 1024` (`:65`).
 *  - `topK` — `RAGContext.java:421-428`; precedence is body → configured → `DEFAULT_TOP_K = 5`
 *    (`:55`, `:88-94`), so an explicit per-request value always wins.
 *
 * There is deliberately NO reasoning-effort or posture parameter in the table: the backend has
 * none (`buildRequestBody` declares question/prompt/docIds/schema/sessionId/selection and nothing
 * else), and inventing a name the server ignores would be the dead control in disguise.
 *
 * ONE honest side effect, stated where the mapping is made: `parseSamplingParams` returns sampling
 * params ONLY when `enableThinking` is present, and the object it builds pins temperature 0.8 /
 * top_p 0.95 with it (`ConversationEngine.java:780-786`). So the two rungs that name thinking also
 * pin those two, and `standard` — which sends no parameter at all — is the only rung that leaves
 * every sampling decision to the backend. That is why `standard` is the default and sends `{}`
 * rather than a spelled-out copy of the defaults: a rung that restates the backend's numbers would
 * silently fork them the first time they change.
 */
export type Sv3Effort = 'quick' | 'standard' | 'thorough';

export interface Sv3EffortOption {
  readonly id: Sv3Effort;
  /** The trigger's label when this rung is chosen — the spec's trigger IS the current value. */
  readonly label: string;
  /** One line, in the menu, saying exactly what the rung changes (the spec's item description). */
  readonly description: string;
  /** The rung the window starts on; the spec badges it in the menu. */
  readonly isDefault: boolean;
}

/** The menu's group label (one per descriptor). */
export const SV3_EFFORT_MENU_LABEL = 'Effort';

export const SV3_EFFORT_OPTIONS: readonly Sv3EffortOption[] = [
  {
    id: 'quick',
    label: 'Quick',
    description: 'Skips the thinking step and keeps the answer short.',
    isDefault: false,
  },
  {
    id: 'standard',
    label: 'Standard',
    description: 'Leaves every setting to the model.',
    isDefault: true,
  },
  {
    id: 'thorough',
    label: 'Thorough',
    description: 'Thinks first, allows a longer answer, and retrieves more passages.',
    isDefault: false,
  },
];

export const SV3_EFFORT_DEFAULT: Sv3Effort = 'standard';

export const isSv3Effort = (value: unknown): value is Sv3Effort =>
  SV3_EFFORT_OPTIONS.some((option) => option.id === value);

export const sv3EffortLabel = (effort: Sv3Effort): string =>
  SV3_EFFORT_OPTIONS.find((option) => option.id === effort)?.label ?? '';

/**
 * The rung → request parameters mapping, and the ONLY place it is made. Each field below is one
 * sentence of the option's description, so a copy change that stops being true has to happen here,
 * next to the parameter that made it true.
 */
export function sv3EffortParams(effort: Sv3Effort): Readonly<Record<string, unknown>> {
  switch (effort) {
    case 'quick':
      return { enableThinking: false, maxTokens: 512 };
    case 'thorough':
      return { enableThinking: true, maxTokens: 3072, topK: 12 };
    case 'standard':
    default:
      return {};
  }
}

export interface Sv3AskRequest {
  readonly apiBase: string;
  readonly question: string;
  /** The window's session id — stamped on every dispatch so the backend records the turn. */
  readonly conversationId: string;
  /** The composer's effort rung for THIS send; omitted is the default rung, not "no parameters". */
  readonly effort?: Sv3Effort;
  readonly signal?: AbortSignal;
}

export interface Sv3AskSink {
  /** Streaming text, delta by delta. The accumulated answer lives in the caller's turn. */
  onDelta(text: string): void;
  /**
   * The retrieval evidence the backend minted for this answer, as a WHOLE snapshot — never a delta
   * and never a count. Phase F1 kept only the count because the window had no resolver behind it;
   * Phase F4 has the shared ones, so the turn stores what the answer actually stood on and every
   * number on screen is read off that one record (tempdoc 822 Phase F4).
   *
   * Called on each contributing event rather than once at the terminal: retrieval happens BEFORE the
   * text finishes, so a halted turn keeps the sources that really arrived.
   */
  onEvidence(evidence: Sv3TurnEvidence): void;
  /**
   * The model's own thinking, chunk by chunk (`reasoning_chunk`, a declared event of this shape —
   * `api/generated/shape-handlers/core-rag-ask.ts`). Handed over as the RAW payload because the
   * SHARED `ReasoningController` is the one thing that parses it, accumulates it and times it
   * (inventory C9); a second parse here would be a second reasoning model.
   */
  onReasoning(payload: unknown): void;
  /**
   * A follow-up was decontextualized before retrieval ran (`rag.rewrite`; tempdoc 603 C2, inventory
   * C8). What is handed over is what retrieval ACTUALLY searched for, which is the whole point of
   * showing it back.
   */
  onRewrite(standalone: string): void;
  onDone(): void;
  /** The session lock refused this send (HTTP 423) — the ONLY 423 consumer in this window. */
  onRefused(): void;
  /** The reader pressed Stop. Whatever streamed so far is kept; the turn is halted, not failed. */
  onHalted(): void;
  onFailed(message: string): void;
}

/**
 * The per-sentence grounding model both citation events contribute to. Mined from the since-retired
 * search-v2 window's ask client (the same reason F1 mined the dispatch: a dev window importing
 * another dev window's module is the coupling this arc exists to avoid); the RESOLVER it feeds is
 * shared.
 */
interface ClaimAcc {
  text: string;
  verifiedScore: number | null;
  lexicalScore: number;
  /**
   * Tempdoc 822 §3b — the refs are split by producer for the same reason the scores are: deltas
   * arrive FIRST, so one merged set made `refs[0]` the streaming guess on every doubly-matched
   * sentence, and the resolver took `refs[0]`. Only the verified set may resolve a mark.
   */
  verifiedRefs: Set<number>;
  lexicalRefs: Set<number>;
}

/**
 * Tempdoc 822 §3d — which producer scored this sentence. The two citation events are already
 * separate handlers, so the provenance needs no wire field: it is the handler that knows.
 */
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
    // The two scores are maxed WITHIN a scale, never across one (822 §3d: no monotone mapping
    // between a coverage ratio and a relevance probability exists to max over), and the refs land
    // on the matching side for the same reason (822 §3b).
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

/**
 * Dispatch one grounded ask and drive `sink` from the stream.
 *
 * `docIds` is empty by construction: this window has no committed document set to scope an answer to
 * (the retired search-v2 window's frozen-snapshot law has no counterpart here yet), and an empty
 * array is the backend's documented open-retrieval fallback (`views/unifiedChatRequest.ts:80-84`) rather than a scope this
 * FE invented.
 */
export async function sv3Ask(req: Sv3AskRequest, sink: Sv3AskSink): Promise<void> {
  const body = buildRequestBody(SV3_ASK_SHAPE_ID, req.question, req.conversationId, '', []);
  // The turn is recorded against THIS window's session, exactly as the shipped window stamps it.
  body.conversationId = req.conversationId;
  // The effort rung's parameters, added AFTER the shared builder rather than inside it: the builder
  // is the shipped window's authority over the per-shape body and this window may not widen it. The
  // fields land beside `conversationId`, which arrives the same way and for the same reason.
  Object.assign(body, sv3EffortParams(req.effort ?? SV3_EFFORT_DEFAULT));

  let sources: readonly RetrievalCitation[] = [];
  let matches: readonly CitationMatch[] = [];
  let retrievalMode = '';
  const claims = new Map<number, ClaimAcc>();
  /**
   * The evidence record is rebuilt WHOLE on every contributing event and handed over as one value,
   * so its parts can never be written separately and drift: the marks are always the resolution of
   * exactly the claims and sources beside them.
   */
  const publish = (): void =>
    sink.onEvidence({
      sources,
      matches,
      marks: claimsToCitations(claimsOf(claims), sources),
      retrievalMode,
    });

  const handlers: CoreRagAskHandlers = {
    onReasoningChunk(payload: unknown) {
      sink.onReasoning(payload);
    },
    onRagRewrite(payload: unknown) {
      const p = payload as { standalone?: unknown } | null;
      if (typeof p?.standalone !== 'string') return;
      sink.onRewrite(p.standalone);
    },
    onChunk(payload: unknown) {
      const p = payload as { text?: unknown } | string | null;
      const delta = typeof p === 'string' ? p : typeof p?.text === 'string' ? p.text : '';
      if (delta === '') return;
      sink.onDelta(delta);
    },
    onRagMeta(payload: unknown) {
      const p = payload as RagMetaPayload | null;
      if (!p || typeof p.retrieval_mode !== 'string') return;
      retrievalMode = p.retrieval_mode;
      publish();
    },
    onRagCitations(payload: unknown) {
      const p = payload as { citations?: RetrievalCitation[] } | null;
      if (!p || !Array.isArray(p.citations)) return;
      sources = p.citations;
      publish();
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
        claims,
        p.sentenceIndex ?? 0,
        p.sentenceText,
        best,
        'lexical',
        typeof ref === 'number' ? ref : null,
      );
      publish();
    },
    onRagCitationMatches(payload: unknown) {
      const p = payload as { matches?: CitationMatch[] } | null;
      if (!p || !Array.isArray(p.matches)) return;
      matches = p.matches;
      for (const m of p.matches) {
        mergeClaim(
          claims,
          m.sentenceIndex ?? 0,
          m.sentenceText ?? '',
          typeof m.similarity === 'number' ? m.similarity : 0,
          'verified',
          typeof m.sourceIndex === 'number' ? m.sourceIndex : null,
        );
      }
      publish();
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
    // The signal is consulted BEFORE the error is classified: an abort surfaces as whatever the
    // platform's fetch rejects with (`DOMException: AbortError` in a browser, a plain error in some
    // test environments), so keying on the error's shape would report the reader's own Stop as a
    // backend failure on some runtimes and not others.
    if (req.signal?.aborted === true) {
      sink.onHalted();
      return;
    }
    // The ONE refusal path. `consumeShapeStream` stamps the HTTP status on the thrown error, so a
    // lock taken between this window's render and its submit is a typed outcome here, not a generic
    // failure the caller has to sniff for.
    if ((err as Error & { status?: number }).status === 423) {
      sink.onRefused();
      return;
    }
    // The shared mapping, reached only AFTER the abort and the refusal have been ruled out above —
    // which is exactly the shipped window's ordering (`views/UnifiedChatView.ts:6019-6024`) and the
    // reason a reader's own Stop is never worded as a failure (inventory E9).
    sink.onFailed(friendlyStreamError(err));
    return;
  }

  sink.onDone();
}
