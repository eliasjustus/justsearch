---
title: "265: Agent Workflow Observability and Evaluation Stack Selection"
type: tempdoc
status: done
created: 2026-03-06
updated: 2026-03-07
---

> NOTE: Child tempdoc split out of tempdoc 262. This is the architecture-facing document for external stack selection and current-repo baseline analysis.

# 265: Agent Workflow Observability and Evaluation Stack Selection

## Purpose

Capture the external open-source options relevant to workflow quality, the workflow-analysis tech
already present in this repo, and the adopted optional architecture direction for the completed
scoped program.

This doc preserves the stack-selection material that originally accumulated inside tempdoc 262.

## Relationship to tempdoc 262

- Tempdoc `262-agent-workflow-quality-architecture.md` is the umbrella architecture/index doc.
- This tempdoc is the observability/evaluation stack-selection child doc.

## Closure update (2026-03-07)

This stack-selection lane is now closed for the scoped pilot program.

Adopted optional layers:

- self-hosted Opik as the supported optional external trace backend
- Inspect AI with Claude Code CLI as the supported optional external workflow-harness path

Non-adopted defaults:

- local NDJSON remains the canonical evidence source
- no remote backend is required for local workflow analysis
- no external harness is required for repo-local workflow comparisons

## Promoted canonical docs (2026-03-07)

The adopted scoped outcomes from this tempdoc now live in:

- observability explanation:
  `docs/explanation/08-observability.md`
- operator runbook:
  `docs/how-to/validate-workflow-quality.md`
- architecture decision:
  `docs/decisions/0010-local-first-workflow-quality-observability.md`

This tempdoc remains as the research and rationale record behind that adopted optional-stack policy.

## Open-source tech and repos worth using or copying

### Selection criteria for this repo

- We need tools that help analyze workflow quality, not just final-answer quality.
- The highest-value capabilities for JustSearch are:
  - standardized traces for agent, tool, MCP, shell, and HTTP activity
  - attachable per-trace or per-span scores and labels
  - experiment and dataset runs for comparing workflow changes
  - support for self-hosting or local analysis
  - licenses that are safe to reuse if we want to copy code or patterns

### Tier 1: strongest fits

#### 1. OpenTelemetry GenAI semantic conventions plus OpenInference

- Official sources:
  - OpenTelemetry GenAI semantic conventions:
    <https://opentelemetry.io/docs/specs/semconv/gen-ai/>
  - OpenInference repo:
    <https://github.com/Arize-ai/openinference>
- Why it fits:
  - This is the best standards layer for representing agent, model, and tool activity.
  - OpenTelemetry now has GenAI events, metrics, model spans, and agent spans, plus MCP semantic
    conventions.
  - OpenInference already ships instrumentation for OpenAI SDK, Claude Agent SDK, MCP, LiteLLM,
    LangChain, and related stacks, and is designed to work with any OTel-compatible backend.
- Reuse posture:
  - Use directly as the canonical event and trace model.
  - Copying patterns or code carefully is acceptable; OpenInference is Apache-2.0.
- Best use in JustSearch:
  - Convert the current ad hoc workflow telemetry into OTel/OpenInference spans and events.
  - Keep repo-specific metrics as custom attributes rather than inventing a parallel schema.

#### 2. Langfuse

- Official source:
  - Repo: <https://github.com/langfuse/langfuse>
- Why it fits:
  - Strong trace, observation, and score model for attaching quality labels to workflows.
  - OpenTelemetry integration exists in the SDK stack.
  - Practical fit for self-hosted tracing, scores, datasets, and experiments around agent sessions.
- Reuse posture:
  - Use directly for a self-hosted workflow-quality dashboard if we want a productized UI quickly.
  - Copy code selectively only from the MIT-licensed portions; the repo explicitly says MIT except
    for `ee` folders.
- Best use in JustSearch:
  - Attach workflow scores and labels to traces or observations.
  - Compare workflow cohorts such as legacy dev-runner vs direct backend launcher as experiments.

#### 3. Opik

- Official sources:
  - Repo: <https://github.com/comet-ml/opik>
  - Docs: <https://www.comet.com/docs/opik/>
