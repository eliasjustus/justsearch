/**
 * Wire-types barrel.
 *
 * Tempdoc 564: every FE wire type is the generated record→JSON-Schema→{TS,Zod} projection in
 * `./schema-types/`. The barrel re-exports those generated authorities (plus the FE-ergonomic body
 * aliases derived from them — see the health family below) so existing consumers keep stable import
 * paths. The earlier parallel paths are retired: the typescript-generator `wire-types.ts` snapshot
 * (Phase 4) and the `*_pb`-sourced FE types (the `*_pb` files remain the gated buf contract + runtime
 * validators, but no longer source FE types).
 */

// Tempdoc 564 Phase 4: TimeseriesSnapshot derives from its generated schema-types projection; this
// was the last barrel re-export sourced from the parallel `wire-types.ts` path, now retired.
export type { TimeseriesSnapshot } from './schema-types/timeseries-snapshot.js';

// Tempdoc 564 Phase A (status collapse): `StatusResponse` is now the SINGLE generated authority in
// `schema-types/` (record → JSON Schema → {TS, Zod}); the barrel re-exports THAT, not the parallel
// `wire-types.ts` projection. This retires the third-representation (the hand `SystemStatusSchema`
// `.loose()` Zod is deleted too). The `wire-types.ts` StatusResponse definition is now vestigial
// (generated, unconsumed) — removed when the wire-types path is fully demoted (incremental, §4b).
export type { StatusResponse } from './schema-types/index.js';

// Tempdoc 564 Phase 3: the unified stage-keyed search-trace types are now the generated Zod
// projections (record → JSON Schema → {TS, Zod}); the raw REST JSON is validated at the searchState
// parse boundary via Zod. The FE no longer imports `knowledge_pb` (proto) for these. `TraceQpp` /
// `TraceDegradation` stay inline on `SearchTrace` (no by-name consumers), so they are not re-exported.
export type { SearchTrace } from './schema-types/search-trace.js';
export type { TraceStage } from './schema-types/trace-stage.js';
export type { HitStage } from './schema-types/hit-stage.js';

// Tempdoc 564 Phase 1 (health): the HealthEvent wire shape is the single generated authority in
// `schema-types/health-event.ts` (record → JSON Schema → {TS, Zod}). The barrel re-exports THAT and
// derives the FE-ergonomic body subtype names (the record-named `AssertedCondition`/`LifecycleEvent`/
// `ThresholdState` + `Severity`/`Source`) from it via `Extract`/`NonNullable` — so no hand copy can
// drift. The old hand `HealthEvent`/`UnknownEventBody`/body-union override + the `wire-types.ts` health
// re-exports are retired (the sealed body union has no Unknown variant). `MetricRef` now also comes
// from the generated health module. The `wire-types.ts` health definitions are vestigial (generated,
// unconsumed) — removed when the wire-types path is fully demoted (§4b / Phase 4).
import type { HealthEvent as WireHealthEvent } from './schema-types/health-event.js';

export type { HealthEvent, MetricRef } from './schema-types/health-event.js';

/** The discriminated body union, projected from the single generated authority. */
export type HealthEventBody = NonNullable<WireHealthEvent['body']>;
export type AssertedCondition = Extract<HealthEventBody, { kind: 'condition' }>;
export type LifecycleEvent = Extract<HealthEventBody, { kind: 'lifecycle' }>;
export type ThresholdState = Extract<HealthEventBody, { kind: 'threshold' }>;
export type Severity = NonNullable<WireHealthEvent['severity']>;
export type Source = NonNullable<WireHealthEvent['source']>;
export type HealthSeverity = Severity;
export type HealthSource = Source;
