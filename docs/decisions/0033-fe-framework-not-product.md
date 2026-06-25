---
title: "Frontend as a framework, not a product"
type: decision
status: accepted
description: "The frontend is a framework whose surfaces are projected from declarations, so users/plugins can author presentation without forking the shell."
date: 2026-06-09
---


# ADR-0033: Frontend as a framework, not a product

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. The projection-kernel direction this names is carried forward by tempdocs 559 / 567 / 569.

## Decision

This folder defines the frontend framework/kernel, not the final JustSearch
product UX.

## Rationale

Binding framework docs to the current product layout would preserve old UI
assumptions and make later product redesign harder. Current product behavior is
used as workload pressure only.

## Rejects

- Treating current React UI structure as the destination product design.
- Encoding final navigation, layout, or keyboard shortcuts in framework docs.
- Using reference workloads as hidden product requirements.

## Future Agents Must Not

- Add product UX commitments to `../reference/ui/frontend-kernel/kernel/` or `20-systems/`.
- Present workload examples as final UI decisions.
- Resolve product/design conflicts inside this folder unless explicitly asked.

## Revisit When

- A separate product-design packet is created and intentionally depends on this
  framework.
- A framework capability cannot be evaluated without choosing a product
  interaction model; record the conflict first.

## Affected Docs

- `00-orientation/03-framework-vs-product.md`
- `40-reference-workloads/`
- `CONFLICT-LEDGER.md`

## Source Evidence

- `archive/source-tempdocs/421-reasoning.md`
- `archive/source-tempdocs/421-surfaces.md`
