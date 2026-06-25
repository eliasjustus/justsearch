---
title: "272: Workflow Attribution and Usability Theory"
type: tempdoc
status: done
created: 2026-03-10
updated: 2026-03-11
---

> NOTE: Noncanonical working tempdoc. Verify any behavioral or implementation claims against canonical docs, code, and tests before promotion.

# 272: Workflow Attribution and Usability Theory

## Purpose

Define the next theory layer after tempdocs 118 and 262-265 so the repo's workflow-quality system
becomes more useful in practice rather than simply more instrumented.

This tempdoc focuses on three questions:

1. How should the repo attribute workflow runs to agent sessions, traces, DAG runs, and eval tasks?
2. What decisions should the workflow-quality system support for agents, maintainers, and evaluators?
3. Which parts of the current telemetry and external stack materially improve usefulness, and which
   parts are only descriptive or experimental?

## Current state

The repo now has:

- Session-centric agent analytics from tempdoc 118 and `scripts/agent-analytics/`
- Run-centric workflow telemetry from tempdocs 262-265 and `tmp/workflow-telemetry/runs/`
- **Workflow-family SLOs** with engine-specific timing bounds and boolean/count invariants,
  gated in the closure runner (`run-workflow-quality-closure.mjs` exits non-zero on violations)
- **Agent-to-workflow session attribution** via `resolveWorkflowSessionId()` with 3-tier
  resolution: explicit `--session-id` > `JUSTSEARCH_AGENT_SESSION_ID` env var >
  `tmp/agent-telemetry/current-session-id` file
- **Attribution report** (`report-workflow-attribution.mjs`) classifying runs as `attributed`,
  `orphaned_session_ref`, or `unattributed`
- Optional OTLP fan-out for Java traces
- Optional Opik and Inspect AI pilot integrations

What remains theoretically weak:

- Eval identity fields (`evalRunId`, `evalSampleId`, `datasetItemId`, `datasetVersionId`) —
  deferred until a concrete eval harness consumes workflow runs
- A crisp model of which layer answers which operational question (partially addressed by F5 below)

## Main research questions

### RQ1. Attribution

How do comparable systems distinguish and join:

- session identity
- workflow or run identity
- trace identity
- dataset or eval-sample identity

### RQ2. Usefulness

Which decisions do mature systems optimize for?

- operator debugging
- agent self-correction
- experiment comparison
- shared observability
- cost accounting

### RQ3. Architecture

Do comparable systems reinforce or weaken the current JustSearch direction:

- local repo-owned workflow evidence as canonical
- optional external overlays rather than mandatory infrastructure
- explicit separation between workflow telemetry and session analytics

## External research pass (2026-03-10)

This pass focused on current official docs and primary-source repos for systems adjacent to the
JustSearch workflow-quality stack.

Reviewed sources:

- OpenTelemetry session semantic conventions:
  <https://opentelemetry.io/docs/specs/semconv/general/session/>
- OpenTelemetry MCP semantic conventions:
  <https://opentelemetry.io/docs/specs/semconv/gen-ai/mcp/>
- OpenInference semantic conventions and JS core:
  <https://arize-ai.github.io/openinference/spec/semantic_conventions.html>
  <https://arize-ai.github.io/openinference/js/packages/openinference-core/>
- Anthropic Claude Code monitoring:
  <https://code.claude.com/docs/en/monitoring-usage>
- Anthropic Claude Code hooks reference:
  <https://code.claude.com/docs/en/hooks>
- Inspect AI logs, dataframes, datasets, and typing:
  <https://inspect.aisi.org.uk/eval-logs.html>
  <https://inspect.aisi.org.uk/log-viewer.html>
  <https://inspect.aisi.org.uk/dataframe.html>
  <https://inspect.aisi.org.uk/datasets.html>
- Opik OpenTelemetry, datasets, and experiments:
  <https://www.comet.com/docs/opik/integrations/opentelemetry>
  <https://www.comet.com/docs/opik/evaluation/manage_datasets>
  <https://www.comet.com/docs/opik/reference/typescript-sdk/evaluation/experiments>
