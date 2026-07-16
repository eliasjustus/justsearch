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

## Takeover investigation (2026-07-16, session 805279a4, worktree 745-oss-observability)

All claims below are live-measured in this worktree / the main checkout on 2026-07-16, not
read from READMEs (constraint 4). Where a claim is unverified it says so.

### F-1. The inventory (work-plan step 1) — the stack is dead by *invocation*, not by quality

Invoker map, scoped to this checkout (`grep` over `.claude/settings*.json`, `.claude/skills`,
`.github`, `scripts/{dev,ci}`, `scripts/agent-analytics/hooks`, excluding `*.test.*`):

| Artifact | Invokers | Status |
|---|---|---|
| `record-merge.mjs` | `scripts/dev/remove-worktree.cjs:215` (teardown) | **ALIVE** |
| `otlp-sink.py` | `settings.local.json` + `otlp-sink-ensure.mjs` (SessionStart) | **ALIVE** |
| `note-observation.mjs` | `observation-shard-hint.mjs`, `subagent-guide.mjs` | **ALIVE** |
| `observations-triage.mjs` | `known-state-hint.mjs` | **ALIVE** |
| `baseline-economics.mjs` (lib path) | via `record-merge.mjs:31` → `computeSessionCost` | **ALIVE (lib only)** |
| `lib/transcript-cost.mjs` | via `baseline-economics.mjs` ← `record-merge.mjs` | **ALIVE** |
| `score-session · cost-session · outcome-session · analyze-session · analyze-trends · correlate-signals · generate-dashboard · generate-index · context-attribution · evaluate-session` | **0 each** | **DEAD** |
| `mine-friction · aggregate-friction · friction-timeline` | 0 automated; owner-driven | **ALIVE via consumer** (114 mined sessions, latest 2026-07-16 10:41) |

`analyze-session.mjs`'s two apparent hits are *mentions*, not calls (`maintain-doc-hint.mjs:33`
is a comment; `subagent-guide.mjs:67` cites it as a large-file example). Verified.

Live liveness data (main checkout): `session-merges.ndjson` 218 rows (current) · `events.ndjson`
10,837 (current) · `costs.ndjson` **2** rows (Jul 5 + the 743 survival-wiring's first firing,
Jul 16 12:59) · `scores.ndjson` 26 rows, all one batch **Jul 12** · `outcomes.ndjson` **absent,
never produced**.

**Correction to this tempdoc's own motivating evidence.** The charter says two generations
"died **unmaintained**". They did not: tempdoc 424 probed them and found *"The analytics scripts
are sound but the pipeline is inert"* — sound, never invoked. The distinction is load-bearing
and inverts the charter's inference:

- The graveyard is a **liveness** failure (no consumer), not an **implementation-quality**
  failure. OSS adoption changes the implementation and **never** the consumer.
- 622 already ran the natural experiment: it replaced the forked capture layer with a projected
  native-OTel one. If implementation quality were the cause, the derived layers would now live.
  They don't — `outcomes.ndjson` is still absent *on the new stack*. The one Layer-B component
  that lives (`record-merge`, 218 rows) is the one 622 wired to a workflow moment.
- Therefore **adopting OSS into a slot with no consumer produces a dead slot with a dependency**
  — Gen-4 with extra steps. This tempdoc's non-goal *"whether an OSS taxonomy replaces the
  slot"* invites exactly that error, and is withdrawn as a framing (see V-2).

Refinement to 743's design law (finding 2): survival requires a **live consumer question**;
automated invocation is one way to guarantee re-invocation, but `mine-friction` proves an
*owner* works too. What kills a layer is having neither — which is what happened to Gen-1 the
moment the investigation that created it ended.

### F-2. The OTel reservoir is still not a reservoir — 622's core defect is UNFIXED (new)

Not known to 743 or to this charter. `otlp-sink.py:125-137`: `ROTATE_BYTES = 20MB`, one `.prev`
generation, `os.remove(prev)` then `os.replace` — rotation **destroys** data, per stream,
independently. Measured live under load:

| Stream | Growth | 20 MB rotation every | Total retention (current+prev) |
|---|---|---|---|
| `logs.ndjson` | 0.98–6.9 MB/min (load-dependent) | ~3–21 min | **~6–42 min** |
| `traces.ndjson` | ~96 KB/min | ~219 min | ~7 h |
| `metrics.ndjson` | ~27 KB/min | ~769 min | **~25 h** |

Directly observed: a rotation fired at 13:36:58 **during** the measurement, discarding 21 MB
of capture (`logs.ndjson` shrank 16.8 MB in 45 s; `logs.prev.ndjson` mtime advanced 13:24→13:36).

Consequences:

1. **743's handoff claim is false.** 743:433-435 tells the next agent *"OTel reservoir is
   feeding now — from 2026-07-16 onward, native OTel data accumulates … a richer source than
   transcript parsing for future windows."* It feeds and then eats itself. 743 fixed the
   *plumbing* (worktree-relative `--out`; chunked-encoding parse) so data reaches the right
   file — the file then rotates away within the hour. 622's *"firehose with no reservoir"*
   verdict stands unfixed.
