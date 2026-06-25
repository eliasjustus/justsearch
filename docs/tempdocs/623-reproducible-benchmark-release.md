---
title: "Reproducible benchmark release: the release is a canonical measurement record (metric-family parametric, sibling-capable); the register/ratchet baselines — and future quality surfaces (model card, in-app) — are governed projections of it, conforming to the canonical-record+governed-projections seam (553/559/622), not hand-maintained forks"
type: tempdocs
status: proposed
created: 2026-06-21
author: agent analysis (research-channel design theorization), filed by agent — STUB
category: search-quality / eval-infrastructure / reproducibility / business-research-channel
principle: "canonical-authority-and-projection — a published measurement is a projection of a cohort-identified reproducible run, never a hand-copied value. Conforms to the canonical-record+governed-projections seam (553 SearchTrace / 559 sibling-record / 622 telemetry); an instance of the recurring system shape 'single canonical authority + governed consumers + fork-prevention gate' (also 626/627/628). Generalization (every-measurement-a-projection) recorded as tempdoc 625, deliberately not built."
related:
  - ../business/research-channel/plan.md            # origin — the backing design + decision 2/3
  - 400-*                                            # run manifest + non-determinism envelope (the substrate)
  - 580-relevance-freeze-and-fw001-thaw              # §11 two-bars + Pyserini/Brewing-BEIR alignment target; Q-010 ratchet
  - 622-agent-telemetry-native-otel-migration        # the canonical-authority-and-projection seam this conforms to
---

