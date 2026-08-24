---
status: design + implemented (all four verdicts settled; PHI RETIRED 2026-08-20, §4.5)
created: 2026-08-19
updated: 2026-08-20
author: agent session (Opus 5, 1M context)
charter: the analytics events lane has four products, not one — decide each on its own merits
---

# 858 — Analytics lane: outcomes are a view, the dashboard and PHI retire

Opened from tempdoc 856 §10. The sketch asked one question — is the events lane alive? The
design's first move is that this was the wrong unit. The lane holds **four products with
different value and different failure modes**, and lumping them produced a question with no
good answer.

## 1. Method, and what it can support

Conforms to tempdoc 844's audit-and-prune framework rather than minting a parallel one: per-item
verdicts drawn from `KEEP` / `FIX-THEN-SURFACE` / `RETIRE`, the middle option of *retiring the
machinery while keeping the finding* (844 §4.3), and explicit **owner decisions** left pending
rather than an agent deciding a judgement call for the person who uses the output.

What this can support: consumer analysis (mechanical), source-of-truth analysis (source reading),
and one instrument's own documented self-assessment. What it cannot support: whether a
human-facing report is read. That is asked, not inferred — §5.

## 2. The four products

| Product | Instruments | Store | Verdict |
|---|---|---|---|
| The outcome JOIN | `outcome-session` | `outcomes.ndjson` | **KEEP — convert store to view** (§3) |
| Process-hygiene scoring | `score-session`, `correlate-signals` | `scores.ndjson` | **RETIRE — cannot be evaluated** (§4.5) |
| The LLM judge | `evaluate-session` | `judge-outcomes.ndjson` | **KEEP — owner, §5** |
| The dashboard | `generate-dashboard` | `dashboard.html` | **RETIRE — owner, §5** |

Consumer analysis, mechanical — **and an earlier draft of this paragraph was wrong.** It claimed
nothing outside `scripts/agent-analytics/` reads these stores. In fact
`scripts/ci/check-agent-quality-trend.mjs` and `scripts/ci/agent-quality-baselines.v1.json` read
`scores.ndjson` (622 Layer C), and `generate-index.mjs` fills `session-index.json`'s `score` field
from it. None of the three is wired to CI, but all three widened the PHI blast radius beyond what
§4 first listed — and all three retire with it (§4.5, §7). The dashboard was never a mechanical
call at all: its consumer is a person, which is why it went to the owner.

## 3. KEEP the outcome JOIN, and make it a view

`outcome-session` is not the broken part. Its design is right: a per-session record **joined from
canonical owners** — git for the merge link, the build counter, tempdoc frontmatter, governance
SARIF — with the LLM judge demoted to a residual `inference` block that can never overwrite a
fact. That is 622 Layer B working as specified, and 856 has just repaired its weakest input.

What is wrong is only its **persistence**. A JOIN over canonical sources is a pure function of
those sources; `outcomes.ndjson` is a cache of it with a refresh obligation nothing meets. Per
856 §10.2, the artifact should not exist as maintained state.

**Design: outcomes are computed on demand and printed. Writing a file becomes opt-in, and any
written file is a report, not an authority.** Consumers recompute rather than read. The
recomputation is file reads and a join — cheap enough that caching buys nothing but staleness.

### 3.1 The exception this design has to admit

One of the joined facts is not recomputable. Governance SARIF is a **single shared file
overwritten by every gate run**, and `outcome-session` decides whether it belongs to a session by
comparing its mtime against the session's window. Recomputed a week later, that mtime says
nothing — the observation was destroyed by the next gate run.

So the honest rule is narrower than "always recompute":

> **Recompute what survives; capture only what time destroys — and record which you did.**

SARIF is a legitimate capture-at-a-moment. It should be *marked* as captured, with its
observation time, rather than silently re-derived into a false answer later. The same applies to
the LLM judge in §5: a paid derivation is legitimately cached, because recomputing it costs money
rather than milliseconds.

This is a real boundary on 856 §10.2's principle, found by designing against it, and it is the
more useful form of the rule.

**Implementation found a second captured field this section had not predicted, which is the better
evidence that the boundary is real rather than a special case built for SARIF.**
`facts.build_last_status` reads `build-fails-<session>.json` — and `hooks/dispatch.mjs` deletes
that file at SessionEnd while `hooks/intervene.mjs` prunes it after 24h. So a later recompute can
only ever answer `unknown`, and that `unknown` means *"the counter was deleted"*, not *"the build
never failed"*. Same shape as the SARIF, found by auditing rather than by being told.

