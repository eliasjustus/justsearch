---
title: "Agent-eval concurrency ceiling: a single cell's wall-clock is ~90% Anthropic API time, not backend time — the GPU semaphore is not what raising concurrency would hit"
type: tempdocs
status: "measured (2026-07-08) — a live, corrected-after-a-real-bug timing-breakdown experiment (4 cells @ concurrency 1, 6 cells @ concurrency 6) confirms 675's Post-pilot theorization §H hypothesis. Not yet a design or an implementation; a measurement + a flagged next-step decision. No PR opened."
created: 2026-07-08
updated: 2026-07-08
category: agent-eval / jseval / performance
related:
  - 675-agent-eval-executor-v2-in-process   # source of the open question (§Post-pilot theorization H/I) and the pilot this extends
  - 624-agentic-retrieval-eval-rebuild      # the certified run this concurrency ceiling would eventually matter for
principle: "before raising a fan-out concurrency knob to chase a suspected shared-resource ceiling, measure where a single unit's own wall-clock actually goes — a resource assumed to bind (because it's the one you can bench in isolation) may be nearly idle under real load, while the actual ceiling is something you never benched at all."
---

# 699 — Agent-eval concurrency ceiling

## Context

675's Post-pilot theorization (§H, 2026-07-08) named an unresolved attribution question: the search
backend's own ceiling was measured directly (`bench-concurrency`, bare queries, ~6.7 qps, GPU-saturated
on the reranker semaphore) — but a real agent cell spends most of its wall-clock on Anthropic API
round-trips between tool calls, not on backend calls, so "6 concurrent agent sessions" is very likely a
much smaller number of concurrent *backend requests*. Nobody had measured a single cell's own internal
time breakdown to confirm this. This tempdoc is that measurement, done directly at the user's request as
a follow-up once 675 merged.

## Method

A throwaway instrumented driver (not committed — same evidentiary standard as 675's other live checks)
reused the *real* production building blocks — `agent_utility_inspect._PROMPT`,
`agent_retrieval_eval.build_disallowed_tools`, the same `ClaudeAgentOptions`/MCP-config shape — around a
raw `ClaudeSDKClient` message loop, condition C only (file tools disallowed, so every cell must exercise
JustSearch), haiku, `max_turns=100`, against a live locally-ingested `battlefield-en-v1` backend.

Every message from `client.receive_response()` was timestamped. The wall-clock of each cell was split
into:
- **`anthropic_time`** — the gap between a tool-result (or the initial query) and the *next* assistant
  message: the model "thinking" with nothing in flight.
- **`justsearch_tool_time`** — the gap between an assistant message's `mcp__justsearch__*` tool_use and
  its matching tool_result: real backend + MCP round-trip time.
- **`file_tool_time`** / **`other_tool_time`** — same, for any other tool (should be ~0 under condition
  C, which disallows file tools).
- **`overhead`** — wall-clock minus the above (connection setup, SDK bookkeeping).

**A real bug was found and fixed before trusting any number here** (`interrogate-results` discipline):
the first cut only advanced the "waiting" boundary on tool-result events, not on assistant-message
events, so repeated assistant messages with no intervening tool call double-counted the same growing
interval — the very first run showed `anthropic_time` at 195–244% of wall-clock, an impossible number
that was the tell. Fixed by advancing the boundary on every assistant-message event too; the corrected
run's percentages sum to ~100% per cell (sanity-checked explicitly, not just eyeballed).

## Results

**Concurrency = 1** (4 fresh cells, sequential, uncontended baseline):

| cell | wall (s) | anthropic | anthropic % | justsearch | justsearch % | turns | js calls |
|---|---|---|---|---|---|---|---|
| q0 | 72.4 | 67.7 | 93.5% | 2.18 | 3.0% | 49 | 7 |
| q1 | 87.6 | 81.0 | 92.4% | 2.91 | 3.3% | 50 | 8 |
| q2 | 27.6 | 25.3 | 91.8% | 0.97 | 3.5% | 22 | 3 |
| q3 | 38.7 | 36.8 | 95.1% | 0.00 | 0.0% | 15 | 0 |

Aggregate: mean wall/cell 56.6s, **Anthropic-API share 93.1%**, **JustSearch-backend share 2.7%**.

**Concurrency = 6** (6 fresh cells, all launched simultaneously, matching the throughput pilot's operating
point):

| cell | wall (s) | anthropic | anthropic % | justsearch | justsearch % | turns | js calls |
|---|---|---|---|---|---|---|---|
| q0 | 86.0 | 77.4 | 90.0% | 6.86 | 8.0% | 50 | 13 |
| q1 | 156.0 | 142.6 | 91.4% | 12.14 | 7.8% | 78 | 33 |
| q2 | 108.5 | 100.5 | 92.6% | 5.58 | 5.1% | 60 | 13 |
| q3 | 90.7 | 78.9 | 87.0% | 10.06 | 11.1% | 63 | 17 |
| q4 | 152.8 | 137.3 | 89.8% | 11.43 | 7.5% | 111 | 25 |
| q5 | 90.2 | 78.9 | 87.5% | 8.99 | 10.0% | 67 | 23 |

Aggregate: mean wall/cell 114.1s, **Anthropic-API share 89.9%**, **JustSearch-backend share 8.0%**.
Batch wall-clock (slowest cell) 161.5s for 429 total turns across the 6 cells → **~2.7 turns/sec ≈ ~160
requests/min aggregate Anthropic API call rate** at this concurrency (each assistant-message turn is one
API round-trip).

## Interpretation

1. **The backend is confirmed nowhere near its ceiling at agent-concurrency 6.** Implied simultaneous
   backend-request load: `concurrency × justsearch_time_fraction` ≈ `6 × 0.08` ≈ **0.48 concurrent
   requests** on average — a small fraction of the ~6.7 qps / ~8-way point where the bench-measured GPU
   reranker semaphore saturates. The justsearch share even went slightly *up* under 6-way contention
   (2.7%→8.0%), consistent with mild real backend queueing at higher load, but the absolute magnitude is
   still tiny. This closes 675 §H's open question with a real number, not just a structural argument: the
   agent-eval matrix's Amdahl ceiling (per 675 §D/§Reach) is **not currently binding** at concurrency 6,
   and 675's earlier "0-exclusion result suggests headroom" hedge is now a measured fact, not a hedge.
2. **The dominant cost is Anthropic API round-trip time (~90–93% of every cell's wall-clock,
   concurrency-independent in this range).** This means the executor-side levers already shipped in 675
   (single pool, in-process cell) were correctly targeted — the thing worth optimizing was never backend
   throughput, it's the number of model round-trips per cell and how many cells run in parallel against
   the *model*, not the search index.
3. **The turn-rate math points at the Anthropic account rate-limit tier as the more likely real
   ceiling**, matching 675 §I's concern (a shared resource with unrelated interactive Claude Code work on
   the same account) — but this tempdoc does **not** confirm that empirically. ~160 RPM at concurrency 6
   is a real number but not a breaking point; whether it's comfortably inside or already brushing the
   account's actual tier ceiling is unknown without either checking the account's tier directly (not done
   here — no tool access to Anthropic Console account settings) or deliberately ramping concurrency until
   a 429 appears.