> NOTE: Noncanonical working tempdoc. **Investigated 2026-06-21** (§Investigation below);
> the implementation plan is still deliberately deferred per the assigning instruction.
> Captures an idea identified in `docs/business/research-channel/plan.md` ("The backing
> design"). The design *shape* in §Problem…§Next-step is the original STUB; the
> §Investigation section that follows verifies its claims against `main` and records the
> critical analysis (strengths, tensions, scope corrections, open questions for planning).

# 623 — Reproducible benchmark release

## Problem (one paragraph)

JustSearch's retrieval-quality numbers are credible internally but **not publishable as a
reproducible benchmark**, which is the gate the research/eval credibility channel waits behind
(`research-channel/plan.md` decision 2). Two structural defects: (1) there is **no composed,
shippable "benchmark release" object** — the per-run manifests + envelopes live scattered in run
directories as internal operator artifacts; (2) the human-facing baselines (the search-quality
register's Canonical Baselines table and `scripts/jseval/relevance-ratchet-baselines.v1.json`) are
**hand-maintained forks** that hold numbers from heterogeneous runs (different configs at different
commits) and have **demonstrably drifted** — e.g. a "0.925 legal" (`full`@dc4f79a) and a "0.758
default" (`hybrid`@f91e269bc) stapled into one floor file.

## What already exists (do NOT rebuild — extend)

- **Run manifest + cohort-identity hash** (`scripts/jseval/jseval/manifest.py`, tempdoc 400): already
  binds git_sha + config fingerprints + model fingerprints + dataset identity + eval_protocol_hash.
- **Non-determinism envelope** (`jseval calibrate` → `cohort_baselines/<hash>/envelope.json`): the ±2σ
  reproducibility band.
- **Comparability gate** (`comparability.py`): is a run trustworthy enough to compare.
- **Checksum-pinning seam** (`SSOT/manifests/repro/repro.v1.json`): pin an input by source + sha256.
- **Standard scorer** (`ir-measures`, tempdoc 308) and the recognized external target (580 §11.5 Tier 0:
  align to the Pyserini / Brewing-BEIR reproduction matrix).

## What is missing (the design gap — general shape, not implementation)

1. A **benchmark-release projection**: the *set* of `jseval run` manifests identical on every axis
   except `dataset` (one config / commit / hardware), composed into one publishable unit.
2. An **external-baseline reference** per corpus (the published BM25/SPLADE/BGE-M3 number for the same
   dataset), so ours sits "side by side."
3. **Corpus references by source + checksum** (the licensing-safe distribution mechanism —
   `research-channel/plan.md` decision 4), with optional pre-built indexes only where the license
   permits a redistributable derivative.
4. **Re-rooting** the register table + ratchet file as **derived views** of the latest green release —
   removing the fork/drift class structurally.

No new calibration machinery, no hosted leaderboard, no pipeline code change. The release is a thin
composition layer over mature parts.

## Next step (not done here)

Investigate the exact manifest/envelope/registry surfaces, then write the implementation plan. Scope it
to the eval benchmark only; the generalized "all asserted numbers trace to a run" enforcement is
tempdoc 625 (recognized, deliberately deferred).

---

# Investigation (2026-06-21)

> Primary-source verification pass against `main`. Goal: fully understand idea/motivation/solution,
> verify every load-bearing claim against code, and think critically. **No implementation plan or
> design work yet** (per assignment). Every claim below cites `file:line`.

## A. Verdict in one paragraph

The **problem is real and the principle is sound.** The drift the STUB opens with is verified (the
ratchet file genuinely staples a `full`@`dc4f79a` legal floor next to a `hybrid`@`f91e269bc` SciFact
floor — `scripts/jseval/relevance-ratchet-baselines.v1.json:19-24,7-12`), and it conforms to a seam
the codebase already names (`canonical-authority-and-projection`, tempdoc 622 frontmatter:2,9). The
substrate is genuinely ~80% present. **But the headline framing — "a release is the set of manifests
whose `manifest_hash` is identical on every axis except `dataset`" — is technically wrong as written**
(the existing hash cannot answer that question), and **two of the four "what is missing" items plus the
re-rooting target are under-scoped.** None of these sink the idea; they sharpen it. Details below.

## B. Claims that hold up (verified against code)

1. **The drift is real, not rhetorical.** `relevance-ratchet-baselines.v1.json` pins `mixed/courtlistener-200`
   = 0.925 in mode **`full`** (src "309 §35") and `beir/scifact` = 0.758 in mode **`hybrid`** (src "580
   HEAD f91e269bc") in the same file (`:19-24` vs `:7-12`). The file's own `note` confesses it:
   *"grandfathered … loose regression tripwires, not tight ratchets … REFRESH from a fresh green
   calibration run"* (`:4`). The register's Canonical Baselines table is the larger instance —
   `beir/scifact` alone carries 19 rows spanning ≥5 commits (`dc4f79a`, `68782549f`, `5d19ff2c1`,
   `3af6773cc`, `f91e269bc`) (`docs/reference/search-quality-register.md:88-109`).

2. **The substrate exists as described.** Cohort-identity hash binds git+config+model+eval-protocol
   (`scripts/jseval/jseval/manifest.py:261-316`); envelope is per-cohort mean±σ
   (`calibrate.py:51-62`, read/embedded at `manifest.py:322-339`); comparability gate is
   `readiness ∧ ann_proof ∧ error_rate<thr` (`comparability.py:8-37`); checksum-pinning seam exists
   (`SSOT/manifests/repro/repro.v1.json:1`); the scorer is `ir-measures` on official BEIR via
   `ir_datasets` (`corpora.py:1,10,53`); a per-run **manifest index** already records
   `{manifest_hash, run_dir, git_sha, dataset, mode, timestamp}` append-only
   (`bisection.py:79-114`). "Compose, don't rebuild" is largely accurate.

3. **The external-baseline *axis* is already aligned — the slot is curation, not reconciliation.** 580
   §12.3 confirms our runs use official BEIR (`ir_datasets`, binary qrels, test split) + `ir-measures`
   nDCG@10, so *"our numbers are already on the same axis as the Pyserini/BEIR leaderboard — no
   reconciliation needed."* The Tier-0 target (580 §11.5) is the **Pyserini/Brewing-BEIR reproduction
   matrix** (published BM25 / SPLADE++ / ColBERTv2 / BGE-M3 nDCG@10). Public reproduction matrices for
   exactly these models/datasets exist (Pyserini BEIR regressions; the 2025 "Reproducible Baselines with
   Anserini/Pyserini" line, arXiv 2509.02558). So binding an external reference is a **data-entry +
   cite-the-source** task, not a methodology problem.

4. **Scope boundaries with 624/625 are clean.** Agent-utility eval rebuild → 624; generalized
   "every asserted number traces to a run" enforcement → 625 (record-only, `625:48-53`). Correct
   separation; no overlap to resolve.

## C. Critical findings (the part that changes the plan)

### C-1 — "Release = identical `manifest_hash` except `dataset`" is not expressible by the existing seam *(most important)*

`manifest_hash` is a **single SHA over the whole cohort surface, and that surface *includes*
`dataset`, `doc_count`, `query_count`, `corpus_identity`** (`manifest.py:269-290`, hashed at
`_compute_cohort_hash` `:212-222`). Two runs on different datasets therefore have **different**
`manifest_hash` by construction — you cannot ask "same on every axis *except* dataset?" from an opaque
digest. The manifest **index** can't bridge it either: its rows carry `manifest_hash + git_sha +
dataset + mode`, **not** the config/model fingerprints (`bisection.py:98-105`). I grepped for any
config-only / non-dataset sub-hash and there is **none** (`manifest.py`, `run.py`).

→ The release equivalence key is **genuinely missing infrastructure**, not "the existing seam." The
clean fix is small: add a **`config_cohort_hash`** to the manifest — a SHA over the cohort fields
**minus the dataset family** (`dataset`/`doc_count`/`query_count`/`corpus_identity`). Then a release
is honestly *"the set of runs sharing one `config_cohort_hash`, one per dataset,"* and the STUB's
"equivalence class over a hash" framing becomes literally true. This is the one place the "thin
composition, no code change" claim breaks — but it breaks small.

### C-2 — The "hardware fingerprint" release axis does not exist as a *stable* identity

The STUB's release tuple lists a **hardware fingerprint**; decision-2 criterion 2 requires published
GPU model + VRAM + driver + ORT version. But the only hardware capture today is
`env_fingerprint`, which is **explicitly volatile and excluded from cohort identity**
(`manifest.py:_VOLATILE_FIELDS:51-63` includes `"env_fingerprint"`) and whose own docstring says it is
*"informational only … not used as a comparability gate"* because it captures temperature, utilization,
clocks, and top-N processes that change every run (`env_fingerprint.py:1-22,38-56`). tempdoc 400
likewise excludes all hardware from the cohort hash. So a *stable* hardware identity must be
**projected** from the volatile snapshot (the stable subset: GPU `name`, `mem_total_mb`,
`driver_version` — `env_fingerprint.py:95-105`) plus an **ORT-version field that I did not find captured
anywhere stable** (needs a plan-phase check). `model_fingerprints` *is* captured and stable
(`manifest.py:294`). → New surface, small, but real — not pure composition.

### C-3 — A release carries two *kinds* of numbers; only one is a projection

Re-rooting our table removes **our** fork, but the external-baseline column can **never** be a
projection of our runs (we cannot re-run Pyserini inside `jseval`). It is a **cited external
constant**. So the release object should distinguish: **projected** values (ours — derived from the
cohort run, regenerable) vs **cited** values (theirs — pinned by source URL + version + retrieval
date, ideally via the `repro.v1.json` digest seam the STUB already gestures at). This keeps the
"everything is a projection" framing from over-claiming, and tells the implementation which fields
regenerate vs which are immutable citations.

### C-4 — Re-rooting the register *table* is over-scoped; only the headline + floor re-root *(scope correction)*

The Canonical Baselines table is **not** "best-known per corpus" — it is a **research log of
(config × mode) ablations** across encoders/CEs/fusion-weights (the 19-row SciFact block,
`search-quality-register.md:88-109`). A single green release is **one config across all corpora**; it
*cannot* reproduce those ablation rows, which exist *because* they used deliberately different configs.
By the projection-vs-fork rule's own logic (*unify only what shares a reason to change*), the ablation
rows and the published headline row have **different reasons to change** → they should not be unified.

→ The re-rooting target must be precisely **(a) the per-corpus headline/"Best known" number and (b)
the ratchet floor** — *not* the whole table. The ablation history stays as an explicitly-heterogeneous
research log (each row already carries its own `git`/`conf` columns, which *is* honest provenance).
plan.md decision-2 criterion 1 already anticipates this ("non-default config … reported *as a labelled
ablation alongside* the default, never *as* the headline"); the STUB text just needs to inherit that
precision instead of saying "the register table becomes a derived view."

### C-5 — Re-rooting is **not value-preserving**: it will *change* (likely lower) some floors

The floors today use **per-corpus best configs** (`full` for courtlistener=0.925, `full` for MIRACL,
`bm25_splade` for enron, `hybrid` for scifact — `relevance-ratchet-baselines.v1.json:9,15,21,27,33`).
A single-config release measures every corpus under **one** preset (decision-2's `hybrid` default).
Courtlistener has **no `hybrid` row measured at all** today (`search-quality-register.md:148-155` —
only lexical/splade/bm25_splade/full at `dc4f79a`), so we genuinely **do not know** what hybrid scores
on it, and 0.925 (a `full` number) will not survive as a hybrid floor unchanged. → The plan must state
plainly: re-rooting means **adopting the single-config release numbers, several of which will differ
from today's cherry-picked best-config floors** (honesty is the point — but it is a number *change*,
not a refactor, and downstream business docs quoting "0.925 on legal" inherit the change).

### C-6 — Q-010 framing is stale, and the projection alone is not self-maintaining

plan.md (`:299-302`) and 625 (`:37`) call the ratchet *"deliberately not built."* It **was built** —
commit `5a1bfaf18` ("feat(580 Track A+B): … Q-010 relevance ratchet") shipped
`relevance_gate.py` + the baselines file + tests. **But it is not CI-wired**: no `.github/` or
`scripts/ci/` reference invokes it (grep clean); it runs only via the advisory `search-engine-hint`
hook + manual `jseval relevance-gate` against a live stack (the file's note: *"a jseval gate … NOT a
discipline-gate-kernel gate"*). → Consequence for 623: re-rooting fixes the *grandfathered-source*
half of the fork, but a **projection re-rots if nobody regenerates it.** Without a regeneration trigger
(the `search-engine-hint` nudge is the natural hook), 623 swaps "hand-typed fork" for "stale
projection" — strictly better (one source of truth) but **not self-maintaining**. The durable property
the STUB promises ("reproducibility as a standing property") needs the regen step named, or it inherits
the same rot one level up. This is precisely 625's trigger condition wearing a smaller hat.

### C-7 — "One config, all corpora" interacts with the just-shipped adaptive selector (forward risk, not current blocker)

The same commit shipped an **`AdaptiveWeightSelector`** (per-query CC fusion-weight selection),
**default-off** (`index.hybrid.adaptive_weights_enabled` default off — `git show 5a1bfaf18`). So today's
default `hybrid` is reproducible-enough. **Forward risk:** if adaptive weights becomes default-on, "one
config across all corpora" stops meaning "one static leg set" — the selector's per-query inputs become
part of the reproducible surface and must be deterministic for a stranger to land in the envelope. The
release should pin **preset name + engine commit** and treat the engine's preset resolution as part of
the reproduced behavior (not assume a static config). Flag for the plan; not a blocker now.

## D. Open questions for the planning phase (do not resolve here)

1. **`config_cohort_hash` placement** (C-1): add to `manifest.py` as a second hash, or compute the
   release key by field-diff at composition time? (Leaning: the hash — it makes "release = equivalence
   class" literally true and is testable in isolation.)
2. **Where does the release object live?** A new `jseval release` subcommand emitting a versioned
   `release.v1.json` under `cohort_baselines/` vs a top-level `benchmark-release/` dir? (Affects the
   distribution-repo story in plan.md decision 3.)
3. **ORT-version capture** (C-2): is ONNX Runtime version recorded in any stable manifest field today?
   (Did not find it — needs a check; if absent it's a tiny addition to the hardware projection.)
4. **External-baseline source-of-record** (C-3): hand-curated JSON pinned by `repro.v1.json` digest,
   or a fetch-at-build step from Pyserini's published matrix? (Licensing of *their numbers* is trivial —
   they're published facts — but the citation should be version-stamped.)
5. **Re-rooting mechanism** (C-4/C-5): does the register's "Best known" line + the ratchet floor get a
   `<!-- generated from release <id> -->` marker and a generator script, with the ablation rows left
   hand-authored? And who owns running the generator (the `search-engine-hint` regen trigger, C-6)?
6. **The decision-2 "fresh green calibration run" prerequisite is upstream of 623.** 623 *composes* a
   release; it presupposes one single-config/all-corpora green run exists. Does 623 own producing that
   first run, or does it consume one the user produces? (plan.md treats the run as the gate; 623 is the
   object that wraps it. Sequencing question.)
7. **Extraction-quality sibling — SETTLED (F-δ/T-10):** stays a **named deferral inside 623** (§F), in
   substantive owner-attributed form. The release object carries a first-class "what this does NOT
   measure" `coverage` field regardless (T-3). If the metric-family-pluggable shape (§F favor 1 / §C-4)
   is adopted, the sibling later reuses 623's release object with a different scorer — the cheap path.

## E. Logged out-of-scope

- Doc-drift: `relevance_gate.py:11` + `cli.py:2238,2249` reference a non-existent
  `gates/relevance-ratchet/baselines.v1.json`; the loaded path is
  `scripts/jseval/relevance-ratchet-baselines.v1.json`. Appended to `docs/observations.md` Inbox.

## F. Coordination input — retrieval-only coverage boundary (from the 607 extraction/routing agent, 2026-06-21)

The 607 agent (visual-extraction routing authority — just shipped `PolicyDrivenTikaExtractor` /
`VisualRoutingDecision` / `VisualExtractionEvidence`) raised a coverage concern. I verified it against
code and it is **correct, and it strengthens 623's own thesis** — so it is recorded here, not deflected.

**The gap (verified).** 623 measures the **back half** of the pipeline only: retrieval ranking
(nDCG@10) over text that *already exists* — corpora are BEIR/golden/mixed **text**
(`corpora.py:1,10,53`). The **front half** — extraction + route decision (OCR-needed / VDU-needed /
confidence / fallback) — is what *produces* that text from scanned/visual/mixed documents, and **none of
it executes on a clean-text corpus.** So a 623 release measures zero of the extraction/routing authority.

**Why this is in 623's interest, not scope creep.** plan.md **decision 5** is built entirely on *not
overclaiming to an adversarial audience* (the deleted-`marketing.md` teardown). "The engine is
benchmarked," asserted from a release that only scores retrieval, is exactly that overclaim — and the
extraction layer flying with no measurement behind it is exactly the kind of hole a hostile reviewer
finds. → **A 623 release must carry an explicit *negative-space* statement of what it does NOT measure**
(extraction quality, OCR/route correctness), as a first-class field, not a footnote. This is added to
the design intent here; the §C-4 honesty discipline (ablations stay labelled, headline is precise) is
the same reflex applied to coverage.

**Named, owned follow-up (NOT absorbed into 623).** Retrieval-only scope for 623 is correct and stays.
The sibling — an **extraction-quality benchmark release** — is named here with the **extraction/routing
authority (607 lineage) as proposed owner**. It is *not* a footnote and *not* my domain to design; it is
a distinct piece of work with its own corpus, metrics, and ground-truth notion:
- **Metrics:** WER/CER (OCR accuracy), **route-decision correctness** (did the heuristic pick the right
  route — the actual shipped authority), extraction-latency distribution across messy files, and honest
  `ocr.engine_missing` / `ocr.language_missing` facts.
- **Ground truth:** "correct extracted text + correct route" per document, *not* relevant doc IDs.
- **Corpus the engine actually needs:** text PDF, image-only PDF, mixed PDF, bad scan, table/form,
  screenshot, non-English scan, giant PDF.

**Two design favors the 607 agent requested — recorded as *planning considerations*, not committed
design** (consistent with "no design work yet"):

1. **Keep the metric family a parameter, not hard-wired to nDCG. — COMMITTED v1 CONSTRAINT (promoted
   2026-06-21).** A release is *"a config-cohort's scorecard over a corpus set,"* with a family-keyed
   `metrics` map (T-3), not an nDCG field. **Promoted from "consideration" to a hard constraint because
   623's own anti-fork thesis requires it:** an nDCG-hard-wired release object forces the extraction
   sibling to *fork* it into a parallel "extraction release" type — re-creating the projection-vs-fork
   defect 623 exists to remove, one level up. So this is self-consistency, not just a courtesy to 607.
   Aligns with §C-4. **Two coupling points the plan must honour to actually deliver it:**
   - **(a) Release object** — `measured.metrics` is a family-keyed map (T-3). ✅ recorded.
   - **(b) The within-machine envelope/calibration** — `calibrate.py:51-57` `CALIBRATED_QUALITY_METRICS`
     is a *hard-coded retrieval-metric tuple*. Because F-α made "reproduction tolerance = within-machine
     envelope," an extraction release that wants the *same* tolerance shape needs that metric set to be a
     **parameter** (or it computes its own band over WER/CER/route-accuracy). Either is fine; the point is
     the envelope is currently retrieval-only, so "same release shape, different metrics" is **not yet
     true at the tolerance layer** — the plan must make the calibration metric set pluggable, or
     explicitly let the extraction sibling define its own band. This is the one place the settled
     decisions and 607's favor 1 are in tension, and it is now recorded, not glossed.
2. **The extraction scorecard projects from evidence the engine already emits.** Verified:
   `VisualExtractionEvidence` is a per-document record carrying `route`, `ocrMeanConfidence`,
   `ocrFallbackRoute`, `ocrSkipReason`, quality scores, etc.
   (`modules/worker-services/.../extract/VisualExtractionEvidence.java:10-32,116-144`). That is almost
   exactly the per-doc extraction scorecard — so 623's *"release = projection of a reproducible run"*
   thesis applies to extraction **with no new emission machinery**, only a ground-truth corpus + scorer.

**Decision (settled 2026-06-21, F-δ/T-10): this stays a named deferral *inside 623*** — the substantive,
owner-attributed form above (metrics + corpus + ground-truth named) satisfies the 607 agent's "not a
footnote" ask without a new tempdoc. 623 still carries the first-class `coverage`/negative-space field
(T-3) regardless, so a 623 release never implies the extraction front-half is measured.

---

# Theorization (2026-06-21) — design theory, not yet a plan

> This section develops the *design theory* (the "how to proceed" the user asked for): it resolves the
> §C/§D questions where code-evidence lets me, names the genuine forks that need the user's steer, and
> states what must be **live-verified** before any plan is committed. **No implementation, no committed
> design.** Three new facts were verified for this pass and are load-bearing:
> `eval_protocol` is a constant (`run.py:27-31` `METRIC_CONTRACT`) → stable across datasets;
> `env_fingerprint` is volatile/excluded (`manifest.py:51-63`); and **`policy_hash` is hardware-derived**
> — `session-policies` returns the Worker-boot `PolicySnapshot` built from *"ConfigStore + detected
> hardware"* (`SessionPoliciesController.java:13-16`), and that field **is inside the cohort hash**
> (`manifest.py:277-278`). That last fact reshapes the whole design.

## T-1. The cohort, made precise — and its consequence: a release is config-**and-hardware**-pinned *by construction*

Decompose the cohort surface (`manifest.py:261-316`) into three classes:

| Class | Fields | In config-cohort key? |
|---|---|---|
| **Dataset family** (varies per corpus *within* a release) | `dataset`, `doc_count`, `query_count`, `corpus_identity` | **No** — these are the axis a release ranges over |
| **Corpus-dependent `commit_metadata` (⚠ U1 — verified on disk)** | `field_catalog_hash`, `index_schema_fp`, `analyzer_fp`, `synonyms_hash` | **No** — these *vary by corpus* (scifact ≠ the mixed corpora); they describe which fields a corpus populates, not a config choice |
| **Config + hardware** (identical across the release's corpora) | `git_sha`, config-global `commit_metadata` (`schema_fp`, `similarity_fp`, `boosts_fp`, `grammar_hash`), `model_fingerprints.embed_fingerprint`, `eval_protocol_hash`, **`policy_hash`** | **Yes** |
| **Volatile / execution-context** (excluded) | `env_fingerprint`, snapshots, timestamps, `run_id`, `model_fingerprints.{embed_gpu,splade_gpu}` | n/a |

**⚠ U1 — verified against three real on-disk manifests (scifact / courtlistener-200 / cord19-qddf,
`scripts/jseval/tmp/eval-results/*/manifest.json`):** the config fields split cleanly. *Identical across
all three corpora:* `eval_protocol_hash` (`24ce3446…`), `policy_hash` (`60fb2aa6…`), `schema_fp`
(`9b7d9607…`), `similarity_fp` (`e61b58c3…`), `boosts_fp` (`44136fa3…`), `grammar_hash` (`46a5a700…`),
`embed_fingerprint` (`f1d0f4ec…`). *Differ by corpus:* `field_catalog_hash`, `index_schema_fp`,
`analyzer_fp`, `synonyms_hash` (scifact's BEIR profile ≠ the mixed-corpus profile — corpus-intrinsic,
not a config choice). **So a naive "hash all non-dataset manifest fields" config key would NEVER group
scifact with courtlistener into one release — it would silently refuse every multi-corpus release.** The
composer's config-cohort key MUST be the config-global subset above, explicitly excluding the four
corpus-dependent `commit_metadata` fields and the `*_gpu` execution flags. This was the single
highest-risk assumption; it is now resolved with a precise, evidence-backed field list.

Because `policy_hash` is hardware-derived and sits in the "yes" class, **the release-equivalence key is
hardware-entangled whether we like it or not.** This is not a bug to design around — it is the honest
shape: a release *is* "one config + one commit + one hardware, ranged over N corpora." A stranger on a
different GPU produces a **different** cohort key (different `policy_hash`) and therefore **their own
release**, not a re-run of ours. This directly satisfies decision-2 criterion 2 ("publish the
hardware") at the identity level, and it forces T-2. *(Caveat: the three on-disk runs share `policy_hash`
`60fb2aa6…` despite different commits and even different GPU/CPU execution — so `policy_hash` may be more
stable / less hardware-sensitive than T-2 assumes; whether it truly varies across distinct GPUs is the
one part of T-1 still unconfirmed, and moot under the F-α equivalent-setup scoping.)*

## T-2. The two-variances theorem *(the most important theoretical result of this pass)*

> **Decision (F-α, T-10):** the user chose the **single within-machine envelope** for v1, scoped to
> "equivalent setup." The analysis below is **retained as the rationale for that scoping guard** and as
> the trigger-case for a future cross-hardware band — it is *not* the adopted v1 mechanism. Read it as
> "why the equivalent-setup wording is load-bearing," not "what we build now."

The STUB and plan.md decision-2 criterion 5 both promise reproduction *"within the cohort envelope
(±2σ)."* **That conflates two different variances:**

- **Within-machine non-determinism** — what `jseval calibrate` measures: N identical runs on the *same*
  box (`calibrate.py:138-184`). nDCG CV is **0.1–0.3%** (σ≈0.001, ±2σ≈0.002) — tiny, because only
  GPU/thread/ANN jitter moves between runs (tempdoc 400 §13.9 B2).
- **Cross-hardware / cross-version reproduction** — what a *stranger* experiences: different GPU
  microarchitecture → different FP accumulation → different dense vectors → different *approximate*-NN
  neighbours → different ranking, compounded by different ORT/CUDA/driver. This is **unmeasured** and
  architecturally must be **larger** (plausibly 5–20× the within-machine σ; needs live validation —
  see T-9).

→ **Advertising the within-machine ±0.002 band as the reproduction tolerance would mislead exactly the
adversarial audience decision-5 fears** — a reviewer on a different GPU lands outside it and concludes
we fudged. The release must therefore carry an *explicit, separate* **reproduction tolerance** that is
**not** the within-machine envelope. Two honest ways to set it, not mutually exclusive:

1. **Conservative hand-set band, labelled as such** (e.g. the ratchet's ±0.02 abs) — "cross-hardware
   tolerance, not yet empirically calibrated." Ship this first.
2. **Grow it empirically from reproducer submissions** — each stranger who reproduces contributes a
   `(hardware, number)` pair; the cross-hardware band becomes a *measured, widening* artifact (the
   Pyserini-community model). This is the elegant long-run answer and costs us nothing but a submission
   format.

**This reframes decision-4's pre-built index from "convenience" to "rigour lever."** A shipped pre-built
index (+ ideally pre-encoded queries) removes the index-build and ANN-construction variance, collapsing
the cross-hardware band toward the within-machine one. So the pre-built index is *the mechanism that
makes a tight cross-hardware reproduction claim true* — available only where licensing permits
(CourtListener public-domain cleanly; MIRACL under CC-BY-SA; **never** BEIR/SciFact pointer-only). The
consequence: **reproduction tolerance is per-corpus** — tight where we ship an index, looser (full
pipeline) where we ship only a pointer. That is more honest than one global band and falls straight out
of the licensing matrix plan.md already built.

## T-3. The release object shape (schema-level theory, not a schema)

A release binds, per the cohort tuple, two **distinct value classes** that must not be conflated (§C-3):

- **`measured` (projected, regenerable)** — per corpus: `{config-mode, metrics: {<family-keyed map>},
  tolerance_band, confidence_tier, corpus_source_url + sha256 (or ir_datasets id), qrels_id}`. The
  **`metrics` map is family-keyed, NOT nDCG-hard-wired** (committed constraint — see §F favor 1): a
  retrieval release populates `{nDCG@10, P@1, R@10, …}`; an extraction release populates `{WER, CER,
  route_accuracy, …}`. Same object shape, different metric family. Derived from the cohort run; re-runs
  reproduce it.
- **`external_baselines` (cited, immutable)** — per corpus: `[{model, value, source_url, version,
  retrieved_at}]` for the published BM25/SPLADE++/ColBERTv2/BGE-M3 numbers. **Never** a projection of
  *our* runs; pinned by source+version (reuse the `repro.v1.json` digest seam — `repro.v1.json:1`).
- **`reproduction_tolerance`** — per F-α (T-10): the per-corpus **within-machine ±2σ envelope**, with the
  reproduction claim **scoped to "equivalent hardware/setup"** (the load-bearing wording, not a separate
  cross-hardware band).
- **`coverage` / negative-space** (§F) — first-class statement of what this release does **not** measure
  (extraction/OCR/route quality). Not a footnote.
- **`hardware`** — human-readable projection of the stable `env_fingerprint` subset (GPU `name`,
  `mem_total_mb`, `driver_version` — `env_fingerprint.py:95-105`) + ORT/CUDA version (**T-9 gap:**
  ORT version not yet confirmed captured anywhere stable).

## T-4. The equivalence key — *no `manifest.py` change needed for v1* (refines §C-1)

§C-1 said the existing `manifest_hash` can't express "same config, different dataset." True. But the fix
need **not** touch `manifest.py`: the **release composer** can compute the config-cohort key itself, at
compose time, by canonical-hashing the "config + hardware" field class (T-1) read from each candidate
run's existing `manifest.json`, and **refuse to compose** unless every run in the set agrees on it
(this is the comparability gate `comparability.py:8-37` lifted from per-run to per-*set*). So:

- **v1 (thin, no migration):** composer derives + asserts the key from existing fields; emits it as the
  release's identity. The "release = equivalence class over a hash" claim becomes literally true, with
  zero pipeline change. This is the honest version of the STUB's "compose what exists."
- **Later (optimization, deferred):** promote `config_cohort_hash` into `manifest.py` + the
  `manifests.jsonl` index row (`bisection.py:98-105`) so `jseval release --config-cohort <hash>` can
  *discover* the member runs without opening every manifest. Pure discoverability; not needed for
  correctness.

## T-5. Re-rooting mechanism — **live-read beats generated-file** (resolves §C-4/§C-5 mechanism)

Two ways to make the ratchet/register "projections":

- **(a) Generated file** — a script rewrites `relevance-ratchet-baselines.v1.json` + the register's
  "Best known" lines from the latest release, marked `<!-- generated -->`. Still a *file of numbers* that
  can be hand-edited → the fork can silently return.
- **(b) Live-read (recommended)** — `relevance_gate.evaluate` reads the floor **directly from the
  current release object**; the baselines file shrinks to a **pointer + policy**: `{current_release:
  "<id/path>", tolerance_default_abs: 0.02, per_corpus_tolerance?}`. There is then **no table of numbers
  to hand-maintain** — the fork becomes *structurally impossible*, which is the whole point. The register
  "Best known" line likewise becomes a **link** to the release, not a restated number; the (config×mode)
  ablation rows stay hand-authored (they are a legitimately heterogeneous research log — §C-4).

Live-read also fixes a latent correctness bug for free: today the ratchet floors CourtListener in
**`full`** mode (`relevance-ratchet-baselines.v1.json:21`) — a config users do **not** get by default —
so the ratchet protects a non-production path. Re-rooting to the single production-default release makes
the floor track *what users actually receive*. (It will also *change* the floors — §C-5 — likely lowering
some; that is the honest cost, stated up front, not a regression.)

## T-6. Self-maintenance — staleness is "the floor stops *rising*," not "the floor is wrong" (softens §C-6)

A floor is monotonic: even a stale release floor still **catches regressions** (HEAD below floor → fail);
staleness only means the floor doesn't *tighten* as the engine improves ("loose tripwire vs tight
ratchet", the file's own words — `:4`). So the projection re-rot is bounded and non-false-positive. Make
it *visible* at the right tier: the existing `search-engine-hint` (PostToolUse, hook-hint ~85%) already
fires on engine-source edits — extend its message to "your current release predates HEAD; regenerate to
re-tighten floors." Hard enforcement (fail on stale release) is **625's** trigger, not 623's.

## T-7. Sequencing — four independently-valuable increments; channel-unblock ≠ fork-kill

1. **Release object + composer** (T-3/T-4) — unit-testable against *existing* run dirs (incl.
   deliberately heterogeneous ones, to prove the "refuse non-cohort set" path). No live stack.
2. **First green release published from it** (the decision-2 gating run) — needs the dev stack + all
   corpora at one commit/config/hardware. *This is upstream live-stack work that 623 consumes, not owns*
   (§D-6) — but publishing *through* the object from day one means there is **never** a hand-typed
   published table to entrench.
3. **Re-root the ratchet** to live-read the release (T-5). Kills half the fork.
4. **Re-root the register headline** to a link (T-5). Kills the other half; ablations stay.

(1)+(2) unblock the business channel; (3)+(4) deliver the structural anti-drift property. They can land
in order; each is shippable alone.

## T-8. Alternatives considered (steelmanned)

- **Alt-1 — skip the object, publish a static methodology table.** Minimal decision-2 satisfaction, zero
  code. **Rejected as the destination:** it *is* a new hand-typed fork — the exact defect 623 exists to
  remove; it re-rots on the next fusion-weight tweak. (Useful only as a throwaway if the channel deadline
  truly precedes (1); even then, prefer publishing through a v1 object.)
- **Alt-2 — compose-time field comparison, no hash in `manifest.py`.** **Adopted for v1** (T-4): simpler,
  no migration, and arguably *more* robust (compares actual fields, not a digest that might omit one).
- **Alt-3 — pre-built index as the *primary* reproduction path (Pyserini model).** **Deferred for v1**
  (F-ε/T-10): with the within-machine band + equivalent-setup scoping (F-α), a pre-built index is not
  *needed* for v1's tolerance claim. It remains the right *future* rigour lever for a tight
  cross-hardware claim, available only where licensing permits (CourtListener public-domain; MIRACL
  CC-BY-SA; never pointer-only corpora) — a per-corpus lever, never the global path.

## T-9. What must be **live-verified** before a plan is committed

1. **Cross-hardware variance magnitude (T-2).** The whole reproduction-tolerance design rests on
   "cross-hardware Δ ≫ within-machine σ." Architecturally sound, but *unmeasured here*. Needs the same
   config run on ≥2 distinct GPUs (or a documented external repro) before we publish *any* tolerance
   number as more than conservative. Cannot be done from this box alone.
2. **Is a single `hybrid` config actually green on *all* pinned corpora?** CourtListener has **no `hybrid`
   row measured at all** (§C-5). Until the decision-2 run exists, we do not know the headline legal number
   under the production default — so the release's first row set is genuinely unknown, not a re-label.
3. **ORT/CUDA version capture (T-3 `hardware`).** Confirm whether ONNX Runtime version is recorded in any
   stable manifest field; if absent it is a tiny addition to the hardware projection.

## T-X. External-practice research (2026-06-21) — what the IR-reproducibility community expects

> Two bounded web-research passes (primary-source cited). Purpose: align the *externally-judged*
> surface (release format, tolerance reporting, external-baseline presentation) to current
> (2025–2026) Pyserini/Anserini/ECIR-reproducibility convention, since 623's whole value is credibility
> with that adversarial audience. **Post-cutoff flag:** the anchor paper *"Lighting the Way for BRIGHT:
> Reproducible Baselines with Anserini, Pyserini, and RankLLM"* (arXiv 2509.02558) v1 is 2025-09-02
> (safe) but was accepted to the **SIGIR 2026 Reproducibility Track** with a **v2 dated 2026-06-01**
> (beyond my knowledge cutoff — its v2 revisions are unverified).

### Format/convention findings (MATCH = our shape is native; DIVERGE = change needed)

| # | Convention (primary source) | Verdict for our planned shape |
|---|---|---|
| R1 | **"Two-click reproduction" (2cr): a runnable *search command + eval command* per corpus×model**, not a metadata blob (Pyserini 2cr matrix `castorini.github.io/pyserini/2cr/beir.html`; 2509.02558) | **DIVERGE — add.** Our release is a metadata record; the community expects a copy-pasteable runnable repro per corpus. A JSON blob alone reads as a results dump. |
| R2 | **Checksum the *derived index*, not only the source corpus** (Pyserini ships prebuilt indexes w/ MD5) | **PARTIAL.** Our pointer+sha256 on the *corpus* matches the no-redistribute norm (sha256 > their MD5 — keep), but the number is determined by the *index*; reproducer can't hit our nDCG from a corpus checksum if index-build is under-specified. Pin the index build (or ship a checksummed index — but F-ε deferred prebuilt indexes). |
| R3 | **Expected scores pinned in version-controlled config + an automated regression check** (Anserini `src/main/resources/regression/*.yaml` + `run_regression.py`) | **MATCH.** This is exactly our live-read re-rooting + `relevance-gate` (T-5). Upgrade: keep expected scores in-repo + machine-checked (we have this). |
| R4 | **Deterministic lexical/sparse retrieval → numbers are pinned and EXACT-MATCHED (3–4 dp), NOT a tolerance band.** A ±band signals "my pipeline is nondeterministic" (2cr pages quote no tolerance; Anserini "promise" = determinism-by-construction) | **DIVERGE — bears on F-α (see below).** Our single within-machine ±2σ envelope is a *different epistemic claim* than community norm; an adversarial reviewer reads a σ-band as weakness, not rigor. |
| R5 | **GPU/neural nondeterminism is a disclosed, controlled liability; report numeric precision (FP32/FP16/BF16) — a first-order σ driver** (arXiv 2506.09501) | **MATCH existence / REFRAME.** A variance band is justified *for the neural stages only*, framed as "bounded disclosed nondeterminism," and **precision must enter the hardware-cohort identity** (T-3 gap). |
| R6 | **The formal "reproduced within X" vocabulary is per-topic RMSE / Effect Ratio / ΔRI / Kendall's τ** (Breuer et al., SIGIR 2020, arXiv 2010.13447), NOT "σ" | **DIVERGE — add idiom.** A cross-machine *replication* claim should report per-topic RMSE / Effect Ratio vs our own published run; keep σ as a supplementary engineering metric. |
| R7 | **ACM badging: "Available" needs a DOI archival deposit (Zenodo), not just a git URL; "Functional/Reusable" need env+precision pinning + runnable script + drift check** (acm.org/publications/badging-terms; SIGIR/ECIR tracks) | **MOSTLY MATCH; close 3 gaps:** DOI deposit, env/precision pinning per cohort, runnable script + drift check. Our provenance record is *more* complete than the median artifact. |
| R8 | **External-baseline table form: datasets-rows × models-columns, nDCG@10 @3dp; footnote/mark which numbers are self-reproduced vs quoted-from-paper** (Brewing BEIR arXiv 2306.07471) | **MATCH layout.** Add explicit provenance marking on each external number (no standard visual convention exists — make one + footnote sources, or a reviewer assumes every number is ours). |

### External-baseline data findings (the "side-by-side" pillar has three real holes)

Published nDCG@10 for our corpora (all sources 2022–2024, none post-cutoff):

- **BEIR SciFact (test, binary qrels — matches our axis):** BM25 **0.665** multifield / **0.679** flat; SPLADE++ **0.710** EnsembleDistil / **0.702** SelfDistil; ColBERTv2 **0.693**. **BGE-M3 dense on SciFact = NOT FOUND** (the BGE-M3 paper has no BEIR table). Sources: SPLADE++ (Formal SIGIR'22), ColBERTv2 (NAACL'22), Brewing BEIR (SIGIR'24).
- **MIRACL — three protocol problems that undercut a naive side-by-side:**
  1. **MIRACL baselines are *dev split*, not test** (test held out for the WSDM'23 Cup). Our eval axis is "test split" — so for MIRACL the published baselines are on a *different split*. (Qrels are binary on both — that axis matches.)
  2. **German is NOT a canonical MIRACL language** (it's a "surprise" language; no row in the MIRACL paper's baseline table). The only published `de` numbers are BGE-M3's *own non-canonical* reproduction (BM25 de=0.120, dense de=0.567). So `miracl-de-2k` — a headline multilingual corpus — has **no canonical external baseline**.
  3. **Two incompatible BM25/mDPR baselines exist** (MIRACL-paper BM25 fr=0.183 vs BGE-M3-paper BM25 fr=0.458 — different implementations, up to ~27 nDCG pts apart). Must never be mixed in one comparison.
  - Citable canonical (MIRACL paper Table 2, dev): fr BM25 0.183 / mDPR 0.435 / hybrid 0.523; zh BM25 0.180 / mDPR 0.512 / hybrid 0.526.

### Net design deltas from the research

- **Add:** runnable 2cr repro per corpus (R1); a pinned index-build or checksummed index (R2); numeric precision in the hardware-cohort identity (R5); per-topic RMSE/Effect-Ratio for cross-machine claims (R6); DOI archival deposit + env pinning (R7); explicit self-reproduced-vs-quoted marking on external numbers (R8).
- **Reconsider F-α (R4):** the single within-machine ±2σ band diverges from community norm. The research recommends a **stage-split**: exact-match pinned numbers for the deterministic lexical/index path; a *named* variance band only for neural/GPU stages. **This is new external evidence bearing on a decision the user already made (F-α) — flagged for the user's reconsideration, not silently overridden** (see §T-10 F-α note).
- **External-baseline scope correction:** SciFact side-by-side is clean (drop BGE-M3-on-SciFact, which has no published number; cite BM25/SPLADE++/ColBERTv2). MIRACL side-by-side must footnote *dev-split* and treat **German as having no canonical baseline** — a genuine weakness to disclose, not paper over (decision-5 honesty).

## T-10. Decisions (settled by the user, 2026-06-21)

The forks are resolved; these are now design constraints the plan must honour.

- **F-α — Reproduction tolerance → `within-machine envelope` (single band).** v1 publishes the existing
  per-cohort ±2σ envelope as the reproduction tolerance (NOT the two-variances split). **Mandatory
  honesty guard that makes this safe:** the release's reproduction claim is **scoped to "equivalent
  hardware/setup"** — matching decision-2's own wording *"different team, same setup, same number"* (the
  setup includes the GPU). The release must **not** claim reproduction on *arbitrary* hardware to within
  this band. The empirical cross-hardware band (reproducer submissions) and the within-/cross-hardware
  distinction (T-2) are **deferred**, not discarded — they re-enter if/when reproducers on differing
  hardware appear (a natural 625 trigger). *T-2's analysis stands as the rationale for the guard; the
  user chose the lighter v1.*
  **⚠ New external evidence (R4, T-X) bearing on this decision:** the IR-reproducibility community
  pins-and-**exact-matches** deterministic lexical/sparse numbers and reads a σ-band as a sign of a
  *nondeterministic pipeline* (a weakness), not rigor. Recommended refinement to revisit with the user:
  **stage-split** the tolerance — exact-match for the deterministic lexical/index path, a *named*
  variance band only for the neural/GPU stages (with precision FP32/FP16/BF16 disclosed), and express
  any cross-machine claim in the community idiom (per-topic RMSE / Effect Ratio, R6). This does not
  reverse F-α; it scopes *where* the envelope applies. **Open for the user — not silently changed.**
- **F-β — Re-rooting → `live-read pointer` (T-5b).** The ratchet floor (and the register "Best known"
  line) read **directly from the current release object**; the hand-typed baselines file collapses to
  `{current_release, tolerance}`. The fork becomes structurally impossible. Also fixes the
  CourtListener wrong-mode floor (T-5).
- **F-γ — Release model → `config-AND-hardware-pinned` (T-1).** Settled by the code (`policy_hash` is
  hardware-derived and in the cohort). One release per (config, commit, hardware); cross-hardware is
  separate releases. Consistent with F-α's "equivalent setup" scoping.
- **F-δ — Extraction sibling → `named deferral inside 623` (§F).** Stays in §F as a *substantive,
  owner-attributed* named deferral (metrics + corpus + ground-truth spelled out — not a footnote, which
  satisfies the 607 agent's actual ask). No new tempdoc. 623 still carries the first-class
  `coverage`/negative-space field regardless.
- **F-ε — Corpus distribution → `pointer-only v1` (Alt-3 deferred).** Ship download scripts + checksums
  for **all** corpora (already half-there via `ir_datasets`); **no pre-built indexes in v1.** Pre-built
  indexes for the license-clean corpora (CourtListener public-domain, MIRACL CC-BY-SA) become a later
  increment — and, with F-α settled on the within-machine band, they are a *future* rigour lever, not a
  v1 necessity.

**Net effect on scope:** v1 is now meaningfully lighter — single tolerance band, no pre-built-index
build/licensing work, no new tempdoc. The structural anti-drift core (live-read re-rooting) is retained
in full. Remaining live-verification items (T-9 #2 single-config greenness, #3 ORT capture) are
unchanged; T-9 #1 (cross-hardware magnitude) drops off the v1 critical path per F-α.

---

# Confidence-building pass (2026-06-21) — resolved uncertainty ledger

> Pre-implementation de-risking (user-approved plan). Converts assumptions verified only from *code*
> into facts from *on-disk artifacts + one live measurement*. **No feature work.** Each row: outcome +
> primary-source evidence. (Plan: `~/.claude/plans/tranquil-orbiting-mango.md`.)

| # | Uncertainty | Outcome | Evidence |
|---|---|---|---|
| **U1** | config-cohort key invariant across datasets? | **RISK CONFIRMED → resolved with precise fix.** A naive "hash all non-dataset fields" key would *never* group scifact with the mixed corpora. 4 of 8 `commit_metadata` fps are corpus-dependent (`field_catalog_hash`, `index_schema_fp`, `analyzer_fp`, `synonyms_hash`); the rest + `policy_hash`/`eval_protocol_hash`/`embed_fingerprint` are config-global. Composer key = config-global subset only. | 3 real manifests diffed (T-1 table); `scripts/jseval/tmp/eval-results/{*scifact,*courtlistener-200,*cord19-qddf}/manifest.json` |
| **U2** | `hybrid` (prod default) green + not-embarrassing on courtlistener? | **GREEN but NOT FLATTERING — important.** Live eval at HEAD (`1b43bbe45`): hybrid courtlistener-200 = **nDCG@10 0.620** (P@1 0.60, R@10 0.635, `comparable=True`, `ann=PASS`, 100% enriched). That is **~0.30 below the "0.925" legal headline** (a `full`-mode number) and below even the lowest `full` run (0.777). The production-default config is materially weaker on long legal docs (matches register F-004 / "BM25-dominant beats balanced on legal"). The 623 machinery is unaffected; but **the first single-config release would publish ~0.62 for legal, not 0.92** — a business-headline issue, not a code issue (see implication below). | live `jseval run --modes hybrid` exit 0; `scripts/jseval/tmp/eval-results/.../summary.json` |
| **U3** | lexical path deterministic (σ≈0) → R4 exact-match supportable? | **PARTIALLY RESOLVED.** Properly-calibrated (n=5) full-mode envelope σ(nDCG)=0.00117 (tiny, ✓); an n=3 envelope showed 0.0138 (unstable estimate). **No `lexical`/`bm25` envelope exists on disk** — lexical determinism is architecturally sound (BM25 over a fixed Lucene index) but *not empirically shown*; a lexical calibration run would confirm. Informs F-α (R4), doesn't block. | `tmp/validation-data/cohort_baselines/*/envelope.json`; `scripts/jseval/tmp/calibration-data/non_determinism_envelopes/*.json` |
| **U4** | real run artifacts match assumed shape; gate read-path correct? | **CONFIRMED.** `summary.json` carries `per_mode.<mode>.aggregate_metrics.nDCG@10`, `comparable`, `ann_proof_status`, embedded `manifest` — exactly what the composer + `relevance_gate.evaluate` read. (hybrid scifact here = 0.755 ≈ register 0.758.) | `scripts/jseval/tmp/eval-results/20260615T162932_scifact/summary.json` |
| **U5** | which SciFact external BM25 baseline (flat 0.679 vs multifield 0.665)? | **RESOLVED by data.** Our `lexical` scifact nDCG = **0.661** — sits on the published **multifield** BM25 (0.665), not flat (0.679). Cite 0.665; our BM25 is already on-axis. | summary.json lexical mode = 0.6611; T-X external table |
| **U6** | multi-corpus sweep producible as composable run-dirs? | **CONFIRMED.** `jseval run` takes a *single* `--dataset` → a release = N invocations (one per corpus) → N run-dirs the composer groups. No new orchestration; a loop. | `cli.py:42` (`cmd_run` single `--dataset`) |
| **U7** | ORT/CUDA version captured for the hardware projection? | **GAP CONFIRMED (small).** `inference_status` has driver (`nvmlDriverVersion 610.47`) + VRAM (12 GB) + tier; `env_fingerprint` has GPU name. But **ORT version + CUDA version are captured nowhere** → a small new capture at Worker boot (exactly T-9 #3). | manifest `inference_status_snapshot`; grep found no `ortVersion` in `modules/*/src/main/java` |

**Design deltas forced by this pass** (folded into T-1; to carry into the plan):
- **U1:** the composer's config-cohort key is the *config-global subset*, not all-non-dataset-fields — and this is now spec'd with the exact field list. (Biggest catch — would have silently broken every multi-corpus release.)
- **U7:** add ORT + CUDA version capture (small) so the hardware projection meets the R7 badging bar.
- **U3/R4:** a lexical-only calibration run is worth doing during the first-release work to *empirically* back the "deterministic lexical → exact-match" claim (else the σ-band framing is all we can defend).

### ⚠ The U2 finding is the real surprise — it's a *business/headline* decision, not a code risk

The single-config premise (decision-2 criterion 1) is *implementable*, but the data it surfaces is
unflattering: **under the production-default `hybrid`, legal = 0.62, not the 0.92 the decision-1 wedge
leans on.** 623's machinery handles this correctly (each corpus row carries its `config-mode`; the
headline must be config-labelled — §C-4/§C-5 predicted exactly this, now *quantified*). But it forces a
call the implementation cannot make for itself — **which belongs to the user/business:**
- (a) Report `full` for legal as a **labelled ablation** alongside the hybrid default (decision-2
  criterion 1 explicitly permits this) — keeps the 0.92 honest-but-caveated; **or**
- (b) Make the **adaptive preset** (the default-off `AdaptiveWeightSelector` / corpus-aware routing) the
  production default so the engine itself picks the BM25-dominant config for legal — turns "one config"
  into "one *adaptive* config"; **or**
- (c) Drop legal as a headline corpus and lead on multilingual.

This is surfaced, not resolved — it is the single most consequential thing this pass found, and it lands
squarely in the decision-1/decision-2 territory the research channel owns.

## Confidence rating — remaining v1 work: **6 / 10** (critical)

**Implementation mechanics alone: ~8/10.** The substrate is mature and now *verified against real
artifacts*: the config-cohort key has an exact, evidence-backed field list (U1); the artifact shapes the
composer and live-read re-rooting depend on are confirmed (U4/U6); the only new code surfaces are small
and known (U7 ORT capture; the config-global hash; the live-read gate change). I would not expect a
structural surprise building (1)–(4).

**Held down to 6/10 by three non-code risks this pass exposed or left open:**
1. **U2 — the deliverable's headline is weaker than assumed** (legal 0.62 under the honest default). The
   machinery ships fine, but whether the *release achieves its purpose* (a credible, flattering-enough
   benchmark for the channel) now hinges on a business decision (a/b/c above) that could reshape scope
   (e.g. option (b) pulls the adaptive selector onto the critical path — a much larger change).
2. **Decision-gated framing still open:** F-α tolerance vs the IR-community exact-match norm (R4), and the
   external-baseline honesty (MIRACL dev≠test, German non-canonical, BGE-M3-SciFact absent). These are the
   user's calls; each could move the v1 shape.
3. **U3 not empirically closed:** the "deterministic lexical → exact-match" claim rests on architecture,
   not a measured σ≈0 (no lexical envelope exists yet).

**Reading:** *confidence to build the thing right* is high; *confidence the thing as-specified achieves
its credibility goal without a strategy decision first* is moderate. The pass did its job — the biggest
surprise (U2) is now visible **before** implementation, where it's cheap to act on.

---

# Implementation (2026-06-21) — the machinery

> Built per the approved plan (`~/.claude/plans/tranquil-orbiting-mango.md`). Extends existing infra;
> the new code is a thin composition layer + two re-root seams + one small status field.

**Increment 1 — release object + composer.** `scripts/jseval/jseval/release.py`:
`config_cohort_key()` hashes only the **config-global** manifest subset (U1 field list — excludes the
four corpus-dependent `commit_metadata` fps + `*_gpu` flags); `compose()` is a pure fn building per-corpus
`measured.metrics` as a **family-keyed map** (not nDCG-hard-wired, §F favor 1), with within-machine
tolerance band, hardware projection, `external_baselines` (cited), `coverage` negative-space, and
labelled `ablations`. `canonical_dataset_slug()` normalizes the BEIR short name (`scifact`) the runner
emits to the register/ratchet slug (`beir/scifact`). `release.v1.schema.json` + `cmd_release` (CLI).
**15 unit tests** incl. the U1 regression guard (scifact+mixed MUST compose) — green.

**Increment 3 — ratchet re-rooted to live-read.** `relevance_gate.project_release_to_baselines()`
projects a release into the exact dict shape `evaluate()` already consumes (so `evaluate()` is unchanged;
its tests still pass). `cmd_relevance_gate` detects a pointer (`current_release`) and projects; the
baselines file is now a **pointer** (`{current_release, tolerance_default_abs, per_corpus_tolerance,
fallback_baselines}`) — the hand-typed table is gone, the fork is structurally impossible. Transition
`fallback_baselines` covers corpora not yet in the release. **10 gate tests** (incl. projected-floor
end-to-end) — green.

**Increment 4 — register headline + CI guard.** `scripts/docs/register-headline-sync.mjs` (mirrors
`llmstxt-generate.mjs`) projects the release into a marked "Release Scorecard" block in the register
(datasets-rows × baselines, per R8); the per-corpus ablation tables stay hand-authored.
`scripts/ci/check-release-baseline-sync.mjs` (Ajv schema-validate + register `--check` + pointer
well-formedness) is the §C-6 anti-rot guard. `scripts/jseval/external-baselines.v1.json` carries the
cited published numbers with the T-X caveats baked in (MIRACL dev≠test; German non-canonical;
BGE-M3-SciFact omitted).

**Increment 5 — ORT/CUDA capture (U7) — PARTIAL, with a live-confirmed limitation.**
`OnnxSessionCache.ortVersion()` (public accessor over the existing cached static) +
`OrtCudaHelper.CUDA_TOOLKIT_MAJOR`; `InferenceHandlers` adds `gpu.cudaVersion` + best-effort
`gpu.ortVersion` to `/api/inference/status`. **Live finding:** `cudaVersion` ("12") is captured
Head-side and flows into the release hardware projection; **but `ortVersion` is always `null` because
the Head process cannot initialize the ORT environment** (it runs no ORT sessions — exactly the nuance
the plan flagged: "do not put an `OrtEnvironment` call in the Head"). Confirmed live: a fresh
courtlistener manifest shows `gpu.cudaVersion=12, gpu.ortVersion=null`. The ORT-version *string* needs a
**worker-side** capture surfaced through a worker→Head channel — but that touches the governed worker
inference-composition region and shifts `policy_hash` for every run, which is disproportionate to a
minor projection field. **Flagged for the user** (per `tempdoc-is-your-contract`): captured cudaMajor +
driver + VRAM + GPU name + model fingerprints already populate the projection richly; `ort_version`
records honestly as `null`/"unstated". → see "ORT-version follow-up" below.

**Verification — all green (live, 2026-06-21 at `3a9c073b3`):**
- Unit: full jseval suite **883 passed**; +**25** new/extended (15 composer incl. the U1 guard, 10 gate incl. projected-floor end-to-end).
- **End-to-end machinery (live):** two fresh HEAD runs — scifact (BEIR, hybrid **0.757**) + courtlistener (mixed, hybrid **0.620**) — **composed into one release** (`config_cohort_key e548e775`) *despite differing corpus-dependent `commit_metadata` fps* → the U1 design proven live (a naive key would have refused them). The release carries a rich hardware projection (RTX 4070 / driver 610.47 / VRAM / cuda 12 / model fps), family-keyed metrics, cited external baselines (SciFact BM25 0.665 / SPLADE++ 0.710 / ColBERTv2 0.693), and the coverage negative-space.
- **Re-rooting (live):** `jseval relevance-gate` reads floors **projected from the release** — courtlistener's floor is now **0.620** (production-default hybrid), **re-rooted from the hand-typed 0.925** (`full`). The fork is gone (no table of numbers to hand-edit).
- `register-headline-sync` projected the release into the register's "Release Scorecard" block (datasets-rows × cited baselines, R8); `check-release-baseline-sync` green (schema + register `--check` + pointer).

**Increment 2 — first canonical release:** the all-corpora sweep (hybrid default; `full` legal ablation)
is the long live op gated on the U2 business call. The end-to-end **demo release** (scifact + courtlistener
at HEAD) is real and validates the whole flow; the canonical release just adds the remaining corpora at
one commit. The U2 legal-headline strategy (option a/b/c) remains the user's call — and the live courtlistener
**0.620 vs 0.925** confirms why it matters.

**ORT-version follow-up (small, deferred — user's call):** to capture the ORT library *version string*,
compute it worker-side (where `OnnxSessionCache` already initializes ORT) and surface it via a worker→Head
status channel the manifest retains. Logged to `docs/observations.md`. cudaMajor + driver already cover most
of the R7 "published environment" need.

**Build status (2026-06-21):** my code is green at every tier — `compileJava` + `spotlessJavaCheck` +
`pmdMain` pass for `modules:ui` and `modules:ort-common`; jseval `pytest` 883 + 25 pass. The full
`./gradlew build -x test` is **red, but NOT from this work** — it fails only at `:ssotValidateExec`
(`field-catalog.schema.json`) due to **another agent's uncommitted `SSOT/schemas/*.v1.json` edits**
(knowledge-search-response / operation / resource / search-trace / trace-stage — search/trace WIP in the
shared `main` checkout, plus the `main` advancing to `3a9c073b3` via the 628 merge mid-session). 623
touches zero `SSOT/` files, so this is a cross-agent coordination state, not a 623 regression; not fixed
here per `never-delete-untracked-in-main`. **Flagged for the user.**

---

# Confidence pass #2 (2026-06-21) — de-risking the *remaining* work (post-audit)

> Pre-implementation de-risking for the four remaining items: corpus checksum (③), register
> reconciliation (④ deviation), canonical all-corpora release (④ realization), ortVersion (flagged).
> Read-only investigation + (attempted) one live run. **No feature work.** (Plan:
> `~/.claude/plans/tranquil-orbiting-mango.md`.)

| # | Uncertainty | Outcome | Evidence |
|---|---|---|---|
| **V1** | meaningful checksum target per corpus family? | **RESOLVED.** The `corpus_identity.signature` seam is **dead** (env vars `JUSTSEARCH_CORPUS_SIGNATURE`/`PROFILE_ID` are only *read*, never set — no producer). Strategy: compute an own sha256 — `mixed/*` over local `corpus.jsonl` + `qrels/test.tsv`; BEIR uses the `ir_datasets_id` pointer (already captured) + a qrels hash. Trivial. | `manifest.py:288-289` + `run.py:531-532` (read-only); no setters in `scripts/`/`modules/`; `corpora.py:79-80` (local `corpus.jsonl`/`qrels`) |
| **V2** | reuse the `SSOT/manifests/repro` digest seam? | **REFUTED (design assumption wrong).** `repro.v1.json` is **config-artifact-specific** — produced by the Java `SsotValidator` (catalogs/prompts/versions), consumed by `WorkerConfig.manifestHash`. Not a general corpus hasher. → own-hash (V1), which is a 3-line `hashlib` call. Smaller than feared. | `SsotValidator.java:201-203`; `WorkerConfig.java:57` |
| **V3** | reconciling register "Best known" won't break the generator? | **RESOLVED.** Generated markers are lines 86–104; **all ~9 "Best known" lines are *outside* them** (hand-authored, in corpus blocks). Relabel ("best-achievable-config ablation") + cross-link to the Scorecard is a safe edit; aligns with C-4. | `search-quality-register.md:86,104,131,160,177,200,…` |
| **V5** | can a sweep be held at one commit despite `main` moving? | **RESOLVED + amplified.** `compose()` structurally **refuses** cross-commit sets (unit-tested) → drift is *auto-caught* (re-run), never a silent partial release. **But operational risk is real:** `main` moved **three times this session** (`1b43bbe45→3a9c073b3→6a0163a19`). Discipline: pin a commit, abort+restart the sweep if `git rev-parse HEAD` changes. | `test_release.py::test_compose_refuses_non_cohort_set`; live `quick_health` gitHead drift |
| **V6** | clean worker→Head channel for ortVersion w/o `policy_hash` shift? | **RESOLVED (sizing).** Adding to `session-policies` would shift `policy_hash` (it's hashed → cohort-identity change for all runs). The clean path is a **retained-raw snapshot** field (`status_snapshot`/`debug_state_snapshot` are in `_VOLATILE_FIELDS` → retained, not hashed), fed by a worker→Head gRPC report. Confirms it's **bigger than a field-add** → deferral justified. | `manifest.py:51-60,277,306`; `RemoteKnowledgeClient.java:1149-1186` |
| **V4** | remaining corpora comparable + **presentable** under hybrid at one HEAD? | **PARTIALLY RESOLVED — live run BLOCKED.** Mechanism low-risk (hybrid already green+`comparable` on scifact/courtlistener). The high-value sub-question — *does production-default hybrid score presentably on **multilingual** (the decision-1 wedge), or tank like legal did (0.62)?* — is **unanswered**: the shared dev stack is **actively held by another agent** (`quick_health` verdict `CONTENTION`, fresh lease), and the one-stack rule forbids contending for the shared GPU without coordination. | `quick_health`: holder `e92511a0`, verdict CONTENTION, gitHead `6a0163a19` |

**Design deltas from this pass:**
- **V1/V2:** checksum is an own `hashlib` sha256 over the local corpus files (mixed) / `ir_datasets_id`+qrels-hash (BEIR) — NOT the repro seam. Small, clear.
- **V5:** the canonical sweep needs an explicit **HEAD-pin + abort-on-drift** discipline; in this fast-moving shared `main`, that's the dominant operational risk (not the code).
- **V4 (open):** the **multilingual-under-hybrid value question** is the U2-analog for the wedge — must be measured before the canonical release is published. Blocked on stack availability.

## Confidence rating — remaining work: **6.5 / 10** (critical)

- **Checksum (③): ~8/10.** Strategy is clear and small (own sha256); the only refutation (repro-seam reuse) made it *simpler*, not harder.
- **Register reconciliation (④ deviation): ~8.5/10.** Safe hand-authored edit, markers verified to exclude it, form aligns with C-4.
- **Canonical release (④ realization): ~5/10.** Code path is proven (compose works, drift auto-caught), but **two live unknowns gate a *presentable* release**: (a) the **multilingual-under-hybrid** value question (V4, blocked — could be a U2-style surprise), and (b) **operational HEAD-stability** in a `main` that moved 3× this session. Neither is a code risk; both are "will the deliverable be good + producible" risks.
- **ortVersion (flagged): ~6/10.** Feasible, sized (worker→Head gRPC into a retained-raw field), but a real chunk for a minor field — genuinely the user's proportionality call.

**Reading:** confidence to *build* the remaining mechanics is high (~8); confidence the canonical release *lands well without surprises* is held to ~5 by the unmeasured multilingual-hybrid value and the volatile shared `main`. The blocked V4 is the single thing keeping this from ~8 overall.

---

# Remaining work — implemented (2026-06-21)

> Closes outcomes ③ & ④ + the ortVersion follow-up. All extend-existing-infra (the two feared
> assumptions collapsed in our favor during de-risking).

**③ Corpus checksum — DONE (revived the dead seam).** `run.py::_get_corpus_identity` now computes a real
`signature`: `mixed/golden` = `sha256(corpus.jsonl + qrels/test.tsv)`; BEIR = `sha256({ir_datasets_id,
qrels})` (`.txt` files materialize after ingest). Reuses `manifest._sha256_canonical`; env var still
overrides. The composer's `_corpus_source` already reads `signature` into `corpus_source.sha256` → **zero
composer change**. Extended `test_run::TestGetCorpusIdentity` (computed mixed/beir signatures, env override, content-change sensitivity).

**④ Register reconciliation — DONE.** Added one reconciling note after the Release Scorecard contrasting
the two number-types: the Scorecard is the *production-default projection*; the per-corpus "Best known"
lines are *best-achievable-config ablations* (often `full`-mode), kept as a research log — they differ by
design (legal `full` 0.925 vs `hybrid` 0.620). Resolves the dual-number confusion (§C-4). Outside the
generator markers → CI `--check` stays green.

**ortVersion (U7) — DONE worker-side, debug-only scope (no new proto field, no status-contract change).**
Worker `GrpcHealthService` puts `ort.version` into the existing `HealthCheckResponse.effective_config`
map (`OnnxSessionCache.ortVersion()`). **Correct-scope note (a misstep caught + fixed):** I first put
`effectiveConfig` on the *shared* `HealthNodeView` — but that record is in the **`/api/status` wire
contract** (`StatusWireContractConformanceTest`), so it polluted the public contract. Reverted; moved
`effectiveConfig` to the **debug-only `WorkerDebugView`** (explicitly excluded from the status wire
surface). It surfaces at `/api/debug/state` → `debug_state_snapshot.worker.effective_config` —
**retained, un-hashed** (no `policy_hash` shift), no public-contract expansion.
`release._hardware_projection` reads `ort_version` from there; `cuda_version` from the Head-side
`inference-status` constant; the dead Head-side `gpu.ortVersion` (always-null) was removed.
`debug-state.schema.json` regenerated via `updateSchemas` (status-response EOL churn reverted).
`GrpcHealthServiceTest` + `StatusRecordSchemaTest` green. **Live-verified:** a fresh manifest carries
`worker.effective_config["ort.version"] = 1.24.3` (old `health_check` path absent — clean revert).

**Verification (stack-independent — all green):** jseval `pytest` (run/release/gate, 30 targeted incl. the
extended corpus-signature + projection tests); `:modules:{app-api,worker-services,app-services,ui,ort-common}`
compile + `spotlessJavaCheck` + `pmdMain`; `app-api:test StatusRecordSchemaTest` + `worker-services:test
GrpcHealthServiceTest`. **Browser: N/A — verified** by grep: the only UI consumer (`BrainSurface.ts`) reads
`inference.gpu.{vramDescription,cudaAvailable}` (unchanged); it renders none of the added fields
(`cudaVersion`/`ort.version`/`effective_config`), and `check-wire-schema-types-regen` covers only
`SSOT/schemas/*` (not app-api `debug-state.schema.json`) → no frontend regen, no UI change.

**④ realization (canonical release) — IN PROGRESS (stack phase):** sweeping the ratchet-pinned corpora at
one pinned HEAD (`6a0163a19`)/hybrid + a `full`-courtlistener ablation; then compose → empty
`fallback_baselines` → regen scorecard → CI check. Live results from the completed runs:

- **Live-verify PASSED (end-to-end):** every fresh manifest carries a populated `corpus_identity.signature`
  (revived seam ✓) and **`ort.version` = `1.24.3`** via `effective_config` → `/api/debug/state` (the U7
  worker-side path ✓), plus `cuda_version` = `12`. ③ and U7 are proven live, not just unit-tested.
- **V4 RESOLVED — POSITIVELY (no multilingual surprise):** production-default **hybrid** is on par with
  full-mode on multilingual — miracl-de **0.726** (full best-known 0.734), miracl-fr **0.700** (full 0.706);
  scifact **0.747**. Contrast legal: hybrid **0.605** vs full 0.925. **So the decision-1
  "multilingual-competitive without per-language tuning" wedge holds under the production default**;
  *legal is the lone config-sensitive corpus* (needs `full`). This sharpens the U2 call: option (a)
  (hybrid headline + `full`-legal ablation) is well-justified — only legal needs the ablation. (Side-by-side,
  our hybrid also beats the published MIRACL baselines — fr 0.700 vs BM25 0.183 / mDPR 0.435 / hybrid 0.523,
  dev split — though dev≠test is footnoted.)

## ④ realization — FULLY DONE (5-corpus canonical release; fallback emptied)

> Supersedes the earlier 4/5 state. A clean single-commit sweep (this pass) achieved the full cohort.

The canonical release `scripts/jseval/release.v1.json` (`release_id canonical-691d5c5a0-hybrid`,
`config_cohort_key a5fc48bb436f`) is composed from **all 5 ratchet-pinned corpora at one commit
(`691d5c5a0`)** — scifact (0.751), courtlistener (0.620), enron (0.740), miracl-de (0.725),
miracl-fr (0.701), all hybrid, all `comparable`, all with populated `corpus_source.sha256` (③), the full
hardware projection incl. **ort_version 1.24.3** via the finalized `worker.effective_config` path (U7), and
cited external baselines side-by-side (②). The **courtlistener `full` (0.971) is recorded as a labelled
`ablation`** (U2 option (a) — hybrid headline, `full` legal as ablation). The ratchet pointer's
**`fallback_baselines` is now `{}`** — every floor projects from the release, none is hand-typed; proven
live: all 5 `relevance-gate` runs pass reading their floor from the release (incl. **enron 0.740**, formerly
the lone fallback; courtlistener 0.620 re-rooted from the old `full` 0.925). The register scorecard projects
all 5; `check-release-baseline-sync` green.

**V5 did NOT bite this time:** `main` held stable for the ~35-min sweep (`PINNED == NOW == 691d5c5a0`), so
the retry-discipline wasn't needed — but it remains the documented mitigation if a future sweep splits (the
cohort guard makes a split safe, never a bad release).

**Remaining-work items closed this pass:** **P1** — `cmd_release --latest-per-dataset` now groups by
`config_cohort_key`, picks the dominant cohort, and warns+excludes a split (pure helper
`release.select_dominant_cohort`, tested). **W2/CI** — `check-release-baseline-sync` is now **wired into
`.github/workflows/ci.yml`** (the no-fork guard is enforced, not honor-system; `check-workflow-triggers`
green). **P3** — `debug-state.schema.json` renormalized to LF.

**Status:** outcomes ② (external baselines), ③ (checksum), ④ (re-rooting **and** full fallback-emptying),
and U7 (ortVersion) are **complete and live-verified**; the no-fork gate is CI-wired. Browser validation is
N/A (verified — no `ui-web` surface consumes any 623 artifact). Decision-gated U2 = option (a) (taken; the V4
multilingual finding supports it); F-α exact-match refinement remains a v2 option. **Reproducibility caveat
(W3):** the release's `git_sha` is the current working-tree HEAD; a *fully* reproducible canonical release
is reproducible from the committed branch once this work is staged (commit is the user's call). Deferred
extensions (model card / in-app surface / release-diff / continuous-CI / extraction sibling / community
repro / public repo+badge / generalized provenance gate) remain the recorded growth path, not built.

## Closing notes (2026-06-21)

- **`scripts/jseval/release.v1.json` is a development DEMO** that proves the full pipeline end-to-end
  (compose → re-rooted gate → register projection → CI check, all green). It was composed during the
  build, so its `hardware.ort_version` (1.24.3) was read via the *intermediate* ort-path; the finalized
  path (`worker.effective_config`) is separately live-verified. **The path- and code-consistent CANONICAL
  release is a post-commit step:** commit this work, then run one clean single-commit `jseval` sweep of the
  pinned corpora (HEAD-pinned; abort+restart on a mid-sweep `main` move — V5) and re-compose. (The code is
  uncommitted, so a truly *reproducible* release requires committing first regardless.)
- **Pre-existing failures (NOT 623), logged to `docs/observations.md`:** `StatusWireContractConformanceTest`
  (`migrationSource` drift in another agent's migration record) and the `worker-services` VDU/PDF eligibility
  test, plus the earlier `ssotValidateExec` `SSOT/schemas/*` WIP. 623 touches none of those subjects; the
  repo-wide `build -x test` is red only on this cross-agent WIP.
- **Nothing is committed** — staging + the U2 strategic call are the user's.

---

# Future directions (research theorization, 2026-06-21) — what to build on 623

> Pure-research/ideation pass (docs-only; no code). Goal: surface what the implemented
> benchmark-release infrastructure *could* become — polish / simplify / extend / new UX. Grounded in a
> survey of mature eval ecosystems (MTEB, HELM, eval-CI tooling) + ML transparency artifacts (model
> cards, datasheets, nutrition labels) + AI-transparency UX practice. No specific goal; all viable; no
> rush; app is pre-production with no users (so wild ideas are fine, trust/velocity matter more than
> public leaderboards). Citations inline.

## Two unifying lenses (the insight)

The release object turns out to be an instance of **two recognized things at once** — which is why it
extends so far:

1. **It is a "model card / nutrition label" for the search engine.** Model cards (Mitchell et al. 2018,
   arXiv 1810.03993 — explicitly "a nutrition label for predictive models"), datasheets (Gebru et al.),
   Dataset Nutrition Labels, FactSheets, and System Cards all encode the *same* payload our release
   already holds: metrics + confidence + **limitations/coverage** ("what it can and cannot do") +
   provenance + honest caveats + external comparisons. There is even work on **auto-generating** cards
   (arXiv 2405.06258). → The release is a model card we already compose; most "extend"/"UX" ideas are
   just *rendering it for a new audience*.
2. **It is the substrate for Eval-Driven Development (EDD).** EDD is a named 2025 practice
   (evaldriven.org; AI Technology Radar): repeatable evals as the *core* dev step, version-controlled
   with changelogs, **two suites** (quality benchmark + regression gate), block regressing PRs, trends
   not snapshots. 623 already supplies the pieces: release = the quality-benchmark suite, the
   re-rooted ratchet = the regression gate, the cohort-identity = the version-controlled identity, a
   release-diff = the changelog. → The "extend" ideas that touch CI/velocity are just *closing the EDD loop*.

## POLISH (refine what's built)

- **P1 — Graceful cohort handling in `jseval release`.** Auto-detect the dominant `config_cohort_key`,
  **warn on a cross-commit split**, and offer auto-pin-HEAD + abort-on-drift — turning the V5 manual
  discipline (which bit this very session: `main` moved mid-sweep, splitting enron off) into tooling.
  (MTEB does exactly this kind of submission-time validation — arXiv 2506.21182.) *Highest-value polish.*
- **P2 — Promote `config_cohort_key` into `manifest.py` + the `manifests.jsonl` index** (T-4 deferred) so
  `jseval release --config-cohort <hash>` discovers member runs without opening every manifest.
- **P3 — EOL-churn fix** for regenerated schemas (`.gitattributes` / generator line-endings) so
  `updateSchemas` stops dirtying `status-response.schema.json` spuriously.

## SIMPLIFY

- **S1 — Retire the `fallback_baselines` transition** once a clean full sweep exists; add an explicit
  release `status: draft|green` so the demo-vs-canonical distinction is a field, not tribal knowledge.
- **S2 — One projection core.** `register-headline-sync`, the ratchet projection, and the CI sync-check
  each "project a release into a derived view"; factor the shared projection step once (AHA: only if they
  truly share a reason to change — they do: the release schema).

## EXTEND (highest-leverage, by frame)

*Transparency / artifact frame:*
- **E1 — Auto-generate a search-engine model card from the release.** The release → a published
  MODEL CARD / nutrition label in a recognized format (metrics + confidence tiers + coverage/limitations
  + provenance + cited baselines + honest caveats). This *is* the research-channel's "publishable
  artifact" (decision-3) in a format reviewers already trust. (arXiv 1810.03993 / 2405.06258.) **Top pick.**
- **E8 — Public benchmark repo + reproducibility badge + venue.** Release + corpus download-scripts +
  methodology, published; pursue an **ACM "Results Reproduced"** badge (project.inria.fr/acmmmreproducibility)
  / the ECIR reproducibility track; submit to BEIR/MTEB for instant comparability. The release + checksums
  are already an artifact-evaluation-ready bundle.
- **E7 — Measurement-provenance everywhere (tempdoc 625).** Every asserted number (docs/marketing/UI)
  traces to a release; a provenance gate fails when a number lacks a cohort trace. The projection-not-fork
  thesis generalized.

*Eval-Driven-Development frame:*
- **E2 — Release-diff / quality timeline.** Diff two releases (commit A→B) → a per-corpus "quality
  changelog"; a Braintrust-style **PR comment** ("scifact +0.4%, courtlistener −1.1%") + a trend view.
  Builds on the manifest index + release object. (Braintrust GitHub Action posts exactly this —
  braintrust.dev/articles/best-ai-evals-tools-cicd-2025.) **Top pick.**
- **E3 — Continuous-quality CI (Q-010 elevated).** Wire the existing `search-engine-hint` → nightly/PR
  eval → release → ratchet, closing the EDD loop: the release becomes the standing quality contract that
  blocks regressing engine changes. (deepeval = "pytest for evals"; the gate+track pairing pattern.)

*Reproducibility-rigor track (makes the artifact credible across hardware):*
- **E5 — Community reproducibility submissions → empirical cross-hardware band.** The Pyserini/MTEB model:
  third parties submit `(hardware, number)`; grow a *measured* cross-hardware tolerance, resolving the
  deferred two-variances question (T-2) empirically instead of by a hand-set band.
- **E6 — Pre-built, checksummed indexes** for license-clean corpora (CourtListener public-domain, MIRACL
  CC-BY-SA) → tight cross-hardware reproduction (the Pyserini convenience layer; decision-4 / Alt-3).

*Domain-extension:*
- **E4 — Extraction-quality sibling release (§F).** Reuse the **metric-family-pluggable** release object
  for OCR/VDU quality (WER/CER, route-decision correctness, extraction-latency) — the 607 agent's named
  follow-up; the infra is already shaped for it (the metrics map + the projection thesis). No new machinery.

## NEW UX (the most novel direction — "trust as a product feature")

For a privacy-first, local-first app whose currency is *trust* (strategy E.2), surfacing honest quality
is a product feature, not just dev infra (Deloitte 2025: trust-as-a-feature drives engagement;
Waymo "shows what the car sees", Netflix "because you watched"; CLeAR = Comparable/Legible/Actionable/Robust).

- **U1 — In-app "Search Quality" surface (the nutrition label, rendered).** A new `shell-v0` surface
  (sibling to `GovernanceView` / `SystemSelfView` — ~20 surfaces already exist, so the pattern + home
  are there) rendering the release for the *user*: "your engine scores ~0.75 on standard academic search,
  is multilingual-competitive without per-language tuning, here is **what it does NOT measure**
  (extraction/OCR), reproducible at commit X on your GPU class." Honest, legible, with the HELM principle
  of *showing trade-offs and incompleteness* rather than one number. **Top pick — most novel, high-leverage
  (release object → UI), fits the privacy/trust thesis.**
- **U4 — HELM-style multi-metric view.** Not a single nDCG — the multi-metric picture (nDCG/P@1/R@10 +
  latency + coverage) so trade-offs are visible (HELM: multi-metric foregrounds failures a single number hides).
- **U2 — Per-result "why trust this" affordance.** A subtle search-result tooltip tracing to the engine's
  measured quality + honest caveats (the 625 provenance idea, user-facing; Waymo/Netflix "explain why" analog). *Speculative.*
- **U3 — Developer release dashboard.** Local view of releases over time (the E2 diff/trend) + regression
  alerts — the EDD loop made visible to contributors.

## Prioritization (value × leverage on the release object)

| Rank | Idea | Why it tops the list |
|---|---|---|
| 1 | **E1 + U1 — model card / nutrition label (publish + render in-app)** | The release *is* a model card; one artifact serves the research channel AND becomes a trust UX feature. Highest novelty + leverage. |
| 2 | **E2 + E3 — release-diff + continuous-quality CI** | Closes the EDD loop; a proven shape (Braintrust PR-diff); turns the ratchet into a living quality contract. |
| 3 | **E4 — extraction-quality sibling** | Concrete, infra-ready (pluggable metrics), satisfies a standing request (607). |
| 4 | **E5 + E6 + E8 — community repro band + pre-built indexes + public repo/badge** | Makes the external artifact *credible* and resolves the deferred cross-hardware question empirically. |
| 5 | **P1 — graceful cohort handling** | Cheapest fix to the friction that actually bit this session (V5 split). |

---

# Long-term design (theorization, 2026-06-21) — the release as a canonical measurement record

> Settles the *correct* long-term shape for the tempdoc's problem + its remaining work/ideas. Scope-matched:
> it adds only the structure the eval-benchmark problem actually requires, and it **conforms to an existing
> seam rather than inventing a parallel one** (the design investigation found the seam already built).

## What already exists (investigated — conform, don't replace)

The codebase already has the exact shape this design needs: the **canonical-record + governed-projections
seam** (tempdoc 553/559), realized as the `execution-surfaces` register (`governance/execution-surfaces.v1.json`)
+ the `execution-surface` gate. Its anatomy:
- a **`canonicalRecord`** (`SearchTrace`) — the single source of truth for "what the pipeline did";
- **`siblingRecords`** (`ContextCitation`) — a *second* canonical record for a fact that **shares no field
  and never co-occurs** with the first; 559's ruling: *do not* fuse them into one mega-record — the single
  authority is "one level up" (*what records exist + who projects them*);
- **`surfaces[]`** — every code site that produces / carries / consumes / projects a record, each with a
  `guard` (test/gate/exempt); and a **gate** that fails the build on an unregistered referencer.
- It belongs to a *family* of such registers (`operation-surfaces`, `contract-surfaces`, …) and to the
  `canonical-authority-and-projection` seam tempdoc 622 names. **This is what 623 must conform to.**

## The design (general)

**The benchmark release is a canonical measurement record; everything that asserts a quality number is a
governed projection of it; and the record stays true as a standing property by regenerating on engine
change.** Three elements — and *only* these, because they are what the problem requires:

1. **Canonical record = the release** (`release.v1.json`): a config-cohort scorecard, **metric-family
   parametric**. This parametricity is not a convenience — it is the *"one level up"* authority from 559:
   a **retrieval release** (nDCG family) and an **extraction release** (WER/CER/route family) *share no
   metric and never co-occur*, so they are **sibling records**, exactly like `SearchTrace`/`ContextCitation`
   — not one mega-release. The metric-pluggable schema already built **is** that one-level-up authority, so
   no new structure is needed to admit the extraction sibling (§F/E4) when it comes.
2. **Governed projections = the consumers.** The ratchet floors and the register scorecard are projections
   of the release (built); `check-release-baseline-sync` is their fork-prevention gate (the measurement-domain
   analog of the `execution-surface` gate). **⚠ Honesty correction (confidence pass #3, W2): this gate is a
   *script*, not yet CI-wired** — it exists and is correct, but nothing runs it automatically, so the no-fork
   guarantee is currently honor-system/local, not enforced. **Wiring the static `check-release-baseline-sync`
   into CI is concrete, small remaining work** (it is pure-static — no dev stack — unlike the relevance
   ratchet, which is deliberately stack-manual). Future consumers — a published model card (E1), an in-app
   quality surface (U1), business-doc numbers (E7) — are *more projections of the same record*, never new authorities.
3. **Standing property = the regeneration loop.** The tempdoc's purpose ("not a heroic run that re-rots")
   is met by closing engine-change → eval → release → projections. Today this is *minimally and only
   manually* satisfied (the `search-engine-hint` nudges a re-run; the static no-fork check exists but is
   **not yet CI-wired** — W2); fully closing it to automation is the Eval-Driven-Development loop (E3) — an
   extension, not core structure.

## Scope judgment (why this size, not bigger)

The present problem has **two** projection consumers + a CI gate + a metric-pluggable record. That is
*already* the canonical-record+governed-projections seam at the right size. So the long-term design is
mostly **recognition + conformance**, plus closing the regeneration loop — **not** new machinery:
- **No release "surface register" is built now.** With two consumers, the hard-coded projections + the one
  CI gate suffice; a full `release-surfaces.v1.json` register (a sibling of `execution-surfaces`) is the
  *correct* structure **only once consumers multiply** (model card + UI + docs) — recorded as the growth
  path, not built (YAGNI; mirrors 553's own staged rollout).
- **No sibling-release built now.** The schema already admits it; the extraction release (E4) is a *use*
  of the existing authority, deferred to its owner (§F).
- **No generalized measurement-provenance gate.** That is tempdoc 625, deliberately deferred until the fork
  bites a second time. 623 stays the eval-benchmark instance.
- The **remaining work** dissolves into this design: enron's `fallback_baselines` entry and the post-commit
  canonical release are *transition artifacts the regeneration loop empties*; the V5 cohort-split is a
  *tooling* refinement of the equivalence-key (auto-detect/pin — P1), not missing structure (the
  `config_cohort_key` already defines "one release"; promoting it into the manifest+index — P2 — is the
  discoverability step, warranted only when releases multiply).

## Design reach

**This design is an instance of a principle the system already names — conform, don't fork.** The release
is a `canonicalRecord`; the ratchet/register/(future cards/UI) are `surfaces`; `check-release-baseline-sync`
is the gate; retrieval-vs-extraction are `siblingRecords` (559's "share-no-field → one level up" rule). The
right long-term home for a release-projection register, if consumers multiply, is **a new member of the
existing surface-register family** (beside `execution-surfaces`/`operation-surfaces`), governed by the
discipline-gate kernel — *not* a parallel mechanism.

**The recurring shape, named plainly:** *a single canonical authority + governed/derived consumers + a gate
that makes forking structurally impossible.* Instances already in the tree: `SearchTrace` (553), evidence
`ContextCitation` (559), agent telemetry-as-projection (622), this benchmark release (623) — and, in the
current engine-reliability cycle, the indexing **reconciler-as-single-authority** with the watcher demoted to
a fast-path (626), the **single recovery-contract authority** (627), and the **one declared
detect→classify→policy dispatcher** (628). The codebase is *actively converging* on this shape (626 even
frames itself as an instance of 627's "Observation-Actuation Closure"). 623 is its **measurement-domain**
instance.

**The generalization (recorded, not built):** *every externally-asserted measurement is a projection of a
cohort-identified reproducible run; a hand-maintained table of numbers is a fork that drifts.* Candidate
scope beyond the eval benchmark: the register's hand-authored ablation tables, business/marketing docs that
quote a metric, and the strategy docs' conflated "92% / 62%" (tempdoc 624 — the extreme fork with no cohort
identity at all). **Existing violations:** those three already violate it. Per **tempdoc 625** this is
deliberately *not* generalized into an enforcement gate now — the trigger is the *second* place the fork
bites, or a user decision to harden it. Recording the principle + scope here (separate from building it) is
the deliberate move that captures the insight without premature abstraction.

---

**Cross-refs:** MTEB maintenance ([arXiv 2506.21182](https://arxiv.org/html/2506.21182v1)) · HELM
([crfm.stanford.edu/helm](https://crfm.stanford.edu/helm/)) · Model Cards
([arXiv 1810.03993](https://arxiv.org/pdf/1810.03993)) · auto-gen cards ([arXiv 2405.06258](https://arxiv.org/pdf/2405.06258)) ·
Eval-Driven Development ([evaldriven.org](https://evaldriven.org/)) · eval-CI/PR-diff
([braintrust.dev](https://www.braintrust.dev/articles/best-ai-evals-tools-cicd-2025)) · ACM repro badges
([project.inria.fr/acmmmreproducibility](https://project.inria.fr/acmmmreproducibility/)) · trust-as-a-feature
(Deloitte 2025 Connected Consumer) · CLeAR framework (Shorenstein Center).

---

# Confidence pass #3 (2026-06-21) — de-risking the remaining work

> Read-only investigation (the live worktree experiment was deliberately skipped — see W1). Resolves the
> uncertainties before implementing the remaining work (canonical sweep → empty fallback; P1/P3 polish;
> wire the gate). **No feature work.**

| # | Uncertainty | Outcome | Evidence |
|---|---|---|---|
| **W1** | single-commit canonical sweep stays stable | **CONFIRMED real; mitigation found (no experiment needed).** `main` moved 3–4× this session. **Two mitigations:** (A) *retry-until-consistent* — run the sweep, and re-run any straggler corpus whose `git_sha` differs (compose already refuses cross-commit sets, so it's safe) — **the simpler choice**; (B) a pinned worktree gives a stable `git_sha` by construction, BUT only **6 files** under `datasets/` are tracked (≈ just `cord19-qddf`) + `eval-corpora/` is gitignored, so a worktree **lacks the mixed corpora** → needs corpus-staging. The git_sha-stability is tautological, so the live worktree experiment would only validate the inferior path; skipped. | `git ls-files datasets/` = 6; `git check-ignore eval-corpora/`; `_paths.py:52` REPO_ROOT cwd-based |
| **W2** | the fork-prevention gate runs in CI | **CONFIRMED GAP — design overstated.** `check-release-baseline-sync` / `register-headline-sync` / `relevance-gate` are in **no `.github/` workflow** — un-wired scripts. The no-fork "structurally impossible" / "standing property via the CI gate" claim is honor-system/local until wired. **Corrected in the design above; wiring the static check is named small remaining work** (it's pure-static; the relevance ratchet is deliberately stack-manual). | grep `.github/` → none |
| **W3** | the canonical release is commit-gated | **CONFIRMED (manageable).** `git_sha = git rev-parse HEAD` (`manifest.py:103,268`); `worktree.baseRef:"head"` (`.claude/settings.json`). A *reproducible* release needs committed code — but a worktree/local-branch commit IS a real stable SHA, so the canonical release can be produced from a committed branch even pre-merge. Commit timing is the user's call. | `.gitattributes`/settings; manifest |
| **W4** | the right graceful-cohort policy (P1) | **RESOLVED.** `cmd_release --latest-per-dataset` (`cli.py:2350-2360`) currently picks latest-per-dataset *without* cohort grouping → can mix commits (compose then refuses). Policy: **group latest-per-dataset by `config_cohort_key`, pick the cohort covering the most pinned corpora, warn+list the excluded split** (no silent mixing). Small CLI change. | `cli.py:2350-2360`; `bisection.load_index` |
| **W5** | schema EOL-churn root cause (P3) | **RESOLVED.** `.gitattributes` declares `* text=auto eol=lf` (no `-text` exception for the status/debug schemas). The churn = committed-CRLF schema files vs that rule. **Fix = a one-time `git add --renormalize` of the schema files**, not a generator change. | `.gitattributes:2` |

**Net surprise caught:** W2 — the fork-prevention gate is **not actually CI-wired**, so the design's
"structurally impossible" wording was aspirational. Corrected; wiring the static check added as small
remaining work. Everything else is confirmed-and-manageable (W1 retry-mitigation) or resolved cleanly (W3/W4/W5).

## Confidence rating — remaining work: **8 / 10**

- **Mechanics (canonical sweep, compose, empty fallback, P1, P3, wire the static gate): ~9.** All were
  exercised this session; the only new bits (P1 group-by-cohort, P3 renormalize, the one-line CI wiring) are
  small and clear.
- **Held to 8 by two non-mechanical realities:** (1) **W1 operational** — the single-commit sweep needs the
  retry-discipline in a fast-moving shared `main` (handled, not hard, but adds attempts/coordination);
  (2) **W3 commit-gating** — the *truly-reproducible* canonical release is sequenced after a commit (the
  user's call), so it can't be 100% autonomously closed now.
- **Not a code risk:** the U2 legal-headline strategy (decision-gated) and all deferred extensions are out of scope.

**Reading:** confidence to *build* the remaining work is high; the residual is operational (sweep-in-a-moving-`main`)
and sequencing (commit-first), plus the one honesty fix (CI-wire the no-fork check) — all known and small.