- Langfuse Python SDK reference:
  <https://python.reference.langfuse.com/langfuse>
- OpenHands benchmarks and agent-analysis repos:
  <https://github.com/OpenHands/benchmarks>
  <https://github.com/OpenHands/agent-analysis>

## Findings

### F1. Comparable systems keep identity domains separate

The strongest cross-system pattern is explicit separation of identity types rather than trying to
 overload one ID:

- OpenTelemetry session semconv defines `session.id` separately from traces and spans.
- OpenInference reserves `session.id`, `user.id`, tags, metadata, and graph node IDs as distinct
  span attributes.
- Langfuse scoring APIs distinguish `session_id`, `trace_id`, `observation_id`, and
  `dataset_run_id`.
- Inspect distinguishes:
  - `eval_id`
  - globally unique `sample_id`
  - dataset sample `id`
  - `epoch`
- Opik experiments explicitly link `traceId` to `datasetItemId`.

Implication for JustSearch:

- The current direction of keeping `workflowRunId`, `session_id`, and `trace_id` distinct is
  correct.
- The repo should extend that same discipline to eval identity rather than stopping at
  workflow/session/trace joins.

### F2. Session semantics are now strong enough to standardize against

OpenTelemetry now has a session semantic convention with `session.start`, `session.end`,
`session.id`, and `session.previous_id`.

OpenInference JS also provides `setSession(...)` as a first-class context operation rather than
 expecting callers to overload traces.

Implication for JustSearch:

- The repo should keep `session_id` in local workflow files for compatibility.
- But exporter and mapping layers should treat `session.id` as the standards-aligned external name.
- If future Node-side direct OTel is added, it should map repo `session_id` to `session.id`
  rather than introducing a third session field.

### F3. MCP-aware observability also separates session and trace identity

The OTel MCP semantic conventions now include `mcp.session.id` and explicitly recommend trace
 context propagation in MCP request metadata.

Implication for JustSearch:

- For MCP-backed agent workflows, JustSearch should treat MCP session identity as a contextual join
  field, not as a replacement for `workflowRunId`.
- This reinforces the repo's existing rule that transport tracing and logical workflow identity are
  different concepts.

### F4. Mature evaluation systems link traces to dataset items and versions

Opik's experiment model links traces to dataset items. Dataset versions are immutable and
 experiments remain permanently associated with the version used.

Inspect similarly exposes:

- globally unique `sample_id`
- parent `eval_id`
- original dataset `id`
- typed immutable sample metadata

Implication for JustSearch:

- The next attribution model should include optional eval identity fields such as:
  - `evalRunId`
  - `evalSampleId`
  - `datasetItemId`
  - `datasetVersionId`
- This is the main refinement that the external research adds beyond the initial hypothesis.
- Without these fields, JustSearch can compare workflow runs, but cannot cleanly connect them to
  reproducible eval populations the way Opik and Inspect do.

### F5. External eval logs and observability traces are different evidence planes

Inspect logs are optimized for evaluation replay and analysis:

- per-eval logs
- per-sample metadata
- message and event history
- resumability and retry

Opik is optimized for trace visibility and trace-to-dataset experimentation.

Anthropic's official Claude Code monitoring is different again:

- it emits OTel metrics and events/logs
- it includes `session.id` as a standard attribute
- it does not by itself guarantee the spans needed for trace viewers like Opik

Implication for JustSearch:

- The current layered model is correct:
  - workflow correctness: repo-local workflow evidence
  - session behavior/cost: agent analytics and vendor telemetry
  - shared trace visibility: Opik
  - comparative external eval: Inspect AI
- Do not collapse these into one "agent quality" substrate.

### F6. Official agent telemetry can improve attribution, but not replace workflow evidence

Anthropic's official Claude Code telemetry is now a real OTel source with session, cost, tool, and
 event data.

That is valuable because it provides a standards-based agent-session signal that could be joined
 with repo workflow runs.

