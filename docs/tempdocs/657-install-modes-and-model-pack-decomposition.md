---
title: "Install modes and model-pack decomposition: separate Full Desktop, Headless Runtime, and MCP Lite so first-run weight is explicit and optional"
type: tempdocs
status: open — re-scoped LAUNCH-BLOCKER 2026-07-01 (§Re-scope note)
created: 2026-06-28
updated: 2026-07-01
category: packaging / installer / model-distribution / developer-experience
related:
  - 654-local-runtime-contract-and-product-center
  - 656-five-minute-agent-runtime-onramp
  - 631-go-public-publish-machinery
  - 634-go-public-cutover-transition
  - docs/decisions/0024-app-packaging-nsis-per-user-download.md
  - docs/reference/model-inventory.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 657 - Install modes and model-pack decomposition

## Purpose

JustSearch's full local capability has a real runtime cost. The reports consistently argue that this
cost is not fatal, but it needs clearer product shapes: Full Desktop for the complete private assistant
experience, Headless Runtime for local service use, and MCP Lite for agent developers who need fast
retrieval before they commit to the full stack.

This tempdoc asks a next agent to design install/runtime modes and model-pack decomposition without
weakening the local-first promise. The goal is to make first-run weight explicit, optional where
possible, and aligned with actual capability tiers.

## Boundary

This is not a request to rip out models or weaken the retrieval stack. It is a packaging and product
mode design. The agent should map existing model/runtime dependencies, decide which capabilities are
required for each mode, and identify where the current installer or setup flow makes optional weight
look mandatory.

Coordinate with installer ownership before implementation. Packaging changes touch Windows installer,
model distribution, runtime config, and public docs.

## Prior owners to read first

- ADR-0024 for current installer and download-on-demand architecture.
- `docs/reference/model-inventory.md` for model purposes, sizes, and licensing.
- `go-public-publish-machinery` and `go-public-cutover-transition` for release/public-repo constraints.
- `local-runtime-contract-and-product-center` for whether Headless/MCP Lite are official product
  shapes.
- `five-minute-agent-runtime-onramp` for activation requirements.

## First questions

- Which capabilities define Full Desktop, Headless Runtime, and MCP Lite?
- Which models are required, optional, deferrable, or replaceable per mode?
- How should setup explain the first-run download without hiding its size?
- Can a no-model or small-model mode still provide enough value for developer evaluation?
- What installer/runtime metadata must expose the active mode so callers do not guess?


---

# Re-scope note (2026-07-01)

Elevated to **launch-blocker** alongside 656: install-mode / model-pack decomposition is what makes
the five-minute path physically possible.

One decision this design must NOT make unilaterally: **which capability tier the lightest mode
ships** (search-only vs. search + small model) — that choice shapes the public first-touch claim
and is reserved for founder/strategy input. Design the decomposition so the tier boundary is a
configuration choice, then surface the options with their trade-offs.
