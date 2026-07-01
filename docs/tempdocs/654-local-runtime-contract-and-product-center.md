---
title: "Local runtime contract and product center: decide whether JustSearch's public center is the desktop app or a reusable local knowledge runtime with the desktop as reference client"
type: tempdocs
status: open
created: 2026-06-28
updated: 2026-06-28
category: product-architecture / runtime-contract / public-positioning
related:
  - 650-go-public-capability-descriptor-truthfulness
  - 501-runtime-manifest-design
  - 500-mcp-protocol-surface
  - docs/explanation/23-runtime-manifest.md
  - docs/reference/api-contract-map.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 654 - Local runtime contract and product center

## Purpose

JustSearch's public story currently has three plausible centers: a Windows desktop search app, a
private MCP retrieval backend for agents, and a broader local knowledge runtime. The recent external
research reports converge on the same pressure: developer adoption becomes easier if external tools
can target one stable object, not a bundle of strong but scattered subsystems.

This tempdoc asks a next agent to design that product boundary. The likely direction is not to demote
the desktop app, but to make the desktop shell the reference client for a versioned local runtime
contract: health/status, runtime manifest, supported API subset, MCP endpoint, compatibility matrix,
and semver/change rules.

## Boundary

This is not an implementation ticket for new endpoints. It is a design pass over product identity and
contract ownership. It should decide what "JustSearch Runtime" means, which existing surfaces belong
to that contract, and which surfaces remain private implementation detail.

Do not duplicate `go-public-capability-descriptor-truthfulness`; that tempdoc owns public descriptor
truthfulness. This note owns the missing product-contract shape behind that descriptor.

## Prior owners to read first

- `go-public-capability-descriptor-truthfulness` for the public "what JustSearch is" drift class.
- `runtime-manifest-design` and `docs/explanation/23-runtime-manifest.md` for the existing runtime
  discovery surface.
- `docs/reference/api-contract-map.md` for current API ownership.
- `mcp-protocol-surface` for the external agent ingress shape.

## First questions

- What is the smallest stable local runtime contract an external tool can rely on?
- Does the runtime contract need its own version, changelog, and compatibility matrix?
- Which endpoints are public contract, which are reference-client-only, and which are internal?
- How should the README and canonical docs describe the desktop app relative to the runtime?
- What would make Headless or MCP Lite a product shape rather than a loose launch mode?

