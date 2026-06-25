# Alert Evidence — Phase 6a Rollout

Source: `config/alerts/pipeline-phase6a.yaml` exported 2025-11-04.

- **Pipeline deadline spike** — PromQL: `increase(pipeline_stage_timeout_total{reason_code="pipeline_deadline"}[5m]) / increase(pipeline_stage_total[5m]) > 0.10`
- **Rerank skip share > 10%** — PromQL: `increase(search_rerank_skips_total{reason="rerank_skipped_deadline"}[10m]) / increase(search_rerank_invocations_total[10m]) > 0.10`
- **ANN skip/error > 2%** — PromQL: `increase(search_hybrid_skips_total{reason=~"ann_.*"}[10m]) / increase(search_hybrid_invocations_total[10m]) > 0.02`
- **Indexing queue age breach** — PromQL: `max_over_time(index_queue_age_ms_p95[15m]) > 60000`
- **Plugin admission failures** — PromQL: `increase(plugins_stage_failures_total{reason_code!=""}[5m]) > 0`

Runbook: `docs/ops/runbooks/pipeline-alerts.md` (Section 6a) with mitigation checklist.
