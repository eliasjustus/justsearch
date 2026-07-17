# Step-2 powered campaign evidence (2026-07-17)

Founder-authorized powered agent-utility campaign (tempdoc 624 §Step-2 pre-registration +
amendments 1-2 + results + sensitivity + time-axis sections — the complete dated audit trail).

- Matrix: haiku, conditions A (file tools incl. grep) vs B (A + JustSearch MCP), seeds {0,1,2},
  3 verbose strata (legal-1k / legal-10k / email-1k), 20 committed queries each, 360 cells.
- Per-cell records: `<cell>/utility-comparison.v1.json` — recomposed 2026-07-17 under the
  resource-exhaustion-as-failure ITT outcome rule (post-hoc for this campaign, declared in the
  record's `outcome_rule` field) with the `duration` metric family.
- `<cell>/pre-rescore-2026-07-17/` — the original compositions under the pre-624 rule (exhausted
  cells excluded), preserved for the audit trail.
- `<cell>/calibration.json` — pre-run calibration (readiness, config_cohort_key, timeout,
  closed-book filter, cost estimate).
- `chain-step2.bat` — the exact detached campaign driver (historical record; absolute paths are
  machine-specific by nature).

Raw Inspect logs (per-cell messages/events) remain in the campaign worktree's gitignored
`tmp/step2-powered/<cell>/logs/`; the committed records recompose from them bit-identically via
the 719 replay machinery (raw-vs-evidence digest equality proven, see tempdoc 624).
