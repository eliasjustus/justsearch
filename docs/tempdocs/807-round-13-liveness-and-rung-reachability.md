---
title: Round-13 fix campaign — snapshot liveness, and the escalation rungs' landing-only gate
status: "designed 2026-08-05 (Parts A-C); implementation running. Scope: R13-F2 (stale surface vs dead backend, MEDIUM, 5th instance of the campaign's signature class), the rung-reachability class's minimum viable fix (5th finding, now blocking a qualification gate), R13-F1 (installer branding on two templates). Target: a clean 0.2.0 qualification on one confirmation round."
created: 2026-08-05
updated: 2026-08-05
---

# Round-13 fix campaign — liveness and rung reachability

Round 13 (tempdoc 734) was the qualifying round. It found **no blocking product defect** —
golden parity passed its blocking assertion for the first time, the fresh-install AI journey,
locked-state truthfulness, uninstall and warm reinstall are all clean. Two things stop it being
a clean qualification, and both are fifth instances of classes this project already named.

---

## Part A — R13-F2: a snapshot rendered present-tense

### A.1 What was observed, and what it actually is

The round found the Brain card reporting "Online / Chat and summaries ready" with **zero** Head
or Worker processes alive. The orchestrator reproduced it on the dev stack and found it wider:
with both java processes killed and `/api/health` unreachable, the surface simultaneously
rendered an **animating** "Building semantic search 2.0% · 5,084 pending" progress card,
"Search Quality Features 4/4 active", a populated Runtime card (CUDA available, VRAM 12 GB,
embed queue 4,789), and a **green CONN dot** in the status bar.

So this is not one stale card. Every surface that renders from the *status snapshot* keeps
asserting live facts about a process that no longer exists.

### A.2 The mechanism — and why the existing authority did not catch it

The verdict authority already models this: `SystemHealthVerdict` has an **`unreachable`** kind,
`readinessNotice` words it ("Backend disconnected."), and `verdictOwnsStatus()`
(`aiStateStore.ts:462`) is the shared predicate the status label and tone both consume since
806 W2.

The gap is one layer down. `verdictOwnsStatus` governs the **status line's own wording and
tone**. It does not govern the many surfaces that read *fields off the last successful
snapshot* — `BrainSurface`'s progress card reads `emb?.coveragePercent` and a queue count
directly (`:1868-1890`); the capability count, the runtime card, and the connection dot do the
same. Each renders a **last-known observation in the present tense**, and nothing asks whether
the observation is still live.

That is the campaign's signature class for the fifth time, now in its purest form: **the value
is not wrong — its tense is.** "4/4 active" was true when it was measured. Rendering it while
the backend is gone converts a past measurement into a present claim.

### A.3 Design — one liveness question, asked once

The store already knows everything needed: it has the verdict (including `unreachable`) and it
knows when the snapshot was last refreshed. Add **one derived signal — is the snapshot live? —
projected from the same verdict authority**, and have snapshot-rendering surfaces consume it
rather than each inventing a staleness heuristic.

When the snapshot is not live, surfaces must degrade rather than assert: progress/queue/coverage
figures stop animating and read as last-known (or disappear), capability counts stop claiming
"active", the connection dot reflects reachability rather than the last good poll, and any
action whose precondition is a live backend is unavailable-with-a-reason rather than clickable.
The existing "Backend disconnected." banner stays the loud, correct signal — this change stops
the surfaces *around* it contradicting it.

**Explicitly rejected:** per-surface `if (unreachable)` patches. That is how the status label
and dot drifted apart in the first place (806 W2's Health finding — the label lacked the arm its
own doc comment claimed). One predicate, consumed; the gate that enforces the classifier's
single authority is the model.

**Regression home:** a ui-web test that with an `unreachable` verdict no snapshot-derived
surface renders a present-tense capability/progress claim; plus the standing
`ui-api-truthfulness-under-load` must-watch, which is what caught it.

---

## Part B — The rung-reachability class, root-caused

### B.1 One line explains five findings

`UnifiedChatView.ts:2507` renders the escalation strip as
`${this.isLanding() ? html`<div class="escalation-strip">…` — **the strip exists only in the
empty landing state.** Once a search has run, every rung control is gone from the DOM, and the
only remaining way into a non-default affordance is the "+ Schema" attachment path (which
derives `extract`) or a fresh session.

That single gate explains the whole class:

| Round | Finding |
|---|---|
| 5 | `agent-run` / `free-chat` "not found" — entry points existed, hidden behind composer state |
| 7 (B2a/B2b) | `workflow-run` unreachable by any user; `free-chat` deep-link only |
| 11 | Delegate rung reachable *only from the empty landing state* — ~8 min lost, recovered only after a cold restart returned the app to landing |
| 13 | No route back to the Structured rung after a search — **cost the qualification round its coverage gate** |

Tempdoc 805 F.5c deferred this class on the explicit grounds that it was "four findings without
a blocker." The fifth finding blocked a gate; the deferral's own trigger condition is met.

### B.2 Design — minimum viable, not the full campaign

805 F.5c's full proposal (declare each rung's entry conditions as data, render from the
declaration, make reachability unit-testable) remains the right long-term shape and stays
deferred. What this campaign does is the **smallest honest slice**: the escalation rungs remain
reachable after a search has run — the strip (or an equivalent control carrying the same rungs
with the same availability semantics) does not vanish when `isLanding()` goes false.

Constraints that make this small rather than a redesign: the rung controls, their labels, their
availability-with-reason behaviour and their `data-testid`s already exist and are correct — only
their *render condition* is wrong. Keep the landing presentation as-is; add the post-search
route. Do not redesign the composer.

**Regression home:** a ui-web test asserting each rung is reachable in the post-search state,
not only on landing — the assertion that would have failed for rounds 11 and 13 alike. This
also closes round 13's `shape:core.extract` coverage gap at its cause rather than by renaming a
screenshot.

---

## Part C — R13-F1 and derisk notes

**R13-F1 (LOW).** Interior wizard pages and the uninstaller carry
"JustSearch 0.2.0 - Copyright (c) JustSearch"; the two MUI full pages (Welcome, Finish) render
an empty strip. 806's `bundle.copyright` → `BrandingText` lever works where a strip exists; the
MUI pages do not draw one. Either give those pages a version-bearing element or accept the
partial and correct the claim — the honest options, in that order of preference. The host-side
regression home is a source-side assertion extended to those templates.

**Derisk, recorded before implementation:**
- **C1 — the `unreachable` verdict is already produced** (`aiStateStore.ts:465,578`), so W1
  consumes an existing signal rather than inventing detection. Confirmed at source.
- **C2 — the strip's controls are already correct** in label, availability and test ids
  (`:2508-2545`); only `isLanding()` gates them. Confirmed at source, which is what makes B.2 a
  render-condition change rather than a feature.
- **C3 — open risk on W1's blast radius:** "surfaces that render from the snapshot" is not an
  enumerated set. Implementation must enumerate the consumers it changes and say which it left
  alone, rather than claiming a class-wide fix it did not make. A partial fix honestly scoped is
  acceptable; an overclaimed one is not.
- **C4 — the CONN dot may be a distinct authority** from the AI-state store (the status bar has
  its own connection tracking, `HealthLitView.ts:144` carries a disconnect debounce). If so, the
  fix must reconcile the two rather than adding a third; report which authority won.
