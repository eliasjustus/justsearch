---
title: "Backend-owned truth — the frontend renders, never owns"
type: decision
status: accepted
description: "Operations, Resources, and Prompts (the truth) are singular and backend-owned; the frontend is a renderer/composition layer over them."
date: 2026-06-09
---


# ADR-0034: Backend-owned truth — the frontend renders, never owns

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. The truth/presentation cut this draws is the foundation of the plugin boundary (ADR-0035) and tempdoc 569.

## Decision

Backend-owned runtime truth must be declared through resources, operations,
prompts, catalogs, or explicit APIs. Product surfaces must not maintain private
readiness or capability state machines for backend concepts.

## Rationale

The old frontend failure class was split-brain truth: different panels disagreed
about readiness, install state, AI availability, and health. The framework must
make that class of failure structurally difficult.

## Rejects

- Frontend-private readiness state machines for backend-owned concepts.
- Deriving operation availability from scattered product surface checks.
- Reporting installed/configured/active/ready as one ambiguous state.

## Future Agents Must Not

- Let product surfaces enable actions from stale or inferred backend state.
- Hide unknown or stale resource values to make UI look clean.
- Duplicate backend error or label catalogs in frontend code/docs.

## Revisit When

- A concept is only observable from the shell, such as local connection failure.
- Backend cannot own the truth without violating process boundaries.
- The exception is recorded as `shell-observed` or equivalent resource policy.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/01-runtime-contracts.md`
- `20-systems/01-resources.md`
- `20-systems/06-error-recovery-diagnostics.md`
- `40-reference-workloads/04-health-diagnostics-workload.md`

## Source Evidence

- `archive/source-tempdocs/428-frontend-pre-deletion-deep-audit.md`
- `archive/source-tempdocs/430-slice-1-1-a-health-event-catalog.md`
- `archive/source-tempdocs/421-ux-systems.md`
