// SPDX-License-Identifier: Apache-2.0
/**
 * Universal SSE envelope types (slice 436).
 *
 * All four SSE Resources in the kernel — HealthEvent, RuntimeContext,
 * OperationHistory, ServerCapabilities — emit frames in this shape.
 * The constant SSE event name is `"frame"`. The dispatcher routes by
 * `frameKind` and (for LIFECYCLE) `payload.kind`.
 */

/**
 * The envelope wrapping every SSE frame, regardless of stream id.
 *
 * Wire-format reference: see backend
 * `modules/app-streaming-envelope/src/main/.../SseEnvelope.java`.
 */
export interface SseEnvelope<P = unknown> {
  /** Stream identifier (e.g., 'health-event/v1'). Set by the producer. */
  streamId: string;
  /** Frame discriminator: UPDATE = domain delta, LIFECYCLE = connection signal. */
  frameKind: 'UPDATE' | 'LIFECYCLE';
  /** Monotonically increasing per-connection sequence number. */
  seq: number;
  /** ISO-8601 producer timestamp. */
  ts: string;
  /** Domain payload. Shape varies by streamId + frameKind. */
  payload: P;
  /**
   * Token the client can pass back via `?since=<token>` on a fresh
   * connection to replay missed frames within the server's resume window.
   */
  resumeToken: string;
}

/**
 * Lifecycle payload `kind` discriminator. Domain-agnostic.
 *
 * - `connected` — first frame after the connection establishes.
 * - `snapshot` — replaces the consumer's local state.
 * - `heartbeat` — keep-alive; advances seq + resumeToken only.
 * - `reset` — server signals consumer should drop cached state and
 *   wait for the next snapshot.
 * - `closing` — server is closing the connection (clean shutdown).
 */
export type LifecycleKind =
  | 'connected'
  | 'snapshot'
  | 'heartbeat'
  | 'reset'
  | 'closing';

/**
 * Lifecycle payload base shape. The Resource may attach
 * domain-specific fields (e.g., HealthEvent's snapshot includes
 * `conditions` + `occurrences`). Consumers cast to a more specific
 * type after checking `kind`.
 */
export interface LifecyclePayloadBase {
  kind: LifecycleKind;
}

/**
 * Update payload base shape. Each Resource defines its own update
 * `kind` enum (e.g., 'condition-added' | 'condition-modified' |
 * 'condition-removed' | 'occurrence-appended' for HealthEvent).
 */
export interface UpdatePayloadBase {
  kind: string;
}

/**
 * Type guard: lifecycle frames carry `LifecyclePayloadBase`-shaped
 * payloads. Useful when the consumer's reducer wants to bail out
 * early on lifecycle frames vs delta frames.
 */
export function isLifecycleEnvelope(
  envelope: SseEnvelope,
): envelope is SseEnvelope<LifecyclePayloadBase> {
  return envelope.frameKind === 'LIFECYCLE';
}

/**
 * Type guard: update frames carry `UpdatePayloadBase`-shaped payloads.
 */
export function isUpdateEnvelope(
  envelope: SseEnvelope,
): envelope is SseEnvelope<UpdatePayloadBase> {
  return envelope.frameKind === 'UPDATE';
}
