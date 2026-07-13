---
title: "Agent tool-adoption legibility: diagnose why agents offered the JustSearch MCP surface rarely invoke it, and raise correct adoption as far as the product surface allows"
type: tempdocs
status: "open — pickup stub; no takeover, design, or implementation yet"
created: 2026-07-14
author: agent (Fable, session 9d3c1869) — filed at founder direction after the 719 publish, which surfaced the adoption gap as the program's most under-weighted finding
category: product / mcp-surface / agent-adoption / eval-supporting
related:
  - 624-agentic-retrieval-eval-rebuild            # owns the measurement harness + interpretation tree; its pre-registered "adoption pilot" is the measurement step this tempdoc's levers feed
  - 719-reproducible-public-agent-utility-benchmark # publication boundary; surfaced the 8.3% finding; owns no product lever
  - 707-pillar1-inband-utility-corpus              # corpus substrate for any adoption measurement at scale
---

# 725 — Agent tool-adoption legibility

## Purpose

Diagnose why agents that are *offered* the JustSearch MCP surface rarely *invoke* it, and then
improve correct adoption **as far as possible** through the product surface itself — tool names,
descriptions, parameter schemas, result shapes/legibility, and when-to-use guidance. "As far as
possible" is bounded by honesty, not by a pre-set floor: the ceiling is an agent that adopts the
tool **whenever it would genuinely help and not otherwise** — indiscriminate invocation
manufactured by an over-eager description is a regression, not a win. The maximization target is
therefore *correct* adoption, paired with a non-degrading outcome sanity check.

