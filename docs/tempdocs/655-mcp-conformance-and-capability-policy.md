---
title: "MCP conformance and capability policy: turn the production MCP endpoint from a useful integration into a certified, policy-governed external contract"
type: tempdocs
status: open
created: 2026-06-28
updated: 2026-06-28
category: mcp / agent-safety / contract-testing / capability-policy
related:
  - 500-mcp-protocol-surface
  - 401-mcp-alternatives-considerations
  - 0015-mcp-tool-surface-design
  - 0030-policy-on-operations-vs-mcp-hints
  - docs/reference/mcp-production-server.md
  - docs/reference/security/threat-model.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 655 - MCP conformance and capability policy

## Purpose

JustSearch already ships a production MCP endpoint and a deliberately curated tool surface. The next
strategic question is whether that surface is merely documented, or whether it is certified as an
external contract that agent clients can trust.

This tempdoc asks a next agent to design the conformance and policy layer around MCP: protocol
capability negotiation, tools/resources/prompts behavior, schema validation, structured outputs,
client fixture coverage, session/token behavior, and capability policy for tools that mutate local
state.

## Boundary

Do not start by adding `justsearch_delete`, `justsearch_reindex`, or more lifecycle tools. The reports
make those tools look attractive, but destructive or broad lifecycle tools need capability/default-deny
semantics first. This tempdoc should define the safety and conformance frame that makes future tool
expansion coherent.

Do not re-litigate whether the tool surface should be curated; `mcp-protocol-surface` and
`mcp-tool-surface-design` already established that principle. This note owns the next layer: certify
and govern the curated surface.

## Prior owners to read first

- `mcp-protocol-surface` for the shipped protocol and resources/prompts frontier.
- `mcp-alternatives-considerations` for previous MCP design ideas and tool-shape tradeoffs.
- ADR-0015 for the fewer-task-oriented-tools decision.
- ADR-0030 for JustSearch's policy-vs-hints divergence and trust-tier reasoning.
- `docs/reference/security/threat-model.md` for current localhost and MCP threat assumptions.

## First questions

- What conformance tests should define "supported MCP client behavior" for JustSearch?
- Which MCP clients should have first-class fixtures and documented configs?
- What schema/output guarantees should tools provide to external agents?
- Which tools are read-only, which mutate local state, and how is that reflected in policy?
- Should future lifecycle tools be separate MCP tools, REST-only helpers, or gated operations surfaced
  through MCP only under explicit grant?

