---
title: "Wire contract format — protobuf + protovalidate (SUPERSEDED)"
type: decision
status: superseded
description: "V1 picked protobuf 3 + protovalidate as the wire-contract spec format. Superseded by tempdoc 564, which found proto3 cannot faithfully model the wire and demoted it to a derived view."
date: 2026-06-09
---


# ADR-0040: Wire contract format — protobuf + protovalidate (SUPERSEDED)

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

**Superseded by tempdoc 564.** This ADR chose protobuf 3 + protovalidate as the wire-contract *source* format; tempdoc 564 reversed exactly that — proto3 cannot faithfully model the JustSearch wire, so the source is the **Java record** (record-as-IDL) and proto is a derived/gated view, not unified. Retained for historical context per the ADR lifecycle.

## Decision

The wire-Category instance of the contract substrate
(`0039-contract-substrate.md`) uses **protobuf 3 syntax with
the protovalidate v1.0 invariant framework** as its spec source format.
The seven-axis substrate's per-target emitters are:

- **Java**: `protoc-gen-java` + `protovalidate-java` (Maven artifact;
  CEL-based runtime validators, GA April 2026).
- **TypeScript**: `protoc-gen-es` (idiomatic protobuf-es output) AND/OR
  `protoc-gen-ts_proto` with `useJsonName=true` + `snakeToCamel=` for
  finer-grained snake_case round-trip control. Final pick between the
  two is at slice 3a-1-8 Phase 4 pickup.
- **Zod / runtime validators on FE**: `@bufbuild/protovalidate` (the
  ECMAScript runtime; reads the protovalidate descriptors and
  evaluates CEL constraints symmetrically with `protovalidate-java`).
- **JSON Schema 2020-12 (for OpenAPI documentation)**:
  `protoschema-plugins` (Buf-distributed Go binary; emits strict-mode
  JSON Schema from the same proto). Adopted at slice 3a-1-8b Phase 6
  alongside the publishing pipelines, NOT at the format-choice phase.

ADR-08's original four candidates (OpenAPI 3.1, TypeSpec, Protobuf,
Java records as DSL) are now resolved:

- **OpenAPI 3.1** is reserved as a **fallback** if a future hard case
  surfaces that Candidate A cannot meet. ADR-08's "single source of
  truth for shape" framing remains historically correct; under
  Candidate A's choice, the SoT *is* protobuf, and OpenAPI emission
  flows from `protoschema-plugins` for documentation.
- **TypeSpec** is rejected. Its Java emitter dropped Jackson and is
  preview-only; its `@typespec-tools/emitter-zod` package is dormant.
  TypeSpec is regressing on the symmetric-emit axis.
- **Java records as DSL** is rejected. protovalidate's CEL covers
  the runtime-invariant axis the custom DSL was supposed to fill;
  shipping a custom annotation processor when the substrate's seven
  axes have a production-GA off-the-shelf solution is YAGNI per
  CLAUDE.md's "speculative abstractions" prohibition.

This ADR is a sub-decision of `0039-contract-substrate.md`.
The substrate's commitments stand regardless of format choice; this
ADR picks the format under which V1 ships.

## Rationale

Slice 3a-1-8 Phase 1's hands-on spike (artifacts at
`tmp/contract-spike/`) verified Candidate A against the seven-axis
test:

- **Axis 1 (shape) — discriminated unions**: protobuf's native `oneof`
  produces nested JSON, mismatching JustSearch's flat wire shape (sharp
  edge SE-1). Resolution: model the discriminated union as a single
  message with explicit `kind` discriminator + variant-specific fields
  at the top level. CEL constraints enforce per-`kind` required-fields.
  Verified live in the spike: `HealthEventBody` emits the flat wire
  shape `{kind: "condition", subject: ..., reason: ...}` natively
  through `protoc-gen-es` with `target=js+dts`.

- **Axis 2 (required-ness)**: protobuf's `optional` keyword + protovalidate's
  `(buf.validate.field).required = true` annotation distinguish "absent
  vs null vs always-present." Verified via `Severity` enum — emitted
  as `SEVERITY_UNSPECIFIED = 0` per proto3 convention; runtime check
  `enum.defined_only = true` rejects unknown enum values.

