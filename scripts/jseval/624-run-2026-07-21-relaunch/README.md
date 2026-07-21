# 624 Relaunch Campaign — 2026-07-21 — **ACCEPTED (adoption-only)**

The agent-utility program's **first promoted claim**. Pre-registered in tempdoc 624
§Relaunch pre-registration (merged before launch, PR #260); evaluated against the ratified
policy `agent-utility-public-v2` (PR #262); executed by `chain-confirm-v5.bat` from a clean
tree at squash `3ed766b7`, launched 07:51, completed 12:0x — single uninterrupted cohort
window, zero launch incidents.

## Verdict

`claim_verdict.status = "accepted"`, `outcome = "adoption-only"`, failing reasons: **none** —
see `combined-ACCEPTED-VERDICT.…json`. Per the policy's wording constraints, the promotable
statement is the adoption claim (agents, given the tool, adopted it at 98–100% under every
identity/integrity gate); no numeric benefit language is licensed by this verdict.

- `verified_tool_surface`: **rate 0.9167 (220/240) ≥ 0.9**, single observed hash equal to
  declared — the ratified rate-based gate's first live evaluation, landing within a point of
  the 755 smoke's 91.7% prediction.
- Identity: one harness cohort (CLI `2.1.216` held for the entire campaign — the
  `DISABLE_AUTOUPDATER` window + SHA/CLI-stamped calibrations, 758), one search-config cohort,
  `git_dirty: false`, corpus-dir derivation enforced per run (#258), exposure identity carried
  through compose (756).
- 480/480 cells observed, all resolved `claude-haiku`; efficiency intervals fail closed where
  with-tool wall-clock truncation exists (757) — expected and pre-registered.

## Scientific results (ITT primary, n=60 pairs/stratum, McNemar exact, α=0.05) — third replication of the regime pattern

| Stratum | acc A | acc B | Δ | p | adoption | completion A→B |
|---|---|---|---|---|---|---|
| legal-1k | 0.033 | 0.200 | **+0.167** | **0.013** | 1.00 | 0.97 → 0.97 |
| legal-10k | 0.000 | 0.167 | **+0.167** | **0.002** | 0.98 | 0.58 → 0.92 |
| email-1k | 0.533 | 0.417 | −0.117 | 0.27 | 1.00 | 0.87 → 0.95 |
| email-10k | 0.217 | 0.200 | −0.017 | 1.0 | 0.98 | 0.57 → 0.75 |

Legal accuracy benefit significant at both scales (third independent replication); email
accuracy-null (fourth and fifth independent null); baseline completion collapses at 10k in
both domains while the with-tool arm holds.

## Spend & infrastructure

All-real guard total **$89.77** (cap $100): email-1k $20.40, email-10k $9.31, legal-1k
$31.58, legal-10k $28.48. Wall clock ~4h15m. The 751 warm/adopt path ran all four runs
(builds published as store entries `16e134bb…`/`6d15611e…`/…; every serve boot adopted in
seconds; every safety-net ingest no-op'd at the union floor in <1 min — the §Q fix's first
campaign, flawless). Zero incidents, zero relaunches, zero exclusions-by-error.

## Files

- `combined-ACCEPTED-VERDICT.…` — the policy-evaluated promoted record.
- `<stratum>.utility-comparison.v1.json` — the four per-run records.
- `calibration-*.json` — SHA+CLI-stamped calibrations (758 provenance binding).
- `chain-confirm-v5.bat.txt` — the exact chain executed.
