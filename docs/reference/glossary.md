---
title: Project Glossary
type: reference
status: stable
description: "Authority index for overloaded JustSearch architecture, search, runtime, and interface terms."
---

# Project Glossary

This page is an authority index, not a second specification. Each row gives the
short distinction needed to choose the right term and points to the canonical
document that owns its full meaning.

| Terms | Distinction | Canonical authority |
|---|---|---|
| **Head / Body / Brain** | Head is the Java API and orchestration process; Body is the Lucene-owning Worker; Brain is local model inference. “Worker” is the preferred concrete name for Body in code-facing prose. | [System overview](../explanation/01-system-overview.md) |
| **run / operation / job** | A run is one conversation-shape or workflow execution. An operation is a catalog-declared action dispatched through the shared policy/audit spine. A job is queued background indexing or enrichment work. Do not use the three as synonyms. | [Agent system architecture](../explanation/22-agent-system-architecture.md); [knowledge server](../explanation/03-knowledge-server.md) |
| **passage / chunk / document** | A document is the indexed parent item. A chunk is a stored child segment linked to that parent. A passage is a retrieval/context unit and may be produced from stored chunks or virtual full-document chunking. | [Search pipeline overview](../explanation/23-search-pipeline-overview.md) |
| **leg / lane / stage** | A retrieval leg is one search strategy such as BM25, dense, or SPLADE. A pipeline stage is one named processing step. Lane is context-qualified: benchmark lanes separate measurements, CI evidence lanes separate proof, and agent lanes partition delivery; it is not a search-path synonym. | [Search pipeline overview](../explanation/23-search-pipeline-overview.md); [benchmarking architecture](../explanation/20-benchmarking-architecture.md); [testing strategy](../explanation/09-testing-strategy.md); [agent guide](contributing/agent-guide.md) |
| **surface / window / rail** | A surface is catalog-addressable application content. A window is chrome-level presentation that can host a surface without replacing the current stage. The rail is the shell’s navigation region. | [UI/UX design](../explanation/10-ui-ux-design.md); [frontend presentation kernel](../explanation/27-frontend-presentation-kernel.md) |
| **collection / root** | A root is a watched filesystem boundary. A collection is a logical search-scope tag that documents can inherit from a containing root. They are related but have different identity and filtering semantics. | [API contract map](api-contract-map.md); [agent system architecture](../explanation/22-agent-system-architecture.md) |
| **spec / status** | Use this distinction only for types that declare it: `Spec` is desired operation intent and `Status` is its observed state. Do not generalize the suffixes into a project-wide runtime contract without a named type authority. | [Operation contract authority](../explanation/22-agent-system-architecture.md) |
| **grant / source tier / risk tier / gate behavior** | A grant is the revocable authorization artifact (single-use capsule or durable grant). `SourceTier × RiskTier` resolves to `GateBehavior`, which selects the authorization ceremony; none of those policy values is itself a grant. | [Agent system architecture](../explanation/22-agent-system-architecture.md) |

## Known unresolved naming decisions

Two product-language decisions remain deliberately open: F-22 concerns the
fragmented user-facing **Ask** entry points, and F-25 concerns the scope and
labeling collision between **Simple** and **Advanced/Detailed** modes. This
glossary does not decide either question. Until owners do, follow the current
[UI/UX design](../explanation/10-ui-ux-design.md) and
[Simple versus Advanced mode](ui/simple-vs-advanced-mode.md), and avoid adding
another label for either concept.
