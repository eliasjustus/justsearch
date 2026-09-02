---
classification: declared-growth
tempdoc: 885
---
Declares the three configuration keys tempdoc 885 item 19 adds for the NRT reopen
strategy: `index.nrt.mode` (default `continuous`), `index.nrt.background_reopen_ms`
(default 2000) and `index.nrt.on_demand_max_stale_ms` (default 1000). Net
`env_sysprop_pairs` growth is exactly these three; `yaml_keys` and `config_keys`
are unchanged.

A fourth key, `index.commit.idle_ms`, was declared in the first version of this
changeset and has been **deleted** rather than shipped. The live window
(885 §"Item 19 live") measured it and the measurement said no: the commit-cadence
lever cannot work at all while `CommitOps.COMMIT_TIMER_INTERVAL_MS` is a hardcoded
10 s safety net that fires whenever `pendingDocs > 0` — deferring the indexing
loop's commits simply hands more work to that timer (16 -> 46 commits), and the
reason-tagged commit histogram shows enrichment-backfill commits dominate the
population anyway (61 of 114 in the control arm), which these keys do not govern.
A knob that cannot move its own metric is not worth a config key, so it is gone and
the finding is a tracked item instead. This is the changeset's original commitment
("the losing arm's keys are removed, not left behind") being honoured.

Why the surviving three are keys rather than constants: item 19 is decided **by
measurement**, and the reopen axis has not been cleanly measured yet. The first
attempt rejected the *implementation* (the on-demand seam also fired on
enrichment-backfill reads, tripling reopen count); that defect is fixed in this PR,
and the arm needs a re-run on a quiet machine before the mode can be judged. Keeping
the arm selectable at launch is what makes that re-run a config change rather than a
rebuild — and a rebuild between arms is itself a confounder.

Every default reproduces today's behaviour exactly: `continuous` keeps the
`ControlledRealTimeReopenThread` on the existing `index.nrt.*` bounds and leaves the
foreground search path untouched. All four clean default arms in the live window
behaved as the pre-885 code did.

All three resolve onto `ResolvedConfig.Index` and reach the Worker through the
ordinal-450 config snapshot rather than a raw `EnvRegistry` read in the Worker JVM —
the same choice item 3 made after 885 [R1] found a Worker-side knob that could never
reach the Worker. `NrtCadenceConfigForwardingTest` pins the Head → snapshot → Worker
round-trip for all three, and the live window confirmed it end-to-end
(`/api/debug/effective-config` reported `source: env_var` for exactly the knobs each
arm set).

Post-measurement expectation is unchanged: if the corrected reopen axis does not
earn its keep, these three come out too.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the keys it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `env_sysprop_pairs` | 246 | **249** | +3 = exactly the three keys above |
| `yaml_keys` | 108 | **111** | +3 = the same three (they resolve from YAML as well as env) |
| `config_keys` | 56 | 56 | unchanged |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on the merged tree
(`yaml_keys=111 env_sysprop_pairs=249 config_keys=56 rows=305`). The pre-merge pin of 246/108 was
what `main` measured after #600; this branch adds three keys and no others, so the advance is fully
attributable and the ratchet keeps its meaning — it still only ratchets DOWN from here.
