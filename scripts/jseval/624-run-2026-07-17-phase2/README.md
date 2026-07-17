# Phase-2 campaign evidence (2026-07-17)

Founder-authorized Phase-2 runs (tempdoc 624 §Phase-2 pre-registration + amendments 1-2 +
results — the dated audit trail). Rules PRE-registered for these runs: resource-exhaustion-as-
failure ITT + equalized-max per-arm timeouts (run 2 also: single-tier --agent-env overlay).

- `en-email-enron-raw-10k-verbose-haiku/` — run 1 (haiku, A/B × 3 seeds). ACCURACY CONFOUNDED
  by the pre-equalization budget asymmetry (A 337s vs B 120s — amendment 1 disposition);
  duration/completion valid. Equalized rerun = recorded founder option.
- `en-legal-clerc-10k-verbose-sonnet/` — run 2, the tier probe (sonnet, A/B × 1 seed,
  all 40 cells single-tier claude-sonnet-5, comparable:true). Pre-registered verdict (a):
  the 10k hop-2 floor is MODEL-CAPABILITY-bound (sonnet-B 0.600 vs haiku-B 0.067).
  Single seed: floor-vs-not only, no finer claims.
- `chain-phase2.bat` — the campaign driver as last run (fresh-build path; the index-cache
  integration attempt was reverted — findings filed to tempdoc 751 §P.3.5).

Raw Inspect logs remain in the campaign worktree's gitignored `tmp/phase2/<cell>/logs*/`
(including the voided mixed-model attempt archived as `logs-void-mixed-model-guard-20260717/`).
