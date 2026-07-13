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

---

# Takeover investigation (2026-07-14, session 9d3c1869)

## Verdict

**GO — but rescoped, and with an explicit ownership transfer from 655 before any design work.**
The goal (raise correct adoption) is real, owner-directed, and correctly sequenced before any paid
campaign. But the opening stub's framing is partially stale and partially duplicative: most of its
"diagnosis" was already done by ADR-0015, tempdoc 366, tempdoc 655, and 624's scale run — and this
takeover's own new evidence (below) points the first lever somewhere the stub didn't anticipate:
**tool visibility/discovery mechanics, not description copy.** Do not start with description
rewording; that specific lever was already tried, measured, and judged weak (655), and the newest
evidence suggests descriptions may not even be reliably *in context* for current-cohort agents.

## Corrections to the opening stub (verified against source)

- **The surface is six tools, not five** — `justsearch_runtime_manifest` was added post-501
  (`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:225-230`); README already
  says six.
- **"Diagnose why adoption is low" is largely answered prior art**, not an open question (see
  Displacement below). The genuinely open question is narrower: what closes the quantified
  **decision-to-retrieve gap** (adoption 16.7%, median first MCP call at the 21st tool call,
  18/30 forced-arm cells still reflexively trying grep — 624 pass 26 / 655 scale re-test table).
- **The raw pilot log does not contain agent reasoning** — `samples[].messages` holds only the
  input prompt; the Claude Agent SDK runs out-of-process, so no chain-of-thought or per-turn
  assistant text survives. "Read why the agent chose grep" is not possible from this artifact;
  only behavioral traces (tool calls, blocked calls, final completion) exist. The log also stores
  only a **hash** of the offered tool descriptions, not their text (by design,
  `agent_utility_inspect.py:340-433`).
- Raw evidence secured: full pilot directory copied to session scratchpad; the durable facts are
  recorded here and in the committed sanitized fixture.

## New load-bearing finding: the tools were behaviorally DEFERRED

The pilot's per-cell flag says `mcp_tools_offered=6, mcp_tools_deferred=False` — but that flag is a
**protocol-level end-of-session check** (`client.get_mcp_status()`: server connected, `tools/list`
non-empty), not proof the tool schemas were in the model's context. The behavioral evidence says
otherwise: **both** B cells that engaged with JustSearch at all (`B|q9`, `B|q18`) first had to call
`ToolSearch("select:mcp__justsearch__…")` — the deferred-tool expansion pattern — before the tools
were callable, while no cell ever needed to "search" for the always-visible native file tools. The
other 12 completed B cells show zero mechanical trace of the tools (no ToolSearch, no MCP call, no
blocked attempt).

If MCP tools are collapsed placeholders in the current Claude CLI/Agent-SDK cohort (tool search is
documented as on-by-default in the Agent SDK), then:

1. **655's "descriptions were eagerly in-context and still ignored" conclusion is in doubt for
   this cohort** — the description text may be invisible until an explicit discovery step.
2. The adoption funnel has measurable stages, and the pilot locates the loss at the top:
   **visibility/discovery** (2/14 expanded the tools) → **invocation** (1/2 invoked after
   expanding) → **reinforcement** (0/1 — the one adopter made 8 MCP calls with reformulations,
   had 2 blocked/errored `justsearch_search` calls, and still closed via plain `Read`).
3. "Median 21st tool call" adoption lateness may be partly a *harness discovery artifact*, not
   only a habit prior.
4. The primary description-side lever becomes **keyword-matchability for ToolSearch discovery**
   (per Claude Code tool-search docs: names/descriptions are keyword-matched), not eloquence.

Also observed: `B|q9`'s blocked list contains a hallucinated Bash invocation
`justsearch initialize .` — the model conceived of JustSearch as a CLI binary, not MCP functions.
A cheap legibility signal worth remembering when naming/describing tools.

**Caveat: this is one cohort (haiku + Claude CLI via Agent SDK).** Anthropic docs state the
API-level Tool Search Tool is unsupported on Haiku, yet ToolSearch calls appear in this haiku log —
harness-level and API-level tool search are evidently distinct mechanisms. Deferral ground truth
per client (Claude Code / Desktop / Cursor) is THE first design question, not an assumption.

## Rational non-adoption re-confirmed on this corpus

Every completed cell in both arms answered correctly except 2 A cells (A: 14/16 correct of
completed; B: 14/14). The corpus (`battlefield-en-scale-v1`, ~2.4k synthetic docs, exact-phrase
2-hop lookups) is grep-favorable by construction. The real pressure was **wall-clock**: all 18
exclusions were timeouts (A 8/24, B 10/24), with completed cells at a median ~26 tool calls. So the
plausible utility story on such corpora is turns/time/timeout-rate, and adoption is the gate to
observing it — but *correctness* pressure toward the tool does not exist there, consistent with the
external finding (arXiv:2605.15184, "Is Grep All You Need?", 2026-05): grep beats vector retrieval
in real harnesses except at corpus scale, on paraphrased (non-literal) queries, or one-shot
retrieval. The 707 corpus family (grep-stressed, verbose/short-natural strata) targets exactly the
regime where adoption *should* be rational — measurement there is decision-grade; more measurement
on battlefield-en-scale-v1 is not.

