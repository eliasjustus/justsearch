---
title: "745 — OSS-first agent observability: adopt/keep/retire survey across the analytics stack"
type: tempdocs
status: "investigated 2026-07-16 (session 805279a4) — survey COMPLETE incl. a discovery sweep + LLM-obs ecosystem survey (founder-raised gap; V-1 partly REFUTED against itself: slices 1-2 ARE well-served OSS, slices 3-4 genuinely empty). Central call CONFIRMED after adversarial re-probe: no tool (ccusage/tokscale/usage-monitor/agents-observe) passes the swap preconditions — the subagent-role blind spot is STRUCTURAL across the ecosystem. KEEP our engine + ccusage as differential oracle; fix 3 (possibly 4) verified parser bugs; fix the sink (F-2 retention, the highest-value item, never in the charter). AWAITING FOUNDER: (1) F-8 — the standing falsifier fired but its remedy does not exist in any tool; (2) 743 go/no-go gates the retire sweep."
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

### F-3b. CORRECTION to F-3/V-1 — the discovery sweep refutes my own blanket claim

Founder challenge (2026-07-16): *"did you already do a research for further OSS projects?"* — No.
F-3 verified the **four candidates the charter already named**; it was not a discovery sweep. The
sweep, run refute-first against V-1, **refuted it for two of four slices**. Recording this against
myself:

| Project | Health (primary-source) | License | Offline | Slice |
|---|---|---|---|---|
| `ryoppippi/ccusage` | **maintained** — 1,474 commits, **75 contributors, 100 releases**, 17.2k★ | MIT (GitHub detector says `NOASSERTION`; LICENSE file is verbatim MIT) | yes (`-O`) | 1 |
| `junhoyeo/tokscale` | **maintained** — 1,803 commits, **97 contributors, 85 releases**, 4.5k★, last commit today | MIT | ⚠ advertises a **"Global Leaderboard"** — egress must be verified opt-in or it is disqualified | 1–2 |
| `Maciek-roboblog/Claude-Code-Usage-Monitor` | **maintained** — 6 contributors, 12 releases, 8.5k★ | MIT | yes, "privacy-first" | 1–2 |
| `phuryn/claude-usage` | **maintained** — 21 contributors, 16 releases, 2k★ | MIT | local dashboard | 1–2 |
| `simple10/agents-observe` | **marginal** — 28 releases but 2 contributors, ~6 wks stale, pre-1.0 | MIT | ? | 2 (subagent attribution) |

**Corrected slice coverage:** slice 1 (cost/pricing) **well covered** — this is *not* an abandonware
niche, and my V-1 sentence overrode my own F-3 (which had already named ccusage as maintained).
Slice 2 (session/subagent) **partially covered**. Slices **3 (behavioral taxonomy) and 4
(compaction/context) are genuinely empty** — V-1 holds *there*, and only there.

The star-trap analysis **survives** and is worth keeping: `disler/claude-code-hooks-multi-agent-observability`
1,490★ with **no license** (disqualified); `ColeMurray/claude-code-otel` 467★, created and last
pushed **the same day**, 13 months cold; `mksglu/context-mode` 19k★ but **Elastic License 2.0 —
source-available, not OSI**. High stars + 1 contributor + 0 releases remains the tell.

**Two findings here directly threaten V-3 (my "keep our engine" call) — now being probed:**
1. `Claude-Code-Usage-Monitor` reportedly carries a **provenance-label system**
   (`official`/`local_estimate`/`experimental`/`unknown`) — i.e. *exactly* the fail-closed
   unknown-model guard I concluded we'd have to build ourselves because ccusage lacks it.
2. `agents-observe` claims **subagent attribution** — *exactly* the headline metric ccusage
   loses (F-5). If it (or tokscale) delivers a real role dimension, the engine-swap case reopens
   with a different tool, and F-8's fired falsifier could be honored rather than amended.

