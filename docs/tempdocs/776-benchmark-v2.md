---
title: "benchmark v2: multi-schema questions, baseline-arm characterization on the camouflaged corpora, the golden-corpora leak audit, and the register provenance sweep — everything the eval-design owner owes before the hero numbers go public"
type: tempdocs
status: "items 1 (offline phase) + 2 + 3 + 4 DONE (2026-07-22; §D audit/sweep, §E schemas, §F baseline probe — uplift does NOT compress, baseline near-floor on all hero strata). Remaining: item 1 scientific gates in the combined stack window (§E.4), scheduled with the next stack occupancy."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: eval-design / agent-utility
related:
  - 766-eval-content-rebuild-program     # D4 multi-schema debt; hero pre-registration consumer
  - 767-camouflaged-injection-corpus-lane  # the generator + certification gates this extends
  - 764-eval-validity-lane               # power tables; per-qid matrix method
---

> Charter. Four work items, one owner (eval design). Load `/search-quality`;
> the register provenance sweep is item 4's deliverable.

# 776 — benchmark v2

## §A. Work items

1. **Multi-schema questions (the 766 D4 debt).** The rebuilt corpora are
   bridge-schema only (verified: 50× `1_hop` per cell). Add single-fact
   lookup and multi-doc aggregation generators on the 767 camouflaged
   payload substrate (entity banks, carrier sentences, determinism proofs
   all reusable); per-schema difficulty bands via the reachability-anchored
   method (767 §F); per-schema `gold_kind` comparators (767 §F registry);
   schema-stratified certification cells. Temporal/negation stay candidates.
2. **Baseline-arm characterization (the unmeasured uplift denominator).**
   Nothing is known about grep/Read performance on the camouflaged corpora —
   and df=1 bridge tokens may grep BETTER than the old alien tokens, which
   would compress the hero campaign's uplift. Measure cheaply BEFORE the
   hero pre-registration: a small haiku baseline-arm probe (~10-15 cells,
   order $5) per viable stratum, giving the pre-registration an honest
   expected-delta band instead of a guess.
   **Status (2026-07-22): EXECUTED — baseline probe complete for all three
   hero-viable strata; see §F. Verdict: df=1 camouflaged tokens do NOT grep
   better and do NOT compress the hero uplift — baseline stays near-floor.**
3. **Golden-corpora leak audit.** The `_FILLER` leak (767 §Q — a third of
   measured retrieval) proves the gold-only-feature class; the same
   generator family built `golden/needle-burial-v1` and
   `golden/battlefield-{en,de}-v1`. Run the now-existing instruments
   (distractor-flood index, shape-indistinguishability, filler ON/OFF
   ablation where cheap) over the back catalog; classify each corpus
   clean/leaky with measurements.
4. **Register provenance sweep.** Every register finding measured on the
   pre-767 707-corpora (F-039 census numbers, the 707 catalog rows'
   hybrid fidelity values, Q-018's DE numbers if the DE member shares the
   filler) gets the corpus-provenance-note treatment the register already
   uses for regenerated corpora: dated annotation that the measurement was
   made on a leak-inflated substrate, numbers historical-not-reproducible,
   shipped decisions unaffected. Catalog rows for the rebuilt strata added;
   old strata rows annotated superseded-for-claims.

## §B. Optional scope (explicitly severable, founder-priced)

Third stratum (US gov docs, 17 USC §105 — 766 D8); FRAMES public anchor
(Frame D, 762 §X.6.5); tier-scaling curve as a hero-campaign deliverable.

## §C. Acceptance

- New schema cells pass ALL certification gates incl. the 767 five;
  closed-book ≈ 0 per schema; cross-interpreter determinism green.
- Baseline probe: per-stratum baseline band with n stated, folded into the
  766 pre-registration's expected-numbers table.
