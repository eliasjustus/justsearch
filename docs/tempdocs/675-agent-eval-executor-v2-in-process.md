---
title: "Agent-eval executor v2: replace the claude-CLI subprocess shellout with an in-process executor so cell failures are observable, resumable, and forensically complete"
type: tempdocs
status: "open — design pass done (2026-07-07), no implementation yet. STUB→design: §Design pass (2026-07-07) records a measured attribution of the ~3h wall (520-cell matrix; wall dominated by the max_tasks=1 condition-serialization 2× + a tail-heavy agent-loop per-cell mean, NOT the search backend) plus a bench-backed lever ranking. Owner priority (2026-07-07): CUT WALL-CLOCK, not decouple — levers are the single concurrency pool + the in-process cell (neither touches inference placement). CPU-inference offload / dedicated-CPU-backend was surfaced by the attribution and REJECTED by the owner (a slower CPU run holds the box longer AND contends for the CPU dev itself needs, so it slows all dev for longer — faster-on-GPU = shorter exclusive hold = less disruption). Original trigger (still valid): the next certified agent-utility run or re-certification event. Spun out of tempdoc 624's certified-run session (2026-07-03), which hardened the subprocess executor enough to finish but demonstrated its structural ceiling."
created: 2026-07-03
updated: 2026-07-03
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