**Anthropic's first-party direction (never checked before, and it corroborates F-2):** official
analytics is **cloud-only** (`claude.ai/analytics/claude-code`, Team/Enterprise) → disqualified by
our local-only constraint. A built-in `claude usage` command is an **open enhancement request**
([claude-code#33978](https://github.com/anthropics/claude-code/issues/33978), still open
2026-07-08) → no local first-party tool exists or is imminent. **Anthropic's sanctioned local path
is OpenTelemetry export** — which independently endorses constraint 2 and raises the priority of
F-2 (fixing retention on the OTel path) over any transcript-parser choice.

**Method lesson for this tempdoc:** the charter's candidate list was treated as the search space.
It was a *starting* list, and a 15-minute sweep found 4 maintained projects it omitted. This is
`explore-before-implementing` applied to research: verifying a given list is not surveying a field.

### F-5. The ccusage probe: the swap's own preconditions FAIL 2 of 3

ccusage **20.0.17** pinned, probed live against 9,620 transcripts (125 sessions + 546 subagent
files for this project). The migrated proposal is explicitly conditional — *"IF probes confirm
per-session + subagent granularity and pinnable offline pricing."* Probed:

| Precondition | Verdict | Evidence |
|---|---|---|
| Per-session granularity | **CONFIRMED** | `session --json -O` → 6,954 rows; `period` = the Claude Code session UUID. Caveat: 21 duplicate `period` values across projects — a naive `Map(period→row)` silently drops data. |
| Subagent granularity | **REFUTED** | Subagents are separate nested files (`<session>/subagents/**`), **not** `isSidechain` rows (0 sidechain lines in 125 files). ccusage reads them and folds them into the parent **exactly and invisibly** — token-for-token match with our parent+subs total. **No output mode carries a role dimension.** The orchestrator/worker split — a headline metric — is lost at the ccusage boundary. |
| Pinnable offline pricing | **REFUTED (silent-failure trap)** | `-O` is genuinely zero-network (proxy-verified: 0 hits vs 7 CONNECTs to `raw.githubusercontent.com` **and `models.dev`** by default). But the pinned table **has no `claude-sonnet-5` entry and fails silently to $0**: 11.38 B sonnet-5 tokens priced at **$0**, corpus total $23,956 offline vs $27,305 online — **12.3% understated, exit=0, stderr empty, no warning in JSON or table mode.** Pinning the version pins a table that silently zero-rates every model newer than the pin. |

So the swap is **self-cancelling on its own pre-registered terms**: 2 of 3 preconditions fail.

### F-6. The 4.2% delta root-caused — and it is THREE bugs in OURS, not one

Applying the token-vs-dollar discriminator (89 cleanly-matched sessions, ccusage *online* pricing
to remove the $0 artifact): **tokens +4.51%, dollars −0.93%** → a dedup/scope delta.
**Pricing-table divergence: REFUTED as the cause.** Causal proof by reconstruction, not argument:

```
ccusage tokens                              10,391,804,505
A  ours today (first-snap, per-file dedup)  10,860,843,607   +4.51%   output −30.19%
B  + last-snapshot fix                      10,870,708,781   +4.61%   output  +2.18%
C  + last-snapshot + GLOBAL dedup           10,391,981,122    0.00%   output   0.00%
```

Variant C matches ccusage to **0.00% on every field** (residual 0.0017%). That closes it. The
tempdoc's stated hypothesis was right — but for **incomplete** reasons: the +4.5% is *two
opposing errors partially cancelling*, which is exactly why the dollar column looked innocuous.

1. **Cross-file dedup (hypothesis CONFIRMED).** `seenMessageIds` is a per-file `Set`
   (`lib/transcript-cost.mjs:109`); ccusage dedups `(messageId, requestId)` globally. Measured
   985 turns / 489.8 M tokens = **3.42% of tokens duplicated across files** (+3.54% overcount),
   concentrated in ~11 resumed sessions (85 of 96 match within 1%).
2. **First-vs-last snapshot — a NEW bug nobody suspected.** `transcript-cost.mjs:124`
   (`if (seenMessageIds.has(msgId)) continue;`) keeps the **first** usage snapshot, on the
   comment's premise that repeated lines carry *"the identical usage snapshot"*. **That premise
   is false in subagent files** — snapshots are streaming partials that *grow*. Independently
   reproduced by this session on a fresh subagent transcript:
   `msg_011CcyTURQe2GYCGo9SgEiYt -> 5, 5, 5, 5, 5, 291` — 9 of 9 multi-line ids vary. Taking the
   first undercounts that turn **58×**. Corpus-wide: **output tokens −30.19%**;
   1,465 of 1,640 multi-line subagent ids affected.
3. **Cache 1h-tier collapse — REAL, and ccusage does NOT fix it.** The comment
   (`transcript-cost.mjs:11`) *"transcripts don't distinguish tiers"* is **factually wrong**:
   25,404 of 25,404 turns carry a `cache_creation` breakdown, and **100.000% of cache writes are
   `ephemeral_1h`** (283.1 M tokens; `ephemeral_5m` = 0). We price all of it at the 5-minute rate
   (1.25× input) instead of 2.0× → **~$655–$1,091 underpriced on the compared corpus alone**.
   ccusage's binary parses the same breakdown but its pricing struct carries a single
   `cache_creation_input_token_cost` with no 1h rate — **adopting ccusage inherits this bug.**

**This is 743's ship-blocker fix being half-wrong.** The review correctly stopped the 2.34×
double-count, but chose the wrong representative (first, not last/max) on a premise it verified
by *counting lines vs unique ids* — never by checking whether the snapshots were **equal**.
Textbook `interrogate-results`: the expected result (dedup kills the overcount) landed, so
nobody asked why. The 2.34× fix traded an overcount for a −30% output undercount.

### F-7. Consequence for 743's published baseline (cross-tempdoc, needs action)

743's Phase-1 numbers were computed with this parser, so they carry all three defects:

- **Total cost is understated** — the 1h-cache-tier collapse alone underprices ~8–13% of the
  cache-write column, and cache-write is 290 M tokens of the sample.
- **The 85.1% / 14.9% orchestrator/worker split is biased toward the orchestrator** in both
  directions at once: bug 1 inflates *parent* files (resumed sessions), bug 2 deflates *subagent*
  output. Magnitude is likely small in token share (output is ~0.2% of tokens; `cache_read`
  dominates) — but **the direction is exactly the axis 743's live prediction 1 tests**
  ("the 2026-07-15 delegation change should lower orchestrator share below 85.1%"). That
  prediction is currently being measured by an instrument biased the same way it predicts.
  **Recompute the baseline after the parser fix before testing prediction 1.** I have not
  estimated the corrected split — recomputation, not arithmetic, is the honest route.
- The delegation-economics *decision* (2026-07-15) is unaffected in direction: a biased-high
  orchestrator share still supports "orchestrator tokens are scarcest."

### F-8. The governance conflict the founder must resolve (do not let an agent settle this)

Two pre-registered commitments, in the same paragraph of this tempdoc, now point opposite ways:

- **The standing falsifier FIRES, twice over**: *"if a second parsing-class bug is found in
  `transcript-cost.mjs`, adopt ccusage's engine without further debate."* Bugs 1 and 2 are both
  parsing-class and both verified. The trigger is unambiguously met.
- **The proposal's preconditions FAIL**: the swap was conditioned on *"IF probes confirm
  per-session + subagent granularity and pinnable offline pricing"* — and 2 of 3 are refuted
  (F-5). Executing the falsifier literally would adopt an engine that **loses a headline metric**
  and **silently zero-rates new models**, to escape bugs whose exact fix is already known.

Reading: the falsifier was written as a guard against *motivated reasoning* — "don't let us
defend our own buggy parser out of NIH pride." It was **right about what it measured** (our
parser is not trustworthy) and **wrong in its implied premise** (that ccusage's engine is the
fit remedy). Its finding must be honored; its prescription is unavailable.

