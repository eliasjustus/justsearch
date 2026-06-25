---
title: Agent System Architecture
type: explanation
status: stable
description: "Agent loop, operation-substrate tool dispatch, token budget, durability, and MCP tool surface."
---

# 22. Agent System Architecture

JustSearch includes an agentic assistant that can search the knowledge base, browse indexed folders, request ingestion, and perform approved file operations. The agent runs in the Head process, delegates online inference to the app inference runtime, and delegates Lucene index I/O through service/Worker abstractions.

## Conversation Substrate

The agent loop does not own the HTTP/LLM surface directly — it is one *shape* within a shared conversation substrate (tempdoc 491). `ConversationEngine` (in `modules/app-services`) is the one runtime for every model-driven interaction, dispatching a request to a registered `ConversationShape` by its `ConversationShapeRef`. Shapes are declared in `ConversationShapeCatalog` (`modules/app-agent-api`); examples include `AgentRunShape` (the tool-using agent), `FreeChatShape`, `RAGAskShape`, the summarize family (`SummarizeShape` / `BatchSummarizeShape` / `HierarchicalSummarizeShape`), `ExtractShape`, `NavigateChatShape`, and `WorkflowRunShape`.

The engine runs a shape in one of two execution modes:

| Mode | Behavior |
|------|----------|
| `SHAPE_DRIVEN` | The engine delegates the entire conversation lifecycle to a registered `ShapeRunner`. This encapsulates existing implementations — the **agent loop is the canonical example**: `ToolIteratingShapeRunner` is the `AgentRunShape` (`core.agent-run`) runner, parsing the request body into an `AgentRequest` and delegating to `AgentService.runAgent`. |
| `SUBSTRATE_DRIVEN` | The engine controls the per-iteration loop itself and invokes the shape's declared SPIs in order — `PromptContributor`s (assemble the system prompt by priority), `ContextInjector`s (prepend injected messages), `OnlineAiService.streamChat`, `StreamConsumer`s (collect message deltas), then `IterationController` (decide whether to loop). |

HTTP/SSE chat endpoints route through `ChatController.dynamicHandler`, which calls `ConversationEngine.run` with an SSE sink. Before invoking either mode the engine validates the request's invocation `Audience` against the shape's declared audience (trust gating). The sections below describe the body of the `AgentRunShape` — the agent loop.

## Agent Loop

Primary entry point: `AgentLoopService.runAgent()` in `modules/app-agent` (the `AgentRunShape` body, reached via `ToolIteratingShapeRunner`).

`runAgent` is a thin driver (tempdoc 240): it builds the session, then loops over `AgentStepRunner.executeIteration(...)`, which runs one iteration and returns a typed `IterationOutcome` (`cont` / `terminated(success)`). The loop's responsibilities are split across package-private collaborators rather than one method.

`AgentLoopService` is the sole implementor of the `AgentService` interface, so over time the *non-loop* surface (read-time projections, per-run control, prompt assembly) accreted there alongside the loop. Tempdoc 584 re-decomposed that surface along the breadth axis: the read-query and live-control and prompt clusters moved to dedicated collaborators, and the read surface was segregated into a narrow `AgentRunQueries` super-interface (`AgentService extends AgentRunQueries`) so read-only consumers can depend on it alone. `AgentLoopService` is now the `runAgent` driver plus thin delegating overrides.