**And one field forced a distinction the rule did not have.** `facts.tempdocs` reads a tempdoc's
`status` and checkboxes live. Those survive — so the field is derived, not captured — but a
recompute reports the tempdoc's status *now*, not its status while the session ran. That is a
value which **changes over time**, as against one **destroyed by** it. Recompute is right for it,
and a reader could still mistake the answer for a session-time observation, so the rows say so.
The rule's third clause ("record which you did") is what makes that distinction expressible at
all; without it, "derived" would have silently covered both cases.

## 4. Process-hygiene scoring — RETIRED (2026-08-20), on grounds the first attempt did not have

This section has held three verdicts in two days, and the sequence is the point.

1. **RETIRE**, on the composite's own r=0.064 header. The owner **declined** that basis: a dated
   finding is not a reason to delete a live thing.
2. **UNDECIDED**, pending a fresh measurement. Correct at the time.
3. **RETIRE**, on evidence the derisk pass produced while scoping the measurement — which found
   that the measurement cannot be taken, and could not be compared to anything if it were.

The final verdict is **not** the first one re-argued. §4.1 and §4.2 below are preserved as written,
because the case they make for *keeping* PHI was sound on what was known then and is the reason
the retirement had to be earned rather than assumed. §4.4 is the evidence that earned it.

### 4.1 The argument as it stood

`score-session.mjs:8-9`, verbatim:

> PHI measures tool discipline and process patterns — it does **NOT** predict task completion or
> outcome quality (r=0.064 at N=116, see tempdoc 277 C4).

Read alone, that retires the metric: it was built to test whether process hygiene predicts
outcomes, the test ran, the answer was no, and `correlate-signals` exists to keep asking it.

### 4.2 Why that reading does not survive the code

The same tempdoc produced **signal-level** results with real effect sizes, and the code encodes
them. The `RULES` array's own comments record that `bash_fileop_pct` correlates *positively* with
completion on feature sessions (r=+0.51, d=+1.11) and that `THRASHING` fires on 33% of completed
implementation sessions against 0% of partial ones — an inverted signal. Both findings are
implemented as per-type suppressions.

So 277 did not find the metric worthless. It found the **composite** non-predictive while
**components** carry signal, and the response was calibration, not abandonment. Retiring the whole
product would discard the calibrated part along with the composite.

**And the calibration is currently inert**, for three stacked reasons — worth separating, because
fixing one and declaring victory is the obvious mistake:

1. **A wrong field path.** `score-session.mjs` read `outcome?.task_type`, but post-622 that field
   lives at `outcome.inference.task_type`, so `taskType` was always null, neither suppression ever
   fired, and `computeTypeCeilings` collapsed to one pool. Fixed in this tempdoc's implementation —
   a plain defect whichever way the verdict lands.
2. **A store nothing writes.** Once §3 makes outcomes a view, reading the file yields an empty map
   and `taskType` is null again for a new reason. Fixed by wiring the consumer to recompute through
   the exported join rather than read the file — the half of §3 that "consumers recompute" always
   implied and that was initially left undone.
3. **A data gap neither fix closes.** `inference.task_type` originates in the LLM-judge cache, and
   the join cannot manufacture it. In the main checkout that cache is 994 bytes dated 2026-07-12,
   so suppression still will not fire for any session the judge never scored. This is not wiring;
   it depends on the judge (D2, kept) actually having run.

So PHI as it runs today is still the metric *without* the corrections its own measurement produced,
and will remain so until the judge has scored a corpus. Any judgement made now judges a
miscalibrated version — which is a second, independent reason §4.3's verdict stays open.

### 4.3 The blocker: the evidence cannot be gathered today

The owner's chosen basis is a fresh measurement. It is not currently obtainable.

`score-session` scores session *reports*, which are generated from the events store. That store
holds **10 distinct sessions** across `events.ndjson` and its rotated `.prev` — against N=116 for
the original finding. There is no honest correlation to run at that size, and running one anyway
would produce a number with the shape of evidence and none of the weight.

Two routes to evidence, neither belonging to this tempdoc:

- **Retention.** The transcript store rotates on a 30-day default and nothing sets otherwise, so
  the corpus is capped by configuration rather than by history. Changing that starts the clock but
  answers nothing for weeks.
