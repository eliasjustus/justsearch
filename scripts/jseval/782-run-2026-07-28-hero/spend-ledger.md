# 782 hero campaign spend ledger — run 2026-07-28
Cap: $300.00 hard (frozen §E.1). Guard: step2-budget-guard.py --cap 300.
| phase | planned | actual | cumulative | projection | headroom |
|---|---|---|---|---|---|
| 0b sonnet closed-book x3 | ~$3-9 | (CLI-capped, 150 calls) | ~$5 est | n/a | ample |
| s1 calibration (enron-1k) | ~$1-3 | cost_estimate basis run | incl. below | $36.00/stratum @300cells full-50q basis | — |
| s1 max_budget derivation | — | mean cell $0.12 (36.0/300); p95 not exposed by calibration.json; 1.6xp95-hat <= ~$0.56; clamp floor adopted | max_budget=$0.50/cell (binding, utility_run max_budget_usd) | 120 cells x <=$0.50 = $60 worst-case s1 | OK |
| s1 re-run (Amendment 2) | ~$39-50 | overlay verified by $1 smoke; max_budget $0.80 per frozen rule w/ measured censored p95 | pending | s1+s2+s3 @~$45 each + $45 sunk ~= $185 | OK vs $300 |
| Step 4 judge overlay x3 (2026-07-28) | $0.00 | **$0.00** — local Qwen3.5-9B judge via the Head's /v1 proxy; zero paid API calls (186 judged misses x 2 dual-order calls, all local) | unchanged | n/a — no paid path in step 4 | unchanged |
| Step 5 close-out compose (2026-07-28) | $0.00 | **$0.00** — offline compose over completed logs; FAILED CLOSED on the agent_cohort_key split, no record written | unchanged | re-measuring 3 strata in one clean-tree cohort window ~= $135 if founder authorizes | OK vs $300 |
| w2-s1 attempt 1 (backend killed mid-run) | ~$36 | ~$10-20 est (110 samples touched, 98 failed fast on refused connections; log killed pre-finalize, exact figure unrecoverable) | window1 ~$130 + ~$15 est = ~$145 | w2 remaining: s1 retry + s2 + s3 ~= $110-120 -> cumulative ~$255-265 | TIGHT but OK vs $300; one more full-stratum loss breaks the cap — serve now detached to remove the kill vector |
| w2-s1 attempt 2 (detached serve, eval start 13:12) | ~$36 | pending | — | — | — |
| w2-s1 attempt 2 COMPLETE (14:0x) | ~$36 | ~$41 actual (record cost_usd: A mean $0.321, B mean $0.358, 60 cells/arm) | window1 ~$130 + w2-s1a1 ~$15 + w2-s1a2 ~$41 = ~$186 | s2+s3 at similar actuals ~$80 -> ~$266 total | OK vs $300, thin; guard re-run before s2 per step e |
| w2-s2 COMPLETE | ~$36 | ~$42 actual (A mean $0.388, B mean $0.311; B exhausted 12/60 vs A 4/60 at the $0.80 cap) | ~$228 | s3 ~$40 -> ~$268 total | OK vs $300 |
| w2-s3 COMPLETE | ~$36 | ~$50 actual (A mean $0.448, B mean $0.392) | ~$278 | paid phase DONE; judge + compose are $0 local | OK vs $300, headroom ~$22 |
| Step 4 judge x3 + Step 5 compose (w2) | $0.00 | $0.00 (local Qwen judge via proxy; offline compose) | ~$278 | CAMPAIGN PAID PHASE CLOSED | final headroom ~$22 vs $300 cap |