- Why it fits:
  - Explicitly positions itself around tracing, evaluation, production monitoring, and agentic
    workflows.
  - Strong self-hosting story including Windows-friendly local startup commands.
  - Apache-2.0 license is favorable if we want to reuse code or patterns.
- Reuse posture:
  - Use directly if we want a more evaluation-first platform than Langfuse.
  - Copying patterns or code is comparatively safe from a license perspective.
- Best use in JustSearch:
  - Track workflow traces plus online and offline evals for workflow changes.
  - Study its experiment-management and agent-optimizer concepts for workflow regression loops.

#### 4. Inspect AI

- Official sources:
  - Docs: <https://inspect.aisi.org.uk/>
  - Repo: <https://github.com/UKGovernmentBEIS/inspect_ai>
- Why it fits:
  - This is the best open-source evaluation harness in the shortlist for multi-turn, tool-using
    agentic tasks.
  - It supports tool use, MCP tools, bash, text editing, browsing, and web-based eval logs.
  - MIT license makes it safe to borrow architecture and components.
- Reuse posture:
  - Use directly if we want a benchmark and eval runner for workflow experiments.
  - Copying patterns or code is relatively low-friction.
- Best use in JustSearch:
  - Build workflow-quality eval tasks rather than only transcript analytics.
  - Reuse its log structure and task/scorer split for reproducible workflow experiments.

### Tier 2: valuable complements

#### 5. OpenHands Benchmarks plus SWE-bench

- Official sources:
  - OpenHands benchmarks repo: <https://github.com/OpenHands/benchmarks>
  - OpenHands agent-analysis repo: <https://github.com/OpenHands/agent-analysis>
  - SWE-bench repo: <https://github.com/SWE-bench/SWE-bench>
- Why they fit:
  - These are useful for outcome-side coding-agent evaluation and for understanding how mature OSS
    agent teams structure benchmark harnesses, rich logs, and reproducible containerized evals.
  - OpenHands Benchmarks is MIT-licensed and includes rich logging of tool calls and summaries.
  - OpenHands agent-analysis is also MIT-licensed and is closer to our exact problem: analyzing
    agent trajectories, costs, and benchmark outcomes from real runs rather than only scoring final
    pass/fail.
  - SWE-bench is the canonical repository-level coding benchmark and already uses a reproducible
    Docker-based harness.
- Reuse posture:
  - Use as reference and harness inspiration more than as drop-in workflow analytics.
  - Copying patterns or code selectively from the OpenHands repos is plausible.
- Best use in JustSearch:
  - Borrow evaluation-runner structure, rich per-instance logs, and reproducibility patterns.
  - Borrow agent-analysis patterns for per-run telemetry joins, cost and outcome reporting, and
    workflow-slice analysis.
  - Use them as the task-outcome side while JustSearch-specific telemetry covers workflow process.

#### 6. OpenLIT

- Official sources:
  - Repo: <https://github.com/openlit/openlit>
  - Docs: <https://docs.openlit.io/>
- Why it fits:
  - OTel-native, self-hosted, and broader than pure trace storage.
  - Includes tracing, evaluations, dashboards, prompt management, and guardrails.
  - Apache-2.0 licensed.
- Reuse posture:
  - Use directly if we want an OTel-native platform with more batteries included.
  - Copying patterns or code is license-friendly.
- Best use in JustSearch:
  - Good candidate if we want one platform for OTel traces plus AI-specific dashboards without
    committing to Phoenix, Langfuse, or Opik.

#### 7. SigNoz and Jaeger

- Official sources:
  - SigNoz repo: <https://github.com/SigNoz/signoz>
  - Jaeger repo: <https://github.com/jaegertracing/jaeger>
- Why they fit:
  - They are strong generic OTel backends.
  - Useful if we want traces, logs, and metrics at the infra level and do not need AI-native eval
    UX.
- Reuse posture:
  - Use directly as backends.
  - Do not expect them to solve workflow-quality semantics on their own.
- Best use in JustSearch:
  - Good destination layers if we standardize on OTel but keep custom workflow analytics in repo.

