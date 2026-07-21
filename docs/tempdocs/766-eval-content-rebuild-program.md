---
title: "eval-content rebuild program: camouflaged real-document injection + attributable-by-construction harness — numbers that hold up to a clone-and-inspect skeptic; umbrella design + lane charters 767-770"
type: tempdocs
status: "chartered (2026-07-21). Design settled from 762 §X theorize + research; lanes 767/768/769/770 chartered, founder-run; program closes with the hero campaign's pre-registration."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed 2026-07-21 ("rebuild the test content on the real-document mode, and get numbers that hold up")
category: eval-design / agent-utility / program-umbrella
related:
  - 762-agent-utility-analysis-program   # predecessor: analysis program (complete) — priors, targets §T, lever table §L, theorize §X, research §X.6
  - 767-camouflaged-injection-corpus-lane
  - 768-harness-attribution-lane
  - 769-bridge-entity-retrieval-lane
  - 770-agent-tool-surface-economy-lane
  - 707-pillar1-inband-utility-corpus    # the pipeline being extended, not replaced
  - 741-eval-corpora-are-derived-artifacts
---

> Umbrella. Successor program to 762 (analysis → this rebuild). Read 762 §T
> (targets), §L (levers), §X (theorization + research answers) first — this
> doc does not restate them. Lanes are founder-run; each lane tempdoc is a
> self-contained charter. Program deliverable: a certified, credible
> benchmark substrate + harness, closing with the sonnet-class hero
> campaign's pre-registration (a founder-gated event, not a lane).

# 766 — eval-content rebuild program

## §A. Problem, in one paragraph

The v5-accepted benchmark's plumbing is proven (paired ITT, identity gates,
cost receipts, reject-until-clean promotion — three campaigns rejected, one
accepted clean) but its *content* cannot produce numbers that survive a
skeptic: one question schema; injected gold payloads that are domain-alien
inside their real host documents (greppable synthetic entities + `_FILLER`
prose — the substrate is real, the payload is not); a corpus-difficulty
regime that turned out to be vocabulary overlap between the generator and
the distractor domain; a naming leak (designer *N* ↔ value 000*N*)
neutralized only by the scorer's full-string match; a headline
(completion-rescue) resting on one arbitrary wall-clock knob; and an
instrument in which the engine explains ~13% of failure variance while the
model explains ~77%. Evidence: 762 §X.1, lanes 763/764/765 §Results,
register F-039.

## §B. Governing design decisions

- **D1 — Extend the 707 pipeline; do not replace it.** `corpus_inject.py`
  (real-host interleave + determinism proof), `corpus_certify.py`
  (structural + SCIENTIFIC_GATES incl. closed_book), the recipe/commitment
  scheme, and the 709 fetch cache are all reused. The rebuild is a *payload
  generator* change plus *new certification gates*.
- **D2 — Camouflage is deterministic and host-derived.** Injected entities
  are minted type- and length-matched against an entity bank harvested from
  the host corpus itself (Faithfulness-QA recipe: NER type + length band
  0.3–3.0×, collision-checked against real entities); fact sentences come
  from per-domain register templates. No LLM in the build path — preserves
  `corpus_certify`'s cross-interpreter determinism stance. The mechanism is
  the lane's to refine; the *requirement* is D3's certified
  indistinguishability, not any particular minting algorithm.
- **D3 — Camouflage is certified, not assumed.** New gates in
  `corpus_certify` (same seams as existing checks): **distractor-flood
  index** (grep-hit profile of question vocabulary against the distractor
  pile — the 764 mechanism-probe measurement, made a standing gate),
  **injected-entity indistinguishability** (grep-hit profile of minted
  entities statistically indistinguishable from native same-type entities),
  **naming/format-leak check** (no component of the gold derivable from any
  entity name or from a uniform gold format), **gold dispersion**, and a
  **difficulty band** per schema (existing `retrieval_calibration` extended).
  The existing `closed_book` gate (threshold 0.15) is the un-guessability
  control and MUST also run at the hero campaign's model tier. A corpus that
  fails certification never reaches a paid run.
- **D4 — Multi-schema question set, schema-stratified reporting.** v1
  schemas: single-fact lookup, 2-hop bridge (kept), multi-doc aggregation.
  Temporal/negation are candidates, not v1. One schema is never again the
  whole benchmark.
- **D5 — Golds stay machine-checkable, stop being pattern-greppable.**
  Domain-plausible, format-diverse, exact-matchable after normalization;
  value minting decoupled from entity uid counters (kills
  `corpus_generate.py:320-323`).
- **D6 — Attribution at capture time.** `evidence_ids` into
  `Sample.metadata`; `ordered_doc_ids`/`scores`/`gold_rank` into
  `tool_result_digests` (ids+ranks only — outside the payload-redaction
  rationale; exact change points in 762 §X.6.4). Every future campaign
  self-attributes; the 763 forensic-replay program is never needed again.
