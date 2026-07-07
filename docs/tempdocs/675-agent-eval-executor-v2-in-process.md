---
title: "Agent-eval executor v2: replace the claude-CLI subprocess shellout with an in-process Agent-SDK cell run in one concurrency pool — cells become observable/resumable/forensically complete, and the same substrate change removes the run's structural wall-clock waste (per-condition serialization + unbounded retry/turn budgets) so the measurement runs at cadence"
type: tempdocs
status: "IMPLEMENTED (2026-07-07) on branch worktree-675-executor-v2 — live-smoke validated end-to-end; see §As-built (2026-07-07) for the delivered change + validation. Read §Settled design for the design intent (current truth); §Design pass (attribution + bench-backed lever ranking), §External research (Agent SDK parity — one premise corrected: the Python Agent SDK still spawns a CLI subprocess per cell, so the win is forensics/retry-fix/max_turns, NOT subprocess removal — moot for wall-clock since the backend's measured ~6.7 qps ceiling + the max_tasks=1 2× bind first), and §Theorization (open reframes) precede it as dated history. Settled v2 = an in-process Agent-SDK cell + ONE concurrency pool (matrix folded from one-task-per-condition into one task, cells as samples, condition a sample field) + per-cell wall-clock budget + generous max_turns; Inspect resume, 624's cell-identity seam, and the utility-comparison.v1 record are PRESERVED not rebuilt. Owner priority (2026-07-07): CUT WALL-CLOCK; CPU-inference offload / dedicated-CPU-backend REJECTED (slower CPU run holds the box longer + contends the CPU dev needs). §Settled design names the exact ORPHANS whose deletion/tombstoning is THIS tempdoc's work (classic run_agent_eval + _get_reflection + _build_agent_cmd + _build_argv + build_disallowed_tools + the max_tasks=1 construct + cmd_agent_eval; shared helpers relocate first) and hands the statistical/cadence reframes to 624/673 (recognized, not built here). Reach: v2 instantiates a candidate 'two-tier/continuous measurement' shape (+ Amdahl-ceiling, attribute-signal, memoize-invariant-arm), each recorded with an earn/retire condition. Trigger (still valid): the next certified agent-utility run or re-certification event. Spun out of tempdoc 624's certified-run session (2026-07-03), which hardened the subprocess executor enough to finish but demonstrated its structural ceiling."
created: 2026-07-03
updated: 2026-07-07
author: agent retrospective (624 certified-run session), filed by agent — STUB
category: agent-eval / jseval / execution-infrastructure
related:
  - 624-agentic-retrieval-eval-rebuild        # origin — the executor's current form (Inspect AI + claude -p subprocess) and every failure this stub cites
  - 673-agent-utility-standing-regression-ratchet  # a future consumer — the cheap standing gate runs through the same executor
  - 674-cross-family-grader-local-model-infrastructure  # sibling infrastructure in the same eval stack
principle: "a measurement cell whose failures are forensically blind cannot vouch for its own loss-accounting — the executor must be able to say WHY a cell died, not just that it did. 624's run-governance extended honesty to the run; this extends it to the cell."
---

> Noncanonical working tempdoc. STUB: goals and context only — no design decisions, no implementation
> specifics. The design pass should start from 624's own already-named option ("the in-process
> Agent-SDK executor... the second-time-you-run-this-at-scale move", named twice in 624 before the
> evidence below existed) and evaluate it against alternatives on its merits.

# 675 — Agent-eval executor v2 (in-process)

## Goal

Make the agent-utility eval's per-cell execution **observable, resumable, and forensically complete**,
so that a cell failure is always attributable from the record itself and never requires live
reproduction to diagnose. Today's executor (Inspect AI driving `claude -p` subprocesses,
`scripts/jseval/jseval/agent_utility_inspect.py`) was hardened enough during 624's certified run
(2026-07-03) to produce clean records, but the hardening treats symptoms of one structural property:
**the cell is a black-box subprocess**.

## Context — what the 2026-07-03 certified-run session demonstrated (all documented in 624, twenty-first/twenty-second passes)

- **Silent cell deaths with no forensics anywhere**: ~5%/cell under sustained 8-way load, rc=1, empty
  stderr, no result event, no CLI-side logs. Root cause never established; mitigated (bounded disclosed
  retry + `stdin=DEVNULL`), not explained. An unexplained failure mode in the measurement substrate is a
  standing credibility threat.
- **Timed-out cells lose their partial evidence** — the tool calls a cell made before its timeout never
  reach the record, a blind spot in the credibility bar's "every cell actually run" assertion.
- **Retry semantics interact badly with slot scheduling** (a retried timeout can hold a concurrency slot
  for twice the calibrated budget).
- **Resume and mid-run observability are brittle**: dirty-log refusals force fresh directories and
  re-spend; retry-rewrites scrub errored samples so mid-run state contradicts final state; the session's
  monitoring instruments were repeatedly misled by log-flush semantics.
- **No first-class run monitor exists** — the session hand-rolled four ad-hoc watchdogs, one of which
  (built on a projection not designed for partial logs, since fixed) aborted two healthy runs.
- **Usage/cost visibility is nonstandard** because the harness cannot see inside the subprocess (Inspect's
  own `model_usage` is empty for shell-out solvers; everything flows through hand-stashed metadata).

- **(Added 2026-07-03, post dead-config discovery)** Two parallel executors now exist with
  **asymmetric guards**: the Inspect runner gained config fail-fast + per-cell offered-MCP-surface
  assertion; the classic runner (`agent_retrieval_eval.run_agent_eval`) deliberately did not — a
  standing mechanism-fork whose halves will drift. Executor v2's scope must include resolving the
  two-runner question (consolidate, or explicitly retire one), not only replacing the subprocess layer.

## What v2 must preserve (the parts that work and are load-bearing for the records)

Cohort/cell identity as the resume + pairing key; per-cell tool-call capture with
disallowed-tool/leak-suspect assertions; the calibrate-before-spend governance path; per-cell disclosed
retries as record fields; compatibility with `utility-comparison.v1` composition and the leak-scan /
judge / panel post-passes. The record shape is settled (624); only the execution substrate is in scope.

## Explicit non-goals

Not a redesign of the record, the governance gates, the judge, or the statistics — those are 624's and
they are done. Not a general-purpose eval framework: the scope is the expensive-agent-cell executor that
624's "one identity, three roles" analysis already established as the only place this investment pays.

---

## Direction (2026-07-03, settled for the implementing session — design review done, fork resolved)