**Strengthened by F-10 (2026-07-16, after the discovery sweep).** The obvious objection to the
amendment — *"you only checked ccusage; find a better tool and just execute the falsifier"* — has
now been tested and **fails**. Every maintained alternative was probed and **none passes the
preconditions**, for structural reasons (no tool joins cost to merges, so none exposes a role
dimension; live-fetched pricing tables fail open by construction). So the falsifier's prescription
is unavailable **from the entire ecosystem**, not merely from ccusage. That converts the amendment
from "an agent rationalising around a fired falsifier" into "the prescribed remedy does not exist"
— which is exactly the distinction `structural-defects-no-repeat` exists to police, and it is why
this still goes to the founder rather than being settled here.

Per `structural-defects-no-repeat`, an agent must not quietly convert a fired falsifier into a
cost-benefit discussion — so this is escalated, not resolved here. **Recommended amendment (founder's call):**

> Second parsing-class bug → **ccusage becomes a mandatory differential oracle wired to a
> workflow moment**, not the engine. Engine adoption additionally requires the swap's original
> preconditions to pass.

### F-9. The LLM-observability ecosystem surveyed (the F-3b scoping gap, closed)

Our capture is native OTel, so the mature self-hostable LLM-obs ecosystem was in scope and had
never been surveyed. It has been now. **The gap was real; the conclusion lands in the same place
as V-5, but via a much better argument than I had.**

| Tool | Blocking finding |
|---|---|
| **Arize Phoenix** | **Elastic License 2.0 — not OSI OSS**; and **traces-only, no metrics ingest** → disqualified twice |
| **Langfuse** | MIT core but **`/ee` gated — "Data Retention Policies" is Enterprise-only**, i.e. the exact feature we need is the paid one; traces-only; Postgres+ClickHouse+Redis+S3 |
| **OpenLLMetry / Helicone** | Architecturally irrelevant — emission-side SDK / API proxy; we already have native emission |
| **OpenObserve** | Best architecture found, but **no OSS Windows build** (EE-only `.exe`) + mandatory telemetry |
| **SigNoz / HyperDX / Uptrace / Laminar** | 2–5 containers (ClickHouse + friends) for one developer's tooling |
| **Jaeger v2 / Grafana+Mimir** | Jaeger **structurally cannot store metrics**; Mimir has **no Windows binary** |
| **Braintrust** | Proprietary |
| **Prometheus 3.x** | **The only survivor** — single native `.exe`, Apache-2.0, `--web.enable-otlp-receiver`, `--storage.tsdb.retention.time=1y` |

**The decisive argument is architectural, not cost.** Our question is *"what did this merge cost,"*
keyed `session.id → merge_commit`: a **high-cardinality OLAP join**. Prometheus is optimized for
the opposite (low-cardinality aggregation over time) — `session.id` as a label makes every session
a new series with a resetting counter, and `increase()` over 4 weeks across churning series is
precisely its weak spot. **The incumbent NDJSON + `record-merge.mjs` join is better matched to the
question than a TSDB is.** The ecosystem wants spans; the TSDBs want low-cardinality metrics; we
need durable per-session records for a join. Nobody is shaped to solve our problem.

And the data isn't lost because NDJSON can't hold 1.1 GB/month — **it's lost because
`otlp-sink.py:136` calls `os.remove(prev)`.**

**The survey's real yield is three emitter config flags, not a tool — zero adoption cost:**

1. **`OTEL_LOG_RAW_API_BODIES=file:<dir>`** — writes bodies out-of-band as `<uuid>.request.json`
   instead of inline. That is **72% of log bytes out of the stream** (F-2), collapsing the
   6–42 min retention crisis with a config change. ⚠ **Not yet live-probed** (it edits the
   founder's global `~/.claude/settings.json`) — probe before relying on it, per constraint 4.
2. **`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=cumulative`** — default is `delta`
   (verified). Mandatory before *any* TSDB: VictoriaMetrics **silently drops** delta samples;
   Prometheus's converter sits behind an experimental flag. A silent-loss trap of exactly the
   class this sink already has twice.
3. **Per-signal endpoints** (`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`) — metrics could go to
   Prometheus while logs/traces keep hitting our sink, with **no Collector in between**.

**V-6 RESOLVED — otel-tui is incompatible with our sink.** The flagged unverified fact is now
settled from source: `otel-tui --from-json-file` delegates entirely to upstream
`otlpjsonfilereceiver` and **will not read our flattened schema**. Adopting it therefore requires
first adopting the OTel Collector `fileexporter` (`max_backups: 0` = retain forever, `format: json`)
— an **alpha** component whose README disclaims field-name stability. Taking on alpha-schema churn
to fix a rotation bug we can fix in five lines is a bad trade. *(One correction the sweep made
against another agent's claim: format migration would **not** "touch every downstream script" —
the scripts that would break are the ones F-1 measured at **0 invokers, DEAD**. Migration cost is
near-zero; it just isn't worth paying for.)*

**Ranked outcome for slice 5:** (1) **fix the sink + set the flags** — hours, no dependencies,
**mandatory regardless of every other option** because nothing else stops a data-destroying bug we
own; (2) *optionally* add Prometheus for metrics **trend visualization** — additive, metrics-only,
does not displace the sink and does not answer the per-merge question better than the existing
join; (3) Collector+otel-tui — only if a maintained viewer is independently wanted.

This independently re-derives V-2: adopting Prometheus into a slot with no live consumer would be
Gen-4 with extra steps.

### F-10. The alternatives probed against the failed preconditions — NONE passes (V-3 survives)

F-3b surfaced two candidates that threatened V-3. Both were probed live against real transcripts.
**No tool passes (a)+(b)+(c). The engine-swap case does not reopen — and the reason is more
interesting than "our tool is fine."**

| Tool | (a) per-session | (b) subagent role | (c) offline + fail-closed | Verdict |
|---|---|---|---|---|
| **tokscale** v4.5.3 | **PASS** (`--json --group-by session,model`; within 0.16–1.4% of ground truth) | **FAIL** | **FAIL** | strongest candidate, still fails |
| **Claude-Code-Usage-Monitor** 4.0.0 | **FAIL** | **FAIL** | offline PASS / fail-closed **FAIL** | premise was wrong |
| **agents-observe** 0.9.11 | N/A | N/A | N/A | **category error** |

- **tokscale (b)**: it *does* parse `isSidechain`/`agentId` and has an "Agents" TUI tab — but
  `AgentUsage` **does not derive `Serialize`**. Role-aware internally, **invisible in JSON**; it
  reassigns sidechain lines to the parent `sessionId` and folds them in. Same loss as ccusage.
- **tokscale (c)**: forced synthetic-model test → `claude-totally-unknown-v99` = **$0.00, exit 0,
  no warning** (control `claude-opus-4-8` = $30.00, correct). `calculate_cost_with_provider`
  returns `0.0` on lookup miss — the cost path **fails open**. (Its `pricing` subcommand *does*
  say "Model not found" — a *different* code path; probing that alone would have produced a false
  PASS.) **No pinned table at all**: live-fetched from LiteLLM/models.dev/openrouter, 1h TTL, no
  `include_str!` fallback — strictly *more* network-coupled than ccusage.
- **tokscale telemetry: NOT disqualified.** Measured egress = 3 pricing hosts only; **zero**
  `tokscale.ai` contact ever; warm cache = **zero egress**. The leaderboard is double-gated
  (explicit `submit` **and** prior `login`). The README-based worry in F-3b does not survive
  measurement — recorded so it isn't repeated.
- **Usage-Monitor: my brief's premise was factually wrong.** The `official`/`local_estimate`/
  `experimental` labels are **`PlanConfig.confidence` — subscription *plan limits*, not model
  pricing**. It is a **quota monitor**: its "session" is a **5-hour billing block**, not a Claude
  Code session (`sessionId` is parsed at `analyzer.py:373` but no view keys off it) → not joinable
  to merges. **Zero** subagent awareness (`rglob("*.jsonl")` swallows subagent files unaware). Its
  `strict=True` path is **unreachable for Claude models**: `normalize_model_name('claude-sonnet-5')`
  → `'claude-3-sonnet'`, which exists in the dict (so would `claude-sonnet-9-future`).
- **agents-observe**: hook-driven **lifecycle** attribution (`SubagentStart`/`SubagentStop`
  timeline), **not tokens or cost**; doesn't parse transcripts; `"private": true` (unpublishable).

**The generalization — this is the real finding.** The subagent-role blind spot is **structural
across the ecosystem, not a ccusage quirk**: every tool reassigns sidechain lines to the parent and
reports session totals, and tokscale's source shows this is a *deliberate design choice* ("fix
inflated session counts"), not an oversight. **None of them are built to join cost to git merges**,
so none exposes the dimension that join needs. Likewise the silent-$0 is what **live-fetching a
third-party pricing table** buys you — adopting tokscale would trade our 3 known bugs for an
*unpinnable* pricing dependency **plus** the lost headline metric, and we would still write the
role-attribution layer ourselves.

**The data already supports what we need**: `isSidechain`, `agentId`, and the `subagents/` path are
all present in the transcripts. Only our own layer will ever read them.

**Two items this probe hands to V-4 (fix-the-parser), both new:**

1. **Ground truth for session `4bd6a45f`: 38.4% orchestrator / 61.6% worker** (deduped by
   `message.id`+`requestId`; a naive tally read 1.04 B tokens vs 399 M deduped — a **2.6×
   over-count** the prober caught only by chasing a gap against tokscale's 402 M). This is *one
   delegation-heavy session* (122 subagent files) and is **not** in contradiction with 743's
   corpus-wide 85.1/14.9 — but it shows how violently the split moves with the dedup key, and
   reinforces F-7's recompute-before-testing-prediction-1 requirement.
2. **⚠ A possible 4th defect — the sonnet-5 intro-rate cliff.** Reported (and **NOT yet
   independently verified — verify before acting**): sonnet-5 is on a **$2/$10 intro rate through
   2026-08-31**, reverting to **$3/$15 on 2026-09-01**. Our table (`transcript-cost.mjs:23`) has
   `$3/$15` — the *sticker* rate — so if true we are **overstating sonnet-5 by 50% today** and
   will be correct only after the cliff. Note the irony for the charter: this is exactly the
   "pricing chases the market" burden it cites — **and neither OSS tool handles it either**
   (ccusage silently $0s sonnet-5 offline; Usage-Monitor returns the sticker rate). A dated cliff
   is worth *encoding* (date-conditional pricing), which no tool surveyed does.

## Verdict

**As chartered — a stack-wide OSS-first survey producing a standing policy: NO. But the survey
itself is now COMPLETE, and its answer is "adopt almost nothing."** Work-plan steps 1–3 are done
above (~1 session). Steps 4–5 largely evaporate because there is almost nothing to adopt.
This is not "don't do 745" — it is "745 asked a good question and the answer came back negative."

**V-1 — The OSS-first policy: REFUTE, do not record it. ⚠ PROVISIONAL — see the discovery gap
below.** In this niche "maintained OSS" mostly does not exist (F-3): 2 of 3 named candidates
abandoned ~3 months, 2 carry **no license**, all single-author with zero releases. The policy's
hidden premise is that maintained OSS *exists* per slice; here it usually doesn't, and stars
actively mislead (639★, dead since April). A standing CLAUDE.md line would cost always-loaded
budget, rest on N=1 evidence, and mostly fire "no." `explore-before-implementing` already covers
the real lesson.

> **⚠ Known weakness in V-1 (owned, 2026-07-16, founder-raised).** This verdict rests on the
> **four candidates the charter already named**, plus ~6 sighted by name in passing — it is *not*
> a discovery sweep. "The niche is abandonware" is therefore stated more strongly than an N=4
> sample supports. Two sweeps are open to close this:
> 1. **Discovery** of Claude-Code analytics OSS nobody has listed yet (incl. whether Anthropic
>    ships/endorses first-party tooling), run refute-first against V-1.
> 2. **The scoping error**: F-3 defined "the niche" as *Claude-Code-transcript parsers*. But our
>    capture is **native OTel**, so the mature self-hostable LLM-observability ecosystem
>    (Phoenix / Langfuse / SigNoz / OpenLLMetry / Collector+Tempo) is arguably in scope and was
>    **never surveyed**. If any ingests OTLP locally with durable retention, it bears directly on
>    **F-2 (the retention defect) and V-5/V-6 together** — the strongest possible adopt case in
>    this tempdoc, and the one place the OSS-first premise might actually hold.
>
> Do not treat V-1 as settled until both land. V-2 (the liveness argument) is independent of this
> gap and stands regardless: no discovery result can make OSS supply a missing *consumer*.

**V-2 — Withdraw the "OSS replaces the *slot*" framing** (current non-goal, line 96-97). The
graveyard is a liveness failure (F-1); OSS changes the implementation and never the consumer.
Adopting into a consumer-less slot yields a dead slot **plus** a dependency — Gen-4 with extra
steps. This framing is the single most dangerous idea in the charter.

**V-3 — KEEP OUR ENGINE; ADOPT ccusage AS A DIFFERENTIAL ORACLE. CONFIRMED after the discovery
sweep tried to overturn it (F-10).** Every candidate fails the preconditions, and the failures are
**structural, not incidental**: (b) the whole ecosystem deliberately folds subagents into the
parent because none of it joins cost to git merges; (c) silent-$0 is what live-fetching a
third-party pricing table buys — tokscale is *more* network-coupled than ccusage (3 hosts, no
pinned table, no `--offline`). Adoption would cost the headline metric **and** an unpinnable
pricing dep, and we would still write role attribution ourselves. Meanwhile ccusage as a
*cross-check* found **three** real bugs our tests, our reviewer, and our orchestrator all missed —
that is its demonstrated value, and it is already 743's interim practice; formalize it. Gate:
`-O` must never be trusted without a fail-closed unknown-model check we own.

**V-4 — Fix the parser now; this is the real work and it is not an OSS question.** Three verified
bugs, and the fix is *exactly known* — variant C (global `(messageId, requestId)` dedup +
last/max snapshot) reproduces ccusage to 0.00%. Add the 1h-cache tier (2.0×), which ccusage
would **not** have fixed. Then recompute 743's baseline (F-7).

**V-5 — F-2 (reservoir retention) is the highest-value item in this tempdoc and was not in its
charter.** The reservoir retains ~6–42 min; it is destroying capture every few minutes right now,
and 743's handoff tells the next agent the opposite. Fix per-stream retention (metrics ≈1.1 GB/
month is affordable). Not an OSS question.

**V-6 — otel-tui is the one genuine adopt candidate**, in the one slice nobody had surveyed —
but it is **downstream of V-5** (a viewer for a 6-minute window is polishing a leak) and rests on
one unverified fact: `--from-json-file` schema vs our sink's NDJSON. ~10-minute test, not yet run.

**V-7 — All retire/keep decisions for the dead slices: BLOCKED on 743's go/no-go** (F-4). Then
it is a deletion PR, not a survey.

### Cheapest decisive evidence — and it now exists

For the one slice with a live consumer, the decisive evidence was the 4.2%-delta root-cause, and
**it has been produced today** (F-6): reconstruction to a 0.00% match. It did not merely resolve
the delta — it **fired the standing falsifier** and simultaneously **refuted the swap's
preconditions**, which is why F-8 goes to the founder. For everything else, the decisive evidence
is 743's go/no-go, which is already scheduled and costs nothing.

### What this displaces or duplicates

- **Duplicates 743's governance**: 743 already pre-registered the swap, its falsifier, and the
  interim cross-check. 745's marginal contribution is the *probe*, not a program.
- **Risks destabilizing 743**: retiring `context-attribution` would delete Phase-2's named
  substrate (F-4). The two pre-marked KEEP candidates are confirmed load-bearing and untouched.
- **The standing policy would duplicate** `explore-before-implementing` at always-loaded cost.

### Recommended reshape

745 shrinks from a program to three items: **(a)** fix the parser + recompute 743's baseline
(V-4/F-7); **(b)** fix reservoir retention (V-5); **(c)** hold the retire sweep for 743's go/no-go
(V-7). Plus one founder decision (F-8) and one deferred 10-minute test (V-6). Items (a) and (b)
are both *correctness* work on live layers — neither is an adoption.

## Non-goals

- Building an observability product; this stack remains maintainer-only, local-only tooling.
- Re-instrumenting capture (native OTel owns it — 622).
- Re-validating PHI or reviving Gen-1 analytics as-is (285 closed that; the question here is
  whether an OSS taxonomy replaces the *slot*, not resurrecting the old scores).
- 743's program work (objective function, workflow pilots) — that stays in 743; this tempdoc
  only owns the tooling-stack decisions.
