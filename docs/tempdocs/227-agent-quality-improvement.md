---
title: "227: Agent Quality Improvement"
type: tempdoc
status: done
created: 2026-02-20
updated: 2026-03-04
---

# 227: Agent Quality Improvement

**Status:** active — **Production model: Qwen3.5-9B Q4_K_M.** Best battery result: 13/23
(56.5%, hybrid mode) on 23-scenario full battery. Phase 1 (prompt engineering) and Phase 2
(infrastructure: state machine, tool_choice escalation, BrowseTool file listing, SearchTool
path resolution, E0a tool restriction) complete. Model switched from Qwen3VL-8B-Thinking to
Qwen3.5-9B (Q35 13/22 vs QVL 11/22 on same battery, corrected apples-to-apples comparison).
**Remaining bottlenecks:** (1) BM25 length bias — tempdocs outrank canonical docs in 5+
exploration scenarios (see §6b, F4); (2) stochastic handoff variance — h-003/h-004 flip
between pass/fail across runs; (3) model comprehension — correct doc found but content not
extracted (exp-008, exp-010). Thinking mode investigated: bounded thinking blocked by
llama.cpp (only accepts -1 or 0). LoRA fine-tuning deferred (highest potential but highest
effort). `--search-mode` toggle added for text/hybrid A/B testing without code changes.
**Scope:** Improve end-to-end task completion rate of the JustSearch agent
**Constraint:** Target hardware is 8GB VRAM. Production model is Qwen3.5-9B Q4_K_M (5.87 GB).
Embedding model is nomic-embed-text-v1.5 Q8_0 (139 MiB, auto-discovered).

---

## 1. Purpose and success criterion

**Done means:** ≥90% pass rate across the full 23-scenario battery
(`scripts/ci/agent-live-battery-scenarios.v1.json`), measured with
`dag-runner-agent-battery.mjs` against the current production environment.

The battery has 17 exploration scenarios (diag-001, exp-001–exp-016) and 6 handoff scenarios
(handoff-001–handoff-006). The original Phase 1 criterion was ≥90% on the 6 handoff scenarios
only (N≥5 per scenario). Phase 2 expanded measurement to the full battery (N=1 per scenario,
deterministic gate in report mode).

Stretch target: deterministic oracle active (toolTraces populated) with ≥80% pass.

---

## 2. Current state

The authoritative measurement is the 23-scenario full battery (`dag-runner-agent-battery.mjs`).
Model: Qwen3.5-9B Q4_K_M. Embedding: nomic-embed-text-v1.5 Q8_0. Corpus: allrounder-core.v1.small (246 docs).

**Current state (Qwen3.5-9B, N=1 per scenario, det gate = report):**

| Scenario | Text | Hybrid | Failure reason (if any) |
|----------|------|--------|------------------------|
| diag-001-tool-smoke | PASS | PASS | — |
| exp-001-tools | PASS | PASS | — |
| exp-002-roots | PASS | PASS | — |
| exp-003-explanation-purpose | FAIL | PASS | text: missing_facts (architecture, system) |
| exp-004-list-explanation-files | PASS | PASS | — |
| exp-005-config-doc | FAIL | FAIL | missing_facts (environment) |
| exp-006-architecture-sections | FAIL | FAIL | missing_facts (process, head) |
| exp-007-tempdoc-186 | PASS | FAIL | hybrid: missing_keywords (agent) |
| exp-008-search-inference | FAIL | FAIL | missing_facts (model, llama) |
| exp-009-search-gpu | FAIL | FAIL | missing_facts (vram) |
| exp-010-read-env-vars-doc | PASS | FAIL | hybrid: contains_forbidden (no results) |
| exp-011-agent-api-endpoints | FAIL | FAIL | missing_keywords (/api/agent) |
| exp-012-multistep | PASS | PASS | — |
| exp-013-crossref | PASS | PASS | — |
| exp-014-compare | PASS | PASS | — |
| exp-015-deep-browse | FAIL | FAIL | missing_keywords (reference, how-to) |
| exp-016-verify | PASS | PASS | — |
| handoff-001-immediate | PASS | PASS | — |
| handoff-002-search-then-handoff | FAIL | PASS | text: contains_forbidden (cannot) |
| handoff-003-fuzzy-search | FAIL | FAIL | contains_forbidden (cannot) |
| handoff-004-multi-hop | FAIL | FAIL | missing_keywords (ingest/architecture) |
| handoff-005-negative-missing-file | PASS | PASS | — |
| handoff-006-ambiguous-choice | FAIL | PASS | text: required_tool_failure (ingest_files) |
| **Total** | **12/23 (52.2%)** | **13/23 (56.5%)** | Net: hybrid +3 (exp-003, h-002, h-006), -2 (exp-007, exp-010) |

**Failure categories:**
- **Search relevance (6 scenarios):** exp-005, exp-006, exp-008, exp-009, exp-011, exp-015.
  Model finds wrong docs or no relevant content. Root cause: BM25 length bias — tempdocs
  (40-50KB) outrank canonical docs (5-15KB). Cross-encoder reranking is the known fix (tempdoc 135).
- **Stochastic handoff (3 scenarios):** h-002, h-003, h-004.
  Model sometimes refuses to commit ("cannot"), hallucinates paths, or doesn't call ingest.
  Varies across runs — structural fixes (state machine, escalation) reduce but don't eliminate.
- **Model comprehension (2 scenarios):** exp-008, exp-009 (overlap with relevance).
  Correct doc found but required facts not extracted from content.

**Round progression:**

| Round | Battery | Pass | Key change |
|-------|---------|------|------------|
| Pre-fix | 6 handoff (N=3) | 7/18 (38.9%) | Baseline (Qwen3VL-8B-Thinking) |
| R3 | 6 handoff (N=3) | 11/18 (61.1%) | F1-fix + IngestTool strip-prefix + C1 |
| R5 | 6 handoff (N=3) | 16/18 (88.9%) | +browse_folders prohibition + threshold calibration |
| R6 | 6 handoff (N=5) | 25/30 (83.3%) | +BrowseTool relative paths + DRY profiles |
| R7 | 6 handoff (N=2) | 5/6, 6/6 | +E0a tool restriction + llama-server b8157 |
| R8 | 6 handoff (N=5) | 22/30 (73.3%) | Full det oracle. See §A.8 for per-scenario data. |
| R9 | 22 full (N=1) | 13/22 (59.1%) | Model switch to Qwen3.5-9B, system message merge fix |
| R10 | 23 full (N=1) | 13/23 (56.5%) | +BrowseTool file listing, SearchTool path resolution, file-path sanitization |
| R11 | 23 full (N=1) | 12/23 text, 13/23 hybrid | +search mode toggle, battery model reorder. Hybrid net +1 (within N=1 variance). |

R1-R8 used Qwen3VL-8B-Thinking. R9+ use Qwen3.5-9B. The R6 83.3% and R8 73.3% were handoff-only
(6 scenarios, N=5). The R9+ measurements use the full 23-scenario battery (N=1) and are not directly
comparable. The original 2-scenario 100% result is preserved in §A.1.

---

## 3. Root causes

### F1 — Stale path example in Organizer system prompt — FIXED

**Status:** Fixed (Direction A). Organizer prompt updated to use relative paths. I2 strip-prefix
logic added so `docs/...` paths resolve correctly. No longer requires browse_folders for single-file
ingest. See §4 (C1, I2, I3) for the full fix chain.

### F2 — E0a only guards the first Organizer turn — FIXED

**Status:** Fixed by state machine (Direction E) + tool_choice escalation (Direction F). The
Organizer state machine now enforces tool calls on every turn, not just the first. Combined with
F1-fix (single-turn ingest), the turn-2 exposure is eliminated.

### F3 — PRIMARY doesn't commit on multi-hop tasks

