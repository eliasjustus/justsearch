---
classification: declared-growth
tempdoc: 885
---
Declares the four configuration keys tempdoc 885 item 19 adds for the NRT/commit
cadence candidate: `index.nrt.mode` (default `continuous`),
`index.nrt.background_reopen_ms` (default 2000), `index.nrt.on_demand_max_stale_ms`
(default 1000) and `index.commit.idle_ms` (default 0). Net `env_sysprop_pairs`
growth is exactly these four; `yaml_keys` and `config_keys` are unchanged.

Why they are keys rather than constants: item 19 is decided **by measurement**, not
by argument. The comparison table (throughput, search p95, p95 of the first search
after N new segments, commit count, reopen count) needs the two arms run against the
same build on the same corpus, so the arm has to be selectable at launch. A
build-time constant would make each arm a rebuild and each rebuild a confounder.
Every default reproduces the pre-885 behaviour exactly — `continuous` keeps the
`ControlledRealTimeReopenThread` on the existing `index.nrt.*` bounds and leaves the
foreground search path untouched, and `index.commit.idle_ms=0` keeps the historical
commit-on-first-empty-poll — so the shipped surface is unchanged until an operator
opts in.

Why four and not fewer: `mode` selects the arm; the other three are the arm's
parameters and each is a number the measurement is expected to move. The background
cadence and the foreground staleness bound are independent (the first is how often
an unattended index reopens, the second is how stale a query will tolerate its view
being), and the commit knob is on a different axis entirely — it changes durability
cadence, not visibility, and exists because the idle trigger firing on every
momentary queue drain is what makes `justsearch.backfill.commit_interval_ms` and
`max_docs_before_commit` unobservable during a bulk run.

All four resolve onto `ResolvedConfig.Index` and reach the Worker through the
ordinal-450 config snapshot rather than through a raw `EnvRegistry` read in the
Worker JVM. That is the same deliberate choice item 3 made for the pacing keys:
885 [R1] found a Worker-side knob that could never reach the Worker because it was
only ever set as a Head system property. `NrtCadenceConfigForwardingTest` pins the
Head → snapshot → Worker round-trip for all four.

Post-measurement expectation: the losing arm's keys are removed, not left behind.
