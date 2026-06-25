---
title: "Contamination-resistant corpus regeneration (G3): turn tempdoc 635's one-off procedural generator into an on-demand REGENERATION capability — the 2026 field's verdict that contamination-resistance is a *pipeline* property, not a *dataset* property (OKBench/AntiLeak). STUB / record-only: deferred-by-trigger (635 D.4 — build only once the static suite proves worth regenerating); the precondition (635's verification-binding clause) is now built."
type: tempdocs
status: proposed — STUB / record-only (deferred-by-trigger; precondition met, demand not yet)
created: 2026-06-24
author: agent analysis — split out of tempdoc 635 (its G3 goal) for its own tracking; 635 stays the design origin
related:
  - 635-contamination-resistant-eval-corpus      # origin — G3 in its R-4 goal set; the generator + gates + verification-binding this builds on
  - 623-reproducible-benchmark-release            # the reproducibility bar a regenerated corpus must still meet if it targets comparison
  - 625-asserted-measurement-provenance           # the identity-binding a regenerated corpus must re-establish on each regeneration
---

> **STUB / record-only.** Captures the idea + purpose + the build trigger for tempdoc 635's deferred **G3**
> goal, split here so 635's own backlog stays just G3 (this) + G6 (642). **Not being built now** — see Trigger.
> 635 remains the design origin (machinery, the suite, the verification-binding); this tempdoc is only the
> regeneration *product*.

# 641 — Contamination-resistant corpus regeneration (G3)

## The idea
635 ships a deterministic, seeded procedural generator (`corpus_generate.py`) used **once** to author the
committed 4-member suite. G3 is the step from *"a generator we ran once"* to *"a regeneration **capability**"*:
refresh or expand the contamination-free suite on demand (new entities, new scales, new type-axes), each new
corpus passing the same dual gates (closed-book + fidelity) automatically.

## The purpose / why it matters
- **The 2026 field's verdict** (635 §E): contamination-resistance is a *pipeline* property (regenerable), not a
  *dataset* property (static-and-clean). AntiLeak-Bench/OKBench frame the solution as an automated
  regenerate→validate→filter pipeline. 635 deliberately built the static suite first (feasibility before
  pipeline); G3 is the pipeline, *if/when* the static suite proves worth regenerating.
- **Durability without a treadmill.** Fabricated facts never decay (635 §C-4), so unlike post-cutoff-public
  corpora this needs no refresh treadmill — but a regeneration *capability* is the natural home for new corpus
  types, scale sweeps, and re-seeding, and the cheapest way to grow the profile.
- **It is now safe.** 635's **verification-binding clause** (the cache is a verified projection of the source;
  re-materialize on `corpus_signature` change) is G3's precondition — regeneration is exactly "re-sign and
  re-bind," and the stale-cache class it closes was a regeneration-without-rebinding failure.

## Scope boundary / trigger (why deferred)
**Build only when the static 4-member suite proves worth regenerating** (635 D.4) — a real need to refresh,
re-scale, or systematically expand it. Until then this is record-only; building a regenerate-on-demand product
for a suite that is not yet being regenerated would be structure for a case the problem does not include.

## What it would reuse (not rebuild) when triggered
`corpus_generate.py` (the seeded generator + the proven difficulty levers), the dual gates
(`corpus-certify` / `corpus-fidelity`), `suite-profile`, and the verification-binding (`_ensure_materialized`).
The new structure is only the *orchestration* (generate→certify→fidelity→profile as one regenerable pipeline)
+ a manifest of regeneration provenance — conforming to 635's governed-artifact shape, not a new mechanism.

## External-research gate (deferred with the build)
At the build trigger, run the focused 2026 pass 635 named: dynamic/regenerable-benchmark construction
(OKBench/AntiLeak lineage) + verified-dataset-provenance tooling. Not now (researching ahead of an unmet build
trigger is premature — 635 §External-research gate).