| Collaborator | Owns |
|---|---|
| `AgentStepRunner` | one loop iteration — per-iteration tool selection, dispatch orchestration, handoff, virtual tools |
| `AgentLlmCaller` | the LLM round-trip: `callLlmWithTools`, retry policy, `DEFAULT_MAX_TOKENS`, Hermes-format fallback parse, `<think>`-tag stripping |
| `AgentToolDispatcher` | tool execution + policy + `handleSafetyGate` approval gate (the sole direct `OperationDispatcher.dispatch` site) |
| `AgentContextCompressor` | tool-result truncation/compression (`MAX_TOOL_RESULT_CHARS`) |
| `AgentEventTracing` | `TraceContext` / OTel span decoration |
| `AgentHandoff` | multi-agent handoff tools + research-brief history pruning |
| `AgentTurnPolicy` | PRIMARY→DECIDING state machine + force-tool-call decisions |
| `AgentSessionFinalizer` | end-of-run telemetry + health-event + run-store reason emit |
| `AgentPromptComposer` | system-prompt assembly — `DEFAULT_SYSTEM_PROMPT`, the indexed-root preamble, and the condition-recovery context (tempdoc 584) |
| `AgentSessionRegistry` | the live-run session map + per-run control surface — approve/reject, cancel, autonomy dial, steering interject, budget/context-gate resolution, attach, virtual-tool completion (tempdoc 584) |
| `AgentRunQueryService` | the read-time query/projection surface behind the `AgentRunQueries` interface — session snapshots/lists, event/thread/lifecycle/presence projections over `AgentRunStore`, operation history, and resume (tempdoc 584) |

The loop follows a tool-using ReAct-style flow:

```text
1. Build the default system prompt plus indexed-root context.
2. Call the online model with the current conversation and emitted tool definitions.
3. If the model returns final text, emit completion and stop.
4. If the model calls tools:
   a. Apply loop guards before execution.
   b. Require approval for write/destructive operations.
   c. Dispatch operations through the operation substrate.
   d. Append tool results to the conversation.
5. Stop on completion, cancellation, safety limits, budget exhaustion, or unsupported state.
```

Current defaults are intentionally modest:

| Constant / setting | Current behavior |
|--------------------|------------------|
| Conversation-shape `maxIterations` default | Omitted conversation-shape requests default to `1` in `ToolIteratingShapeRunner.parseRequest`; resume/fallback paths in `AgentLoopService` can use a larger internal limit. |
| Completion token cap | `AgentLlmCaller.DEFAULT_MAX_TOKENS` is `1024`. |
| Approval timeout | `300` seconds. |
| Tool result truncation | `MAX_TOOL_RESULT_CHARS` defaults to `4000`. |
| Thinking control | Forced tool/commit turns use `SamplingParams.AGENT.withEnableThinking(false)` and the service strips `<think>` blocks when needed. The default prompt does not rely on a literal `/no_think` line. |

## Operation-Substrate Tool System

The current tool system is operation-based. Do not add new agent tools through the legacy registry path.

Key classes:

| Class | Role |
|-------|------|
| `OperationCatalog` | Canonical catalog of available operations and metadata. |
| `OperationDispatcher` | Dispatches operation calls to registered handlers. |
| `AgentToolEmitter` | Projects catalog operations into model-visible tool definitions. |
| `AgentToolsOperationCatalog` | Registers the built-in agent operations in `app-services`. |
| Operation handlers | Implement concrete behavior under `modules/app-services/.../registry/operations/handlers/`. |

Wire-name projection is deliberate. Dotted operation IDs such as `core.search-index` are projected to model-visible tool names such as `core_search_index`.

Current built-in agent-facing tool names include:

| Tool | Safety | Purpose |
|------|--------|---------|
| `core_search_index` | Read-only | Search indexed knowledge. |
| `core_browse_folders` | Read-only | Discover indexed folders and paths. |
| `core_file_operations` | Write/destructive depending on action | Move, rename, copy, delete, or create filesystem items with approval where required. |
| `core_ingest_files` | Write | Request ingestion of files or folders. |

Safety metadata lives with the operation definitions and handlers. Write/destructive operations pause for explicit user approval before execution.

## Query Pre-Processing

Search operations can use backend query helpers before retrieval:

- `FilterNormalizationService` normalizes approximate filter values to indexed vocabulary where enabled.
- `QueryUnderstandingService` can extract soft `boostFilters` from natural-language queries when `JUSTSEARCH_QU_ENABLED=true`.

Explicit caller-provided filters and boost filters take precedence over inferred values.

## MCP Tool Surface

The production MCP server exposes four task-oriented tools:

| Tool | Purpose |
|------|---------|
| `justsearch_answer` | Primary QA tool over local indexed content. |
| `justsearch_search` | Search with filters, boost filters, facets, pagination, and excerpts. |
| `justsearch_ingest` | Index files or directories. |
| `justsearch_status` | Inspect index and ingestion health. |

