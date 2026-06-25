// SPDX-License-Identifier: Apache-2.0
/**
 * TypeScript types for the HealthEvent wire payload streamed by
 * `/api/health/events/stream`.
 *
 * Tempdoc 564 Phase 1 (health): the single authority for the HealthEvent wire
 * shape is the generated record→JSON-Schema→{TS,Zod} projection at
 * `../generated/schema-types/health-event.ts`. This module no longer
 * hand-declares the wire shape; it re-exports the generated `HealthEvent` (+ its
 * Zod `healthEventSchema`) and derives the FE-ergonomic *body* aliases from it
 * via `Extract`/`NonNullable` — so a second hand copy cannot drift from the
 * record. The SSE-envelope types (snapshot/delta/heartbeat) wrap `HealthEvent`
 * and stay here (they are not part of the wire record).
 */

import type { HealthEvent as WireHealthEvent } from '../generated/schema-types/health-event.js';

export type { HealthEvent } from '../generated/schema-types/health-event.js';
export { healthEventSchema } from '../generated/schema-types/health-event.js';

/** The discriminated body union, projected from the single generated authority. */
export type HealthEventBody = NonNullable<WireHealthEvent['body']>;

/** One-shot lifecycle occurrence body. */
export type LifecycleEventBody = Extract<HealthEventBody, { kind: 'lifecycle' }>;

/** k8s-shaped persistent assertion body. */
export type AssertedConditionBody = Extract<HealthEventBody, { kind: 'condition' }>;

/** Prometheus-shaped persistent threshold body. */
export type ThresholdStateBody = Extract<HealthEventBody, { kind: 'threshold' }>;

/** Severity is per-occurrence on the wire (per §B.A); not a catalog-static field. */
export type HealthSeverity = NonNullable<WireHealthEvent['severity']>;

/** k8s metav1.ConditionStatus shape. */
export type ConditionStatus = NonNullable<AssertedConditionBody['status']>;

/** Prometheus-shaped threshold lifecycle state. */
export type ThresholdPhase = NonNullable<ThresholdStateBody['phase']>;

/** OTel Resource semconv (per §B.I). */
export type HealthSource = NonNullable<WireHealthEvent['source']>;

/** SSE 'snapshot' event payload. */
export interface HealthSnapshot {
  catalogVersion: number;
  conditions: WireHealthEvent[];
  occurrences: WireHealthEvent[];
}

/** Discriminator for SSE 'delta' events (matches HealthEventChangeRegistry.Kind). */
export type HealthChangeKind =
  | 'condition-added'
  | 'condition-modified'
  | 'condition-removed'
  | 'occurrence-appended';

/** SSE 'delta' event payload. */
export interface HealthDelta {
  catalogVersion: number;
  kind: HealthChangeKind;
  event: WireHealthEvent;
}

/** SSE 'heartbeat' event payload. */
export interface HealthHeartbeat {
  catalogVersion: number;
}

/**
 * Canonical key for a persistent-state event in the FE map. Matches the backend's
 * ConditionStore key shape (per rev 3.11 §B.X.2 generalization handles both bodies
 * uniformly). Lifecycle events don't go in this map (they're appended to the
 * occurrences ring buffer instead).
 */
export function conditionKey(event: WireHealthEvent): string | null {
  const body = event.body;
  if (event.id && body && (body.kind === 'condition' || body.kind === 'threshold')) {
    return event.id + '|' + (body.subject ?? '');
  }
  return null;
}