2. **Nothing in the OTel path can serve D-1's month-scale window.** This is why the working
   instrument (`baseline-economics.mjs`) parses `~/.claude/projects/*` transcripts — those
   persist. Constraint 2 ("capture stays native OTel, authoritative") remains right about
   *capture fidelity*, but the authoritative source cannot currently back any month-scale
   aggregation. That tension should be stated explicitly rather than left implicit.
3. **The fix is cheap and is not an OSS question.** Volume is dominated by full-content logs:
   ~72% of `logs.ndjson` bytes are `api_request` raw API bodies (`OTEL_LOG_RAW_API_BODIES=1`).
   The stream D-1 actually needs is `metrics` — **~1.1 GB/month at measured rate, affordable to
   retain outright**, vs ~40 GB/month for logs. A per-stream retention policy (date-partitioned
   metrics/traces, keep logs rolling) fixes the defect without adopting anything.
4. Per CLAUDE.md `structural-defects-no-repeat`: one documented silent-loss instance proves the
   class. This is the *second* silent-total-loss defect in this sink in one month (743 found
   100% loss 2026-06-25→07-16 via chunked encoding; this is loss-by-rotation). The sink's
   failure mode is consistently **silent** — which is the actual recurring defect.

### F-3. The OSS-first premise is refuted for the Claude-Code analytics slices (probed)

Health probe of the charter's named candidates (primary sources: GitHub commit/contributor APIs,
not READMEs). Today = 2026-07-16.

| Candidate | Repo | Last commit | Health | License | Verdict |
|---|---|---|---|---|---|
| token-dashboard | `nateherkai/token-dashboard` | **2026-04-20** (~3 mo) | **abandoned** — 639★ but **1 contributor, 15 commits, 0 releases, 22 open issues, no maintainer response since April** | MIT | do not adopt |
| token-dashboard (collision) | `Arylmera/Token-Dashboard` | 2026-07-12 | marginal — 10★, near-verbatim port of the above | MIT | unproven |
| claude-session-analyzer | `lucemia/claude-session-analyzer` | **2026-04-11** | **abandoned** — 3 commits, 13★, 0 releases | **NONE** | **legally unadoptable** |
| context-analyzer | `manavgup/context-analyzer` | 2026-06-13 | marginal — 4★, single author, 5 feature commits within 12 min then silence | **NONE** | **legally unadoptable** |
| **otel-tui** | `ymtdzzz/otel-tui` | **2026-07-13** | **maintained** — 1,048★, 10 contributors, sustained releases since 2024, prebuilt Windows binary, single Go binary | Apache-2.0 | **genuine candidate** |
| otel-desktop-viewer | `CtrlSpice/otel-desktop-viewer` | 2026-07-10 | maintained, but a *receiver* not a file-reader — no `--from-json-file` equivalent | Apache-2.0 | worse fit |

Two candidates carry **no license at all** — that disqualifies adoption independent of health.
Two are ~3 months dead. **639 stars on a repo dead since April is precisely the trap an
OSS-first policy walks into**: stars measure popularity, not maintenance. This niche is ~4
months old and shows hype-cycle signature (many near-identical agent-generated dashboards, high
stars, ~zero contributors, no releases, abandonment within weeks) rather than an ecosystem.

The genuinely valuable asset in this group is **methodology, not code**: `claude-session-analyzer`
merely replicates `anthropics/claude-code#42796`'s thresholds (benchmarked on ~234,760 tool
calls). Read the issue; don't take the dependency.

**The one real find is in the slice nobody had surveyed** (the charter's admitted research gap):
`otel-tui` is maintained by any honest standard and its `--from-json-file` reads OTLP from disk
with no live collector. **UNVERIFIED and load-bearing:** its README does not specify the
`--from-json-file` schema, and our sink writes its own NDJSON shape. That compatibility is the
single fact any adoption case rests on — a ~10-minute empirical test, **not yet run** (it is
downstream of F-2: a viewer for a 6-minute window is polishing a leak).

### F-4. Scope collision: most retire/keep decisions are BLOCKED on 743's pending go/no-go

743's Phase 2 explicitly names `context-attribution.mjs` as substrate for the overhead taxonomy
(743:226, 743:429), and 743 Phase-1 is what re-animated the friction miner. 743's go/no-go on
phases 2-6 is **pending founder decision** (743:4, 743:423).

So for the DEAD slices the honest answer is *conditional*, and 745 cannot settle it now:

- **743 GO** → `context-attribution` (+ possibly the trend/correlate machinery) gains a consumer
  → KEEP, and the OSS question for them is still "no" (F-3).
- **743 NO-GO** → they have no consumer in any live plan → RETIRE (plain deletion; no OSS
  question arises).

Deciding now means either deleting 743's Phase-2 substrate or preserving dead code on
speculation. 743:436-437 anticipated this ("coordinate only if Phase-2 proposals touch the
analytics stack") — they do.

## Non-goals

- Building an observability product; this stack remains maintainer-only, local-only tooling.
- Re-instrumenting capture (native OTel owns it — 622).
- Re-validating PHI or reviving Gen-1 analytics as-is (285 closed that; the question here is
  whether an OSS taxonomy replaces the *slot*, not resurrecting the old scores).
- 743's program work (objective function, workflow pilots) — that stays in 743; this tempdoc
  only owns the tooling-stack decisions.
