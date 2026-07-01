---
title: "Retrieval inspectability and diagnostic bundle: make advanced search behavior explainable enough for users, agents, and contributors to trust"
type: tempdocs
status: open
created: 2026-06-28
updated: 2026-06-28
category: observability / retrieval / diagnostics / contributor-experience
related:
  - 623-reproducible-benchmark-release
  - 624-agentic-retrieval-eval-rebuild
  - 640-engine-performance-budget-latency-throughput-footprint
  - docs/explanation/08-observability.md
  - docs/explanation/23-search-pipeline-overview.md
  - docs/reference/search-quality-register.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 658 - Retrieval inspectability and diagnostic bundle

## Purpose

JustSearch's retrieval stack is a differentiator only if outsiders can understand and debug what it
did. The reports converge on this point: BM25, dense vectors, SPLADE, reranking, OCR, citations, and
degraded readiness are impressive, but opaque sophistication does not build trust by itself.

This tempdoc asks a next agent to design a retrieval inspector and diagnostic bundle: score/fusion-leg
breakdowns where available, skipped-file and parser/OCR status, query-time reason codes, citation
provenance, enrichment readiness, and a bug-report package that contributors can attach without
leaking sensitive content by default.

## Boundary

This is not a new benchmark harness. `reproducible-benchmark-release` and
`agentic-retrieval-eval-rebuild` already own publishable measurement records. This note owns
operational inspectability for one local run: why this query, this result, this citation, or this
degraded state happened.

Avoid inventing a second authority for search traces or pipeline state. The design should project
from existing trace/status/reason-code surfaces where possible and name any missing source-of-truth
explicitly.

## Prior owners to read first

- `docs/explanation/23-search-pipeline-overview.md` for retrieval pipeline stages.
- `docs/explanation/08-observability.md` for telemetry and trace surfaces.
- `docs/reference/search-quality-register.md` for quality ownership and registers.
- `reproducible-benchmark-release` and `agentic-retrieval-eval-rebuild` to avoid forking eval work.
- `engine-performance-budget-latency-throughput-footprint` for performance attribution context.

## First questions

- What can be explained today from existing SearchTrace/status/reason-code data?
- Which missing fields are needed for useful inspection without creating representation drift?
- What should be visible in UI, MCP responses, REST diagnostics, and bug-report bundles?
- How should sensitive local document content be redacted from diagnostic exports?
- What is the smallest inspector that makes retrieval behavior trustworthy without becoming a
  dashboard project?

