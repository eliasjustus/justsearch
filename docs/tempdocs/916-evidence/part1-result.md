# Tempdoc 916 evidence — Part 1 result, §L: incumbent retained; threshold rationale closed analytically

Split from `docs/tempdocs/916-lane-e-search-quality-rederivation.md` (size-cap split, 930 §19.3 F4).

## §L Part 1 result — incumbent retained; threshold rationale closed analytically (2026-09-03/04)

### L.1 Evidence admitted to the decision

The campaign produced **32 admissible completed arms**: 15 on `mixed/legal-clerc-200`
(the 12-cell matrix, two additional incumbent reindexes, and one shipped-deadline control), 12 on
`mixed/ohr-bench-clean`, and five on `mixed/enron-qa`. Every admitted arm has
`ce_coverage.verdict == "ok"`, `per_mode.hybrid.comparable == true`, a complete non-null
decision record, and `machine_dirty == false`. All matrix arms used the arm-invariant **2000 ms**
cross-encoder deadline selected in §K.9. That is campaign instrumentation, not a production-default
proposal.

One earlier Enron 256/50 smoke left an `arm-metrics.json` without an admissible result after its CE
coverage failure; it is not one of the 32. A later resume began Enron 128/0 but was stopped before
the arm produced metrics or `ARM.done`; its directory contains only setup/log material and is not
evidence. No mean or threshold below incorporates either attempt.

The three legal incumbent reindexes establish the required calibration:

| run | nDCG@10 | R@10 | leg-union recall |
| :--- | ---: | ---: | ---: |
| `20260903T085832_mixed_legal-clerc-200` | 0.581629 | 0.810 | 0.925 |
| `20260903T090447_mixed_legal-clerc-200` | 0.592372 | 0.835 | 0.925 |
| `20260903T091138_mixed_legal-clerc-200` | 0.580665 | 0.815 | 0.925 |
| **mean / sample σ** | **0.584888 / 0.006499** | **0.820 / 0.013229** | **0.925 / 0** |

For legal nDCG the pre-registered `max(observed σ, 0.0068)` rule therefore applies the 0.0068
floor, making the strict +2σ win line **0.598488**. For OHR and Enron, where the incumbent has one
replicate, the explicitly marked run-level floor applies: ±2σ = **0.0136**.

### L.2 What the two full matrices establish

All 12 legal arms and all 12 OHR arms completed. On OHR, the incumbent itself is the matrix maximum
at **0.957099 nDCG@10** (`20260903T125636_mixed_ohr-bench-clean`); the next arm is 256/50 at
0.957090. No challenger can clear the OHR +2σ line of **0.970699**.

On legal, eight challengers are eliminated before Enron: either they do not clear 0.598488 or their
leg-union recall falls below the incumbent's 0.925, violating clause 2. Exactly four challengers
remain capable of winning legal without that recall loss:

| target / overlap | legal nDCG@10 | legal R@10 | legal union | legal run |
| :--- | ---: | ---: | ---: | :--- |
| 128 / 25 | 0.634915 | 0.840 | 0.925 | `20260903T093157_mixed_legal-clerc-200` |
| 256 / 0 | 0.629296 | 0.835 | 0.930 | `20260903T095341_mixed_legal-clerc-200` |
| 384 / 0 | 0.621148 | 0.845 | 0.925 | `20260903T102630_mixed_legal-clerc-200` |
| 384 / 25 | 0.609067 | 0.835 | 0.925 | `20260903T103256_mixed_legal-clerc-200` |

This is a complete logical partition, not a top-four convenience: the other seven non-incumbents
cannot satisfy clauses 1 and 2 on legal, and no challenger can win OHR.

### L.3 The decisive Enron proof

Because no challenger wins OHR, a challenger can meet clause 1's “at least two of three” rule only
by winning **both legal and Enron**. The four and only four legal-capable challengers were therefore
run on Enron alongside the incumbent:

| target / overlap | Enron nDCG@10 | Δ vs incumbent | R@10 | union | Enron run |
| :--- | ---: | ---: | ---: | ---: | :--- |
| **500 / 50 incumbent** | **0.792402** | — | 0.910 | 0.960 | `20260903T133806_mixed_enron-qa` |
| 128 / 25 | 0.760281 | −0.032121 | 0.907 | 0.957 | `20260903T171519_mixed_enron-qa` |
| 256 / 0 | 0.787604 | −0.004798 | 0.917 | 0.953 | `20260903T155614_mixed_enron-qa` |
| 384 / 0 | 0.793542 | +0.001140 | 0.897 | 0.963 | `20260903T150903_mixed_enron-qa` |
| 384 / 25 | 0.798495 | +0.006093 | 0.920 | 0.963 | `20260903T142710_mixed_enron-qa` |