- **Axis 3 (runtime invariants) — the highest-value axis**:
  - `(buf.validate.field).string.pattern` carries the regex.
  - `(buf.validate.field).string.min_len = 1` carries the non-blank
    check.
  - `(buf.validate.message).cel = { ... }` carries cross-field
    constraints (e.g., "condition kind requires subject + reason").
  - **Verified live**: the spike's `test-validate.mjs` constructs a
    `HealthEventBody` with `reason = "worker-starting"` (hyphens not
    allowed); protovalidate-es **rejects** with `"does not match regex
    pattern '^[A-Za-z]([A-Za-z0-9_,:]*[A-Za-z0-9_])?$'"`. Same
    behavior would fire on Java side via `protovalidate-java`. This
    is the symmetric-invariant property ADR-08 named as the substrate's
    most-important property and that the original four candidates left
    scattered. **Production-GA across Java + TS via the same protovalidate
    runtime.**

- **Axis 4 (cross-references)**: encoded via custom field options
  (e.g., `(justsearch.wire.references) = "OperationRegistry"`) at
  slice 3a-1-8c. Format-agnostic; protobuf's option machinery makes
  this a one-line annotation per field. Live verification deferred
  to 3a-1-8c.

- **Axis 5 (behavior separation)**: protobuf-generated TS is
  pure-data (interfaces only, no methods). Java emission via
  `protoc-gen-java` produces messages, not records — RecordBuilder
  interaction is a slice 3a-1-8 Phase 3 concern (Java emitter choice
  may pivot to a records-aware tool; documented as Phase 3 sub-spike).

- **Axis 6 (evolution rules)**: protobuf's field numbers + `reserved`
  + `buf breaking` provide GA structural-diff. CHANGELOG enforcement
  + reviewer discipline shipped in V1 per slice 3a-1-8 Phase 5b.
  **V1.5 live (2026-05-07)**: kernel-mediated mechanical structural-
  diff via slice 3a-1-8f. The governance kernel
  (`scripts/contract-governance/`) registers `buf breaking` as the
  protobuf-format enforcer; the truth-table cross-validates declared
  classification × structural diff × VERSION delta. SARIF v2.1.0 is
  the report payload. **`bufbuild/buf-action` was considered and
  rejected** per slice 3a-1-8f §A.12 — its load-bearing value
  (`pull_request`-event PR-comment posting + label-as-escape-hatch)
  did not fit the then-current ADR-0026 `workflow_dispatch`-only CI
  model; ADR-0044 later narrowed ADR-0026 for public hosted CI, but it
  does not by itself re-adopt `bufbuild/buf-action`. The buf CLI is
  invoked directly. Format extensibility lives in the
  enforcer interface (one implementation per format), not in this
  ADR; concrete enforcers ship when a real second-format consumer
  surfaces.

- **Axis 7 (catalog membership)**: protobuf enums are closed sets;
  `protoc-gen-es` emits TS enums; protovalidate's `enum.defined_only`
  enforces. **Caveat surfaced live** (Test 3 in the spike): the
  substrate's `(buf.validate.field).string.in: [...]` constraint is
  *incompatible with forward-compat unknown variants* — when the
  `kind` field is constrained by an `in:` list, the validator rejects
  unknown future variants. **Production specs must NOT use `in:`
  constraints on the discriminator field; instead, the discriminator
  is a free string and the consumer's emitter handles unknown values
  via the `UnknownEventBody` fallback.** This is a documented production
  rule, recorded here.

### Mixed snake/camel naming (SE-4)

proto3 JSON's default is camelCase. JustSearch's wire format mixes
snake_case (e.g., `schema_version`, `worker.queue_depth`) and camelCase
(e.g., `indexBasePath`, `worker.indexHealthy`). Resolution:

- Every snake_case wire field declares `[json_name = "snake_case_name"]`
  to override proto3 JSON's camelCase default.
- `ts-proto` with `useJsonName=true` honors the override on parse +
  emit.
- `protoc-gen-es` with default settings honors `[json_name]` for
  serialization.

**Caveat surfaced live, corrected from earlier draft**: BOTH standard
emitters (ts-proto AND protobuf-es) camelCase TS interface property
names by default — verified empirically via the spike output (`service_name`
→ `serviceName` in both `wire_pb.d.ts` and `wire.ts`). The earlier
ADR-09a draft claimed protobuf-es preserves snake_case TS verbatim;
that was wrong. **camelCase is the protobuf ecosystem's TS convention
regardless of emitter choice.** The TS-side property name is *separate*
from the wire JSON field name (which can stay snake_case via
`[json_name]`).

JustSearch's existing `wire-types.ts` (typescript-generator output)
preserves snake_case TS properties (e.g., `worker_state?: string`).
Adopting any standard protobuf emitter means TS-side property names
shift to camelCase. This is a structural change vs the existing FE
convention. Phase 4 has three real options:

1. **Accept camelCase TS uniformly** (V1 recommendation). FE access
   sites currently using snake_case (e.g., `worker.queue_depth`,
   `lifecycle.reason_code`, `data.observed_at`, `data.schema_version`)
   migrate to camelCase. Wire format unchanged; FE ergonomic alignment
   with JS conventions. **Migration cost: an audit of FE access sites
   to snake_case wire fields is a Phase 4 §A pre-impl item;
   approximate count from existing wire-types.ts: every snake_case
   field in the records inventory's snake_case-tagged records
   (WorkerDebugView's ~15 fields, Lifecycle's `reason_code`,
   StatusResponse's `schema_version` + `observed_at`, others).**
2. **Custom post-emit step** that re-projects camelCase TS interfaces
   into snake_case property names matching the wire. Defeats the
   unification's "regenerate from one source" property; ships custom
   tooling. Reject unless the FE migration in option 1 surfaces
   unacceptably high cost.
3. **Hybrid**: keep typescript-generator output for snake_case-required
   types during a transition window; use protobuf-es for new code.
   Defeats the unification's V1 ship; reject as anti-pattern.

V1 recommendation: option 1. Phase 4 §A pre-impl audit enumerates the
exact access-site count; Phase 4 §B records the migration outcome.
Phase 4's session estimate widens by ~1-2 sessions to absorb the
access-site sweep (per the matrix update in slice 3a-1-8 §B.6).

### Dual-name aliases (SE-2)

`KnowledgeStatusView` emits both `pendingJobs` (canonical) and
`queueDepth` (deprecated alias) on the same payload. protobuf doesn't
support dual-name field emission natively. Resolution: declare both
fields in the contract spec; mark the alias `[deprecated = true]`;
populate both server-side. The CEL invariant
`this.pending_jobs == this.queue_depth` enforces consistency at
construction time. Verified in the spike's `KnowledgeStatusView`
declaration.

### Forward-compat unknown variants

The spike's `(buf.validate.field).string.in: [...]` constraint on
the `kind` field rejects unknown values. Production rule (recorded
in this ADR's "Future Agents Must Not"): **do NOT constrain the
discriminator field to a closed set**; the discriminator is a free
string + the consumer's emitter falls back to `UnknownEventBody`
for unknown values. This is the substrate's forward-compat axis 6
property; the spike's constraint was for testing purposes only.

## Rejects

- **OpenAPI 3.1 + JSON Schema 2020-12** as the spec source. Tooling-
  side discriminator bugs (openapi-typescript #1690 / #2149 / #1464 /
  #1158, openapi-generator #23289 partial fix), no GA invariant
  emitter symmetric across Java + TS, manual evolution-rule discipline.
  Reserved as fallback if a future hard case surfaces.
- **TypeSpec**. Regressing on symmetric emit (Java emitter dropped
  Jackson, Zod emitter dormant).
- **Custom Java records DSL**. CEL via protovalidate covers the same
  axis with production-GA tooling; custom DSL is YAGNI.
- **`(buf.validate.field).string.in: [...]`** on discriminator fields.
  Incompatible with forward-compat unknown variants. Production specs
  use free-string + emitter-side fallback.
- **`oneof`-shaped discriminated unions** when the wire shape is flat.
  Use single-message-with-discriminator pattern instead.

## Future Agents Must Not

- Constrain a discriminator field with `in: [...]` at the protovalidate
  level. The discriminator is a free string; the consumer's emitter
  (e.g., FE's `HealthEventBody` discriminator-mapping) falls back to
  `UnknownEventBody` for unknown values per axis 6 forward-compat.
- Use `oneof` for sealed-union body types when the wire shape is flat.
  Use the single-message-with-discriminator pattern instead. The
  trade-off (single message admits any field combination at proto
  level) is enforced via CEL `option (buf.validate.message).cel`
  cross-field constraints.
- Skip `[json_name = "..."]` on snake_case wire fields. proto3 JSON's
  camelCase default would otherwise silently flip the field name.
- Emit dual-name aliases without a CEL consistency invariant. The
  `this.canonical == this.alias` check guards against producer-side
  drift.
- Use TypeSpec, custom Java DSLs, or hand-written per-language
  validators when the substrate's protovalidate path covers the case.
  Format choice graduations require revising this ADR.
- Assume protobuf TS emitters preserve snake_case TS interface property
  names. Both standard emitters (protoc-gen-es, ts-proto) camelCase
  TS by default; this was verified empirically in the Phase 1 spike.
  V1 ships camelCase TS uniformly; the wire format keeps snake_case
  via per-field `[json_name = "..."]` overrides. FE consumers shift
  from `worker.queue_depth`-style access to `worker.queueDepth` at
  Phase 4 §A's audit-driven sweep.
- Assume protoc-gen-java produces Java records. The canonical Java
  emitter produces protobuf Messages (Builder pattern, Message
  interface, getter-prefix accessors). Phase 3 picks one of the four
  options in slice 3a-1-8 §"Phase 3 Java-side projection shape sub-
  spike"; V1 recommendation is option C (two-layer wrap that preserves
  Java records ergonomics on the consumer side).

## Revisit When

- protovalidate v2 ships with breaking changes; CEL syntax extends.
- A new contract Category surfaces that protobuf cannot honestly
  carry (e.g., a streaming wire format that doesn't fit
  message/service); the substrate's vocabulary-governance per
  `04-shape-governance.md` may add a sibling format.
- Java emitter pivot. Phase 3's protoc-gen-java + record-shape
  interaction may surface needs that the canonical emitter cannot
  meet (e.g., RecordBuilder integration). A sub-spike picks an
  alternative Java emitter without revisiting this ADR.
- A multi-language port materializes (Rust / Go / Swift). The same
  proto sources serve all three via their respective protoc plugins.
- The forward-compat constraint pattern (free-string discriminator +
  emitter-side fallback) proves disruptive in practice — alternative
  resolution patterns may surface (e.g., protobuf's official
  `Any`-typed unknown variant).

## Affected Docs

- `0039-contract-substrate.md` (architectural commitment;
  this ADR is a sub-decision picking the format).
- `../reference/ui/frontend-kernel/kernel/05-contract-substrate.md` (substrate primitive's
  format-agnostic commitments stay; this ADR fills the format slot).
- `slices/3a-1-8-wire-contract-architecture.md` (Phase 1 §B closure
  records this ADR's landing).
- `slices/3a-1-8b-contract-distribution.md` (publishing infra targets
  Maven jar + npm package per the chosen emitters; protoschema-plugins
  for OpenAPI doc emission).
- `slices/3a-1-8c-cross-reference-enforcement.md` (cross-references
  encoded via custom field options).
- `slices/3a-1-8d-catalog-consolidation.md` (catalog Categories use
  protobuf enums + protovalidate `enum.defined_only`).
- `tmp/contract-spike/` (sandbox artifacts: `wire.proto`, `buf.yaml`,
  `buf.gen.yaml`, generated output, `test-validate.mjs` runtime-
  verification harness).

## Source Evidence

- Slice 3a-1-8 §A.1 (tooling refresh, May 2026 → present, recorded
  protovalidate v1.0 GA as the substrate's invariant-axis enabler).
- Slice 3a-1-8 §A.5 (format spike sketches written pre-implementation
  with documented sharp edges per candidate).
- `tmp/contract-spike/protobuf/wire.proto` (live-tested spec).
- `tmp/contract-spike/protobuf/test-validate.mjs` (live-tested
  protovalidate-es invariant verification — the `worker-starting`
  rejection that proves regex enforcement is symmetric).
- `tmp/contract-spike/protobuf/gen/protobuf-es/wire_pb.{js,d.ts}`
  (live-generated descriptor + types; descriptor blob preserves all
  protovalidate annotations).
- `tmp/contract-spike/protobuf/gen/ts-proto/wire.ts` (live-generated
  alternative TS output; surfaces the `useJsonName` vs `snakeToCamel`
  TS-property-naming caveat).
- Phase 1 §B closure in `slices/3a-1-8-wire-contract-architecture.md`
  (records the spike's outcomes + sharp edges + production rules).

## Alternatives Considered (deferred to in-Phase decisions, not ADR-grade)

- **`protoc-gen-es` vs `ts-proto` for the FE TS emitter.** Both produce
  working output. protobuf-es preserves proto field names verbatim
  (snake_case stays snake_case in TS); ts-proto applies its own
  naming convention. Phase 4 picks; if option 1 above (camelCase
  uniformly) is chosen, ts-proto is the natural fit; if option 2
  (preserve snake_case TS) is chosen, protobuf-es is. V1 recommendation:
  protobuf-es as primary; ts-proto only if a specific need surfaces.
  Phase 4 §B records the choice.

- **Java emitter choice.** `protoc-gen-java` (canonical, message-based)
  vs alternative tools that emit Java records natively. The canonical
  emitter produces messages, not records; Phase 3 either accepts the
  message-based Java API or pivots to a records-aware emitter. The
  pivot is a sub-spike at Phase 3 pickup, not an ADR-grade decision.