- **Re-base the report generator on transcripts.** `analyze-session` already reads transcripts
  directly; the transcript lane retains roughly 75 main sessions against the events lane's 10.
  Moving the input from the store that rotates to the one that survives is the same move tempdoc
  856 made for the merge key, and it would serve 856's falsifier too — which is why it deserves
  its own tempdoc rather than riding along here.

### 4.4 Why the measurement cannot settle it — the evidence that decided the verdict

A derisk pass scoped the re-base named above and found four things. Together they mean the
question is not "does PHI predict outcomes" but "can PHI be evaluated at all".

**The baseline is already unreachable, independent of any re-base.** Comparing tempdoc 277 §C4
against `extractSignals` today: the signal count went 7 → 10; `unbounded_read_pct`'s numerator
changed from `unbounded_count` to `unbounded_large_count`; the reads population changed from main
to main+subagent; the weights changed; global ceilings were revised and per-type hierarchical
pooling added; anomaly detection went IQR → MAD. And `bash_fileop_pct` — the r=+0.51 signal §4.2
leans on — was redefined to first-pipeline-segment-only with `git`-prefixed commands excluded,
recorded in **no tempdoc at all**. A fresh number would not re-test 277; it would be a first
measurement of a different metric, placed beside an old number as though they matched.

**The two lanes count different populations, so no re-based score is comparable to a stored one.**
Measured on one session: hook `pre_tool_use` = 5,900; the main transcript = 1,117 tool-use blocks;
main plus its 177 subagent transcripts = 22,985. The hook lane is a lossy subset that already folds
subagent calls into the parent's counters — and `extractSignals` then *adds* main and subagent
totals on top. A live report records `data_completeness.capture_rate = 13.187` about itself.

**The re-base is a rewrite, not a re-point.** `score-session` is untouched by it (it reads report
fields), but every derivation in `analyze-session` is written against hook-event shapes. That is
`analyzeSession` plus five helpers — effectively the whole file below `relPath` — and it would also
have to re-base the task-type join, or §4.2's just-fixed defect returns through a different door.

**Even after all that, the correlation still cannot run.** Pairs need a score *and* a judge
outcome. The judge cache holds **2 sessions**; `correlate-signals`' own derived floor is **44
pairs**. Closing that gap is a paid judge pass over the corpus.

### 4.5 Verdict

**RETIRE.** Not because it scored badly — because it cannot be scored, and nothing is waiting on
the answer. The composite measured r=0.064 once; its baseline no longer exists in a comparable
form; its per-type calibration was inert for months; its consumers are gone (the dashboard was
deleted in this same tempdoc, and `check-agent-quality-trend` is wired to no workflow); and
re-establishing any of it costs a rewrite plus a paid measurement whose result would compare to
nothing.

Per 844 §4.3 the machinery goes and the **finding stays** — r=0.064 at N=116, and the signal-level
results — relocated to the reference layer with the caveat that those numbers describe signal
definitions that have since drifted. A future agent proposing a process-hygiene score should find
the result *and* the reason it cannot simply be re-run.

**Predictable evasion, named inline:** "keep it, deleting costs more than leaving it." Leaving it
costs the next agent an orientation pass over a metric with no consumers, no valid baseline, and a
store nothing writes — which is the cost this tempdoc was opened to stop paying.

## 5. Owner decisions

Neither was an agent's call. Both were asked and answered 2026-08-19.

**D1 — the dashboard: RETIRE.** Never opened. Mechanical analysis could not settle this and
inferring abandonment from a file date is the reasoning 856 §3.2 forbids, so it was asked. The
sweep is part of this tempdoc's work, not a follow-up: the generator, the generated artifact,
`test-pipeline.mjs`'s Test 19 (an unguarded `execFileSync` that would abort the suite at that
point and lose tests 20-25), and the prose in the analytics README and
`docs/explanation/21-agent-analytics-pipeline.md`.

Hazard recorded because a name-based sweep would cause real damage: `dashboard.html` names **two**
checked-in artifacts. `modules/ui/src/main/resources/debug/dashboard.html` is the *product's* debug
page, served at `LocalApiServer.java:731`. `scripts/governance/lib/dashboard.mjs` is a third
unrelated thing that generates governance state. The sweep is path-qualified for this reason.

