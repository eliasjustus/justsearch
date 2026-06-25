---
title: "Query Understanding Soft-Boost"
type: decision
status: stable
description: "Use soft boosts (SHOULD clauses) for QU-extracted signals, not hard filters."
date: 2026-03-28
---

# ADR-0016: Query Understanding Soft-Boost

## Status
Accepted

## Context

Query Understanding (QU) preprocesses natural language search queries by extracting metadata signals (e.g., author, source, date) and translating them into structured Lucene query clauses. These signals can be applied as:

- **Hard filters (MUST/FILTER clauses):** Documents not matching the filter are excluded entirely.
- **Soft boosts (SHOULD clauses):** Matching documents are ranked higher, but non-matching documents are not excluded.

The choice between hard and soft application has a fundamental tradeoff: hard filters provide precision when the extraction is correct, but produce catastrophic zero-result failures when the extraction is wrong. LLM-based extraction is inherently probabilistic — it may extract the wrong entity, misspell a source name, or hallucinate a filter value that does not exist in the index.

Evaluation on a 50-query dataset with filter-bearing queries (tempdoc 363) measured the impact:
- **Soft boost:** +3.2% nDCG@10 improvement over no QU. Zero zero-result queries.
- **Hard filter:** -20.1% nDCG@10 degradation. 7/50 queries produced zero results due to incorrect or non-matching filter values.

## Decision

Use soft boosts (SHOULD clauses) exclusively for all QU-extracted signals. Never apply QU output as hard filters.

Implementation details:
- **Weight:** Constant boost weight of 20, applied via `ConstantScoreQuery` wrapped in `BoostQuery` to avoid IDF variation across filter terms.
- **Composition:** QU boost clauses are added as SHOULD clauses to the main BooleanQuery alongside content MUST clauses and any caller-provided hard FILTER clauses. The boost raises matching documents in ranking without excluding non-matching documents.
- **Bypass:** QU is bypassed entirely when the caller provides explicit `filters` or `boostFilters` parameters, deferring to the agent's or user's explicit intent.
- **Gating:** QU is gated behind `JUSTSEARCH_QU_ENABLED=true` (disabled by default) due to LLM scheduling contention — QU and query expansion compete for the same single-slot llama-server.
- **Response metadata:** A `QueryUnderstanding` sub-record on `KnowledgeSearchResponse` exposes `appliedBoosts` (map of field to extracted values) and `latencyMs`, enabling agents to observe what the system detected and adjust their strategy.

## Consequences

**Positive:**
- +3.2% nDCG@10 on filter-bearing evaluation set — a meaningful improvement from a single preprocessing step.
- Never produces zero results — SHOULD clauses are additive, so incorrect extractions degrade ranking but do not eliminate results.
- Safe for all query types, including queries where the LLM extracts nothing (no boost clauses added, no degradation).
- Transparent to agents — the `queryUnderstanding` response field surfaces the system's interpretation for outer-agent coordination.

**Negative:**
- Cannot guarantee filter precision — if the LLM extracts the wrong source, the wrong documents get a ranking boost instead of being excluded.
- Correct extractions produce weaker signal than hard filters would (boost vs. exclusion), so precision-sensitive use cases see less benefit.
- The constant weight (20) is a tuned heuristic; optimal weight may vary across query distributions.

## Alternatives Considered

### Hard filters (MUST/FILTER clauses)
Apply QU-extracted signals as hard exclusion filters. Provides maximum precision when the extraction is correct. Rejected due to catastrophic failure mode: 7/50 evaluation queries produced zero results from incorrect filter values, causing -20.1% nDCG@10 degradation. The failure is silent (user sees "no results" with no indication why) and unrecoverable within a single search turn.

### Hybrid hard+soft
Apply high-confidence extractions as hard filters and low-confidence extractions as soft boosts. Requires a reliable confidence calibration mechanism for the LLM's extraction output. Rejected because: (a) confidence calibration for local small models is an unsolved problem, (b) adds complexity without demonstrated benefit over pure soft-boost, and (c) the hard-filter failure mode persists for the high-confidence tier.

### No QU
Skip query understanding entirely. Baseline behavior — agents and users must construct filters manually. Rejected because the +3.2% nDCG improvement is available at minimal risk with soft boosts, and the infrastructure enables future capabilities (temporal reasoning, query reformulation) that benefit from the same preprocessing step.