However:

- it is metrics and events/logs oriented
- trace backends still need actual spans for trace visualization
- it says nothing about repo-specific workflow correctness, cleanup semantics, or attached local
  artifacts

Implication for JustSearch:

- Official agent telemetry should be treated as a session overlay and join input.
- It should not replace repo-owned workflow evidence.
- If adopted, it should probably feed the tempdoc 118 / `scripts/agent-analytics` layer first,
  not the workflow system of record.

### F7. Benchmark ecosystems still separate harnesses from analysis layers

OpenHands continues to separate benchmark execution infrastructure from downstream analysis tools.
That matches the current JustSearch direction more than it challenges it.

Implication for JustSearch:

- Inspect-style external harnessing and repo-local workflow telemetry should remain separate layers.
- The repo does not need to force all comparative eval semantics into the local workflow contract.

## What changed from the pre-research hypothesis

### No change

The research does not weaken these recommendations:

1. Keep repo-owned local workflow evidence canonical.
2. Keep `workflowRunId`, `session_id`, and `trace_id` distinct.
3. Keep Opik and Inspect AI optional rather than default infrastructure.
4. Keep tempdoc 118's process-hygiene scoring and interventions as a session-centric overlay, not
   as the workflow truth layer.

### Material refinement

The research adds one important theoretical requirement:

- JustSearch should treat eval identity as a first-class join domain, not as an afterthought.

In practical terms, the full identity model should eventually distinguish at least:

- `agentSessionId`
- `workflowRunId`
- `trace_id`
- `dagRunId`
- `evalRunId`
- `evalSampleId`
- `datasetItemId`
- `datasetVersionId`

## Recommendations and status

### 1. Attribution contract — done

Session-to-workflow attribution is implemented. The contract is documented in
`docs/reference/contracts/workflow-telemetry-contract.v1.md` (agent-driven attribution semantics,
line 128).

Key implementation files:

- `scripts/agent-analytics/hooks/export-session-env.mjs` — SessionStart hook, writes
  `current-session-id` file and appends to `CLAUDE_ENV_FILE`
- `scripts/lib/workflow-telemetry.mjs` — `resolveWorkflowSessionId()` with 3-tier resolution
- `scripts/bench/report-workflow-attribution.mjs` — attribution report with 3 classifications
- 10 maintained entrypoints accept `--session-id` (all DAG runners, lifecycle adapter, closure
  runner, search workflow)

### 2. Eval identity — deferred

Optional eval fields (`evalRunId`, `evalSampleId`, `datasetItemId`, `datasetVersionId`) are not
yet added to the workflow model. Deferred until a concrete eval harness (Inspect AI or equivalent)
consumes workflow runs and needs these joins. This is the main gap identified by external research
(F4).

### 3. Session attribution mandatory for agent-driven runs — done

Agent-driven runs automatically receive `session_id` via the file-based fallback. Manual shell
runs and CI runs remain unattributed by default. Subagent attribution is parent-owned (phase-1
policy; `subagent-guide.mjs` instructs subagents to pass `--session-id`).

### 4. Official agent telemetry as overlay — theory only

Claude Code's OTel metrics/events are acknowledged as a session overlay input, not a replacement
for workflow evidence. No implementation needed unless the repo adopts official telemetry export.

### 5. Workflow-family SLOs — done

SLOs are defined, tested, and gating. Implementation:

- `WORKFLOW_SLOS` constant + `evaluateWorkflowSLOs()` in `scripts/lib/workflow-telemetry.mjs`
- Timing thresholds: `readyHttpMs` (direct <= 30s, legacy <= 120s), `indexServingMs`
  (direct <= 45s, legacy <= 180s), derived from ~3x p95 of N=173 runs
- Boolean/count invariants: `portsClosed: true`, `orphanProcessReaped: false`,
  `staleArtifactCount: 0`, `parameterBindingErrorCount: 0`, `circuitBreakerDuringBaseline: false`
