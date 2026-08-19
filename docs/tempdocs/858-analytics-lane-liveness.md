---
status: design (two verdicts settled, two owner decisions pending)
created: 2026-08-19
updated: 2026-08-19
author: agent session (Opus 5, 1M context)
charter: the analytics events lane has three products, not one — decide each on its own merits
---

# 858 — Analytics lane: outcomes are a view, PHI is answered

Opened from tempdoc 856 §10. The sketch asked one question — is the events lane alive? The
design's first move is that this was the wrong unit. The lane holds **three products with
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

## 2. The three products

| Product | Instruments | Store | Verdict |
|---|---|---|---|
| The outcome JOIN | `outcome-session` | `outcomes.ndjson` | **KEEP — convert store to view** (§3) |
| Process-hygiene scoring | `score-session`, `correlate-signals` | `scores.ndjson` | **RETIRE — the question is answered** (§4) |
| Judge + dashboard | `evaluate-session`, `generate-dashboard` | `judge-outcomes.ndjson`, `dashboard.html` | **Owner decision** (§5) |

Consumer analysis, mechanical: nothing outside `scripts/agent-analytics/` reads `scores.ndjson`,
`outcomes.ndjson`, or `dashboard.html`. The lane's only consumers are inside the lane — except
the dashboard, whose consumer is a person, which is exactly why it is §5 and not §4.

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

## 4. RETIRE process-hygiene scoring — its own header answers it

`score-session.mjs:8-9`, verbatim:

> PHI measures tool discipline and process patterns — it does **NOT** predict task completion or
> outcome quality (r=0.064 at N=116, see tempdoc 277 C4).

The metric was built to test whether process hygiene predicts outcomes. The test was run, at
N=116, and the answer was no. `correlate-signals` exists to correlate `scores.ndjson` against
outcomes — that is, to keep asking a question already answered by the file it reads.

This is apparatus outliving its reason: tempdoc 742's class exactly. Following 844 §4.3, **retire
the machinery and keep the finding.** The durable output of this work is the r=0.064 result, and
it belongs in the reference layer where a future agent proposing a process-hygiene score will
find it — not in a live store that implies the question is open.

Retiring here means one sweep, per `retire-with-a-sweep`: `score-session.mjs`,
`correlate-signals.mjs`, `scores.ndjson`, the `SCORES_FILE` constant, the dashboard's scores
panel if the dashboard survives §5, and the PHI rows in any doc that presents it as live. Then
grep the names to confirm no residue.

**Predictable evasion, named inline:** "keep it, it costs nothing to leave." It costs the next
agent an orientation pass and a plausible-looking store, which is what this tempdoc was opened to
stop.

## 5. Owner decisions

Neither is an agent's call, and both are cheap to answer.

**D1 — the dashboard.** `generate-dashboard.html` is human-facing; its only consumer is the
person who opens it. Mechanical analysis cannot say whether it is read, and inferring
abandonment from a file date is exactly the reasoning 856 §3.2 forbids. If it is read, it becomes
a render of the §3 view and stays. If it is not, it retires with §4's sweep.

**D2 — the LLM judge.** `evaluate-session` costs money per session and feeds only the `inference`
block, which by 622's own design can never override a fact. If the facts in §3 are what get used,
the judge is paying for a field nobody reads. If the residual questions it answers — did this
satisfy intent — are the point, it stays and legitimately keeps its cache under §3.1.

## 6. One cross-cutting change, adopted not invented

844 set the criterion *a dev tool must not report state it did not verify*. 856 §3.2 set *absent
evidence is not negative evidence*. These are the same criterion on two surfaces, and this
tempdoc adopts it rather than minting a third phrasing.

Applied here it has one concrete consequence: **an instrument below its viable sample size must
refuse, not degrade.** Today a starved run prints a plausible result — one session, N=7 of 20,
zero joined pairs — and a reader must know the denominators to catch it. Exiting non-zero with
"starved" is a small change and is worth making regardless of how D1 and D2 resolve, because it
is the failure that made this lane's decay invisible for five weeks.

## 7. What this orphans

Named here, not deferred to a cleanup sweep:

- **`scores.ndjson`, `score-session.mjs`, `correlate-signals.mjs`** — retired by §4; the r=0.064
  finding relocates to the reference layer in the same change.
- **`outcomes.ndjson` as maintained state** — the record survives as a view; the file becomes an
  opt-in report. Any reader treating it as an authority is orphaned with it.
- **`friction-excluded-sessions.json`** — every session id in it has rotated away, so it excludes
  nothing while the report still prints "0 excluded" as though that were observed. Same family,
  small, and it should be derived from a scope rule or deleted rather than left to read as data.
- **`dashboard.html`** and the judge cache — only if D1/D2 resolve that way.

## 8. Scope

In: the three verdicts, the view conversion, the capture/recompute distinction and its marking,
the refuse-don't-degrade rule, and the named orphans.

Out: rebuilding any report on the transcript lane. The two lanes answer different questions —
transcripts hold cost and behaviour; the JOIN holds merge, build, tempdoc and gate outcomes that
transcripts do not contain — so this is not duplication and collapsing them would lose the facts.

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
