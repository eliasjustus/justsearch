---
classification: declared-growth
tempdoc: 885
---

Declares ONE configuration key: `index.commit.timer_interval_ms` /
`JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS`, default `10000`. Net `env_sysprop_pairs` growth is
exactly this key; `yaml_keys` and `config_keys` are unchanged.

**Why it is a key and not a constant.** It replaces `CommitOps.COMMIT_TIMER_INTERVAL_MS`, a
hardcoded 10 s safety-net timer that commits whenever `pendingDocs > 0` regardless of which write
path produced them. Tempdoc 885's live window recorded it as the reason the commit-cadence lever
could not be measured at all: with the indexing loop's own `buffer` trigger driven to zero by the
candidate key, this timer's share rose 16 → 46 — deferring the loop's commits keeps `pendingDocs`
above zero for longer and hands the safety net MORE work. The window's own tracked follow-up says it
in those words: *"`CommitOps.COMMIT_TIMER_INTERVAL_MS` must become configurable before any
commit-cadence work"*, and *"do not re-run a commit-cadence arm as designed"* until it is. This
changeset is that prerequisite, nothing more — it ships no cadence change and makes no claim about
which period is right.

**The default reproduces today's behaviour exactly.** `10000` is the constant it replaces;
`CommitOpsTest.commitTimerDefaultsToTenSecondsAndRefusesANonPositiveInterval` asserts the scheduled
period on an unconfigured runtime, so the no-change arm is pinned rather than assumed. A
non-positive value is refused with a WARN and falls back, because a zero-period `scheduleAtFixedRate`
would spin the commit thread — a boot-time knob must not be able to do that.

**It resolves onto `ResolvedConfig.Index` and reaches the Worker through the ordinal-450 config
snapshot**, not a raw `EnvRegistry` read inside the Worker JVM. `CommitOps` runs in the Worker, and
885 [R1] already found one Worker-side knob whose only setter lived on the Head, so it could never
fire. `CommitTimerConfigForwardingTest` walks the whole Head → snapshot → Worker path for this key,
the same shape `NrtCadenceConfigForwardingTest` pins for the three cadence keys.

**The enum entry is appended at the END of `EnvRegistry`**, per the cross-lane append rule.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the key it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `env_sysprop_pairs` | 249 | **250** | +1 = exactly the key above |
| `yaml_keys` | 111 | 111 | unchanged — the key has no YAML contribution |
| `config_keys` | 56 | 56 | unchanged |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=111 env_sysprop_pairs=250 config_keys=56 rows=306`). The pre-change pin of 111/249/56 is
what `main` measures after #602, so this branch's delta is fully attributable and the ratchet keeps
its meaning — it still only ratchets DOWN from here.