ADR-0015 records the design rationale for a compact task-oriented MCP surface. Local JustSearch evaluation evidence and external prompting/tool-use research should be treated separately:

- Local evidence supports the current product decision for this repository and eval setup.
- External research is directional evidence for prompt/tool-surface design, not a direct proof that every future schema change will help.

Progressive disclosure is response-driven: `facets`, contextual `hints`, citations, filters, and full-document options guide follow-up calls without adding more top-level tools.

## Token Budget and Compression

`AgentSession` tracks token usage and emits budget events. Before additional model calls, the loop checks whether enough budget remains and can attempt a final synthesis from gathered tool results.

Older tool outputs may be compressed deterministically to keep useful context while reducing prompt size. Compression is extractive and preserves recent results according to configuration.

Important environment settings include:

| Setting | Purpose |
|---------|---------|
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_ENABLED` | Enables/disables tool-result compression. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_MIN_CHARS` | Minimum result size before compression is attempted. |
| `JUSTSEARCH_AGENT_CONTEXT_COMPRESSION_KEEP_LAST_RESULTS` | Number of newest tool results left uncompressed. |
| `JUSTSEARCH_AGENT_MAX_TOOL_RESULT_CHARS` | Maximum emitted tool result size. |

## Loop Detection and Errors

The agent blocks repeated identical tool calls before execution and escalates after repeated blocked calls. Tool call identity is based on tool name and normalized JSON arguments.

Errors are classified with `AgentErrorCode` and `AgentErrorClass` so UI, SSE, persistence, and retry policy can make consistent decisions. Transient model/tool failures may retry with bounded backoff; policy, contract, cancellation, and permanent errors abort.

## Durability

Agent sessions are persisted by `AgentRunStore` as an append-only event log with checkpoints. Supported resume states include waiting for approval, ready for the model, and after a tool result. Unsupported states return `UNSUPPORTED_RESUME_STATE`.

Event persistence supports replay through the agent session event endpoint and schema upcasting for compatible older records.

## Agent Run Grounding (tempdoc 565 §3.A)

A grounded agent answer carries **one** citation authority: `AgentEvent.AgentSource` (a chunk-identified local passage — `parentDocId`, `chunkIndex`, `path`/`title`/`excerpt`, `startLine`/`endLine`). At end-of-run, `AgentSession.collectGroundingSources()` collects these from the run's executed search-tool results (the `searchResults` structured payload, keyed by chunk identity and deduped), and `AgentStepRunner.groundedDone()` attaches them — plus the per-sentence inline-citation links `AgentEvent.AgentSentenceCite` resolved by `AgentCitationResolver` (the *same* answer↔source matcher the RAG path uses) — to the terminal `AgentDone` event. The sources stand alone even when the matcher does not run; the inline marks are an enrichment layer on top, never a second authority.

Only **chunk-identified** hits are citable: a hit lacking `parentDocId` (a document-level hit, or a search over an index whose chunk-enrichment is not yet ready) is skipped, and grounding degrades to empty — observably (a `WARN` fires when search hits existed but none were citable, so an operational issue never masquerades as a dead feature). The FE renders this one list as the evidence rail + the collapsible "Sources · N" chips + the inline `[n]` marks (the "one tool-call render + one ordered run projection" governance in `governance/run-renderers.v1.json`); a reloaded conversation rehydrates the same grounding from the persisted record, so live and record renders cannot diverge.

**One run-STRUCTURE authority (tempdoc 565 §26).** The same one ordered run projection carries a typed `RunSegmentRef` facet — *which group/origin each timeline item belongs to* — computed by one `assignRunSegments` pass both the live and record projectors call. This completed the "a run is a run" unification at the run's *structure* (the facet the §15.C flatten dropped into an untyped escape hatch): a **workflow** run renders its node graph as labelled segments (the `node_started`/`node_completed` boundaries bracket each node's `node_output` content; the spine marks node boundaries), and a **background** run launched with a `conversationId` renders inline in that conversation's thread as an `origin=background` segment. The workflow shape is a *mode* of the one window, not a second surface (the `interaction-surface` gate makes a second one a build failure), reached through a **picker** that projects `/api/registry/workflows` rather than a hardcoded id. The **memory** surface split accordingly: the durable facts ("what it knows") stay the `core.memory-surface` peer, while the *activity* half ("what it did" — the presence inbox + the run-in-background launcher) folded into the retrospective drawer's Inbox tab. `RunSegmentRef` is *branded* (only `assignRunSegments` mints it) and the `run-renderers` register covers the segmentation pass, so a second run-structure renderer is caught the same way the grounding/answer leaves are.

