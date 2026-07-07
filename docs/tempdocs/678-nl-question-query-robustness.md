---
title: "Natural-language question robustness: the engine loses ~21 points of top-3 hop-1 recall to question boilerplate — verbose NL questions rank worse than their own distinctive descriptor phrase"
type: tempdocs
status: "open — STUB, goals and context only, no design or implementation. Evidence in hand (a measured 69% -> 90% top-3 gap between verbatim-question and descriptor-phrase formulations of the same information need, tempdoc 624 mechanism replay, 2026-07-03). Sequencing: design AFTER the 624 harness fix + real-with-tool re-run lands — that run decides whether lazy verbatim-question usage is the dominant real access pattern (raising this to critical path) or whether callers reformulate well (lowering it). D-005-clean by construction: the lever keys on the query's own shape, never on an assumed corpus."
created: 2026-07-03
updated: 2026-07-03
author: agent retrospective (624 mechanism investigation), filed by agent — STUB
category: search-quality / query-side / retrieval-engine
related:
  - 624-agentic-retrieval-eval-rebuild   # origin — the engine-replay evidence and the access-pattern question the re-run will settle
  - 363-query-understanding-boost-extraction  # dormant query-side machinery to register-check before any new mechanism (QU, F-018)
  - 655-mcp-conformance-and-capability-policy # the consumer boundary: agents/users paste whole questions through the tool surface
principle: "a retrieval engine consumed by agents and end users will receive whole natural-language questions, not curated keyword queries — robustness to the query's own boilerplate is engine capability, not caller responsibility."
---

> Noncanonical working tempdoc. STUB: goals and context only.

# 678 — Natural-language question robustness

## Goal

Close (or substantially narrow) the measured gap between how well the engine retrieves for a verbose
natural-language question and how well it retrieves for that question's own distinctive descriptor
content — so that the laziest realistic usage (pasting the whole question) performs close to the best
reformulated usage, without requiring the caller to be a skilled query formulator.

## Context — the measurement (tempdoc 624 engine replay, 2026-07-03, clean per-corpus indexes)

On the two certified battlefield corpora (26 queries each, deliberately hard paraphrase band,
`hybrid` mode, top-10), hop-1 document recall by query formulation:

| Formulation | top-3 (pooled n=52) | top-10 |
|---|---|---|
| verbatim benchmark question | 69% | 79% |
| the question's descriptor phrase alone | 90% | 96% |
| best-of-3 formulations | 94% | 98% |

The engine is near-ceiling when given the distinctive content and measurably degraded when that same
content arrives wrapped in question boilerplate ("What is the value associated with the founder of
the designer of…"). Concrete instance: one query's verbatim form missed top-10 entirely while its
descriptor phrase ranked the gold document #1. The effect is language-invariant (EN and DE verbatim
both 69%). Why it matters: the realistic consumers of this engine — MCP agents and end users — paste
questions; every point recovered here accrues to real-world results without any caller cooperation,
and directly moves the agent-utility measurement's most realistic usage mode.

## Relevant register facts for the design pass (check before building anything new)

Query-side machinery already exists in various states of dormancy and must be register-checked first:
query understanding / boost extraction (363, F-018 — shipped, default-off), LLM query expansion
(TEXT-preset only by design), QPP signals (computed, unused for routing — F-019 constrains what they
can do), and the fusion/weighting layer's existing per-leg configurability. The design pass should
also characterize WHERE the boilerplate hurts (lexical leg dilution vs dense-embedding dilution vs
fusion) before choosing a mechanism — the replay data supports constructing that decomposition
cheaply. Per D-005, any solution must be a fixed, regime-blind behavior keyed on the query itself.

## Explicit non-goals

Not benchmark tuning (the fix must be justified on general verbose-question handling, with the
battlefield replay as *one* measurement among the register's corpora); not agent-side prompt
engineering (that is 655's layer); not a per-corpus router (D-005).
