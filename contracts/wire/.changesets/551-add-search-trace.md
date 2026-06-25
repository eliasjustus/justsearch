---
evolution-rule: additive-optional
---
Tempdoc 551 Part 1 promotes the unified `SearchTrace` into the gated wire contract.

Tempdoc 549 collapsed five search-explainability representations into one canonical
stage-keyed `SearchTrace`, but added it only to the app-api Java record + the frozen
`wire-types.ts` barrel — never to `contracts/wire/knowledge.proto`. So `buf breaking`
guarded a contract that did not describe the trace (zero protection), and protovalidate
could not carry its invariants. This changeset closes that governance gap (551 §B.4),
independent of any FE-consumer migration (that is 551 Part 2, opt-in).

Additions (all optional / non-breaking):

- `KnowledgeSearchResponse.search_trace` (field 27, `json_name = "searchTrace"`).
- `Hit.trace` (field 9, `repeated HitStage`).
- New messages `SearchTrace`, `TraceQpp`, `TraceDegradation`, `TraceStage`, `HitStage`,
  mirroring the app-api records `SearchTrace` / `SearchTrace.{Qpp,Degradation,TraceStage,HitStage}`
  (`SearchTrace.java`) — the runtime JSON source of truth.

protovalidate: `TraceStage.status` is constrained to the closed set
{executed, skipped, disabled, failed} (a status flag, safe to constrain). `TraceStage.id`
(the StageId wireId) is left a FREE STRING per ADR-09a §"Forward-compat unknown variants"
(`in:`-constraining a discriminator would reject future stage variants).

`TraceStage.ms` is modeled as `int32` (not `int64`) so `protoc-gen-es` emits `number`, not
`bigint`, faithful to the width-less JSON `ms` (551 §B.7).

This brings the gated contract into conformance with a shape ALREADY live in the runtime
JSON and `wire-types.ts` — it creates no new public commitment. A new app-api
record↔proto field-parity test (`KnowledgeWireContractConformanceTest`) now guards against
the recurrence of this omission class (a record field with no proto field).