This tempdoc owns the **product levers and the diagnosis**. It does not own measurement (624's
harness and interpretation tree are the instrument), makes **no utility claim**, and publishes
nothing (719's boundary). Instrument and subject stay in separate tempdocs deliberately — the tool
surface is the thing being changed; 624 measures the change.

## Why now (the evidence)

- **B-arm adoption was 8.3% in the 2026-07-12 pilot**: exactly 1 of 12 paired B-cells (file tools
  + JustSearch offered) made any MCP call, yet the (rejected, non-comparable) record showed a
  favorable token delta — meaning 11 of 12 cells exhibited a token difference without using
  JustSearch at all. 624's pre-registered tree already classifies this correctly: *a result with
  low adoption is an adoption finding, not a utility finding*. Evidence:
  `scripts/jseval/tests/fixtures/agent-utility-rejected-2026-07-12/observations.v1.jsonl`
  (per-cell `mcp_tools_offered` + `tool_call_names`; committed, sanitized).
- **Consequence for the program**: a powered paid campaign run today would most likely return an
  expensive adoption-only verdict. The claim policy's `minimum_adoption_rate` is deliberately
  unresolved (`scripts/jseval/utility-claim-policy.v1.json`); no owner will set a floor the product
  cannot clear. Adoption work therefore *precedes* campaign spend in any sane ordering.
- **The why is likely readable for $0**: the raw pilot log (untracked, main checkout,
  `scripts/jseval/624-pilot-2026-07-12/logs/…agent-utility-task_7EXqthzWBGRHZeRtxMMXcX.json`)
  contains the agents' actual completions and tool-call sequences for all 48 attempted cells — the
  non-adopting B-cells' transcripts may state or reveal why the agent chose grep/read over the
  offered tool. **This file is fragile evidence** (untracked, machine-local): if it disappears, the
  committed sanitized fixture retains tool-call *names* but not completions.

## What this owns

1. **Diagnosis first ($0).** Read the 11 non-adopting B-cell trajectories from the raw pilot log:
   did the agent never consider the tool, consider and reject it, misunderstand its parameters, or
   fail to connect the task to the tool's description? Catalogue the current offered surface as the
   agent actually sees it (the exact `tools/list` payload: five tools — `justsearch_answer`,
   `justsearch_search`, `justsearch_browse`, `justsearch_ingest`, `justsearch_status` — names,
   descriptions, schema sizes). External comparison: what do high-adoption MCP tools (per public
   agent-tooling practice) do differently in naming/description shape?
2. **Product levers.** Tool descriptions (when-to-use guidance, capability boundaries), tool
   naming, parameter schema simplicity, result legibility (does the response invite follow-up use
   or reward one-shot answers), surface size (five tools vs a leaner offer), and any
   MCP-spec-level affordances (annotations/hints) the connected clients honor.
3. **Cheap iteration loop.** Adoption is far cheaper to measure than utility: it needs invocation
   events under a neutral prompt, not accuracy-gated powered runs. Define with 624 a smoke-scale
   adoption probe (haiku, small n, existing corpus fixtures) that can rank lever variants before
   any full campaign. Pre-registration discipline still applies: metric, floor/target, and variant
   set fixed before outcomes are seen.
4. **Honest maximization criteria.** Success = adoption on queries where retrieval would help AND
   no increase in spurious invocation on queries where it wouldn't AND outcome sanity not degraded.
   Report token effects only descriptively (see confounds).

## Boundaries (what this does NOT own)

- **NOT the measurement machinery** — 624 owns the harness, the adoption metric's formal
  definition, and the interpretation tree. This tempdoc proposes lever variants and consumes 624
  measurements; if the harness needs a small capture addition, that is a 624 ask, recorded there.
- **NOT prompt-steering the evaluated agent.** The neutral-prompt discipline stands; the levers
  are what the product *is* (its offered surface), not what the eval tells the agent to do. An
  encouragement/steering *experiment* (does explicit prompting change adoption?) is a legitimate
  diagnostic instrument here, but its results are diagnosis, never the shipped configuration.
- **NOT utility or publication claims** — 624 interprets, 719 publishes, and only from an accepted
  record. Adoption improvements shift what a future campaign measures; they claim nothing.
- **NOT retrieval-quality work** — if diagnosis reveals agents tried the tool and abandoned it
  because results were poor, that finding routes to the search-quality tempdocs, not here.

## Known constraints and confounds (record up front)

- **Surface changes create new cohorts by construction.** Any change to tool names/descriptions/
  schemas changes the MCP `tools/list` hash, which the 719-merged identity machinery treats as a
  different measured product — before/after cells cannot silently pair. State this; don't fight it.
- **The tool-definition block perturbs token measurements.** Tool definitions participate in
  prompt-cache writes (Anthropic cache semantics, 719 research pass), so lever variants mechanically
  move `cache_creation_input_tokens` independent of behavior. Token effects are descriptive only.
- **Adoption is gameable.** A shouty description can manufacture invocations. Every reported
  adoption gain needs the spurious-invocation and outcome-sanity counterparts reported beside it.
- **Client diversity.** Adoption behavior is a function of (model, client harness, surface). The
  pilot observed one client cohort (Claude CLI + haiku); generalization to Cursor/Claude
  Desktop/other models is an explicit open question, not an assumption.

## First questions for the takeover agent

1. Does the raw pilot log still exist, and what do the 11 non-adopting B-cell transcripts actually
   show? (Secure this evidence first — copy the relevant cells into the tempdoc or a committed,
   sanitized note before it is lost. If already lost, say so; do not reconstruct.)
2. What exactly does the offered surface look like today — the verbatim `tools/list` payload an
   agent receives, its total token weight, and each description's when-to-use signal?
3. Is there prior art in our own decisions? (Check ADRs/tempdocs for earlier tool-description or
   MCP-surface decisions before claiming a novel gap — e.g., how the five-tool surface and
   `justsearch_answer`-as-primary framing were chosen.)
4. What is the cheapest pre-registered adoption probe 624 would accept as decision-grade for
   ranking lever variants (model, n, corpus, seeds), and what does it cost?
5. Where is the diminishing-returns line — at what adoption level does the bottleneck plausibly
   shift from legibility back to retrieval quality or task mix?
