---
status: design
created: 2026-08-19
updated: 2026-08-19
author: agent session (Opus 5, 1M context)
charter: where the session→merge key lives, and what a missing link is allowed to mean
---

# 856 — Merge attribution: put the key in the authority

## 0. What this doc corrects about its own brief

The brief arrived as "the merge ledger is broken, record at merge instead of at teardown."
Two of its premises did not survive investigation:

- **REFUTED — "`remove-worktree.cjs` guesses the merge commit from main's HEAD."**
  True when the observations were written; fixed in PR #202 (`a10ed1bf`, 2026-07-16).
  `mergeCommitFromPr()` now resolves the commit from the branch's merged PR and skips
  loudly rather than guessing. Rows pointing off `origin/main` fell from 43% (July) to
  2% (August). Correctness is not the defect.
- **DEMOTED — "week-bucketing by row timestamp fakes the weekly volatility."**
  Measured: 6.3% of rows change ISO week when bucketed by commit date; median lag 0.00
  days. It cannot explain 2–2.7× weekly swings. Real, minor, not part of this design.

The live defect is **completeness**, and it is a property of the design rather than a bug
in the code.

## 1. The problem

`tmp/agent-telemetry/session-merges.ndjson` links a session to the squash commit it
produced. It is the join behind cost-per-merge (`baseline-economics.mjs`) and behind
"did this session ship" (`outcome-session.mjs`).

Measured 2026-08-19 (each number reproducible by the command named; the ledger and `git log` are
the only inputs). **These are moving-window counts and drift with every merge** — re-measured the
same day, the squash-commit denominator had already moved 463 to 475 and the ledger 400 to 403.
The ratios and the load-bearing figures hold; the absolutes are as-of. This is the same caution §7
applies to the dollar figures, and it applies here for the same reason:

| Fact | Value | How to reproduce |
|---|---|---|
| Squash PRs linked | 229 / 463 (49.5%) | compare ledger `merge_commit` set against `git log --pretty=%H` filtered to `(#N)` subjects |
| Linked, post-fix window only | 174 / 284 (61%) | same, `--since 2026-07-16` |
| Weekly linkage | 15% – 78%, no trend | same, bucketed by ISO week |
| Lag, merge → ledger row | median 6 min, p90 ~20 h, max 22 d | ledger `ts` minus `git log -1 --format=%cI` |
| Duplicate rows | 83 | duplicate `(session_id, merge_commit)` pairs |
| Rows citing a commit not on `origin/main` | 79 | `git merge-base --is-ancestor <c> origin/main` |

Two consequences, in ascending order of seriousness.

**The instrument cannot answer the questions it was built for.** `CLAUDE.md`'s
delegate-by-default falsifier names `baseline-economics.mjs` as its instrument and a
judgment date of ~2026-09-14. Its pre-window baseline is empty (transcripts rotate at
the 30-day default; the oldest surviving one starts one day before the window opened),
and half its denominator is missing. It is not judgeable on schedule, and waiting does
not fix that.

**A missing link is published as a hard fact.** `outcome-session.mjs:31`:

```js
if (!hit) return fact(false, 'git/session-merges', 'no recorded merge for this session');
```

`kind: 'fact'`, which that file's own header says the LLM judge may never overwrite. So
roughly 234 merged PRs are asserted at fact tier as *did not merge*, outranking the one
signal that could have contradicted them. This is `offered-not-asserted` in a new place:
a thing that was never observed, reported as a thing that was observed to be absent.

## 2. Why it under-reports by construction

The ledger is **captured at a moment** — worktree teardown. Capture-at-a-moment records
an event only when the moment happens. Teardown does not happen when the session keeps
the worktree, abandons it, works from the main checkout, or tears down before the merge
queue has landed the PR (`gh pr list --state merged` is then correctly empty, the script
correctly skips, and nobody returns to backfill).

The lag distribution is the fingerprint: a median of 6 minutes is teardown firing right
after a merge; a 22-day tail is manual backfill. Coverage swinging 15%–78% week to week
is not drift, it is a coin flip on whether the moment occurred.

No amount of moving the capture point removes this. Moving it from teardown to `/publish`
raises coverage — `/publish` is closer to the merge and runs on every publication that goes
through it — but it is the same shape, and it records nothing whenever a merge happens outside it.

## 3. The design: the key belongs in the commit

Tempdoc 622 §6.3 already named the right answer and the repo built the other one:

> So Layer B needs one cheap *prerequisite* keying step — e.g. **a `Session-Id:` git
> trailer on merge commits**, or a session-close `merge↔session` map