### Tier 3: niche but useful for specific slices

#### 8. PM4Py

- Official source:
  - Repo: <https://github.com/process-intelligence-solutions/pm4py>
- Why it fits:
  - Best open-source process-mining library for turning event logs into variants, bottlenecks,
    directly-follows graphs, and discovered process models.
  - Very relevant if we want to analyze recurring agent trajectories like:
    `start -> timeout -> port forensics -> kill -> restart -> health poll -> retry`.
- Reuse posture:
  - Use only as an analysis tool or service unless AGPL-3.0 is acceptable.
  - Do not casually copy or vend code into JustSearch.
- Best use in JustSearch:
  - Export workflow event logs into process-mining format and analyze them offline.

#### 9. RAGChecker and Giskard

- Official sources:
  - RAGChecker: <https://github.com/amazon-science/RAGChecker>
  - Giskard OSS: <https://github.com/Giskard-AI/giskard-oss>
- Why they fit:
  - These are good for diagnostic quality slices such as RAG or agent-evaluation failures.
  - RAGChecker is especially strong for fine-grained retrieval and generation diagnostics.
  - Giskard is broader on AI testing, risk, and vulnerability scanning.
- Reuse posture:
  - Both are Apache-2.0 and reasonable to learn from or integrate.
- Best use in JustSearch:
  - Not the core workflow-quality platform.
  - Useful as specialized evaluators attached to workflow traces or regression suites.

#### 10. Promptfoo

- Official source:
  - Repo: <https://github.com/promptfoo/promptfoo>
- Why it fits:
  - Strong for declarative evals, CI/CD, and regression gates across models, prompts, and agents.
  - MIT licensed and simple to run locally.
- Reuse posture:
  - Use directly for regression-style evals.
  - Copying patterns or code is easy from a license standpoint.
- Best use in JustSearch:
  - Good as a lightweight CI gate for prompt and agent behavior, not as the main workflow
    analytics system.

### Important license caveat

- Phoenix is powerful but not permissive for code-copying.
  - Repo: <https://github.com/Arize-ai/phoenix>
  - The repo states it is licensed under Elastic License 2.0.
- Practical meaning for this doc:
  - Phoenix is still a strong use or integrate candidate for internal and self-hosted observability.
  - It is not the best candidate to copy code from into JustSearch if we want permissive reuse.

### Recommended shortlist for JustSearch

#### If the goal is "standardize telemetry first"

- `OpenTelemetry GenAI semantic conventions`
- `OpenInference`
- backend/UI choice: `Langfuse` or `Opik`

#### If the goal is "build a rigorous workflow eval harness"

- `Inspect AI`
- `OpenHands Benchmarks` and `OpenHands/agent-analysis` as harness and reference patterns
- `SWE-bench` as outcome-side benchmarking infrastructure

#### If the goal is "mine recurrent bad workflows from logs"

- `PM4Py` for offline process mining
- current `scripts/agent-analytics` pipeline as the repo-specific event source

## Relevant tech already present in this repo

### 1. Real OpenTelemetry is already in use locally

- `modules/telemetry/build.gradle.kts` already depends on `opentelemetry-api`,
  `opentelemetry-sdk`, `opentelemetry-sdk-metrics`, and `opentelemetry-sdk-trace`.
- `modules/telemetry/src/main/java/io/justsearch/telemetry/LocalTelemetry.java` builds an OTel
  `SdkMeterProvider`, registers histogram views, and exports to local NDJSON via
  `NdjsonMetricExporter`.
- `modules/telemetry/src/main/java/io/justsearch/telemetry/TracingBootstrap.java` boots a real
  `OpenTelemetrySdk`, enables `W3CTraceContextPropagator`, and exports spans to
  `<dataDir>/telemetry/traces.ndjson`.
- `docs/explanation/08-observability.md` is explicit that the repo already uses a local-first OTel
  stack rather than a collector or service backend.

### 2. The repo already has partial GenAI-semconv alignment

