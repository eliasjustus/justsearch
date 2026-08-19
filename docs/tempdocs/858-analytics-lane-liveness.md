---
status: sketch (theorization only — no design, no decision)
created: 2026-08-19
updated: 2026-08-19
author: agent session (Opus 5, 1M context)
charter: half the agent-analytics stack has no live inputs; decide whether to collapse it, retire it, or leave it
---

# 858 — Analytics lane liveness

**Sketch, not a design.** Opened from tempdoc 856 §10, which found the shape while fixing one
instance of it. Nothing here is decided, and the first item below is a question for the owner,
not a task.

## The observation

`scripts/agent-analytics/` holds ~33 instruments. They split into two lanes by how they get
their inputs, and the split predicts which ones still work.

**Transcript lane** — recomputes from the harness's own session transcripts on every run.
`baseline-economics`, `cache-efficiency`, `signature-census`, `overhead-taxonomy`,
`mine-friction`. All produce current output. They can be wrong at the edges (windowing,
transcript rotation) but they cannot go stale, because they keep no state between runs.

**Events lane** — reads intermediate stores written by an earlier stage. `context-attribution`,
`analyze-trends`, `correlate-signals`, `score-session`, `outcome-session`, `generate-dashboard`.
Observed behaviour when run: N=7 of 20 usable, 1 session loaded, exit 1 on "0 joined pairs",
and `outcomes.ndjson` missing. `scores.ndjson`, `judge-outcomes.ndjson` and `dashboard.html`
carry the same modification minute, which reads more like one run than like periodic use.

Collection is healthy in both cases — the hook-written per-session counters are current. What
is stale is the aggregation between collection and report.

## The framing 856 arrived at

> A pipeline decays at its first stage that requires invocation.

Hooks fire unattended; the stage after them does not. A derived store is a cache with a refresh
obligation, and unattended refresh obligations go unmet. 856 §10.2 lists five artifacts in this
repo with that shape, of which the merge ledger was one and is now addressed.

## Open questions, in the order they should be answered

1. **Is the events lane dormant by intent?** If it is an episodic maintainer tool, staleness
   between uses is correct and most of this sketch dissolves. This is an owner question and
   should be asked before anything is measured or built. Everything below assumes the answer is
   "no"; if it is "yes", the honest output of this tempdoc is a line in the README saying so.
2. **Does anything consume the lane's output?** The repo gates product code with
   `consumer-presence` and `dead-code`. Neither is pointed at the tooling. Applying the existing
   check inward would answer this mechanically rather than by inspection.
3. **Is the lane recoverable, or is it superseded?** The transcript lane already holds richer
   data than the intermediate stores do, so the reports may be cheaper to rebuild on top of it
   than to revive in place.

## Directions, unranked and undecided

- **Collapse** — rebuild the reports as recompute-from-transcript; delete the intermediate
  stores. Removes the decaying stage rather than maintaining it.
- **Retire** — delete the lane and sweep its fingerprints in one pass, per `retire-with-a-sweep`.
  Cheapest if question 2 finds no consumers.
- **Refuse rather than degrade** — an instrument below its viable sample size exits non-zero
  instead of printing a plausible-looking result on a starved input. Small, independent of the
  other options, and worth doing whichever way 1–3 resolve.
- **Derived liveness view** — one command answering "which instruments have live inputs, and how
  fresh". Constraint that makes or breaks it: a hand-maintained manifest of what is alive becomes
  another entry in the very table this tempdoc is about. It has to read declarations that live in
  the instruments themselves.

## Adjacent gap, noted not claimed

`CLAUDE.md`'s delegate-by-default falsifier is due for judgment around 2026-09-14 and its stated
test has two halves: cost-per-shipped-merge improving, and rework not rising. **Nothing in the
stack measures rework.** Tempdoc 622 §6.3 already named the authority for it — "was the work
fixed or reverted later → git churn over subsequent sessions" — and it was specified and never
built. Whether that belongs here or in its own tempdoc is undecided; it is recorded so it is not
rediscovered a third time.

## Risks of acting on this sketch

- **Adding a stage to fix stage decay.** A liveness layer is itself a stage. If it is maintained
  rather than derived, it will decay the same way and this tempdoc will have added an instance
  of the problem it opened to describe.
- **Retiring something nearly working.** If the lane is one fix from useful, deletion loses more
  than it saves — which is why question 3 precedes any direction.
- **Surface growth.** The repo's own budget doctrine holds that more instructions get followed
  less. More instruments plausibly get run less, and this stack is already larger than the number
  of people who read it.
- **Thin evidence.** The observation comes from one session's use of the stack plus file
  modification times. Modification times are evidence of absence, not a decision record.

## What would make this tempdoc worth opening

A yes/no from question 1, and a mechanical answer to question 2. If the lane has consumers and is
intended to be live, this becomes a repair. If it has neither, it becomes a deletion. Either is a
smaller piece of work than the sketch's length suggests, and neither should start before those two
answers exist.