**The fork** was: deepen the Inspect integration vs. replace the executor wholesale with an
Agent-SDK loop. **Resolved: keep Inspect as the executor shell; replace the CELL's subprocess with an
in-process Agent-SDK session.** Rationale, in order of weight:

1. **The failure catalog is entirely cell-interior.** Every forensic blindness this doc cites (silent
   CLI deaths, lost timeout evidence, invisible tool results, the dead-config class itself) lives
   inside the `claude -p` subprocess boundary. Inspect's contributions (durable per-sample resume,
   adaptive concurrency, schema-valid EvalLog, epochs-as-seeds) all sit OUTSIDE that boundary and
   were empirically validated twice (624 Confidence-pass #2, the certified runs). Replacing the shell
   re-solves solved problems; replacing the cell dissolves the actual disease: an SDK session exposes
   the tool stream, tool RESULTS (which the trace capture never had), usage, and errors as objects —
   no stdout parsing, no pipe semantics, no config file that can silently not connect (the MCP
   surface is constructed programmatically and is assertable as a value, not via an init-event
   disclosure).
2. **The timing argument — do it NOW-ish, not later:** the arm-invalidation reset (twenty-third
   pass) means there is **zero comparability debt**: no valid with-tool history exists that an
   executor change would fracture. This is the cheapest moment this migration will ever have; after
   the Step-2 real run produces the first valid records, the cohort-comparability cost of swapping
   executors returns.
3. **Sequencing tension, resolved explicitly:** the campaign (624 pass 24) wants Step 1/2 soon. The
   Step-1 adoption pilot and Step-2 run may proceed on the hardened subprocess executor (its guards
   are now adequate for validity) — executor v2 is NOT a blocker for them. But if the implementing
   weeks allow, v2-first is preferred: it removes the ~5%/cell retry noise and the temporal-confound
   workaround below. Founder's scheduling call; both orders are sound.

**Design constraints settled here** (the implementing agent should not re-litigate these):
- **Interleave conditions within one concurrency pool** (fixes the max_tasks=1 temporal confound —
  serialized condition-tasks run arms in disjoint time windows, exposing arm comparisons to
  API-condition drift; a single pool over the full cell matrix restores contemporaneous arms at the
  calibrated concurrency).
- **Tool RESULTS enter the per-cell record** (bounded/truncated), not just tool calls — the
  mechanism analyses were blinded by call-only capture (annex 04's own caveat).
- **Per-cell wall-clock budget** replaces per-attempt timeout (a retried cell must not hold a slot
  for 2× the calibrated budget).
- **The two-runner question:** the classic runner (`agent_retrieval_eval.run_agent_eval`) is retired
  to smoke/diagnostic status the day v2 is trusted, with a deprecation note naming v2 — not silently
  kept as an unguarded fork.
- **Parity risks to verify, not assume** (the honest unknowns for the implementing session): SDK
  agent-loop behavioral parity with the CLI (system prompt, tool defaults, permission semantics),
  MCP-over-HTTP support parity, and cost/usage accounting equivalence — each gets a one-cell A/B
  probe against the CLI path before any full run migrates.

---

## Design pass (2026-07-07) — measured attribution + the wall-clock lever ranking

> This pass moves the doc from STUB to a settled, evidence-backed design **for the throughput
> objective specifically**. It applies 691's method (attribution → allocation → optimization: pick
> no lever until measurement says what dominates). New this pass: a live concurrency bench + a
> query-path serialization code-trace that together pin where the ~3h actually goes. The Direction
> section above (in-process cell, single pool) is *reaffirmed and quantified*, not revised.

### Owner framing (2026-07-07): the objective is wall-clock, and decoupling is not a substitute

The motivating pain (624's "holds the shared dev stack for hours, which is the real constraint",
`624:707-708`) has two conceivable answers: make the run **faster**, or **decouple** it from the
shared stack (a dedicated ephemeral instance, floated at `624:1019`). The owner settled this: the
objective is **cutting wall-clock**. A slower-but-decoupled eval is *worse* for parallel
development, not better — it holds the box **longer** and, if it offloads inference to CPU to spare
the GPU, it contends for the CPU that dev itself needs, so it slows all dev over a longer window.
Faster-on-GPU = shorter exclusive hold = less disruption. **Consequence: cutting wall-clock IS the
dev-unblocking lever; the two needs collapse into one.** The wall-clock levers below (single pool +
in-process cell) do not touch inference placement, so this framing leaves them intact and only
removes the CPU-offload branch from consideration (see Rejected).

### Attribution — where the ~3h goes (the "before allocation" step)

Matrix = **520 cells** = 26 queries × 2 conditions (A,B) × 5 seeds × 2 corpora (EN/DE), haiku agents
(`624:3914`, `624:3931` "520/520 cells"). Judge / leak-scan / cross-family grader / statistics are
all **post-hoc and cheap** (`624:1157-1166`), so the 3h is *purely* the agent matrix. Wall identity:

```
matrix_wall ≈ n_conditions × (260 cells × mean_per_cell) ÷ concurrency  +  ~40min calibration/ingest
```

Three waste terms, largest first:

1. **`max_tasks=1` serializes the two conditions → a clean 2× multiplier**
   (`agent_utility_inspect.py:365-374`). It is a *contention workaround* (concurrent condition-tasks
   multiplied in-flight cells to ~16 effective → 40% arm-B timeout exclusions), **not** a physical
   need. A single concurrency pool over the whole matrix at the calibrated cap removes the 2× while
   *also* fixing the temporal confound this doc already names (arms run contemporaneously). Single
   biggest lever.
2. **Per-cell mean is tail-heavy (~130s) vs the 78s median.** There is **no `--max-turns` cap
   anywhere** in either runner — a cell is bounded only by `--max-budget-usd 0.50` and the timeout,
   and observed cells span **8→31+ tool calls** (`624:4267`). The 31-turn tail drags the mean; the
   naive `520×78s÷8 ≈ 84min` is half the projected 3h precisely because the mean, not the median,
   sets the wall.
3. **~40 min calibration/ingest** per run (`624:3777-3778`).

### Measured backend ceiling (new evidence, 2026-07-07) — the backend is NOT the wall

**Structural (code-trace):** nothing serializes at the gRPC/Lucene layer, but the two GPU stages
inside every query each funnel through a per-model `Semaphore(1)` — query-embedding
(`NativeSessionHandle.java:110,279`, "only one GPU run() in flight at a time") and the cross-encoder
reranker (its own separate `Semaphore(1)`). **CPU sessions bypass the semaphore entirely.**

**Empirical** (`jseval bench-concurrency`, `golden/battlefield-en-v1`, RTX 4070 12GB, dense-off /
reranker-active index):

| concurrency | p50 ms | p95 ms | qps | GPU util |
|---|---|---|---|---|
| 1 | 204 | 258 | 4.5 | — |
| 4 | 583 | 663 | 6.3 | — |
| 8 | 1027 | 1047 | **6.8** | 92–98% |
| 16 | 264¹ | 1405 | 12.8¹ | 92–98% |

¹ N=16 percentiles unstable (26 queries ÷ 16 streams ≈ 1.6/stream) — treat as noise.

Two clean facts: (a) per-query latency scales **~linearly** with concurrency (the `Semaphore(1)`
signature); (b) throughput **saturates at ~6.7 qps with the GPU at 92–98%** — the reranker alone
nearly saturates the GPU one-in-flight. Consequences:

- **"Raise `--parallel`" is exhausted.** Past ~8, concurrency buys latency, not throughput — so
  624's operating point of 8 was empirically correct, and there is no free wall-clock in more
  parallelism against a saturated GPU.
- **The backend is a ~19-min floor, not the wall.** 520 cells × ~15 searches ÷ 6.7 qps ≈ 19 min of
  pure backend time over the whole matrix — real, but far below 3h. The agent loop (Anthropic
  round-trips + file reads) dominates. So the wall-clock levers must attack the *client/orchestration*
  side, not the search backend.
- **Caveat:** the measured index had dense retrieval disabled (legacy-fingerprint state on a reused
  data dir), so this exercised only the reranker semaphore. Real hybrid adds the embed-semaphore
  queue on top — 6.7 qps is therefore an *upper bound* on backend throughput; the contention is at
  least this, never less. A clean dense-on re-measure would tighten the number but cannot change the
  ranking (backend already ≪ wall).

### Lever ranking (settled for wall-clock)

1. **Single concurrency pool interleaving conditions** — kills the `max_tasks=1` 2×; fixes the
   temporal confound. Already the Direction section's first constraint; the attribution now
   quantifies it as the single largest lever. No backend change.
2. **In-process Agent-SDK cell** — removes per-cell `claude -p` cold-start + MCP handshake (paid
   520×; each cell today is a fresh subprocess with a fresh `tempfile.mkdtemp` cwd, no session
   reuse), the retry-holds-slot-for-2×-budget waste, and the ~5%/cell silent-death retry noise;
   and it **enables deliberate prompt caching of the shared prefix** — the harness today sets **zero**
   `cache_control` (grep-confirmed), so the identical system prompt + tool definitions + corpus
   listing are re-sent uncached on every one of the 520 cells. Holds concurrency at the ~8 backend
   ceiling but sheds the "local RAM for N concurrent claude processes" tax that is part of what caps
   it. This is the Direction section's core move; the throughput case for it is now explicit, not
   only the forensics case.
3. **Turn-tail compression (secondary, measurement-sensitive).** A generous `max-turns` cap clips the
   31-turn tail that inflates the per-cell mean. Must sit well above the useful range — turn count is
   partly the *measured* signal (how the agent uses the tool), so an aggressive cap would bias the
   comparison. Treat as a tail-trim, not a budget.
4. **Progress/certify tiering (methodology, not executor code).** The owner's recurring need is
   "see progress regularly" — which does **not** need the full 520-cell publication bar each time.
   Routine tracking via a ~156-cell progress run (1 corpus × 3 seeds × 26q × 2 cond) or 673's cheap
   standing gate; the full bar only at publication milestones. Cheapest recurring-cost lever; zero
   executor work.
5. **(Only if the backend becomes the wall after 1–4)** reduce the reranker's per-query cost for the
   eval to lift the 6.7 qps ceiling — 648-adjacent (fewer candidates / lighter cross-encoder). Not
   needed while the agent loop dominates.

**Projected envelope (linear, ±, unmeasured at full scale):** levers 1+2 plausibly take the matrix
3h → ~75–90 min (the 2× removal plus subprocess/retry-overhead removal); lever 4 takes *routine*
progress runs to ~20–30 min. Numbers to be confirmed by the implementing session's first A/B.

### Rejected (recorded so it is not re-proposed)

**CPU-inference offload / dedicated-CPU-backend.** Surfaced by the attribution (CPU sessions bypass
the GPU `Semaphore(1)`, so CPU rerank/embed run concurrently; `JUSTSEARCH_EMBED_GPU_ENABLED=false` +
`JUSTSEARCH_RERANK_GPU_ENABLED=false` exist, no code change; enrichment is pre-built so the eval
backend only serves). Rejected by the owner on the framing above: slower CPU inference stretches the
exclusive hold and contends for dev's CPU — worse dev impact than a faster GPU run, not better. Kept
here only as a closed option.

### Evidence index (reproduce without this session)

- **Concurrency bench:** `cd scripts/jseval && python -m jseval bench-concurrency --dataset
  golden/battlefield-en-v1 --concurrency <N> --base-url http://127.0.0.1:<port> --mode hybrid
  --warmup 2 --allow-errors --output-dir <REPO>/datasets` — note the `--output-dir` doubles as the
  corpus `base_dir` (`bench.py:62-63` passes it to `corpora.load`), so it must point at the parent of
  `golden/…`; result dirs land under it and should be cleaned. GPU via `nvidia-smi
  --query-gpu=utilization.gpu,memory.used --format=csv,noheader -l 1`. (Table above from this box,
  2026-07-07.)
- **Serialization trace:** embed semaphore `NativeSessionHandle.java:110,279`; reranker is a separate
  `SessionHandle`/semaphore built via `composeRerankAssembly`; query-embed and enrichment-embed share
  one process-wide `EmbeddingService` (`KnowledgeServer.java:973`, `DefaultWorkerAppServices.java:283-288`)
  — so `JUSTSEARCH_EMBED_GPU_ENABLED` cannot split them (relevant only to the Rejected lever).
- **Matrix + executor facts:** cell count `624:3914/3931`; `max_tasks=1` rationale
  `agent_utility_inspect.py:365-374`; no max-turns / no `cache_control` — both runners' argv builders
  (`agent_utility_inspect.py:_build_argv`, `agent_retrieval_eval.py:_build_agent_cmd`); retry-slot 2×
  `agent_utility_inspect.py:136-238`.

### External research (2026-07-07) — parity verified against current docs, one premise corrected

Verified against current official docs (Claude Agent SDK `code.claude.com/docs/en/agent-sdk/*`,
Inspect AI `ukgovernmentbeis/inspect_ai`, Anthropic prompt-caching) rather than training priors,
because these are fast-moving and load-bearing for lever 2.

**Claude Agent SDK (Python `claude-agent-sdk`) — the Direction section's "parity risks to verify"
are RESOLVED, favourably, with two migration gaps and one corrected premise.** The SDK IS the
right in-process substrate:
- Tool calls **and** tool results arrive as structured objects (`AssistantMessage.content` tool_use
  blocks + a final `ResultMessage`) — no stdout `stream-json` parsing. Resolves the "tool RESULTS
  enter the record" constraint the annexes were blinded on.
- **MCP-over-HTTP is native** — `mcp_servers={"justsearch": {"type": "http", "url":
  "http://127.0.0.1:<port>/mcp"}}`. The offered MCP surface is a constructed *value* (assertable),
  not an init-event disclosure — this dissolves the dead-config class (the `"type":"http"` bug that
  retracted the $109/3h run, 624 twenty-fifth pass) at the source.
- Usage + cost as objects (`ResultMessage.total_cost_usd` / `.usage` / `.model_usage`) — resolves
  the "usage/cost visibility is nonstandard" item; no hand-stashed metadata.
- **`max_turns` is a first-class option** — directly enables lever 3's turn-tail cap.
- `model`, `permission_mode="bypassPermissions"`, `system_prompt` map 1:1. **Two migration gaps to
  handle, not assume:** (a) the SDK exposes `allowed_tools` (allowlist) but **no denylist** — the
  current `--disallowedTools` leak-control becomes allowlist membership (re-express the leak-scan /
  disallowed-tool assertions as "not in the offered allowlist"); (b) corpus access maps to `cwd`,
  but the current design deliberately separates an **isolated tempdir cwd** from `--add-dir
  <corpus>` precisely to avoid `CLAUDE.md`/ambient-context contamination (624 fifth pass found the
  operator's own global config could bypass isolation) — verify that isolation survives the SDK's
  `cwd` model before trusting a migrated run.
- **Premise correction (honest limit).** The Python Agent SDK **still launches a Claude Code CLI
  subprocess per `query()`/`ClaudeSDKClient` session** ("100 concurrent cells → 100 processes",
  per the SDK docs). So this doc's Direction-section framing — that the in-process cell "dissolves
  the subprocess boundary" and sheds the process-count RAM ceiling — is **wrong for this SDK**; that
  overhead is retained. It does **not** regress the baseline (`claude -p` also spawns one per cell),
  and it is **moot for wall-clock**: the search backend saturates at ~6.7 qps and the cell is
  network-bound, so client process count is not the binding ceiling — the `max_tasks=1` 2× and the
  API/backend are (see the bench + attribution above). A *truly* in-process path (agent loop built
  directly on the Anthropic Messages API + tool runner, no CLI) would remove the RAM ceiling and
  grant prompt-cache control, but it sacrifices CLI agent-loop parity (system prompt, tool defaults,
  permission semantics) with the measured baseline — a parity cost the owner's "measured, not
  assumed" bar disfavours. **Decision: take the Claude Agent SDK (parity-preserving). Lever 2's
  value is forensics + the retry-slot fix + `max_turns` + assertable MCP config — not subprocess
  removal.** The Direction section's rationale #1 (dissolve the cell-interior failure class) holds;
  its implied throughput-from-fewer-processes claim does not.

**Inspect concurrency — lever 1 stays inside the Inspect shell (no rewrite of the executor
harness).** `max_samples` = concurrent samples *within a task* (default `max_connections+1`; the
docs say set it *above* `max_connections` for tool/sandbox tasks); `max_tasks` = concurrent tasks;
both are directable mid-flight. The single-pool fix is therefore: fold the whole cell matrix into
**one task** with each cell a sample and `condition` a sample field (instead of one-task-per-
condition + `max_tasks=1`), then calibrate `max_samples` to the pool cap. Conditions interleave
contemporaneously at the calibrated concurrency — the temporal-confound fix **and** the 2× recovery,
both achievable without leaving Inspect.

**Prompt caching (lever 2-D DOWNGRADED to opportunistic).** Prefix-match, `cache_control:
{ephemeral}`, 5-min TTL, **haiku minimum cacheable prefix = 4096 tokens**, read ~0.1×. Two
constraints kill it as a *planned* lever under the Agent SDK: (a) concurrent cells racing a cold
shared prefix all pay full price — the cache is readable only *after the first response starts
streaming*, so a fan-out needs a warmed prefix (a `max_tokens:0` pre-warm, or a single lead cell)
to benefit; (b) **the Agent SDK exposes no cache control** — caching is whatever the bundled CLI
does automatically, opaque and unassertable. So deliberate prefix caching is a lever only on the
raw-API path; under the chosen Agent SDK it is opportunistic-and-opaque, not a design knob. Do not
bank a caching win in the lever ranking.

**License note (for the implementing session):** any Agent SDK example code adapted into the
executor must clear the repo's license-and-notices CI check — `anthropics/claude-agent-sdk-python`
is MIT; attribute the source if code is lifted. (This research pass copied nothing.)

---

## Theorization & open directions (2026-07-07) — options to weigh before the design settles

> **Status: exploratory, not decided.** The Design pass above is a settled *engineering* answer to
> the wall-clock objective the owner named. This section deliberately steps back and asks whether the
> objective, the measurement, and the run shape are framed the right way. Nothing here supersedes the
> lever ranking; it records directions, tradeoffs, and hidden assumptions worth examining before an
> implementation locks the design in. Several ideas conflict with each other on purpose.

### A. Reframe the objective before optimizing it

The Design pass minimizes **wall-clock of a fixed 520-cell run**. Three prior framings coexist in the
lineage and pull different ways; naming the objective explicitly is itself a contribution:

- **What is actually being minimized?** Dollars (624's "wall-clock is free overnight; money is
  not"), wall-clock (this doc's owner framing), *blocked-development-hours* (the motivating pain), or
  *information gained per unit cost*? These rank the levers differently. Cost-per-run favours fewer
  cells; blocked-dev-hours favours a non-blocking run shape (§B) over a faster one; information-per-cost
  favours spending samples only where they change the conclusion (§C). A one-line objective statement
  at the top of the eventual design would prevent optimizing the wrong quantity.
- **Attribute *signal*, not only cost.** 691's method is "attribution → allocation → optimization."
  675 adds a corollary the Design pass hints at but doesn't foreground: a companion tempdoc (673)
  found the accuracy-delta metric is **noise-dominated even at full n**. If that holds, making the
  520-cell run faster is polishing an instrument that cannot conclude — the binding constraint is the
  *metric's sensitivity*, not the runtime. Before investing in executor throughput, confirm the
  measurement can resolve the effect it exists to measure. "Attribute cost AND signal" may be the
  more general principle than "attribute cost."

### B. Cadence reframes — dissolving the "run regularly vs. blocks development" tension

The Design pass answers this with a progress/certify **tier split**. Two stronger reframes:

- **Continuous trickle instead of a monolith.** The tension only exists because a run is a *batch* that
  holds the stack for hours. If instead a handful of cells run per merge (or per idle window) and
  accumulate into a **running estimate**, the batch never exists — blocking becomes negligible per
  increment, and "regularly" becomes "continuously." This reframes the agent-utility number as a slow
  CI signal rather than an event. The cost is real and must be designed around: increments taken across
  different engine versions mix versions in the estimate, so the accumulator needs to **window or reset
  on an engine change** (a fingerprint boundary, reusing the cohort-identity machinery 624 already
  built), and each increment still pays a fixed calibration overhead unless calibration is amortized.
- **Sequential / optional-stopping designs.** The 520 cells run regardless of what they show. For the
  *common* case where the effect is clearly present or clearly absent, a fraction would settle the sign.
  Anytime-valid inference (confidence sequences / always-valid p-values) lets a run **stop early when
  the estimate is conclusive** without the false-positive inflation that naive peeking causes. This is
  the natural partner to the continuous-trickle shape (a confidence sequence *is* an always-readable
  running estimate) and directly serves "cut wall-clock" for the majority of runs where the answer is
  not on the knife's edge.

### C. Structural reuse — spend fewer agent-cells for the same conclusion

- **Memoize the invariant arm.** The paired design has one arm (condition A — the agent working over the
  fixed corpus with *no* JustSearch tool) whose result does not depend on the search engine at all.
  Across a *regression* cadence (engine vN → vN+1 on the same corpora/queries), A is invariant modulo
  API drift, so in principle only the with-tool arm needs re-running. Tempting ~2× for regression runs —
  **but in direct tension with the paired design's contemporaneity requirement**: pairing A and B within
  one run/seed is exactly what controls for shared variance and API drift (the same thing the
  `max_tasks=1` removal restores). Caching A across runs reintroduces a temporal confound between arms.
  Recorded as a real tradeoff, not a free win; it may be acceptable for the *progress* tier and not the
  *certification* tier. The general shape — "in a comparison, factor out and reuse the arm independent of
  the thing under test" — is worth naming regardless of whether it's adopted here.
- **Differential / incremental eval.** Re-run only the cells whose *inputs* changed since the last run —
  i.e., cells where the engine's results on the agent's queries actually differ. Requires recording
  backend responses per cell (which the forensic executor will capture anyway) and a diff over them.
  Bounded by the fact that a changed result can change the agent's whole trajectory, so the "unchanged"
  set is smaller than it looks — but non-trivial for small, targeted engine changes.
- **Variance-driven allocation.** Seeds and queries are currently uniform. If a few queries/corpora carry
  most of the variance in the utility delta, concentrating seeds there (stratified / importance
  allocation) buys the same power for fewer cells. Cheap to explore: the variance decomposition is
  computable from the logs already collected, before any executor change.
- **Control variates / covariate adjustment.** Use a cheap per-cell retrieval-quality signal (e.g. nDCG
  on that cell's queries) as a covariate to shrink the variance of the utility estimate at fixed n
  (a CUPED-style adjustment). Orthogonal to all the above; needs the proxy to correlate at all.
- **Cheap surrogate as a leading indicator.** If a static retrieval-quality metric *tracks* the
  agent-utility delta, it could be the continuous signal with the expensive agent run only calibrating it
  periodically. This is in **direct tension with 624's founding thesis** (agent-utility ≠ retrieval
  quality — the agent may not exploit a better engine). That tension is the point: a correlation study is
  cheap, and a *negative* result (proxy does not track utility) is itself a valuable, publishable
  confirmation of why the expensive measurement is necessary.

### D. The latent backend ceiling (know it before optimizing toward it)

The bench found the search backend saturates at ~6.7 qps, GPU-bound on the cross-encoder reranker
serialized through a per-model `Semaphore(1)`. It is **not** today's wall — but it is the ceiling every
client-side parallelism lever eventually hits (the eval's Amdahl limit). Consequences worth holding:
raising the semaphore permit count buys nothing while the GPU is already saturated; the only ways to lift
the ceiling are to **reduce rerank work per query** (fewer candidates / a lighter cross-encoder — this is
retrieval-semantics-affecting, /search-quality-register territory, and belongs to the query-latency
sibling 648, not here) or to **avoid repeat queries** (the eval fires a small fixed qrels set plus
free-form agent queries against a static corpus; a per-run, per-corpus query-result cache could collapse
much of the load — but must be *per-run scoped* to avoid the answer-key/cross-cell contamination class
624 repeatedly hit, and free-form agent queries cache poorly). None of this is needed while the agent loop
dominates; it becomes the next attribution question the moment the other levers push concurrency up.

### E. Hidden assumptions this doc has been carrying

1. That the full certified run must recur regularly (§B: it may not — a trend needs less precision than a
   run-level CI, and a continuous signal may replace the batch).
2. That the 520-cell design is fixed (§B/§C: sequential stopping and variance allocation can shrink it).
3. That both arms must be re-run every time (§C: one arm is engine-independent).
4. That agent-utility must be measured by running agents (§C: a calibrated proxy *might* track it — or
   provably might not).
5. That runtime is the binding constraint (§A: the metric's noise floor may be).
6. That the backend is not a bottleneck (§D: true now, latent ceiling always).

### F. Risks to carry into the design

- **Optimizing a measurement that cannot conclude** (§A) — the highest-order risk; guard it by confirming
  metric sensitivity before executor investment.
- **Stale/cross-time comparability bugs** from arm caching or continuous accumulation (§B/§C) — the
  stale-baseline class; any reuse across engine versions needs a fingerprint boundary and an explicit
  drift re-baseline, or the green is unreachable-seed-green.
- **Naive optional stopping inflates false positives** (§B) — only anytime-valid methods are safe.
- **Leak-control regressions in the Agent SDK migration** (Design pass: denylist→allowlist, cwd vs
  `--add-dir` corpus isolation) — silent contamination is the exact failure 624 kept hitting; treat the
  offered-tool allowlist and corpus isolation as assertions the executor checks per cell, not
  assumptions.
- **Phantom caching savings** (§ Design pass 2-D) — a prompt cache that silently never fires (haiku's
  4096-token floor, cold concurrent batches) reads as a win that isn't there; measure `cache_read` before
  claiming it.

### G. Candidate broader principle / recurring system shape (not yet a design)

675 looks like one instance of a shape that recurs across every quality axis this system measures:

> **Two-tier (or continuous) measurement.** Each quality axis needs a *cheap, continuous* signal that can
> run at the cadence decisions are actually made, plus an *expensive, high-rigor certification* that runs
> rarely and never sits on the critical path of routine work. The relevance ratchet, the performance gate
> (640), the leak scan, and the cheap agent-utility gate (673) are siblings; 675 is the agent-utility
> instance, and its "make the expensive run cheaper" problem is really "make sure the expensive tier is
> never what blocks the cheap cadence."

Two narrower shapes fall out, each potentially reusable beyond this eval:

- **Memoize-the-invariant-arm** (§C): in any comparison measurement, the arm that does not depend on the
  variable under test is a caching opportunity — bounded by whether the design needs the arms
  contemporaneous.
- **Amdahl ceiling of a shared serial resource** (§D): a saturated shared resource (here a GPU inference
  semaphore) caps throughput no matter how much client parallelism you add; identifying that ceiling is a
  prerequisite to knowing when a parallelism lever has stopped paying.

And a method refinement worth promoting if it survives scrutiny: **attribute signal, not only cost** —
extend 691's "attribution before allocation" so a measurement's *runtime* is optimized only after its
*sensitivity* is confirmed sufficient to conclude.

---

## Settled design (2026-07-07) — executor-v2 substrate, scoped to what 675 owns

> This is the design conclusion. It supersedes the open questions in §Theorization *for the executor
> substrate only*; the statistical/cadence reframes there remain open and are explicitly handed to
> their owners below. General, not implementation-level: it fixes the *shape* of v2 and the exact
> extend/replace/orphan boundary, code-verified this session (`file:line` in §Evidence and inline).

### The design in one paragraph

The measurement cell becomes an **in-process Claude Agent SDK session** (parity-preserving), so a
cell's tool calls, tool **results**, model init, and usage/cost are objects — not stdout to parse —
and the offered MCP surface is a constructed, assertable value rather than an init-event disclosure.
The matrix stops being **one Inspect task per condition run under `max_tasks=1`** (the current
structure, verified) and becomes **one task whose samples are the flat cell list**, with
`(corpus, condition, seed→epoch, query)` as the sample identity and **one bounded concurrency pool**
(`max_samples` at the calibrated cap). Each cell runs under a **per-cell wall-clock budget** (so a
disclosed retry can no longer hold a slot for twice the budget) and a **generous `max_turns` cap**
(clipping the pathological turn tail without biasing the measured tool-use — set well above the
useful range). Everything else that already works is **preserved, not rebuilt**: Inspect's durable
per-sample resume, 624's cell-identity seam, and the `utility-comparison.v1` record. Net effect: the
same substrate change that makes a cell forensically legible also removes the run's two structural
wall-clock wastes (per-condition serialization, unbounded retry/turn budgets), so the measurement is
cheap enough to run at the cadence decisions need.

### Extend / preserve — the parts that work are load-bearing and stay

- **The Inspect shell.** `eval_set` durable per-sample resume is already keyed by a **deterministic
  `eval_set_id`** (pinned from a config hash precisely so a crash-restart resumes; `agent_utility_inspect.py:355-374`)
  → resume is *satisfied*; v2 preserves it, only adjusting the sample-id shape to carry `condition`
  (e.g. `"{condition}|q{i}"`) so ids stay unique inside one task. Adaptive concurrency, epochs-as-
  seeds, schema-valid EvalLog — all retained.
- **624's "one identity, three roles" seam.** Sample id = cell identity = resume key = pairing key =
  record key. v2 conforms; it does **not** introduce a new identity or a parallel resume mechanism.
- **The record + composer.** `utility-comparison.v1` is unchanged, and `compose_utility` is decoupled
  from task/sample topology (it consumes already-reshaped summaries, `utility_comparison.py:266`) —
  so it needs no change.
- **Calibration.** Extend `utility_calibrate` to pilot the **per-cell budget at the target
  concurrency** (and optionally probe the concurrency cap), never hand-set — this keeps the
  hard-won "calibrate, don't guess" governance (624's twice-learned lesson).

### The single-pool restructure's real (bounded) blast radius

Beyond the runner itself, exactly **one** downstream reader is coupled to the one-task-per-condition
shape: `eval_logs_to_summaries` reads `condition` from **task-level** `log.eval.metadata`
(`agent_utility_run.py:170-171`); it must read it from **sample** metadata instead (seed is already
sample-level there, so the plumbing pattern exists). This adjustment is **in-scope for 675** — it is a
direct consequence of the restructure, not a 624 record change.

### Orphans — their deletion/tombstoning is THIS tempdoc's work, not a later cleanup sweep

The classic runner's file (`agent_retrieval_eval.py`) is a grab-bag; only the *classic executor* is
orphaned, and some neighbours must **relocate first** so the orphan deletes cleanly:

1. **Delete:** `agent_retrieval_eval.run_agent_eval` (the `ThreadPoolExecutor` classic execution path),
   `_get_reflection` (+ its `claude -p --resume` reflection subprocess — an arm the composer never
   reads), and `_build_agent_cmd` (its argv builder).
2. **Delete (in-place within the Inspect runner):** `agent_utility_inspect._build_argv`, its
   `subprocess.run` cell dispatch, and the one-task-per-condition + `max_tasks=1` construct —
   superseded by the Agent-SDK cell and the single pool.
3. **Delete:** `build_disallowed_tools` (the `--disallowedTools` flag builder) — superseded by the
   Agent SDK `allowed_tools` allowlist. (The *assertion* helpers `find_disallowed_tool_calls` /
   `find_leak_suspect_tool_calls` **survive**, adapted to object input.)
4. **Confirm-last-consumer, then delete:** the stdout parsers `parse_claude_stream_json` /
   `parse_claude_init_event` orphan once no path shells out for stdout; verify no surviving Tier-2
   path still uses them before removing.
5. **Remove or repoint:** the CLI subcommand `cmd_agent_eval` (`jseval agent-eval`) and the tests in
   `test_agent_retrieval_eval.py` that call `run_agent_eval` — either drop them or repoint to a v2
   smoke mode (675's Direction already elects smoke/diagnostic status with a deprecation note naming
   v2).
6. **Relocate before deleting (so the orphan's neighbours don't break):** the surviving shared helpers
   (`stage_corpus_dir`, `_score_answer`, the two assertion helpers) move to a neutral module —
   `_score_answer` alone has three other consumers (`corpus_fidelity.py`, `utility_calibrate.py`,
   `utility_judge.py`), and the file also hosts the **independent Tier-1/Tier-2 retrieval runners**
   (`run_retrieval_eval`, `run_tier2_eval`, `load_queries`, formatters) which are **not** 675's to
   touch. Leaving a "retired" module alive purely as a helper-import target is the anti-pattern the
   relocation avoids.

### Scope boundary — what 675 does NOT own (handed to owners, recognized-not-built)

The §Theorization reframes are real but belong elsewhere; 675 executes *whatever cell set it is
given* and must not absorb the measurement-design question:

- **Statistical / cadence design** — continuous trickle, sequential/anytime-valid stopping, variance-
  driven allocation, control variates, the surrogate-proxy study, and memoize-the-invariant-arm →
  **624** (matrix/record) and **673** (standing cadence/gate). 675's single-pool flat-cell executor
  makes any *subset* cheap to run, which is the mechanism those tiers need — but *which* cells and
  *when* is their policy, not this substrate's.
- **Backend query throughput** (the ~6.7 qps GPU-rerank ceiling measured this session) — **648** +
  the /search-quality register; retrieval-semantics-affecting, out of an executor's remit.
- **Record shape, judge, governance gates** — **624**, settled.

### Reach — candidate principles (named, with earn/retire; deliberately NOT built now)

Separating "recognizing a principle" from "building general structure": these are recorded as
candidate shapes, not new apparatus.

- **Primary — Two-tier (or continuous) measurement** (the shape v2 *instantiates*). Every quality axis
  needs a *cheap signal at decision cadence* plus a *rare high-rigor certification*, and the expensive
  tier must never sit on the critical path of routine work. v2 is the shared substrate that makes both
  cheap. **Applies elsewhere:** the relevance ratchet, the performance gate (640), the leak scan, the
  llm-gen gate. **Candidate existing violation:** if agent-utility's only routine-runnable tier is the
  full certification (673 defined but not the routine cadence), that is the gap v2 narrows — stated as
  a candidate, not an assertion. **Earns its keep when:** the cheap tier catches a real regression
  ahead of a certification run *and* routine development is observably not blocked by the expensive
  tier. **Retire when:** the cheap tier's false-negative rate forces routine re-runs of certification
  anyway — then it is one tier with overhead, not two.
- **Amdahl ceiling of a shared serial resource** (diagnostic). A saturated shared resource (here the
  per-model GPU inference `Semaphore(1)`) caps throughput no matter the client parallelism. **Applies:**
  anywhere client fan-out funnels into a shared GPU session (embed / rerank / NER / SPLADE all use a
  single-permit semaphore; corpus-build throughput, 691, already lives against it). **Earns its keep
  when:** it correctly predicts a concurrency raise that does not improve wall-clock (the bench already
  showed qps saturation). **Retire when:** the resource is over-provisioned relative to any realistic
  concurrency (as a *fact* it doesn't retire; as a *live lever* it does).
- **Attribute signal, not only cost** (refinement of 691's attribution-before-allocation). Optimize a
  measurement's runtime only after confirming its sensitivity can conclude. **Applies:** any standing
  gate where a "make it faster" investment is proposed. **Earns its keep when:** it correctly defers a
  throughput investment because the metric was first shown noise-dominated (673's finding is the live
  example). **Retire when:** metric sensitivity is never in practice the binding constraint — then the
  refinement is ceremony.
- **Memoize-the-invariant-arm** (from §Theorization C). In a paired comparison, the arm independent of
  the variable under test is a caching opportunity, bounded by whether the design needs the arms
  contemporaneous. **Applies:** any A/B with a fixed reference arm. **Earns its keep when:** a
  regression-tier run measurably shrinks by reusing the engine-independent arm with no comparability
  loss. **Retire when:** the temporal-drift re-baseline cost approaches the savings.

---

## Pre-implementation verification (2026-07-07) — live-probed, design de-risked

> Throwaway probes of the Claude Agent SDK (`claude-agent-sdk` 0.2.111, Python 3.14) against a live
> local JustSearch backend (battlefield-en-v1). No harness code was touched. Purpose: verify the
> feasibility claims that §External research established from *docs*, before implementation. The three
> risks that could have *invalidated* the design are all resolved live; the doc-research had two
> "migration gaps" that turned out not to exist.

**Resolved live (the design-invalidating risks):**
- **SDK ↔ `/mcp` ↔ search — WORKS.** A single retrieval cell called `mcp__justsearch__justsearch_answer`
  and returned the correct answer as **objects** (`AssistantMessage` tool_use + `ToolResultBlock`
  results + a `ResultMessage` with `total_cost_usd`/`usage`/`num_turns`). No stdout parsing. The
  forensic goal (tool RESULTS in the record) is delivered by the substrate itself.
- **Leak-control ENFORCES.** With `disallowed_tools` set (condition-C analog) and a prompt actively
  tempting a file read, the agent's `Read` call was **blocked from executing** ("I don't have access
  to the Read or Bash tools in this context") — the file was never read. Measurement validity holds.
- **Isolation via `setting_sources=None` — clean.** With an isolated `cwd` and `setting_sources=None`,
  the agent reported **"NO AMBIENT CONTEXT"** — no repo `CLAUDE.md`, no global `~/.claude` config
  leaked in. Cleaner than the CLI's isolated-tempdir trick.
- **Concurrency — real.** 8 concurrent SDK cells: **7/8 clean**, total **39.3 s** vs **129.3 s** serial
  (**~3.3×**); the 1 error was a max-turns straggler (handled as an excluded cell, parity with today).
  No races/crashes. (Reproduce: `$CLAUDE_JOB_DIR`-local probe; backend `battlefield-en-v1`.)

**Doc-research corrections (verified against the installed SDK, authoritative over §External research):**
- `ClaudeAgentOptions` HAS **`disallowed_tools`** (1:1 with `--disallowedTools` — no allowlist rewrite
  needed) AND **`add_dirs`** distinct from `cwd` (isolated-cwd + corpus-via-`add_dirs` maps 1:1) AND
  `strict_mcp_config`, `max_turns`, `max_budget_usd`, `permission_mode`, `system_prompt`,
  **`setting_sources`**. The two "migration gaps" §External research flagged do not exist.
- Build the cell on **`ClaudeSDKClient`** (not the one-shot `query()`): it exposes `get_mcp_status()` /
  `get_server_info()` (for the offered-surface assertion) plus `set_model` / `set_permission_mode` /
  `toggle_mcp_server` / `disconnect`.

**Integration details discovered (handle in implementation; none invalidate the design):**
1. **`max_turns` exhaustion raises an exception** (not a `ResultMessage`) — the executor must catch it
   and mark the cell errored (parity with today's timeout handling).
2. **`query()`'s `SystemMessage` init does not list the offered tools/mcp_servers** — the offered-MCP-
   surface assertion moves from init-event parsing to `ClaudeSDKClient.get_mcp_status()`.
3. **The leak assertion must distinguish attempted-but-blocked from executed** — a blocked disallowed
   tool still appears as a tool_use block; key the assertion on the tool_result (now an object), not
   the mere presence of the call.
4. **The default toolset is richer than the CLI's** (includes `ToolSearch`, and the agent reaches for
   `Bash` / `Agent` / `Task` — the subagent-bypass vector 624 flagged) — the disallowed set must be
   comprehensive.
5. **Packaging:** add `claude-agent-sdk` to the jseval Python deps (installed clean on 3.14; not yet in
   requirements).
6. **Convergence tuning (non-blocking):** haiku floundered a few turns (tried `Bash`/`ToolSearch`/`Agent`
   before the MCP tool) — a tighter `system_prompt`/tool-guidance would cut turns/cost; a calibration
   item, not a correctness one.

**Confidence for the remaining implementation: 8/10.** The design cannot be invalidated by what's left
— feasibility, API parity, concurrency, isolation, leak-enforcement, and the composer field-mapping are
all confirmed. The residual is well-understood *integration*: the single-pool restructure has not been
exercised end-to-end (only the SDK cell in isolation); the assertion-porting and max-turns-exception
handling are new-but-bounded; and the orphan deletion touches a grab-bag file with unrelated Tier-1/2
consumers (relocate-first mitigates). None of these is an unknown that risks the shape.

---

## As-built (2026-07-07) — implemented on `worktree-675-executor-v2`, live-validated

Delivered the settled design. The full unit suite is green (1503 tests; the only REDs are the two
pre-existing `test_correction_probe` cases, missing data file, unrelated). A live end-to-end smoke
(A+B × 3 queries × 1 seed, real haiku agents against a live JustSearch `/mcp` backend) produced a
valid `utility-comparison.v1` record. An independent second-agent review (reviewer ≠ implementer)
found one **confirmed** measurement-validity bug that the smoke had missed — fixed and regression-tested.

**What shipped**
- **Cell interior → in-process `ClaudeSDKClient`** (`agent_utility_inspect.py`): tool calls, tool
  *results*, usage/cost, and the offered MCP surface come back as objects; no stdout parsing.
  Per-cell **`asyncio.wait_for` wall-clock budget** wraps the whole cell incl. the disclosed retry;
  a generous **`max_turns`** cap; `disallowed_tools` / `add_dirs` / `setting_sources=None`
  (verified-clean isolation) / `permission_mode` set from the sample's condition.
- **Single concurrency pool**: ONE Inspect task, samples = the flat `condition × query`
  cross-product, `condition` a sample field, `sample.id = "{cond}|q{i}"`; `eval_set` without
  `max_tasks=1`. Inspect's durable resume, 624's cell-identity seam, and `utility-comparison.v1` are
  preserved.
- **Object-based assertions**: `tool_calls` (composer-facing) = only tools that ACTUALLY executed
  (non-error result, not in `permission_denials`); blocked attempts stashed separately as forensics;
  offered-surface via `ClaudeSDKClient.get_mcp_status()` with the tri-state (`unverified` never
  conflated with healthy) preserved.
- **Readers** (`agent_utility_run.eval_logs_to_summaries` + `scan_leaked_cells`,
  `utility_judge._iter_eval_records`): read `condition` from sample metadata + strip the `sample.id`
  prefix; exclude on either `metadata.error` OR Inspect's own `s.error`.
- **Teardown (same PR)**: deleted the classic runner (`run_agent_eval`, `_get_reflection`,
  `_build_agent_cmd`), the subprocess argv/stdout parsers (`_build_argv`, `parse_claude_stream_json`,
  `parse_claude_init_event`) and the `--disallowedTools` CLI formatting, `cmd_agent_eval` (+ its
  tests), and regenerated `inventory.generated.json`. `cmd_utility_compose` (compose-from-classic-
  result-files) is **tombstoned** (deprecation note → `utility-run`), not deleted, as a format-coupled
  utility beyond the executor's scope. `claude-agent-sdk>=0.2.111` added to the `[agent]` extra.

**Load-bearing implementation findings (empirically verified, worth recording)**
- Under `permission_mode="bypassPermissions"`, a disallowed tool is **removed from the agent's
  toolset** — it usually never appears as an attempt at all; `permission_denials` is empirically
  **empty**. A tool that runs and fails has `is_error=True`; a successful tool has `is_error=None` (not
  `False`). The executed-vs-blocked split therefore rests on the tool_result's `is_error`, with
  `permission_denials` a belt-and-suspenders cross-check.
- **Review-caught bug (fixed):** `ResultMessage.permission_denials` is a raw pass-through of
  **dicts** (`{"tool_name", ...}`); the first cut did `set(permission_denials)` → a latent
  `TypeError: unhashable type: 'dict'` in the measurement path, and its unit test used bare strings (an
  `unreachable-seed-green` false green). Fixed to extract `tool_name` robustly; the cell projection is
  now wrapped so ANY error marks the cell excluded rather than fabricating an included one; the test
  now uses the real dict shape.
- `max_turns` exhaustion surfaces as an errored `ResultMessage` (or a raised exception) → excluded
  cell (parity with a timeout). Cross-condition pairing keys stay aligned because all three readers
  apply the identical prefix-strip.

**Operational note** (logged to the observations shard): Inspect's rich display crashes with a
`UnicodeEncodeError` on the braille spinner when stdout is redirected/non-tty on Windows (cp1252) —
set `INSPECT_DISPLAY=none` (and/or `PYTHONUTF8=1`) for backgrounded eval runs. Pre-existing (default
display), but more relevant now that runs are long and non-interactive.

**Not done here** (out of scope, per §Scope boundary): the statistical/cadence reframes (624/673), the
backend rerank/query-cache ceiling (648). No PR opened yet.
