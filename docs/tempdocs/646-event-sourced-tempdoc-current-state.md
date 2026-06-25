---
title: "The event-sourced tempdoc: a tempdoc's current-state should be a NON-DRIFTING PROJECTION of its own append-only history, not a hand-maintained banner that lies. Keeps the tempdoc the single source (no offloading to task list / canonical docs) while making 'what's left right now?' a one-glance, always-true view. An instance of the canonical-authority-and-projection / projection-vs-fork seam (622/623/625) applied reflexively to the working document. STUB / design-recorded — principle settled, machinery deferred to the rule-of-three trigger."
type: tempdocs
status: proposed — STUB / design-recorded (principle settled; marker-grammar + generator + drift-gate deferred until the pain recurs across enough large active tempdocs)
created: 2026-06-24
author: agent analysis — originated as "item 2" of tempdoc 635's session retrospectives (the cost of re-deriving "what's left" each turn from a long append-only tempdoc); investigated + theorized 2026-06-24
related:
  - 635-contamination-resistant-eval-corpus      # origin — the long, active, multi-turn tempdoc whose drift cost surfaced this (workflow-lessons / the deferred-by-blocker + handed-off restructuring)
  - 625-asserted-measurement-provenance           # the projection-vs-fork generalization this is a (document-process) instance of
  - 623-reproducible-benchmark-release            # the measurement-side first instance of the same seam (a published number is a projection of a run)
  - 622-agent-telemetry-native-otel-migration     # the canonical-authority-and-projection seam, named
---

> **STUB / design-recorded.** Captures the *correct long-term design* + why it conforms to an existing seam,
> **not** structure to build now (see Trigger). Constraint that shaped it (owner-directed): the tempdoc stays
> the **single source** — no solving this by moving current-state into the task list or canonical docs.

# 646 — The event-sourced tempdoc (current-state as a projection of the append-only log)

## The problem (stated structurally)
A tempdoc is an **append-only log** — you add to the bottom, never rewrite (its value as dated archaeology
depends on that, and the README enshrines it: tempdocs are "outdated by design, not maintained"). But while a
tempdoc is the **active working surface** across many turns, *"what is the current state / what is left?"* is a
constant, live question — and an append-only log is the wrong shape to **read** current state out of (you
replay the whole log to rebuild it). The naive fix — a hand-maintained "current state" banner at the top —
**drifts**: nobody re-edits it on every append, it lags the latest entry, and a stale banner *lies* (which is
exactly why the README distrusts in-doc current-state). So: append-only history and findable-current-state pull
in opposite directions **inside one document**, and a hand-maintained head fails by drift.

## The design — derive the current-state, don't assert it
**Make the current-state a non-drifting *projection* of the body, computed from the history, never typed
separately.** This is the canonical-authority-and-projection move (the codebase's own seam) applied reflexively
to the tempdoc:

1. **The body stays the append-only authority.** Unchanged — the dated log, written frankly, never rewritten.
   Honors the "outdated by design" philosophy completely.
2. **Each append that changes a tracked item's state carries a small structured *transition marker*** beside
   its prose — a **stable item-slug + new status** (+ a one-line note), modeled on CLAUDE.md's
   `<!-- rule:slug -->` anchors (stable identity, survives renumbering, machine-readable). Writing an As-built
   now *also* means dropping "this moved item X → built". Near-zero added effort over what an append already is.
3. **The current-state block is a PROJECTION at the top** — the fold of those markers (latest transition per
   item) — inside a **`<!-- generated:start … -->` block** (the repo *already* uses this pattern: the
   search-quality register + the skills carry "do not edit between markers; run `node scripts/docs/…`"
   sections — current-state regenerated from a source). The tempdoc's own marked-up history *is* the source.
4. **A generate-or-check step is the fork-prevention gate.** The top block must equal the fold of the body's
   markers; regenerate on edit, or a check fails on drift. **Drift becomes impossible by construction** — which
   removes the README's objection: the doc is not "kept current" by hand; its current-view is *derived* from the
   history it already is.

Resulting shape: `frontmatter → [generated current-state projection] → [append-only history + transition
markers]`. The bottom is still the honest log; the top is a materialized view of it; the two cannot contradict.

## Why this is the correct shape (conform, don't fork)
- **A direct instance of the seam the codebase already names** — canonical-authority-and-projection (553
  SearchTrace / 559 evidence / 622 telemetry / 623 release / 640 perf-family) and **625's** projection-vs-fork
  principle ("an asserted state must trace to its source, not a hand-maintained fork that drifts"). A tempdoc's
  current-state is just another *asserted state*; it must trace to the log, not fork from it. **625 is the home
  of that generalization; this is its document-process instance** (the principle applied to the document doing
  the tracking, not only to the measurements it tracks).
- It mirrors **635's own verification-binding** one level up: a source of truth + a view that is *re-derived*
  rather than separately asserted, so it cannot silently lie.

## Scope / trigger (recognize the principle; do NOT build the machinery yet)
- **Long-term ideal (recorded here):** the event-sourced tempdoc with a generated, gate-checked current-state
  projection — fully intra-tempdoc, drift-free, findable.
- **Build trigger (rule-of-three / 625's "build only when the fork bites again"):** the drift pain recurring
  across several large *active* tempdocs (635 / 623 / 640 are the candidate cohort). The marker-grammar + the
  fold/generator + the drift-gate are real infra, justified only then.
- **Interim form, no tooling:** the **manual fold** — "the latest As-built is current truth, and each As-built
  restates the running backlog (done / deferred-ours / handed-off)." 635 half-does this ("the As-built at the
  foot is current truth"); the convention just makes the fold explicit and item-keyed, so a human computes the
  same projection a generator later would. Separating *recognizing the principle* from *building the structure*
  is deliberate (the same discipline 625 embodies).

## Next step (not done here)
None until the trigger fires. If it does: design the marker grammar (item-slug + status enum), the fold
generator (a `scripts/docs/…` step writing the `generated:start` block), and the drift gate (regenerate-and-
diff, or a Stop-hook nudge mirroring `maintain-doc-hint`); decide whether it lives beside the docs-regen
tooling or as a tempdoc-scoped check. Until then, record-only.