**One DIRECTION (steering) authority — the proactive peer of Consent (tempdoc 565 §30).** Human-in-the-loop over a run has two axes: *Consent* (reactive — the agent proposes, the human authorizes; the mature `IntentGateEvaluator` lattice + ceremony + hard-stop) and *Direction* (proactive — the human sets/changes what the agent does). The latter used to be forked across the prompt, the autonomy dial, and the kill button, with the continuous case missing. §30 unifies it: the human's direction over a run is ONE control-intent channel — `initiate` (the composer prompt) · `set-posture` (the `watch/assist/auto` dial) · `interject` (a free-form *mid-run* steer) · `halt` (the stop) — and every run-control affordance dispatches through the one `dispatchRunControl` seam. The genuinely-new value is `interject`: the FE POSTs `/api/chat/agent/steer`, which queues a `volatile pendingInterject` on the live `AgentSession`; the agent loop *drains* it at the next step boundary (mirroring the `isCancelled` poll + the approval-gate mailbox), folds the text into the next LLM call as a system steering note, and emits a `directive_acknowledged` event the FE renders as a human-origin run-spine landmark + a "Your direction" chip. Consent is untouched (Direction owns the dial; the gate *consumes* its posture). The anti-drift is the `steering-surfaces` register + `check-steering-arbitration` gate: a run-control affordance that bypasses the seam — a second steer input, a hand-rolled stop — is a build failure. The run-level `halt` is the per-run `cancelSession`; the GLOBAL hard-stop stays a separate emergency circuit-breaker, not a per-run directive.

**One grounding-semantics authority, gate-locked (tempdoc 565 §15.A/§15.D.1/§15.J).** On the FE, the score→tier→grounding-class/label mapping lives once in `evidenceProjection.ts`; the tier is a *branded* `GroundingTier` only `evidenceTier` can mint (the typed seam), and every grounding surface (the rail chips, the inline-mark colouring, the hover label) derives from it — so one answer↔source similarity classifies identically everywhere. This is enforced, not just conventional: the `groundingSemantics` section of `governance/run-renderers.v1.json` + the `check-run-renderers.mjs` gate fail the build on (i) a tier symbol imported outside the registered consumer sites or (ii) a numeric-threshold re-derivation of a grounding class (`score >= 0.X ? 'grounded'`) anywhere but the authority. Combined with the build-pinned `AgentDone.sources/citations` carrier (`AgentEventPayloadConformanceTest`) and the answer-renderer/weave gate, this realises tempdoc 565 §6's "evidence-surface gate": a *second* grounding classifier is now unrepresentable by construction. The one residual that is genuinely not build-gateable — a *rendered* grounded answer that silently dropped its evidence — stays the runtime `WARN` above plus the deferred grounding-readiness signal, named honestly rather than faked as a gate.

## Action Lifecycle (tempdoc 550)

Every action an actor takes — whether the user clicked it, the agent proposed it, or a plugin requested it — flows through one spine: **one record of what was done, one judgement about whether it may, and one grant model for durable consent.** The principle: receipt, timeline, undo, trust-audit, and plan-preview are *projections* over one log, never ad-hoc re-joins; and a read-view that is plumbed but not mounted is a build failure, not a silent gap.

**One action-event log.** `ActionEvent` (in `app-observability`) is a sealed union — `Operation` / `Navigation` / `Gate` / `Grant` / `Effect` — with an explicit, deterministic id. `ActionEventStore` is the one authoritative store: id-keyed, idempotent (re-ingest on reload does not duplicate), and bounded. `ActionLedgerProjection.toWireRow` is the single projection layer; `/api/action-ledger` (snapshot) and `/api/action-ledger/stream` (SSE) are two reads of that one projection. The FE folds its local effects into the same log via `POST /api/action-ledger/events`, so the log spans the process boundary. Per-kind Outcome read-views (operation-history, navigation-history) fan into the one log on append, so they cannot diverge from it.

