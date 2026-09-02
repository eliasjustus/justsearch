---
classification: declared-growth
tempdoc: 885
---

`env_sysprop_pairs` 243 → 244: three keys for the extraction sandbox pool (tempdoc 885 item 14) —
`justsearch.extraction.sandbox.pool`, `.heap` and `.max_requests`.

**Why the count moved only now.** These three landed at 242, under the 243 pin, and the gate passed.
Merging `origin/main` brought in lane A's two `justsearch.llm.*` keys, and the sum crossed. The
growth being declared here is this branch's three, not main's two.

**They are the operator surface of a failure domain that previously had none.** Tempdoc 410 shipped
an out-of-process extraction sandbox with exactly two keys — a mode and a raw argv the operator had
to author — and it was unreachable as shipped, because nothing produced that argv. Item 14 replaces
the argv-authoring burden with a command the Worker builds itself, and the three keys are what is
left once that is gone:

- `pool` — how many child processes exist. This is the concurrency of out-of-process extraction, and
  it is a machine-shape choice (the tempdoc's own open question is whether a ≥8-core box should
  default to 2), not a tunable to sweep.
- `heap` — `-Xmx` for a child. The default is derived, not guessed: at least 4x the largest accepted
  input with a 512m floor, because POI needs 10-20x a document's size in heap. An operator who
  raises `worker.limits.max_file_size` gets a larger child heap without touching this key; it exists
  for the case where the derivation is wrong for their corpus.
- `max_requests` — the leak guard. A long-lived Tika process accumulates parser state, so the child
  is retired on a request budget. The alternative was an RSS probe, which needs a platform-specific
  memory API and is not deterministically testable.

**Why none of them is deletable.** Each names a resource the operator may have to bound on a machine
we cannot see: how many JVMs, how much heap each, how long each lives. The two keys 410 declared
cannot express any of that — `mode` selects a strategy and `command` was the escape hatch that made
the feature unusable. The count is +3 on a surface that went from "unreachable" to "on by default",
which is the trade this gate exists to make visible rather than to forbid.

**Withdrawal condition.** `pool` collapses if lane F's single-JVM engine removes the process boundary
(tempdoc 885 §R9 lists the pool as surviving that merge, so this is not expected). `max_requests`
retires if the leak it guards is fixed upstream in Tika and the budget can go to unlimited. Neither
is deferred to a sweep: both are named here so a later reader can test them.