## What this tempdoc does NOT do, and why

**Deliberately did not push concurrency further to find the actual rate-limit breaking point.** Doing so
means intentionally trying to exhaust a shared, account-wide resource — exactly the risk 675 §I named:
this account's rate-limit budget is shared with *other active Claude Code sessions* on the same machine
(this session directly observed dev-stack contention from at least one other concurrently-running agent
during this investigation). Deliberately provoking a 429 could throttle or degrade someone else's
unrelated, live interactive session. This is a "could affect shared systems beyond your local
environment" action per this project's own risk discipline — worth a explicit go-ahead, not a default
next step to take unprompted.

## Recommended next step (not done here, needs explicit authorization)

If more concurrency headroom is wanted: a short, bounded ramp (e.g. concurrency 10 → 16 → 24, a handful
of cells each) specifically watching for `429`/rate-limit errors in the SDK's `ResultMessage.errors` or
`api_error_status` field (already captured by 675's forensic executor) would find the real ceiling
directly and cheaply — but should be scheduled at a moment when contention with other active sessions on
the account is low, and the person running it should know in advance it might visibly slow other
concurrent Claude Code usage for its duration.

## Evidence / reproduction

Not a committed script (throwaway, matching 675's own established pattern for one-off live measurements).
Reproduction: instrument `ClaudeSDKClient.receive_response()`'s message loop with per-event
`time.monotonic()` timestamps as described in §Method, reusing `agent_utility_inspect._PROMPT` and
`agent_retrieval_eval.build_disallowed_tools("C")` for a faithful cell shape, against a live locally
ingested `battlefield-en-v1` backend. Raw per-cell timelines were saved to local job-scratch JSON during
this session (not committed — ephemeral working-session output, per the same discipline that keeps
`$CLAUDE_JOB_DIR`-style paths out of tempdoc prose).
