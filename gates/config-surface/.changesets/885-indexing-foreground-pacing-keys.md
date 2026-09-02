---
classification: declared-growth
tempdoc: 885
---
Declares the two configuration keys tempdoc 885 item 3 adds when it replaces the
Worker's breath-hold pause with a foreground-contention duty cycle:
`justsearch.indexing.foreground_duty_pct` (default 20) and
`justsearch.indexing.foreground_cooldown_ms` (default 500). Net
`env_sysprop_pairs` growth is exactly these two.

Why they are keys rather than constants: the duty is the one number the item's
acceptance measures (arm (c) of the throughput comparison must reach at least the
configured minimum duty where it previously reached zero), and 20% is explicitly a
starting guess to be measured — a build-time constant would make the measurement
require a rebuild. The cooldown exists because the gauge alone reads idle in the
gaps between short queries.

Both resolve onto `ResolvedConfig.Ai.BackfillPacing` and reach the Worker through
the ordinal-450 config snapshot rather than through a raw `EnvRegistry` read in the
Worker JVM. That is deliberate: 885 [R1] found the previous Worker-side knob
(`justsearch.eval.disable_breath_holding`) could never reach the Worker at all
because it was only ever set as a Head system property, and
`ForegroundPacingConfigForwardingTest` now pins the snapshot round-trip. That dead
key is deleted in the same change, but it was a bare `Boolean.getBoolean` read and
never an `EnvRegistry` entry, so it was never counted by this metric: the net growth
is the full +2.
