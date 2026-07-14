---
title: "Agent tool-adoption legibility: diagnose why agents offered the JustSearch MCP surface rarely invoke it, and raise correct adoption as far as the product surface allows"
type: tempdocs
status: "open — level 1 merged (PR #178); A/B smoke judged (adoption corpus-conditional, visibility refuted in-regime); #605 fix merged (PR #179). Forensics complete (9/4/2 split; token-efficiency added; L4 re-scoped to response legibility, owner decision: weak-agent failures are product-addressable). Research#2 + theorization (model-agnostic axiom) + design#2 (self-describing results by projection) + derisk (8/10) done. LEVEL-2 IMPLEMENTED on branch worktree-725-response-legibility (unpushed, no PR per instruction): match-anchored previews + rationale/degradation/coverage lines, evidence-pack header, LABELED wrong-gate fix, response_format concise|detailed, actionable errors, TOOL_SURFACE_VERSION 0.3.0, surface-aware contrast guard; opus review MAJOR+MINOR fixed; live-validated incl. on a genuinely degraded stack; full suites green. Response-shape A/B PRE-REGISTERED (run owner-gated, ~$9-12/campaign/model). Awaiting owner: PR/merge word, A/B spend, 624 Step-2."
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

---

# Research pass (2026-07-14; targeted — client exposure mechanics + MCP spec affordances)

> Scope decision: the takeover already covered description-writing guidance, over-triggering costs,
> and selection benchmarks. This pass researched only the two questions the deferral finding made
> load-bearing and that are actively changing: (1) how current clients actually expose MCP tools to
> the model; (2) what the MCP spec itself offers for discovery/steering. No external code or text
> was copied into the repo; findings are cited summaries only (no license/attribution payload).

## R1. Claude Code / Agent SDK defer ALL MCP tools by default — descriptions are invisible pre-search

Current official docs (code.claude.com/docs/en/mcp.md §"Scale with MCP tool search";
agent-sdk/tool-search.md; both fetched 2026-07-14): **"Tool search is enabled by default. MCP tools
are deferred rather than loaded into context upfront"** — unconditional, not threshold-gated (the
10%-of-context `auto` mode is opt-in; at the Jan 2026 launch the threshold WAS the default, and the
flip to unconditional defer-all is undated in any changelog). Pre-search the model sees **"only
tool names and server instructions"** — not descriptions, not schemas (both truncated at 2KB).
Discovery search matches tool names, descriptions, argument names, and argument descriptions
(platform tool-search-tool.md).

Consequences for this tempdoc:

- **655's conclusions survive, re-read under deferral.** The `initialize.instructions` text is
  precisely one of the only two things a deferred-tools model ever sees pre-search — 655
  unknowingly shipped the one server-side text with guaranteed pre-search visibility in this
  cohort, and adoption moved off zero. "Descriptions read and lost to a habit prior" becomes
  "descriptions never read pre-search"; the description lever is weak for a *documented structural
  reason*, and its remaining value is as **ToolSearch match target** (keyword-matchability).
- **Tool NAMES are the pre-search billboard.** The visible name in this cohort is
  `mcp__justsearch__justsearch_answer` — the server prefix already carries "justsearch", so the
  tool-side `justsearch_` prefix duplicates it in every listing. Naming is now a first-class
  lever, but note the trade-off: bare `answer`/`search` names lose keyword-richness for clients
  that DON'T prefix (spec guidance: names should be unique + descriptive). Design decision, not
  obvious.
- **A zero-risk client-config lever exists that no prior tempdoc considered:**
  `"alwaysLoad": true` on the server entry in `.mcp.json` (Claude Code ≥v2.1.121) forces eager
  loading of our 6-tool/~1.5k-token surface — well inside the docs' own "skip tool search under
  ~10 tools" guidance. JustSearch's README/docs recommend the client config verbatim; adding
  `alwaysLoad` there changes what every following user's agent sees, without touching the eval's
  neutral-prompt discipline (it is product documentation, not eval steering). Whether
  eager-vs-deferred actually moves adoption is then a clean, cheap A/B under 624's harness —
  arguably the single highest-information experiment now available.
