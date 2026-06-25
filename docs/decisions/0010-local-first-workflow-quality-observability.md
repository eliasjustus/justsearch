---
title: "Local-First Workflow Quality Observability"
type: decision
status: stable
description: "Keep repo-owned NDJSON workflow evidence canonical and treat Opik/Inspect as optional overlays."
date: 2026-03-07
---

# ADR-0010: Local-First Workflow Quality Observability

**Status: Superseded (tempdoc 638)** — the workflow-telemetry implementation described here was removed.

## Status

Accepted

## Context

JustSearch now has a completed workflow-quality program with:

- repo-owned workflow-run evidence under `tmp/workflow-telemetry/runs/`
- canonical Node automation surfaces for search workflows and workflow comparison
- optional OTLP trace fan-out
- successful local pilots for self-hosted Opik and Inspect AI plus Claude Code

The repo needed a durable decision about what is canonical versus optional. Without that decision,
workflow-quality policy would remain split across tempdocs, wrappers, and pilot tooling.

The main design forces are:

- local-first operation on developer machines
- Windows-native workflows
- compatibility with existing PowerShell evaluation logic
- ability to compare workflows without requiring external infrastructure
- ability to add shared external visibility without replacing repo-owned evidence

## Decision

Adopt the following workflow-quality observability policy:

1. Repo-owned NDJSON workflow evidence is canonical.
   - `tmp/workflow-telemetry/runs/<workflowRunId>/meta.json`
   - `tmp/workflow-telemetry/runs/<workflowRunId>/events.ndjson`
2. Local workflow comparison uses repo-owned evidence first.
3. Self-hosted Opik is the supported optional external trace backend.
4. Inspect AI with Claude Code is the supported optional external workflow harness.
5. External layers remain opt-in overlays rather than default infrastructure.
6. PowerShell evaluation logic may remain supported, but canonical orchestration surfaces are the
   Node wrappers and DAG runners.

## Consequences

- Developers can inspect and compare workflow runs without standing up external services.
- The repo can still use shared external tooling when traces or harness runs need a richer UI.
- Pilot success is preserved without turning external infrastructure into a new dependency.
- Workflow-quality policy becomes explicit and stable instead of living only in tempdocs.

Negative or limiting consequences:

- Some concepts now exist in both local files and optional external tools, so docs must clearly
  state which layer is canonical.
- External overlays must map back to repo-owned schema and join keys instead of inventing a new
  primary contract.
- Raw/manual lifecycle ownership paths remain compatibility-only and should not be treated as the
  primary operator path.

## Alternatives Considered

### Make the remote backend canonical

Rejected because it would make local workflow comparison depend on external infrastructure and would
conflict with the repo's local-first operating model.

### Keep workflow-quality policy only in tempdocs

Rejected because the tempdocs succeeded as investigation and rationale artifacts, but they are not
the right long-term home for stable operator policy and architecture decisions.

### Replace the PowerShell eval core immediately

Rejected because the repo already invested in hardening the evaluation logic there. The stronger
boundary is orchestration ownership, not forcing an immediate rewrite of the evaluation core.