The map was built. But 622's stated purpose for Layer B is *"applies projection-not-fork
to the measurement tooling itself"* — and a side-file keyed to git is exactly a fork: a
second authority that drifts from the first. The keying step inherited the failure mode
the layer above it was designed to eliminate.

**Design: the squash commit carries its own `Session-Id:` line.**

> **Correction (independent review, same day).** This section first specified a git *trailer*,
> following 622's wording. That form does not survive this repo's squash path and was refuted
> before it shipped. GitHub appends `---------` + `Co-authored-by:` **after** the PR body when
> squashing, and git's trailer block is the last paragraph — so a `Session-Id:` written at the
> end of the body sits two paragraphs up and `%(trailers:key=Session-Id)` returns nothing.
> Measured over the 273 squash commits since 2026-07-16: 262 carry an appended `Co-authored-by:`
> line, 227 an appended "Generated with" block, 171 a `-----` separator — and only 3 carry none of
> the three, i.e. only 3 where a trailing position would have survived. It is the default path. The check meant to catch this inspected the PR body, where the line *is* last,
> and would have reported success while the commit carried no link — `static-green ≠
> live-working`, inside the mechanism built to prevent silent loss.
>
> The thesis is unchanged and survives: the key lives in the authority. What GitHub takes away
> is git's *trailer parser*, not the commit message. The line still lands in `main` permanently
> and is publicly verifiable; the reader matches `Session-Id:` at line start anywhere in the
> commit body (`%B`) rather than through `%(trailers:…)`. The word "trailer" is retired from
> this design so the refuted assumption is not preserved as vocabulary.

- `/publish` authors the PR body, which becomes the squash message under ADR-0045. A
  `Session-Id:` line added there lands in `main` permanently.
- Git becomes the authority. `session-merges.ndjson` becomes a **derived cache** that can
  be rebuilt at any time by scanning commit messages — it cannot drift from git, because it is
  no longer a claim about git.
- Coverage stops depending on whether a workflow moment occurred and starts depending on
  whether the squash message is well-formed, which `preview-squash-message.mjs` already
  checks and which `/publish` already treats as the last gate before permanent public
  history.
- It survives transcript rotation, which the current design does not: 60% of the
  historical links this session could recover are for sessions whose transcripts are gone.

The convention matches existing practice — this repo already appends `Co-Authored-By:`
to commit messages — and exposes nothing new: session UUIDs are already published, as the
filenames of `docs/observations.d/<session-uuid>.md` shards.

### 3.1 Evidence tiers, conforming to the grammar that exists

622 defines `kind: 'fact' | 'inference'` with `source`. This design populates that grammar
honestly and extends it by exactly one value, `unknown`, for provenance we cannot place — the
alternative was laundering an unrecognised source into the fact tier, which is what §3.2 forbids.

| Tier | Source | Meaning |
|---|---|---|
| `fact` | `commit-message` | The commit says so. Permanent, publicly verifiable. |
| `fact` | `teardown` / `publish` | A workflow moment recorded it. Legacy, still true. |
| `inference` | `shard-inference` | Recovered from git history; measured error rate, see §4. |
| `unknown` | unrecognised | Provenance we cannot place. Never promoted to `fact`. |
| *absent* | — | `unknown`. Never `false`. |

**Precedence, when a row's declared `source` and `kind` disagree: the weaker tier wins**
(`unknown` < `inference` < `fact`). Deriving the tier from either field alone is an upgrade in
one direction — trusting `kind` lets a `shard-inference` row claim it was observed; trusting
`source` promotes a row whose writer explicitly disclaimed observation. Normalization may only
ever weaken a claim, so a disagreement degrades to caution instead of resolving toward whichever
field the reader happened to trust. Every in-repo writer goes through one constructor where the
fields agree by construction, so a disagreement means a foreign or corrupt row — exactly the case
to be conservative about.

A corollary the same invariant forces: **a row whose tier is `unknown` cannot carry the claim.**
If every link for a session is unrecognisable, the reported value is `unknown` — the same answer
as having no links at all, because that is the same epistemic position. The rows stay listed so
the gap is legible rather than swallowed.

The row shape gains a `source` field. Without it, a recovered row is indistinguishable
from an observed one, and the fact tier silently absorbs an inference — the failure
`catalog-verbatim` describes.

### 3.2 Absent evidence is not negative evidence

`mergeFact` must return `unknown` when no link exists.