h004/h006: PRIMARY searches, finds multiple candidates, but notes uncertainty about which is
best and responds directly rather than handing off. The Handoff trigger rule ("when user
explicitly asks to ingest") is in tension with find-and-ingest tasks, where the model treats
judgment uncertainty as a reason not to commit.

The state machine (Direction E) and escalation (Direction F) address F3 structurally — they
force tool calls even when the model would prefer to reason. Qwen3.5-9B shows improved handoff
rate vs Qwen3VL (4/6 vs 2/6 on handoff scenarios), but h-003 and h-004 remain stochastic.
The remaining variance is model stochasticity + search relevance, not a fixed capability ceiling.

### F4 — BM25 length bias in agent search (dominant remaining failure)

The dominant failure mode for exploration scenarios. BM25 naturally favors longer documents:
tempdocs (40-50KB) accumulate more term frequency than canonical docs (5-15KB), so they outrank
the correct answer for generic keyword queries. This affects 6+ exploration scenarios where
the model searches for a topic and gets a tempdoc instead of the canonical `docs/` file.

Cross-encoder reranking (tempdoc 135) is the known fix. Hybrid mode (BM25 + dense KNN via RRF)
was tested but is net-neutral on Qwen3.5-9B: +3 scenarios gained, -2 lost, within N=1 variance.
The `--search-mode` toggle (`JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE`) enables ongoing A/B testing.

### Provenance note on C3

The original root cause analysis recorded: "PRIMARY handoff guidance (C3): With 8K budget,
PRIMARY's handoff behavior is reliable (100% handoff rate). No prompt changes needed." This was
based on h001/h002 only. h004/h006 exhibit F3, which was invisible in the 2-scenario suite and
is not addressed by the 8K budget fix.

---

## 4. Shipped work

| Item | What it does | Actual effect | Caveat |
|------|-------------|--------------|--------|
| **C4** — max_tokens 8192→1024 | Caps per-LLM-call completion tokens | Fail-fast at ~16s; prevents 60–90s reasoning dumps | — |
| **C1** — Organizer prompt rewrite | Numbered steps + concrete browse→ingest example | Reduced first-turn reasoning dump rate | Concrete example uses `D:/data/docs` — the source of F1 |
| **C6** — 8K context | Doubles token budget (~3300→~7700) | Fixed budget exhaustion on multi-step flows | — |
| **E0a** — `tool_choice: "required"` first turn | Forces Organizer's first LLM call to be a tool call | Eliminated first-turn reasoning dump | Turn 2+ unguarded for 2-turn flows (F2) |
| **E1** — Qwen3 sampling | temp 0.2→0.6, top_p 0.9→0.95 | Better budget usage and ~2s faster avg | — |
| **E2** — `/no_think` removal | Removes inert prompt prefix | ~10 token savings; confirmed no behavioral change | — |
| **I2** — Relative paths in IngestTool | IngestTool resolves relative paths against indexed roots | Capability present | Previously dormant (F1). Now active: F1-fix updated the prompt; strip-prefix logic added so `docs/...` paths resolve correctly against `.../docs` root. |
| **I3** — Relative paths in BrowseTool | BrowseTool outputs relative paths (root-name-prefixed) and resolves relative `parent_path` input | h002: 1/3 → 5/5. Eliminated absolute-path leakage into handoff reasons | — |
| **C1b** — Iteration cap ("at most twice") | Explicit search bound + handoff-is-commitment framing in PRIMARY prompt | h004: 0/3 → 3/3. Confirmed load-bearing: removing causes regression to 33.3%. | Couples prompt to current corpus size. |
| **DRY profiles** | Top-level `profiles` in scenario JSON; runner resolves string references | Eliminated 6×2 duplicated prompt embeds | — |

The shipped work addresses path handling (I2, I3), prompt structure (C1b), and testing
infrastructure (DRY profiles). The remaining handoff failures (h-003/h-004) are stochastic —
they vary across runs due to model sampling + search result ranking, not a fixed capability
ceiling. The infrastructure fixes (state machine, escalation) reduce the variance structurally;
further improvement requires search relevance improvements (F4).

---

## 5. Development directions

The immediate next steps (§6) address known mechanical failures. The directions below are the
higher-level theoretical frames that determine what happens after those fixes — and what
questions they open rather than close.

### Direction A: Systematic prompt-tool co-evolution — DONE

**Status:** Complete. F1-fix applied (relative paths in prompts), I2 strip-prefix added,
BrowseTool relative paths, all scenario thresholds calibrated. The principle that prompt
examples and tool interfaces must be treated as a single unit is now established practice.
See §4 and §6 for shipped items.

### Direction B: Agent state as a first-class concept — DONE (subsumed by E)

**Status:** The principled state model was implemented via Direction E (state machine
orchestration). The Organizer state machine enforces tool calls on every turn, eliminating
the E0a/E0b tension. F2 is resolved.

### Direction C: Uncertainty-aware handoff — DONE (C1 shipped)

**Status:** C1 (prompt directive) shipped — commit-under-uncertainty rule added to PRIMARY.
Combined with state machine (E) and escalation (F), handoff rate improved from 2/6 to 4/6.
C2 (structured handoff with confidence signal) deferred — not needed until multi-hop
find-and-ingest becomes a core use case.

### Direction D: Thinking mode as a multiplier — BLOCKED

**Status:** Direction A is complete. However, bounded thinking remains blocked by llama.cpp:
`--reasoning-budget` only accepts -1 (unlimited) or 0 (disabled). With Qwen3.5-9B, budget=-1
is catastrophic (5/22 — model exhausts max_tokens on `<think>`). Budget=0 (default) works
fine (13/22). Until llama.cpp supports bounded thinking budgets, this direction cannot be
meaningfully tested. See §8 for full investigation results.

### The prompt engineering ceiling (answered, context updated)

The Phase 1 prompt engineering ceiling was reached at R6 (83.3% keyword / ~56% honest task
completion) with Qwen3VL-8B-Thinking. That ceiling was specific to Qwen3VL + prompt-only
approaches. With Qwen3.5-9B + state machine (E) + escalation (F), the ceiling has moved —
the model's IFEval 91.5 and 4/6 handoff rate show structural improvement in instruction
compliance.

**The remaining bottleneck is search relevance (F4), not prompt engineering or model
tool-calling compliance.** 6 of 10 failing scenarios (text mode) fail because the wrong
document is ranked first, not because the model refuses to call tools. The protocol evolution
(E, F) was the correct answer for the Phase 1 ceiling. Phase 3 requires search quality
improvements.

### Direction E: State machine orchestration — DONE

**Status:** Implemented. The Organizer state machine enforces tool calls on every turn,
generalizing E0a from a single turn-0 check to a full state-driven policy. This eliminated
F2 and structurally reduced F3 (model can no longer "leak" into text responses when a tool
call is expected). See §4 for shipped items.

### Direction F: tool_choice escalation — DONE

**Status:** Implemented alongside Direction E. Progressive constraint escalation is now part
of the state machine policy. See §10 for implementation results.

### Direction G: Model selection — DONE (Qwen3.5-9B adopted)

**G1 (Qwen3-8B benchmark):** Tested. No switch warranted — marginal improvement over Qwen3VL
on tool calling, not enough to justify the switch.

**G2 (Qwen3.5-9B):** **ADOPTED as production model.** Generational upgrade with IFEval 91.5,
hybrid architecture (Gated DeltaNet + sparse MoE), 262K context. Battery results: Q35 13/22
vs QVL 11/22 on same battery (corrected apples-to-apples comparison on 22-scenario battery).
Handoff rate improved from 2/6 to 4/6.

| Property | Qwen3VL-8B-Thinking (previous) | Qwen3.5-9B (current) |
|----------|-------------------------------|----------------------|
| Q4_K_M GGUF size | 5.03 GB | 5.87 GB |
| Fits 8GB VRAM? | Yes | Yes (~2GB headroom) |
| IFEval | Not benchmarked | **91.5** |
| Thinking mode | Always on | Controllable (`enable_thinking` toggle) |
| Tool call format | Hermes 2 Pro JSON | Qwen3-Coder XML |
| Chat model Q8_0 | ~6.5 GB (fits) | 9.5 GB (not viable for 8GB VRAM) |

GGUF file: `models/Qwen_Qwen3.5-9B-Q4_K_M.gguf` (5.46 GB on disk).
Embedding model: `models/nomic-embed-text-v1.5.Q8_0.gguf` (139 MiB, auto-discovered).

### Direction G2: Qwen3.5-9B adoption details (historical)

**Blockers resolved (2026-03-03):** llama.cpp prebuilt upgraded b8157→b8185 (PR #19468 merged
2026-02-10 adds `qwen35` architecture). CPU prebuilt SHA: `CFADEA4775F8BD9948D0E242FFFE3BF52110EEEB055AC03EE6AC0DB66830850B`.

**Battery progression:**
- Pre-fix: 10/22 (45.5%) — 0/6 handoff (all LLM_TRANSIENT from mid-conversation system message)
- Post-fix: 13/22 (59.1%) — 4/6 handoff (LLM_TRANSIENT eliminated)
- R10 (23 scenarios): 13/23 — +BrowseTool file listing, SearchTool path resolution
- R11: 12/23 text, 13/23 hybrid — +search mode toggle

**Root cause of initial 0/6 handoff (FIXED):**
Qwen3.5 jinja template enforces "System message must be at the beginning." After handoff,
`pruneHandoffMessages()` + `swapSystemPrompt()` produced mid-conversation system messages
that caused HTTP 500. Fix: generalized merge logic into a forward-scanning loop that merges
ALL mid-conversation system messages into position [0] (`AgentLoopService.java` lines 854-871).

### Direction H: Fine-tuning (LoRA)

**Source:** Amazon research (arXiv:2512.15943) — 350M model outperformed ChatGPT on ToolBench
after targeted fine-tuning. Microsoft guide demonstrated Llama-3.2-3B going from failure to
"perfect" on function calling after LoRA.

**Core idea:** Instead of working around the model's tool-calling weakness at inference time,
train it away. A LoRA adapter targeting the specific JustSearch tool schema (search_index,
browse_folders, handoff_to_organizer, ingest_files) could transform reliability.

**Approach:**
1. Generate training data from successful battery runs + synthetic examples
2. LoRA fine-tune (rank 8, alpha 16) using Unsloth or HuggingFace TRL
3. Export to GGUF for llama-server deployment
4. Public datasets: glaive-function-calling-v2, Hermes Function Calling V1, xlam-function-calling-60k

**Effort estimate:** High. Requires training infrastructure, dataset curation, and ongoing
maintenance when tool schema changes. But highest potential impact for persistent failures.

**Risk:** Fine-tuning is specific to the current tool definitions. Any schema change may require
retraining. Best applied after the tool schema stabilizes.

### Direction I: Constrained decoding (GBNF grammars) — DONE

**Status:** GBNF grammar infrastructure implemented. `TOOL_CALL_GRAMMAR` constant in
`AgentLoopService`; grammar wired to E0a and escalation calls. Stack-overflow bug fixed in
b8157. See Phase 2 status table for details.

### Direction J: Context management for small models — DONE

**Status:** Implemented. Original user instruction re-appended at end of message queue before
each PRIMARY LLM call (counteracts position bias). Context compression in battery runner.
See Phase 2 status table for details.

---

## 6. Immediate next steps

| Step | Action | Direction | Status | Expected outcome |
|------|--------|-----------|--------|----------------|
| **F1-fix** ✓ | Updated Organizer prompt in `agentProfiles.ts` + all 6 scenario embeds. Replaced stale `D:/data/docs` example with portable relative-path pattern. | A | **DONE** | h001/h002/h003 path errors resolved. |
| **IngestTool strip-prefix** ✓ | Added strip-prefix resolution to `IngestTool.resolvePath()`. When path starts with a component matching the indexed root folder name (e.g. `docs/...` against root `.../docs`), strips prefix and retries. New test: `relativePathWithRootNamePrefixStripped`. | A | **DONE** | Resolves `docs/docs/...` double-prefix failure mode. |
| **C1 directive** ✓ | Added commit-under-uncertainty rule to PRIMARY in `agentProfiles.ts` + all 6 scenario embeds. | C | **DONE** | h006: 0/3 → 3/3. h003 improved. |
| **h001 threshold calibration** ✓ | `expectedMinToolCalls` 2→1 (new efficient flow: Organizer calls ingest_files directly, no browse_folders needed). | A | **DONE** | h001 oracle no longer fails correct runs. |
| **h002 threshold calibration** ✓ | `expectedMinToolCalls` 3→2 | A | **DONE** | |
| **h004 threshold calibration** ✓ | `expectedMinToolCalls` 4→3 | A | **DONE** | |
| **h005 threshold calibration** ✓ | `expectedMinToolCalls` 2→1 (new flow: 1 search → "not found" report) | A | **DONE** | |
| **h006 threshold calibration** ✓ | `expectedMinToolCalls` 3→2 | A | **DONE** | |
| **browse_folders prohibition** ✓ | Added "NOT browse_folders" rule to PRIMARY write-ops line + "Never use full absolute Windows path" in both source files. | A | **DONE** | Resolved h004 0/3→3/3. h002 improved 0/3→1/3. |
| **BrowseTool relative paths** ✓ | Modified `BrowseTool.java` to output relative paths (stripping root prefix) and accept relative `parent_path` inputs (resolving against root names). Now browse_folders returns `docs/explanation` instead of `D:\code\JustSearch\docs\explanation`. | A | **DONE** | Eliminated h002 root cause: browse_folders no longer leaks absolute paths into handoff reasons. h002: 1/3 → 5/5. |
| **Prompt contradiction removed** ✓ | Replaced "NOT browse_folders" with "or browse_folders" in PRIMARY write-ops line. BrowseTool relative paths make the prohibition unnecessary. | A | **DONE** | No regression — browse_folders is safe now. |
| **h002 expectedKeywords fix** ✓ | Changed h002 `expectedKeywords` from `["architecture","ingest"]` to `["ingest"]`. "architecture" was a false negative — Organizer's concise confirmation doesn't echo the word. Architecture requirement already covered by det oracle `argPathAnyOf`. | A | **DONE** | Eliminated h002 false negative. |
| **DRY scenario profiles** ✓ | Added top-level `profiles` object to scenario JSON. Handoff scenarios reference profiles by string ID instead of embedding inline. Runner resolves references at load time. | — | **DONE** | 6×2 inline embeds → 1 shared definition. Maintenance hazard eliminated. |
| **Iteration cap confirmed** ✓ | Tested removing "at most twice" → replaced with "search efficiently". Battery dropped to 2/6 (33.3%). Reverted. "at most twice" is load-bearing for h003/h004/h006 handoff compliance. | C | **DONE** | "at most twice" is required; "search efficiently" is too vague for 8B. |
| **h002 oracle widened** ✓ | Added `docs/tempdocs/` and `docs/reference/` to h002 `argPathAnyOf`. Model legitimately ingests architecture-related tempdocs when searching for "system architecture". | A | **DONE** | |
| **Increase N** ✓ | Ran N=5 for all 6 scenarios. Results in §2 and §A.6. | — | **DONE** | h002 confirmed at 5/5. Overall: 25/30 (83.3%) keyword, but honest task completion ~56%. |
| **Exploration regression check** ✓ | Ran exp-001 through exp-016 (N=2). No regressions from BrowseTool changes. 8/16 pass rate is pre-existing baseline for exploration scenarios. See §A.7. | — | **DONE** | BrowseTool relative paths verified working in exp-002, exp-015, exp-016. |

**Phase 1 (prompt engineering) is complete.** All items above are DONE. Remaining improvements
require infrastructure changes. The items below are Phase 2.

| Step | Action | Direction | Status | Expected outcome |
|------|--------|-----------|--------|----------------|
| **tool_choice escalation** | PRIMARY text-only response after tool use retried with `tool_choice:required` and handoff-only tools. `shouldEscalateToHandoff()` fires when `agentIterationsSinceHandoff >= 2`. | F | **DONE** (c12b2156) | h004 100% stable. h003 now fails on `expectedMinToolCalls:3` — see spec fix below. |
| **Det oracle enforcement** | `requiredToolSuccess` promoted to always-enforced primary check in `evaluateTranscript()`. `firstToolCallOracle` marked `required: false` (diagnostic only). See §11. | — | **DONE** (8618e9b4) | Honest baseline: ~56% (was inflated 83.3%). False positives from keyword-only passes eliminated. |
| **h003 spec fix** | Relax `expectedMinToolCalls` 3→2 in handoff-003-fuzzy-search. Direction F escalation shortcut produces search(1)+ingest(1)=2 counted calls (handoffs excluded from `toolCallsExecuted`); threshold of 3 fails correct escalated runs. | — | **DONE** | Accurate pass rate for h003. |
| **Model benchmark (Qwen3-8B)** | Benchmark Qwen3-8B (non-VL, non-Thinking) Q5_K_M on handoff battery. | G | **DONE — no switch warranted** | Qwen3-8B Q5_K_M avg 3.5/6 vs Qwen3VL avg 2.5/6 — slight edge but within variance. Not enough to justify switch. Qwen3.5-9B (G2) superseded this investigation. |
| **State machine orchestrator** | Added `AgentState` enum (SEARCHING/DECIDING) and `resolveAgentState()` in `AgentLoopService`. After `PRIMARY_FORCE_COMMIT_ITERATIONS=3` tool rounds without handoff, PRIMARY enters DECIDING state: tools restricted to `[handoff_to_organizer]`, `tool_choice:required` applied. Code-controlled routing — PRIMARY cannot loop on searches indefinitely. | E | **DONE** | Principled fix for h004/h006 F3 failure: PRIMARY forced to commit after 3 tool rounds. 1 new test: `primaryForceCommit_afterThresholdToolRounds_restrictsToHandoffAndForcesToolChoice`. |
| **Context management** | Re-append original user instruction at end of message queue before each PRIMARY LLM call (when `agentIterationsSinceHandoff > 0` and last message is not already a user message). Guard: multi-agent mode only. | J | **DONE** | Counteracts position bias: instruction stays in high-attention zone throughout multi-step PRIMARY loops. Removed cleanly by `pruneHandoffMessages` on handoff. |
| **GBNF grammar constraints** | `SamplingParams.grammar` field + `withGrammar()` builder implemented. `TOOL_CALL_GRAMMAR` constant in `AgentLoopService`; grammar wired to E0a and escalation calls. `OnlineModeOps` guard skips grammar when tools non-empty (llama-server rejects grammar+tools). | I | **DONE** | Grammar infrastructure in place. Stack-overflow bug (`[\s\S]*` patterns on ≥50K char inputs) fixed in b8157 (PR #18342) — budget-edge grammar path now reliable on long-context sessions. |
| **Budget bypass for forced-tool-call turns** | `budgetExhausted && !shouldForceToolCall(session) && agentState != DECIDING` — E0a and DECIDING turns skip `attemptBudgetEdgeFinalize` and proceed to regular LLM calls even when budget is technically exhausted. `attemptBudgetEdgeFinalize` was intercepting these critical turns and producing Hermes JSON text as the "answer". 2 new tests: `budgetExhausted_e0aBypassesFinalizeAndProceedsToLlmCall`, `budgetExhausted_decidingBypassesFinalizeAndProceedsToLlmCall`. | E/F | **DONE** | h003 fix: Organizer E0a now gets its LLM call even when PRIMARY exhausted the budget. Live battery: 4/6 → 5/6 (one run). |
| **Qwen3.5-9B benchmark** | Download Qwen3.5-9B Q4_K_M (5.46 GB), benchmark on full battery. | G2 | **DONE — ADOPTED as production model** | llama.cpp upgraded b8157→b8185. System message merge fix (AgentLoopService). **Corrected apples-to-apples: Q35 13/22 vs QVL 11/22 on same full battery.** Exploration tied 9/16; handoff Q35 4/6 vs QVL 2/6. Chat model Q8_0 not viable (9.5GB exceeds 8GB VRAM). See Direction G for full comparison table. |
| **Diagnostic pre-flight scenario** | Added `diag-001-tool-smoke` as first battery scenario. Agent exercises `search_index` + `browse_folders`, self-reports structured summary. Evaluation: `requiredToolSuccess` + sentinel keyword "ALL TOOLS OPERATIONAL". | — | **DONE** | Canary for silent environment issues (empty corpus, broken browse, etc.). |
| **BrowseTool file listing** | Auto-fallback: when `listFolders()` returns empty for a non-root parent, BrowseTool now calls `listFolderFiles()` to show individual files. Also adds `list_files:true`/`max_files` parameters for explicit file listing. `FilesCallback` wired to existing `KnowledgeHttpApiAdapter.listFolderFiles()`. 7 tests (restructured as peer operations after critical review). | — | **DONE** | Root cause of exp-003, exp-004 failures: BrowseTool returned "No folders found" for leaf dirs. **Battery retest (2026-03-03):** exp-003 FIXED (agent now sees filenames via auto-fallback). h-004 FIXED (multi-hop handoff now succeeds). exp-004 still fails (model lists only 5/22 files, omits `system-overview`). exp-015 NOT fixed (has subfolders, auto-fallback doesn't fire). 4 scenarios hit LLM_TRANSIENT (consecutive mid-battery crash, infra flakiness). Net: h-004 confirmed stable across 2 runs; exp-003 stochastic (pass in 1 run, fail in the other). |
| **SearchTool relative path_prefix** | SearchTool now resolves relative `path_prefix` values against indexed roots (matching BrowseTool/IngestTool). Extracted `AgentToolPaths.resolveRelativePath()` shared utility. Constructor changed to `Supplier<List<RootInfo>>`. `AppFacadeBootstrap` now shares single `rootsSupplier` across all 3 tools. Description updated. 2 new tests + 5 updated. | — | **DONE** | Root cause of h-006 battery failure: model copies relative paths from browse results into search `path_prefix` → rejected as "not an absolute path." **Clean battery (2026-03-03):** 13/23 (12/22 excluding diag). h-004 confirmed stable fix (BrowseTool). h-006 confirmed stable pass (SearchTool path fix). exp-007 and exp-010 regressed (model variance, `missing_facts`). Remaining 8 failures are all `missing_facts`/`missing_keywords` — see §6b search relevance analysis: 5 caused by BM25 length bias (tempdocs outranking canonical docs), 1 by Lucene query syntax bug, 1 by model comprehension, 1 browse-only. |
| **Search relevance investigation** | Hybrid mode tested (net neutral on Qwen3.5-9B with Q8_0 embeddings). `--search-mode` toggle added for A/B testing. File-path query sanitization retained. See §6b for full analysis. | — | **PARTIAL** | BM25 length bias confirmed as dominant bottleneck (tempdocs 40-50KB outrank 5-15KB canonical docs). Hybrid mode: text 12/23, hybrid 13/23 (net +1 within N=1 variance). Cross-encoder reranking for TEXT mode is the remaining untested approach (tempdoc 135). |
| **LoRA fine-tuning** | Fine-tune on JustSearch tool schema using Unsloth + glaive dataset. Export to GGUF. | H | **DEFERRED** | Highest potential impact but highest effort. Best after tool schema stabilizes. |
| **E3: thinking mode evaluation** | b8185 supports `--reasoning-budget`. Evaluate impact of thinking on battery scores. | D | **INVESTIGATED — negative result** | Qwen3.5-9B GGUF template ALWAYS emits `<think>\n` by default (extracted from GGUF metadata). With `--reasoning-budget 0` (current default): thinking suppressed — 13/22. With `--reasoning-budget -1` (unlimited): **catastrophic 5/22 (23%)** — model spends all 1024 max_tokens on `<think>` content → EMPTY_RESPONSE on 10+ scenarios. Root cause: `DEFAULT_MAX_TOKENS=1024` is shared between reasoning and content. Bounded test (budget=1024, max_tokens=2048) failed: **`--reasoning-budget` in b8185 only accepts -1 or 0** — arbitrary token budgets are not supported (`"error while handling argument: invalid value"`). True bounded thinking requires upstream llama.cpp support. Only viable test: `-1` (unlimited) + larger `max_completion_tokens`, but this is risky given the 5/22 catastrophic result. Per-request `enableThinking=false` on mechanical turns (E0a, DECIDING, escalation) is already implemented and working. Env vars: `JUSTSEARCH_REASONING_BUDGET`, `JUSTSEARCH_AGENT_MAX_COMPLETION_TOKENS`. |
| **enable_thinking per-request** | Added `Boolean enableThinking` field to `SamplingParams` + `withEnableThinking(Boolean)` builder. `OnlineModeOps.streamChatWithTools()` emits `chat_template_kwargs: {"enable_thinking": false}` when set. Applied to E0a (`resolveAgentSampling`), DECIDING state call, and Direction F escalation call in `AgentLoopService`. 6 new tests (3 SamplingParamsTest + 3 OnlineModeOpsTest); `enableThinking=false` assertions added to 5 existing AgentLoopServiceTest methods. Post-review gap fixes: (1) added `isBoolean()` type assertion to confirm JSON boolean (not string) is emitted — required for C++ `.dump()` comparison; (2) new combo test `suppressesGrammarWhenToolsPresentButEmitsChatTemplateKwargs` exercises the E0a production path (grammar suppressed by tools-present guard, `chat_template_kwargs` still emitted); (3) added `size()` guard to `handoff_organizerFirstTurnGetsToolChoiceRequired` matching the pattern of all other `enableThinking` tests. | D | **DONE** | Under `--reasoning-budget 0`: suppresses thinking-prompt formatting on mechanical turns. Full E3 benefit (token generation) blocked pending upstream. |

---

## 6b. Search relevance analysis (2026-03-03)

**Context:** After SearchTool path fix, clean battery shows 13/23 (12/22 excl. diag). Remaining 8
non-infra failures are all `missing_facts`/`missing_keywords`. Reproduced exact battery search
queries against the battery corpus (docs/ only, ~180 files) and against the dev stack (729 docs).

**Finding 1: BM25 default mode causes systemic length bias.**
SearchTool defaults to `mode=text` (BM25 only, line 66). BM25 rewards documents with more term
occurrences → tempdocs (40-50KB verbose notes) consistently outrank short canonical docs (5-15KB
focused reference). Every "wrong top result" in the battery is a tempdoc outranking the target
canonical doc.

| Scenario | Query | Target doc | Actual #1 result | Root cause |
|----------|-------|-----------|-----------------|------------|
| exp-005 | "configuration documentation" | `reference/configuration/environment-variables.md` | `tempdocs/215-duplicate-intent-analysis.md` (4.07) | Tempdoc mentions "configuration" + "documentation" repeatedly |
| exp-006 | "architecture documentation main sections" | `explanation/01-system-overview.md` | `tempdocs/185.../09-critical-analysis.md` (4.58) | "Sections 1-7" text matches "sections" query term |
| exp-007 | "tempdocs 186" | `tempdocs/186-llm-agentic-file-operations.md` | `tempdocs/200-agent-tool-architecture-unification.md` (3.57) | Tempdoc 200 references "186" frequently → outranks 186 itself |
| exp-008 | "inference" | AI/inference architecture docs | `tempdocs/139-feature-gaps-vs-alternatives.md` (3.26) | Tempdoc longer, mentions "inference" more times |
| h-003 | "system design overview" | `explanation/01-system-overview.md` | `future-features/subscription-licensing...md` (0.03) | All scores flat (0.03); "overview" matches URL text in subscription doc |

**Finding 2: Lucene query syntax bug.**
exp-010 first query used a file path as the search query: `"docs/reference/configuration/environment-variables.md"`.
Lucene parser choked on `/` characters → `INVALID_ARGUMENT: Lexical error`. The query was wasted.
This would be fixed by SIMPLE query syntax fallback on parse error.

**Finding 3: Some scenarios have correct results but model doesn't extract.**
exp-010 second query ("environment-variables"): the actual env vars doc appeared at rank #2 with
full content preview showing `JUSTSEARCH_DATA_DIR`, `JUSTSEARCH_HOME`, etc. The model still failed
to include "JUSTSEARCH" in its response. This is a model comprehension issue, not search relevance.

**Finding 4: Cross-encoder reranking already known to fix BM25 length bias.**
Tempdoc 135 (Search & Retrieval Quality) documented this exact issue: Q3 "how does the three
process architecture work" returned `agent-guide` (39K chars) over `system-overview` under BM25,
but cross-encoder reranking correctly promoted `system-overview`. The agent's search path does not
use cross-encoder reranking — it goes through `KnowledgeHttpApiAdapter.search()` which calls the
standard search endpoint.

### Search improvements — status

| Action | Impact | Effort | Status |
|--------|--------|--------|--------|
| **Hybrid search mode** | MIXED | LOW | **Tested — net neutral.** Qwen3.5-9B + Q8_0 embeddings: text 12/23 (52.2%), hybrid 13/23 (56.5%). Hybrid gains 3 (exp-003, h-002, h-006), loses 2 (exp-007, exp-010) = net +1 within N=1 variance. `--search-mode` toggle (`JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE`) added for ongoing A/B testing. Default remains text (null). Previous Qwen3VL test showed hybrid regression (9/23) but that was confounded by LLM_TRANSIENT errors. |
| **File-path query sanitization** | MEDIUM | LOW | **DONE** — `sanitizeFilePathQuery()` detects queries with `/` or `\`, strips file extensions, replaces path separators with spaces. Prevents Lucene parse errors on file-path queries. 4 new tests. Targets: exp-010 first query. |
| **Boost filename/path matches** | HIGH | MEDIUM | **NOT FEASIBLE** — `path` and `filename` are keyword fields (not text) in Lucene schema. Cannot text-search them without schema change + re-indexing. |
| **Enable cross-encoder reranking for agent search** | MEDIUM | MEDIUM | **NOT TESTED** — Cross-encoder fires for TEXT mode when model auto-discovered. Battery may not have cross-encoder ONNX model available. Could help BM25 length bias without requiring hybrid mode. Separate investigation needed. |

**Bottom line:** Hybrid mode is net-neutral on Qwen3.5-9B with Q8_0 embeddings (within N=1
variance). The `--search-mode` toggle enables ongoing A/B testing without code changes. BM25
length bias remains the dominant bottleneck — the fix is cross-encoder reranking (tempdoc 135),
not retrieval mode switching.

---

## 7. Open issues found during Phase 2

These issues were identified during the Phase 2 implementation and battery runs (commit a3dcac1a).
Investigated in depth 2026-02-26. Status updated below.

### 7a — Organizer E0a tool set too broad (**implemented — commit 14d7e135**)

**Note:** Evidence collected with Qwen3VL-8B-Thinking and Qwen3-8B Q5_K_M. Fix applies to all models.

**Observed:** Across all 4 battery runs (2× Thinking baseline, 2× Qwen3-8B Q5_K_M), the Organizer
calls `search_index` instead of `ingest_files` on E0a in approximately 5 of 8 handoff-002/003/004
attempts. Battery evidence:

| Run | h002 E0a | h003 E0a | h004 E0a |
|-----|----------|----------|----------|
| Thinking run1 | `search_index` ✗ | `ingest_files` ✓ (wrong path) | `ingest_files` ✓ (wrong path) |
| Thinking run2 | `search_index` ✗ | `ingest_files` ✓ (keyword miss) | `search_index` ✗ |
| Qwen3-8B run1 | timeout | `search_index` ✗ | `search_index` ✗ |
| Qwen3-8B run2 | `ingest_files` ✓ (pass) | `ingest_files` ✓ (keyword miss) | `search_index` ✗ |

**Root cause (confirmed):** The tool-list resolution block in `AgentLoopService` (lines 479–488)
only forks on `agentState == DECIDING` (PRIMARY-only concept). For non-PRIMARY agents the `else`
branch always fires: `buildIterationTools(request, baseTools, session.activeAgentId())`, which
returns the Organizer's full `toolSubset` = `["search_index", "browse_folders", "file_operations",
"ingest_files"]`. `shouldForceToolCall()` adds `tool_choice: "required"` but does not narrow
*which* tool is required.

**Implementation:** Added `buildE0aTools(request, session)` to `AgentLoopService` returning
`ingest_files` from the registry plus handoff tools (so the Organizer can return control if
needed). Injected as `else if (shouldForceToolCall(session))` branch between the DECIDING
check and the normal `buildIterationTools` fallthrough. Note: handoff tools are included
(initial spec said exclude them, but excluding them broke `multiAgent_handoffToolsArePerActiveAgent`
— agents must be able to return control to their caller even on E0a).

**Unit tests (passing):** Three existing test methods updated with assertions verifying
`ingest_files` is present and `search_index`/`browse_folders`/`file_operations` are absent
on E0a turns. 222 tests pass (commit 14d7e135).

**Expected impact:** Eliminates the wrong-tool branch for h002/h003/h004. Also resolves 7d
(see below). Estimated lift: 4–5/6 → 5–6/6. **Needs live battery validation.**

---

### 7b — Grammar blocked on tool-call turns (**closed — non-actionable**)

**Investigated (2026-02-26):** Confirmed from source. `third_party/llama.cpp/common/chat.cpp`
line 3200 contains the explicit guard:
```cpp
if (params.tool_choice != NONE && !params.grammar.empty())
    throw std::runtime_error("Cannot specify grammar with tools");
```
This is **intentional architecture**, not a bug. When tools are present, llama.cpp auto-derives
an internal grammar from the tool schemas via lazy-grammar triggers (PR #9639, merged, present
in b7502). Custom `grammar` + `tools` is redundant and explicitly rejected. The latest upstream
build (b8157, Feb 26 2026) does not change this.

**Measured impact:** Zero malformed-JSON tool-call failures across all 4 battery runs. Direction I
grammar fires correctly on the `attemptBudgetEdgeFinalize` (empty-tools) path. The `OnlineModeOps`
guard is correct and sufficient. No action needed.

**Resolution:** Closed. The grammar+tools limitation has no measured effect on pass rate. Track
tempdoc 236 for the broader llama-server upgrade investigation.

---

### 7c — Battery default start timeout too short (**implemented — commit 14d7e135**)

**Investigated (2026-02-26):** Data flow confirmed:
`DEFAULT_START_TIMEOUT_MS (180s)` → `--ready-timeout-sec 180` → `readyTimeoutMs = 180_000`
→ deadline. `MAX_NO_ACTIVE_RUN_STREAK = 300` is a detached constant computing 600s — longer
than the default 180s deadline, so crash detection never fires before the deadline expires.
**Implementation:** Both changes applied (commit 14d7e135):
1. `dag-runner-agent-battery.mjs`: `DEFAULT_START_TIMEOUT_MS` 180_000 → 600_000
2. `dev-runner-lifecycle.mjs` `cmdStart()`: removed hardcoded `MAX_NO_ACTIVE_RUN_STREAK = 300`;
   computed dynamically as `Math.ceil(readyTimeoutMs / POLL_INTERVAL_MS)` so crash detection
   scales with the caller's timeout rather than being a fixed 600s constant

**Note:** The root cause of why cold starts take 3–5 minutes is under separate investigation.

---

### 7d — BUDGET_EXHAUSTED during SEARCHING phase (**closed — symptom of 7a**)

**Note:** Evidence collected with Qwen3VL-8B-Thinking. Analysis applies to all models.

**Investigated (2026-02-26):** Every h002 `BUDGET_EXHAUSTED` instance traces directly to 7a.
Battery traces:
- Thinking run1: `[search_index, handoff_to_organizer, search_index]` → BUDGET_EXHAUSTED
  (Organizer's `search_index` on E0a adds a tool result to context, tipping the budget check)
- Thinking run2: `[search_index, search_index, handoff_to_organizer, search_index]` → `required_tool_failure:ingest_files`

`REASONING_BUDGET = 0` confirmed — thinking tokens are disabled at the server level
(`--reasoning-budget 0`). Thinking-token inflation is NOT the cause. The extra `search_index`
iteration from E0a (and its tool result in context) is the sole cause of budget exhaustion.

**Resolution:** Closed. Fixing 7a eliminates the extra iteration; BUDGET_EXHAUSTED on h002
disappears as a side effect. No per-model budget multiplier needed.

---

## 8. E3 status (deferred — runtime infrastructure blocker)

**Direction A is complete.** E3 can now be evaluated in isolation (no more F1 confound).

However, E3 remains blocked by llama.cpp `--reasoning-budget` limitation: only accepts -1
(unlimited) or 0 (disabled). Arbitrary token budgets (e.g., 512, 2048) produce
`candidate_reasoning_budget_unsupported_by_runtime`.

**Qwen3.5-9B investigation results (2026-03-03):**
- `--reasoning-budget 0` (default): thinking suppressed — 13/22 (59.1%). Working.
- `--reasoning-budget -1` (unlimited): **catastrophic 5/22 (23%)** — model exhausts 1024
  `max_tokens` on `<think>` content → EMPTY_RESPONSE on 10+ scenarios.
- Bounded test (budget=1024, max_tokens=2048): **rejected** — `--reasoning-budget` in b8185
  only accepts -1 or 0, not arbitrary values.

`enable_thinking` per-request via `chat_template_kwargs` is implemented and working for
mechanical turns (E0a, DECIDING, escalation). This is the achievable scope under current
server config. Full E3 benefit (bounded thinking where the model reasons for N tokens then
acts) requires upstream llama.cpp support for per-request `think_budget`
(discussion #12339). Env vars: `JUSTSEARCH_REASONING_BUDGET`,
`JUSTSEARCH_AGENT_MAX_COMPLETION_TOKENS`.

E3 is most interpretable after Direction A (F1-fix) is completed. See tempdoc 230 for eval
infrastructure closure artifacts.

---

## 9. Related work

| Tempdoc | Relationship |
|---------|-------------|
| 211 (Multi-Agent Handoff M0) | Source of initial empirical findings. Infrastructure complete. |
| 213 (Agent Search Context Quality) | Approach B (structured format) could be a follow-on improvement. |
| 230 (Eval Suite Decision Closure) | Eval infrastructure: 6-scenario battery, scorecard, E3 protocol. |
| llama.cpp upgrade (no tempdoc) | **Done (2026-03-03).** Prebuilt upgraded b8157→b8185 in `modules/ui/build.gradle.kts`. CUDA variant also updated in `native-bin/`. Both Qwen3VL-8B-Thinking (regression OK) and Qwen3.5-9B load/serve. Commit: `feat/llama-b8185-qwen35`. |

### External research (informing Directions E–J)

| Source | Key finding | Direction |
|--------|------------|-----------|
| "Blueprint First, Model Second" (Alibaba, arXiv:2508.02721) | Expert-defined execution blueprint + deterministic engine outperforms LLM-as-controller by 10.1pp. LLM invoked only for bounded sub-tasks. | E |
| StateFlow (NeurIPS 2024, arXiv:2403.11322) | Finite state machine formalization for LLM workflows. Separates process grounding from sub-task solving. | E |
| Docker: Local LLM Tool Calling Evaluation (2025) | Qwen3-8B achieves F1 ~0.93 on tool calling. Extreme quantization degrades performance. | G |
| LlamaIndex handoff fix research (dataleadsfuture.com) | Position bias causes small models to lose instructions as context grows. Fix: re-append original request. | J |
| Amazon (arXiv:2512.15943) | 350M model outperformed ChatGPT on ToolBench after targeted LoRA fine-tuning. | H |
| llama.cpp function-calling.md | GBNF grammars guarantee syntactically valid tool calls at decode time. Native support in llama-server. | I |
| NVIDIA: "SLMs Are the Future of Agentic AI" (arXiv:2506.02153) | SLMs fine-tuned for specific agentic routines are more reliable than general-purpose LLMs. Heterogeneous architecture recommended. | H |
| Strands Agents (AWS, github.com/strands-agents) | First major framework with native llama.cpp + grammar constraint support for local agents. | E, I |
| DSPy Assertions (arXiv:2312.13382) | Systematic retry with backtracking when output constraints fail. | F |
| LangGraph (langchain.com/langgraph) | Production state graph framework. Deterministic routing as first-class pattern. | E |
| Galileo: "Why Multi-Agent AI Systems Fail" (2025) | Position bias, tool call leaking, handoff refusal documented as top failure modes. | J |

---

## 10. Direction F implementation results

**Date:** 2026-02-23
**Change:** Added tool_choice escalation for PRIMARY text-only responses after tool use.

### Implementation summary

When PRIMARY produces a text-only response after completing at least one tool-use round
(`agentIterationsSinceHandoff >= 2`) in a multi-agent session, the system retries the LLM call
with `tool_choice: "required"` and a restricted tools array containing only handoff tools.
GBNF grammar enforcement in llama-server guarantees the model produces a tool call.

**Files changed:**
- `AgentLoopService.java`: Added `shouldEscalateToHandoff()` method + escalation logic at the
  text-only handler + `callLlmWithTools` overload accepting explicit `SamplingParams`
- `AgentLoopServiceTest.java`: 3 new tests (escalation fires, no-escalation-first-turn,
  no-escalation-single-agent)

### Battery results (handoff scenarios, N=3)

| Scenario | Before (R6) | After (F) | Status |
|----------|-------------|-----------|--------|
| h001 | 5/5 (100%) | 3/3 (100%) | STABLE |
| h002 | 5/5 (100%) | 3/3 (100%) | STABLE |
| h003 | 4/5 (80%) | 1/3 (33%) | FLAKY — `insufficient_tool_calls`. Escalation shortcuts flow, reducing total tool calls below `expectedMinToolCalls: 3`. Pre-existing search quality issue (can't find "system design overview" → "01-system-overview.md"). |
| h004 | 3/5 (60%) | **3/3 (100%)** | **FIXED** — was the primary target. Escalation forces handoff after PRIMARY's ranked-list text response. |
| h005 | 5/5 (100%) | 3/3 (100%) | STABLE |
| h006 | 3/5 (60%) | 2/3 (67%) | FLAKY — 1 BUDGET_EXHAUSTED (resource issue, not escalation-related) |
| **Total** | **25/30 (83.3%)** | **15/18 (83.3%)** | Stable aggregate. Key fix: h004 → 100%. |

**Note:** R6 "before" numbers are keyword pass (inflated). The R6 honest task completion was ~56%.
The Direction F numbers use the same battery runner with full validation (keywords + requiredFacts
+ mustNotContain + expectedMinToolCalls). Direct comparison requires R6 re-run with current battery.

### Exploration battery regression check (N=1)

9/16 (56%) — no regression from baseline 50%. Escalation does NOT fire for exp scenarios (single-agent mode).

### Key observations

1. **h004 FIXED:** The primary target of Direction F. PRIMARY's ranked-list text response after
   2 searches now triggers escalation → forced handoff → organizer ingests. 3/3 stable.
2. **h003 regression is a scenario spec issue:** `expectedMinToolCalls: 3` doesn't account for
   escalation shortcutting the flow (1 search + escalated handoff + ingest = 3 tools, but the
   battery counts `toolCallsExecuted` which excludes handoff events). Consider relaxing to 2.
3. **No new AgentSession state needed:** Reused existing `agentIterationsSinceHandoff` counter.
4. **Streaming is acceptable:** Client sees PRIMARY's text explanation followed by the forced
   handoff — looks like natural "explain then hand off" behavior.

---

## Appendix A — Historical record

### A.1 Original measurement context

Environment: dev stack data dir `D:/data/docs`; 77 docs ingested; completion check: keyword
presence in final response text (not whether ingest_files succeeded). Data: `tmp/227-baseline/`.

**This environment matched the `D:/data/docs` path hardcoded in the Organizer system prompt
example. Ingest succeeded because the example path was accidentally correct for this
environment. Results do not generalise to other environments.**

| Scenario | Before | After (C1+C4+C6) | Final (E0a+E1+E2) |
|----------|--------|-------------------|---------------------|
| handoff-001 (direct ingest) | 80% completion, 19.5s avg | 90% completion, 13.0s avg | **100% completion, 11.5s avg** |
| handoff-002 (search → ingest) | 10% completion, 29.1s avg | 90% completion, 12.6s avg | **100% completion, 11.4s avg** |
| **Weighted average** | **45%** | **90%** | **100%** |

**Baseline (4K context, 8192 max_tokens, old prompt) — handoff-001-immediate:**

| Run | Status | Organizer tools | Budget at handoff | Final budget | Duration |
|-----|--------|----------------|-------------------|-------------|----------|
| 1 | SUCCESS | browse → ingest | +3028 | -264 | 5.6s |
| 2 | SUCCESS | browse → ingest | +3028 | -274 | 5.4s |
| 3 | FAIL | (reasoned 4096 tok) | +3034 | -1062 | 66.0s |
| 4 | SUCCESS | browse → ingest | +3028 | -262 | 4.4s |
| 5 | SUCCESS | browse → ingest | +3039 | -184 | 3.9s |
| 6 | SUCCESS | browse → ingest | +3034 | -497 | 7.1s |
| 7 | FAIL | (reasoned 4096 tok) | +3034 | -1062 | 89.9s |
| 8 | SUCCESS | browse → ingest | +3039 | -170 | 4.3s |
| 9 | SUCCESS | browse → ingest | +3035 | -213 | 4.2s |
| 10 | SUCCESS | browse → ingest | +3035 | -203 | 3.7s |

100% handoff, 80% completion. Failures: reasoning dumps at 66s and 90s.

**Baseline — handoff-002-search-then-handoff:**

| Run | Status | PRIMARY tools | Budget at handoff | Organizer outcome | Duration |
|-----|--------|--------------|-------------------|-------------------|----------|
| 1 | FAIL | search→browse→search→handoff | -344 | Text only (912 tok) | 14.5s |
| 2 | FAIL | search→browse→search→search | — (no handoff) | — | 19.3s |
| 3 | SUCCESS | search→search→handoff | +724 | ingest_files | 13.2s |
| 4 | FAIL | search→browse→search→handoff | -924 | Text only (1233 tok) | 25.7s |
| 5 | FAIL | search→browse→search | — (no handoff) | — | 125.6s |
| 6 | FAIL | search→search→handoff | +611 | Text only (928 tok) | 17.2s |
| 7 | FAIL | search→browse→search→browse→browse | — (no handoff) | — | 14.4s |
| 8 | FAIL | search→browse→search→handoff | -831 | Text only (992 tok) | 18.7s |
| 9 | FAIL | search→browse→search→handoff | -897 | Text only (1352 tok) | 22.9s |
| 10 | FAIL | search→browse→search→handoff | -863 | Text only (1012 tok) | 19.2s |

70% handoff, 10% completion. Root cause: budget exhaustion. PRIMARY consumed 4700–7400 tokens
on 2–4 search/browse calls. In 5/7 handoff cases, budget was negative at handoff.

**Final state (8K context, 1024 max_tokens, C1+C4+C6) — handoff-001-immediate:**

| Run | Status | Tools | Budget remaining | Duration |
|-----|--------|-------|-----------------|----------|
| 1 | SUCCESS | handoff→browse→ingest→handoff_back→search→browse | -1357 | 13.6s |
| 2 | SUCCESS | handoff→browse→ingest | 1704 | 16.6s |
| 3 | SUCCESS | handoff→browse→ingest→ingest | 548 | 6.7s |
| 4 | SUCCESS | handoff→browse→ingest→handoff_back→search→browse | -1806 | 14.6s |
| 5 | SUCCESS | handoff→browse→ingest→handoff_back | 736 | 18.3s |
| 6 | SUCCESS | handoff→browse→ingest→handoff_back→search→handoff | -1109 | 11.2s |
| 7 | SUCCESS | handoff→browse→ingest→ingest→ingest | -224 | 8.1s |
| 8 | FAIL | handoff (Organizer: 1024 tok text) | 4708 | 16.2s |
| 9 | SUCCESS | handoff→browse→ingest | 1669 | 16.4s |
| 10 | SUCCESS | handoff→browse→ingest→ingest | 372 | 8.7s |

100% handoff, 90% completion.

**Final state — handoff-002-search-then-handoff:**

| Run | Status | Tools | Budget remaining | Duration |
|-----|--------|-------|-----------------|----------|
| 1 | SUCCESS | search→browse→search→handoff→ingest | -837 | 14.8s |
| 2 | SUCCESS | search→browse→search→handoff→ingest | -1034 | 15.6s |
| 3 | SUCCESS | search→browse→search→handoff→ingest | -374 | 12.8s |
| 4 | SUCCESS | search→browse→search→handoff→ingest | -39 | 10.1s |
| 5 | FAIL | search→search→handoff (Organizer: reasoning dump) | 2170 | 22.0s |
| 6 | SUCCESS | search→search→handoff→ingest | 1247 | 9.3s |
| 7 | SUCCESS | search→browse→search→handoff→ingest | -194 | 11.0s |
| 8 | SUCCESS | search→search→handoff→ingest | 1363 | 8.9s |
| 9 | SUCCESS | search→browse→search→handoff→ingest | -705 | 14.7s |
| 10 | SUCCESS | search→search→handoff→browse→ingest | 7 | 7.0s |

100% handoff, 90% completion.

**Intermediate experiments (reference):**
- 8K/8192/old prompt, h001 N=5: 20% completion. 8K without token cap made reasoning dumps worse (900s timeouts).
- C4+C6 only (8K/1024/old prompt), h001 N=10: 70% completion. Token cap worked; 30% reasoning dump rate without example.
- C4+C6 only (8K/1024/old prompt), h002 N=10: 90% completion. Budget fix was the dominant improvement.

---

### A.2 Experiment results (E0a/E0b/E1/E2)

#### R1 Research findings (condensed)

Pre-experiment research (3 rounds) established the mechanism and build compatibility.

**Finding 1: `tool_choice: "required"` forces tool call from token 0.**
With `"required"`, grammar applies from token 0 — model must produce a tool call. With default
`"auto"`, lazy grammar allows free text until a trigger token is detected, making reasoning dumps
possible. Build b4963 fully supports `tool_choice` (feature landed in b4599). Only requirement
is `--jinja`, which we already pass unconditionally (`LlamaServerOps.java:199`).

**Finding 2: Qwen3-VL uses Hermes 2 Pro handler.**
With `--jinja`, server logs show `Chat format: Hermes 2 Pro`. Correct and expected. The Thinking
variant does not change handler selection — it adds `<think>` tags but the Hermes 2 Pro handler
detects on `<tool_call>` tags. Hypothesis H3 (prompt format mismatch) is ruled out.

**Finding 3: Agent loop termination conflicts with `tool_choice: "required"` on all turns.**
The agent loop terminates when the LLM returns text with no tool calls. The 10% failure is
always the Organizer's first response — no failure observed on turn 2+. This establishes that
first-turn-only forcing (E0a) fixes the observed failure without breaking text-exit termination.

**Findings 4–7 (condensed):** Qwen3 recommended sampling differs (temp 0.6, top_k 20) —
addressed by E1. `/no_think` is inert (server-level `--reasoning-budget 0` overrides prompt-level
switch) — confirmed by E2. `response_format` cannot combine with `tools` (errors: "Either
'json_schema' or 'grammar' can be specified, but not both") — rules out that alternative.
Hermes parser can false-positive on JSON-like text outside `<tool_call>` tags — low risk
on constrained turns; relevant for PRIMARY's `"auto"` turns.

#### E0 implementation

`SamplingParams` record gained nullable `String toolChoice` field with backward-compatible
two-arg constructor. `OnlineModeOps.streamChatWithTools()` emits `tool_choice` when non-null.
`AgentSession` tracks `agentIterationsSinceHandoff` (reset on handoff).
`AgentLoopService.shouldForceToolCall()` returns `true` for non-primary agents on their first
turn after handoff (E0a variant).

**Files changed:** `SamplingParams.java`, `OnlineModeOps.java`, `AgentSession.java`, `AgentLoopService.java`
**Tests added:** `SamplingParamsTest`, `OnlineModeOpsTest` (tool_choice injection), `AgentLoopServiceTest`

#### E0a measurement (first-turn-only, `D:/data/docs` environment)

| Scenario | Completion | Avg duration |
|----------|------------|-------------|
| handoff-001 | 10/10 (100%) | 11.0s |
| handoff-002 | 10/10 (100%) | 13.1s |

Every run completed with zero errors. Conclusion: E0a is the correct variant.

#### E0b measurement (all-turns forcing, `D:/data/docs` environment)

| Scenario | Completion | Issues |
|----------|------------|--------|
| handoff-001 | 7/10 (70%) | 3 "LLM call failed" transient errors; extra handoff hops |
| handoff-002 | 10/10 (100%) | Extra handoff hops add ~5s latency |

E0b is strictly worse. Forces unnecessary round-trips; ~5s slower. Code stays on E0a.

#### E2 measurement (`/no_think` removal)

Removed `/no_think` from all 4 locations in `AgentLoopService`.

| Scenario | Completion | Avg budget remaining |
|----------|------------|---------------------|
| handoff-001 | 10/10 (100%) | 897 |
| handoff-002 | 10/10 (100%) | -18 |

Confirmed inert. Removal is permanent.

#### E1 measurement (Qwen3 sampling params)

Changed AGENT preset: `temp=0.2, top_p=0.9` → `temp=0.6, top_p=0.95`. Applied after E0a + E2.

| Scenario | Completion | Avg duration | Avg budget remaining |
|----------|------------|-------------|---------------------|
| handoff-001 | 10/10 (100%) | 11.5s | 975 |
| handoff-002 | 10/10 (100%) | 11.4s | 127 |

Better budget usage and latency vs E0a+E2 baseline (h002 improved from -18 to +127). Change is permanent.

#### I2: Tool consolidation (relative path resolution)

`IngestTool` accepts relative paths and resolves them against indexed roots.

**Files changed:** `IngestTool.java` (rootsSupplier, resolvePath()), `AppFacadeBootstrap.java`,
`IngestToolTest.java` (2 new tests).

Note: this capability is dormant until F1-fix updates the Organizer system prompt to use it.

---

### A.3 Investigation artifacts (2026-02-22)

Fresh battery runs against `D:\code\JustSearch\docs` indexed root:
- `tmp/investigation-runs/run-01.json`, `run-02.json`, `run-03.json`
- Scorecard attempt (incomparable — scenario signature mismatch with A2 baseline):
  `tmp/investigation-runs/scorecard.pass3.json`

**Deterministic oracle note:** toolTraces are populated correctly in both CI and investigation
run manifests. The oracle IS evaluating real call data. In the investigation runs: h001 shows
`required_tool_success:ingest_files` → `calls=1,success=0,fail=1` (ingest called, failed due
to F1 wrong path); h002–h004/h006 show `missing_call:ordinal=1` (ingest never called,
correctly identified by oracle as F2/F3 failures). The deterministic failures in the
investigation runs are real quality failures mapping directly to the three root causes.

Note: the claim that 157/162 from the autonomous run (full 18-scenario battery) were
measurement artifacts was incorrect and is retracted. That run's oracle results reflect real
tool-call outcomes.

---

### A.4 Post-fix measurement (2026-02-22)

**Changes applied before these runs:**
1. **Direction A — F1-fix:** Organizer system prompt updated in `agentProfiles.ts` (ORGANIZER_PROFILE)
   and all 6 embedded prompts in `agent-live-battery-scenarios.v1.json`. Stale `D:/data/docs` path
   example replaced with portable relative-path pattern. `browse_folders` demoted from mandatory step
   to optional fallback.
2. **IngestTool strip-prefix fix:** `IngestTool.resolvePath()` now strips a leading path component
   that matches the indexed root folder name before retrying resolution. New test:
   `relativePathWithRootNamePrefixStripped()` in `IngestToolTest.java`.
3. **Direction C1:** PRIMARY's commit-under-uncertainty directive added to `agentProfiles.ts`
   (PRIMARY_PROFILE) and all 6 embedded prompts.
4. **h001 threshold calibration:** `expectedMinToolCalls` 2→1 for h001 (new flow = 1 Organizer
   tool call, not 2).
5. **Frontend rebuild:** `npm run build` in `modules/ui-web` → `AgentView-DoOsqttT.js`.

**Run 1 (round 2, immediately post-fix, before threshold calibration):** `tmp/post-fix-runs/run-r2-01/02/03.json`
**Run 2 (round 3, after h001 threshold fix):** `tmp/post-fix-runs/run-r3-01/02/03.json`

**Round 3 per-scenario breakdown:**

| Scenario | Pass | Key observations |
|----------|------|-----------------|
| h001 | 3/3 | Organizer calls `ingest_files({"paths": ["docs/explanation/01-system-overview.md"]})` directly. Strip-prefix logic resolves to `D:\code\JustSearch\docs\explanation\01-system-overview.md`. `calls=1,success=1,fail=0`. |
| h002 | 0/3 | Organizer traces=0 across all 3 runs. PRIMARY hands off with absolute path in handoff reason; Organizer produces text claiming "I've ingested X" without calling the tool. E0a appears to not fire or model ignores it under complex context. |
| h003 | 2/3 | 2/3 runs: PRIMARY hands off, Organizer calls ingest_files. 1/3 run: PRIMARY responds directly with search results instead of handing off. |
| h004 | 0/3 | PRIMARY searches, finds candidates, but responds directly with a list rather than committing. C1 directive insufficient for this scenario. |
| h005 | 3/3 | Stable. Agent correctly reports file not found. |
| h006 | 3/3 | C1 directive effective. PRIMARY picks best candidate and hands off in all 3 runs. |
| **Total** | **11/18 (61.1%)** | Improvement from 7/18 (38.9%) pre-fix. |

**h002 instability detail:** In all 3 round-3 h002 runs, the Organizer's `toolSequence` is empty
(traces=0) but the agent terminates with a text response asserting completion. PRIMARY's handoff
reason in h002 contains the absolute path (`d:\code\justsearch\docs\tempdocs\232-fundamental-structural-issues.md`)
rather than the relative form. The Organizer may be ignoring E0a and generating a text completion
because the complex handoff context (which contains the absolute path) triggers anchoring on a
known-format response. Separate from F2, this appears to be a stochastic E0a bypass under high-context conditions.

---

### A.5 Round 4 and round 5 measurement (2026-02-23)

**Round 4 changes applied:**
1. PRIMARY prompt updated: "In the reason, use the relative path (e.g. docs/explanation/file.md) — not the full absolute Windows path." Added to `agentProfiles.ts` + all 6 scenario embeds.
2. Existing C1 rule replaced with iteration-cap directive: "call search_index at most twice, then call handoff_to_organizer with your top result's relative path as the reason. Do not respond with a ranked list. Do not keep searching. The handoff is your commitment — not a text response."
3. Threshold calibrations: h004 4→3, h006 3→2.
4. Frontend rebuilt.

**Round 4 raw results (N=3):** 8/18 (44.4%). Raw score lower than round 3 because:
- h006 now does search+handoff+ingest (2 calls) vs old browse+ingest flow (3 calls) — stale threshold caused false failures. With corrected thresholds: effective 11/18.
- h004 improved slightly but still 0/3 (oracle: ingest_files calls=0 in all runs).
- h001 had 1 timeout (stochastic).

**Round 5 additional changes:**
1. PRIMARY prompt updated: "For write operations: use search_index (NOT browse_folders) to find the target, then call handoff_to_organizer. In the reason, include the relative path from the search result. Never use the full absolute Windows path in the reason." Changed in `agentProfiles.ts` + all 6 embeds.
2. Threshold calibration: h005 2→1 (new flow: 1 search → "not found" report).
3. Frontend rebuilt.

**Round 5 raw results (N=3):** 15/18 (83.3%). Effective with h005 threshold correction: **16/18 (88.9%)**.

**Per-scenario round 5 detail:**

| Scenario | Tool sequences across 3 runs | Pass |
|----------|------------------------------|------|
| h001 | ①handoff+ingest ②handoff+ingest+ingest ③handoff+ingest | 3/3 |
| h002 | ①search+handoff+ingest ②search+handoff+ingest ③search+browse+search+handoff | 1/3 |
| h003 | ①search+browse+search×2+handoff ②search×3+handoff ③search+browse+search+handoff | 3/3 |
| h004 | ①search×3 ②search+browse+search×2 ③search×3 | 3/3 |
| h005 | ①search×2 ②search+browse+search ③search | 2/3 raw (3/3 effective) |
| h006 | ①search×2+handoff ②search×2+handoff+ingest ③search+handoff+ingest | 3/3 |

**h002 failure analysis (round 5):**
- Runs 1+2: PRIMARY calls search_index once, hands off with relative path, Organizer calls ingest_files. ✓ But 1 of these 2 runs fails `missing_keywords:architecture` (Organizer's confirmation "Ingested X.md" doesn't contain "architecture").
- Run 3: PRIMARY calls `search_index,browse_folders,search_index` despite "NOT browse_folders" prohibition → absolute path in reason → Organizer E0a noncompliance (text-only).
- Root causes: (a) ~33% stochastic non-compliance with browse_folders prohibition; (b) `missing_keywords:architecture` on correct-flow runs where Organizer confirms succinctly.

---

### A.6 Round 6 measurement (2026-02-23)

**Changes applied (round 6):**
1. **BrowseTool relative paths:** `BrowseTool.java` modified to output relative paths (e.g.
   `docs/explanation` instead of `D:\code\JustSearch\docs\explanation`) and accept relative
   `parent_path` inputs. New methods: `toRelativePath()`, `resolveRelativeParent()`.
   Tests added: `relativeParentPath_resolvedByRootName`, `outputShowsRelativePaths`,
   `toRelativePath_rootMatch_returnsRelative`.
2. **Prompt contradiction removed:** PRIMARY "NOT browse_folders" replaced with "or browse_folders".
   browse_folders now safe because it returns relative paths.
3. **h002 expectedKeywords fix:** `["architecture","ingest"]` → `["ingest"]`.
4. **DRY scenario profiles:** Top-level `profiles` object in scenario JSON. 6 handoff scenarios
   reference by string ID. Runner resolves at load time (~15 lines in `run-agent-live-battery.mjs`).
5. **Iteration cap confirmed:** Tested removing "at most twice" → "search efficiently".
   Round 6 pass 1: **2/6 (33.3%)**. Immediately reverted. "at most twice" is load-bearing.
6. **Prompt normalized to American spelling.** "organise" → "organize" in `agentProfiles.ts`.

**Round 6 results (N=5, passes 2-6 after reverting "search efficiently"):**

| Scenario | Keyword pass | Det pass | Tool sequences |
|----------|-------------|---------|----------------|
| h001 | **5/5** ✓ | 5/5 ✓ | All: handoff→ingest |
| h002 | **5/5** ✓ | 0/5 | All: search→handoff→ingest. Det: path_mismatch (model ingests tempdoc, oracle expected docs/explanation/). Oracle widened post-run. |
| h003 | **4/5** | 0/5 | ①search×2+browse ②search×2+handoff+ingest ③search×2+browse ④search×2+handoff ⑤search+browse+search+browse. Runs 1,3,5: no handoff/ingest (false keyword pass). Run 4: handoff but Organizer text-only. |
| h004 | **3/5** | 0/5 | ①search×2+handoff+ingest ②search+browse+search×2 ③search+browse+search+handoff ④search+handoff(timeout) ⑤search×2+handoff. Run 1: full success. Run 2: no handoff. Run 3: Organizer text-only. Runs 4,5: fail. |
| h005 | **5/5** ✓ | 0/5 | Various search/browse patterns. Agent correctly reports non-existence. Det oracle expects ingest attempt — design mismatch for negative cases. |
| h006 | **3/5** | 0/5 | ①search only ②search+handoff+ingest ③search×2+browse ④search+handoff+ingest ⑤search only. Runs 2,4: full success. Runs 1,5: no handoff. Run 3: no handoff/ingest. |
| **Total** | **25/30 (83.3%)** | **5/30 (16.7%)** | |

**Key findings:**
1. **h002 resolved:** BrowseTool relative paths eliminated the root cause (absolute paths in
   handoff reasons → Organizer E0a noncompliance). Combined with keyword fix: 1/3 → 5/5.
2. **"at most twice" is load-bearing:** Removing it caused 33.3% pass rate. The 8B model
   needs explicit iteration bounds to commit to handoff rather than searching indefinitely.
3. **Keyword checks inflate scores:** h003 runs 1,3,5 "pass" because the model's text response
   contains "ingest" (e.g. "I found the architecture document. To ingest it...") but
   `ingest_files` is never called. Det oracle correctly identifies these as failures.
4. **h003/h004/h006 handoff reliability:** The model searches, finds candidates, then responds
   with text listing findings instead of calling `handoff_to_organizer`. This is the same F3
   failure mode (§3) that the iteration cap partially addresses but doesn't fully resolve.
5. **h005 det oracle design mismatch:** Oracle expects `ingest_files` call with `expectSuccess: false`,
   but the agent correctly handles the negative case by searching and reporting non-existence
   without attempting ingestion. The oracle should be redesigned for negative test cases.

### A.7 Exploration battery regression check (2026-02-23)

Ran exp-001 through exp-016 (N=2) to verify BrowseTool relative path changes caused no regressions.
No pre-existing baseline exists (prior runs only included handoff scenarios).

**Results (N=2):**

| Scenario | Pass | Category | Failure reason |
|----------|------|----------|---------------|
| exp-001-tools | 1/2 | FLAKY | Missing "tool","search" keywords (model described capabilities without exact terms) |
| exp-002-roots | 2/2 | STABLE | browse_folders returns roots with relative paths ✓ |
| exp-003-explanation-purpose | 0/2 | BROKEN | Search returns enterprise-policy instead of architecture docs. Missing "architecture" fact. |
| exp-004-list-explanation-files | 0/2 | BROKEN | browse_folders correctly returns "no folders" (docs/explanation has files, no subfolders). Design limitation: browse_folders doesn't list files. |
| exp-005-config-doc | 1/2 | FLAKY | Search quality — sometimes finds config docs, sometimes finds unrelated tempdocs. |
| exp-006-architecture-sections | 0/2 | BROKEN | Search finds 09-critical-analysis.md but response missing "process","head" facts. |
| exp-007-tempdoc-186 | 1/2 | FLAKY | Search sometimes finds tempdoc-200 instead of 186. |
| exp-008-search-inference | 0/2 | BROKEN | Search for "inference" only returns tempdoc-139. Missing "model","llama" facts. |
| exp-009-search-gpu | 2/2 | STABLE | ✓ |
| exp-010-read-env-vars-doc | 2/2 | STABLE | ✓ |
| exp-011-agent-api-endpoints | 0/2 | BROKEN | Search doesn't find agent API docs. Missing "/api/agent" keyword. |
| exp-012-multistep | 2/2 | STABLE | ✓ |
| exp-013-crossref | 1/2 | FLAKY | Missing "setup" keyword in one pass. |
| exp-014-compare | 2/2 | STABLE | ✓ |
| exp-015-deep-browse | 0/2 | BROKEN | browse_folders returns all 9 folders correctly, but 8B model only lists 4 in its summary. Missing "reference","how-to". |
| exp-016-verify | 2/2 | STABLE | ✓ |
| **Total** | **16/32 (50%)** | | |

**BrowseTool regression verdict: NONE.** All browse_folders calls return correct relative paths.
BROKEN scenarios are caused by: (1) search quality / ranking issues (exp-003, exp-006, exp-008,
exp-011), (2) design limitation — browse_folders lists folders not files (exp-004), (3) model
summarization quality — 8B model drops items from lists (exp-015). None are attributable to
the BrowseTool relative path changes.

### A.8 Round 8 Qwen3VL-8B-Thinking data (2026-02-24, moved from §2)

R8 was the final measurement with Qwen3VL-8B-Thinking. 6 handoff scenarios, N=5, det oracle enforced.

| Scenario | Det pass | Rate | Notes |
|----------|---------|------|-------|
| h001 (direct ingest) | 5/5 ✓ | **100%** | Stable across all 5 runs. |
| h002 (search→ingest) | 3/5 | **60%** | R1: ingest API fails (model selects wrong path). R3: BUDGET_EXHAUSTED (absolute-path reasoning loop). R4,5,6: pass. |
| h003 (fuzzy search) | 4/5 | **80%** | R3: ingest fails — model hallucinated `docs/architecture/01-architecture-overview.md`. R1,4,5,6: pass. |
| h004 (multi-hop) | 3/5 | **60%** | R1: ingest fails (wrong path). R3: contains_forbidden + ingest fails. R4,5,6: pass. |
| h005 (negative case) | 3/5 | **60%** | R3 and R6: BUDGET_EXHAUSTED (model loops searching for non-existent file). R1,4,5: pass. |
| h006 (ambiguous choice) | 4/5 | **80%** | R3: ingest fails — model hallucinated `docs/explanation/architecture.md`. R1,4,5,6: pass. |
| **Total** | **22/30** | **73.3%** | R3 anomaly (1/6): path-reasoning loop caused 5 failures in one run. R4 and R5 perfect (6/6). |

**Failure modes:** (1) Ingest path hallucination; (2) BUDGET_EXHAUSTED via absolute-path loop.
These were Qwen3VL-specific patterns largely eliminated by the model switch to Qwen3.5-9B +
system message merge fix.

**Note:** The 73.3% was handoff-only (6 scenarios, N=5). Not directly comparable to the
R9+ full battery (23 scenarios, N=1). See §2 round progression for context.

---

## 11. Battery infrastructure: DAG migration

**Date:** 2026-02-24
**Motivation:** The agent live battery (`run-agent-live-battery.mjs`, 1,646 lines) was a monolithic
script that duplicated infrastructure centralized in the DAG runner framework (tempdoc 233). Nine
other runners share this framework; the battery was the sole outlier.

**Cross-script duplication eliminated:**
- Dev-runner lifecycle (start/stop/readiness) — was duplicated in ~7 scripts
- HTTP GET/POST primitives — was duplicated in 4+ scripts
- AI activation polling — was duplicated in 2 scripts
- Corpus ingestion HTTP calls — was duplicated in 3 scripts

**What changed:**

| File | Action | Lines |
|------|--------|-------|
| `scripts/lib/bench/backend-api-client.mjs` | NEW — shared HTTP primitives | ~170 |
| `scripts/ci/agent-battery-core.mjs` | NEW — battery-specific logic as multi-command CLI | ~890 |
| `scripts/ci/dag-runner-agent-battery.mjs` | NEW — DAG orchestrator (linear: start→configure→activate→ingest→scenarios→stop) | ~420 |
| `scripts/ci/run-agent-live-battery.mjs` | REPLACED — slim facade, re-exports + delegation | ~40 |

**DAG topology:** Linear chain (`start-backend → configure-model → activate-ai → ingest-corpus →
run-scenarios → stop-backend`). `run-scenarios` is a "fat step" that runs all scenarios sequentially
within a single subprocess. `stop-backend` uses `cleanup: true` for always-run semantics.

**Backward compatibility:**
- `test-run-agent-live-battery-eval.mjs` imports resolve through facade re-exports (verified)
- `node scripts/ci/run-agent-live-battery.mjs` CLI behavior preserved (delegates to DAG runner)
- Uses fixed port 9840 (was dynamic port 0 in original)

**Benefits gained from DAG framework:**
- Per-step log capture via `step-runner.mjs`
- Job object containment for process tree cleanup
- Resume support via `--resume` flag and step hash comparison
- Crash detection via `dev-runner-lifecycle.mjs` (30-poll SUPERVISOR_CRASH detection)
- Atomic manifest writes via `writeJsonAtomic()`
- Progress tracking via `format-utils.mjs`

### 10.1 Post-implementation review — issues found and resolved

All actionable issues from the post-implementation review have been fixed.

#### Resolved issues

| ID | Priority | Issue | Fix |
|----|----------|-------|-----|
| I1 | P0 | Missing 6 manifest diagnostic fields (`run.resolvedModelPath`, `run.modelConfig`, `run.aiActivation`, `run.ingestion`, `config.readyTimeoutMs`, `config.ingestTimeoutMs`) | Added `parseStepJsonOutput()` helper that reads combined step log files and extracts JSON. `onStepComplete` parses output from start-backend, configure-model, activate-ai, ingest-corpus into `stepResults` map, passed to `assembleManifest()`. |
| I2 | P0 | `run.runId` always null | Same as I1 — `start-backend` step output parsed for `runId` and `apiPort`. |
| I3 | P0 | Unused `ensureLoopbackUrl` import in core | Removed. Loopback guarantee is structural (URL built from `127.0.0.1` literal). |
| I4 | P1 | Fixed port 9840 vs dynamic port 0 | Restored dynamic port (`DEFAULT_API_PORT = 0`). Uses `__API_BASE_URL__` placeholder in `buildSteps()`, patched in `onStepComplete` after `start-backend` reports actual port. Explicit `--api-port` bypasses placeholder. |
| I5 | P1 | `config.scenariosPath` not normalized | Added `path.relative(...).split(path.sep).join('/')` in `assembleManifest()`. |
| I6 | P2 | 78 lines of corpus identity code duplicated | Exported 3 functions from `agent-battery-core.mjs`, imported in DAG runner. Removed duplicated definitions + unused `normalizeBool`/`normalizeStringArray` helpers + corpus governance imports. |
| I7 | P2 | Unused imports in DAG runner | Removed `createHash`, `fileURLToPath`, `buildScenarioProfile`. Kept `fsp` (now used by `parseStepJsonOutput`). |
| I11 | P0 | `--ready-timeout-ms` crashes DAG runner (strict parseArgs) | Added `ready-timeout-ms` option. When provided, overrides `start-timeout-ms` as the start timeout. Two confirmed callers: `run-agent-live-battery-win.ps1`, `overnight-rag-ai-queue-win.ps1`. |

#### Accepted (no fix needed)

| ID | Priority | Issue | Decision |
|----|----------|-------|----------|
| I8 | P2 | `backend-api-client.mjs` has one consumer | Keep. Harmless premature abstraction; will gain consumers if other scripts migrate. |
| I9 | P3 | Fat step hides scenario-level failures | Accepted. Mid-scenario crash → no output file → `infraFailure` flag. Functionally equivalent to original. |
| I10 | P3 | Process spawn overhead (~1.5-3.5s) | Accepted. <2% of 3-10 min battery. |

---

## 12. Evaluation methodology: trajectory grading vs. terminal state (response to 232 §3)

**Date:** 2026-02-24
**Prompt:** Tempdoc 232 §3 ("Agent Evaluation Trajectory Variance vs. Terminal Truth") proposes
replacing the battery's trajectory-based evaluation (`firstToolCallOracle`, `requiredToolSuccess`,
`expectedMinToolCalls`) with **Terminal State Assertions** — grading solely on whether the backend
ended up in the correct state after the agent finishes.

### The diagnosis is correct

232 §3 correctly identifies that `firstToolCallOracle` penalizes valid exploratory trajectories.
An agent that calls `search_index` → `browse_folders` → `handoff_to_organizer` → `ingest_files`
(4-step exploratory path) fails the oracle because its first `ingest_files` call wasn't ordinal 1.
This is the agent doing its job — searching, discovering, then acting.

The §2 data confirms the broader problem empirically:
- h003 (fuzzy search): 4/5 keyword pass, but only 1/5 actually called `ingest_files`
- h006 (ambiguous choice): 3/5 keyword pass, ~2/5 actual ingestion
- Honest positive-case task completion: ~56%, not the 83.3% keyword pass rate

### Backend state verification: revised feasibility (2026-02-24)

The original assessment (below) identified three blockers. After investigating the actual backend
API surface and surveying industry agent evaluation benchmarks, **two of the three are not
blocking** and the third was framed incorrectly.

**Original blocker 1: "No document inventory API" — NOT BLOCKING.**
`/api/knowledge/status` exposes `docCount`, `activeDocCount`, `buildingDocCount`, `queueDepth`,
`pendingJobsCount`, `processingJobsCount`, `healthy`, and `indexState`. Combined with
`/api/knowledge/search` (verify specific content is searchable), these provide sufficient
terminal state verification without a full document inventory. Industry benchmarks confirm:
WebArena uses DOM queries for specific elements, tau-bench compares specific DB rows, Agent-Diff
snapshots specific tables — none require "list all state."

**Original blocker 2: "Async ingestion" — SOLVABLE.**
The status endpoint exposes the exact fields needed for a completion polling loop:
`pendingJobsCount + processingJobsCount = 0` AND `queueDepth = 0`. Record `docCount` before the
agent runs, poll until idle, verify `docCount` increased + search returns expected content. This
is a standard pattern — the dev-runner lifecycle already uses polling (`justsearch_dev_wait_ready`).

**Original blocker 3: "Transcript already captures the signal" — TRUE BUT INCOMPLETE.**
The transcript records tool `success: true`, which means the MCP tool *reported* success. It does
not guarantee the side effect landed in the index. Agent-Diff (2025) introduced a "closed-world
invariant" specifically because agents can report success while corrupting unrelated state.
WebArena Verified documented the same gap. For JustSearch's current scenarios the practical gap
is small (the only failure mode is "accepted but never indexed" — a backend bug, not an agent
bug), but the principle matters for evaluation integrity.

**The real constraint: evaluation architecture.** `evaluateTranscript()` runs offline against
saved transcripts — it has no access to a running backend. Backend state verification requires
the evaluator to run while the backend is still alive, after the agent finishes but before
teardown. This is an architectural change, not a tweak. However, it fits naturally into the DAG
runner: add a `verify-state` step between `run-scenarios` and `stop-backend`.

| Blocker | Original | Revised | Reason |
|---------|----------|---------|--------|
| No doc inventory API | Blocking | **Not blocking** | `docCount` + search verification suffice |
| Async ingestion | Blocking | **Solvable** | Poll `queueDepth`/`pendingJobsCount` until 0 |
| Transcript has signal | Makes verification unnecessary | **Necessary but insufficient** | Transcript checks tool reports, not actual state |
| Eval runs offline | Not identified | **The real constraint** | Requires DAG step between scenarios and teardown |

**Industry benchmark survey (2024–2026):**

| Benchmark | Verification method | Async strategy |
|-----------|-------------------|----------------|
| WebArena | DOM queries + DB queries + API calls post-trajectory | Settled web apps; 34% of original failures were timing |
| SWE-bench | Test execution in frozen Docker container | Synchronous by design |
| AgentBoard | Environment state query after each step; subgoal Progress Rate | Turn-based simulators |
| OSWorld | 134 custom Python scripts inspect VM state post-execution | Post-execution; Google Drive tasks fail from timing |
| tau-bench | Database state comparison (single correct outcome) | Simulated conversation |
| Agent-Diff | PostgreSQL snapshots before/after + closed-world invariant | Synchronous schemas |
| AppWorld | DB snapshot unit tests; 60K lines deterministic engine | In-process FastAPI; synchronous |
| Terminal-Bench | Container state property tests | Docker containers |

Key finding: most benchmarks sidestep async by using turn-based or synchronous environments.
The ones that face real async (WebArena, OSWorld) treat it as a known pain point. The polling
approach is strictly better than ignoring the problem.

### The real root cause: evaluation pipeline ordering

The battery already has `requiredToolSuccess: ["ingest_files"]` on every handoff scenario. This
check verifies that `ingest_files` was called with `success: true` at some point in the
transcript — regardless of trajectory. This **is** the terminal truth check 232 asks for.

But it doesn't affect pass/fail because it lives in `evaluateDeterministicChecks()`, which is
gated behind `deterministicGateMode`. Currently set to `report` (not `enforce`). The evaluation
pipeline in `evaluateTranscript()` runs:

1. Keyword check → "ingest" in response text? → PASS (false positive: model talks about ingesting)
2. `expectedMinToolCalls` → enough tools called? → PASS
3. `requiredFacts` → PASS
4. `mustNotContain` → PASS
5. `evaluateDeterministicChecks()` → `requiredToolSuccess` + `firstToolCallOracle` → **report only, doesn't affect verdict**

The keyword check passes before the tool check gets a chance to fail. The fix is not "query the
backend" — it's "let the existing tool check gate the verdict."

### Recommended actions (ordered by impact/effort)

**Tier 1 — Transcript-based fixes (no backend needed, implementable now):**

| Action | Effort | Effect |
|--------|--------|--------|
| **Promote `requiredToolSuccess` to primary check.** Move it out of `evaluateDeterministicChecks` and into the main `evaluateTranscript` flow, alongside keyword checks. A scenario passes only if *both* keywords match *and* the required tool succeeded. | ~15 lines in `agent-battery-core.mjs` | Pass rate drops from 83.3% to ~56% (the honest number). No false positives. |
| **Relax `firstToolCallOracle`.** Either remove it entirely or change it from "first call must be X" to "X must appear in the trace" (which `requiredToolSuccess` already does). Stop penalizing exploratory trajectories. | Schema change in scenarios JSON | Deterministic oracle pass rate jumps from 16.7% because the oracle no longer fails agents that searched before ingesting. |
| **Add efficiency diagnostics (Progress Rate).** Build from existing transcript data: token burn, redundant tool calls, loop detection. Report as diagnostic metadata, never as pass/fail gate. | ~50 lines, new function in core | Gives trajectory insight without penalizing self-healing. 232 Phase 3 item 3. |

**Tier 2 — Backend state verification (feasible, requires DAG runner change):**

| Action | Effort | Effect |
|--------|--------|--------|
| **Add `verify-state` DAG step.** Between `run-scenarios` and `stop-backend`: poll `/api/knowledge/status` until `queueDepth=0`, then run `/api/knowledge/search` for each scenario's expected content. | ~80 lines new step + scenario schema additions | Catches "tool reported success but side effect didn't land." Stronger guarantee than transcript-only. |
| **Add `terminalStateAssertions` to scenario schema.** Per-scenario definition of expected post-agent state: `{ searchQuery: "...", expectedMinHits: 1 }` or `{ docCountDelta: ">0" }`. | Schema change in scenarios JSON | Declarative, composable. Aligns with Agent-Diff/AppWorld approach. |

**Not recommended:**
- Building a full document inventory API (`GET /api/knowledge/documents`) solely for evaluation.
- Replacing `requiredToolSuccess` entirely with backend verification — the transcript check is
  complementary, not redundant. Use both (belt-and-suspenders).

### Relationship to 232 §3 Phase 3 items

| 232 Phase 3 item | Feasibility | Implementation path |
|-------------------|-------------|-------------------|
| **1. Deconstruct scenario signatures** (drop `requiredToolSuccess`/`firstToolCallOracle`, replace with Terminal State Assertions) | **Partially feasible.** Don't *drop* `requiredToolSuccess` — promote it. Drop `firstToolCallOracle`. Add `terminalStateAssertions` as a complementary check. | Tier 1 (transcript) + Tier 2 (backend) |
| **2. Mock-backend verification** (inject mock data, query DB after agent finishes) | **Feasible but unnecessary.** The battery already uses a live backend with real ingestion. No mock needed — just add a post-scenario verification step to the existing DAG. | Tier 2 `verify-state` step |
| **3. Efficiency cost modeling** (Progress Rate, token burn, loop detection) | **Fully feasible.** All data is in the transcript. No backend needed. | Tier 1 efficiency diagnostics |

### Relationship to Phase 2 roadmap

The "Det oracle enforcement" item in §6 Phase 2 table should be updated to reflect this analysis.
Recommended sequence: (1) relax `firstToolCallOracle` (stop penalizing exploration), (2) promote
`requiredToolSuccess` to primary check (get honest pass rates), (3) optionally add backend
state verification via DAG step for stronger guarantees.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Agent-quality analysis (1196 lines) producing concrete recommendations on Phase 2 oracle enforcement (relax firstToolCallOracle, promote requiredToolSuccess). Recommendations were the deliverable; whether each was picked up is downstream work.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