- **D7 — Parameter robustness in the campaign design.** USD-binding
  per-cell budgets (100% cost receipts, 757); ≥2 model tiers (haiku floor +
  sonnet hero); a second budget point on a subsample for the
  completion-rescue robustness figure; n from 764's power tables (60 at
  Δ≥0.25 target; 120 only if certifying Δ≈0.15); ITT + per-protocol +
  completion always reported as a triple (762 §T.4).
- **D8 — Distribution posture.** Fetch-then-inject deterministic recipes for
  ALL strata; no modified real document is committed to the repo. Build
  invariant: every injected document carries a machine-visible
  "synthetically altered" header stamp. Enron cells surfaced in published
  examples pass a PII-scrub gate. Third stratum (US gov docs, 17 USC §105)
  and the FRAMES anchor (762 §X.6.5) are optional post-v1 scope.

## §C. Lanes (founder-run) and sequencing

| Lane | Tempdoc | Scope | Depends on |
|---|---|---|---|
| Corpus & generator | 767 | D2 + D3 + D4 + D5 + D8: camouflaged payload generator, schema library, certification gates, regenerated strata | — |
| Harness & attribution | 768 | D6 + D7's harness half: rank-of-gold capture, USD budgets, schema-stratified records, claim-policy v3 draft | — (parallel to 767) |
| Engine: bridge-entity retrieval | 769 | Register F-039 fix; acceptance = the 17 B2 replay cells reach agent-visible top-k, register baselines unregressed | — (independent) |
| Tool-surface economy | 770 | ~~Snippet-economy + fetch(doc_id) + result salience~~ → **[SUPERSEDED 2026-07-21 — tempdoc 770 landed (PR #268).** `fetch(doc_id)` is **withdrawn**, not deferred: `hit.id` IS the filesystem path (identical in 14,617/14,617 measured hits), so `fetch(id)` reduces to `fetch(path)` — a tool returning a file the agent can already `Read`. The salience/passage-span lever is also withdrawn: the CLI delivers `structuredContent` only, so the text tier this lever reshaped is never sent to the model (0 of 1078 payloads carried `TRUNCATION_REMEDY`). What 770 shipped instead: ~16–31% payload reduction, truthful tool descriptions, and a characterized truncation cliff. Read 770 §0 before relying on this row.]** | — (independent); **LANDED 2026-07-21 (PR #268)** |

**Hero campaign** (not a lane): after 767+768 certify — ideally after
769+770 land so the campaign measures the improved product — pre-register
under a ratified claim-policy v3 (new strata/schemas), sonnet-class,
USD-capped. Cost re-estimated at pre-registration from 765's projections
(~$90–270 depending on scope). Founder gates the spend, per program
constraint (762 §C.1 stands: no paid campaign runs until then).

## §D. Orphans (deleted or superseded by this program — owned here, not by a later sweep)

1. `_FILLER` alien filler sentences + syllable-pair entity minting as the
   *payload* path in `corpus_generate.py` — replaced by the D2 generator in
   lane 767; superseded code paths deleted in that lane's PR.
2. Counter-coupled value minting (`corpus_generate.py:320-323`) — deleted in
   767 (D5).
3. Claim policy `agent-utility-public-v2`'s `required_strata_exact` matrix —
   superseded when policy v3 is ratified (768 drafts, founder ratifies);
   until then v2 remains ACTIVE and un-edited.
4. The v5 strata (`en-legal-clerc`/`en-email-enron-raw` *-verbose 1k/10k as
   claim-bearing corpora) — retired to history when the 767 strata certify;
   register Dataset Catalog rows annotated at that point. The v5 evidence
   dir and accepted verdict remain untouched history.
5. 762 §X.1's items 1/5 mis-statements — corrected in place (this PR), per
   code-wins.

## §E. Principles and reach

- **Attributable-by-construction evaluation** (762 §X.4, now instantiated by
  D6): every failure mechanically attributable to engine/model/eval from
  captured fields alone. Earns its keep when the next campaign's failure
  attribution is a query instead of a two-phase forensic program (763 cost a
  full replay build-out). Retire if the eval harness is ever replaced
  wholesale.
- **Camouflage is certified, not assumed** (D3): synthetic content embedded
  in real substrate must pass mechanical indistinguishability gates before
  money is spent on it. Candidate scope beyond this program: any future
  fixture that plants sentinel content in real data (e.g. leak canaries).
  Earns its keep on the first cert failure that catches an alien payload
  pre-spend; retire if corpora ever become fully real (no injection).
- **Conforms to existing seams** (not new structure): certification gates →
  `corpus_certify` SCIENTIFIC_GATES; capture fields → `tool_result_digests`;
  corpora → 741 derived-artifacts + 709 fetch cache; engine findings →
  search-quality register (F-039); budget semantics → 757.
