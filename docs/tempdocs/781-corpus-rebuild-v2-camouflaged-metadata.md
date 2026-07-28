---
title: "corpus rebuild v2: camouflaged host metadata + re-certification — close the title-class leak in the 707 strata and re-validate the levers it contaminated"
type: tempdocs
status: "§B.2 DONE — v2 cohort FULLY CERTIFIED (2026-07-27, §F). Both English members `fully-certified`, 16/16 scientific gates each (32/32); closed-book 0.000 on all 8 cells; policy re-pinned to the v2 identities with thresholds re-derived per cell from fresh n=50 measurements (707:760 rule + 767 §Q.3 override) and a provenance sidecar. field_selectivity title separability 0.0 on every cell (§E). KEY FINDING (§F.5): closing the title leak moved retrieval by less than the enron control band (enron corpus is byte-identical v1→v2, spread ±0.03) — the rebuild's value is validity, not a number. REMAINING: Phase F publication pointer (founder), §B.3 de-miracl rebuild, §B.4 title-boost re-validation. Executes founder decision 4 (766 §G); the instrument landed as the gold-selectivity gate (776 §I / #291)."
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

### §E.5 Re-materialization + reproducibility proof (2026-07-22, post-#301)

**Why this happened.** #301's materialized payloads (multi-GB, gitignored) lived inside the
implementing worker's temporary worktree and were lost when that worktree was torn down; only
the committed artifacts (recipes, commitments, structural certifications) survived. Operational
lesson for campaign work: **gitignored campaign artifacts do not survive worker-worktree
teardown** — materialize into the main checkout's `tmp/` (or a lane worktree kept alive for the
campaign's duration), never into a disposable agent worktree.

