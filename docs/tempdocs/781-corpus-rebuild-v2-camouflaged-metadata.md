---
title: "corpus rebuild v2: camouflaged host metadata + re-certification — close the title-class leak in the 707 strata and re-validate the levers it contaminated"
type: tempdocs
status: "OFFLINE HALF DONE (2026-07-22, §E) — v2 8-cell English cohort re-materialized under the host-title generator (#297) and fully structurally-certified; field_selectivity PASSES on every field of every cell (title separability 0.0). Measured/scientific gates (closed-book + backend) + threshold re-derivation remain for the orchestrator's supervised window (§E.4). HERO-BLOCKING per §A; executes founder decision 4 (766 §G — rebuild-not-retire, instrument-first; the instrument landed as the gold-selectivity gate, 776 §I / #291)."
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

## §E. Materialization + offline certification log (2026-07-22)

OFFLINE half of §B.2 executed end-to-end: the full 8-cell English cohort was re-materialized
under the v2 host-title generator (PR #297 `_synthesize_host_title`) and passed the complete
**structural (offline, zero-spend, no-backend)** certification, including the new
`field_selectivity` gate on **every field of every cell**. No backend was started and no paid
API call was made. The measured/scientific gates remain for the orchestrator's supervised window
(§E.4).

### E.1 What was run

1. **Host pools fetched** via cache-backed `jseval corpus-fetch-clerc` / `corpus-fetch-enron-raw`
   (seed 707) — both **warm-cache hits** (no network fetch). The fetched `corpus.jsonl` byte-shas
   match each recipe's `real_source_sha256` **exactly**:
   - legal `989977109653…999ba4` ✓ ; enron `ac96743aafa3…4331c6b` ✓
2. **8 cells materialized** with `corpus-inject-real` using the v1 recipe params (`seed 707`,
   `style interleave`, `host_min_words 60`, `n_distractors` 900@1k / 9900@10k) and the SAME
   committed `fabricated-docs/queries/meta` gold sources (reconstructed per 767 §R.4 into a
   throwaway gold-source dir; v1 707-corpora bytes untouched). Output → **`781-corpora/<member>/`**
   commitments/recipes; materialized datasets → gitignored `datasets/mixed/<name>/`.
3. **Invocation records confirmed** (`invocations.v1.jsonl`, #294/§S) present next to all 8 outputs
   plus both host-pool dirs.
4. **Structural certification** (`corpus-certify-member`, NO `--scientific-evidence`) → both members
   `structurally-certified`.

### E.2 Per-cell table (offline)

`fs.title` = title-field null-calibrated separability (this rebuild's whole point); all other offline
gates: `indist` = id-shape + n-gram indistinguishability, `desc` = descriptor_collision,
`immut` = immutable_commitment, `regen` = cross-process_regeneration. `inv` = invocation record.

| cell | materialized | corpus_signature | inv | field_selectivity (title sep / max sep) | indist | desc | immut | regen | cell passed |
|---|---|---|---|---|---|---|---|---|---|
| en-legal-clerc-1k-verbose | y | `6df70703…f01fae` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-legal-clerc-1k-short-natural | y | `6df70703…f01fae` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-legal-clerc-10k-verbose | y | `5c4682ea…8e2d51` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-legal-clerc-10k-short-natural | y | `5c4682ea…8e2d51` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-email-enron-raw-1k-verbose | y | `3391fc97…18cff7` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-email-enron-raw-1k-short-natural | y | `3391fc97…18cff7` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-email-enron-raw-10k-verbose | y | `6a80af3b…8399cb` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |
| en-email-enron-raw-10k-short-natural | y | `6a80af3b…8399cb` | y | PASS (0.0 / 0.0) | PASS | PASS | PASS | PASS | ✓ |

For all 8 cells the `title` field now populates on **both** gold and native at rate 1.0
(`gold_population_rate = native_population_rate = 1.0`, separability `0.0`, `n_fields_compared = 2`
= text + title), i.e. the 774 §J.7 title field-presence leak is closed — `max_field_separability`
(0.0) ≤ `native_base_rate` (0.0) on every cell, the exact gate condition
(`corpus_certify.py:889-890`). (`corpus_signature` is identical across the two query variants of a
size because it hashes `corpus.jsonl + qrels` only, which are variant-invariant; the variants are
distinguished by `query_gold_sha256` — legal 1k v/sn `2797469a…` / `bccae852…`, legal 10k same pair;
enron 1k v/sn `1138c58c…` / `5c819a2b…`, enron 10k same pair; n=50 each.)

### E.3 Finding: enron v1 was already title-clean

`assembled_digest` (hash of assembled docs **and** queries, `corpus_inject.py:464`) differs from the
committed v1 recipe for all four **legal** cells (v1 wrote `title:""` on natives) but is
**byte-identical** to v1 for all four **enron** cells. So the live title leak was carried only by the
legal stratum; the enron v1 artifacts were already produced by a title-synthesizing generator.
Immaterial to the rebuild (both members now certify identically), recorded for accuracy — the §A
framing that *both* English strata carried the live leak holds for legal, is already-fixed for enron.

### E.4 Certification phases REMAINING for the orchestrator (from `767-certification-runbook.md`)

The offline structural half is done. These need a supervised backend window and/or paid calls and
were deliberately NOT run here (and per §D, thresholds are NOT re-derived and the policy JSON is NOT
edited until the measured runs exist):

- **Threshold re-derivation + policy re-pin (runbook B1 / Phase A4):** the v2 signatures above differ
  from every v1 policy cell, so `_policy_threshold` will fail-closed on the first scientific gate
  until each cell's `corpus_signature` / `query_gold_sha256` / `query_count` is re-pinned AND the
  `ndcg_band` / `union_recall` / `leak_floor` thresholds are re-registered from fresh n=50
  measurements (767 §R.4-4 no-reuse rule; p-hacking caution). Do this BEFORE Phase D/E.
- **Phase B — `closed_book`** (PAID, no stack; est. ~$2.50–$7.50; must return 0.000 per §C).
- **Phase C — backend gates** (GPU + PAID): C1 `jseval run --modes lexical,vector,splade,hybrid
  --embedding --splade` (feeds `union_recall` + `leak_floor`) and C2 `corpus-fidelity` (paid shortcut
  probe; feeds `retrieval_calibration`). B4's all-three-leg-modes requirement applies.
- **Phase D — build the 16 evidence artifacts per member** (offline, but consumes B+C outputs).
- **Phase E — final scientific certification** (`corpus-certify-member` with the 16
  `--scientific-evidence` entries; offline, all-or-nothing) → target `fully-certified`.
- **Phase F — publication pointer** (founder decision; only after Phase E green).
- **Ride-along (§B.4) title-boost re-validation** and the **de-miracl rebuild (§B.3)** are separate
  §B items, not part of this offline materialization.

### E.5 Constraints honored

- No v1 `707-corpora` bytes changed (`git status` on `scripts/jseval/707-corpora/` clean).
- No backend started; no paid API call; no threshold re-derivation; certification policy JSON
  untouched. Committed: `781-corpora/` recipes + commitments + fabricated gold-source snapshots +
  per-member `member.v1.json` + `structural-certification.v1.json`. Multi-GB materialized datasets and
  the invocation-record sidecars live under gitignored `datasets/` — the exact committed-vs-ignored
  split v1 used.
