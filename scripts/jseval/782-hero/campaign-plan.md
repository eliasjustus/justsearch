# 782 hero campaign — the ordered command list

**Authority:** `docs/tempdocs/782-hero-preregistration-protocol.md` §E, **FROZEN 2026-07-28**
(§E.0 / §H). Every parameter below is *read from* the frozen protocol, `cells.v1.json`, or
`scripts/jseval/utility-claim-policy.v3.json`. **Nothing here is a choice made at supervision
time.** A parameter change after the first measured cell voids claim eligibility (§E.2 R5).

**Status: preflight green, launch NOT authorized.** §H BLOCKER-1 was cleared 2026-07-28 by 782
§E.0 **Amendment 1** (`known_schemas` → `["1_hop"]`, a corpus-description correction; thresholds
and strata untouched, `require_all_present` still `true`). `preflight.py` now reports
**36 PASS / 0 FAIL / 6 PENDING**, the 6 PENDING being artifacts this campaign produces. Do not run
any step below until the founder gives explicit launch authorization (§E.6, Step 0f). This file is
a dry driver.

Precedent this mirrors: 624's `scripts/jseval/chain-confirm-v5.bat` and its
`Relaunch pre-registration` section (`docs/tempdocs/624-agentic-retrieval-eval-rebuild.md:4920`).

---

## Constants (frozen)

| knob | value | source |
|---|---|---|
| model | `sonnet` | §E.1; policy `requested_model` |
| conditions | `A,B` | §E.1 (A = file tools only, B = A + JustSearch MCP) |
| seeds | `3` (`seed_ids [0,1,2]`) | §E.1; policy |
| queries per stratum | `20` via `--max-queries 20` | §E.8 |
| cells | 120/stratum, **360 total** | §E.1 |
| paired observations | 60/stratum (`minimum_paired_observations` 54) | §E.1; policy thresholds |
| stratum order | enron-1k → enron-10k → legal-1k (**cheapest-first**) | §E.1 |
| hard cap | **$300 USD** (binding limit is USD, never wall-clock) | §E.1 |
| concurrency | from each calibration's `concurrency`; target `6` | §E.1 (calibration-time) |
| per-cell `--max-budget` | `ceil2(1.6 × p95_cell_cost_usd)`, floor `$0.50`, **cap `$2.00`** | §E.1 (calibration-time) |
| contamination class | `private-synthetic` | §E.2 R3; policy |
| confidence tier | `C` | §E.1 lineage (624) |
| port | `33221` | 624 chain |
| run dir | `scripts/jseval/782-run-<YYYY-MM-DD>-hero/` | §E.6 |

Environment for the whole campaign window (one shell, never re-entered with different values):

```
DISABLE_AUTOUPDATER=1        # 624 incident #6: a mid-campaign CLI bump voids every later cell
PYTHONUTF8=1                 # Windows cp1252 vs Inspect's output
INSPECT_DISPLAY=none         # Inspect's rich display crashes when stdout is redirected
PYTHONPATH=<worktree>/scripts/jseval
JSEVAL_HEALTH_TIMEOUT_SEC=600
```

Record `claude --version` at start and **re-check it at every phase boundary** (§E.6).

---

## Step 0 — pre-launch gates (no spend except 0b)

### 0a. Mechanical preflight (zero cost, no backend)

```
python scripts/jseval/782-hero/preflight.py
```

Must print `PREFLIGHT PASS`. Any FAIL is §E.6 stop rule 3. Covers: active policy is
`agent-utility-public-v3` and its `required_strata` are §E.1 verbatim; all three members
`fully-certified`; the six §E.2 P1 signature pins; `field_selectivity` PASS per cell; the §E.8
qid digests reproduce; datasets resolvable and their first 20 rows aligned with the committed
gold; schema coverage vs `known_schemas`; the closed-book tier; `claude` CLI; budget guard;
frozen scorer identity.

### 0b. Sonnet closed-book measurement — **required, §H FINDING-2**

The committed 781 certifications measured closed-book at **haiku**. The policy gate
`closed_book_at_hero_tier` accepts that (it checks the number and that a model is *named*, not the
tier — `utility_claim_policy.py:497-506`), but **§E.2 P3 is the stricter, frozen bar: `0.000` at
sonnet.** Produce it before launch, per stratum:

```
python -m jseval corpus-certify --dataset mixed/en-email-enron-raw-1k-verbose  --model sonnet --threshold 0.0 --concurrency 8
python -m jseval corpus-certify --dataset mixed/en-email-enron-raw-10k-verbose --model sonnet --threshold 0.0 --concurrency 8
python -m jseval corpus-certify --dataset mixed/en-legal-clerc-1k-verbose      --model sonnet --threshold 0.0 --concurrency 8
```

No tools, no corpus in context — pure parametric recall (`corpus_certify.certify_corpus` →
`closed_book_filter`). Cost is charged to the $300 cap and logged in `spend-ledger.md` as its own
phase. **Any non-zero result on any stratum → §E.6 stop rule 3, do not launch.** Copy each
`metadata.json` closed-book block into the run dir as the campaign's P3 evidence; the committed
certification files are **not** edited.

### 0c. §E.3 derivability audit (zero cost)

The frozen scorer is `substring_scorer` (§E.3 / §E.7 row 9), so the branch that runs is the
**derivability audit**, not the judge-agreement spot-check. For each of the 60 selected qids
(20 × 3 strata), assert the gold answer string is not derivable from any token of the question or
from any entity name in the corpus metadata (the `corpus_generate.py:320-323` naming-leak class,
764 §E). **Floor: 0 derivable qids.** A hit means that qid is replaced from the committed pool
*before* launch and the replacement recorded — note that a replacement changes the §E.8 selection
and therefore requires a §E.8 amendment under the dated-amendment convention (pre-first-cell only).
Output committed to the run dir.

### 0d. §E.2 P4 probe-self-leak checks (zero cost, four outputs to the run dir)

1. **Arm symmetry** — `prompt_template_hash` and instruction text byte-identical across A and B.
2. **No gold text in the prompt** — no gold answer string, gold doc id, or gold-only field in
   either arm's context for any selected qid.
3. **Answer-key path isolation** — `--corpus-dir` resolves to the exploded `corpus-dir/` only;
   `qrels/`, `fabricated-queries.json`, `fabricated-meta.json`, `commitment.v1.json` are outside
   every readable root. Verified by path check now and by `leak_suspect_tool_calls == []` at compose.
4. **Shipped-config parity** — `captured_search_config` equals the shipped post-775 default cohort.

### 0e. Environment gates (624 chain STEP 0)

```
python scripts/jseval/phase2-cli-check.py          # claude CLI resolvable; no cost, no model call
netstat -ano -p tcp | findstr "LISTENING" | findstr ":33221 "   # must be EMPTY
```

Plus: clean committed tree (`git status --porcelain` empty → `git_dirty: false`, §E.2 R1), and
the MCP config for arm B written once:

```
{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:33221/mcp"}}}
```

`"type":"http"` is **mandatory** — a url-only entry is silently dropped (624 F-027).

### 0f. Founder launch authorization

Founder-gated (§E.6). Record it in the §E.6 ledger, never in §E.0. **Not granted at freeze.**

---

## Step 1..3 — per stratum, in cheapest-first order

Run strata **sequentially**, in `cells.v1.json` `order`: **1 enron-1k → 2 enron-10k →
3 legal-1k**. The order is frozen because the guard's max-extrapolation over-projects when the
most expensive stratum calibrates first (624 confirmatory amendment 1); the fix is order, not cap.

For each stratum, with `RUN=scripts/jseval/782-run-<DATE>-hero`, `CELL` = the dataset slug's last
segment, `OUT=$RUN/$CELL`, `ROOT` = the resolved dataset dir, `CORPUSDIR=$ROOT/corpus-dir`,
`QUERIES=$ROOT/queries.json`, `CERT` / `SIG` from `cells.v1.json`:

### a. Warm the index cache

```
python -m jseval index-cache warm --corpus-dir "$CORPUSDIR" --port 33221
```

### b. Serve + adopt

```
python scripts/jseval/serve-eval-backend.py --port 33221 --index-cache-mode on \
  --corpus-dir "$CORPUSDIR" --ready-file "$OUT/serve.ready" --stop-file "$OUT/serve.stop" \
  --stopped-file "$OUT/serve.stopped" --failed-file "$OUT/serve.failed"
```

Poll for `serve.ready` (condition-poll, no fixed sleep).

### c. Ingest (idempotent safety net)

```
python -m jseval run --base-url http://127.0.0.1:33221 --dataset "mixed/$CELL" \
  --corpus-dir "$CORPUSDIR" --modes lexical --max-queries 1 --pipeline --allow-errors --allow-degraded
```

