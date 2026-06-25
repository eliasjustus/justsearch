---
title: "Bring-your-own-files self-demo eval (G6): let a USER run the clean closed-book + fidelity certification on THEIR OWN private files — a user-supplied golden/ dataset certified by the same gates. The purest self-demonstration on contamination-free private data; turns 'unshareable' into a self-serve feature. STUB / record-only: deferred (the deliverable is a user-facing HARNESS, its own scope); tempdoc 635 shaped the container + verification-binding so this is a thin addition."
type: tempdocs
status: proposed — STUB / record-only (deferred; user-facing harness, own scope)
created: 2026-06-24
author: agent analysis — split out of tempdoc 635 (its G6 goal) for its own tracking; 635 stays the design origin
related:
  - 635-contamination-resistant-eval-corpus      # origin — G6 in its R-4 goal set; the golden/ container, dual gates, and verification-binding this builds on
  - 625-asserted-measurement-provenance           # the identity-binding that makes "the ceiling on YOUR files" an honest claim
---

> **STUB / record-only.** Captures the idea + purpose + scope for tempdoc 635's deferred **G6** goal, split here
> so 635's own backlog stays just G3 (641) + G6 (this). **Not being built now.** 635 remains the design origin
> (it deliberately conformed to the `golden/` container + built the verification-binding to keep this a *thin*
> addition rather than a rewrite); this tempdoc is G6's design + build home.

# 642 — Bring-your-own-files self-demo eval (G6)

## The idea
Let a **user** point the clean-corpus certification at **their own private files**: a user-supplied `golden/`
dataset that the same gates certify — closed-book (the model can't shortcut it from memory) + fidelity (real
retrieval difficulty) — and then run the self-demo (retrieval ceiling, optional agent-utility) over it. The
deliverable is the **harness/flow**, not a dataset.

## The purpose / why it matters
- **The purest self-demonstration on the actual moat.** JustSearch's real use case is *private, local files*,
  which are contamination-free **by construction** (635 §B-3). A public benchmark throws that away; a BYO eval
  measures the exact thing no public-corpus competitor can claim — *on the user's own data*.
- **Unshareability becomes a feature, not a defect.** 635 §R-2 dropped redistributability precisely because
  self-demo needs no stranger to reproduce; G6 is the strongest expression of that — the user *is* the
  audience, so "your files, certified clean, retrieval ceiling X" is self-serve and unfakeable.
- **It is now honest.** 625 / 635's **verification-binding** is what makes "the ceiling on YOUR files" a
  truthful claim: the measured index is provably the user's certified files, not a stale or mismatched copy.

## Scope boundary / what's deferred (and why this is its own tempdoc)
- A BYO corpus is *just a `golden/` dataset the user supplies* — 635 conformed to the container exactly so this
  stays thin. But the **flow is substantial and user-facing**: file selection, on-the-fly build+certify+gate,
  and presenting the result. Unlike the rest of 635 (eval-harness plumbing, no UI — §D.4), G6 plausibly has a
  **real user-facing surface** and so needs its own UX + browser-validation scope — which is why it is split
  out rather than carried in 635.
- **Deferred** until prioritized as a product feature; this stub is the design home.

## What it would reuse (not rebuild) when built
The `golden/` container + `corpus-build` (single-source→projections), `corpus-certify` + `corpus-fidelity`
(the dual gates), the verification-binding (`_ensure_materialized` — the user's index provably == their
files), and `corpus-probe` (the inspectable witness). The genuinely-new part is the **user-facing flow**
(intake + orchestration + presentation), not the certification machinery.

## Open question for the design phase (not resolved here)
Whether G6 surfaces in-app (a real UI, browser-validated) or stays a CLI/dev harness — this decides whether
it inherits 635's "no UI → no browser" validation tier or needs the full UI-shot/browser validation discipline.
