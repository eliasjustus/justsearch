// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — Aggregate Surfacing Substrate public API.
 *
 * Re-exports the substrate's stable surface. Strategies + per-
 * aggregate components live in sibling files (strategies/,
 * components/), imported separately.
 */

export {
  WIRE_AGGREGATE_KINDS,
  type WireAggregateKind,
  type AggregateOf,
  type AggregateOfMap,
  isWireAggregateKind,
} from './aggregateKinds.js';

export {
  SURFACE_CONTEXT_KINDS,
  type SurfaceContextKind,
  type SurfaceContextOf,
  type SurfaceContextOfMap,
  isSurfaceContextKind,
} from './surfaceContextKinds.js';

export {
  registerAggregateStrategy,
  dispatchAggregateStrategy,
  renderAggregate,
  getRegisteredCells,
  __clearAggregateRegistry,
  __resetAggregateSubstrateForTest,
  type AggregateStrategy,
  type StrategyHost,
  type StrategyResult,
  // Tempdoc 543 §13.3.2 — Multi-Provider Dispatch.
  setDispatchPolicy,
  getDispatchPolicy,
  __clearDispatchPolicies,
  dispatchAggregateStrategies,
  renderAggregateMulti,
  type DispatchPolicy,
} from './aggregateRegistry.js';

export {
  assertExhaustive,
  consumedKeys,
  type Consumed,
} from './assertExhaustive.js';

export * as operationQuery from './queryPrimitives.js';
