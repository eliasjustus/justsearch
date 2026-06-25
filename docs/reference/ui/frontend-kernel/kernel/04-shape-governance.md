---
title: "Frontend kernel — shape governance"
type: reference
status: stable
description: "How new shapes/Categories are governed: typed values inside an existing primitive, never a new primitive per kind."
date: 2026-06-09
---

# Frontend kernel — shape governance

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


Shape governance prevents the framework from becoming either too generic or too
bespoke.

## Entry Kind Before Primitive

When a new need appears, ask first:

1. Is this an `Operation`?
2. Is this a `Resource`?
3. Is this a `Prompt`?
4. Is this an entry kind inside one of those?
5. Is this product-specific surface data?

Create a new primitive only after those options fail.

## Shape Proposal

A new framework shape requires a proposal with:

- owner
- user need
- backend truth owner
- expected consumers
- schema and versioning
- renderer strategy
- failure behavior
- plugin interaction
- migration story
- acceptance tests

## Bespoke Surface Acceptance

A bespoke product surface is justified when:

- the interaction cannot be honestly represented by generic renderers
- domain-specific layout is essential
- the surface still consumes backend truth through kernel contracts
- private readiness/state-machine logic is avoided

Bespoke does not mean unconstrained.

## Anti-Patterns

- adding one primitive per backend subsystem
- letting every surface re-fetch registry data
- using generic renderers for workflows they cannot explain
- hiding unsupported fields to make a UI look clean
- treating plugin convenience APIs as core truth
- letting examples become accidental product mandates
- **conflating data source with information shape** — naming a slice
  by its data source ("Log Resource", "Job Queue Resource",
  "Settings Resource") rather than by the structural shape the
  workload needs (`DiagnosticChannel` / TABULAR / STATE). Source-named
  slices permit the implicit shape choice that this governance exists
  to surface explicitly. The same anti-pattern reaches one layer up:
  **conflating presentation primitive with information shape** —
  naming a slice after a renderer ("Lit Sparkline component")
  rather than after the typed Resource Category the workload needs
  (TIMESERIES). See `slices/444-resource-category-substrate.md`
  §B.1 (first instance: producer-vs-shape, "Log Resource" → historical
  LOG_TAIL vs HISTORY; later resolved by slice 448 as
  `DiagnosticChannel`) and `slices/3a-1-4-timeseries-resource-category.md`
  §B.1 (second instance: renderer-vs-shape, "Lit Sparkline" →
  TIMESERIES Resource Category) +
  `60-migration-history/06-resource-category-rewrite.md` for the
  combined methodology lesson.
- **adding a sub-primitive to model a typed shape**. If a shape needs
  per-shape fields (e.g., `HistoryPolicy` on `EVENT_STREAM` and
  `HISTORY` Resources but not on `STATE`), the fields belong as
  `Optional<>`-shaped fields on the existing primitive, gated by the
  Category-vocabulary constraint matrix. Splitting Resource into
  `EventStreamResource`, `HistoryResource`, etc. is the same
  primitive-per-subsystem anti-pattern.

## Governance Outcome

The framework should remain generative enough to absorb new backend capability,
but enumerated enough that readers can understand the system without reverse
engineering product surfaces.

## Vocabulary Governance

The policy vocabulary tables in `20-systems/00-operations.md`,
`20-systems/01-resources.md`, and `20-systems/07-extensions-renderers.md`
declare allowed values for each policy axis. The "If a value does not fit
these axes, add a new value to this table before using it" rule depends on
governance discipline:

**Adding a value** requires:

- a use case demonstrating that existing values cannot represent the need
  honestly (not just inconveniently)
- identification of affected `50-decisions/` and `30-agent-workflows/` docs
- the table addition + any recipe updates landing in the same commit as the
  first declaration that uses the new value

**Deprecating a value** requires:

- an explicit retirement marker in `00-orientation/04-retired-concepts.md`
- pointer to source archive evidence if the value originated there
- migration note for any slice / decision doc that referenced the value

**Review authority**: vocabulary additions are reviewed at the same level
as a primitive proposal per the `## Shape Proposal` section above.
Vocabulary deprecations are reviewed by the same reviewer who would close
the corresponding `CONFLICT-LEDGER.md` entry, if one exists.

Without this discipline, the policy tables either calcify (dead reference)
or sprawl (uncontrolled additions that drift between renderers, plugins,
and agent clients). Either failure mode reintroduces the surface-specific
shape divergence that the three primitives were chosen to eliminate.


## Manifest entry-kind governance (slice 449)

The Surface Manifest tier (`registry-surface` namespace) joins the
governance scope. Manifest entries are validated at handshake against
two cross-axis invariants:

1. **Cross-reference resolvability** — every id listed in
   `Surface.consumes.{resources,operations,prompts,diagnosticChannels}`
   must resolve in the live catalog the consumer is reading. The
   `SurfaceAreaValidator` runs the check at registration; failure
   rejects the catalog (registration is all-or-nothing per
   namespace, mirroring the per-primitive validators).

2. **Audience composition rule** — `effective audience = max(declared,
   audienceFloorFromProvenance, audienceFloorFromConsumedChannels)`.
   The validator computes the effective audience and stamps it on the
   entry; consumers read the stamped value. Provenance floors and
   channel-derived floors are documented in `00-primitives.md` §
   "Manifest tier".

Plugin-contributed surfaces (slice 449 phase 12) flow through the
same validator; the trust-tier floor is applied at install time via
`audienceFloorForTier()` in the host's `PluginRegistry`, so the
catalog the host re-reads after install is already floor-elevated.

Mechanical structural-diff governance (slice 3a-1-8f) extends to
Manifest entry kinds: a CI-time diff against the prior catalog snapshot
flags additions / removals / audience-level changes / consumes-set
changes. The diff matters because *audience changes are user-visible*:
elevating a surface from USER to OPERATOR removes it from the rail for
non-admin users — a correctness hazard the structural-diff governance
is designed to surface.
