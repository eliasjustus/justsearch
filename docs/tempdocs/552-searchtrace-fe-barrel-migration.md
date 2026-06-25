---
title: "552 — Migrate the SearchTrace FE consumers off the frozen wire-types.ts barrel onto generated knowledge_pb (551 Part 2, opt-in)"
type: tempdocs
status: superseded
superseded-by: tempdoc 564 (contract-projection substrate)
created: 2026-05-27
updated: 2026-06-10
category: frontend-framework-kernel / wire-contract / search-ui
related:
  - tempdoc 553 (canonical-search-execution-record) — the umbrella design; this migration is the wire/FE projection instance
  - tempdoc 551 (wire-contract-searchtrace-gap) — Part 1 SHIPPED; this is its deferred Part 2
  - tempdoc 549 (unified-search-trace) — added SearchTrace to the barrel; COMPLETE/merged
  - ADR-08 / ADR-09a (docs/decisions/)
  - slice 3a-1-8 Phase 4 (slices/3a-1-8-wire-contract-architecture.md) — Item 4 (wire-types.ts deletion) deferred as opt-in capability
  - kernel 10-kernel/05-contract-substrate.md §"Capability vs Mandate"
---

# 552 — SearchTrace FE consumer migration (551 Part 2)

> **SUPERSEDED 2026-06-10 by tempdoc 564 — see [553 §0](553-canonical-search-execution-record.md#0-umbrella--searchtrace-instance-tracker-551--552--553) for the family tracker.**
> This doc's *goal* — get the FE search-trace consumers off the frozen `wire-types.ts` barrel and onto
> a single generated authority — was **achieved**, but by a different route than planned here. 564's
> contract-projection substrate **deleted `wire-types.ts`** and made `SearchTrace` a generated
> record→JSON-Schema→Zod projection (`api/generated/schema-types/search-trace.ts`). The FE now imports
> from that generated module (see `searchTraceExplain.ts:36`, `api/schemas.ts:141-143`) — **not** the
> `knowledge_pb` protobuf-es route this plan assumed. The `bigint`/`int32` friction analysis below was
> the motivation for that route and is now moot. No work remains; the doc is preserved as the dated
> reasoning trail for why the migration was framed this way before 564 landed.

> **Epistemic markers:** **[V]** = verified against source (mostly during 551);
> **[I]** = inferred / to-confirm. Subagents do not inherit `CLAUDE.md`/`.claude/rules` —
> the brief is self-contained, but re-verify **[I]** before acting.

## Purpose of this tempdoc

This tempdoc owns the **opt-in** second half of the 551 wire-contract work: migrating the
**FE search-trace consumers** off the frozen, no-longer-regenerated `wire-types.ts` barrel and
onto the protobuf-es types generated from `contracts/wire/knowledge.proto` (`knowledge_pb`), then
**deleting the trace types from the barrel**. It completes 551's declared end-state — the trace
defined once in the gated contract, consumed by the FE from the generated output — and closes the
one gap 551 Part 1 deliberately left open (see §"What Part 1 left open").

**This tempdoc is NOT a mandate to execute on sight.** Per the contract substrate's
*capability-vs-mandate* discipline (kernel `05-contract-substrate.md` §"Capability vs Mandate";
551 §B.5), per-consumer migration onto `knowledge_pb` is an **opt-in capability, not a campaign** —
the `_pb` destination is alive (ADR-08/09a un-rescinded, ArchUnit/ESLint barrel guards enforced),
but forcing a consumer to convert is itself minting a mandate, which the kernel says requires
**workload evidence**, not a default. So this tempdoc's *first* job is to record the justification
for doing it now (§"Justification gate"); only then does it become an implementation plan.

In short, the tempdoc's purpose is to:
1. Hold the **justification decision** (do it now, or leave the trace on the barrel until there is
   demand) — capability-vs-mandate.
2. If authorized: resolve the **central design question** (§"Design question") and execute the
   migration + barrel deletion as a focused, independently-verifiable slice.
3. Be the home for the residual record↔barrel drift risk until that risk is closed.

## Why this is separate from 551 Part 1

| | 551 Part 1 (SHIPPED `386c4f0ef`) | 552 / Part 2 (this doc) |
|---|---|---|
| Nature | Governance — bring the gated contract into conformance with reality | Consumer migration — change which artifact the FE imports |
| Discipline | **Mandated** (contract = SoT; ADR-08 forbids hand-mirrors) | **Opt-in** (capability-vs-mandate; needs workload evidence) |
| Touches | `knowledge.proto`, regen `knowledge_pb`, conformance test. **No FE consumer code.** | 4-5 FE files + `wire-types.ts`/barrel deletion |
| Roadmap-gated? | No (correct in 3 of 4 branches) | Yes (only do it with justification) |

## What Part 1 left open (the gap this closes)

Part 1's `KnowledgeWireContractConformanceTest` guards **record ↔ proto** parity. But the **live FE
still reads the frozen `wire-types.ts` barrel**, which is tied to neither the record nor the proto
by any check (it is a hand-frozen snapshot). So today **record ↔ barrel drift is unguarded**: a
future change to `SearchTrace.java` + `knowledge.proto` (caught by the conformance test) would NOT
force a matching edit to `wire-types.ts`, and the FE would silently read a stale shape. Part 2
closes this by making the FE consume the generated-from-proto type, so it inherits the proto's
guarantees and the barrel's trace types are deleted.

## Justification gate (decide first — capability-vs-mandate)

Candidate workload justifications for doing the migration now rather than waiting for demand:
- **Close the record↔barrel drift** described above (a correctness/governance argument, not
  cosmetic).
- **A genuinely clean pilot for the dormant-but-alive barrel→`_pb` migration.** [V] The trace
  types are already camelCase (no snake→camel access-site sweep) AND — because 551 modeled
  `TraceStage.ms` as `int32` — they emit `number`, **not `bigint`**, so they skip the `bigint`
  friction that actually deferred slice 3a-1-8 Item 4. The trace is the lowest-friction consumer
  in the codebase to migrate; doing it establishes/validates the per-consumer pattern for the rest.
- **protovalidate at the FE boundary** — the `status` vocabulary constraint Part 1 added only bites
  if the FE parses through protobuf-es (`wireValidator`). On the barrel path it is inert.

If none of these clears the bar for the owner, the correct outcome is **leave it** — the trace on
the barrel is functionally fine; Part 1 already closed the governance gap. Record this decision here.

## Design question (the crux — resolve before coding)

The FE↔Head wire is **JSON over REST**, not protobuf binary. So "use `knowledge_pb`" is not a
simple type-import swap: `protoc-gen-es` types are `Message` instances (with `$typeName`, methods,
oneof/enum shapes), not the plain interfaces the consumers use today. The runtime data is a parsed
JSON object, which is NOT a protobuf-es `Message`. Two paths:

- **(A) Parse through protobuf-es.** Route the knowledge search response through
  `fromJson(KnowledgeSearchResponseSchema, json)` (+ `wireValidator` for protovalidate, +
  `wireProjection` for any bigint — N/A for the trace given int32). Consumers then hold real
  generated types with runtime validation. **This is the established pattern** [V]:
  `lifecycleState.ts` imports `LifecycleState from './generated/status_pb'`; `wireProjection.ts`
  ("validate first, then project") + `wireValidator.ts` are the boundary helpers. **But** it likely
  displaces the current Zod `SearchResponseSchema` (`api/schemas.ts`) + the manual mapper
  (`api/domains/search.ts`) — larger surface than "the trace."
- **(B) Type-only adoption.** Use the generated TS types purely as static annotations over the
  plain JSON shape, without `fromJson`. Lower effort, but the runtime object is not actually a
  `Message`, so it is a type-level fiction that can mislead and forfeits protovalidate — likely an
  anti-pattern the barrel guards exist to prevent. **[I] confirm whether the lint/ArchUnit barrel
  rules even permit importing a `_pb` type without parsing through it.**

**Decide A vs B, and decide the scope:** *just the trace types*, or *the whole knowledge search
response*. The honest reading is that a faithful migration is path A and naturally pulls in the
search-response parsing layer — which is precisely why this is "opt-in, needs its own justification"
and not a 20-minute import swap. Scope this explicitly before starting.

## Scope / files (when authorized)

FE consumers importing the trace types from the barrel (`../api/generated/index.js` → `wire-types`) [V]:
- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.ts` (+ `.test.ts`)
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfSearchTrace.ts`
- `modules/ui-web/src/shell-v0/state/searchState.ts`
- `modules/ui-web/src/shell-v0/views/SearchSurface.ts`

Plus the parsing/mapping layer (path A): `modules/ui-web/src/api/domains/search.ts`,
`modules/ui-web/src/api/schemas.ts`. And the deletion: remove `SearchTrace`/`TraceStage`/`TraceQpp`/
`TraceDegradation`/`HitStage` (+ the `searchTrace`/`trace` fields if KnowledgeSearchResponse/Hit are
migrated too) from `modules/ui-web/src/api/generated/wire-types.ts` and the barrel re-export in
`modules/ui-web/src/api/generated/index.ts` (lines ~78-83). Confirm no other importer breaks first.

## Verification (when authorized)
- `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- **Live-stack** (this is a user-facing consumer — static green ≠ live working): a real search
  renders the explain panel from the trace; `jseval ui-shot search-results`; `ai_activate` so the
  cross-encoder stage is `executed`. Load `/ui-check`.
- `node scripts/governance/run.mjs --gate consumer-drift --mode gate` (the `jf-search-trace` mount
  slot) and `--gate stage-completeness`.
- Confirm the `KnowledgeWireContractConformanceTest` (551) still green.

## Pointers
- Established `_pb` consumption precedent: `modules/ui-web/src/api/lifecycleState.ts`,
  `modules/ui-web/src/api/wireProjection.ts`, `modules/ui-web/src/api/wireValidator.ts`.
- Barrel header documenting the transitional intent: `modules/ui-web/src/api/generated/index.ts` (lines 1-21).
- Generated trace types (Part 1 output): `modules/ui-web/src/api/generated/knowledge_pb.d.ts`.
- Regenerate after any further proto change: `./gradlew :wireGenerate`.

## Scope guard
Wire/FE-boundary precision work. Resolve the §"Justification gate" and §"Design question" with the
421 owner before writing code; this is opt-in by discipline, and path A is materially larger than
"swap the trace imports." Do not delete the barrel trace types until the consumers are migrated and
live-verified.