- Leak audit: per-corpus verdict table with instrument outputs committed;
  any leaky corpus gets a register provenance note + a retirement/rebuild
  recommendation (decision stays founder's).
- Register sweep complete before any hero number publishes (hard ordering).

## §D. Items 3+4 results (2026-07-22)

Offline audit (no backend, no paid API), reusing the 767 instruments
(`scripts/jseval/jseval/corpus_leak.py`). Artifacts:
`tmp/analysis-624/776/leak-audit/{needle-burial-v1,battlefield-en-v1,battlefield-de-v1,de-miracl}.json`
+ `run_audit.py`.

### D.1 Leak-audit verdict table

| corpus | filler (gold/native cov) | id-shape (best rule, sep vs base) | rare-token df≤5 | length sep | verdict |
|---|---|---|---|---|---|
| `golden/needle-burial-v1` | 1.0 / 1.0 (uniform, not a leak) | `trailing_int<=40` P/R 1.0, J **1.0 vs 0.29** | 2/20 (0.10) | 0.025 | **LEAKY** — id-shape enumeration |
| `golden/battlefield-en-v1` | 1.0 / 1.0 (uniform) | `trailing_int<=78` P/R 1.0, J **1.0 vs 0.20** | 3/26 (0.115) | 0.026 | **LEAKY** — id-shape enumeration |
| `golden/battlefield-de-v1` | 1.0 / 1.0 (uniform) | `trailing_int<=78` P/R 1.0, J **1.0 vs 0.20** | 1/26 (0.038) | 0.0 | **LEAKY** — id-shape enumeration (+ minor query-overlap: 0/26 zero-overlap, median Jaccard 0.072) |
| `707-corpora/de-miracl` | English `_FILLER` 240/240 gold; ~0 real DE hosts (by construction) | minted lower-alnum gold vs host int ids (likely; not measured offline) | — (mixed corpus not materialized offline) | — | **LEAKY** — `_FILLER` gold-selective; NOT rebuilt |

Key distinctions established with measured numbers:

1. **`_FILLER` is only a leak in the injection family, not the pure-synthetic
   `golden/*` corpora.** In needle/battlefield the filler sits in 100% of BOTH
   gold and distractors (both procedurally generated) → gold coverage =
   native coverage → not gold-selective. In the 707 injection corpora the gold
   is fabricated (filler-bearing) and the distractors are real hosts (no
   filler) → gold-selective, the 767 §Q mechanism.
2. **The `golden/*` leak is id-shape enumeration** (767 defect #3 class): gold
   occupies `trailing_int(id)` 1..N and distractors N+1..M with **zero
   overlap** (verified directly), so a numeric filename threshold selects the
   whole gold set at precision/recall 1.0 without reading a body. Bites the
   agent-utility grep/Read baseline (materialized `<doc_id>.txt` filenames);
   does **not** affect dense retrieval (IDs aren't searchable text).
3. **de-miracl shares the `_FILLER` paragraph — Q-018's dependency confirmed.**
   Its gold source `635-corpora/synth-multiling-de-v1` and every fabricated
   cell carry the identical *English* filler among real German hosts; the DE
   member was **not** part of the 767 English-members rebuild.

### D.2 Register provenance sweep (item 4)

Annotated in `docs/reference/search-quality-register.md` (verdicts unchanged;
provenance only):

- **New note:** "Corpus provenance note (2026-07-22, tempdoc 767 §Q + 776
  items 3-4)" — the anchor the rows/findings point to; mirrors the 664/666 tone.
- **Dataset Catalog rows:** `needle-burial-v1`, `battlefield-en-v1`,
  `battlefield-de-v1` (id-shape leak notes); `en-legal-clerc` + `en-email-enron-raw`
  (pre-rebuild hybrid marked leak-inflated; certified leak-free hybrid added —
  legal 0.33/0.25/0.06/0.08, enron 0.66/0.61/0.49/0.44, #273); `de-miracl`
  (LEAKY / pre-rebuild / not-rebuilt).
- **Findings:** F-039 (one provenance sentence — census magnitudes pre-767,
  resolution re-measured leak-free #273); Q-018 (provenance caveat + explicit
  "748 should re-verify on a defillered de-miracl rebuild"); F-027 (corpus-leak
  provenance — battlefield id-shape confounds any future real-with-tool arm,
  did not bias the A-vs-A Δ).
- Regen: `skills-sync.mjs` + `llmstxt-generate.mjs` run.

### D.3 Flagged follow-ups (need a live eval — out of offline scope)

- **de-miracl filler ON/OFF retrieval-inflation ablation** (mirror 767 §Q on
  legal-1k) to quantify how much of Q-018's collapse is leak-inflation vs. real
  German representation floor — needs a materialized mixed cell + GPU retrieval
  run. Route to **748**.
- **Full gold-vs-native `corpus_leak` run on materialized de-miracl mixed cells**
  (id-shape/ngram/rare-token vs real host distractors) — needs a host-pool
  fetch (network) + inject.
- **Retirement/rebuild decision for the three `golden/*` id-leaked corpora and
  de-miracl** stays founder's (per §C).

## §E. Item 1 implementation log (2026-07-22)

Item 1 (multi-schema questions) implemented OFFLINE on the 767 substrate: build + unit
tests + structural certification only. Zero paid API, zero GPU, zero network. The scientific
per-schema difficulty bands are the deferred combined-stack-window work (command list below).

### E.1 What landed (file:line)

- **Comparator registry** — `scripts/jseval/jseval/corpus_comparators.py` (new). `gold_kind`
  → deterministic comparator, no judge (767 §F6): `single_value` (reproduces the pre-767
  substring-exact scorer byte-for-byte), `set` (order-insensitive all-present, split on
  `SET_DELIMITER`), `count` (standalone-numeric exact — guards the `4`-in-`40` substring FP),
  `extremum` (single value exact). `score(gold_kind, gold, predicted)` defaults absent kind to
  `single_value`, so pre-776 bridge queries score identically.
- **Two new schemas** — `corpus_generate.py`. `_render_single_fact` (`corpus_generate.py:625`)
  and `_render_aggregation` (`:653`) reuse the bridge hop-1 synonym paraphrase barrier
  (`_sem_for`) and rotate existing phrasing pools (`_TAIL_PHRASINGS`, new
  `_MEMBERSHIP_PHRASINGS` `:602`) — no new authored-template fingerprint. Aggregation member
  minting + precomputed answer: `_aggregation_members` (`:492`). Opt-in wiring:
  `schema_mix` param on `generate()` (`:1007`), validated/expanded by `_schema_plan` (`:974`);
  the gold loop branches on `schema_plan` (`:1146`) — the default (`schema_mix=None`) path is
  the byte-identical original. `question_type` labels: `single_fact` / `aggregation`; bridge
  keeps `1_hop` (edge-count vocabulary, 731 §3.3 — evidence_ids = edges+1). `gold_kind` and
  `schema_mix` provenance are APPENDED only for a mix.
- **Cross-interpreter proof threaded** — `regenerate_and_diff` (`corpus_generate.py:1250`) +
  `regeneration_determinism_report` (`corpus_certify.py:1377`) pass `schema_mix` through the
  subprocess; absent for pre-776 corpora so the default proof is unchanged.
- **Materialization passthrough** — `corpus_build.build_golden`: `gold_kind` preserved into
  the agent-view `queries.json` (`:111`, guarded → pre-776 bytes/`query_gold_sha256`
  unchanged); aggregation marks EVERY member relevant in qrels (`:97`, guarded on
  `question_type == "aggregation"` → single-schema qrels byte-identical). `corpus_inject.assemble`
  already passes new query fields through untouched (verified by test).
- **Per-schema structural certification** — `corpus_certify.py`: `schema_dispersion_report`
  (`:1201`, per-`gold_kind` title-distinctness) and `schema_format_leak_report` (`:1232`,
  per-`question_type` null-calibrated n-gram selectivity scoped to that schema's gold vs the
  TRUE natives). Wired into `certify_materialized_family` as new `checks` keys
  `schema_dispersion` / `schema_format_leak` — added ONLY for a multi-schema cell
  (`is_multi_schema`, `:1192`; wired at `:144`), so a pre-776 single-schema cell's `checks` stays exactly
  `_CELL_CHECKS` and its cell shape is unchanged.
- **Demonstration recipe** — `scripts/jseval/776-demo/build_demo.py` (new): synthetic 240-doc
  legal host → generate a 40-question mix (10 bridge + 15 single_fact + 15 aggregation) →
  inject (200-doc materialized cell) → structural certify + cross-process determinism, all
  offline. Runs green: all 5 leak/collision gates + both new schema checks + assembly
  determinism pass; all three schemas + all four gold_kinds present.

### E.2 Test evidence

- `scripts/jseval/tests/test_corpus_schema.py` (new): **28 tests** — comparators (7),
  schema generation incl. in-process AND cross-interpreter determinism, evidence-count
  vocabulary, naming-leak-free-by-construction, paraphrase barrier (11), opt-in byte-stability
  incl. the committed-cell zero-drift regeneration (3), inject/build passthrough + aggregation
  qrels (2), certification schema checks + demo-is-certified (5). All green.
- Corpus suite (governance + inject + leak + schema + fetch + axis + query_variant +
  chunk_completeness + entity_harvest): **336 passed**. Full jseval suite: green except the
  two pre-existing `correction-eval-queries-missing` reds (expected-state, not mine).

### E.3 Zero digest drift on certified cells (proven — 756 §F method)

Both committed English English gold sources (`707-corpora/en-legal-clerc/1000-verbose`,
`707-corpora/en-email-enron-raw/1000-verbose`) regenerated from their own recorded
`generation_provenance` are **byte-identical** in `queries.json`, `docs.jsonl` AND `meta.json`
(test `test_committed_gold_sources_regenerate_byte_identically`, green). The default
(`schema_mix=None`) generator path executes the original code unchanged; `schema_mix`/`gold_kind`
are strictly additive and opt-in, so `corpus_signature` / `query_gold_sha256` / `assembled_digest`
/ commitment manifests on every certified cell are untouched.

### E.4 Deferred to the combined paid/GPU stack window (exact commands)

The SCIENTIFIC per-schema difficulty bands + closed-book are founder-gated and run together
(the evidence matrix is all-or-nothing, `commands/corpus.py`). For each committed multi-schema
cell `mixed/<name>` (once the founder ratifies which mix cells ship), per the 767 runbook:

1. Materialize + ingest, backend up (self-contained jseval, NOT the MCP lease — §R.4):
   `PYTHONPATH=scripts/jseval PYTHONUTF8=1 INSPECT_DISPLAY=none python -m jseval run --start-backend --clean --dataset mixed/<name> --json`
2. Per gate, build the evidence envelope (all three retrieval legs present — §R B4):
   `python -m jseval corpus-scientific-evidence-build --member <member> --dataset mixed/<name> --dataset-dir datasets/mixed/<name> --gate <closed_book|retrieval_calibration|union_recall|leak_floor> --measurement <run.json> [--run-manifest <manifest.json>] --output <evidence>.json`
3. Certify the family with the four evidence files per cell:
   `python -m jseval corpus-certify-member --member <member> --datasets-dir datasets --dataset <name>... --commitment 707-corpora/<member>/<cell>... --scientific-evidence <gate>=<evidence>.json... --output <member>-cert.json`

New scientific policy work the window MUST do first (pre-registration, before any paid run):
- **Per-schema (per gold_kind / question_type) difficulty bands**: closed-book ≈ 0 must hold
  PER SCHEMA (aggregation set/count/extremum answers are as un-guessable as the bridge value);
  retrieval-reachability floor/cap (767 §F7) measured per schema — aggregation union recall now
  scores against ALL k members (qrels already mark them). Derive thresholds by the 707:760 rule
  + §Q.3 leak-floor override from the fresh n measurements, recorded in the policy provenance
  sidecar (`707-corpus-certification-policy.provenance.v1.json`) — the policy JSON schema forbids
  extra fields (§R.3).
- **Publish-path validator update (deferred)**: `_complete_certification_document` /
  `_CELL_CHECKS` (`corpus_certify.py`) pin the EXACT single-schema `checks`/cell key set, so a
  multi-schema cell cannot yet be published as `fully-certified`. Extending them to accept the
  two `schema_*` keys is policy work for whenever a mix cell publishes; left untouched here to
  keep the existing publish path byte-stable.
- **Baseline-arm probe (776 §A.2)** should include the new schemas so the pre-registration's
  expected-delta band covers single_fact/aggregation, not just bridge.

### E.5 Deviations

- **Host for the demo is synthetic**, not a real CLERC fetch: the CLERC/Enron fetch cache is
  absent in this worktree and the phase is network-free, so `build_demo.py` builds a
  deterministic legal-flavoured host (integer ids, ≥60-word varied opinions) to exercise the
  full generate→inject→materialize→certify path offline. A real-host mix cell is a fetch-cache
  matter for the stack window, not a code gap.
- **Aggregation qrels** now mark every member (guarded, single-schema cells byte-identical) —
  necessary so the deferred `union_recall` gate measures aggregate retrieval correctly.
- **Bridge in a mix keeps `question_type: "1_hop"`** (not a new `bridge` label) — the
  commitment-bound edge-count vocabulary is preserved; the schema is distinguished by
  `gold_kind` + the two new question_type labels only.

## §F. Item 2 baseline probe (2026-07-22)

**Arm:** condition A only (file tools — Grep/Read/Bash — over the materialized
`corpus-dir`; **no MCP search backend, no dev stack**). `haiku`
(`claude-haiku-4-5`), 1 seed, `private-synthetic` / confidence-tier C. Every
record is internal / non-claim-bearing: single-arm, `comparable=False`, no
policy evaluation, no promotion. Corpora: the certified n=50 hero cells,
signature-verified byte-identical to the 707 structural commitments (A2 gate
PASS) before any spend.

### Per-stratum baseline (no-tool floor)

| stratum | n | baseline acc | read BOTH evidence docs | mean $/cell |
|---|---|---|---|---|
| en-legal-clerc-1k-verbose  | 15 | 1/15 = **0.067** | 1/15 | 0.328 |
| en-email-enron-raw-1k-verbose | 15 | 4/15 = **0.267** | 5/15 | 0.241 |
| en-email-enron-raw-10k-verbose | 10 | 1/10 = **0.100** | 0/10 | 0.209* |

\*enron-10k: 5/10 cells lost their cost receipt to the 768 D7 wall-clock/USD
race (num_turns/cost null on exhausted cells), so the mean is over the 5
reporting cells; true stratum spend is higher (see ledger).

### Qualitative census — does grep find the df=1 bridge tokens?

- **Strategy that dominates:** exhaustive filesystem search — Grep + Bash
  (`ls`/`grep`/`cat`) + Read, 20-40+ turns/cell. The failing cells run the
  per-cell budget to the cap (~$0.45-0.49) and *still* fail; the cheap cells
  (~$0.03-0.10) are early give-ups.
- **Correctness determinant = reading BOTH bridge docs.** Every correct
  legal / enron-1k cell read both evidence docs; the single enron-10k "correct"
  read neither (a fluke, not a solve). At 10k files grep dilution is worse —
  **0/10 reached both docs**.
- **Grep-ability verdict (the headline).** The df=1 answer token
  (e.g. `RBF 840` / `XQN 853`) and the linking key (e.g. `Ofrles Prodres`)
  ARE unique literals that grep would return instantly *if known* — but the
  agent cannot reach them, because the **query→entity mapping is a synonym
  paraphrase**: the query says "the power station in the upper wetlands" while
  the bridge doc says "The reactor in the northern marshlands." Literal grep on
  the query's surface tokens fails at **entity resolution**; the searchable
  df=1 token is only reachable *after* the paraphrase is resolved (which needs
  semantic retrieval, not grep). So the camouflaged tokens do **not** grep
  better than the old alien tokens — the camouflage defeats grep one step
  *earlier*, at entity resolution, and the answer token's df=1-ness never gets
  a chance to help the baseline arm. (Same structure in both corpora — the
  fabricated gold is shared; only the real-text host differs.)

### Expected-delta band for the hero campaign

With-tool retrieval ceilings (given): legal-1k **0.50**, enron-1k **0.86**,
enron-10k **0.48**. The ceiling is retrieval recall; with-tool *answer*
accuracy ≤ ceiling (retrieve AND extract the bridge AND answer). Even when the
baseline reads both evidence docs it converts to a correct answer only ~0.8 of
the time (enron-1k: 5 read-both → 4 correct), so applying a ~0.70-0.85
retrieve→answer conversion to each ceiling:

| stratum | baseline (measured) | ceiling | with-tool acc (est) | **expected Δ** |
|---|---|---|---|---|
| legal-1k  | 0.067 | 0.50 | 0.35-0.43 | **+0.28 to +0.43** |
| enron-1k  | 0.267 | 0.86 | 0.60-0.73 | **+0.33 to +0.59** |
| enron-10k | 0.100 | 0.48 | 0.34-0.41 | **+0.24 to +0.38** |

**Implication.** The pre-registration's worry — that df=1 camouflaged bridge
tokens grep well enough to compress the uplift — is **not borne out**. Baseline
is near-floor across all three strata, so the expected uplift band is wide and
firmly positive: largest on **enron-1k** (highest ceiling + moderate baseline),
smallest on **enron-10k** (low ceiling AND low baseline). enron-1k is the
strongest hero cell; enron-10k the weakest signal. The 766 pre-registration
should carry these bands, not a guess.

### Spend ledger

| item | queries | reported $ |
|---|---|---|
| pilot (legal-1k) | 2 | 0.471 |
| legal-1k | 15 | 4.920 |
| enron-1k | 15 | 3.611 |
| enron-10k | 10 | 1.046 (5/10 cost receipts lost, 768 D7) |
| **total reported** | | **10.05** |

Est. true total ~$11-11.6 (enron-10k's 5 null-cost cells were exhausted cells,
so ~$0.3 each). **Under the $15 hard stop.**

### Deviations & caveats (interrogate-results honesty)

- **Corpora reused, not re-fetched.** To avoid real-corpus fetch
  non-determinism, the 3 cells were copied byte-identical from the 767
  worktree's certified materialization and signature-verified against the 707
  structural commitments (sig + query_gold_sha256 + count all match) before
  spending. Equivalent to a re-materialize; stronger guarantee (exact bytes).
- **enron-10k n=10, not 15** — sized down to stay safely under $15 once the
  measured per-cell cost ($0.24-0.33) came in well above the $0.19 historical
  assumption the $9/45-cell estimate rested on.
- **Mid-run environment failure (not self-inflicted).** The execution worktree
  `agent-ace19997b9499c758` was removed by an external cleanup *while locked*,
  mid-run, wiping its working tree and most of `tmp/`. enron-1k artifacts
  survived and were secured to main `tmp/`; **legal-1k's raw record/log were
  LOST** — its per-qid values above are reconstructed verbatim from the in-run
  census (numbers authoritative; raw artifact `legal-1k-RECONSTRUCTED.json`,
  not byte-reproducible without a re-run). enron-10k was re-run *after* the
  failure using the 767 worktree's certified corpus, writing to stable main
  `tmp/`. §F was authored and committed from a worktree re-created on the
  surviving branch.

### Artifacts

`F:\justsearch-public\tmp\analysis-624\776\baseline-probe\` —
`per-stratum-summary.json`, `per-qid-outcomes.json`,
`records/{enron-1k, enron-10k, legal-1k-RECONSTRUCTED.json}`,
`logs/{enron-1k, enron-10k}` (Inspect eval logs).
