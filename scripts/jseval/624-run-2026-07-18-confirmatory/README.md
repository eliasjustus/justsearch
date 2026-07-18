# 624 Confirmatory Campaign — 2026-07-18 (agent-utility, 4-stratum, haiku)

Pre-registered confirmatory campaign against the **active** claim policy
`agent-utility-public-v1` (`scripts/jseval/utility-claim-policy.v1.json`,
ratified + activated in PR #243 *before* launch). Spec: tempdoc 624
§Confirmatory pre-registration + amendment 1 (cheapest-first).

## Verdict

**`claim_verdict.status = "rejected"`, `outcome = "inconclusive"`** — see
`combined-3stratum-VERDICT.utility-comparison-cross-corpus.v1.json`.

No promoted claim. Per the policy's wording constraints, this campaign may be
described only as *rejected/inconclusive*; no numeric benefit language may be
published from it. The rejection is entirely **infrastructure-identity gates**,
not data quality (0 excluded cells in 600, paired retention 1.0, adoption 1.0):

| Failing gate | Cause | Class |
|---|---|---|
| `required_strata_exact` | email-1k stratum voided twice (incidents #5, #6 below) | campaign execution |
| `source_identity_complete` | cross-corpus compose drops `exposure_config`/`mcp_initialize_identity` that every per-run record carries | compose bug (filed) |
| `verified_tool_surface` | SDK `get_mcp_status()` flake → 15/180 composed B-cells lack an observed surface hash (~8%/cell ⇒ a 100%-of-240-cells bar has ≈0 success probability) | policy/capture mismatch (filed; founder decision) |

Additionally, per-stratum outcomes cap at `adoption-only` (never `benefit`)
because exhausted cells lack ITT usage evidence, making the efficiency
intervals unavailable — also filed.

## Scientific results (pre-registered primary: ITT, exhaustion-as-failure, n=60 pairs/stratum, McNemar exact, α=0.05)

| Stratum | acc A (files+grep) | acc B (+JustSearch MCP) | Δ | p | 95% CI | A completion | B completion | median wall A→B (s) |
|---|---|---|---|---|---|---|---|---|
| legal-1k | 0.017 | 0.233 | **+0.217** | **0.00098** | [+0.100, +0.333] | 0.883 | 0.950 | 98 → 48 |
| legal-10k | 0.000 | 0.100 | **+0.100** | **0.031** | [+0.033, +0.183] | 0.550 | 0.900 | 129 → 74 |
| email-10k | 0.300 | 0.250 | −0.050 | 0.68 | [−0.200, +0.100] | 0.550 | 0.817 | 146 → 43 |
| email-1k (r2, voided for composition) | 0.350 | 0.383 | +0.033 | 0.83 | [−0.117, +0.183] | — | — | — |
| email-1k (v4, voided for composition) | 0.367 | 0.317 | −0.050 | 0.69 | [−0.217, +0.117] | — | — | — |

Adoption 1.0 in every stratum. The two independent email-1k runs agree (both
null), so the void is an identity technicality, not a data gap. The regime
triad from the Step-2 exploratory campaign **replicates under confirmatory
conditions**: accuracy benefit concentrates on legal (significant at both
scales), email is accuracy-null, and the time/completion axis favors B
everywhere (A-arm completion collapses to 55% at 10k in both domains; B holds
82–90%; median wall time roughly halves).

## Runs

Launched 23:43 2026-07-17 on a clean tree at public squash `079e63e5`,
cheapest-first (amendment 1). 480 planned cells + 120 rerun cells; all 600
resolved `claude-haiku`, 120/120 observed per stratum.

| Run | Window | Search-config cohort | Agent cohort |
|---|---|---|---|
| email-1k (v4) | 23:45–00:16 | `f1566eff…` (**split — banked calibration**) | `431be21e…` |
| email-10k | 00:16–01:05 | `ab705cf9…` | `431be21e…` |
| legal-1k | 01:05–01:42 | `ab705cf9…` | `431be21e…` |
| legal-10k | 01:42–03:18 | `ab705cf9…` | `431be21e…` |
| email-1k rerun (r2) | 03:24–04:05 | `ab705cf9…` | `c1115251…` (**split — CLI auto-update + dirty tree**) |

## Incident ledger (fail-closed gates working as designed)

1. **751 index-cache warm wedge** (launch 1, 22:23) — cumulative readiness floor
   on double ingest; warm step removed (PR #244).
2. **Budget-guard max-extrapolation over-projection** (launch 2) — legal-1k
   $31.77 first ⇒ $127 projected; cheapest-first reorder (PR #245).
3. **Calibration queries-rewrite at zero drops** (launch 3) — byte-change broke
   the certification digest; rewrite-only-when-dropped fix (PR #246).
4. **Flaky CI lane** on #245 (platform-contracts unit lane; diff was .bat+md) —
   single rerun, logged.
5. **Banked-calibration cohort split** (v4) — email-1k adopted the 23:33
   calibration pinned at `92ec2e6d` (launch-3 attempt) into the `079e63e5`
   chain; recompose refused ("with-tool arms span multiple search configs").
   Forensic proof: recomputing the key against the live backend and swapping
   *only* `git_sha` reproduces both keys exactly — the split was label-only,
   all live components identical. Remedied by a clean stratum rerun rather
   than any hand-patched identity.
6. **Rerun cohort split** (r2) — Claude Code auto-updated 2.1.212→2.1.214
   between the v4 window and the rerun cells, and the rerun launched with the
   (then-untracked) `chain-confirm-r1.bat` in-tree ⇒ `agent_cohort_key` split
   + `git_dirty`. Completing the cohort would require downgrading the shared
   global CLI — out of bounds for an autonomous overnight run; stopped here.

## Spend

Calibration-estimate basis: $92.09 for the five runs (guard-verified under the
$100 cap: `known=5/5 sum=$92.09`), of which the voided v4 email-1k run is
$12.92 and the r2 rerun $9.36. Earlier aborted launch attempts (1–3) spent
≈$9 in calibration pilots, recorded in the 624 pre-registration amendments.

## Files

- `combined-3stratum-VERDICT.…` — the policy-evaluated record (the verdict).
- `email-10k|legal-1k|legal-10k.utility-comparison.v1.json` — per-run records
  (per-protocol view; ITT lives in the composed records).
- `email-1k-v4-VOIDED` / `email-1k-r2-VOIDED` — the two voided email-1k
  per-run records (kept, not hidden).
- `email-1k-{v4,r2}-itt-recompose.…` — single-stratum ITT composes of the
  voided runs (for the results table above).
- `calibration-*.json` — all five calibrations, including the banked one that
  caused incident #5 (filename carries its pin SHA).
- `chain-confirm.bat.txt`, `chain-confirm-r1.bat.txt` — the exact chain
  scripts executed.
