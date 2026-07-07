---
title: "Server-side chain-following retrieval: agents spend ~20 turns walking entity chains that the engine could resolve internally — hop-1 is the only semantically hard step, and the engine already wins it"
type: tempdocs
status: "open — STUB, goals and context only, no design or implementation. HARD TRIGGER (do not begin design before it fires): the 624 real-with-tool re-run's per-cell traces must first show whether tool-equipped agents still burn multi-turn budgets chain-walking after a successful hop-1 retrieval. If they chain efficiently themselves (search + 2 targeted lookups), this capability's value collapses and the doc should be closed with that finding rather than built. Filed now because the supporting evidence (turn anatomy, hop cost structure, engine hop-1 strength) is fresh and precise."
created: 2026-07-03
updated: 2026-07-03
author: agent retrospective (624 mechanism investigation), filed by agent — STUB
category: search-quality / rag / retrieval-orchestration / agent-eval
related:
  - 624-agentic-retrieval-eval-rebuild   # origin — the trace evidence and the re-run whose results are this doc's trigger
  - 655-mcp-conformance-and-capability-policy  # owns the tool SURFACE any such capability would be exposed through; this doc owns the retrieval orchestration behind it
  - 366-agent-search-interface           # dated prior art: answer-first vs search-explore cost asymmetry (~3x) on the same backend
principle: "when a multi-step information need decomposes into one semantically hard step plus mechanically easy follow-ups, making the caller orchestrate the easy steps multiplies cost without adding judgment — the funnel-and-judge stance applied to multi-hop: put the loop where the competence is."
---

> Noncanonical working tempdoc. STUB: goals and context only.

# 679 — Server-side chain-following retrieval

## Goal

Let a single retrieval call resolve entity-chain information needs (find the entry-point document,
follow its referenced entities through successive lookups, return the chain with provenance) —
converting what is currently a many-turn, caller-orchestrated trajectory into one engine-side
operation, if (and only if) the trigger evidence shows callers actually pay that trajectory cost.

## Context — the measured cost anatomy (tempdoc 624 mechanism investigation, 2026-07-03)

On the certified battlefield runs, every question decomposes into: one semantically hard step
(hop-1: bridging the question's paraphrase to the entry-point document) plus 2 mechanically easy
follow-ups (exact entity-token lookups; on these corpora even a filename lookup suffices). Measured
agent cost for the whole trajectory: median ~22 turns, ~46k unique tokens, ~$0.22 per question —
with the winning strategy spending a multi-grep seeding burst on hop-1 and a grep/read cycle per
subsequent hop. The engine, replayed on the same corpora, wins hop-1 in top-3 for 90-96% of queries
given a reasonable formulation (69% even verbatim-lazy) — and the follow-ups are exact-token
searches the lexical leg handles trivially. Reasoning-given-the-docs never fails (oracle ceiling
95-99%). So essentially the entire per-question cost above the price of one search is caller-side
orchestration of mechanically easy steps — the natural origin of any honest token/cost claim for
retrieval, and the gap 366's dated answer-first measurements (~3x cost asymmetry on the same
backend) pointed at from the other direction.

## What the trigger must establish before design begins

From the real-with-tool re-run's per-cell traces: (a) do tool-equipped agents perform hop-1 via the
tool and then chain-walk via further tool calls or file reads, and at what turn/token cost; (b) how
often do they mis-chain (grab a sibling entity) in ways an engine-side chain-follow with provenance
would prevent; (c) what fraction of their spend is chain-walking vs hop-1 vs synthesis. If (a)'s
cost is small and (b) is rare, close this doc with that finding.

## Relevant existing machinery for the eventual design pass (register-check first, conform don't fork)

The RAG/answer path (`RagContextOps`, chunk-first retrieval + context assembly), the existing
`justsearch_answer` MCP tool, the agentic-citation substrate (retrieved ⊃ grounding ⊃ cited
provenance chain, F-021's harvest-not-build note), and D-005's funnel-and-judge stance — a
chain-follow is an *engine-side loop over retrieval calls with provenance*, not a new intelligence
layer, and every hop it takes must remain auditable (recall-survival discipline applies to the
chain steps like any other funnel stage).

## Explicit non-goals

Not a general agent framework inside the engine; not a replacement for the tool surface (655 owns
what is exposed and how); not speculative graph infrastructure — the capability is scoped to
following explicit entity references surfaced by retrieval, with each hop recorded.
