---
title: "corpus rebuild v2: camouflaged host metadata + re-certification — close the title-class leak in the 707 strata and re-validate the levers it contaminated"
type: tempdocs
status: "chartered (2026-07-22). HERO-BLOCKING per the title-consumption finding (see §A); executes founder decision 4 (766 §G — rebuild-not-retire, instrument-first; the instrument landed as the gold-selectivity gate, 776 §I / #291)."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: eval-content / corpus-certification
related:
  - 767-camouflaged-injection-corpus-lane   # the v1 cohort this rebuilds; certification runbook + policy live there
  - 776-benchmark-v2                        # §I gold-selectivity instrument = this lane's gate
  - 774-passage-first-retrieval-program     # §J.7 discovered the title leak (fifth instance)
  - 766-eval-content-rebuild-program        # §G decision 4 is this lane's authority
---

## §A. Problem

The 707 English strata (en-legal-clerc, en-email-enron-raw; 1k/10k × verbose/short-natural)
carry a **field-presence leak**: `corpus_inject.assemble()` writes `title: ""` onto every
native host while injected gold docs carry populated titles (774 §J.7, fifth leak instance).
This is not latent: the production lexical leg searches the title field with a **3.0× boost**
(`TextQueryOps.TITLE_BOOST`, 306-B1 DisMax multi-field), so any query token appearing in a
gold title receives a boosted match **no distractor can receive by construction**. The v1
certification (767 §R) remains internally self-consistent — thresholds were derived from the
same measurements — but any *claim-bearing* run on these corpora (the hero campaign above
all) embeds the artifact. The gold-selectivity gate (776 §I) now detects this class
(title flagged at separability 1.0 vs in-corpus null 0.0); it does not fix it.

## §B. Scope

1. **Generator change:** hosts get plausible, non-answer-bearing titles at assembly (and the
   same treatment for every other stored field the engine consumes asymmetrically — audit
   `author` and the entity fields against `fields.v1.json` roles + `TextQueryOps` consumption
   before deciding each field's camouflage strategy). Gold titles must be indistinguishable
   in *shape and content class* from host titles — presence parity alone is necessary, not
   sufficient (the next leak class is content-level: answer-bearing gold titles among neutral
   host titles; extend the instrument per §D if needed to certify this).
2. **Re-materialize + re-certify** the full 8-cell English cohort under the ACTIVE policy
   plus the gold-selectivity gate; re-derive thresholds from fresh measurements per the
   767 §R rule (no threshold reuse across a corpus change — that would be the p-hacking
   767 §R.4-4 warns against). Invocation records now write automatically (#294).
3. **De-miracl rebuild** (decision 4's second half): same generator fixes; secondary stratum,
   not claim-bearing, so it re-certifies under its existing non-policy status.
4. **Title-boost re-validation (ride-along measurement):** with leak-free corpora, measure
   whether `TITLE_BOOST=3.0` earns its keep on injected strata AND on the real-benchmark
   catalog (legal-clerc-200, enron-qa) — the constant cites an industry prior, not local
   evidence. Output: keep / retune / demote to evidence-backed value, recorded in the
   search-quality register.

## §C. Acceptance

- Gold-selectivity gate green on **every field × every cell** of the rebuilt cohort.
- Closed-book 0.000 on all cells; full scientific gate set green; commitments + recipes
  re-pinned; provenance sidecars present.
- Retrieval deltas vs the v1 cohort are REPORTED with the leak direction named (v1 numbers
  were inflated for any title-matching query; expect honest decreases — do not "fix" them).
- A register note under each affected finding (F-040 floor numbers, 767 §R bands) marking
  which v1 numbers the rebuild supersedes.
- The hero campaign's corpus prerequisite (782) is satisfied.

## §D. Constraints & notes

- Committed v1 corpora/commitments are dated history — the v2 cohort gets NEW commitment
  dirs and policy cells; do not mutate v1 bytes (digest-bound).
- D-005: camouflage reacts to content class, never to corpus identity.
- If content-level title selectivity (not just presence) turns out to matter, the per-field
  instrument gains an n-gram-overlap mode — that extension belongs to this lane, not a later
  sweep (it is this lane's gate; retire-with-a-sweep applies to the v1 policy cells too).