**D2 — the LLM judge: KEEP.** The residual question it answers — did this satisfy intent — is the
point, and it is the one thing no canonical source owns. It keeps its cache legitimately under
§3.1: a paid derivation is not the same as a free recomputation, and caching it is correct rather
than a refresh obligation nobody meets.

> **Correction 2026-08-20.** This paragraph warned that the judge reads `SCORES_FILE`, "which
> couples it to §4's open verdict — the two were filed as independent and are not." That was
> wrong, and the retirement sweep found it: the score was loaded, threaded through two call sites
> and destructured — and **never read**. `evaluate-session.mjs:214` says why, in a comment older
> than this tempdoc: *"Header — intentionally excludes process score to avoid anchoring the
> judge."* So retiring PHI removes a dead parameter and cannot change any judge verdict. D2 and
> §4 really were independent; I asserted a coupling from an import rather than from a use.

## 6. One cross-cutting change, adopted not invented

844 set the criterion *a dev tool must not report state it did not verify*. 856 §3.2 set *absent
evidence is not negative evidence*. These are the same criterion on two surfaces, and this
tempdoc adopts it rather than minting a third phrasing.

Applied here it has one concrete consequence: **an instrument must not let a conclusion rest on a
sample too small to support it.** Today a starved run prints a plausible result — one session,
N=7 of 20, zero joined pairs — and a reader must know the denominators to catch it. That is the
failure that made this lane's decay invisible for five weeks.

**An earlier draft said "exit non-zero with starved". That was the weaker design**, and the repo
already has the better one. `scripts/ci/check-agent-quality-trend.mjs:53,70,76,103` declares a
minimum **in data** (`min_sessions`, from its baseline file), computes an `insufficient` flag,
surfaces it prominently, and gates only the *conclusion* on it — while still printing the numbers.
Refusing to run throws away readable data; refusing to conclude does not. Conform to that shape
rather than the one this section first proposed. `MIN_TOOL_CALLS` (`score-session.mjs:27,319`) is
the same idea already applied to under-sized sessions.

## 7. What this orphans

Named here, not deferred to a cleanup sweep:

- **`generate-dashboard.mjs` and `dashboard.html`** — retired by D1, swept in this tempdoc's work
  along with `test-pipeline.mjs`'s Test 19 and the prose that describes the dashboard as live.
- **`outcomes.ndjson` as maintained state** — the record survives as a view; the file becomes an
  opt-in stamped report. Any reader treating it as an authority is orphaned with it.
- **`friction-excluded-sessions.json`** — all 31 listed session ids have rotated away (verified
  twice, independently), so it excludes nothing while `baseline-economics` printed "0 excluded by
  scope filter" as though that were an observation.

  This section first said the only honest options were to derive the exclusion from a non-decaying
  rule or delete it, and that *"a maintained list of dead ids is not an option."* **That was too
  absolute, and implementation took a third path that is better.** Deriving is not available: two
  of the three exclusion classes need a content judgement only the LLM judge can make. Deleting is
  not available either — the file has **four** consumers, not the one this tempdoc assumed, so
  removing it would have been a half-sweep. The path taken applies this tempdoc's own §9.1: the
  list is a **capture**, a hand classification made against evidence that has since rotated, and a
  capture that no longer matches is not thereby wrong — it is inert. So it is marked as captured,
  its *reasoning* is recorded so a re-run can reproduce the judgement rather than the ids, and the
  report now says "scope filter matched no session here — 0 of 31 listed ids" instead of asserting
  an exclusion it did not perform. (Quoted verbatim from `lib/telemetry-io.mjs`; an earlier draft of this
  line paraphrased the output, which is the `catalog-verbatim` shape in miniature.)
- **The PHI scoring product**, orphaned by §4.5: `score-session.mjs`, `correlate-signals.mjs`,
  `scores.ndjson` and its `SCORES_FILE` constant, `scripts/ci/check-agent-quality-trend.mjs` and
  `agent-quality-baselines.v1.json`, **three** fields of `session-index.json` (`score`, `flags`
  and `anomalies_count` — all read the same score record, so removing only the first would have
  left two hardcoded to `[]` and `0`), and the score input `evaluate-session` passed to the judge
  (a dead parameter — see the correction in §5). The session **reports** stay — `analyze-trends`,
  `context-attribution`, `cost-session` and the judge all read them, and only the scoring layer on
  top is retiring. This list moved twice: it first read RETIRE, was voided when §4 reopened, and
  is restored now on different evidence (§4.4).

