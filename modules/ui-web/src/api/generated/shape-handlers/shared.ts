/**
 * Shared leaf payload types referenced by the generated shape-handler interfaces
 * (tempdoc 564). These are the nested object shapes the (non-recursive) EventDescriptor
 * model references by name — `TracePayload` (the cross-cutting trace envelope on agent
 * events) and `ProposedCall` (an element of `tool_batch_proposed.calls`). Kept here as the
 * one hand-written piece; the scalar/enum/array fields of every event are generated.
 *
 * Sources: TracePayload mirrors ToolIteratingShapeRunner.toTraceMap; ProposedCall mirrors
 * ProposedBatchProjection.project.
 */

/** The optional trace envelope appended to every agent-loop SSE event when it has identity. */
export interface TracePayload {
  runId?: string;
  stepId?: string;
  spanId?: string;
  parentSpanId?: string;
  agentId?: string;
  toolCallId?: string;
  iteration: number;
}

/** One proposed tool call in a `tool_batch_proposed` event's `calls` array. */
export interface ProposedCall {
  callId: string;
  toolName: string;
  /** Present when the tool resolved to a known Operation. */
  risk?: string;
  /** The predicted gate behavior, present when an evaluator was available. */
  gateBehavior?: string;
}

/**
 * Tempdoc 565 §3.A — one grounding source behind the agent's answer (an element of the `done`
 * event's `sources` array): a chunk-identified local passage. Mirrors the backend
 * `AgentEvent.AgentSource`; `parentDocId` + `startLine`/`endLine` drive the click-to-local-line
 * deep-link (the same path the RAG citations use).
 */
export interface AgentSource {
  parentDocId: string;
  chunkIndex: number;
  path: string;
  title: string;
  excerpt: string;
  startLine: number;
  endLine: number;
  headingText: string;
  /**
   * Tempdoc 865 §7.5 — whether this passage was still in the prompt the answer was written from,
   * spelled EXACTLY as the RAG plane spells it (`RAGContext` writes these same two keys onto a
   * `rag.citations` entry), because the panel reads both planes through one shared evidence record.
   *
   * ABSENT (the key omitted) means the producer resolved nothing — the state of every delegate
   * source persisted before this field, and of every source whose carrier message is still intact.
   * It is never "included": see `AgentSession.inclusionFor` for why this producer states `dropped`
   * or nothing at all.
   */
  contextInclusion?: string;
  /** Characters of this passage that reached the model. Absent together with `contextInclusion`. */
  contextIncludedChars?: number;
  /**
   * Tempdoc 868 §B.3 (865 §7.6's acquisition axis, second value) — HOW this source came to be in
   * front of the model: `"retrieved"` (a ranked search hit) or `"opened"` (the agent read the
   * document by name through `core_read_document`).
   *
   * ABSENT means `"retrieved"`, and that default is honest rather than convenient: until the read
   * tool existed every delegate source was a search hit, so absence is a known fact about the
   * producer, not a guess about it. The narrowing lives in ONE place — `acquisitionOf` in
   * `evidenceProjection.ts` — and this stays a raw wire `string` for the same reason
   * {@link contextInclusion} does: a field pre-narrowed to the union would force a cast here.
   */
  acquisition?: string;
}

/**
 * Tempdoc 565 §3.A — one answer sentence matched to a grounding source (an element of the `done`
 * event's `citations` array). Mirrors the backend `AgentEvent.AgentSentenceCite`.
 */
export interface AgentSentenceCite {
  sentenceText: string;
  sourceIndex: number;
  similarity: number;
}

/**
 * Tempdoc 834 §6.2 — one tool call currently held at an approval gate (an element of the
 * `state_snapshot` event's `pendingApprovals` array). Mirrors the backend
 * `AgentEvent.PendingApproval`, which carries the same five values the `tool_call_pending` event
 * announced. It rides the SNAPSHOT because the replay ring evicts: after a long run parks at a
 * gate, the announcing frame may be gone while the gate is still open and answerable.
 *
 * The array itself is optional on the payload, and that distinction is load-bearing: `undefined`
 * means UNKNOWN (a legacy `events.ndjson` record written before 834), while `[]` means none are
 * pending. Rendering "no approvals pending" for the former is the bug the optionality prevents.
 */
export interface PendingApproval {
  callId: string;
  toolName: string;
  arguments: string;
  risk: string;
  /** Absent when no gate evaluator was available at emit time. */
  gateBehavior?: string;
}

/**
 * Tempdoc 834 §1.5 / §6.2 — why a run is currently stopped (the `state_snapshot` event's `park`,
 * present only while the run IS parked). Mirrors the backend `AgentEvent.ParkSnapshot`.
 */
export interface ParkSnapshot {
  /** `approval` | `budget` | `context` | `unobserved`. */
  kind: string;
  /** When the park began, or `0` when it has no recorded start (the zero-observer park). */
  sinceEpochMs: number;
  detail: string;
}