This is not a new principle for this repo, and it should not be designed as one. The
correct pattern sits **thirteen lines below the broken one, in the same file, from the
same tempdoc**: `buildFact` returns `fact('unknown', 'build-counter', 'no build-fails
file')` when its input is missing. `mergeFact` is the outlier. Conform to `buildFact`;
do not invent a parallel convention.

`mergeFact` also uses `.at(-1)`, so a session with several merges reports one arbitrary
commit. Sessions in the ledger hold up to 56. The field is answering "which merge" when
the question is "which merges".

## 4. Recovering the history that has no `Session-Id:` line

Observation shards are named by session UUID and ride into `main` inside the PR carrying
that session's work, so the commit that *adds* a shard is a durable session→merge link
already sitting in public history:

```
git log --diff-filter=A --name-only -- docs/observations.d/
```

Under the restrictions below this yields **66 links across 48 sessions**, of which 19 sessions the
ledger has never seen. (An earlier draft of this doc said 117/38. That figure was the
*unrestricted* novel count, taken before the single-shard rule was applied; it is corrected
here rather than left standing. Reconciliation, all against the same 400-row ledger §7 cites:
171 shard→commit links exist in history, 130 are absent from the ledger, 100 survive the
single-shard + squash-PR restrictions, and 66 remain once already-linked pairs are skipped.)

**It is not fact tier, and the measurement says so.** Falsifier: a session cannot merge a
PR after it ended. Comparing each recovered link against the session's transcript window:

| Commit shape | n | false positives | rate |
|---|--:|--:|--:|
| single-shard | 45 | 4 | 8.9% |
| multi-shard | 18 | 10 | 55.6% |
| **all** | **63** | **14** | **22.2%** (95% CI ±10.3) |

Multi-shard commits sweep in shards belonging to other sessions — one commit adding five
shards is claimed by three sessions that ended 1, 1, and 7 days earlier. Restricting the
recovery to single-shard commits is therefore part of the design, not a tuning knob.

Two limits stated because they are load-bearing: only 63 of 158 candidate links have a
surviving transcript, so the error rate is unmeasured for the majority; and some of the
residual 8.9% is not error but **ambiguity** — see §5.

## 5. The open question this design forces

**What does a link assert — that the session did the work, or that it performed the
merge?** The two sources disagree by construction. Teardown records whoever tore the
worktree down; the shard key records whoever authored the work. Cost-per-merge divides a
session's spend by its merges, which argues for the author. "Did this session ship?"
also argues for the author. Nothing in the current code states which is meant, and the
ambiguity is why a portion of §4's residual error is not clearly error.

The design should state it once, in the line’s definition, rather than let two writers
answer differently. Recommendation: **the session that authored the work**, because both
consumers are asking about work, and because `/publish` authoring the line makes the
authoring session the natural writer. This is a decision to record, not a detail to infer.

## 6. What this orphans

Per `retire-with-a-sweep`, named here rather than deferred:

- **`session-merges.ndjson` as an authority.** It survives as a derived cache. Its
  implicit "every row is fact tier" contract does not.
- **`mergeFact`'s `fact(false, …)` branch** — replaced, not extended.
- **`mergeFact`'s `.at(-1)`** single-merge assumption — replaced by the full set.
- **622 §6.3's "or a session-close `merge↔session` map" alternative** — this doc
  supersedes it; 622 should be annotated rather than silently left standing.
- **`recordMergeLink()` in `scripts/dev/remove-worktree.cjs`** — a *tombstone candidate*,
  not a deletion yet. It is the only writer for merges that bypass `/publish`, so it stays
  until the commit-message key demonstrably covers the path. **Retirement condition: coverage
  ≥95% of squash PRs over 30 consecutive days, measured by the same `git log` scan that
  builds the cache.** If that holds, delete it and its `gh pr list` call in the same PR.

Two entries in `docs/observations.md` are stale as of this design and should be resolved
rather than left to accrue `seen` counts: the `remove-worktree` HEAD-guessing entries
(fixed in #202) and the "`record-merge.mjs` has NO dedicated test" entry (the file exists;
what is true is narrower — it covers only the cost-upsert wiring, not the link write).

## 7. Restatement is not improvement

Applying deduplication and an `origin/main` ancestry filter to the existing window moves
**raw** merge rows 400 → 238 (−40.5%) and collapses the `other` class from 103 to 13.

**But −40.5% is not the falsifier's denominator swing, and saying so unqualified mislabels
the restatement in the opposite direction.** Cost-per-merge divides by *attributed* merges,
and most rejected rows were already in the `unattributable` bucket — outside that denominator
to begin with. Attributed merges move **171 → 165 (−3.5%)**, and cost/merge rises **+3.6%** on unchanged spend.