- Closure runner (`run-workflow-quality-closure.mjs`) exits non-zero on violations (schema v2)
- Comparison tool (`compare-workflow-runs.mjs`) includes `slo` section per run
- Contract updated in `workflow-telemetry-contract.v1.md` (line 215)
- 9 SLO test cases + 1 export shape validation in `test-workflow-telemetry.mjs`

SLOs were implemented before attribution because they made existing infrastructure immediately
actionable without requiring new identity fields or schema changes.

## Windows CLAUDE_ENV_FILE platform issue

`CLAUDE_ENV_FILE` sourcing is broken on Windows. The Claude Code binary contains a hardcoded
bail-out:

```javascript
if (L8() === "windows") return y("Session environment not yet supported on Windows"), null;
```

The SessionStart hook writes the env file correctly, but Claude Code never sources it before Bash
tool calls. Tracked upstream:
[anthropics/claude-code#27987](https://github.com/anthropics/claude-code/issues/27987) (open, no
fix as of Claude Code 2.1.72).

**Workaround**: the file-based fallback (`tmp/agent-telemetry/current-session-id`) bypasses
`CLAUDE_ENV_FILE` entirely. The SessionStart hook writes the raw session ID there before attempting
the env file append. `resolveWorkflowSessionId()` reads it as tier 3. The `CLAUDE_ENV_FILE` append
is preserved for when Anthropic ships the Windows fix.

The hook also includes a permanent directory guard: if `CLAUDE_ENV_FILE` is set but `stat()` says
it's a directory (observed as EISDIR on Windows), the hook logs the path and type and exits
cleanly without attempting the write.

## Smoke test record (2026-03-10)

Repo-side attribution was verified end-to-end with a manual workaround (env var export required
because the automatic `CLAUDE_ENV_FILE` bridge is broken on Windows):

1. Session ID `2d135f1f-03dd-409b-8022-cbee9d14756a` sourced from `session_start` event in
   `tmp/agent-telemetry/events.ndjson`
2. Manually exported `JUSTSEARCH_AGENT_SESSION_ID` in Bash
3. Ran `eval-backend-lifecycle.mjs start --engine direct --run-id attr-smoke-1773168368`
   — index_serving reached in ~8s
4. Ran `eval-backend-lifecycle.mjs stop` — clean stop, `portsClosed: true`
5. `meta.json` confirmed: `session_id` matches, `lifecycleEngine: "direct"`,
   `source: "eval-backend-lifecycle"`
6. Attribution report (`report-workflow-attribution.mjs`) classified the run as **`attributed`**
   — 1 of 199 total runs attributed
7. Session ID consistent across: Bash env, `meta.json`, attribution report

This proves the repo-side plumbing works correctly. The only broken link is the automatic
`CLAUDE_ENV_FILE` bridge (platform issue, not repo issue).

## Open items

1. **Eval identity fields** — deferred. Add `evalRunId`, `evalSampleId`, `datasetItemId`,
   `datasetVersionId` to the workflow model when a concrete eval harness needs them.
2. **Attribution report unit tests** — `classifyRun()` in `report-workflow-attribution.mjs` has
   no dedicated tests. Coverage is indirect (smoke test only).
3. **Upstream Windows fix** — `CLAUDE_ENV_FILE` sourcing blocked by
   [anthropics/claude-code#27987](https://github.com/anthropics/claude-code/issues/27987).
   File-based fallback works in the meantime.

## Conclusion

External prior art does not overturn the current JustSearch direction. It strengthens it:

- Keep the local-first workflow contract — **done**, canonical
- Keep external overlays optional — **done**, no mandatory infrastructure added
- Keep identity domains separate (`workflowRunId`, `session_id`, `trace_id`) — **done**,
  enforced in the contract
- Add eval identity as the missing fourth join domain — **deferred** until concrete need
- Use official agent telemetry as a session overlay — **acknowledged**, no implementation needed
- Define SLOs and gate on them — **done**, closure runner exits non-zero on violations
- Propagate session attribution into workflow evidence — **done**, 3-tier resolution with
  file-based Windows fallback
