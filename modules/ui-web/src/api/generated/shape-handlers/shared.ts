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