- **Haiku contradiction (needs runtime probe, not docs):** CLI/SDK docs say tool search excludes
  Haiku; the platform API model table (same day) lists Haiku 4.5 as supported; a Dec-2025 issue
  (#14863) shows live 400s on Haiku `tool_reference` closed "not planned". Our pilot shows a haiku
  Agent-SDK run successfully round-tripping ToolSearch. Docs cannot adjudicate; the deferral
  ground-truth probe (Cheapest evidence #1) must capture the actual wire behavior.
- **Client diversity confirmed as material:** Claude Desktop has no documented tool-search
  mechanism (inferred eager, unconfirmed); Cursor documents a ~40-active-tool ceiling with silent
  drops and no deferred loading (third-party, unverified). Adoption behavior is cohort-specific;
  every measurement must record the client + exposure mode as identity (624's cohort machinery
  already can, once the exposure mode is captured honestly — the current `mcp_tools_deferred`
  protocol-level flag is demonstrably misleading and needs a capture fix under 624).

## R2. The MCP spec offers no server-side priority lever — and `instructions` is only a MAY

Spec research (modelcontextprotocol.io 2025-11-25 + draft, schema.ts diffed; fetched 2026-07-14):

- **No spec-level "when to use" / priority / category field exists** on `Tool` or
  `ToolAnnotations`, in any revision; no in-protocol tool search (closest proposal SEP-1821 is an
  unsponsored draft); no server-suggested ordering a client must honor (the draft's deterministic
  `tools/list` ordering SHOULD is cache-hit optimization only — worth conforming to regardless).
- **`instructions` has always been a MAY** ("this information MAY be added to the system prompt")
  — clients are not obliged to surface it. Claude Code/SDK do (see R1); other clients'
  conformance is unverified. 655's layer therefore has guaranteed reach only in the cohort that
  also defers tools.
- **Forward-compat alert: the next spec revision (final expected 2026-07-28) removes the
  `initialize` handshake entirely** (SEP-2575/2567); `instructions` relocates to a new mandatory
  `server/discover` response. When JustSearch adopts that revision, 655's shipped layer must
  migrate — noted here so the migration lands with spec-adoption work, not as a surprise.
- **Direction of travel** (official client best-practices doc): hosts are told to implement
  progressive discovery — search over tool `name` + `description` via a host-side meta-tool, with
  a 1-5%-of-context trigger guidance. The ecosystem is converging on deferred-by-default; the
  pre-search-visible surface (names + instructions) is where server-side legibility investment
  compounds, and keyword-rich names/descriptions are the one spec-sanctioned discoverability lever.

## Net effect on the takeover verdict

Unchanged (GO, rescoped, transfer from 655), with the lever order now documentation-backed rather
than inferred: (1) capture true exposure mode in the harness (624 fix — the misleading flag);
(2) the eager-vs-deferred `alwaysLoad` A/B as the first experiment; (3) `instructions` +
tool-name work as the pre-search-visible product surface; (4) descriptions rewritten only as
ToolSearch match targets, with the over-triggering negative-control set; (5) the 2026-07-28 spec
migration noted for whenever the protocol revision is adopted.

---

# Settled design (2026-07-14)

## Design decision

725 is **the adoption-funnel program for the agent-facing MCP surface**: measure where agents fall
out of the funnel (visible → discovered → invoked → reinforced), then move the binding stage
through the product surface, under pre-registered decision rules. It is not a description-rewrite
project, not a new measurement authority, and not an eval-steering project.

```text
624 harness (instrument: funnel metrics, exposure-mode identity, A/B protocol)
        |
        v
725 diagnosis + product levers (subject: names, instructions, descriptions,
    error/response legibility, recommended client config)
        |
        v
ship decisions gated by pre-registered funnel evidence; public wording stays
config-recommendation-only until a 624-accepted record exists
```

## Part I — Measurement honesty (624-owned prerequisites, discovered here)

1. **Exposure-mode capture.** Per-cell, capture-time record of how the tools were actually exposed:
   eager in-context vs deferred placeholders, plus the harness config that determines it
   (`ENABLE_TOOL_SEARCH` state, `alwaysLoad`) and a behavioral confirmation signal. Exposure mode
   joins **cohort/pairing identity** — cells measured under different exposure modes must not
   silently pair. This conforms to 719's source-provenance invariant ("run identity is an event
   property") and lands in 624's existing identity machinery; it is not a new seam.
   **Orphan:** the current protocol-level `mcp_tools_deferred` flag's misleading semantics — it is
   demonstrably an end-of-session server check, not a model-context fact. It is superseded by the
   new capture; it may remain only as an explicitly-labeled raw protocol echo. Old records stay
   immutable and visibly pre-contract (719's pattern).
2. **Funnel metrics.** `adoption_rate` (kept, 624's vocabulary) is decomposed with:
   discovery rate (agent expanded/loaded our tools), post-discovery invocation rate, and a
   reinforcement signal (continued/repeat use after first result; share of blocked/errored calls
   followed by abandonment). Definitions belong to 624; 725 states the semantic need. The pilot's
   funnel read (2/14 discovered → 1/2 invoked → 0/1 reinforced) is the motivating shape.
3. **The deferral ground-truth probe is the first implementation act.** One live Agent-SDK session
   against the dev stack (and per-client where cheap), capturing what the model context/wire
   actually holds — settles the Haiku documentation contradiction and validates the new capture
   before any lever experiment. Zero model-spend beyond one trivial session.

## Part II — Product levers, ordered by pre-search visibility (the evidence-backed order)

- **L1 — Recommended client configuration (`alwaysLoad`).** JustSearch's own docs/README recommend
  the `.mcp.json` client snippet; adding `"alwaysLoad": true` makes the six-tool surface eager in
  Claude Code/Agent SDK — inside Anthropic's own "skip tool search under ~10 tools" guidance. This
  is a docs change, not an engine change, and does not touch the eval's neutral-prompt discipline
  (it is the product's recommended configuration, i.e., part of the measured product). Shipping is
  gated by the A/B decision rule below, because eager loading has a real cost (~1.5k tokens every
  session, used or not) and an unmeasured benefit.
- **L2 — `instructions` v2.** The connect-time text is one of only two things a deferred-tools
  model sees pre-search. Revise the existing single-sourced TOOL_SELECTION_GUIDANCE to add what
  the current text lacks under deferral: when to *search for* these tools, and decision-to-retrieve
  timing (reach for retrieval *before* committing to iterative grep over a large corpus, not after
  it fails). Constraints: remains a single-sourced projection (655's single-source guard stays),
  ≤2KB (client truncation), comparative-not-feature-forward tone (366/655 evidence), and version
  bump via the existing TOOL_SURFACE_VERSION. This is an in-place revision of 655's shipped layer,
  not a parallel mechanism.
- **L3 — ToolSearch matchability.** Descriptions and argument names/descriptions are the
  documented match corpus for deferred discovery. Optimize keyword coverage for how agents phrase
  retrieval tasks; explicitly NOT a third eloquence pass (655's warning honored — the target is
  the search index, not the reader). Gated by a negative-control set (queries/tasks where the
  tools should NOT fire) so over-triggering (+67% steps median in the external study) is measured,
  not discovered in production.
- **L4 — Reinforcement-stage legibility.** The one observed adopter was ejected by
  blocked/errored `justsearch_search` calls and closed via `Read`. Audit tool-error responses:
  execution errors should carry self-correction guidance (the 2025-11-25 spec moved validation
  errors to execution errors for exactly this), and the existing 655 response hints should cover
  the "result didn't advance the task" path. Extends 655's progressive-disclosure hints in place.
- **Explicit non-lever: renaming tools.** Bare names (`answer`, `search`) would read better under
  the client's `mcp__justsearch__` prefix, but tool names are user-facing config keys
  (permissions/allowlists), the duplication is cosmetic, keyword-rich names aid both discovery and
  non-prefixing clients, and spec guidance wants unique descriptive names. Churn cost exceeds
  plausible gain; recorded so the question isn't reopened casually.
- **Conformance rider:** deterministic `tools/list` ordering (draft-spec SHOULD; cache-hit
  benefit) — verify at implementation, almost certainly already true in the Java surface.

## Part III — Pre-registered decision rules (fixed before outcomes are seen)

- The first experiment is the **exposure-mode A/B**: same corpus (a grep-stressed 707 member —
  battlefield-scale data is explicitly not decision-grade), same instructions, arms differing only
  in eager-vs-deferred exposure, haiku cohort, 624 protocol, funnel metrics + negative controls.
  It answers whether visibility or persuasion is binding — the highest-information cheap question.
- Ship L1 (recommend `alwaysLoad`) iff discovery+invocation improve materially without
  outcome-sanity degradation and with acceptable token overhead; ship L2/L3 variants ranked under
  the same rule. Numeric thresholds are set with 624 when the experiment is registered — not
  invented here, and never after seeing results.
- Spend is owner-gated per the jseval cost policy (smoke-scale, ~$3 class). No public quantitative
  adoption claim ships from any of this until a 624-accepted record exists (719's boundary);
  public docs may carry the recommended configuration and qualitative rationale only.

## Sequencing

1. Ownership transfer edit in 655's STATUS SUMMARY (dated pointer: adoption/discoverability lever
   → 725; 655 keeps conformance + capability policy + its open Q2 fixture decision).
2. Deferral ground-truth probe (Part I.3).
3. Exposure-mode capture + funnel metrics under 624 (Part I.1-2) — same correctness boundary, one
   lead.
4. Owner-authorized exposure-mode A/B smoke on a 707 member (Part III) — depends on 707
   member materialization (done) but not on its full scientific certification (adoption is not a
   public claim).
5. Ship winning levers (L1 docs change; L2 instructions v2 with TOOL_SURFACE_VERSION bump); then
   L3/L4 with negative controls; re-measure funnel.
6. Spec-migration note stays parked with 500/655 (initialize → server/discover when the 2026-07-28
   revision is adopted); recorded here only as a dependency alert for L2's carrier field.

## Orphans (owned by this tempdoc's implementation, not a later sweep)

1. **655's ownership of the adoption/discoverability lever** (its 2026-07-07 status line and "Next
   lever (owned here)" section) — superseded by transfer; the 655 edit lands with this tempdoc's
   first implementation change.
2. **`mcp_tools_deferred` capture semantics** — superseded by exposure-mode capture (Part I.1).
3. **TOOL_SELECTION_GUIDANCE v1 text** — revised in place by L2 (version-bumped, single-source
   guard intact); not a parallel text.
4. The opening stub's description-first implication and five-tool count — already superseded by
   the takeover corrections above.

# Design reach

## Conforms to (instances of existing seams — no new machinery)

- **719's source-provenance invariant**: exposure mode is an event property of the run, captured at
  source time, part of pairing/cohort identity.
- **624's measurement authority** and pre-registration discipline (metrics vocabulary, decision
  rules, interpretation).
- **655's single-sourced steering projection** (instructions v2 revises the projection, does not
  fork it) and ADR-0015's surface principles (schema-minimal, position-bias, progressive
  disclosure).

## New principle worth naming: the visibility funnel

**"An agent-facing affordance is adopted through a funnel — visible → discovered → invoked →
reinforced — and the binding stage must be identified by measurement before any stage's content is
optimized."** The failure mode it prevents: polishing persuasion text (descriptions) when the loss
is at visibility (deferral), or adding visibility when the loss is at reinforcement (errors eject
the agent).

- **Candidate scope beyond this problem:** MCP resources/prompts if ever exposed; the agent-api
  HTTP endpoints (do external agents discover `/api/agent/*` affordances?); the plugin/community
  surface (660); even 719's public replay command (is it discovered → run → trusted?).
- **Existing violations:** the eval's single `adoption_rate` collapses the funnel (being fixed in
  Part I); 655's description-tuning near-miss was optimizing a possibly-non-binding stage — its
  own warning said so without the funnel vocabulary.
- **Evidence it earns its keep:** a funnel measurement changes a lever decision. This has already
  happened once (the takeover's discovery-stage finding demoted description work and promoted
  exposure-mode work); the A/B will be the second, cleaner test.
- **Retirement condition:** if agent clients converge on transparent/eager tool exposure so that
  visible ≈ discovered (the funnel's top collapses), retire the extra stages back into plain
  adoption/invocation metrics — do not maintain funnel machinery for a distinction reality stopped
  making.

## Smaller conforming principle: cohort relativity of agent-behavior facts

Any measured agent behavior is a (model, client/harness, exposure-mode) cohort fact — this is
624/719's existing direction with exposure mode as a newly recognized axis; conform via identity
capture, build nothing new. Earns its keep when two cohorts' adoption numbers stop being silently
comparable (already true: Desktop-vs-CLI exposure differs); retires only if the identity machinery
itself subsumes it so completely that restating it adds nothing.

---

# De-risking pass (2026-07-14; live probes + log audit; no feature work)

All evidence in session scratchpad `probe-results-v3/` (+v1/v2 for the failure forensics) and the
funnel audit below. Dev stack used and stopped; CLI 2.1.207, `claude-agent-sdk` 0.2.111.

## Per-uncertainty verdicts

- **U1 (deferral on our versions) — CONFIRMED, on haiku.** Live SDK session, trusted cwd, default
  config: all six `mcp__justsearch__*` tools present in the init message but self-reported
  `names_only`; the model answered "Schema not visible (deferred tool)" for
  `justsearch_answer`'s description. The docs' "tool search excludes Haiku" language is wrong at
  the harness level — haiku sessions defer and can ToolSearch (three independent behavioral
  proofs: this probe, the 2026-07-12 pilot, the v1 probe).
- **U2 (`alwaysLoad` works) — CONFIRMED, with a clean within-session control.** Same session,
  `{"type":"http","url":…,"alwaysLoad":true}` (SDK dict form): the six product tools moved to
  `full_schema_visible` — the model quoted the description's first eight words verbatim ("Get
  evidence from your indexed documents to") — while justsearch-dev's tools (no `alwaysLoad`)
  stayed names-only in the same context. The A/B's treatment arm is a one-key config change.
  (File-based `.mcp.json` form untested against a live server — trivial residual, verify when
  wiring the A/B.)
- **U4 (instructions reach the model) — CONFIRMED in BOTH modes.** The model quoted
  TOOL_SELECTION_GUIDANCE's first sentence verbatim with tools deferred AND with them eager —
  655's carrier works on the current cohort, including haiku.
- **U3 (exposure-mode observability) — capture must be config-echo + initialize-response, not
  init-message inference.** The SDK init message lists the six tool names identically in both
  modes — it does NOT distinguish deferred from eager. Capture therefore records: the session's
  `ENABLE_TOOL_SEARCH`/`alwaysLoad` config verbatim, plus the server `initialize` response
  (version + instructions text/hash). **New capture gap found:** the current surface hash covers
  `tools/list` only — `instructions` and server version are outside captured identity, and the
  stale-dist incident (below) shows that gap is live, not theoretical.
- **U5 (funnel derivability) — INVOKED fully derivable from both raw + sanitized shapes (order
  survives sanitization byte-for-byte); DISCOVERED needs one new sanitized field** (ToolSearch
  inputs are stripped — add `toolsearch_targets` or a referenced-justsearch boolean); a
  **REINFORCED-strict predicate needs ordered per-call status** (blocked calls are an unordered
  side-array with no positional linkage — upstream capture gap; `mcp_call_count>1` proxy works
  meanwhile). Full per-cell funnel table for the pilot: offered 14/14 → discovered 2/14 →
  invoked 1/14 → reinforced-proxy 1/14; `B|q9` is a clean discovered-then-abandoned drop-off.
  Passing find (inbox + verify at implementation): the committed rejected fixture may not satisfy
  the observation schema's `source.*` requirements — check before building on that schema.
- **U6 (A/B substrate) — PARTIAL.** No materialized 707 member exists on this machine (the CLERC
  6.7GB fetch never ran; only committed fabricated halves + recipes). Dev-runner stack starts
  reliably (3× today, ~40s) and the ingest path is healthy (200/accepted on a smoke ingest), so
  the 719-reported 120s-startup failures look specific to jseval's `--start-backend` launcher and
  routable-around via the dev-runner + external-backend env vars. Fetch+materialize is time, not
  dollars, and is the A/B's long pole.

## Incidental findings that harden the design

1. **Stale-dist hazard is real and silent:** the first stack launch served TOOL_SURFACE_VERSION
   0.1.0 — *without* the 655 instructions field — despite 0.2.0 being merged; the known
   `installDist` pitfall. Any eval run against a stale jar measures the wrong steering surface,
   and nothing in current capture would notice (instructions isn't hashed). Direct justification
   for the U3 capture rule; also means historical adoption runs' instructions-surface identity is
   unverifiable in retrospect.
2. **Project-scope MCP approval gates headless runs:** an unapproved project server shows
   "Pending approval" and connects as `failed` in `-p`/SDK sessions from an untrusted cwd (this
   masqueraded as server failure through an entire probe round). The A/B harness must pin a
   trusted cwd / pre-approved config, and README guidance for headless users should mention the
   approval step.
3. **One unexplained dev-stack death** under light MCP-only load (logged to observations inbox;
   watch during the A/B).

## Confidence and difficulty

**Confidence: 8/10** for the design's implementable increments (655 transfer, exposure-mode +
initialize-response capture, funnel metrics/fields, A/B harness config, L1/L2 levers). The two
residual unknowns are external and accepted: non-CLI client behavior (cohort-relative by design)
and 707 materialization end-to-end (production commands + tests exist from 719, but the fetch has
never run on this machine). Not 9+ because the schema/fixture drift find suggests the observation
contract needs a verification pass before building on it, and the owner-set A/B thresholds are
still open by design.

**Difficulty: moderate** — the hard thinking (lever order, funnel semantics, capture contract) is
done and probe-verified; what remains is disciplined plumbing plus one experiment. Recommended
split: **one Opus-tier lead (high effort) for the 624 capture/identity/funnel contract and the
pre-registration + instructions-v2 wording** (correctness-sensitive, cross-tempdoc authority
edits); **Sonnet (medium-high effort) for the bounded increments** — probe-script productization,
evidence-field additions + tests, A/B config wiring, 655 transfer edit, docs — under the lead's
review. Full-campaign execution stays owner-gated regardless.

---

# Level-1 implementation (2026-07-14; complete)

All level-1 increments from the approved plan are implemented and verified on
`worktree-adoption-legibility`. Orchestration: seven scoped sonnet implementers/auditors + one
opus refute-first reviewer; briefs, contract decisions, evidence judgment, and commits stayed in
the main loop. No campaign was run; no lever text shipped; public surfaces carry no new claims.

## What landed (per increment, with evidence)

- **0 — evidence contract repaired.** Root cause was sharper than the derisk flag: FIVE
  late-added `source.*` fields (incl. `source_git_state`) were schema-required but absent from
  the pre-contract fixture, and the schema was never enforced against evidence rows in CI.
  Fix: required-ness relaxed to properties-only for late-added fields (types still checked;
  `additionalProperties:false` and `read_evidence` unknown-key rejection untouched), plus a
  parametrized test validating all 48 committed fixture rows. This set the pattern all new
  fields follow: schema-optional, sanitizer always-emits, claim-grade strictness lives in the
  policy gate.
- **1 — 655 → 725 transfer recorded** in 655's STATUS SUMMARY (+ pointer at its "Next lever"
  section) and 624's fold; headless-approval note added to
  `docs/reference/mcp-production-server.md` (increment 6 rode along; llms.txt/links/markdownlint
  green).
- **2 — exposure + initialize identity.** New cohort blocks `exposure_config`
  ({enable_tool_search, always_load, exposure_mode} — config-derived only) and
  `mcp_initialize_identity` ({instructions + sha256, server_version, protocol_version}, captured
  via a JSON-RPC `initialize` call beside the existing `tools/list` capture).
  `mcp_tools_deferred` relabeled as protocol echo (orphan closed). Identity joins per the
  settled contract: cohort key + compose mix-guard + ITT stratum key — NOT `pairing_key`
  (search-config precedent; pinned tests extended, none weakened). Claim policy:
  `source_identity_complete` extended; new `verified_exposure_mode` requirement + gate; policy
  file remains draft and still rejects `policy_unresolved`.
- **3 — funnel fields + metrics.** New per-cell `toolsearch_targets` (strict tool-name grammar)
  and `tool_call_sequence` ([{name,status}] over ALL attempts, ordered — `tool_call_names`
  untouched for existing consumers). Funnel block beside `adoption`: `discovery_rate`,
  `post_discovery_invocation_rate`, `first_discovery_turn`, `reinforced_proxy_rate`, strict
  `reinforced_rate`. Old evidence: metrics null + `funnel_fields_absent: true`, never silent 0
  (fixture-asserted). Producer-shaped synthetic campaign reproduces the pilot funnel
  14→2→1→1 as a regression test.
- **4 — A/B wiring (config only).** `--agent-env KEY=VALUE` on `utility-run` threads
  `env=` into `ClaudeAgentOptions` (SDK merge semantics verified against installed source:
  `options.env` wins); recorded exposure reflects the child session's effective config;
  `alwaysLoad` validated (boolean, fail-closed) in the existing calibration assert; new pure
  `exposure_contrast.py` (descriptive per-metric {a,b,delta}; hard ValueError on mismatched
  non-exposure identity; no verdict field — that boundary is level-2/owner). Command inventory
  unchanged at 84.
- **5 — audits.** `tools/list` tool ORDER was already deterministic; the nested
  `inputSchema.properties` used JVM-salted `Map.of` → converted to insertion-ordered maps
  (idiom-matching helper), order assertions added; spotless + module tests + build green. Our
  own `mcp_tool_surface_hash` was never affected (canonical sorted-key hashing) — the fix is for
  external byte-fingerprinting/prompt-cache stability. Residual noted: `resource()`'s `Map.of`
  feeds `resources/list` (outside scope, trivial follow-up). The L4 error-legibility audit
  produced a ranked 8-item GAP backlog (below).

## Semantic-digest transition (deliberate, proven)

The rejected-fixture recompose digest moved
`2cea990ce444…c076ee6` → `2f555f661a91…4a100`. Independent structural diff of old-vs-new
records: the ONLY non-volatile delta is `claim_verdict.policy_hash`, because adding
`verified_exposure_mode` to the checked-in draft policy is inseparable from a policy-content
change (the policy schema is exhaustively-required by design and the three-way sync test pins
it). Arithmetic, loss, exclusions, identity, gates, reasons: byte-identical. The bare-recompose
digest was always a function of the current draft policy and will legitimately move again when
the owner resolves thresholds; publication replay is immune (bundles pin their policy bytes —
`test_replay_uses_bundled_policy_not_changed_default`). Historical digest citations in tempdoc
719 and this file's earlier sections remain true for their dates.

## Refute-first review (opus, reviewer ≠ implementers)

Attacked: conditional-exclusion asymmetries (absent/unknown/eager/deferred are four distinct
identities; every forged mixed-exposure combination trips the mix-guard), legacy invariance,
sanitizer leak surface, pinned-test integrity (zero weakened assertions across all modified test
files), exposure-derivation edge cases (15 parametrized), SDK env merge order, Java content
preservation, schema coherence. **One MAJOR confirmed and fixed:** `toolsearch_targets` captured
same-comma-segment free text verbatim (prompt-injection-shaped `select:` input could leak
paths/emails into durable evidence) — closed with a fullmatch tool-name grammar in both producer
and schema pattern, with the reviewer's adversarial case as a permanent regression test.

## Pre-registered L4 backlog (from the error-legibility audit; evidence-gated, NOT shipped)

Ranked; each item cites its site in `McpToolSurface.java`/`IngestTool.java`:
1. Generic exception fallbacks leak raw `e.getMessage()` with no classification/next action
   (5 dispatch paths) — the most probable mechanism behind the pilot adopter's 2 errored search
   calls before abandonment (a zero-hit search returns SUCCESS with a helpful hint, so the
   errored calls were not the no-results path).
2. "Knowledge server not available" (answer/search/status) has no transient-vs-permanent or
   retry guidance. 3. Silent search-preview truncation (…substring(0,200), no notice).
4. "No readable files found" doesn't distinguish causes or point at `justsearch_browse`.
5. Many-results hint suppressed once any filter is applied. 6. Answer context-truncation note
   has no action. 7. Unknown-tool-no-suggestion is bare. 8. Unresolved-operation/manifest bare
   errors (likely unreachable).
Positive findings recorded: zero-results paths are already exemplary (success + concrete next
action); the gated-approval responses are best-in-class ("do not retry" + async flow).

## Verification (final tree)

Full jseval suite green except the 2 known pre-existing `test_correction_probe` failures;
`check-public-agent-utility` OK (no accepted result; all three projections in-sync — README and
RESEARCH.md untouched by this branch); inventory 84 in-sync; llms.txt/canonical-links green;
`gradlew build -x test` BUILD SUCCESSFUL (exit 0); recompose digest `2f555f66…a100` reproduced
across three independent agents + the main loop; `git diff --check` clean.

## Remaining (level 2 — unchanged owner gates)

Exposure A/B smoke (spend + thresholds with 624), 707 CLERC fetch/materialization, lever
shipping (L1 `alwaysLoad` recommendation, L2 instructions v2, L3 matchability, L4 backlog) per
the pre-registered decision rules, and the 2026-07-28 MCP spec migration (parked with 500/655).

---

# Exposure A/B smoke — pre-registration (2026-07-14, BEFORE any cell runs)

> Owner authorization: budget cents-to-few-dollars haiku smoke, granted 2026-07-14
> ("you have authorization regarding item 3"). This smoke produces decision evidence
> for the owner's lever-shipping call (design Part III). It is NOT a utility claim,
> NOT publication input, and ships nothing by itself.

## Design

Two campaigns, identical in every respect except MCP tool exposure:

- **Campaign D (deferred):** default client behavior — `{"type":"http","url":<mcp>}`;
  tools collapsed to names + server instructions pre-search (current-cohort default).
- **Campaign E (eager):** same entry + `"alwaysLoad": true` — full descriptions/schemas
  in context from turn 1.

Shared matrix per campaign: corpus `mixed/en-legal-clerc-1k-verbose` (materialized
2026-07-14; every commitment/certification signature reproduced; structural certification
only — sufficient because no public claim is made), all 20 committed queries × seed 0 ×
conditions A and B, model haiku, exact-match scoring (no LLM judge), existing per-cell
wall-clock and budget limits. Estimated total ≤ 80 cells; hard spend cap USD 6 total —
abort if projected to exceed.

## Pre-registered metrics (from the 725 substrate, per campaign B-arm)

Primary: funnel — discovery_rate, post_discovery_invocation_rate (and derived overall
invocation/adoption_rate), first_discovery_turn, reinforced_proxy_rate, strict
reinforced_rate. Contrast computed by `exposure_contrast` (descriptive {a,b,delta}).

Sanity (must-check, not optimization targets): completed-cell accuracy per arm,
timeout/exclusion rates, A-arm invariance (A cells have no tools; any A-arm difference
between campaigns is noise scale), exposure identity captured as declared
(D: exposure_mode=deferred, E: eager; initialize identity present; any `unknown` →
run is void, fix capture, rerun).

Descriptive only (no bars): provider token counters (tool definitions mechanically
shift cache-write tokens — 719 research), turns, cost.

## Pre-registered signal bars (interpretation guide, not ship triggers)

- **Visibility-binding signal:** E invocation ≥ 3× D invocation AND E invocation
  ≥ 25% absolute (≥ 5/20 B-cells).
- **Persuasion-binding signal:** E ≈ D (< 1.5× ratio) with both < 25% — visibility is
  not the bottleneck; L2/L3 levers move up.
- **Outcome-sanity veto:** E completed-cell accuracy worse than D by > 10 pp, or E
  spurious-looking invocation on cells whose grep path succeeded faster (qualitative
  note) — flag prominently regardless of funnel deltas.
- In-between results are reported as-is. The ship/no-ship decision on L1
  (`alwaysLoad` README recommendation) and any lever work is the owner's, made on this
  evidence — pre-committed here to prevent post-hoc bar-moving, not to auto-ship.

## Known caveats (declared up front)

1. **F-029 (dense-on-legal):** dense retrieval may underperform on legal text; affects
   both campaigns identically — the exposure CONTRAST is valid even if absolute
   retrieval quality is poor; absolute adoption may be depressed if early tool results
   disappoint (reinforcement stage). Recorded, not corrected.
2. **CRLF commitment nuance (corpus agent, 2026-07-14):** committed commitment digests
   hash CRLF build-time bytes; LF checkouts must regenerate the gold source via
   `corpus-query-stratum-build` to reproduce (done here; signatures reproduced exactly).
   Cross-platform replay gotcha for 707/719 — logged to observations.
3. **n=20 queries, 1 seed, one model, one client cohort:** smoke-scale; detects large
   effects only; no significance claims; cohort-relative by design.
4. First live exercise of the 725 capture path — a substrate defect discovered live is
   fix-and-rerun, and would itself be a finding.

---

# Exposure A/B smoke — results and judgment (2026-07-14; STOPPED after Campaign D)

Both pre-registered stop conditions fired and were honored: the $6 cap (Campaign D alone cost
$8.87 — real cost ~$0.22/cell vs the $0.075 assumed; future pre-registrations must budget from
this measured rate) and a substrate capture defect (below). Campaign E was not run; the contrast
is mechanically unproducible from D's record. **Judged against the pre-registered bars, the
outcome is a third case neither bar anticipated — and it is decisive anyway.**

## Headline: adoption was at CEILING in the DEFERRED arm

Campaign D (deferred exposure — the supposedly handicapped arm), B-condition, 20 cells on the
grep-stressed CLERC 1k-verbose member, haiku, live 655 instructions (v0.2.0, initialize identity
captured):

- discovery 20/20 (funnel `discovery_rate` 1.0), invocation 20/20 (`adoption_rate` 1.0);
- first discovery at call index ~1, first MCP call at index 2 — immediate, not late;
- `mcp_call_share` 0.49; `reinforced_proxy_rate` 0.90, strict `reinforced_rate` 1.0;
- exposure identity captured exactly as declared (`exposure_mode="deferred"`, `always_load: null`,
  `instructions_sha256=360df3a1…`, server 0.2.0) — the 725 capture worked first time live.

**Interpretation (vs the bars):** the visibility-binding hypothesis is REFUTED in this regime —
with a corpus where grep genuinely struggles, natural-language 2-hop queries, and the 655
connect-time instructions live, agents discover and adopt the deferred tools instantly and
universally. Campaign E could not have improved adoption over 1.0; the cap-stop was scientifically
correct, not merely fiscal. Combined with the 2026-07-12 pilot (8.3% adoption, synthetic
grep-friendly corpus), **adoption is corpus-conditional: rational non-adoption was the dominant
mechanism all along** (655's original hypothesis, now with the cleanest evidence yet). The L1
`alwaysLoad` recommendation is NOT needed for adoption in this regime (owner call whether E ever
runs for its residual questions: token/cache-cost effects of eager loading, and non-CLI cohorts).

## The frontier moves down-funnel: outcomes, not adoption

Descriptive, smoke-scale, no claim: B completed 19/20 (1 budget exclusion), but substring-EM
accuracy on completed cells was only **3/19 (15.8%)** despite universal tool use — on this corpus
the binding constraint is now **result quality/reinforcement** (retrieval quality on legal text —
the F-029 caveat — plus 2-hop difficulty at haiku, and the L4 error/response-legibility backlog),
not tool selection. A-arm baseline accuracy is unavailable (all 20 A-cells void — below).

## Substrate defect found by first live contact (must-fix before any future campaign)

`agent_utility_inspect.py:605`: the offered-vs-declared MCP tool-name assertion is not
condition-gated — A-cells (no MCP servers; empty offered list) are compared against the declared
6-tool canonical surface and marked errored AT RECORD TIME (after the agent loop ran and incurred
cost: $4.91 of void A-cells). Downstream, `_compose_cell` drops the baseline arm and the record
composes `measured: {}` — deterministic, would have voided E identically. Escaped 333 unit tests
AND the adversarial review because no fixture exercises a live A-cell WITH a declared canonical
surface (`unreachable-seed-green`, precisely). Fix + A-arm-shaped regression fixture is the next
implementation item; logged to observations. Second operational finding: `justsearch_dev_stop`
resolves dev-runner from the session-inject-time worktree path and breaks after that worktree's
teardown (observation logged; stack was PID-tree-killed cleanly).

## Evidence

All under `scripts/jseval/tmp/725-ab/` in the `725-ab-smoke` worktree (untracked): raw Inspect log
(J7GN33YAvwSmMYH9C7Xdrb), `source-identity.v1.json` sidecar, run-composed + recomposed records
(semantic_digest `e367a0f3…87b`, `measured:{}`), both mcp-config files, ingest/campaign logs.
Prep was fully green: ingestion+enrichment of the 1000-doc member in ~13.5 min, watched-root
scoped, corpus-dir signature match, queries sha == committed `query_gold_sha256`. Backend healthy
throughout; campaign wall-clock 11m10s.

## Owner decision points created by this result

1. Is Campaign E still wanted for its residual questions (eager-mode token/cache cost; nothing
   about adoption), at measured cost ~$9? Default recommendation: no, not until the #605 fix
   restores the A-arm baseline and a question actually needs it.
2. Lever order revision: L1 (`alwaysLoad`) demoted — adoption doesn't need it where retrieval
   matters; the productive frontier is L4 (reinforcement/error legibility) + retrieval quality on
   legal (708/712/713 territory), and the 624 utility campaign becomes MORE attractive since
   adoption is no longer the blocker on the target corpus regime.
3. The 8.3%→100% corpus-conditionality finding is the single most useful sentence for 624's
   pre-registered interpretation tree going forward.

## The #605 defect — FIXED (2026-07-14, same day, follow-up session)

Root-cause turned out one layer deeper than the results section recorded: the defective
assertion block was introduced by PR #173 (tempdoc 719, `70fb180`), not #178 — which is why the
2026-07-12 pilot (pre-#173) was never voided and why #178's adversarial review (scoped to #178's
changes) never examined it. Campaign D was the first live A-arm run against that code.

The mechanism had a second half: `_mcp_surface`'s `servers`-key `or`-chain returns the LAST
operand when all are falsy, so the SDK's empty server list reached `_record_cell` as `[]` (not
`None`) and passed the `servers is not None` guard — known-empty and status-unavailable were
conflated by key position.

Fix (this branch): both declared-vs-observed assertions (hash + names) now share the existing
`condition in _WITH_TOOL and mcp_config` gate ("condition A exempt by construction", same as the
adjacent surface assertion); `_mcp_surface` uses first-non-null-key lookup (explicit `null` =
absent, preserving null-padded serializer shapes). Regression fixtures mirror the live producer
shape — `mcp_servers=[]` + declared 6-tool surface (the `unreachable-seed-green` gap: the old
A-exempt test seeded `mcp_servers: None` with no declared surface, a shape production never
produces) — plus a B-arm names-mismatch guard proving the assertion still bites, and
tri-state/null-padding cases. Red-proofed: the headline fixture and the or-chain case FAIL
against the pre-fix producer (`401c1ae`). Full jseval suite green (1874 passed; only the 2
known-red `test_correction_probe` pre-existing failures). The A-arm baseline is restored for any
future campaign; Campaign E's evidentiary blocker is gone (the spend question remains the
owner's). Merged as PR #179 (`a8321f6`); post-merge CI green.

---

# Campaign-D failure forensics + live retrieval check (2026-07-14, follow-up session; $0)

## Method

Three evidence layers, all free: (1) field extraction over the raw Campaign-D Inspect log —
tool-call ARGUMENTS survive per cell; **tool RESULT CONTENT is structurally ABSENT** (confirmed
by exhaustive key search over all 40 samples: `tool_calls` entries hold only `{tool, input}`,
`messages` holds only the initial user turn, `events`/`store` empty — an upstream capture gap,
noted for 624); (2) corpus-side identification of every failed query's planted hop-1/hop-2 doc
(all exist; wording distance = full synonym swap, comparable to the control that succeeded);
(3) live re-check on a hard-clean re-ingest of the same member, querying through **the agents'
own path** (`POST /mcp` → `justsearch_search` / `justsearch_answer`).

## The split (16 wrong completed B-cells)

| Class | n | Cells | Avg cost/turns | Evidence |
|---|---|---|---|---|
| Agent shortcut (hop-1 found, no hop-2 lookup) | 9 | q1,q2,q4,q5,q7,q11,q13,q18,q19 | $0.09 / 9.3 | Completion quotes the hop-1 doc, answers the engineer name or its digits; zero follow-up query with the code. Several stop at 4-5 turns |
| Findable but not recognized | 4 | q3,q6,q12,q16 | $0.35 / 20.8 | Live ranks **1, 6, 2, 5** for the verbatim question (the SUCCESSFUL control ranked 5). Mechanisms: keyword-shortened paraphrases that miss (q3 verbatim #1, agent's own paraphrase absent); preview truncation before payload; synonym non-recognition (q12 printed its hop-1 code `Quenthorn25` while declaring failure — never connected "power station"→"reactor") |
| Genuine retrieval miss | 2 | q8,q14 | $0.37 / 25.5 | Absent from top-10 under BOTH phrasings (printing house/market square; watermill/granite quarry). Real dense+sparse gap on these synonym pairs |
| Error ejection | 0 | — | — | Zero blocked/errored MCP calls anywhere (caveat: executed-call error payloads invisible in this log format) |
| Scoring artifact | 0 | — | — | No I-scored completion contains the gold or a formatting near-miss |

Correct cells (3): all executed a genuine second retrieval hop; avg $0.23 / 18.7 turns.

## Product defects found live (not inference)

1. **`justsearch_answer` silently degrades when AI is offline**: returns ~10.3 KB of raw legal
   passages, no notice, no synthesized answer — and its passage set did not include the doc
   `search` ranks #1 for the same question. The harness never activates AI and the campaign log
   has zero inference mentions, so **every campaign B-cell's first tool call got this response**.
   Partially explains the answer→search+Read abandonment pattern. Strongest L4 candidate found
   to date.
2. **Preview truncation hides the payload at rank 1**: druker7's preview reads "The archive in
   the old courthouse" and cuts before "designated Druker7, was designed by the engineer
   Cavby8" — the agent must Read the file to get the answer-bearing span. Upgrades L4 backlog
   item 3 from hypothesis to demonstrated cause.

## Token efficiency (owner directive 2026-07-14: analyze as a first-class dimension)

- 46 `justsearch_answer` + 106 `justsearch_search` calls across 20 B-cells. The AI-offline
  answer dumps alone injected ~474 KB (~120k tokens) of low-signal context — ~6k tokens/cell
  before any Read.
- Failure-mode economics: the wrong-but-fast shortcut mode costs $0.09/cell; the thrash modes
  (unrecognized / retrieval-miss) cost $0.35–0.37 — **~1.5× a correct cell ($0.23) while
  producing nothing**. Bad response legibility is paid for in tokens, not just accuracy.
- Implication: response-shape fixes have direct token ROI (an honest degradation notice replaces
  a 10 KB dump; payload-centered previews shorten the search→Read loop). Token cost per correct
  answer joins the funnel + outcome sanity as a lever-evaluation criterion.

## Ownership reframing (owner decision 2026-07-14)

Weak-agent (haiku-class) failure modes are **in scope for the product**: the client population
includes weak agents, and the product surface is the controllable side of the interaction. The
shortcut and non-recognition buckets therefore route to product levers — response affordances
that make the second hop and result recognition easy for the weakest common cohort (explicit
follow-up nudges in result shapes, payload-visible previews, honest degradation notices), not
just to "use a bigger model". This extends L4's mandate from error legibility to **response
legibility** generally; success is measured under the funnel + outcome sanity + token
efficiency, with the usual over-triggering guard.

## Data note → 624/707

The 20 committed queries carry `question_type: 1_hop`, but every successful trace required two
retrievals (facility→engineer, engineer→value). Check the label taxonomy vs the stratum builder
before the next campaign interprets per-type slices.

## Evidence

`scripts/jseval/tmp/725-ab/725-forensics/` (main checkout, untracked): `b-cells-table.md` +
`b-cells.v1.json` (per-cell extraction), `hop-docs.v1.json` (corpus identification),
`live-retrieval-check.v1.json` (ranks via the MCP path), `answer-offline-probe.txt` (the 10.3 KB
degradation specimen). Campaign evidence relocated from the removed worktree to
`scripts/jseval/tmp/725-ab/` (see `README-provenance.md` there).

## Consequences for the lever program

- L4 is re-scoped to **response legibility** (degradation notice, payload-visible previews,
  follow-up affordances) with two items now causally evidenced; L2/L3 remain demoted.
- 624 Step-2 (model-tier sweep) is the sharpest paid discriminator: the forensics predicts hop-2
  execution improves with model tier (~$10–20 at sonnet for a 20-cell B-arm). Owner spend call.
- Retrieval quality: real but smallest bucket (2/16); q8/q14 become regression queries for the
  legal-retrieval work (708/712/713) when it starts.
- Next design step: response-legibility design pass (research → theorize → design → plan) on
  this evidence, in worktree `725-response-legibility`.

---

# Research pass #2 (2026-07-14; response legibility — 3 parallel lanes, cited summaries only)

> Scope: tool RESPONSE shapes as behavioral steering — the surface the forensics made binding.
> Prior passes covered descriptions/discovery/spec-priority; none of that is re-covered. No
> external code or text copied into the repo.

## Lane 1 — Anthropic first-party guidance (the binding constraint)

- **Imperative instructions inside tool results are officially warned against**: Claude treats
  tool-result content as untrusted data; command-shaped steering "may be ignored or flagged as a
  potential injection" (platform docs: Handle tool calls / Mitigate jailbreaks). Symptom: refusal
  or confirm-with-user on instructions originating in results.
- **Descriptive/contract-shaped in-result guidance is officially RECOMMENDED**: truncation and
  error responses should carry "helpful instructions" and can "directly encourage agents to
  pursue more token-efficient strategies" ("Writing effective tools for AI agents", engineering
  blog). Claude Code's own Read tool ships the pattern verbatim: "content (X tokens) exceeds
  maximum… use offset and limit parameters…".
- **Net design rule: the hint grammar must be DESCRIPTIVE (facts about the result and its
  limits), never imperative (commands about the next call).** This is the single most
  design-constraining finding of the pass.
- Token-efficient shapes have first-party precedent: a `response_format: concise|detailed` enum
  cut a worked example from 206→72 tokens; "return only high-signal information"; pagination/
  filtering/truncation with sensible defaults. "Code execution with MCP" (2025-11) is the
  results-bypass-context endgame (98.7% reduction in their example) — out of scope for us now,
  direction confirmed.
- Actionable-error guidance exists ("what went wrong and what Claude should try next"); Claude
  retries invalid calls 2-3 times with corrections. **Degraded-but-successful signaling: NOT
  FOUND in any official material** — genuine gap.

## Lane 2 — MCP spec + ecosystem (greenfield confirmations)

- Current spec: `isError` is binary (execution error vs protocol error); `structuredContent` +
  `outputSchema` exist (SHOULD mirror into text block); content annotations
  (`audience`/`priority` 0-1) are legal on every content block **but purely descriptive — no
  client behavior mandated, and no evidence Claude Code/Desktop/Cursor honor them.**
- **The ~2026-07-28 revision does not touch result semantics we'd design against** (verified via
  the RC post + SEP list): `structuredContent` widens to any JSON value (helps us);
  initialize→server/discover confirmed (655's carrier migrates, already parked); new
  Tasks/InputRequired/CacheableResult are orthogonal. **No partial-success/degraded status exists
  in current or draft spec.**
- **Degraded-mode practice: NO convention exists** (GitHub MCP server documents a hard
  binary; reference servers have nothing). Result-shape conventions (snippet length,
  highlighting, verbosity params, "N more" affordances): none established across
  Exa/Tavily/Brave — per-call pagination is not even spec'd (cursors are list-ops only).
  **We would be establishing patterns, not conforming to or breaking any.**

## Lane 3 — Multi-hop weak-agent literature (evidence tiers for the levers)

- The shortcut failure is real but **not unified under one named phenomenon**: pieces exist —
  lexical-overlap "reasoning shortcuts" (ACL 2019, arXiv:1906.07132), premature/over-extended
  chain termination correlating with wrong answers + explicit model-size gradient
  (AgenticRAGTracer, arXiv:2602.19127), late bridge-entity resolution mechanistically
  (arXiv:2402.16837), path-execution vs path-discovery split (WebDetective, arXiv:2510.05137).
  Our per-cell data (9 bridge-entity answers with a size gradient implied by the 3 correct
  traces) is genuinely novel evidence — noted for 719 someday, no claim now.
- **Best-evidenced tool-side lever: match quality + snippet/recognition support**, not
  suggestion text — hybrid dense+sparse for paraphrase chains (arXiv:2606.21553), listwise
  reranking ~+6 nDCG@10 (arXiv:2501.09186), attention-steering to the right passage +11.5% in
  low-visibility positions (arXiv:2601.12499). Matched-span-centered snippets are classic IR
  (query-biased summaries) but **untested on LLM agents** — low-risk, evidence-adjacent.
- **The "suggested follow-up query" affordance is literature-UNTESTED for LLM agents in both
  directions** (benefit and over-steering cost). IRCoT-style interleaving (+15 QA pts, holds for
  small models, arXiv:2212.10509) proves the *mechanism* (structured next-lookup framing helps
  weak models) but is harness-side. Treat the tool-side variant as a HYPOTHESIS our own
  pre-registered A/B must test, not a design fact.

## Net effect on the lever program

1. **L4a (degradation notice)** — proceed; greenfield everywhere; shape it as a descriptive
   notice at the TOP of the result (+ `structuredContent` status field when we adopt the
   widened schema), never as an instruction.
2. **L4b (payload-visible previews)** — proceed; strongest evidence-adjacent lever
   (query-biased/matched-span snippets + the demonstrated rank-1 truncation cause); follows the
   first-party truncation-notice grammar.
3. **L4c (`response_format: concise|detailed`)** — proceed; first-party precedent, direct
   token ROI on the 10KB-dump and preview problems.
4. **L4d (follow-up/gap-statement affordance)** — hypothesis tier: must be phrased as a
   descriptive gap statement ("this document names engineer X; no value information appears in
   it"), and ships only through a pre-registered A/B with over-triggering negative controls
   (the thinnest-evidenced intervention in the literature).
5. **Do not build on content annotations** (`priority`/`audience`) for behavior — unenforced
   metadata; may emit them as optional extras only.

---

# Theorization (2026-07-14; before design — response legibility)

> Owner constraint (2026-07-14): fixes/improvements must be **model-agnostic in the first
> place** — the MCP surface serves arbitrary clients and models; nothing may depend on one
> vendor's model behavior to work.

## The model-agnosticism axiom sorts the lever space

Levers differ in *mechanism universality*, and the axiom ranks them:

- **Tier 1 — content-honesty levers** (degradation notice, truncation notice with remedy,
  payload-visible previews, "what this result does not contain"): work by giving *information*
  any model can use or ignore. Universal by construction. These ship on evidence.
- **Tier 2 — economics levers** (`response_format: concise|detailed`, result-size defaults,
  pagination affordances): token cost is universal across vendors. Ship on evidence.
- **Tier 3 — behavioral-steering levers** (gap statements shaped to induce the next hop,
  follow-up suggestions): depend on how a given model *reacts* to result content — exactly the
  cohort-relative territory (Anthropic docs warn imperative in-result text may be flagged as
  injection; other vendors differ). These are hypothesis-tier per the research pass AND
  model-relative per the axiom: they ship only through multi-cohort pre-registered A/Bs, if ever.

Convenient consequence: the descriptive-not-imperative grammar the research pass found for
Claude is also the *lowest-common-denominator safe shape* across vendors — facts about the
result are never injection-shaped, never vendor-specific. The axiom and the research converge
on the same grammar.

## Framings worth keeping

1. **Information scent (IR/HCI).** The three failure buckets are all scent failures: preview
   truncation cuts the scent trail mid-sentence; the degraded answer dump dilutes scent under
   10KB of noise; the missing second hop is a trail that ends without pointing anywhere. Response
   design = scent engineering. This framing imports a mature literature (query-biased snippets)
   without importing model assumptions.
2. **Weakest-cohort design (curb-cut effect).** Designing result shapes for the weakest common
   agent helps strong agents too — payload-visible previews save the strong model a Read call
   (tokens), not just the weak model a failure. No lever should *cost* strong cohorts to help
   weak ones; that's the acceptance frame for every Tier-1/2 change.
3. **Capability absorption (the deep alternative).** Instead of teaching weak agents to hop,
   the product can absorb the hop: `justsearch_answer` with the local LLM online is *supposed*
   to do retrieval-augmented answering — if its RAG loop resolved entity chains internally
   (multi-hop decomposition inside the product), agent capability would stop mattering for this
   task class entirely. This is the most model-agnostic fix possible and the only one that
   converts the 9-cell shortcut bucket directly. Open questions: does answer-with-AI already
   handle 2-hop on this corpus (cheap local probe: `ai_activate` + the 20 questions, no agent
   loop, ~$0)? What are latency/VRAM costs? This is a bigger work item that likely routes to the
   search/RAG tempdocs, but the *probe* belongs here — it decides whether response legibility or
   answer capability is the binding product fix.

## Explore-before-implementing: the degradation machinery already exists

JustSearch already models degradation honestly — `LifecycleReasonCode`, `SearchReasonCode`,
readiness composites, and the `searchTraceExplain` projection (gate-paired via
`check-search-degradation-reason-codes`). The UI surface consumes it; **the MCP result surface
does not**. L4a (degradation notice) is therefore a *projection of existing reason codes into
tool results*, not new status machinery — the `aiFeatures: DEGRADED / inference.offline`
composite that the status endpoint already reports is exactly what the answer tool should have
said. Similarly, any "why did this match" rationale in results must be a **projection of the
canonical `SearchTrace`** (execution-surfaces register; the gate fails unregistered
referencers). SPLADE expansion terms are interpretable and already flow through the trace —
a match-rationale line ("matched via: archive≈records-vault") has a canonical source. Design
rule: every new response field names its canonical source or it doesn't ship.

## Ideas recorded for later (not design commitments)

- **Structured results**: `structuredContent` with `matched_span`, `gaps`, `degraded: reason`
  fields once the widened schema (2026-07-28 revision) is adopted; prose mirrors remain for
  compatibility. Machine-readable beats prose for agent parsing, vendor-neutrally.
- **Session-scoped redundancy notice** ("~80% of these results overlap your previous query") —
  the thrash cells re-searched near-identical queries 8-16×; a redundancy fact is Tier 1 and
  directly token-saving. Constraint: needs per-session state the MCP server already has; must
  degrade gracefully for stateless clients.
- **Total-hits / "N more results" affordance** in every search result (cheap, universal scent).
- **Query-side affordance** as an alternative to response-side steering: a search parameter
  shaped for entity follow-up. Rejected for now (schema-minimal principle, ADR-0015; adds
  surface area for a behavior the response can carry), recorded so it isn't re-invented.

## Hidden assumptions surfaced

1. **"The response is the only lever"** — false; parameter schemas and the answer tool's
   internal capability (absorption) are levers too. The design should say why response-side is
   first (cheapest, no protocol change, no inference cost).
2. **"Agents read previews"** — partially verified at best; the forensics shows Read calls
   following searches, but not which preview drove them. The A/B must measure recognition, not
   assume it.
3. **"Our synthetic 2-hop task generalizes"** — Goodhart risk: engineering responses to pass
   *this* eval's designer-code chains would be overfitting. Guard: every lever must be justified
   by a task-generic mechanism (honesty, scent, economics) and never reference task idioms;
   the eval only *measures*.
4. **"Notices are free"** — they cost tokens on every call. Keep Tier-1 notices one line,
   conditional (only when degraded/truncated), and count their cost in the A/B.
5. **Echo-injection surface** — reflecting query text or matched terms back into results creates
   a channel where corpus/query content masquerades as tool voice (same class as the
   `toolsearch_targets` capture leak the level-1 review caught). Reflected content must be
   clearly attributed/quoted, never phrased as the tool speaking.

---

# Settled design #2 (2026-07-14): response legibility — self-describing results by projection

## Two corrections from design-time probes (record before the design)

1. **The "AI-offline silent degradation" diagnosis is CORRECTED.** `justsearch_answer` has no
   LLM branch at all — `callAnswer` (`McpToolSurface.java:410-512`) calls
   `DocumentService.retrieveContext` and never touches inference state; a live probe with the
   local LLM fully active returned the identical passage dump in <1s. The tool is an
   **evidence-pack tool by construction** (its description says "get evidence"), which is
   defensible and model-agnostic — the calling agent owns synthesis. The real defects: the pack
   does not *say* what it is, its curation quality is suspect (below), and it is token-heavy.
2. **Two new defect candidates found by the probe** (investigate during implementation; fixes
   may route to search-quality/RAG if deep): (a) **constant-leader anomaly** — the same document
   (`3875907.txt`) led the evidence pack for four unrelated queries; (b) **format discrepancy** —
   `callAnswer` sets `ContextFormat.XML` (`:435`) but live output shows the LABELED
   (`[From: …]`) format (`ContextBudgeter.java:95`) — a wrong-gate-shaped mismatch between the
   parameter set-site and observed output. Also: evidence-pack selection did not include the doc
   `search` ranks #1 for the same query (fusion discrepancy between the two tools' retrieval).

## Design statement

Every MCP tool result becomes **self-describing** — it states what it is, what was elided, what
was degraded, and (for search hits) why it matched — built almost entirely by **projecting data
the call site already holds** onto the text and the existing registered `structuredContent`
projection (`McpEvidenceProjection`, execution-surfaces entry `mcp-evidence-projection`). No new
status machinery, no new retrieval machinery, no imperative text anywhere (research pass #2
grammar rule; model-agnostic axiom). Three increments:

### D1 — Search result legibility (Tier 1; data in-hand)

- **Match-centered previews**: render the preview from `hit.excerptRegions()`/`matchSpans()`
  (populated by `HighlightingOps`, silently dropped today at `callSearch`) instead of the head
  of the stored `content_preview` field; center the ~200-char window on the best match span so
  the payload sentence is visible at rank 1. `content_preview` head remains the fallback when no
  spans exist. When the window cuts content, say so descriptively with the remedy (first-party
  truncation-notice grammar: full text via the path).
- **Match rationale**: one attributed line per hit from `matchSpans[].term` +
  `matchedFields` (e.g. `matched: "archive", "courthouse" in content`) — quoted as corpus terms,
  never tool voice (echo-injection guard). Dense/semantic-only matches state that fact instead.
- **Degradation line (conditional)**: when `SearchTrace.Degradation` reports
  `vectorBlocked`/`hybridFallback`, one descriptive line ("semantic ranking degraded:
  <reason>; results are keyword-ranked") — the MCP projection of the same canonical trace the
  UI's `searchTraceExplain` already consumes.
- **Coverage line**: "showing N of <totalHits>" when hits exceed those shown (totalHits is
  already in-hand; no cursor/schema change — the wire `next_cursor` stays unexposed for now,
  schema-minimal).
- structuredContent: extend `McpEvidenceProjection.searchEvidence` with matchSpans /
  matchedFields / degradation — extending the registered projection, not forking it.

### D2 — Answer tool honesty (Tier 1/2)

- **Pack self-description header**: one descriptive line — kind ("evidence pack, no synthesized
  answer included"), size (N passages from M documents), and selection basis — so the weakest
  agent knows what it holds and the strongest stops re-calling it expecting synthesis.
- **Curation verification**: resolve the constant-leader anomaly and the XML/LABELED
  discrepancy; verify pack selection against search ranking for the same query (the probe's
  missing-#1 case). Fixes land here if shallow (parameter/config wiring), route to
  search-quality with an inbox/tempdoc pointer if deep (fusion redesign).
- **Token economics**: `response_format: concise|detailed` on answer + search (first-party
  precedent, ~3x cuts measured externally); concise trims passage count/length and relies on
  the self-description + coverage lines. Schema addition ⇒ TOOL_SURFACE_VERSION bump ⇒ new
  measurement cohort by construction (declared, not fought).

### D3 — Error legibility (the pre-registered L4 backlog, revalidated)

The 8-item backlog stands with item 3 (preview truncation) absorbed into D1. Priority order
re-ranked by forensics evidence: generic exception fallbacks get classification + next-state
pointer (`justsearch_status`), "Knowledge server not available" gets transient-vs-permanent
framing, the unlogged catch at `McpToolSurface.java:386-389` gets logged. All error text follows
the actionable-error grammar (what failed, what state to check) — descriptive, never imperative.

## Measurement (pre-registered before any cell runs; 624 protocol)

Baseline vs D1+D2 response shapes on the CLERC member, funnel + completed-cell accuracy +
tokens/cell + trajectory length, with over-trigger negative controls (queries where retrieval
should NOT be reinforced). Dual cohort (haiku + sonnet) if budget allows — the model-agnostic
axiom makes single-cohort evidence structurally insufficient for shipping Tier-3 levers, and
desirable even for Tier-1/2. Ship decisions stay owner-gated; L4d (gap-statement affordance)
remains hypothesis-tier and is NOT in this design's ship set.

## Orphans (owned by this design's implementation)

1. The forensics section's "AI-offline silent degradation" diagnosis — corrected above (the
   tempdoc is append-only; the correction supersedes, the original stays as dated history).
2. `content_preview`-head as the sole preview source — superseded by match-centered rendering
   (kept as fallback only).
3. L4 backlog item 3 — absorbed into D1.
4. The unlogged catch at `McpToolSurface.java:386-389` — fixed in D3.
5. If `ContextFormat.XML` turns out to be silently unhonored, the dead parameter is the orphan —
   fix or remove with the D2 curation work.

## Design reach

- **Conforms to (projection, not invention)**: `McpEvidenceProjection` (registered
  execution-surface — this design is the completion of the register's own "MCP historically
  dropped the evidence" reverse-coverage note, tempdoc 658); the canonical `SearchTrace` (the
  degradation line is its third consumer after FE explain + OTel); the product's reason-code
  honesty layer; ADR-0015 schema-minimalism (one new enum param total); 655's single-sourced
  steering (new lines are per-result facts, not a second guidance channel — the initialize
  instructions text is untouched).
- **Principle promoted from theorization**: **self-describing results** (defined below) — the
  MCP surface was the violation; D1-D3 is its remediation. It earns its keep when the A/B shows
  recognition/token improvement without over-trigger regression, and retires into protocol
  fields if MCP ever standardizes result-provenance/degradation metadata.
- **Recurring shape worth naming (not building)**: **"dropped at the boundary"** — data computed
  upstream (matchSpans, trace degradation, totalHits context) exists on the exact object a
  boundary holds, and the boundary's projection silently discards it; each such drop is a
  legibility defect candidate. Candidate scope: every projection listed in
  `execution-surfaces.v1.json` (audit: does each projection carry the fields its consumers'
  failure modes need?); the agent-api HTTP responses. Evidence of earning keep: a second
  boundary audit finding a same-shaped drop; retirement: if the register's coverage checks grow
  a completeness dimension that mechanizes this, the prose shape retires into that gate.

---

# De-risking pass #2 (2026-07-14; design #2 — static reads + clean-base live probes)

Evidence: `scripts/jseval/tmp/725-ab/725-forensics/derisk-live-probe.v1.json` (+ static reads
with file:line below). Stack built and launched FROM this worktree's dist (`distFrom`), fresh
ingest — the earlier probes' dist provenance concern (U4) was real and mattered (below).

## Per-uncertainty verdicts

- **U1 (spans/excerpts populated on the search path) — CONFIRMED end-to-end, with a quality
  catch.** `includeExcerpts: true` through the HTTP body reaches the worker and returns
  `excerptRegions` with REAL TEXT windows (lexical probe: the excerpt contains the exact payload
  sentence "Cavby8 is associated with azure vellum 0008"). `matchSpans` populate on the hybrid
  path without any flag. **The catch: for paraphrase hits the spans are stopword noise** — q3's
  rank-1 hit carried 20 spans of "the", and the worker's excerpt window followed the junk to an
  irrelevant passage. Naive projection is NOT enough for the flagship paraphrase case: D1 needs
  informative-term filtering (drop stopwords/ubiquitous terms before rationale/centering) and an
  honest "semantic match — no distinctive term overlap" fallback. Worker-side excerpt scoring
  (`HighlightingOps` window selection) is the deeper fix; head-side filtering is the level-1
  scope, the worker-side change routes to search-quality if level-1 proves insufficient.
- **U2 (offset semantics) — RESOLVED, favorable.** Spans offset into the stored field VALUE
  (`content_preview`, capped 4096 chars — `IndexingDocumentOps.java:430`), so head-side
  centering has 4KB of text to work with; `excerptRegions` carry text extracted worker-side from
  FULL content (`HighlightingOps.java:203-205`) with nested spans relative to the excerpt
  (`indexing.proto:232`). Head never sees full content (`SearchResponseBuilder.java:403-411`
  excludes it) — excerpts are the only full-content window, and they already exist.
- **U3 (constant-leader anomaly) — NOT REPRODUCIBLE on the clean base.** With the worktree dist
  + fresh ingest, the three probe queries had three different pack leaders. The anomaly was an
  artifact of the earlier probe environment (foreign-branch dist from the main checkout and/or
  its index state) — dropped from the defect list. Pack curation on legal text remains weak
  (leaders are irrelevant CLERC docs; bestChunkScore ~0.03, coverage ~0.60) but that is the
  known F-029/legal-retrieval territory, not a new MCP defect. The `quality` block is rich and
  in-hand — D2's self-description should surface it (a "low-confidence evidence pack"
  descriptive line derives directly from bestChunkScore/coverage).
- **U4 (probe validity) — CONFIRMED as a real concern, now retired.** Clean-base rerun changed
  one conclusion (U3). **The XML/LABELED discrepancy SURVIVES the clean base**: `callAnswer`
  sets `ContextFormat.XML` (`McpToolSurface.java:435`) and live output is LABELED `[From: …]` —
  a genuine wrong-gate defect (parameter set-site does not govern the output path); confirmed
  D2 fix item.
- **U5 (pinned-test blast radius) — SMALL and located elsewhere.** ~10-12 methods pin the
  *agent-internal* `SearchTool` format (plus a PRODUCTION regex dependency:
  `AgentContextCompressor.java:36` hard-depends on `SearchTool`'s "Excerpt:" label — do NOT
  touch SearchTool in this work). `McpToolSurface.callSearch`'s own text block has ZERO pinning
  tests — MCP shape changes are test-free but must ARRIVE with new shape tests.
  `McpProtocolHandlerTest:220` pins the search schema's property keys — expected single break
  when `response_format` lands.
- **U6 (A/B comparability) — GAP CONFIRMED, one small work item.** `exposure_contrast` matches
  only corpus/model/query identity (`exposure_contrast.py:45`) and never reads
  `mcp_tool_surface_hash` — a cross-surface-version contrast would compute silently. The
  response-shape A/B needs an explicit surface-aware comparison mode (declared
  surface-identity echo + guard), added to the implementation list.
- **U7 (version bump) — LOW risk.** Constant is single-sourced (`McpContractVersions.java:40`);
  prior bump (0.1.0→0.2.0, `5779c48`/PR #87) shows the shape: version constant + guidance text +
  handler tests + `mcp-production-server.md`. No public-projection consumers (ABSENT in
  `check-public-agent-utility`).

## Design adjustments out of derisk (no redesign; scope refinements)

1. D1 gains: informative-term span filtering + semantic-match fallback line; excerpt-window
   quality noted as a possible worker-side follow-up (search-quality routed).
2. D2 drops the constant-leader item; gains the confirmed XML-format wrong-gate fix; gains the
   quality-block-derived confidence line.
3. New implementation item: surface-aware contrast mode/guard in `exposure_contrast.py`.
4. MCP response-shape tests are green-field — write them with the new shapes (no legacy pins to
   preserve on the MCP surface itself).

## Confidence: 8/10

The design's substance is probe-verified end-to-end (data exists, flag works, text windows
real, bump cheap, test blast radius small and avoidable). Held back from 9+ by: the span-noise
filtering heuristic is judgment-quality work whose sufficiency is only provable by the A/B; and
the excerpt-window scoring may yet need the worker-side change (bounded, but cross-module).

## Difficulty and recommended staffing

Moderate. Concentrated sites: `McpToolSurface.java` (+ its new tests), `McpEvidenceProjection`,
one request flag, `ContextBudgeter`/format wiring fix, `exposure_contrast.py` guard, version
bump. **Recommendation: sonnet (medium-high effort) implementation workers on bounded increments
(D1 text/projection; D2 header+format fix; D3 error grammar; U6 guard) under main-loop briefs;
one opus (high effort) refute-first reviewer before any commit; pre-registration wording and
response-text style decisions stay main-loop.** The span-filtering heuristic brief must include
the probe's noisy-span examples as fixtures.

---

# Level-2 implementation: self-describing results (2026-07-14; complete on branch, unpushed)

Branch `worktree-725-response-legibility` (base `bc4b26f`), commits `bf5cf59` (W1+W4),
`7f3a047` (W2+W3), `1263c64` (review fixes), `a9dc976` (anchor heuristic). Orchestration: 4
sonnet implementers + 1 opus refute-first reviewer; briefs, wording, register/version decisions,
evidence judgment, commits, and stack lease stayed main-loop.

## What landed

- **W1 — search legibility.** `includeExcerpts` requested (one flag, worker excerpts flow
  end-to-end); previews anchor on the informative-span-coverage-maximizing window (occurrences,
  not deduped terms; ties prefer later spans — the title-echo case found live); `Matched:
  "term" in <fields>` rationale (quoted + sanitized incl. quotes/backslashes) with a
  semantic-similarity fallback line; conditional degradation note projected from
  `SearchTrace.Degradation` (registered: `mcp-search-text-degradation` in
  `execution-surfaces.v1.json`; gate green); `Found N…; showing K.` coverage; structuredContent
  gains matchedTerms/matchedFields/excerpts/degradation. New `McpSearchResultFormatter`
  (pure helpers) + 18-test shape suite (the MCP text shape previously had zero pins).
- **W2 — answer honesty.** `Evidence pack: N passages from M documents (retrieval mode: X). No
  synthesized answer is included.` header (+ truncation sentence when contextTruncated;
  fallback-path counts derive from sections so the header can never say 0/0 above rendered
  docs — review MAJOR); the `ContextFormat.XML` wrong-gate resolved by evidence (worker never
  reads the field; no XML renderer exists) — LABELED now requested explicitly, dead XML request
  removed (orphan #5), pinned by a call-site regression test AND an e2e
  `ContextFormatIsIgnored` test that documents the wire field's deadness; `response_format:
  concise|detailed` on both tools (detailed = prior shape; concise: answer ≤3 passages @600
  chars ⇒ ~4x smaller live, search omits Preview); TOOL_SURFACE_VERSION 0.2.0→0.3.0;
  `mcp-production-server.md` updated.
- **W3 — error legibility.** All 5 generic catch sites + 3 "Knowledge server not available"
  sites use actionable descriptive grammar with a `justsearch_status` state pointer;
  the runtime-manifest catch now logs (orphan #4).
- **W4 — measurement guard.** `exposure_contrast` fails closed on
  `mcp_tool_surface_hash`/`server_version` mismatch unless `surface_contrast=True`, which
  echoes both surface identities into the output (cross-surface comparisons are self-describing,
  never silent).

## Review + validation

Opus refute-first review: 1 MAJOR (fallback header 0/0 — fixed + producer-mirrored fixture),
1 MINOR (quote-escaping — fixed), 3 NOTEs recorded: raw excerpt text keeps the pre-existing
raw-echo posture (not a regression); the anti-imperative test assertion is a narrow blocklist
(grammar is enforced by review, not that assertion); detailed-mode adds ~600-800 tokens per
10-hit response vs the old shape (the recorded cost of provenance; concise exists for economics).

Live validation on the worktree dist (evidence:
`scripts/jseval/tmp/725-ab/725-forensics/live-validation-0.3.0.{v1,v2,v3-anchorfix}.json/txt`):
every shape verified live — including, unplanned, on a **genuinely degraded** stack, where the
new note + honest `retrieval mode: BM25` header correctly reported a state that was previously
invisible (twice). Bonus finding logged to observations: the embedding fingerprint appears not
to persist across worker restarts (index re-flags BLOCKED_LEGACY on plain restart) — worker
lifecycle territory, out of 725 scope. Enum validation rejects a bad `response_format` with a
self-describing error (schema-enforced; better than silent default). Known residual: hybrid
ranking on a rebuilt index shifted (druker7 1→outside MCP top-10 for the q3 paraphrase) —
retrieval-quality territory (F-029/708/712/713), explicitly NOT a response-shape item.

Suites: full `gradlew test` BUILD SUCCESSFUL; full jseval pytest 1879 passed (only the 2
known-red `test_correction_probe` pre-existing failures); execution-surface +
register-guard-resolution + suppression-ratchet gates green.

# Response-shape A/B — pre-registration (2026-07-14, BEFORE any cell runs; run is owner-gated)

- **Arms:** baseline = tool surface 0.2.0 (main, `a8321f6` lineage) vs treatment = 0.3.0 (this
  branch, default `detailed` shapes). Same corpus (`mixed/en-legal-clerc-1k-verbose`), same 20
  committed queries × seed 0, B-condition, same limits as the exposure smoke. Comparison via
  `exposure_contrast(..., surface_contrast=True)` (the W4 mode built for exactly this).
- **Cohorts:** haiku (primary); + sonnet arm if the owner funds dual-cohort (~2x) — the
  model-agnostic axiom makes single-cohort evidence weak for any Tier-3 conclusion; Tier-1/2
  ship decisions may rest on haiku + the sanity checks.
- **Metrics (primary):** funnel (discovery/invocation/reinforced), completed-cell substring-EM
  accuracy, tokens/cell (cost_usd + usage counters), trajectory length (num_turns,
  tool_call_sequence length). **Negative controls:** the A-condition arm (no tools — restored
  baseline post-#605-fix) and spurious-reinforcement check (MCP call share should not balloon
  without accuracy movement).
- **Budget:** from the measured ~$0.22/cell: ≈$9 per campaign per model (40 cells incl. A-arm);
  hard cap USD 12/campaign, abort if projected over. Stop conditions: cap, or a substrate
  capture defect (fix-and-rerun).
- **Signal bars (interpretation guide, not ship triggers):** response shapes earn Tier-1/2 ship
  if accuracy improves OR tokens/cell drop materially, with no reinforcement-stage regression
  and A-arm invariance; a null result on accuracy with a token win still supports concise-mode
  and header/notice shipping (economics + honesty are their own justification); bars on exact
  thresholds set with the owner at authorization, before results are seen.

# Response-shape A/B — results and judgment (2026-07-14; owner-authorized "small spend"; Campaign T run)

Small variant executed per authorization: ONE treatment campaign (T: surface 0.3.0, this branch,
haiku, conditions A+B, 20 queries × seed 0), contrasted descriptively against Campaign D
(0.2.0) as the pre-registered baseline arm. **Spend: $9.38** (A $4.93 + B $4.45), inside the $12
cap. All 40 cells completed (Inspect status success, resumed compose after a backend death —
below). Evidence: `scripts/jseval/tmp/725-response-ab/` (worktree) + copies in
`scripts/jseval/tmp/725-ab/725-forensics/`.

## Instrument limitation found first (recorded for 624)

Campaign D's composed record carries `measured: {}` (its A-arm was voided at record time by
pre-fix #605 — baked into the log, unrecoverable by recompose), so `exposure_contrast` —
including the new `surface_contrast=True` mode — can never consume it: the instrument requires
measured cells. The cross-campaign comparison below is therefore a **descriptive raw-log
table**, explicitly not the instrument (`descriptive-contrast-D-vs-T.v1.json` carries the same
caveat inline). Confounds declared up front: index rebuilt between campaigns (ranking variance
demonstrated during validation), single seed, one cohort.

## Results (completed cells; descriptive, smoke-scale)

| arm | n | completed | correct | acc | med cost | med turns | med cache-tokens | med MCP calls | discovery |
|---|---|---|---|---|---|---|---|---|---|
| D 0.2.0 B | 20 | 19 | 3 | 15.8% | $0.217 | 18 | 58.5k | 8 | 20/20 |
| **T 0.3.0 B** | 20 | 19 | 4 | **21.1%** | $0.217 | 16 | 67.5k | 9 | 20/20 |
| T 0.3.0 A (baseline) | 20 | 16 | 1 | 6.2% | $0.411 | 26 | 95.0k | — | — |

- **Response-shape effect (D-B vs T-B):** accuracy +5.3pp (+1 cell — direction positive, far
  below significance at n=19), median turns 18→16, cost flat, **cache tokens +15%** (the
  review-predicted provenance cost of detailed mode: Matched lines + larger previews). Adoption
  stayed at ceiling (20/20 discovery, MCP call counts stable) — no reinforcement regression, no
  over-triggering.
- **Judged against the pre-registered bars: in-between → reported as-is.** Accuracy improved
  but weakly; tokens did NOT drop in default mode. ~~No agent passed concise~~ **CORRECTED by
  the deep analysis (below): agents used `response_format` in 5/20 B-cells unprompted (5
  concise + 17 detailed values across their calls)** — the schema description alone induced
  organic adoption in 25% of cells; the default-flip/guidance/leave decision stands but with
  better priors than first reported.
- **The strongest new datum is A vs B (first valid baseline on this corpus, courtesy of the
  #605 fix working live — zero voided A-cells):** with-tool beat without-tool 21.1% vs 6.2%
  accuracy at HALF the median cost ($0.217 vs $0.411), 10 fewer median turns, and 29% fewer
  cache tokens, with fewer timeouts (1 vs 4). Descriptive and smoke-scale — but it is the
  first time the program has measured the tool actually *paying for itself* on a grep-stressed
  corpus, and it materially strengthens the case for the powered 624 campaign.

## Operational finding

Second unexplained backend death under sustained MCP-only load, at/near campaign end (all cells
already complete; caught by the post-eval surface re-capture; resume-on-same-port recovered
compose cleanly). Observation logged; dev-runner lifecycle territory, not 725 scope.

## Deep analysis of Campaign T (per-cell forensics; evidence `725-forensics-T/` + copies in the evidence dirs)

- **The hop-2 reasoning failure is UNTOUCHED by response shapes — and it is tool-independent.**
  T-B: 11/20 hop1-found-no-hop2 (identical to D-B's 11). Decisive new datum: **T-A shows the
  same failure through grep** (7/20 A-cells found the hop-1 code via file tools and stopped) —
  the second-hop failure is a model-capability property of haiku, not a property of the tool
  surface. No response furniture can be expected to fix it; the candidates remain L4d
  (hypothesis), answer-side hop absorption, or model tier (624 Step-2).
- **The +5.3pp accuracy delta is churn, not signal.** 5/20 B-queries flipped between campaigns
  — 3 I→C (q6, q16, q18) but ALSO 2 C→I (q9, q10, D's two hop-2 executors). Single-seed
  smoke-scale accuracy comparisons on this corpus are churn-dominated; no ship decision may
  rest on the accuracy delta without seeds ≥3 or larger n.
- **The strongest behavioral effect of the new shapes: Reads-per-search HALVED** (D 1.04 →
  T 0.60 pooled) — richer previews demonstrably reduce file opening. But bigger responses cost
  more than the saved Reads on this corpus (+15% median cache tokens): the preview-enrichment
  economics depend on document size and currently net negative here. Concise mode (organically
  adopted, 5/20 cells) is the counterweight.
- **Zero completions quote the new furniture** ("Matched:"/"Evidence pack"/etc.) — agents act
  on the shapes (Reads halved) without citing them; furniture-engagement cannot be measured
  from completions, only from behavior (and result-content capture remains absent — twice
  bitten now).
- **Funnel (T-B, measured block):** discovery 1.0, invocation 1.0, first discovery turn 1.0,
  reinforced_proxy 0.933, strict reinforced 0.867 — reinforcement healthy, no ejection under
  the new shapes.

## Consolidated remaining-issues inventory (2026-07-14, post-A/B)

**Product (JustSearch):**
1. Hop-2 execution failure at haiku — model-level, tool-independent (see above); open levers:
   L4d gap-statement (hypothesis-tier, own A/B), answer-side entity-chain absorption
   (theorization alternative, unbuilt), model-tier (624 Step-2).
2. Evidence-pack curation on legal text: packs led by irrelevant docs, pack selection disagrees
   with search ranking (fusion discrepancy) — F-029 / 708/712/713 territory.
3. Preview-enrichment token economics net negative on small-doc corpora (+15% despite halved
   Reads) — owner decision: concise default flip vs client guidance vs leave (organic adoption
   priors now known: 25% of cells).
4. Hybrid ranking instability across index rebuilds (druker7 rank 1 → >10 on same corpus) —
   retrieval determinism question, unowned.
5. Embedding fingerprint not persisted across worker restarts → BLOCKED_LEGACY + silent dense
   loss on every restart (observation logged; worker lifecycle).
6. Backend death under sustained MCP-only load, 2 occurrences (observation logged; dev-runner).
7. Raw excerpt/preview text unsanitized (review NOTE; pre-existing posture, echo-adjacent).
8. `resource()` Map.of ordering residual from level-1 (trivial).

**Measurement/harness (624-owned):**
9. Tool RESULT CONTENT not captured in Inspect logs — structural; blocks
   retrieval-vs-synthesis classification and furniture-engagement analysis (bitten twice).
10. Executed-call ERROR PAYLOADS invisible (ok/blocked binary only) — error-ejection can never
    be ruled out from logs.
11. Pre-#605 records (`measured:{}`) permanently unusable by `exposure_contrast` — Campaign D
    can only ever be compared descriptively.
12. Errored/timeout cells lose `num_turns`/`cost_usd` (exit path doesn't populate) — spend
    accounting undercounts by the errored tail.
13. Funnel denominator duality (measured n=15 vs ITT ledger n=16) — primary needs declaring.
14. `question_type: 1_hop` labels on behaviorally-2-hop queries — 707 stratum builder check.
15. Single-seed churn (5/20 flips) — smoke protocol should move to seeds ≥3 for any
    accuracy-based decision.

## Remaining (owner-gated)

1. PR + merge of this branch (no PR opened per instruction).
2. Concise-mode decision (see issue 3).
3. L4d gap-statement affordance: still hypothesis-tier, NOT implemented, needs its own A/B.
4. 624 Step-2 / powered campaign — strengthened by the A-vs-B result AND by issue 1's
   tool-independence evidence (the reasoning gap is exactly what a model-tier sweep measures).

## Candidate principle (from theorization, now adopted by the design): self-describing results

**"Every tool result declares its own nature and limits — what it is, what was elided, what
capability was degraded, and (where canonical data supports it) why it matched."** This is the
reinforcement-stage sibling of the visibility funnel: the funnel got the agent TO the result;
self-description is what lets the result be *correctly used*. It conforms to the product's
existing honesty machinery (reason codes, SearchTrace) by projection, and it is model-agnostic
because it adds information, never behavioral dependence. Candidate scope beyond MCP: the
agent-api HTTP endpoints and the UI's evidence rendering already half-follow it. Retirement
condition: if agent clients converge on protocol-level result metadata that carries the same
facts (spec-level degraded/provenance fields), the prose projections retire into those fields.
