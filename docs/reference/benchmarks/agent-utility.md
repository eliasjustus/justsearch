---
title: "Agent-Utility Benchmark"
type: reference
status: draft
description: "Methodology, evidence contract, replay path, and current publication state for the paired agent-utility benchmark."
---

# Agent-Utility Benchmark

The agent-utility benchmark measures the marginal effect of giving the same agent access to
JustSearch in addition to ordinary file tools. Condition A uses file tools only. Condition B uses
the same file tools plus the captured JustSearch MCP surface. Conditions are paired by corpus,
query, seed, resolved model, prompt, runtime, and source identity.

## Evidence and verdict contract

Every attempted condition/seed/query cell is represented in the sanitized observation evidence.
The expected campaign matrix, query-and-gold digest, materialized corpus digest, source Git state,
resolved provider model, search configuration, and complete MCP `tools/list` surface are captured
before or during the run and checked during replay. Missing or corrupt campaign evidence is an
error; replay cannot supply missing identity.

The primary estimand is intention-to-treat: errored cells count as incorrect and as non-adoption.
The completed-cell per-protocol view is retained only as a labeled secondary analysis. The checked-in
policy governs loss, paired retention, exclusion symmetry, adoption, uncertainty, per-stratum
promotion, and effect classification. A valid outcome may be benefit, harm, null, or adoption-only;
an incomplete or invalid record is inconclusive and cannot be published.

## Current result

<!-- agent-utility:generated:start - run: node scripts/docs/gen-public-agent-utility.mjs -->

No agent-utility result is currently accepted for publication. No agent-utility result has passed the unresolved scientific claim policy. The checked-in scientific policy intentionally leaves its adoption, non-inferiority, and efficiency-equivalence thresholds unresolved; choosing those thresholds and paying for a new model run are owner decisions, not defaults the harness invents.

The publication chain is: all attempted Inspect cells -> strict sanitized observation evidence -> pure offline recomposition -> versioned claim-policy verdict -> immutable accepted publication manifest -> explicit accepted-result pointer. Rejected evidence remains a test/history fixture rather than a publication bundle. The pointer is `scripts/jseval/public-agent-utility/current.v1.json`.

Replay uses only committed evidence:

```bash
cd scripts/jseval
python -m jseval utility-replay --publication <publication-id>
```

<!-- agent-utility:generated:end -->

## Replay and live rerun

`python -m jseval utility-replay` is a zero-cost operation. It verifies the publication, observation,
record, and policy hashes, recomposes the record from the observation evidence, re-evaluates the
captured policy, and compares the semantic digest.

A live rerun is separate. It requires the licensed corpus sources, a running JustSearch backend,
model credentials, a pre-registered active policy, and explicit budget authorization. Replaying an
accepted publication never contacts those systems.

## Limitations

- No result is public while the checked-in pointer is `current: null`.
- Historical records without complete query, corpus, model, search, and MCP identities are
  permanently pre-contract and cannot be upgraded during replay.
- Publication establishes reproducibility of the captured campaign; it does not generalize beyond
  the recorded corpora, model snapshot, query strata, and tool surface.