## Displacement — what already exists (do not re-derive)

- **ADR-0015** (founding surface design): eval-driven 7→4 consolidation (41/50 vs 4/50
  tool-composition accuracy), schema-minimal + position-bias (`answer` registered first) +
  progressive disclosure principles, with literature citations. Current descriptions descend from
  **tempdoc 366**; **tempdoc 655** was already the *second* copy iteration and warns that a third
  is justified only if new evidence shows descriptions specifically are the bottleneck.
- **655 shipped the `initialize.instructions` connect-time steering layer** (TOOL_SELECTION_GUIDANCE,
  `McpToolSurface.java:96-104`) + response-level progressive-disclosure hints, after triaging
  levers: descriptions = weak ("read and lost to a habit prior"), MCP prompts = unreachable by
  autonomous agents (user-invoked by protocol), instructions = the untried lever.
- **624 pass 26 / 655 scale re-test** already ran the scale discriminator: adoption 0.0 → 16.7%
  (joint effect of 655 layer + corpus scale, explicitly not separable — no ablation);
  `justsearch_answer` went from never-called to 27 cells; forced-arm answer-first rate 76.7%.
  Adoption metric definitions (`adoption_rate`, `first_mcp_call_turn`, `mcp_call_share`) and the
  interpretation tree live in 624.
- **655 §"Next lever (owned here)" names the decision-to-retrieve gap as its own open work**, with
  candidates (tool naming/annotations, instructions strengthening, answer-first framing before
  grep commitment) and 624 Step-2 (model-tier sweep) as the capability-vs-rational discriminator.
  **This is the overlap 725 must resolve by explicit transfer, not duplication**: at design time,
  add a pointer in 655's current fold transferring the decision-to-retrieve lever work to 725, or
  close 725 and resume under 655. Recommendation: transfer to 725 (655 is a sprawling
  conformance-policy tempdoc whose core shipped; the adoption arc now has its own evidence base),
  recorded in both tempdocs in the same change.

## External evidence base (primary sources; full details in session research, key items here)

- **Trigger conditions in descriptions measurably lift should-call rate**, especially on newer
  conservative models (Anthropic tool-use guidance; "writing effective tools for agents",
  2025-09); namespacing choices have "non-trivial effects".
- **Tool-search-style deferral is the largest documented selection-accuracy lever** (Anthropic
  advanced-tool-use, 2025-11: Opus 4 49%→74% with Tool Search vs all-upfront) — and it inverts
  description strategy toward keyword-matchable discovery.
- **Over-triggering is a measured cost, not hypothetical**: description augmentation improved task
  success but +67% median execution steps and 16.7% outright regressions (arXiv:2602.14878, the
  "smelly descriptions" study — which ADR-0015 already cites). A BFCL-relevance-style negative
  control set (queries where the tool should NOT fire) is the cheap instrument for 725's "correct
  adoption" criterion.
- **Client approval friction suppresses MCP tool reuse independent of descriptions**
  (anthropics/claude-code#25966: per-call approval for read-only MCP tools in Claude Desktop) —
  `readOnlyHint: true` (already set on 5/6 tools) is the partial mitigation.
- **No public data exists on MCP adoption rates** — documented absence; our own funnel numbers may
  be genuinely novel evidence.

## Cheapest evidence, ordered (all pre-paid-campaign)

1. **Deferral ground truth (~$0, decisive for lever choice):** determine per-cohort whether MCP
   tool schemas are in-context or collapsed behind ToolSearch — inspect a live session's init/tool
   surface against the dev stack (one cell, no judging), and check the 2026-07-08 scale-run log
   (if it survives) for ToolSearch-before-adoption in its 5 adopting cells. This either
   revalidates or overturns 655's descriptions-read-and-ignored premise for current cohorts.
2. **Funnel re-read of existing logs ($0):** recompute adoption as the three-stage funnel
   (discovered → invoked → reinforced) on every surviving B-arm log; the stage split, not the
   headline rate, picks the lever.
3. **Smoke-scale adoption probe on a 707 member (small $, 624-owned protocol):** only after 1-2,
   and only on the grep-stressed corpus where adoption is rational; battlefield-en-scale-v1
   results cannot justify description/steering changes.

## What this tempdoc should NOT become

- A third description-copy iteration justified by near-ceiling-corpus data (655's explicit warning).
- A re-run of the scale discriminator that 624 already ran.
- A prompt-steering experiment shipped as product (boundary stands).
- A duplicate authority over adoption metrics (624) or the decision-to-retrieve lever list (655 —
  resolve by transfer).