### d. Fresh **sonnet** calibration

```
python -m jseval utility-calibrate --queries "$QUERIES" --corpus-dir "$CORPUSDIR" \
  --mcp-config "$RUN/mcp-config.json" --base-url http://127.0.0.1:33221 \
  --model sonnet --conditions A,B --seeds 3 --concurrency 6 --output "$OUT/calibration.json"
```

Then derive, per §E.1's frozen rule, and **write both numbers into `spend-ledger.md` before the
launch**:

```
max_budget = clamp(ceil2(1.6 * p95_cell_cost_usd_at_sonnet), 0.50, 2.00)
concurrency = calibration.json:concurrency      # utility-run reads it and overrides the flag
```

`> $2.00` → **STOP and escalate**; a cap raise is founder-gated. Calibrations are stamped with
`git_sha` + `cli_version` and fail closed on drift (758) — never adopt one across a SHA or CLI
change (624 incident #5).

**Expected: `n_dropped_contaminated: 0`.** A non-zero drop rewrites the queries file
(`_calibrated_queries.json`), whose bytes then disagree with the certified `query_gold_sha256`, and
the certified run fails closed with *"corpus_certification rejected: query-and-gold digest
disagrees with queries"*. That is **correct** fail-closed behaviour, not a bug to route around —
it means the certified query matrix no longer holds. Treat it as §E.6 stop rule 3.

### e. Running budget guard — **before every stratum launch**

```
python scripts/jseval/step2-budget-guard.py --glob "$RUN/*/calibration.json" --cap 300 --total 3
```

Non-zero exit → **ABORT the whole campaign** (§E.1: no partial-value ladder; a 2-stratum record
cannot promote, so partial spend buys nothing). Record the projection in `spend-ledger.md` and the
firing in `incident-ledger.md` — a fired guard is evidence, not an obstacle.

### f. The confirm run (root mode, claim-grade identity)

```
python -m jseval utility-run \
  --queries "$QUERIES" --max-queries 20 \
  --corpus-dir "$CORPUSDIR" --corpus-root "$ROOT" \
  --corpus-certification "$CERT" --corpus-signature "$SIG" \
  --mcp-config "$RUN/mcp-config.json" \
  --model sonnet --conditions A,B --seeds 3 --concurrency 6 \
  --max-budget <derived> --calibration "$OUT/calibration.json" \
  --dataset "mixed/$CELL" --contamination-class private-synthetic --confidence-tier C \
  --log-dir "$OUT/logs" --output-dir "$OUT/out"
```

- `--corpus-dir` stays the leak-safe exploded subdir; `--corpus-root` is its parent; the certified
  signature is the ROOT signature. Root mode enforces corpus-dir derivation and **supersedes** the
  declared-mode `step2-corpus-args` preflight (not used).
- `--max-queries 20` is the frozen §E.8 selection. It truncates the *samples* only — the certified
  digest is taken over the whole file's bytes, so certification holds
  (`agent_utility_inspect.py:1796-1824`).
- No `--agent-env`.

### g. Serve down, then next stratum

Signal `serve.stop`, poll for `serve.stopped`. **Re-assert §E.2 R1** (one
`search_config_cohort_key`, one `agent_cohort_key`, one `cli_version`, `git_dirty: false`) before
starting the next stratum — and **after any resume**.

**Resume:** re-invoke the identical command with the **same `--log-dir`** and **byte-identical task
args**. Inspect `eval_set` resumes per-cell on `sample_id`. A changed task arg creates a new run and
**splits the cohort** — that is a failed relaunch, not a resume (§E.6). Two failed relaunches of any
stratum → campaign STOPS, records `rejected`.

---

## Step 4 — judge overlay (post-hoc, no paid API call)

The primary scorer is `substring_scorer` (EM). The overlay is the hybrid **EM → local-LLM judge**
re-score over completed logs; EM stays a high-precision auto-PASS and only EM-misses reach the
judge. It changes no cell and costs no paid call — the local model serves it (`ai_activate`).
Judge-family control is intact: a different model family from the agent under test is the
self-preference control.

```
python -m jseval utility-judge "$RUN/<cell>/logs" \
  --judge-url http://127.0.0.1:33221 \
  --contamination-class private-synthetic --confidence-tier C
```

Run once per stratum log dir; each writes its `judge-overlay.json` next to the logs. Do **not**
pass `--output-dir` here — the single close-out compose in Step 5 is the promoting record (§E.2 R4).
`--calibrate` produces the agent-substitute-rater kappa dry run; it is **not** a validated
judge-accuracy figure and must not be reported as one.

---

## Step 5 — close-out: ONE compose, all three strata

§E.2 R4: **all three strata compose in a single invocation.** No retroactive stratum substitution;
a voided stratum is rerun inside the same cohort window or the campaign records `rejected`.

```
python -m jseval utility-recompose \
  --log-dir "$RUN/en-email-enron-raw-1k-verbose/logs" \
  --log-dir "$RUN/en-email-enron-raw-10k-verbose/logs" \
  --log-dir "$RUN/en-legal-clerc-1k-verbose/logs" \
  --judge-overlay "$RUN/en-email-enron-raw-1k-verbose/logs/judge-overlay.json" \
  --judge-overlay "$RUN/en-email-enron-raw-10k-verbose/logs/judge-overlay.json" \
  --judge-overlay "$RUN/en-legal-clerc-1k-verbose/logs/judge-overlay.json" \
  --contamination-class private-synthetic --confidence-tier C \
  --output-dir "$RUN/combined"
```

`--judge-overlay` is one-for-one with `--log-dir` and applies only to `--log-dir` input. The
resulting record's `claim_verdict` **is** the verdict (§E.5) — nobody re-reads it into something
better.

### Step 5 acceptance checks (read off the composed record, not re-derived)

- `claim_verdict.status` ∈ {`accepted`, `rejected`} with `outcome` ∈ {`benefit`, `adoption-only`,
  `inconclusive`} — the §E.5 ladder.
- Policy gates all green, in particular `verified_tool_surface` (rate ≥ 0.9, exactly one observed
  hash, **any single different hash fatal regardless of rate**), `no_leak_suspect_cells`,
  `completion_triple_reported`, `closed_book_at_hero_tier`, `schema_strata_reported`.
- Thresholds from the policy, not restated: `minimum_seeds` 3, `minimum_paired_observations` 54,
  `maximum_exclusion_rate` 0.15, `minimum_paired_retention` 0.7, `minimum_excluded_jaccard` 0.5,
  `significance_alpha` 0.05, `minimum_adoption_rate` 0.9.
- §E.4 derived JSONs written to the run dir: rank-of-gold distribution; evidence/span carriage;
  read-amplification after-measure (**descriptive only — no delta may be promoted**); exhaustion
  ledger with the USD-exhausted vs wall-clock-cancelled split (null costs segmented, never imputed);
  duration ledger with an explicit censoring flag.
- Every headline number carries its 95 % cluster bootstrap CI over queries (qids resampled with
  their 3 seed replicates as a cluster, 10 000 resamples, BCa, `random.Random(2026)`) **beside** the
  exact-McNemar p, plus the power-honesty line ("no effect detected at n = 60").

### Publication

Founder-gated, per action (766 §G decision 3). Drafting a sentence is not publishing it.

```
python -m jseval utility-publication-build --record ... --evidence ... --publication-id ...   # founder
python -m jseval utility-publication-select --publication-id ...                              # founder
```

---

## Run-directory contents at close (§E.6)

```
scripts/jseval/782-run-<DATE>-hero/
  mcp-config.json
  spend-ledger.md            # every phase boundary: planned/actual/cumulative/projection/headroom
  incident-ledger.md         # every guard that fired, including guards that fired correctly
  preflight.txt              # Step 0a output
  closed-book-sonnet/        # Step 0b evidence (P3)
  derivability-audit.json    # Step 0c
  leak-checks/               # Step 0d, four outputs
  <cell>/calibration.json | logs/ | out/utility-comparison.v1.json | judge-overlay.json
  combined/                  # the single composed record — the promoting artifact
  derived/                   # §E.4 items 1-5
```

## Stop rules (§E.6, all mandatory — each is a full stop, not a judgment call)

1. **Two failed relaunches** of any stratum → STOP, record `rejected`. No third attempt, no stratum
   substitution, no cohort-window extension.
2. **Guard over-cap** → ABORT the campaign.
3. **Any P0–P5 pre-launch check red** → do not launch.
4. **Fatal identity** — a different observed tool-surface hash, or a cohort split a resume cannot
   repair → stop and record.
5. **Post-first-cell change request** to any frozen parameter → stop, record, escalate to the
   founder. The amendment window closed at the first measured cell.

Never hand-patch an identity to get past a guard.
