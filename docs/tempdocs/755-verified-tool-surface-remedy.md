---
title: "verified_tool_surface is structurally unsatisfiable at current SDK flake rate — capture hardening first, staged policy amendment as the ready-to-ratify fallback"
type: tempdocs
status: "open — charter (2026-07-18). Implementation delegable; ONLY the policy-amendment ratification (§D) is founder-gated."
created: 2026-07-18
author: agent (Fable orchestration), chartered after the 624 confirmatory campaign's rejected verdict; founder-directed handoff ("proceed accordingly", 2026-07-18)
category: eval-infrastructure / claim-policy / agent-utility
related:
  - 624-agentic-retrieval-eval-rebuild   # §Confirmatory campaign RESULTS — the rejection this remedies
  - 725                                   # exposure-identity capture increments; the flaky get_mcp_status finding's home lane
  - 675                                   # original SDK mcp-status flake observation
---

> Charter. Verify every number below against
> `scripts/jseval/624-run-2026-07-18-confirmatory/combined-3stratum-VERDICT.utility-comparison-cross-corpus.v1.json`
> before building.

# 755 — verified_tool_surface remedy

## §A. Problem (measured, 2026-07-18)

The active claim policy (`agent-utility-public-v1`) gate `verified_tool_surface`
(`scripts/jseval/jseval/utility_claim_policy.py:394-406`) requires EVERY attempted with-tool
cell to carry an observed MCP surface hash equal to the declared hash. The per-cell hash is
captured at `scripts/jseval/jseval/agent_utility_inspect.py:816-855` and is `None` whenever the
agent SDK's `get_mcp_status()` returns nothing — a known-flaky signal (675/725). Confirmatory
campaign observation: 4–12 of 60 B-cells per stratum unverified (~8%/cell), 15/180 in the
composed record. P(all ~240 B-cells verified) ≈ 0 at that rate → **no campaign can promote
under the ratified policy as-is**. Data quality was otherwise perfect (0 exclusions, adoption
1.0), and `observed_mcp_tool_surface_consistent` was true — every cell that DID report a
surface reported the same single hash.

## §B. Track 1 — capture hardening (default; no policy change; delegable now)

Reduce the per-cell miss rate as far as engineering allows:

1. Retry `get_mcp_status()` with short backoff inside the cell capture (the call is
   read-only; N=3 retries is cheap relative to a 195s cell).
2. Fallback evidence path: if status stays empty but the cell's transcript contains ≥1
   executed `mcp__justsearch__*` call, capture the offered-surface listing from the SDK's
   tools-listing seam (or the `tool_call_sequence` + declared-surface cross-check) and record
   HOW the hash was obtained (`surface_evidence: "status" | "retry" | "fallback-listing"`) so
   the record distinguishes first-class from fallback verification.
3. Instrument the miss rate: emit `cells_mcp_surface_unverified` per run (already aggregated,
   `agent_utility_observations.py:286-338`) into the run-end summary line so campaigns see the
   rate live.

Acceptance: a 3-seed smoke campaign (any 1k dataset, ~$3) reports 60/60 verified B-cells, or
the residual miss rate is measured and documented as irreducible.

## §C. Track 2 — staged policy amendment (ready-to-ratify draft; DO NOT activate)

If Track 1 leaves a residual miss rate, draft (as a `.proposed` sibling file, never editing the
active policy in place): `verified_tool_surface` becomes
`minimum_surface_verification_rate: 0.9` AND `observed_mcp_tool_surface_consistent == true`
AND single observed hash == declared hash AND zero cells with a *different* hash (a missing
hash is a capture miss; a different hash stays fatal). Include the measured-flake evidence and
the exact diff in the draft header.

## §D. Founder gate <!-- founder-decision -->

Activating any amendment to the ratified policy requires the founder's explicit word. Nothing
in §B needs it.

## §E. Verification

- Unit: fixture with a missing-hash cell → gate outcome matches whichever rule is active;
  roundtrip digest invariant test updated only if the record schema gains `surface_evidence`.
- Live: the §B smoke campaign; `python -m jseval utility-recompose` on its logs must show the
  gate green (or the measured residual rate if Track 2 becomes necessary).