- `modules/telemetry/src/main/java/io/justsearch/telemetry/NdjsonSpanExporter.java` allowlists
  GenAI-flavored attributes such as:
  - `gen_ai.operation.name`
  - `gen_ai.agent.id`
  - `gen_ai.tool.name`
  - `gen_ai.usage.input_tokens`
  - `gen_ai.usage.output_tokens`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java` emits hierarchical
  OTel spans for agent invocation, chat, and tool execution.
- `modules/ai-worker/src/main/java/io/justsearch/aiworker/service/AiServiceImpl.java` emits
  gRPC-server spans around intent, embed, and classify operations.
- `SSOT/schemas/genai-semconv.mapping.json` already records a mapping from internal LLM keys to
  OTel GenAI fields.
- This is not the same as adopting OpenInference, but it means the repo is already structurally
  closer to that ecosystem than it first appeared.

### 3. The repo already has a local agent workflow analytics pipeline

- `.claude/settings.local.json` wires Claude Code hooks into `scripts/agent-analytics/hooks/*`.
- `scripts/agent-analytics/hooks/dispatch.mjs` writes append-only NDJSON events under
  `tmp/agent-telemetry/`.
- The current pipeline already covers:
  - event capture
  - session analysis
  - boolean workflow flags
  - deterministic scoring
  - transcript-based cost estimation
  - LLM-as-judge outcome evaluation
  - HTML dashboard generation
- `scripts/agent-analytics/generate-dashboard.mjs` renders a self-contained dashboard using
  Chart.js from CDN.
- `scripts/agent-analytics/evaluate-session.mjs` is already a judge and eval component,
  implemented with transcript condensation plus a structured rubric.

### 4. The app has a second, separate agent-event store

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentRunStore.java` persists per-session
  `meta.json` plus append-only `events.ndjson` for agent runs.
- `docs/explanation/08-observability.md` also documents a distinct in-app `TraceContext` envelope
  and OTel span tree for the runtime agent loop.
- So the repo already has two relevant telemetry families:
  - Claude-side workflow analytics (`scripts/agent-analytics`)
  - app/runtime agent telemetry (`modules/app-agent` plus `modules/telemetry`)

### 5. Existing workflow harnesses are already fairly mature

- `scripts/ci/dag-runner-beir-gate.mjs` is already a structured eval DAG around lifecycle start,
  eval, diff, and cleanup.
- `scripts/lib/bench/eval-backend-lifecycle.mjs` provides a normalized lifecycle adapter over
  direct vs legacy engines.
- `scripts/dev/justsearch-dev-mcp/server.mjs` already exposes structured MCP tools for dev-stack
  workflow operations and probes readiness through HTTP, not only through logs.
- `scripts/dev/justsearch-dev-mcp/log.mjs` optionally emits NDJSON for MCP activity under
  `tmp/dev-runner/justsearch-dev-mcp.ndjson`.

### 6. Important explicit non-hits

- I found no code-level integration of:
  - `Langfuse`
  - `Opik`
  - `OpenInference`
  - `Phoenix`
  - `Promptfoo`
  - `PM4Py`
  - `Inspect AI`
  - `OpenHands`
- Those appear in tempdocs and research notes, but not in the active runtime or tooling code paths
  under `scripts/`, `modules/`, `.claude/`, `SSOT/`, or top-level build configuration.

## Best long-term solution

### Recommendation in one line

- The best current working recommendation is an OTel-native hybrid stack:
  - canonical schema and transport: `OpenTelemetry`, with a repo-owned versioned mapping aligned to
    `OpenInference` and OTel GenAI conventions
  - default local storage: keep the existing local NDJSON exporters
  - shared analysis and eval backend: `Opik`
  - benchmark and eval harness: `Inspect AI`
  - offline trajectory mining: `PM4Py` (optional, analysis-only)

### Why this is the best fit for JustSearch

#### 1. It fits what the repo already is

- JustSearch already uses real OpenTelemetry in the app runtime, already exports local
  `metrics.ndjson` and `traces.ndjson`, already propagates W3C trace context across process
  boundaries, and already has a local workflow analytics pipeline.
- So the long-term architecture should standardize and connect those layers, not replace them.

#### 2. OTel should be the canonical substrate, not a vendor tool

- Official OpenTelemetry GenAI conventions are still marked Development, so directly binding the
  repo to the newest experimental names would be brittle.
- The repo should therefore keep a versioned internal schema and mapping layer:
  - emit stable repo-owned event names and fields
  - map them to OTel, OpenInference, and GenAI attributes at the exporter boundary
  - preserve backward compatibility in local NDJSON and test fixtures
- This matches the repo's current SSOT and config style better than hard-coding a third-party
  schema into every module.

#### 3. Opik is the best long-term backend and UI layer

- Among the surveyed open-source platforms, `Opik` is the best fit because it combines:
  - tracing
  - evaluations
  - experiments
  - self-hosting
  - direct OpenTelemetry ingestion
  - TypeScript support
- That lines up unusually well with JustSearch's mixed Java, Node, and script-heavy environment.
- Most importantly, Opik's experiment model explicitly links traces to dataset items, which is
  exactly the missing connective tissue between:
  - repo workflow traces
  - eval runs
  - outcomes and regressions

#### 4. Inspect AI is the best external harness layer

- `Inspect AI` is the strongest long-term harness for workflow-quality experiments because its
  official docs explicitly support:
  - tool use
  - MCP tools
  - sandboxing
  - external agents such as Claude Code, Codex CLI, and Gemini CLI
  - log, dataframe, and eval-set analysis
- That makes it the best OSS framework for running controlled comparative workflow studies without
  forcing the repo to invent its own benchmark harness from scratch.

#### 5. PM4Py should remain an offline specialty tool

- PM4Py is the right tool for recurring trajectory analysis like:
  - `timeout -> status probe -> port forensics -> kill -> restart -> retry`
- But because it is AGPL-3.0, it is best treated as an offline analysis tool, not as a core
  embedded repo dependency.

### What should not be the long-term center

#### Not "custom scripts only"

- The repo's current custom analytics are valuable and should remain.
- But keeping them as the only long-term layer would preserve the current fragmentation and make
  the repo continue to own too much bespoke analysis, UI, and eval infrastructure.

#### Not Langfuse as the primary long-term center

- Langfuse is strong and still a very good option.
- But for this repo's stated problem, it is slightly less aligned than Opik because the harder need
  is workflow evaluation plus trace-to-dataset comparison, not primarily prompt management or
  general LLM product analytics.

#### Not Phoenix as the center

- Phoenix is powerful, but ELv2 makes it a worse long-term foundation if we want freedom to copy or
  adapt architecture or code freely.

### Long-term target architecture

#### Layer A: canonical telemetry

- Keep `modules/telemetry` as the canonical in-product telemetry stack.
- Extend repo tooling (`agent-analytics`, MCP dev server, lifecycle DAG runners, eval wrappers) to
  emit OTel-aligned workflow spans and events as well.
- Maintain a repo-owned semconv mapping in `SSOT/`.

#### Layer B: local-first default

- Continue writing local NDJSON by default for:
  - offline use
  - privacy-sensitive local debugging
  - deterministic evidence bundles
  - low-ops desktop and dev workflows

#### Layer C: shared remote and self-hosted analysis

- Add optional OTLP export to `Opik`.
- Use Opik for:
  - cross-run trace inspection
  - experiment comparisons
  - judge scores and feedback
  - regressions over time
  - joining traces with eval datasets

#### Layer D: controlled evaluation

- Use `Inspect AI` for benchmark-style workflow evaluation of:
  - Claude Code
  - other CLI agents through the generic runner when needed
  - repo-native agent flows
  - MCP-backed workflows

#### Layer E: trajectory mining

- Export selected workflow traces to process-mining format and analyze them in `PM4Py`.

## Implementation path from the current repo

### What can be implemented now without a platform decision

- Keep the local NDJSON exporters as the default sink.
- Keep the repo-owned semantic mapping in `SSOT/` as the compatibility boundary.
- Extend launcher and workflow tooling to emit OTel-aligned fields even before any remote backend is
  wired in.
- Treat external backend export as additive fan-out, not as a replacement for the local-first
  evidence path.

### Implemented platform-facing baseline in repo head

The repo now has a concrete pilot-grade baseline for external workflow observability:

- `modules/telemetry` now includes the OTLP exporter dependency in production code
- `TracingBootstrap` now supports optional OTLP/HTTP trace fan-out via standard OTel env vars
- local `traces.ndjson` remains mandatory even when OTLP fan-out is enabled
- backend traces now carry repo-owned workflow resource attributes:
  - `justsearch.workflow.run_id`
  - `justsearch.workflow.family`
- local NDJSON trace export now preserves those workflow resource attributes
- a small pilot helper now exists:
  - `scripts/bench/run-workflow-otlp-pilot.mjs`
  - it runs a backend-only lifecycle through the adapter and emits the local evidence paths and
    expected resource-attribute join keys
- an Opik-oriented verifier now exists:
  - `scripts/bench/verify-opik-otlp-pilot.mjs`
  - it validates local evidence, then polls a configured remote query endpoint for the workflow
    join keys
- a repo-managed self-hosted Opik wrapper now exists:
  - `scripts/bench/opik-local-stack.mjs`
  - it manages `ensure`, `start`, `status`, `stop`, and `destroy` for a pinned local Opik stack
- a generic Inspect AI external-CLI harness now exists with Claude default plus Codex transition
  shims:
  - `scripts/bench/inspect_cli_workflow_pilot.py`
  - `scripts/bench/run-inspect-cli-workflow-pilot.mjs`
  - `scripts/bench/inspect_codex_workflow_pilot.py`
  - `scripts/bench/run-inspect-codex-workflow-pilot.mjs`

## Real pilot execution status

- Opik pilot:
  - self-hosted stack was started locally through `scripts/bench/opik-local-stack.mjs`
  - pinned local state was persisted to `tmp/opik-local/state.json`
  - `scripts/bench/verify-opik-otlp-pilot.mjs` completed a real verification run
  - local NDJSON evidence was preserved while remote visibility was verified via ClickHouse query
  - recorded result:
    - `remoteVerification.verified=true`
    - `remoteVerification.queryMode=clickhouse-sql`
    - `remoteVerification.count=1`
- Inspect AI plus Claude pilot:
  - `scripts/bench/run-inspect-cli-workflow-pilot.mjs --enable` completed successfully
  - log:
    - `tmp/inspect-ai/claude-workflow-pilot/2026-03-07T02-15-59+00-00_agent-cli-backend-lifecycle-pilot_5STX7Ao39THnHcgVdCPTdi.json`
  - recorded result:
    - `inspectResult.accuracy=1`
    - explanation: `External CLI backend lifecycle pilot completed`
  - Codex-named runners remain only as deprecated transition shims
  - practical note:
    - Claude's reliable non-interactive mode in this environment was `--output-format json`
    - `--json-schema` remains supported by env hook but was not the stable default here

### What the current codebase implies for OTel export work

- `modules/telemetry/build.gradle.kts` now has the OTLP exporter dependency in production and
  `TracingBootstrap` wires it as optional fan-out.
- Repo-side Node tooling does not currently have a direct OTel or OpenInference integration layer.
  The UI lockfile already carries `@opentelemetry/api` transitively, but that is not the same as a
  deliberate workflow-instrumentation path.
- The OTLP path is now feasible and implemented as traces-only fan-out, but it is still not
  "always on"; remote export remains opt-in and local-first.
- Prefer standard OTel exporter configuration instead of repo-specific endpoint knobs where possible:
  - `OTEL_TRACES_EXPORTER=otlp`
  - `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
  - `OTEL_EXPORTER_OTLP_HEADERS`
  Sources:
  - <https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/>
  - <https://opentelemetry.io/docs/concepts/sdk-configuration/otlp-exporter-configuration/>

### External constraints verified from official docs

- OpenTelemetry GenAI semantic conventions are still marked `Development`, which supports keeping a
  repo-owned mapping layer instead of hard-binding the codebase to unstable field names.
  Source: <https://opentelemetry.io/docs/specs/semconv/gen-ai/>
- Opik's OTel integration docs describe OpenTelemetry ingestion and currently call out HTTP support,
  which makes OTLP/HTTP the safer first export path for a pilot.
  Source: <https://www.comet.com/docs/opik/integrations/opentelemetry>
- Inspect AI's docs expose an Agents API and sandbox-agent-bridge path for external CLI agents,
  which is enough to pilot Claude Code workflow tasks without redesigning the repo's own agent loop
  first.
  Sources:
  - <https://inspect.aisi.org.uk/agent-bridge.html>
  - <https://inspect.aisi.org.uk/reference/inspect_ai.agent.html>
  - <https://inspect.aisi.org.uk/eval-logs.html>
- OpenInference's active repo positions itself as instrumentation and semantic-convention glue for
  OTEL-compatible backends; it fits best here as an alignment target and selective tooling source,
  not as the first system of record.
  Sources:
  - <https://github.com/Arize-ai/openinference>
  - <https://github.com/Arize-ai/open-inference-spec>

### Decision matrix outcome

- Self-hosted Opik
  - local-first and Windows fit: pass
  - reversibility: pass
  - local-evidence coexistence: pass
  - real pilot validation: pass
  - decision: adopt as an optional supported external backend
- Inspect AI plus Claude Code
  - external harness fit: pass
  - local evidence preservation: pass
  - real pilot validation: pass
  - decision: adopt as an optional supported external workflow harness
- Default operating model
  - keep repo-owned NDJSON and search-eval artifacts canonical
  - keep both external layers opt-in rather than default-on

## Privacy, propagation, and export policy

### Default stance

- Remote export should remain opt-in.
- Local NDJSON should remain the canonical local evidence path.
- Workflow telemetry should extend the repo's current redaction and allowlist model rather than
  introducing a second, looser export path.

### Propagation rule

- Use W3C Trace Context for transport-level tracing when distributed traces are needed.
  Source: <https://www.w3.org/TR/trace-context/>
- Do not overload repo session IDs or workflow run IDs into literal `traceparent` values.
- Keep logical workflow join keys as repo fields and span attributes, not as assumptions about
  transport trace identity.

### Baggage rule

- Do not use W3C or OTel baggage as the default carrier for workflow joins, user paths, queries, or
  other potentially sensitive workflow metadata.
- Reasons supported by official docs:
  - baggage values are propagated downstream and can be exposed broadly in request flows
  - baggage has no built-in integrity protection
  Source: <https://opentelemetry.io/docs/concepts/signals/baggage/>
- If baggage is ever used, it should be limited to low-sensitivity machine correlation fields and
  remain explicitly opt-in.

### Redaction and minimization rule

- Continue the repo's existing approach:
  - query redaction at logging boundaries
  - explicit attribute allowlists for NDJSON exporters
  - low-cardinality tags for metrics
- Remote export should initially exclude:
  - raw search queries
  - raw prompts
  - file contents
  - local paths unless a workflow explicitly depends on them and the sink is trusted
- Attribute-limit controls from the OTel SDK are a secondary safeguard, not the primary privacy
  mechanism.
  Source: <https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/>

## Schema and compatibility policy for externalization

### Repo-owned schema remains canonical

- Local workflow NDJSON should keep explicit `schema_version` fields and additive evolution rules.
- New remote integrations should map from repo-owned schemas rather than replacing them as the
  source of truth.

### Semconv mapping policy

- Because OTel GenAI semantic conventions are still marked `Development`, external semconv names
  should be treated as mapping targets, not as the repo's only canonical contract.
  Source: <https://opentelemetry.io/docs/specs/semconv/gen-ai/>
- The repo should maintain a versioned mapping layer in `SSOT/` for:
  - workflow events
  - agent events
  - OTel or OpenInference export names

### Versioning policy

- Reuse the repo's existing compatibility style:
  - additive fields first
  - explicit schema versions
  - dual-write or adapter windows when shapes change
  - pure upcasters where durable stored artifacts need migration
- This matches the existing patterns already present in:
  - status and lifecycle contracts
  - `AgentRunStore.SchemaUpcaster`
  - DAG `run-state.json`
  - bench dual-write helpers

## Why Node-side OTel is not phase 1

- The current Node tooling is:
  - short-lived
  - NDJSON-first
  - already structured enough to support a run-centric measurement layer
- So the first implementation step should be:
  - normalize JSON envelopes and join keys in Node tooling
  - keep Java runtime export as the first OTLP pilot
  - add Node-side direct OTel only where it proves materially better than local NDJSON plus
    conversion
- This keeps the early implementation burden low while preserving the option to instrument selected
  high-value runners later.

## Exit strategy and lock-in control

- The repo should remain able to operate in three modes:
  - local NDJSON only
  - local NDJSON plus optional remote export
  - offline conversion of local evidence into a remote backend after the fact
- Avoid platform-only canonical fields in the main repo evidence model.
- Any remote scores, labels, or judge outputs that matter for decisions should be exportable back
  into repo-controlled artifacts.
- This is the practical requirement that keeps `Opik` or any other backend an optional layer rather
  than a new single point of dependence.

## Post-2026-03-06 scope correction

Recent search-eval hardening commits mean this doc must be careful not to restate already-landed
repo capabilities as if they were still external-stack requirements.

What those commits already provide locally:

- richer BEIR metrics/provenance artifacts
- hardened search-eval artifact selection and diff semantics
- readiness aggregation for promotion/capacity reporting

Implication for stack selection:

- the external stack lane should not replace those local search-eval artifacts
- it should layer on top of them for:
  - cross-run traceability
  - shared dashboards and experiments
  - optional remote judging or comparative harnessing
- this further supports the local-first rule:
  repo-owned files remain canonical, remote systems remain optional views and experiment backends

### Final stance

- For the implemented current scope, the adopted optional direction is:
  - standardize the repo on `OpenTelemetry` plus a repo-owned semconv mapping
  - keep local NDJSON as the default local-first sink
  - use self-hosted `Opik` as the supported optional shared observability backend
  - use `Inspect AI` with `Claude Code` as the supported optional comparative workflow harness

That combination best matches the repo's current architecture, minimizes wasted migration work,
avoids overcommitting to unstable GenAI schema names, and gives JustSearch a credible path from
local scripts to reproducible workflow-quality analysis at scale.

## Status for this pass

- Closed in this pass:
  - Java OTLP fan-out pilot plumbing
  - repo-owned workflow join attributes in local and exported traces
  - self-hosted Opik stack management and real pilot validation
  - Inspect AI plus Claude pilot harness and real pilot validation
  - optional adoption decision for both external layers
- Still intentionally not adopted as defaults:
  - always-on remote export
  - cloud-only hosting assumptions
  - replacement of local NDJSON as the canonical evidence source

## Critical assessment

### What this doc does well

- It captures the strongest current architecture direction and ties it back to technology already
  present in the repo.
- It is license-aware, which matters because part of the stated goal is deciding what can be used,
  copied, or merely studied.
- The recommendation is more grounded than a generic tooling survey because it starts from the
  existing repo baseline.

### Current weaknesses

- The doc is still trying to do three jobs at once:
  - broad OSS survey
  - current repo baseline
  - target-state recommendation
- The survey remains somewhat too broad for a decision document; the long tail is useful research,
  but it weakens the clarity of the actual shortlist.
- The compact decision matrix is sufficient for the current pilot, but not yet detailed enough to
  serve as a permanent procurement-style rubric if the repo later evaluates more platforms.
- The long-tail survey is still useful research, but it should stay secondary to the now-adopted
  optional-stack decision.

### What would improve this doc next

- Compress the long-tail survey into a shorter appendix if this tempdoc is ever promoted.
- If the repo later evaluates additional backends or harnesses, reuse the same decision-matrix
  shape so this doc remains comparable rather than growing ad hoc.

## Working notes

- This doc is a child of tempdoc 262 and should stay focused on stack selection and architecture.
- Tactical workflow fixes belong in tempdoc 263.
- Measurement semantics and empirical findings belong in tempdoc 264.