The r=0.064 finding therefore stays in `docs/explanation/21-agent-analytics-pipeline.md` rather
than relocating — relocating a finding is part of retiring the thing it justifies, and that
retirement has not happened.

Its citation was wrong there and is now fixed. The doc cited **tempdoc 118**; the verifiable source
is **277 §C4**, which actually computes it (N=116, 73 complete / 38 partial). A "2.8-point gap"
figure the doc attached to that citation appears nowhere in the repo and was replaced with the
computed numbers rather than propagated.

**A caution about the reasoning, recorded because the first version of it was unsound.** No `118-*`
file exists on disk or anywhere in this repo's git history — but that proves less than it appears:
public history begins at the v0.1.0 release squash, so it cannot reach anything from before
publication, and tempdocs 262/264/272/276 discuss 118's *content*, which is positive evidence it
existed privately. The defensible claim is that the citation is **unresolvable for a reader of the
public repo**, not that its target never existed. Roughly five code citations of 118 remain and are
logged; they are a dangling-citation class, not a fabrication.

## 8. Scope

In: the four verdicts, the view conversion, the capture/recompute distinction and its marking, the
insufficiency rule, the `task_type` defect (§4.2), and the named orphans.

Out: **collapsing** the reports onto the transcript lane. The two lanes answer different questions
— transcripts hold cost and behaviour; the JOIN holds merge, build, tempdoc and gate outcomes that
transcripts do not contain — so this is not duplication and merging them would lose the facts.

Note the distinction from §4.3, which is easy to blur: re-basing *report generation* on transcripts
so the corpus stops being capped by a rotating store is a different thing from collapsing the
lanes, and it is the route to PHI's missing evidence. It is out of scope here for size, not for
correctness, and it wants its own tempdoc — it also serves 856's falsifier, so it is not solely
858's to define.

Also out: **rework instrumentation**, which the delegate-by-default falsifier needs and nothing
measures. 622 §6.3 already named its source ("was the work fixed or reverted later → git churn
over subsequent sessions"). It is a distinct capability, not a repair of this lane, and giving it
its own tempdoc is better than smuggling it in here.

## 9. Reach

### 9.1 The principle

> **Recompute what survives; capture only what time destroys — and record which you did.**

The first half is 856 §10.2. The second half is what designing against it produced: a shared
file that the next run overwrites cannot be recomputed, and pretending otherwise manufactures an
answer. The third clause is what makes it checkable — a consumer must be able to tell a derived
value from a captured one, which is the `fact`/`inference` grammar 622 already provides.

This is a narrowing of an existing principle, not a new one. It conforms to 856 §9.1 (put the key
in the authority), 844's honesty criterion, and 622's tiering. It should not get a register or a
gate of its own.

**Where it applies, and what already violates it:**

- `costs.ndjson` — recomputable from transcripts, kept as maintained state, and currently pricing
  billions of tokens at $0 in roughly half its rows. Violation.
- `friction-excluded-sessions.json` — maintained list, now a no-op. Violation.
- Governance SARIF in the outcome JOIN — genuinely destroyed by time, so capture is right; but it
  is not currently *marked* as captured. Partial violation of the third clause.
- `session-merges.ndjson` — 856 moved it from maintained to derivable. Conforming.
- The observations conditions store — mixed: the shard adds are durable git history, the folded
  store is maintained. Worth examining under this rule, not here.

**Evidence it earns its keep:** a class of bug disappears — no report disagrees with a fresh
recomputation of itself, because there is nothing to disagree with. The concrete test is that a
stale-store finding stops appearing in audits of this stack.

**Retirement condition:** if recomputation becomes the dominant cost of running the stack — a
report that takes minutes where a cached read took milliseconds — the principle is wrong at this
scale and caching should return, with staleness markers instead of prohibition. It should also be
retired if the marking clause goes unused: an unread distinction is ceremony.

### 9.2 A smaller observation, recorded not built

Three of this repo's audits — 844 on the dev surface, 856 on merge attribution, this one — each
independently arrived at a criterion of the same shape: *do not report what you did not observe*.
That the same rule keeps being rediscovered on unrelated surfaces is weak evidence it belongs
somewhere more central than three tempdocs. It is not built here, and it should not be until a
fourth surface finds it independently — one more instance is the threshold, because two could be
coincidence and three is a pattern only if it predicts the fourth.
