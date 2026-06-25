// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — WireAggregateKind enumeration.
 *
 * The closed set of wire-emitted aggregates the FE renders today.
 *
 * Population history:
 *  - 511 (initial): Operation, Resource, HealthEvent, OperationInvocation.
 *  - 511-followup Track D: trimmed to Operation + Resource. HealthEvent
 *    + OperationInvocation removed because the strategies had no
 *    production consumer (HealthSurface used a local-shape interface,
 *    not the wire shape; nothing mounted OperationInvocation).
 *  - 511-followup-A: HealthEvent reintroduced WITH consumer
 *    (HealthSurface.renderEvents now mounts <jf-health-event>). The
 *    surface had been silently rendering undefined fields against
 *    the local-shape cast; the migration fixes that defect by
 *    consuming the wire shape via the substrate.
 *  - OperationInvocation: still not reintroduced. Real consumers
 *    exist (AdvisoryToastHost, AdvisoryInboxDrawer call
 *    JSON.parse(action.defaultArgsJson)) but the substrate fit is
 *    a design decision (specialized advisory rendering vs generic
 *    strategy). Tracked as 511-followup-C.
 *
 * Adding a new aggregate kind is a substrate change: declare it
 * here, extend `AggregateOfMap` with the corresponding type
 * mapping, register at least one canonical strategy, and ensure
 * a production consumer surface dispatches it.
 */

import type {
  Operation,
  Resource,
} from '../../api/types/registry.js';
import type {
  HealthEvent,
  SearchTrace,
} from '../../api/generated/index.js';

export const WIRE_AGGREGATE_KINDS = [
  'Operation',
  'Resource',
  'HealthEvent',
  // Tempdoc 549 Phase E4: 'SearchIntrospection' retired — superseded by 'SearchTrace'.
  'SearchTrace',
] as const;

export type WireAggregateKind = (typeof WIRE_AGGREGATE_KINDS)[number];

/**
 * Type mapping from kind to the corresponding TypeScript shape. A
 * canonical strategy for kind `K` operates on `AggregateOf<K>`.
 */
export interface AggregateOfMap {
  Operation: Operation;
  Resource: Resource;
  HealthEvent: HealthEvent;
  /**
   * Tempdoc 549: the unified stage-keyed search trace — the single canonical
   * artifact spanning both processes, emitted on every {@link KnowledgeSearchResponse}.
   * Consumed by the canonical (SearchTrace, search-explain) strategy. Supersedes
   * the SearchIntrospection aggregate, which retires in Phase E4.
   */
  SearchTrace: SearchTrace;
}

export type AggregateOf<K extends WireAggregateKind> = AggregateOfMap[K];

export function isWireAggregateKind(value: string): value is WireAggregateKind {
  return (WIRE_AGGREGATE_KINDS as readonly string[]).includes(value);
}