**One intent verdict.** `IntentGateEvaluator` (in `app-services`) computes `(sourceTier × riskTier) → gateBehavior`, the lattice, and the Global Hard-Stop state into one `IntentVerdict`. The enforcement chokepoint (`OperationExecutorImpl.enforceTrustLattice`) and the Preview face (`/api/operations/{id}/preview`) read the *same* evaluator instance — the preview is the structural-prediction read of the one verdict (no args/token; args-bound capsule verification stays enforcement-only). A consumer cannot disagree with enforcement because there is one computation.

**One grant model.** A `Grant` is a caveat-bearing, attenuable, revocable token. Two members exist: the single-use, args-bound, short-TTL **consent capsule** (`ConsentCapsuleService`, an HMAC token minted on user approval) and the **durable allow-always grant** (`DurableGrantStore`, keyed `(operationId, sourceTier)`). The autonomy dial is the *issuance policy* (which grants auto-issue per source×risk), and the **Global Hard Stop** is a *global revocation* over all non-user (UNTRUSTED) grants — a user-mediated approval survives an emergency stop. Grant lifecycle is recorded as `Grant` ActionEvents → one audit, one revocation path, one ceremony (`<jf-authorization-host>` on the FE). That one ceremony posts its verdict to **one** backend endpoint — `POST /api/chat/{approve,reject}` (tempdoc 565 §15.C) — which dispatches the agent tool-call gate (`AgentSession.approvalGates`, keyed by `sessionId`+`callId`) → the workflow GateStep/ToolStep gate (`WorkflowGateRegistry`, keyed by `callId`) → 404. "A run is a run" all the way down: the FE no longer branches the approval URL by run shape, and the forked `/api/chat/agent/{approve,reject}` + `/api/chat/workflow/{approve,reject}` routes were retired. The run-substrate differences that remain (session cancel/resume, the autonomy dial) are legitimately agent-only — workflows are stateless/deterministic — so the unification stops at the genuinely-shared *approval* concept.

**Structural verification.** Per thesis V, a substrate-shipping change is best treated as "done" only with an independent reviewer (≠ implementer) and live verification, not just compile + unit tests. This was briefly encoded as the `independent-review` discipline gate, since retired (tempdoc 530 §Remediation; the audit-dependent gates were judged not worth their cost) — it remains recommended honor-system practice rather than gate-enforced.

## Prompt Design

The default system prompt is kept compact and operational:

- Search before answering factual questions about indexed content.
- Use folder browsing when path discovery is needed, not as a mandatory first step.
- Treat paths as absolute.
- Do not retry the same failed tool call with the same arguments.
- Respect approval boundaries for write/destructive actions.
- Use indexed-root context appended at runtime.

Prompt changes should be evaluated against current tool behavior. Expanding the prompt with long implementation detail is usually worse than routing the agent to current canonical docs and compact skills.

## Key Modules

| Module | Purpose |
|--------|---------|
| `modules/app-agent-api` | Public agent API, events, error types, trace context, and operation-facing contracts. |
| `modules/app-agent` | Agent loop, session state, retry policy, run store, telemetry, and prompt assembly. |
| `modules/app-services` | Operation catalog wiring, operation dispatch, and built-in operation handlers. |
| `modules/app-inference` | Online AI runtime lifecycle used by the agent. |
| `modules/ui` | HTTP/SSE agent routes. |

## Safe Extension Path

To add or change an agent capability:

1. Add or update the operation definition in the operation catalog wiring.
2. Implement or update the operation handler.
3. Ensure `AgentToolEmitter` projects the intended model-visible wire name and schema.
4. Add approval/safety metadata at the operation layer.
5. Register any REST endpoint through route classes in `modules/ui` when the capability also needs HTTP exposure.
6. Update canonical docs and generated skills after code behavior is verified.