**The rebuild doubled as the first end-to-end reproducibility test of the recipe +
invocation-record system (#294/#297), and it passed cleanly:**

- All 8 cells rebuilt from committed artifacts alone. **8/8 `corpus_signature` MATCH**,
  8/8 `assembled_digest` MATCH, 8/8 `query_gold_sha256` MATCH vs the values committed in #301.
- The throwaway commitment dirs (`recipe.json`, `commitment.v1.json`, all `fabricated-*`) came
  out **byte-identical** to the committed `781-corpora/<member>/<cell>/` files.
- `immutable_commitment` passed with freshly-built datasets checked against the **committed**
  commitment dirs — i.e. cross-validating, not self-consistent.
- Offline gate set 72/72 green (9 gates × 8 cells); `field_selectivity` title separability
  **0.0** with gold and native population rates both 1.0 on every cell.
- Host pools were warm-cache hits with recipe-matching `real_source_sha256`; total wall-clock
  12m35s.

**Persistent location** (survives worktree churn): `tmp/781-v2-datasets/mixed/` (8 cells + 2 host
pools, `invocations.v1.jsonl` in all 10), with `tmp/781-v2-datasets/_rebuild-evidence/` carrying
the fresh certifications, the repro result, and the exact scripts used. `datasets/mixed/` was
never written (v1-era cells other worktrees depend on are untouched).

The measured half named in §E.4 (threshold re-derivation, closed-book, Phase C backend gates,
evidence artifacts, Phase E/F) remains outstanding and is the hero path's remaining prerequisite.

## §F. Measured-half campaign log (2026-07-27) — both members `fully-certified` on the v2 cohort

The §E.4 measured half is complete. **Result: `en-legal-clerc` and `en-email-enron-raw` are each
`fully-certified` — 16/16 scientific gates per member, 32/32 total**, against thresholds derived from
the v2 measurements under the 707:760 rule. Structural + measured certification only; per 767 §R.7 it
does **not** authorize the paid confirmatory campaign or a publication pointer (Phase F, founder-gated).

### F.1 Phases and where each ran

| Phase | What | Where |
|---|---|---|
| A | v2 materialization + structural certification | §E / §E.5, `tmp/781-v2-datasets/mixed/` (persistent, survives worktree teardown) |
| B | `closed_book` (PAID) | banked into each cell's `metadata.json` as `closed_book_certification` |
| C1 | `jseval run --modes lexical,vector,splade,hybrid --embedding --splade` (GPU) | banked run dirs `tmp/781-certification/c1-<cell>/` (manifest + `projections/staged_recall_accounting.json`, all `status: ok`) |
| C2 | `corpus-fidelity` shortcut probe (PAID, GPU) | banked `tmp/781-certification/fidelity/<cell>.json` (top-level `retrieval_ndcg`, `comparable: true` on all 8) |
| **A4 / B1 re-pin** | policy identity re-pin + threshold re-derivation | **this session** — `707-corpus-certification-policy.v1.json` + provenance sidecar |
| **D** | 16 evidence artifacts per member (32 total) | **this session**, free/offline |
| **E** | final scientific certification | **this session**, free/offline → `fully-certified` x2 |
| F | publication pointer | NOT run (founder decision) |

**Spend.** Phase B ~$3-8 (8 x 50 haiku closed-book calls, `--max-budget-usd 0.10` per call); Phase C2
inside its $20 ceiling (8 x 50 haiku shortcut probes, `--max-budget-usd 0.05` per call). Phases A4, D
and E cost **$0** — no backend, no GPU, no paid call. Consistent with the 767 §R.6 measured envelope
(~$2-9 for the same 800-call shape).

### F.2 Bookkeeping order (767 §R): identity re-pinned FIRST

`required_cells[].corpus_signature` / `query_gold_sha256` / `query_count` were re-pinned from the
committed `scripts/jseval/781-corpora/<member>/structural-certification.v1.json` **before** any
threshold was touched, and the strict cell shape was preserved exactly
(`{member, dataset, corpus_signature, query_gold_sha256, query_count, thresholds}` — anything else
raises "malformed" at `corpus_certify.py:308-312`). `member` carries the SHORT id (`en-legal-clerc`,
`en-email-enron-raw`) per 767 §R.4-3; verified live — `_active_scientific_policy_cells` matches on it at
`corpus_certify.py:303` and `_complete_certification_document` re-derives it from the snapshot at
`corpus_certify.py:823-825`. Provenance stays in the **sidecar**
`707-corpus-certification-policy.provenance.v1.json` per 767 §R.3 (the policy schema forbids extra
fields), now carrying a `supersedes` block naming the v1 pins it replaces and a `measurement_sources`
block naming the exact key path each measured value was read from.

Only four of the eight identity pins actually moved: the **legal** cells changed
`corpus_signature` (`da8c6f1a…`/`29f08bda…` → `6df70703…`/`5c4682ea…`), while the **enron** cells kept
their v1 signatures because that stratum was already title-clean (§E.3). Their **thresholds were still
re-derived from fresh v2 measurements** — no reuse across the certification boundary (§D / 767 §R.4-4).

### F.3 Per-cell measured values (n=50), with the exact source key for each

`nDCG` = fidelity `retrieval_ndcg` (top-level, headline mode `hybrid`, `--embedding`) — the same choice
767 §R.1 made, confirmed against that section rather than assumed. `sLeak` = fidelity
`shortcut_leak_rate`. `union` = C1 `projections/staged_recall_accounting.json` →
`aggregate.leg_union_recall`; `leak` = same file → `aggregate.leak_rate` (the two keys
`_derive_scientific_verdict` reads at `corpus_certify.py:515-516`). `cb` = `metadata.json` →
`closed_book_certification.closed_book_accuracy` (read at `corpus_certify.py:478-485`). C1 `nDCG` is the
projection's `aggregate.final_ndcg`, shown only as the independent cross-check 767 §R.1 used.

| cell | nDCG (fidelity) | C1 nDCG | union | leak | sLeak | cb |
|---|---|---|---|---|---|---|
| en-legal-clerc-1k-verbose | 0.3103 | 0.3143 | 0.50 | 0.08 | 0.00 | 0.000 |
| en-legal-clerc-1k-short-natural | 0.2419 | 0.2305 | 0.54 | 0.16 | 0.00 | 0.000 |
| en-legal-clerc-10k-verbose | 0.0996 | 0.1262 | 0.16 | 0.02 | 0.00 | 0.000 |
| en-legal-clerc-10k-short-natural | 0.1105 | 0.1015 | 0.18 | 0.02 | 0.00 | 0.000 |
| en-email-enron-raw-1k-verbose | 0.6585 | 0.6576 | 0.86 | 0.04 | 0.00 | 0.000 |
| en-email-enron-raw-1k-short-natural | 0.6122 | 0.6122 | 0.80 | 0.02 | 0.00 | 0.000 |
| en-email-enron-raw-10k-verbose | 0.4756 | 0.4613 | 0.52 | 0.04 | 0.00 | 0.000 |
| en-email-enron-raw-10k-short-natural | 0.4701 | 0.5216 | 0.50 | 0.00 | 0.00 | 0.000 |

Closed-book is **0.000 on all eight cells** (§C acceptance met): not one of the 400 questions is
answerable without retrieval.

### F.4 Derived thresholds (written to policy)

Rule applied verbatim from the committed sidecar (707:760 + the 767 §Q.3 leak-noise override):
`ndcg_band = [clamp(nDCG − 0.08, 0, 0.85), 0.85]`; `shortcut_leak_rate_max = clamp(sLeak + 0.10, 0, 1)`;
`union_recall.minimum = clamp(union − 0.10, 0, 1)`; `closed_book.maximum_accuracy = 0.15` fixed;
`leak_floor.maximum = max(leak + 0.10, 0.20)`. Values rounded to 4 decimals.

| cell | ndcg_band | sLeak_max | union_min | leak_max |
|---|---|---|---|---|
| en-legal-clerc-1k-verbose | [0.2303, 0.85] | 0.10 | 0.40 | 0.20 |
| en-legal-clerc-1k-short-natural | [0.1619, 0.85] | 0.10 | 0.44 | **0.26** |
| en-legal-clerc-10k-verbose | [0.0196, 0.85] | 0.10 | 0.06 | 0.20 |
| en-legal-clerc-10k-short-natural | [0.0305, 0.85] | 0.10 | 0.08 | 0.20 |
| en-email-enron-raw-1k-verbose | [0.5785, 0.85] | 0.10 | 0.76 | 0.20 |
| en-email-enron-raw-1k-short-natural | [0.5322, 0.85] | 0.10 | 0.70 | 0.20 |
| en-email-enron-raw-10k-verbose | [0.3956, 0.85] | 0.10 | 0.42 | 0.20 |
| en-email-enron-raw-10k-short-natural | [0.3901, 0.85] | 0.10 | 0.40 | 0.20 |

`en-legal-clerc-1k-short-natural` is the only cell whose measured `leak_rate` (0.16) exceeds the 0.20
noise floor, so it is the only cell where `leak_floor.maximum` is measurement-driven (0.26) rather than
floor-driven. Every measured value sits inside its own derived threshold by construction; this was
re-checked mechanically against the sidecar (derivation reproduces the policy exactly; measured-inside-
band asserted per cell) **before** Phase E, so Phase E's green is a confirmation, not the derivation.

**Founder-relevant, carried forward from 767 §R.1:** the two legal-10k cells still produce near-vacuous
`retrieval_calibration`/`union_recall` bands (band low 0.0196 / 0.0305; union min 0.06 / 0.08).
Mechanically correct under the measured-derived rule, but those two gates barely constrain anything for
those cells. Flagging, not deciding — unchanged by the rebuild.

### F.5 v1 → v2 retrieval deltas, and why the leak story does not explain them (§C)

§C requires the deltas to be reported with the leak direction named. The expected direction was a
**decrease** for legal (removing a gold-only boosted-field feature). What was measured:

| cell | v1 nDCG (767 §R.1) | v2 nDCG | Δ |
|---|---|---|---|
| en-legal-clerc-1k-verbose | 0.3261 | 0.3103 | −0.0158 |
| en-legal-clerc-1k-short-natural | 0.2482 | 0.2419 | −0.0063 |
| en-legal-clerc-10k-verbose | 0.0625 | 0.0996 | **+0.0371** |
| en-legal-clerc-10k-short-natural | 0.0784 | 0.1105 | **+0.0321** |
| en-email-enron-raw-1k-verbose | 0.6576 | 0.6585 | +0.0009 |
| en-email-enron-raw-1k-short-natural | 0.6122 | 0.6122 | 0.0000 |
| en-email-enron-raw-10k-verbose | 0.4864 | 0.4756 | −0.0108 |
| en-email-enron-raw-10k-short-natural | 0.4408 | 0.4701 | +0.0293 |

**The enron rows are a natural control, not a result.** Those four cells are byte-identical between v1
and v2 (identical `corpus_signature` AND `query_gold_sha256`; §E.3's `assembled_digest` match), so their
spread — 0.0000 to +0.0293 — is pure run-to-run variance plus whatever engine drift occurred between
2026-07-22 (`a851aa22`) and 2026-07-27 (`db838ab6`/`adaf7b44`). Every legal delta lies inside that
±0.03 band.

**Conclusion, stated as the evidence supports it:** closing the title-presence leak produced **no
retrieval change distinguishable from measurement noise at n=50** — an order of magnitude smaller than
`_FILLER`'s −0.164 (767 §Q.1). The two legal-10k *increases* are the reason this is not reported as "an
honest decrease": a leak-removal story predicts decreases, so a rise needs an explanation, and the
control band supplies one where the leak story cannot. This does **not** weaken the rebuild's rationale
— the title field was a structurally gold-only feature on a 3.0× boosted lexical field and had to go
regardless of its measured magnitude; it does mean the rebuild's value is *validity*, not a number.
Caveat held: the control band conflates run-to-run variance with engine drift across the two dates, so
it is an upper bound on noise, which is the conservative direction for this argument.

### F.6 Deviations from the runbook, with reasons

1. **Datasets read from `tmp/781-v2-datasets/`, not the repo-root `datasets/`** (runbook B5). Only
   `corpus-scientific-evidence-build` and `corpus-certify-member` were run here, and both take explicit
   paths (`--dataset-dir`, `--datasets-dir`); no `jseval run` was involved, so B5's cwd-vs-REPO_ROOT
   split does not bite. Absolute paths were used throughout, which also leaves the v1-era
   `datasets/mixed/` that other worktrees depend on untouched (§E.5's persistence lesson).
2. **Evidence artifacts are not committed.** The 32 Phase-D envelopes live in the worktree's gitignored
   `tmp/781-evidence/`; their bytes are base64-embedded into the certification documents
   (`_validate_scientific_evidence`), so the committed certification is self-contained — the same
   committed-vs-ignored split v1 used.
3. **Scientific results overwrite `781-corpora/<member>/structural-certification.v1.json`** (rather than
   landing in a new file) — mirroring what 767 §R committed for v1: the same filename now carries
   `status: fully-certified`, `fully_certified: true`, the four `scientific_gates: passed`, per-cell
   embedded gate evidence, and the embedded `scientific_policy` snapshot.
4. **The runbook's `tests/test_corpus_certify*.py` does not exist.** Certification coverage lives in
   `tests/test_corpus_governance.py` (+ `test_corpus_inject/schema/leak/invocation/axis/query_variant`).
   Ran those (235 passed) and then the full `scripts/jseval/tests` suite: **2527 passed, 2 skipped**.
   **No test pinned the v1 policy cell identities**, so no deliberate pin update was required.

### F.7 What is NOT done

- **Phase F (publication pointer)** — founder decision, and the paid confirmatory campaign with it.
- **§B.3 de-miracl rebuild** and **§B.4 title-boost re-validation** — separate §B items, untouched here.
  The `mixed/de-miracl-*` rows stay **LEAKY / not rebuilt** in the search-quality register.
