---
title: "Liveness reconciliation: revive-or-relocate the dead operation-button block (export-diagnostics / clear-failed-jobs / index-gc never upgrade on any surface), fix the component-vocabulary regen trigger, adjudicate the 683 census suspicion lists, and execute the withheld teardown — one loop: witness, adjudicate, reconcile, re-census clean."
type: tempdocs
status: "open — scoped, not started (follow-up A of tempdoc 683; presentation-authority closure discipline applies to the button work)"
created: 2026-07-07
author: agent session (scoped from the 683 one-shot liveness census + its review-cycle escalation; all findings below are reproducible from repo-visible evidence cited in tempdoc 683)
category: frontend / liveness / teardown
related:
  - 683-wire-contract-and-verification-hardening   # parent — §Census carries the evidence, method, and caveats this doc consumes
  - 638-dead-code-identification-sweep             # named the dead-but-reachable class; the census is its runtime-witness counterpart
---

# 687 — Liveness reconciliation (census round 2)

## Why

Tempdoc 683's one-shot liveness census (method + caveats in its §Census) left three
kinds of unreconciled disagreement between the tree and the live app:

1. **A user-facing dead block.** The `jf-operation` buttons for
   `core.export-diagnostics` (HealthSurface + HelpSurface; and its template siblings
   `core.clear-failed-jobs`, `core.index-gc` in the same HealthSurface block) are
   connected in the surface shadow tree but **never upgrade** — no shadowRoot, zero
   width — while `customElements.get('jf-operation')` is defined globally. A
   pre-branch UI screenshot corroborates the block absent for real users (Quick
   Actions renders only Reindex/Force Rebuild). Suspected mechanism: a scoped
   custom-element-registry upgrade gap in the surface render path. Consequence:
   Export Diagnostics (and the drift-telemetry chain 683 completed up to this button)
   is unreachable from the UI.
2. **A stale generated register.** `component-vocabulary.generated.ts` is missing
   three components that mount live (`jf-security-surface`,
   `jf-context-inspector-pane`, `jf-recents-menu`) — its regen trigger does not fire
   on whatever path added them.
3. **Unadjudicated suspicion lists.** 161/202 routes and ~77/125 components were
   never witnessed under the census journeys; most collapse into knowably-undriven
   families, leaving an 11-route residue and a component tail (form-control
   primitives, demo/editor surfaces) listed in 683 §Census. 683 deliberately deleted
   nothing; the adjudication and any resulting teardown belong here.

## Work items

- [ ] **Diagnose the upgrade gap** (probe the render path of the HealthSurface ops
      block; determine why connected `jf-*` children don't upgrade; check whether the
      same mechanism explains other never-witnessed components). Fix at the
      mechanism, not per-button.
- [ ] **Revive or relocate the three operations** — either the block renders and
      upgrades, or the actions move to the declaration-driven surface path; a dead
      template block does not stay in the tree.
- [ ] **Click-to-zip E2E** (deferred from 683): drive the real Export Diagnostics
      button; assert the produced zip contains `frontend/fe-telemetry.json` with a
      seeded wire-drift ring entry.
- [ ] **Fix the vocabulary regen trigger** so the three missing components appear and
      new components can't skip the register.
- [ ] **Adjudicate the census residue** (11 routes + component tail, lists in 683
      §Census): each item ends as (a) journey-covered in a census re-run, (b)
      deliberately dormant with a recorded reason, or (c) torn down.
- [ ] **Census re-run clean**: repeat the 683 census method; the suspicion residue is
      empty or fully recorded-dormant.

## Acceptance

The button work is presentation-authority: closure requires the independent, measured
(axe/contrast, live-verified) UX audit discipline in addition to the E2E above. The
doc closes only when the census re-run comes back reconciled — no
witnessed-vs-declared disagreement without a recorded reason.

## Out of scope

Wire-contract/PreciseWire follow-ups (tempdoc 688); dev-loop tooling (684); any
census automation as a standing harness (one-shot re-run only — a standing census
must earn itself separately per 683's evidence-before-apparatus rule).
