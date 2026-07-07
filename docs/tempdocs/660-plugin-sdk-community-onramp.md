---
title: "Plugin SDK community onramp: convert the plugin substrate into a real external contribution path after runtime and MCP contracts stabilize"
type: tempdocs
status: open
created: 2026-06-28
updated: 2026-06-28
category: plugins / sdk / contributor-experience / extension-substrate
related:
  - 521-plugin-ecosystem-substrate
  - 527-substrate-consumer-audit
  - 532-virtual-operation-catalog-ship-or-retract
  - 533-first-plugin-scaffold
  - 654-local-runtime-contract-and-product-center
  - 655-mcp-conformance-and-capability-policy
  - packages/plugin-api-ts/README.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 660 - Plugin SDK community onramp

## Purpose

The plugin substrate is architecturally serious, but the reports identify a community gap: a substrate
does not become an ecosystem until external contributors can build, test, package, and understand one
useful plugin without absorbing the whole codebase.

This tempdoc asks a next agent to design the external plugin onramp: published SDK readiness,
examples, simulator/dev loop, contract tests, first-plugin choice, trust-tier explanation, and how
plugin capabilities interact with runtime/MCP contracts.

## Boundary

This should happen after, or at least be designed against, the runtime and MCP contract work. Otherwise
plugins will multiply ambiguity about which surfaces are stable, which host APIs are public, and which
agent-visible actions are safe.

Do not reopen the entire plugin substrate design. `plugin-ecosystem-substrate` owns that. This note
should ask what remains to turn the existing substrate into a contributor-facing path.

## Prior owners to read first

- `plugin-ecosystem-substrate` for the substrate design.
- `substrate-consumer-audit` for hollows and production-consumption discipline.
- `virtual-operation-catalog-ship-or-retract` for agent-visible plugin verbs.
- `first-plugin-scaffold` for the forcing-function plugin question.
- `packages/plugin-api-ts/README.md` for current SDK publication state.
- `local-runtime-contract-and-product-center` and `mcp-conformance-and-capability-policy` once available.

## First questions

- What is the smallest first plugin that proves the SDK, trust model, and dev loop?
- Which Host API surfaces are stable enough to publish, and which should stay experimental?
- What contract tests should every plugin package run?
- What simulator or local harness lets plugin authors iterate without the full app lifecycle?
- How should plugin-contributed actions relate to MCP and operation policy?