The strict Enron +2σ line is **0.806002**. None reaches it; the closest arm, 384/25, improves by
only 0.006093 against the required >0.0136. Therefore every non-incumbent fails clause 1 regardless
of any later control or RAG result. Some also fail the no-union-loss or no-R@10-regression clause,
but those additional failures are not needed for the verdict.

### L.4 Early stopping — useful, but an explicit execution deviation

Section K.6 pre-registered a full 12-arm Enron matrix followed by controls and RAG. It did **not**
pre-register a pruning or early-stop clause. Execution departed from that order once the proof in
L.2–L.3 was independently reconstructed from the completed per-arm records:

- the seven Enron challengers already incapable of winning legal were not run to completion;
- multilingual and scifact controls were not run because those checks can only validate a proposed
  winner, and no proposed winner exists;
- the RAG fixture was not judged because §K.5 makes RAG secondary/veto-only—it cannot promote an arm
  that fails retrieval clause 1.

This is not represented as if the full matrix ran. It is an **unplanned but decision-preserving
early stop**: every skipped result is downstream of a necessary condition that is already false.
The retained incumbent is also the pre-registered tie/near-tie outcome, so skipped controls cannot
cause a different selection.

### L.5 Runner defect found during the attempted resume

The resume exposed a fail-open orchestration defect: an arm's non-zero return was ignored by
`do_run`, decision-bearing nulls were diagnosed but never enforced there, and the generated chain
used PowerShell `Continue` semantics without checking `$LASTEXITCODE`. Consequently phase/run
markers could describe launcher exit rather than successful evidence. The campaign verdict does
not rely on those aggregate markers: it was recomputed from each admitted arm's own
`arm-metrics.json`, admissibility fields, run id and completion marker.

The retained driver now fails closed at both boundaries. On the shipping tree, `run` refuses to
start because the four temporary Worker-bound keys are absent; only `analyze` is available for the
archived evidence. On a deliberate throwaway branch where all four bindings exist, an `ARM.done`
is reusable only with a structured completion record binding target, overlap, minimum, threshold,
deadline, run id and source SHA. Completion also requires a clean machine window and known
chunk-branch execution (true on an arm corpus). Non-zero/null/inadmissible or identity-mismatched
arms cannot earn corpus/run completion, and generated chains stop on a non-zero Python exit.
Regression tests cover these failure and stale-resume shapes. The stopped 128/0 attempt is left out
precisely because it never acquired valid per-arm evidence.

### L.6 Deadline control, costs, and threshold follow-up closure

The matrix deadline was 2000 ms so small chunks did not become inadmissible merely by creating more
rerank candidates. The production default remains 200 ms. A clean incumbent control at the shipped
deadline (`20260903T091800_mixed_legal-clerc-200`) scored 0.581146 nDCG@10, 0.810 R@10 and 0.925
union recall—inside the incumbent calibration band. Campaign-internal scores therefore do not
repin a release baseline.

Every admitted arm records SPLADE truncation, index bytes and primary docs/s. Those measurements
remain useful cost evidence, but clauses 3–6 are not evaluated as if they could rescue an arm after
clause 1 fails. In particular, noisy single-run throughput or index-size variation is not promoted
to a selection rationale.

`CHUNK_THRESHOLD_CHARS = 2000` was **held fixed**, not swept, so this campaign supplies no empirical
threshold comparison. The earlier §C.2 wording “four times the chosen chunk size in chars” is
ambiguous and is superseded: it conflated a four-characters-per-token estimate with four chunk
windows. The exact analytic relationship is:

```java
TokenEstimation.charsForTokens(ChunkSplitter.DEFAULT_CHUNK_TOKENS) == 2000
```

`TokenEstimation` owns the canonical optimistic typical-prose conversion of four characters per
token. Therefore 2000 is a cheap **one-typical-window prefilter** for the selected 500-token target,
not four chunks and not a boundary oracle. The writer first rejects raw content shorter than 2000;
content that passes is then processed by `ChunkSplitter`, and `ChunkDocumentWriter` still emits no
chunk documents when the splitter returns zero or one chunk. Fast characterization tests bind both
the constant relationship and that threshold-plus-single-chunk behavior.

The splitter's own content-aware conversion remains authoritative for actual boundaries: Latin text
maps near 3.85 characters per token, while CJK-dominant text can map near one character per token.
That difference does not create a per-language threshold. The single 2000-character prefilter is
locale-invariant, and the splitter handles content shape after the gate in accordance with
ADR-0043's no-per-language-lever rule.

