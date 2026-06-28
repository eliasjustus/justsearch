---
title: Architecture Decision Records
type: decision
status: stable
description: "Index of architectural decisions with MADR-lite template."
---

# Architecture Decision Records

This directory captures significant architectural decisions using a lightweight [MADR](https://adr.github.io/madr/) template. Each record documents the context, decision, consequences, and alternatives considered.

ADRs complement the explanation docs — they capture *why not* and *what else was considered*, which explanation docs typically omit.

## Conventions

- **Numbering:** `NNNN-short-title.md` (zero-padded, sequential)
- **Append-only:** Don't modify Context, Decision, or Consequences after acceptance. Superseded decisions get a status change and a link to the replacement ADR; the original reasoning stays intact.
- **Cross-references:** ADRs link to the explanation doc that covers the topic in depth.

## Template

```markdown
---
title: "Decision Title"
type: decision
status: stable
description: "One-line summary."
date: YYYY-MM-DD
---

# ADR-NNNN: Decision Title

## Status
Accepted | Superseded by ADR-XXXX | Deprecated

## Context
[Problem statement and forces at play]

## Decision
[What was decided and why]

## Consequences
[Positive and negative outcomes]

## Alternatives Considered
### Alternative A
[Description, pros, cons, why rejected]
```

## ADR Lifecycle

ADRs should be reviewed when any of these triggers occur:

- A technology described in the ADR is replaced (mark `status: Superseded`)
- A module referenced in the ADR is deleted from the codebase
- A follow-up ADR contradicts or narrows a prior decision

Superseded ADRs are retained for historical context but must include a note directing readers to the current approach.

## Decision Log

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [0001](0001-three-process-architecture.md) | Use three separate OS processes | Accepted | 2026-02-03 |
| [0002](0002-grpc-mmf-hybrid-ipc.md) | Use gRPC + MMF hybrid for IPC | Accepted | 2026-02-03 |
| [0003](0003-direct-lucene-no-elasticsearch.md) | Use Lucene directly without search platform | Accepted | 2026-02-03 |
| [0004](0004-single-tenant-gpu-policy.md) | Single-tenant GPU policy | Accepted | 2026-02-03 |
| [0005](0005-manual-ffm-bindings.md) | Manual FFM bindings for llama.cpp | Accepted | 2026-02-03 |
| [0006](0006-two-pronged-citation-strategy.md) | Two-pronged citation strategy | Accepted | 2026-02-07 |
| [0007](0007-entity-faceting-over-knowledge-graph.md) | Entity faceting over full knowledge graph | Accepted | 2026-01-22 |
| [0008](0008-settings-ephemeral-defaults-safe.md) | Settings are ephemeral, defaults are safe | Accepted | 2026-02-10 |
| [0009](0009-custom-dag-engine-ci-orchestration.md) | Custom DAG engine for CI orchestration | Accepted | 2026-02-23 |
| [0010](0010-local-first-workflow-quality-observability.md) | Local-first workflow quality observability | Accepted | 2026-03-07 |
| [0011](0011-distributed-readiness-spi.md) | Distributed Readiness — Remote Shard SPI | Accepted | 2026-03-16 |
| [0012](0012-ui-stack-and-doc-tooling.md) | UI Stack and Documentation Tooling | Superseded | 2026-03-16 |
| [0013](0013-synonyms-fst-placeholder.md) | Synonyms FST Placeholder | Accepted | 2025-10-15 |
| [0014](0014-pipeline-definition-removal.md) | Pipeline Definition Removal | Accepted | 2026-03-16 |
| [0015](0015-mcp-tool-surface-design.md) | MCP tool surface design | Accepted | 2026-04-01 |
| [0016](0016-query-understanding-soft-boost.md) | Query understanding soft-boost over hard-filter | Accepted | 2026-03-28 |
| [0017](0017-ai-bridge-module-decomposition.md) | ai-bridge module decomposition | Accepted | 2026-04-06 |
| [0018](0018-vlm-pdf-extraction-via-chat-model.md) | VLM PDF extraction via chat model | Accepted | 2026-03-23 |
| [0019](0019-cpu-gpu-model-selection-strategy.md) | CPU vs GPU model selection strategy | Accepted | 2026-04-06 |
| [0020](0020-structured-metadata-filterable-facets.md) | Structured metadata fields as filterable facets | Accepted | 2026-03-27 |
| [0021](0021-build-stamp-content-hash.md) | Build-stamp content-hash design | Accepted | 2026-04-06 |
| [0022](0022-recordbuilder-annotation-processor.md) | RecordBuilder annotation processor for API records | Accepted | 2026-04-07 |
| [0023](0023-api-responses-declare-runtime-context.md) | API responses declare their runtime context | Accepted | 2026-03-30 |
| [0024](0024-app-packaging-nsis-per-user-download.md) | App packaging: NSIS, per-user install, download-on-demand | Accepted | 2026-04-06 |
| [0025](0025-core-dto-dual-type-layering.md) | Core DTO dual-type layering (gRPC vs REST) | Accepted | 2026-04-06 |
| [0026](0026-manual-ci-triggering.md) | Manual-Only CI Triggering | Accepted (narrowed by ADR-0044) | 2026-04-22 |
| [0027](0027-metric-catalog-as-telemetry-contract.md) | MetricCatalog as the Telemetry Contract | Accepted | 2026-04-25 |
| [0028](0028-scoped-reverse-path-lookup.md) | Scoped Reverse Path-Hash Lookup | Accepted | 2026-04-26 |
| [0029](0029-telemetry-events-bridge-vs-direct-emit.md) | TelemetryEvents Bridge vs Direct-Emit Façade | Accepted | 2026-04-27 |
| [0030](0030-policy-on-operations-vs-mcp-hints.md) | Policy on Operations vs MCP-style hints | Accepted | 2026-04-30 |
| [0031](0031-fe-three-primitives.md) | Frontend three primitives — Operation, Resource, Prompt | Accepted | 2026-06-09 |
| [0032](0032-fe-lit-web-components.md) | Frontend rendering — Lit web components | Accepted | 2026-06-09 |
| [0033](0033-fe-framework-not-product.md) | Frontend as a framework, not a product | Accepted | 2026-06-09 |
| [0034](0034-fe-backend-owned-truth.md) | Backend-owned truth — frontend renders, never owns | Accepted | 2026-06-09 |
| [0035](0035-fe-plugin-boundary.md) | Plugin boundary — truth vs presentation | Accepted | 2026-06-09 |
| [0036](0036-fe-resource-category.md) | Resource Category axis | Accepted | 2026-06-09 |
| [0037](0037-universal-sse-envelope.md) | Universal SSE envelope | Accepted | 2026-06-09 |
| [0038](0038-wire-contract-source-of-truth.md) | Wire contract as a first-class artifact | Accepted (mechanism superseded by tempdoc 564) | 2026-06-09 |
| [0039](0039-contract-substrate.md) | Contract substrate — every published contract is first-class | Accepted (format superseded by tempdoc 564) | 2026-06-09 |
| [0040](0040-wire-contract-format.md) | Wire contract format — protobuf + protovalidate | Superseded by tempdoc 564 | 2026-06-09 |
| [0041](0041-catalog-category-format.md) | Catalog Category format — protobuf enums + metadata | Accepted (format superseded in part by tempdoc 564) | 2026-06-09 |
| [0042](0042-runtime-witness-consumer-presence.md) | Live-registry witness — consumer-presence over the live ContributionRegistry | Accepted | 2026-06-11 |
| [0043](0043-multilingual-by-construction-no-per-language-levers.md) | Multilingual by construction — no per-language levers | Accepted | 2026-06-15 |
| [0044](0044-public-hosted-ci-fact-lanes.md) | Public hosted CI fact lanes | Accepted | 2026-06-27 |

> ADRs 0031–0041 were graduated on 2026-06-09 from the retired `421` frontend-rewrite kernel
> draft's `50-decisions/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). The
> wire-contract decisions (0038–0041) are retained for historical context but their protobuf-format
> mechanism was superseded by tempdoc 564 (record-as-IDL).
