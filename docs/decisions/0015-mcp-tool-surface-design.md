---
title: "MCP Tool Surface Design"
type: decision
status: stable
description: "Consolidate MCP tools from 7 capability-oriented to 4 task-oriented for higher agent accuracy."
date: 2026-04-01
---

# ADR-0015: MCP Tool Surface Design

## Status
Accepted

## Context

JustSearch exposes its capabilities to AI agents via MCP (Model Context Protocol) tools. The original design had 7 capability-oriented tools that mapped directly to API endpoints: `search`, `preview`, `retrieve_context`, `match_citations`, `suggest`, `ingest`, and `status`. Agents (particularly small models like Haiku) struggled to compose these tools effectively — 41/50 agents in evaluation used a slow search-then-preview loop (70% accuracy) while only 4/50 discovered `retrieve_context` (100% accuracy, fewer turns, lower cost).

Industry evidence strongly favors fewer, task-oriented tools:
- Block Engineering reduced 30+ tools to 2 and saw significant accuracy improvements.
- AWS Prescriptive Guidance recommends task-oriented over capability-oriented tool design.
- "MCP Tool Descriptions Are Smelly" (arXiv 2602.14878) formalizes why fewer tools outperform many: reduced decision complexity, less schema confusion, and better description-to-behavior alignment.

Additional research on schema complexity (arXiv 2504.19277) showed that adding optional JSON schema parameters without proportional description investment causes 16.1% average degradation in small models. Position bias research (ToolTweak, arXiv 2510.02554) showed agents prefer the first-listed tool by 9.51%.

## Decision

Consolidate to 4 task-oriented MCP tools:

| Tool | Purpose | Maps to previous |
|------|---------|------------------|
| `justsearch_answer` | RAG retrieval + context for answering questions | `retrieve_context` + `match_citations` |
| `justsearch_search` | Search with facets, filters, and exploration | `search` + facets |
| `justsearch_ingest` | File ingestion into the knowledge index | `ingest` (unchanged) |
| `justsearch_status` | System health and enrichment coverage | `status` (unchanged) |

Removed tools: `preview` (agents have file access via their own tools), `suggest` (2/50 usage in eval, dead for QA), `match_citations` (0/50 standalone usage, absorbed into `answer` as `verify_citations` param).

Design principles:

1. **Schema-minimal:** Implement features in the backend, document behavior in description text, keep JSON schema parameters minimal. Only add schema parameters when eval proves the target model actually uses them.
2. **Position-bias exploitation:** Register `justsearch_answer` first because it produces the best results for QA tasks and agents naturally prefer the first-listed tool.
3. **Progressive disclosure:** Use response-level hints (zero results, high hit count, filter tips) rather than front-loading guidance in tool descriptions. Hints are contextual and appear only when relevant.

## Consequences

**Positive:**
- +20pp accuracy on 50-query Haiku eval (72% to 92%).
- Reduced schema complexity — agents spend fewer turns figuring out which tools to compose.
- Progressive disclosure via response hints keeps descriptions concise while still guiding agent behavior.
- Answer-first agents achieve 84% accuracy at $0.023 avg cost (vs 94% at $0.069 for search-explore agents — 3x cheaper for similar quality).

**Negative:**
- Less granular tool control for advanced agents — search and retrieval are separate tools but there is no standalone preview or citation-matching tool.
- Answer tool consolidates search+retrieval; agents wanting search-only exploration must use `justsearch_search` explicitly.
- Tool ordering creates a mild dependency on position bias research remaining valid for future models.

## Alternatives Considered

### Keep 7 capability-oriented tools
Direct API-to-tool mapping. Gives agents maximum flexibility but eval showed 70% accuracy with the dominant usage pattern. Agents spent turns composing tools instead of solving problems. Rejected due to lower accuracy and higher cost.

### Add optional schema parameters for advanced features
Expose filter syntax, facet requests, excerpt options as optional JSON schema params. Research (arXiv 2504.19277) showed 16.1% average degradation in small models when optional params are added without proportional description investment. Rejected — features are implemented in the backend and documented in description text instead.

### Use OpenAPI instead of MCP
OpenAPI is more established but MCP is the emerging standard for AI agent tool integration, supported by Claude, Cursor, and other agent frameworks. Rejected — MCP aligns with the target ecosystem.