The ratios are reproducible from the ledger and `git`; the absolute dollar figures are not, and
are deliberately omitted here. Two independent runs hours apart produced $107.11 → $111.00 and
$107.73 → $111.65 — identical ratios over a moving spend total, because sessions are still
accruing cost while the window stays open. A dollar figure in this doc would be stale the day
after it was written, and public-facing text should not carry a number that cannot be
reproduced from a citable run.

Both numbers belong in the report's caveat, and an assertion pins them so the caveat cannot
drift from the code. This correction is recorded rather than quietly folded in: the first
draft of this section carried the −40.5% into a claim about the falsifier without checking
which denominator it applied to, which is `interrogate-results` failing on a number that
happened to fit the argument.

Recovery pushes the other way. Both effects must be reported as separate, labelled
components of a restatement, never netted into a single moved number. A four-week-old
falsifier reading a 40% swing in its own denominator as a result would be
`interrogate-results` failing in the one place this repo instruments most heavily.

Rejected rows are reported, not dropped — a filtered row is a finding about the ledger,
and silently discarding it reproduces the same absent-vs-negative confusion §3.2 fixes.

## 8. Scope

In: the `Session-Id:` commit-message key and its writer; the cache rebuilt from git; `source` on the row; `unknown`
for absent; the full merge set instead of the last; dedup and ancestry with reported
rejects; the restricted, marked recovery; the named tombstone and its retirement
condition.

Out: week-date bucketing (§0). Out: a hook that watches for `gh pr merge` — measurement
shows every observed invocation form shares the substring `pr merge`, so it is feasible,
but it is a second capture-at-a-moment instrument and this design exists to stop adding
those. It becomes worth revisiting only if commit-message coverage stalls below the §6
retirement threshold.

## 9. Reach

### 9.1 The principle: put the join key in the authority, not beside it

When two records must be joined, the key belongs inside one of the joined authorities,
not in a third file that asserts a relationship between them. A side-map is a fork: it
can be incomplete, stale, or wrong, and nothing about reading it reveals which.

This is **projection-not-fork applied to keys** rather than to records, and the repo
already registers the record-level version in `governance/execution-surfaces.v1.json`.
It is a narrowing of an existing principle, not a new one.

Where it already applies in this repo:

- **Positive instance:** observation shards put the session id *in the filename* and
  commit it. That is the only reason §4's recovery is possible at all.
- **`costs.ndjson`** — a side map keyed by session; 11 of 23 rows currently price 3.49B
  Opus-5 tokens at $0, and nothing about reading a row says it is stale.
- **`friction-excluded-sessions.json`** — a side list of session ids that now excludes
  nothing, because every id in it has rotated away, while the report still prints
  "0 excluded" as though that were an observation. **Fixed in the same PR by tempdoc 858** — the
  list is now marked as a capture and four consumers report "scope filter matched no session here
  — 0 of 31 listed ids" instead. Left in this list because it is the clearest instance of the
  shape, not because it is still broken.
- **`current-session-id`** — a side pointer whose correctness depends on which checkout
  wrote it last.

**Evidence it earns its keep:** link coverage becomes insensitive to workflow variation —
concretely, weekly linkage stops swinging 15%–78% and stays flat as PR volume changes.
**Retirement condition:** if commit-message coverage proves no more stable than the side-map's,
the principle is not paying and the extra ceremony should be removed rather than defended.

### 9.2 The invariant: absent evidence is not negative evidence

A reporting layer must distinguish "observed to be false" from "never observed". Reporting
the second as the first manufactures facts, and manufactured facts are worse than gaps
because a gap is legible.

Already correct: `buildFact` → `unknown`. Already violated: `mergeFact` → `false`;
`cost-session.mjs` pricing unknown models at $0 rather than refusing to price them;
`friction-excluded-sessions.json` reporting an empty exclusion as a performed exclusion (that
  last one fixed by 858 in this same PR).
Adjacent, unexamined: `docs/observations.md` carries 358 conditions with `probeable: 0`
and no closed state, so "still open" and "fixed but never deleted" are indistinguishable.

This is not new structure and should not become any. It is one line of discipline per
reader, and the repo already has the pattern in-tree.

**Evidence it earns its keep:** a downstream consumer changes a conclusion because a field
said `unknown` instead of `false` — the merge case is already one, since the falsifier's
denominator depends on it. **Retirement condition:** if `unknown` becomes the dominant
value across a report's fields, the report has stopped saying anything and the honest move
is to fix the collection, not to keep publishing a page of nulls.

