---
title: "745 — OSS-first agent observability: adopt/keep/retire survey across the analytics stack"
type: tempdocs
status: "open — charter drafted 2026-07-16; no investigation started"
created: 2026-07-16
author: agent session f7580e17 (Fable 5)
category: agent-process / tooling / observability
related:
  - 743 (workflow-reconsideration program — its Phase 1 built/repaired the current stack and is this tempdoc's evidence base; the ccusage engine-swap proposal MOVED here from 743, see §Migrated proposal)
  - 727 (friction mining — an alive consumer of the stack; must not be destabilized)
  - 622 (native-OTel migration verdict — capture-layer authority: native OTel is the authoritative source)
  - 285 / 277 (Gen-1 analytics program and its death — the cautionary history)
---

# 745 — OSS-first agent observability

## Charter

Decide, once and as a standing policy, which slices of the maintainer agent-observability
stack (`scripts/agent-analytics/` + the OTel sink/viewer) should be **adopted from maintained
open-source tooling, kept as ours, or retired** — and execute the migration for the adopt
slices. Founder direction (2026-07-16): purpose deliberately broad — this is the stack-wide
survey, not just the ccusage engine swap.

**Motivating evidence (743 Phase 1, 2026-07-16):** hand-rolled transcript parsing carried a
2.34× usage over-count bug class that mature OSS (ccusage) had already solved; our
hand-maintained pricing table must chase monthly market changes; ccusage cross-validated the
fixed instrument within 4.2%. Meanwhile two generations of home-grown analytics layers died
unmaintained (285/622: "the raw stream is alive; every layer built on top is dead").

**Working policy to validate or refute:** *prefer maintained OSS for every slice where we
have no unique requirement; keep only what is genuinely ours* (currently believed unique:
the session→merge join, the developer-session scope filter, orchestrator/worker split as a
headline metric, the teardown workflow-moment wiring).

## Hard constraints (inherited, non-negotiable)

1. **Local-only.** Telemetry never leaves the machine (matches the stack's published
   transparency posture in `scripts/agent-analytics/README.md`). Any OSS tool must run fully
   offline or be pinnable offline; no SaaS backends.
2. **Capture stays native.** Claude Code's native OTel emission is the authoritative capture
   layer (622 §6.3); we do not adopt tools that re-instrument capture.
3. **The survival law (743 finding 2).** Anything adopted must name the existing workflow
   moment that runs it (teardown, session hooks, publish) — a tool that must be remembered
   joins the 285/622 graveyard regardless of its quality.
4. **Verified capabilities, not README claims.** Each candidate's load-bearing capability
   must be probed live before an adopt decision (the 743 takeover found README-level claims
   are routinely stale in this space).

## Scope: the slices to survey

| Slice | Current owner | Known OSS candidates (from 743's research pass — unverified until probed) |
|---|---|---|
| Cost/token parsing + pricing | `lib/transcript-cost.mjs` (ours, post-fix) | **ccusage** (mature, cross-file dedup, offline pricing); claude-code-usage-analyzer |
| Per-session dashboards | `generate-dashboard.mjs` (Gen-1, dormant) | token-dashboard (per-prompt ranking, heatmaps) |
| Behavioral/process taxonomy | `score-session.mjs` PHI (Gen-1, invalidated: r=0.064) | claude-session-analyzer (Read:Edit discipline, regression markers) |
| Compaction/context events | `context-attribution.mjs` (chars/4 approximation) | context-analyzer (hooks→SQLite, compaction events first-class) |
| OTel reservoir + viewer | `otlp-sink.py` + `otlp-viewer/` (ours; sink just fixed in 743) | any offline OTLP file-exporter/viewer stack — candidates TBD (research gap: 743's pass did not survey viewers) |
| Merge-join economics | `baseline-economics.mjs` + `record-merge.mjs` (ours, new) | none found (743 research: genuinely novel) — KEEP candidate |
| Friction mining | `mine-friction.mjs` + timeline/aggregate (727, alive) | none comparable found — KEEP candidate; do not destabilize |

## Work plan

1. **Inventory** — classify every artifact in `scripts/agent-analytics/` (+ otlp-viewer):
   alive/dead/uniquely-ours, with its consumer and workflow moment (743's takeover already
   did ~70% of this; verify and complete).
2. **Probe candidates** — one bounded verification per candidate per load-bearing claim
   (subagent-friendly; each probe is self-contained). Includes the viewer-layer research gap.
3. **Adopt/keep/retire matrix** — per slice: decision, evidence pointer, migration cost,
   what it displaces (displaced code gets deleted in the SAME slice's migration, not a later
   sweep).
4. **Execute migrations** for adopt slices; delete retired code; wire survival moments.
5. **Record the standing policy** outcome (validated/refuted/amended) in this tempdoc and
   propose the one-line pointer for the appropriate canonical doc if it proves durable.

## Migrated proposal (from 743, single-home rule)

**ccusage-as-engine swap** (pre-registered in 743 on 2026-07-16, now owned here): replace
`transcript-cost.mjs` parsing with ccusage's per-session JSON output, keeping our merge join /
scope filter / orchestrator-worker split / teardown wiring on top — IF probes confirm
per-session + subagent granularity and pinnable offline pricing. **Standing falsifier
(fires regardless of this tempdoc's schedule): if a second parsing-class bug is found in
`transcript-cost.mjs`, adopt ccusage's engine without further debate.** Interim state that
holds until this tempdoc decides: ccusage runs as the standing independent cross-check of
baseline numbers (one offline command) whenever they feed a decision.

Open data-quality item inherited with the proposal: the 4.2% ccusage-vs-ours delta —
plausibly resumed sessions re-carrying history lines under new session ids (ccusage dedups
message ids across files; we dedup within-file). Root-cause during the probe step; direction
of bias: ours slightly high.

## Non-goals

- Building an observability product; this stack remains maintainer-only, local-only tooling.
- Re-instrumenting capture (native OTel owns it — 622).
- Re-validating PHI or reviving Gen-1 analytics as-is (285 closed that; the question here is
  whether an OSS taxonomy replaces the *slot*, not resurrecting the old scores).
- 743's program work (objective function, workflow pilots) — that stays in 743; this tempdoc
  only owns the tooling-stack decisions.