The old “≥1536-character corpus-profile floor” is also qualified. It is only the arithmetic point
where `IndexingDocumentOps`'s `/3` **fallback** estimate reaches 512 tokens. The shipped 2000 retains
margin above that compatibility bound, but exact SPLADE counts and corpus-level median/chunk-rate
routing are separate. Neither 1536 nor 2000 is established here as a retrieval-quality optimum.

Disposition: the unchanged threshold is **analytically derived, operationally exercised, and not
quality-optimized**. That explicit classification closes the promised follow-up without another
multi-hour corpus sweep and without changing production behavior or `ALGORITHM_VERSION = "v1"`.

### L.7 Shipping decision

Retain the selected static chunk geometry:

`(target_tokens, overlap_tokens, min_tokens) = (500, 50, 100)`.

The separate `threshold_chars` remains 2000 under §L.6's analytic and operational rationale; it is
not claimed as a quality-optimized result.

No production chunking behavior changes. The four temporary environment/system-property keys,
their nullable resolved-config representation, dynamic Worker consumers, tests and config-surface
growth are removed in the closeout. Lane D already fingerprints the four static values plus
`ChunkSplitter.ALGORITHM_VERSION`; because neither boundaries nor values change,
`ALGORITHM_VERSION` correctly remains **`"v1"`**. A version bump would cause an unnecessary reindex
for byte-identical chunking.

### L.8 OHR reconciliation mismatches — a TREC parser defect, not a measurement (2026-09-05)

The 105/962 reconciliation mismatches recorded on `mixed/ohr-bench-clean` were an **instrument
defect in the projection's TREC reader**, not a property of the run. All 105 were the same shape
("projection says the gold is absent, harness says present"), which is the signature of a parse
failure rather than a retrieval disagreement.

**Mechanism.** `staged_recall_accounting._load_trec` split each run line on whitespace and took
`parts[2]` as the doc id, while `artifacts._write_trec_run` wrote `qid Q0 <docid> rank score tag`
space-delimited and unquoted. 109 of the 962 OHR golds contain a space
(`law/airtechinternationalgroupinc_05_08_2000-ex-10.4-franchise agreement_p8`), so every one of
them was truncated at its first space at parse time and then failed every set-membership check
against qrels — in the leg union and in the final list alike. Fixed in
`scripts/jseval/jseval/trec.py` (new shared module): the reader is now **right-anchored**
(`qid`/`Q0` lead, `rank`/`score`/`run_tag` trail, everything between is the id) and the writer
tab-delimits. Both other in-repo readers (`experiments/fusion_attribution_784.py`,
`metric_order_ab.mjs`) carried the identical `parts[2]` assumption and were fixed the same way.

**Corrected numbers** — incumbent arm `t500-o50-r0`, run
`20260903T125636_mixed_ohr-bench-clean`, re-derived with the fixed code against a copy of the run
dir (the archived artifacts were not rewritten):

| quantity | as-recorded | corrected |
|---|---|---|
| `final_recall` | 0.8783783783783784 | **0.9875259875259875** |
| `leg_union_recall` | 0.8794178794178794 | **0.9885654885654885** |
| `LEG_MISS` | 115 | **10** |
| `reconciliation.mismatches` | 105 | **0** (checked 962) |

Independent corroboration that the corrected values are right, not merely different: the fixed
projection's per-leg recalls now equal the harness's `ir_measures` `R@10` **exactly** for all four
modes — hybrid 0.9875259875259875, lexical 0.9864864864864865, vector 0.920997920997921, splade
0.4553014553014553 (`summary.json` `per_mode/*/aggregate_metrics/R@10`). Two independent code
paths now agree to the last digit.

**Comparability.** The bias is a constant −0.1091 on `leg_union_recall` (and −0.1091 on
`final_recall`) applied identically to all 12 OHR arms, because every arm shares the same qrels and
the same 109 space-bearing golds. **Deltas between OHR arms therefore hold unchanged**; only the
OHR *absolutes* were under-reported. `mixed/legal-clerc-200` and `mixed/enron-qa` had 0 mismatches
and are unaffected — their doc ids contain no whitespace.

**The Part 1 verdict is unchanged.** The pre-registered decision metrics (`nDCG@10`, `R@10`) are
computed by `ir_measures` over in-memory `ScoredDoc`s (`scoring.py:44`, `artifacts.py:233-236`) and
never read the TREC file, so clauses 1, 3, 4, 5 and 6 of §K.6's adoption rule are untouched.
`leg_union_recall` appears only in clause 2, and only *comparatively* ("does not fall" versus the
incumbent) — never as an absolute threshold. A constant per-corpus offset cancels in that
comparison, so clause 2's verdict on every OHR arm is also unchanged. 500/50/100 is retained on
the same evidence.

---