## 10. Theorization — what this fix is an instance of

Not design. This records directions worth considering before anything further is built, kept
separate from §1–§8, which are settled.

### 10.1 The first framing was too coarse

§2 says the ledger under-reports because it is *captured at a moment*. Applied to the wider
analytics stack that framing does not survive contact with the evidence. The per-session stores
the hooks write — read counts, edit counts, repeat buffers, build-fail counters, turn counts —
are all current. Collection is not the problem anywhere. What is five weeks stale is
`scores.ndjson`, `judge-outcomes.ndjson` and `dashboard.html`, all frozen at the same minute,
with `outcomes.ndjson` absent entirely.

Those are **aggregation** stages. The sharper shape:

> **A pipeline decays at its first stage that requires invocation.**

Hooks fire whether or not anyone is paying attention. The stage after them does not, and
everything downstream inherits that staleness however healthy the collection underneath.

The instruments that stayed alive have no intermediate stage: they recompute from raw
transcripts on every run. They can be *wrong* — window edges, transcript rotation — but they
cannot be *stale*, because there is no state to keep fresh.

### 10.2 Candidate principle: prefer recomputation over maintained derived state

A derived store is a cache with a refresh obligation attached, and an unattended refresh
obligation is not met. This is broader than §9.1: that says where a *key* should live; this asks
whether a derived artifact should exist at all.

Same shape, five places, observable today:

| Artifact | Refresh obligation | State |
|---|---|---|
| `scores` / `judge-outcomes` / `dashboard` | run the report chain | one run, five weeks ago |
| `costs.ndjson` | upsert at merge | 11 of 23 rows price billions of tokens at $0 |
| `session-merges.ndjson` | record at teardown | ~50% met — this tempdoc |
| observations conditions store | `fold --apply` | 358 conditions, `probeable: 0`, not converging |
| `friction-excluded-sessions.json` | keep session ids current | every id rotated; excluded nothing while reporting "0 excluded" — fixed by 858 in this PR |

The last is sharpest: a maintained list that has silently become a no-op while printing a number
that reads like an observation.

**Counter-hypothesis, stated because it is plausible.** The events lane may be dormant by intent
— a maintainer tool used episodically, where staleness between uses is correct and mtimes prove
nothing. The weak evidence against is that three artifacts share one timestamp to the minute,
which looks like a single run rather than periodic use. Suggestive, not conclusive, and better
settled by asking than by inference.

### 10.3 Directions worth banking

- **Collapse the pipeline** — rebuild the report chain as recompute-from-transcript and delete
  the intermediate stores. The transcript lane already holds richer data than the stores do.
- **Retire the lane** — apply `retire-with-a-sweep` inward. The repo gates product code with
  `consumer-presence` and `dead-code`; neither is applied to the tooling that measures the work.
- **Refuse rather than degrade** — an instrument below its viable sample size should exit
  non-zero saying so. Today a starved run prints a plausible result, which is the §3.2 invariant
  broken by the instruments that enforce it elsewhere.
- **A liveness view, but derived** — the trap is that a hand-maintained manifest of what is alive
  becomes the sixth row of the table above. It works only if each instrument declares its inputs
  in code and the check reads them.
- **Instrument rework** — the falsifier this tempdoc serves needs it, nothing measures it, and
  622 §6.3 already named the source ("was the work fixed or reverted later → git churn over
  subsequent sessions"). Specified, never built.

### 10.4 Assumptions worth challenging first

- **Cost-per-merge rewards merge frequency.** A session shipping one large correct change scores
  worse than one shipping five trivial ones. This tempdoc's own work is a single PR and would
  score poorly by its own instrument.
- **The session is assumed to be the unit of work.** It is not: work spans sessions, and one
  session spans many delegated workers. The tempdoc may be the better unit — and the merge link
  would then key differently, which touches §5's unresolved question.
- **That measuring improves anything.** No link is established anywhere between an instrument
  existing and an outcome moving. `payback` asks that of tempdocs; nothing asks it of instruments.

Carried to tempdoc 858 rather than pursued here — the present problem is merge attribution, and
§8 stays the scope of this work.

**858 refines §10.2's principle and this doc defers to it.** Designing against "prefer
recomputation over maintained derived state" found its boundary: governance SARIF is a single
shared file that the next gate run overwrites, so it cannot be recomputed later and must be
captured when observed. The usable form is *recompute what survives; capture only what time
destroys — and record which you did*, which 858 §9.1 states with its evidence and retirement
condition. Read that version, not this one.
