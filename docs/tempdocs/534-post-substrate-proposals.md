---
title: "534 — Post-substrate proposal batch (7 forward proposals)"
type: tempdocs
status: open
consolidated: 2026-06-09
---

# 534 — Post-substrate proposal batch (7 forward proposals)

> Consolidated 2026-06-09 (post-400 tempdoc-hygiene pass) from 7 separate ~100–120-line "open — design" proposal stubs into one browsable roadmap. Each section is one original proposal verbatim; originals (535–540) retired to git history.


---

## LANGUAGE_WORKFLOW substrate (was 534)

*(consolidated from `534-language-workflow-substrate.md`)*

### 534 — LANGUAGE_WORKFLOW substrate

## Why this is strategic

488's analysis: the local LLM is *not* the limiting factor for most catalog features. Substrate is. The single most-cited missing substrate, across F2 cascade / F23 advisor / G47 scheduled / G133 ChainShape / G143 memory, is the orchestration layer that sequences (LLM call, tool call, condition check, advisory emission, human gate) into runnable workflows.

Every individual feature waiting on this substrate has been deferred slice-after-slice because the substrate doesn't exist. Each feature's deferral has a different surface-level reason, but the structural cause is the same: there's no place a multi-step LLM-orchestrated workflow *belongs*.

The decision to ship LANGUAGE_WORKFLOW is the decision to make JustSearch an agent platform vs a search product with chat. Substrate-wise, it's the integration test for everything built in the 487/490/491/494/510 era: if those substrates compose into workable workflows, the platform's positioning is real; if they don't, the substrates need adjustment.

## What a workflow is

A workflow is a state machine over typed nodes:

```
node types:
  llm-call(shape: ConversationShapeRef, prompt: Template, capture: Schema)
  tool-call(operation: OperationRef, args: Template, capture: Schema)
  branch(condition: Expression, on-true: NodeRef, on-false: NodeRef)
  wait(until: Condition | Duration | HumanGate)
  emit-advisory(class: AdvisoryClassId, payload: Template)
  return(payload: Template)

transitions:
  on-success → next-node
  on-failure → handler-node (or workflow-failure)
  on-condition → branch arms

context:
  WorkflowContext { inputs, captures-so-far, trace }
  passed by reference to each node's template + capture step
```

A workflow declaration is a Manifest record (alongside Surface, Plugin, ConversationShape, IntentSource). Each declared workflow has:

- An `id` (`core.summarize-and-extract`, `plugin.my-plugin.weekly-digest`).
- A `presentation` (label, description, icon).
- A `provenance` tier (CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN).
- An `audience` (USER / OPERATOR / AGENT).
- A `policy` declaring risk + confirmation strategy for the workflow as a whole, with per-node overrides.
- A node graph (declarative — JSON / YAML; not procedural code).
- A typed input schema and output schema.

## Three lifecycle modes

**One-shot**: user invokes; workflow runs; result returns. The simplest mode. Composes with: askAi-style triggers, command palette, agent-tool-call (workflows-as-virtual-ops per 532). Examples: F2 cascade ("summarize then extract"), G131-style multi-step extract with validation.

**Triggered**: scheduled (G47 cron) or condition-based (F23 advisor observation loop). The trigger is a separate concept that dispatches into the workflow runner. Composes with: 490's advisory substrate (the trigger fires the workflow; the workflow emits the advisory).

**Resumable**: pauses for a human gate (`wait(until: HumanGate)`). Persists state to a per-workflow file-backed store (riding 491's per-shape persistence pattern). Resumes on gate satisfaction. Composes with: 487's trust lattice (the gate is a `TYPED_CONFIRM` or `INLINE_CONFIRM`), 494's advisory (the pending gate surfaces as an advisory).

## Composition with existing substrate

- **LLM-call nodes** invoke `ConversationEngine.run(shapeId, body, audience, sink)`. The shape is registered per 491. The engine emits SSE events that the workflow runner consumes (chunk → trace; done → capture).
- **Tool-call nodes** dispatch via `BackendIntentRouter` with `TransportTag.WORKFLOW` (new value). The trust lattice (487) gates per (workflow tier × operation risk). Operation history (490) records the dispatch.
- **Advisory emission nodes** project through 494's `AdvisoryProjector`. The workflow declares which advisory class it emits; the policy from `EmissionPolicy` applies as usual.
- **Human-gate nodes** suspend the workflow and emit an advisory of class `core.workflow.gate-pending`. The FE surfaces it in the advisory inbox + a workflow-detail surface. User action (`approve` / `reject` / `cancel`) resumes the workflow.
- **Branch / condition nodes** evaluate against `WorkflowContext` using a constrained expression language (probably the same `WhenExpression` 521 ShellContext uses, generalized).
- **Persistence** rides the per-shape file-backed pattern from 491 / 513. Each workflow run gets a directory under `tmp/workflows/<workflowId>/<runId>/` with `meta.json` + `events.jsonl` + `state.json`.

## What a workflow run looks like end-to-end

1. User clicks "Summarize-and-extract" in the command palette (or agent tool call invokes `vop_summarize_and_extract`).
2. Workflow runner constructs `WorkflowContext { inputs }`, allocates `runId`, persists initial state.
3. Node 1 = llm-call (Summarize shape). Runner invokes engine; streams chunks; captures the summary into context.
4. Node 2 = llm-call (Extract shape) with the summary as input. Runner invokes; captures structured JSON.
5. Node 3 = branch on `extracted.action_items.length > 0`. Runner evaluates.
6. Node 4 (on-true) = emit-advisory (`core.advisory.workflow-completed`, payload = `{summary, action_items}`).
7. Return.

Each node is independently observable (trace per node), independently retriable (the captured state is enough to rerun a failed node), independently gateable (any node can be wrapped in a confirmation gate).

## What this dissolves

- The "where do multi-step workflows live?" question 488 named.
- F2 cascade's "absent substrate" deferral.
- F23 advisor's "needs proactive-emission orchestration" deferral.
- G47 scheduled's "needs trigger model" deferral.
- G133 ChainShape — reduces to a workflow with only llm-call nodes.
- The agent runtime convergence question 499 raised: instead of collapsing all shapes into the agent, the agent becomes one *node type* in a workflow.

## What this is NOT

- Not a general-purpose DAG executor (ADR-0014 explicitly rejected that). The node types are constrained; the runtime is a state machine; the runner is not generic.
- Not a replacement for ConversationShape. Shapes remain; workflows compose them.
- Not a plugin-authoring story for V1. Plugins may declare workflows (Phase D), but V1 ships with core workflows only.
- Not a cron substrate. The trigger model is separate; it dispatches into workflows but is its own slice.

## Open questions

- **DSL**: declarative YAML/JSON authored by humans? Agent-authored JSON via a workflow-construction shape? Both? Recommended: V1 ships YAML-authored core workflows; agent-authored is V2 on top.
- **Trigger model location**: Head-process scheduler (in-process cron) vs OS-level (Tauri scheduled tasks)? Head-process is simpler and survives OS-level permission denial; OS-level survives Head-process crashes.
- **Operation history projection**: are workflow runs first-class in OperationHistory, or a separate WorkflowHistory? Probably separate, with cross-link.
- **Branching with 513**: do conversation branches and workflow branches share concept? Probably not — different lifetime semantics.
- **Versioning**: a workflow declaration changes; what happens to in-flight runs of the old version? Recommended: in-flight runs pin to their declaration version; new runs use the new declaration.
- **Failure semantics**: a node throws. Does the workflow fail-hard, fail-soft, or invoke a handler-node? Per-node `on-failure` declaration with workflow-level default.
- **Composition of multiple workflows**: can a workflow node be `run-workflow(id)`? Probably yes, with cycle detection.
- **Trace export**: do workflow traces flow into OTel? Compose with 529's observability work?

## The strategic claim

If this substrate ships and the existing primitives compose cleanly into it, the platform's positioning ("local-first agent platform") is real, validated, and Bucket C of 488's feasibility analysis ("achievable with mitigation") becomes Bucket B ("reliably achievable") in production. The substrate work of 487/490/491/494 then earns out empirically, not just structurally.

If the substrate ships and the existing primitives don't compose cleanly — if the trust lattice rejects too much, if the advisory policy isn't expressive enough, if the conversation engine's two execution modes leak across the workflow boundary — the substrate work needs adjustment. The workflow integration test is the only way to discover this short of a real plugin (533) hitting the same composition.

---

## Multi-corpus substrate (was 535)

*(consolidated from `535-multi-corpus-substrate.md`)*

### 535 — Multi-corpus substrate

## The pressure

The watched-roots model assumes one corpus per machine. Real users naturally have multiple separable contexts: work / personal / project-X / consulting client A / client B. Today the only way to switch is to reconfigure watched roots, which invalidates the index, loses pinned searches, loses conversation history, and is structurally wrong as a switching mechanism.

The framework's existing axes (`Audience`, `Provenance`, `riskTier`) already gate visibility. The same axes that hide OPERATOR Operations from agents in 504 today can gate per-corpus, per-plugin, per-account visibility tomorrow. The substrate is partially pre-wired; what's missing is the corpus boundary itself.

## What a corpus is

A corpus is a named, isolatable scope over:

- **Watched roots** — the filesystem paths indexed.
- **Index** — the Lucene index (one per corpus, or one global with corpus-tag field; design fork below).
- **Configuration** — corpus-specific settings (search defaults, AI shape preferences, theme override).
- **Conversation history** — chats, branches, advisory inbox, recent sessions.
- **Workflow runs** (when 534 ships) — workflow declarations may be global or per-corpus; runs are per-corpus.
- **Plugin set** — which plugins are enabled (corpus may want to disable a code-corpus plugin in a notes corpus).

A corpus has:

- An `id` (`work`, `personal`, `client-X`).
- A `presentation` (label, color, icon).
- A `root-set` (watched paths).
- An optional `passphrase` for at-rest encryption (out of V1 scope but the registration shape reserves it).
- A `policy` declaring cross-corpus visibility (default: isolated; opt-in for federated search).

## Active corpus and context

A `CurrentCorpus` Resource (Category.STATE, ONE_SHOT) tracks the active corpus per-session. Switching corpus:

- Re-binds the index (Worker re-attaches to the new corpus's Lucene directory).
- Re-binds the AdvisoryStore (FE re-subscribes to per-corpus advisory streams).
- Re-binds the ConversationListStore (FE loads conversations from the new corpus).
- Preserves the inference runtime (LLM doesn't reload; model is global).
- Preserves the Head process (no restart; the corpus boundary is intra-process).

Surfaces:

- A `<jf-corpus-switcher>` in shell chrome (probably rail bottom or status-bar area).
- Settings surface gains a corpus-management section (create, rename, delete, set passphrase).
- Search surface optionally has a "search across all corpora" mode that fans out to each corpus's index and merges.

## Design fork: per-index vs corpus-tagged

**Option A — One index per corpus.**

- Clean isolation; deleting a corpus deletes its index outright.
- Per-corpus index size; doesn't bloat a corpus by another corpus's volume.
- Federated search requires fan-out and merge.
- Worker complexity: needs to manage N Lucene index handles. The existing `IndexRootLock` invariant needs widening (one lock per corpus, not one per machine).

**Option B — One index with corpus-tag field.**

- Single index handle; existing Worker code mostly unchanged.
- Per-corpus search becomes a filter (`+meta_corpus:work`).
- Federated search is the default; cross-corpus is a filter relaxation.
- Index size bloats with all corpora; per-corpus delete becomes a Lucene tombstone walk.
- Encryption-at-rest per corpus is harder (one Lucene file vs N).

Recommendation: **Option A.** The isolation is structurally cleaner, the security story is simpler, and the federated-search cost is a one-time engineering effort vs ongoing exposure.

## Composition with existing substrate

- **AppInstanceLock** stays per-dataDir; corpora live under dataDir.
- **IndexRootLock** widens: one lock per `(dataDir, corpusId)`.
- **Operations** declare a `corpusScope`:
  - `none` — operation is global (e.g., `core.switch-theme`, `core.show-help`).
  - `current` — operation runs in active corpus (e.g., `core.reindex`, `core.search`).
  - `all` — operation is cross-corpus (e.g., `core.delete-corpus`, `core.export-all`).
- **Resources** declare their corpus scope. Per-corpus resources are namespaced by `(corpusId, resourceId)`.
- **Conversations** carry a `corpusId` field. Branching across corpora is forbidden by construction.
- **Plugins** declare their corpus binding in their manifest:
  - `binding: global` — plugin loads once, sees no corpus boundary (e.g., theme).
  - `binding: per-corpus` — plugin loads per corpus, sees only that corpus's data.
- **Workflows** (534) declare a corpus scope; runs are per-corpus.
- **Advisory** (494): per-corpus advisory streams. The inbox switches with the active corpus.
- **MCP** (500): MCP clients see only the active corpus. Switching corpus is an MCP command (`mcp_set_active_corpus`).

## What this enables

- Work/personal separation; corpus boundary prevents accidental leakage.
- Client confidentiality (consulting users can't accidentally surface corpus B content in a corpus A chat).
- Per-project plugin sets (a code-corpus has `highlight-todos` enabled; a notes-corpus doesn't).
- Per-project search defaults (date-range default differs).
- Per-project AI behavior (different RAG prompts, different conversation shape preferences).

## What this is NOT

- Not multi-user. One user per machine; corpora are local boundaries within one user.
- Not multi-machine sync.
- Not cloud federation.
- Not per-corpus model (LLM stays global).
- Not a permission system for sharing corpora across users.

## Open questions

- **Default behavior on first install**: one corpus auto-created, named "default"? Or a corpus-creation wizard? Probably auto-create + offer rename.
- **Migration**: existing single-corpus users — how do they migrate? Either auto-migrate to a "default" corpus, or stay single-corpus and opt in to multi-corpus.
- **Plugin trust per corpus**: can a plugin be trusted in corpus A and untrusted in corpus B? Probably yes (the trust tier is per-binding, not per-plugin globally).
- **Cross-corpus chat**: explicitly forbidden, or allowed with strong gate? Recommended: forbidden in V1; revisit if there's demand.
- **Workflow runs across corpora**: a workflow node that operates on corpus A while the workflow is running in corpus B — forbidden? Allowed with `corpusScope: all` declaration? Recommended: workflows are scoped to their declared corpus; cross-corpus workflows are V2.
- **Settings inheritance**: which settings are per-corpus vs global? Theme is global; watched roots are per-corpus; AI shape preference probably per-corpus; keybindings global. A `scope` field on each setting declaration resolves it.
- **Encryption-at-rest**: V1 ships without; per-corpus passphrase + AES-encrypted index is a follow-up. Reserving the registration shape is the V1 work.

---

## Multi-model routing + tool-use fine-tune lever (was 536)

*(consolidated from `536-multi-model-routing.md`)*

### 536 — Multi-model routing + tool-use fine-tune lever

## The capability ceiling

488 §3 measured Qwen3.5-9B Q4_K_M's capability ceiling:

- Function calling (BFCL v4): **66.1%** native.
- Specialized tool-use fine-tune (Llama-3-Groq-Tool-Use-8B): **89%** on the same benchmark.

A 23-percentage-point gap on the same problem class. The local stack uses Qwen3.5 for every path — chat, RAG, extract, agent tool-calling. The 66% ceiling caps every agent-tool-call-heavy feature: F8 MCP, F23 advisor, F2 cascade, anything in 488's Bucket C.

488 §6 named two architectural levers JustSearch isn't yet exploiting:

1. **Qwen3.5's hybrid reasoning toggle** — per-request thinking-mode flip. Same model; lower-error multi-step output at higher token cost.
2. **Specialized tool-call fine-tune swap** — a second model for tool-call paths. Two-model topology costs VRAM but is strictly additive to capability.

Neither is in production. The 9B single-model posture is a deliberate simplification per 488, but it caps the LLM-mediated UX ceiling at 66% function-calling accuracy.

## The idea

Model routing as a substrate.

### Model registry

Each available model is a `RegisteredModel`:

```
RegisteredModel {
  id: "qwen-3.5-9b-q4" | "groq-tool-use-8b" | "qwen-3.5-9b-thinking"
  family: "qwen3.5" | "llama3-groq"
  capabilities: { functionCalling: 0.66, reasoning: 0.8, vision: false, ... }
  costClass: "local-low" | "local-medium" | "local-high" | "cloud-medium" | "cloud-high"
  latencyClass: "fast" | "medium" | "slow"
  vramCost: number  // GB
  loadCost: number  // ms
  source: "local-gguf" | "cloud-anthropic" | "cloud-openai" | "cloud-openrouter"
}
```

The registry is a `Resource` (Category.STATE, MANY); each model is one entry. Users / operators may register / unregister / activate models.

### Router

The router selects a model per LLM invocation. Inputs:

- **Shape affinity** — ConversationShape declares preferred capability profile. RAG shape → general; Extract shape → reasoning; Agent shape → tool-use.
- **Per-request hints** — `requiresReasoning: true`, `prefersToolUse: true`, etc.
- **User override** — per-corpus or per-session preference.
- **Hardware constraints** — VRAM available; some models can't run concurrently.

Output: a `ModelBinding` for the invocation. The runtime loads the model (if not already loaded), routes the request, returns the response.

### Two-model topology (V1 local)

V1 ships with the option to run a second local model: a tool-use specialist (Groq fine-tune or equivalent). The router sends agent-tool-call paths to the specialist; general chat / RAG to Qwen3.5. Both local; both governed by the same loopback + privacy posture.

VRAM trade-off: two 9B models cost ~11 GB combined (vs 5.5 for one). Default off. Per-user opt-in. The substrate ships ready; the cost is a user decision.

### Thinking-mode toggle (free lever)

Independent of multi-model. Each ConversationShape declares per-node `thinkingMode: 'auto' | 'forced-on' | 'forced-off'`. The runtime passes the flag to llama-server (`--reasoning-budget`). Multi-step shapes (Extract, agent runs with > 2 iterations) default to `forced-on`; routing / classification defaults to `forced-off`.

This is the cheapest lift — no second model needed; just expose the toggle that already exists in the runtime.

### Cloud routing (V2, gated)

V2 adds cloud providers as registered models. Privacy posture forces:

- Default off.
- Per-corpus opt-in.
- Explicit gate on every cloud invocation showing user the model name + provider.
- Never use cloud for plugin-emitted requests (trust-tier × model gate).
- Audit every cloud call.

V2 is its own slice; this tempdoc establishes the substrate that makes V2 a configuration change rather than a refactor.

## Composition with existing substrate

- **518 (InferenceLifecycleManager)** generalizes from "manage one llama-server" to "manage a pool of (model, runtime) bindings." Most of TransitionRunner, InferenceRuntimeView, typed failures stays unchanged; the singleton assumption goes. The phase transitions become per-model.
- **491 (ConversationShape)** gains `modelAffinity: ModelCapabilityProfile`. The engine consults the router when invoking.
- **534 (workflows)** — nodes declare model affinity. A workflow's tool-call node naturally routes to the tool-use specialist.
- **487 (trust lattice)** intersects with model selection: untrusted-source × cloud-model = DENY by default.
- **490 (advisory)**: model-switch events emit advisories (per-class `core.advisory.model-loaded` / `core.advisory.model-unloaded`).
- **529 (observability)**: span attributes gain `inference.model.id`, `inference.model.family`, `inference.routing.reason`. Trace correlation across model boundaries.

## What this enables

- The tool-use fine-tune lever: agent paths jump from 66% to ~89% on function-calling-heavy queries. Closes much of 488 Bucket C.
- The thinking-mode lever (cheaper): multi-step features get lower-error output at higher latency. User-controllable.
- Cloud routing (V2 only): high-stakes queries can opt into stronger models without sacrificing local-first default.
- Future model upgrades become a registry update, not a Worker refactor.

## What this is NOT

- Not a model auto-download (the registry references models; install is a separate concern, already partly addressed by BrainInstallService).
- Not a model-quality eval substrate (that's 537 territory).
- Not a model-marketplace.
- Not a removal of the single-model simplification — the registry can hold one entry and behave exactly like today.

## Open questions

- **Concurrent model loading** — VRAM allows one at a time on most desktop GPUs. Default policy: load-on-demand, evict-LRU. User can pin two for low-latency switching at VRAM cost.
- **Cold-start cost** — loading a 9B GGUF takes seconds. The router needs a "warmup" mode that loads expected models proactively. Composes with 540 (cold-start profile).
- **Streaming across model swaps** — if a streaming request mid-flight loads a new model for the next request, the swap has to happen between requests, not during. Per-request locking.
- **API-key management for cloud (V2)** — settings? Per-corpus? Encrypted at rest? V2 design problem.
- **Eval against the routed stack** — 537's eval campaign needs to bake in routing; the metric is end-to-end accuracy with router decisions, not per-model accuracy.
- **Fallback chain** — primary model unavailable. Fall back to secondary? Surface error? Per-corpus policy.

---

## Post-substrate eval campaign (was 537)

*(consolidated from `537-post-substrate-eval-campaign.md`)*

### 537 — Post-substrate eval campaign

## The pressure

Every substrate-shipping tempdoc from the 487–529 era closed with some variant of "the substrate is correct; quality movement is implied." Almost none closed with "and we measured it." The default is that shipped substrate is assumed to help; the measurement is hoped-for but rarely run.

500 §gates is explicit:

> "Eval validation: ⚠️ NOT VALIDATED — the 94% accuracy figure is from the old TS server's 50-query eval (tempdoc 366), not from this Java implementation. An eval run against the new MCP surface is the next validation step."

The same gap exists for:

- **493 claim annotation** — the streaming-citation substrate's UX value. Does inline grounding indicators correlate with user-perceived faithfulness? Untested.
- **493 retrieval preamble** — does "Found 3 passages with 78% coverage" change user behavior or trust? Untested.
- **490/494 advisory substrate** — does the unified inbox actually surface advisories users act on? Untested.
- **510 AI-aware shell** — does framework-level capability gating reduce the "AI is broken" support burden? No metric.
- **517 search decomposition** — search quality should be unchanged after the refactor. Is it?
- **518 inference lifecycle** — startup-failure recovery should be smoother. Measured?

The substrate is shipped; the quality movement is hoped-for, not measured.

## The idea

A post-substrate eval campaign. Re-baseline against the new stack. Establish the discipline of "shipped substrate → measured quality → published delta."

Four tracks:

### Track 1 — MCP curated-tool eval against Java surface

Re-run the tempdoc 366 50-query eval (`scripts/agent-eval-50/...`) against the new Java MCP surface (500). Compare to the 94% TS baseline. Stratify by:

- Tool selected (`justsearch_answer` / `_search` / `_browse` / `_ingest` / `_status`).
- Query class (answer-first / exploration / orientation / ingestion).
- Description-following accuracy.

Targets: ≥94% overall; per-tool accuracy comparable to TS baseline. Variance bands established.

### Track 2 — RAG quality re-baseline

Standard JustSearch eval corpus + ground-truth answers. Measure on the new stack (claim annotation + retrieval preamble + streaming citations + decision-bucketed retrieval per 517):

- **Faithfulness**: % of claims supported by retrieved context (LLM-judged + spot-checked).
- **Citation accuracy**: % of citations that point at genuinely-supporting passages.
- **Claim-grounding rate**: % of answer sentences with at least one citation match.
- **Answer completeness**: covers all retrieval-supported aspects of the question.
- **Retrieval coverage**: per `QualitySignals` (already computed; surfaced via 493 preamble).

Compare against pre-claim-annotation baseline (jseval history; the 280s tempdocs ran eval against earlier stacks).

### Track 3 — Decision-bucket stratification (rides on 525)

525 §D shipped `decision_kind` as a stratification dimension. Run the existing eval corpus through, stratify by `decision_kind`:

- `SparseShortcut` queries vs `MultiLegDecision` queries — quality delta.
- `BlockedDecision` queries (vector-blocked, fallback to keyword) — how much quality is lost?
- `EmptyQueryDecision` — exclude from quality metrics (degenerate).

Surfaces: "vector-blocked queries score X% lower than three-way fusion" — informs which decision arms need attention.

### Track 4 — Agent-workflow eval (when 534 lands)

If/when LANGUAGE_WORKFLOW ships, eval on multi-step workflows:

- Workflow completion rate.
- Per-node failure rate.
- Tool-call accuracy in workflow context (vs ad-hoc agent runs).
- Human-gate intervention rate (V2 calibration).

This track waits for 534. The other three don't.

## Output

A **substrate-vs-quality ledger**. One row per shipped substrate slice in the 487–529 era, with:

- Slice ID.
- What it changed.
- Pre-substrate quality metric (where reproducible).
- Post-substrate quality metric.
- Measured delta + significance.
- Caveats / unmeasurable dimensions.

The ledger becomes the discipline: every future substrate slice closes against a measurement, not an assertion. Eventually this generalizes to a substrate-shipping policy ("ship substrate → run the relevant eval slice → publish the delta in the closure note").

## What this dissolves

- The "we did a lot but did it help?" question 512 §F3 framed.
- The structural asymmetry between substrate-shipping discipline (Pass-8, C-018, ratchets) and substrate-quality discipline (~nothing).
- The risk that substrate accretes for years before someone notices it didn't move the user-facing metric.

## What this is NOT

- Not live A/B testing. No real-user telemetry; local-first stays local-first.
- Not a production telemetry pipeline. Eval is offline, against synthetic corpora.
- Not a substitute for live-stack verification (that's Tier-3, per agent-lessons). Different question.
- Not exhaustive. Substrate slices whose effect is purely structural (e.g., 511's renderer registry refactor) may not have a quality metric; the ledger row says so explicitly.

## Open questions

- **LLM-judged metrics** (faithfulness, completeness) are themselves a quality risk. Need a calibration pass against human-judged for a subset.
- **Eval corpus drift** — the corpus must stay stable across runs for deltas to be comparable. Snapshot it; version it.
- **Cost** — running the eval suite against every model + every shape + every decision-bucket is combinatorial. Default to "core paths" + bias toward shipped-substrate-impacted dimensions.
- **What's the trigger for re-running?** Per-merge against `main`? Per-substrate-shipping-slice? Periodic? Recommendation: per-substrate-shipping-slice on opt-in; nightly on a smaller smoke suite.
- **Where do baselines live?** `evidence/eval-baselines/<date>/<run-id>.ndjson`? Need a clean storage convention. Probably extends jseval's existing model.
- **Integration with workflow observability (ADR-0010)** — eval runs are workflows; the existing telemetry should capture them.

---

## Trust-lattice production audit (was 538)

*(consolidated from `538-trust-lattice-production-audit.md`)*

### 538 — Trust lattice production audit

## The pressure

487 shipped the `(SourceTier × RiskTier) → GateBehavior` lattice. The matrix at §4.4 was authored at design time and ratified by Pass-8. It looks like this:

| SourceTier ↓ RiskTier → | LOW | MEDIUM | HIGH |
|---|---|---|---|
| TRUSTED | AUTO | AUTO | TYPED_CONFIRM |
| MEDIUM | AUTO | INLINE_CONFIRM | TYPED_CONFIRM |
| UNTRUSTED | AUTO | TYPED_CONFIRM | TYPED_CONFIRM (escalated copy) |

The lattice is *correct* by structural argument (convergent with MCP elicitation + OWASP intent-capsule + Microsoft Agent Governance). It's been live since the 487 merge. But which cells fire in real usage?

Possible answers nobody has measured:

- **Some cells never trip.** A combination that never occurs in real traffic (e.g., `UNTRUSTED × LOW`) doesn't validate the design; it just doesn't matter.
- **Some cells trip every time.** If 99% of `UNTRUSTED × HIGH` invocations come from one source and get one user response, the gate is functioning as designed — but the cost-benefit of `TYPED_CONFIRM (escalated copy)` vs simpler ceremony may not be paid back.
- **Some paths bypass the lattice.** If `OperationExecutorImpl.enforceTrustLattice` doesn't run for some dispatch shape, the lattice isn't doing the work the design claims. 487 §6.1 named the insertion point; live confirmation that all dispatch shapes go through it is a separate question.
- **Some operations have miscalibrated risk.** An operation declared `RiskTier.HIGH` whose callers are all `TRUSTED` produces only `AUTO` in practice. The HIGH classification doesn't matter and may signal a miscategorization.

The design assumed these are knowable by inspection; in practice they need measurement.

## The idea

Instrumented audit of the lattice over a representative usage window.

### Instrumentation

Each lattice invocation logs:

- `sourceTier` (from `Intent.sourceId` lookup).
- `riskTier` (from `Operation.policy.risk`).
- `gateBehavior` (the resolved cell).
- `outcome` (`fired` / `approved` / `denied` / `bypassed`).
- `operationId`, `sourceId` (for breakdown).

A small `TrustLatticeAuditChannel` emits these as structured events. The events flow into the existing observability pipeline (529's OTel spans + the `inference-transitions.ndjson` sidecar pattern); the audit reads them post-hoc.

### Audit window

A "representative" window is hard to define for a local-first tool. Two approaches:

- **Synthetic**: run a scripted user journey covering the common 50-query agent workflow + 20 ad-hoc UI actions + N URL deep-link / palette / rail invocations. Probably what 537's eval campaign exercises anyway. Quick; bounded.
- **Real**: instrument over a week of one developer's usage. Higher signal; user-specific bias.

Recommendation: synthetic to calibrate, real to validate.

### Analysis

Counter per `(sourceTier, riskTier, gateBehavior)`. Counter per `sourceId × actual-tier-observed` (to catch sources that declared wrong tiers).

Outputs:

- **Cells with zero hits in the window** — candidates for retraction or merge. If `MEDIUM × LOW` never appears in real usage, why is it a separate cell from `TRUSTED × LOW`?
- **Operations whose lattice resolution is constant** — `core.export-diagnostics` declared `RiskTier.HIGH` but every caller is `TRUSTED`, so it always gets `TYPED_CONFIRM`. Is the classification right? Or is `core.export-diagnostics` actually LOW risk because only TRUSTED sources ever call it?
- **Sources whose actual tier diverges from declaration** — `core.url.deeplink` declared `MEDIUM` but every observed call came from a known-trusted source (the user clicking a Tauri shell link). Maybe `core.url.deeplink` should split into `core.url.deeplink-from-trusted-app` and `core.url.deeplink-from-untrusted-app`.
- **Operations bypassing the lattice entirely** — paths that dispatch via `OperationDispatcher` without going through `enforceTrustLattice` (e.g., direct calls from collaborators, test paths, future plugin paths). Each one is a structural defect.

## Output

A calibrated matrix replacing the sketch in 487 §4.4. Maybe identical; maybe different. Plus a forward discipline:

- Any new `IntentSource` declares its expected tier with a probe.
- The audit re-runs periodically (composes with 531 — every audit is a one-shot until it's a CI artifact).
- Mis-tiered sources are corrected with a paired re-classification commit; the lattice itself moves only with Pass-8 review.

## What this dissolves

- The "the lattice is right but is it real?" question 487 left implicit.
- The risk of `UNTRUSTED × HIGH` ceremony being either over-onerous (every LLM emission burns a `TYPED_CONFIRM`) or under-onerous (some path slips past it).
- The forward question that arrives with 534 (workflows) and 535 (multi-corpus): how do workflow-emitted intents and cross-corpus intents tier? Easier to answer with the audit's data in hand.

## What this is NOT

- Not a redesign of the lattice. The structure stays; the calibration moves the matrix entries.
- Not a test framework. Tests validate that the lattice fires; the audit measures *what it fires*.
- Not user telemetry. Either synthetic or developer-volunteered; no implicit collection.
- Not gated on 537. Useful independently; pairs naturally.

## Open questions

- **Privacy of audit logs** — even on a developer machine, the operation log includes operation IDs and (potentially) argument shapes. Default to redacted args; keep operation + source + tier visible.
- **What window length is "representative"?** Probably ~1000 dispatch events for synthetic; ~5 days for real.
- **How often to re-audit?** Periodic enough to catch drift; rare enough to be cheap. Probably one re-audit per quarter, or when major substrate ships (487 + 534 + 535 each warrant one).
- **Composition with 487 sub-axes that may exist later** — if multi-corpus (535) adds a corpus tier, or workflows add a workflow-tier, the matrix grows. The audit framework should generalize trivially (more dimensions on the counter).
- **Should the audit feed 531's drift detection?** Yes — the substrate-slot for the lattice has its consumer (every dispatch), but the *per-cell* consumer count is exactly the question 531 asks at a finer grain. The two compose.

---

## Cold-start + memory profile (was 539)

*(consolidated from `539-cold-start-and-memory-profile.md`)*

### 539 — Cold-start + memory profile post-substrate

## The pressure

Nothing in the 487–529 era measured the cumulative cost of all the substrate. Many catalogs accreted; many registries accreted; many SSE streams + FE subscriptions accreted. The head process now does more at boot. The frontend now subscribes to more at first render.

488's local-first positioning quietly assumes "modest desktop resources" — the BFCL benchmarks were on stack with a 5.5 GB model and reasonable JVM overhead. Whether the substrate-driven head process + Worker + FE composition still fits in that envelope is unmeasured.

The risk isn't theoretical. Three concrete shapes that probably cost more than expected:

- **ResourceCatalog walks at boot** are O(catalogs × resources). The catalog count grew with 490 (advisory streams), 494 (additional advisory classes), 511 (aggregate-surfacing slots), 521 (status-bar / inspector-tab / empty-state registries). Each is small individually; cumulative unmeasured.
- **AdvisoryStore subscribes to N streams at FE boot**. The substrate is class-polymorphic per 494, so adding a new advisory class adds another SSE handshake. SSE setup is not free.
- **AppFacadeBootstrap** at 2,875 LOC executes a lot of wiring that's never used in a given session. The phase-extraction (519) is moving things, but the *executed* work hasn't been profiled.
- **AiStateStore** (508) aggregates inferencePoll + statusPoll + install/runtime state. First-resolve latency cascades through every AI-dependent surface's mount.

The question this slice answers: **after all this substrate, how long does cold-start take, and how much memory does the head process retain, at what corpus sizes?**

## The idea

A profiling slice. Not optimization — measurement first.

### Targets

- **Head cold-start**
  - Time from `java -jar` to API-ready (`/api/health` returns 200).
  - Time to Worker-connected (Worker capability READY).
  - Time to inference runtime available (Inference capability READY).
  - Time to first-request-served (curl `/api/knowledge/status` returns).
  - Time to FE first-paint (Vite dev mode + Tauri shell, both axes).
  - Time to FE interactive (AiStateStore first-resolved; capability pills rendered; rail clickable).
- **Head memory**
  - JVM heap at idle (post-boot, no traffic).
  - Retained-object counts per catalog / registry.
  - Heap growth after one search.
  - Heap growth after one agent run.
  - GC behavior across an hour of light traffic.
- **Worker cold-start** (separate axis, since Worker is a sibling process)
  - Time to gRPC port advertised.
  - Time to first search served.
  - Index load time per 10K docs.
- **FE memory**
  - Initial bundle size (gzipped + raw).
  - Initial DOM node count.
  - Memory after 10 minutes of light usage (catches subscription leaks).

### Corpus sizes

Profile each metric at:

- Empty index (0 docs).
- Small (1K docs, mixed content).
- Medium (100K docs).
- Large (1M docs — the high-end of single-machine corpora 488 contemplated).

### Tooling

- Java side: `-XX:+PrintGCDetails`, `jcmd <pid> GC.heap_info`, async-profiler for CPU + alloc traces.
- FE side: Chrome DevTools Performance + Memory tabs; React-style "what re-rendered why" if Lit has an equivalent.
- Worker side: same JVM tooling.
- Cross-process: 529's OTel spans give boot-phase visibility; tag with boot-phase ID.

### Output

A profile baseline + a list of unexpected costs. Likely findings (informed guesses, to be validated):

- ResourceCatalog at boot scans all watched roots; on a 1M-doc corpus this dominates cold-start.
- AdvisoryStore's per-stream SSE handshake at FE boot adds linear cost in number of advisory classes; with ~5 classes today, fine. With 20+ (post-534 workflows + 535 multi-corpus), worth attention.
- AppFacadeBootstrap's executed wiring includes paths that aren't used in a given session (e.g., agent-tool wiring runs at boot regardless of whether the user invokes the agent that session).
- The InferenceLifecycleManager's TransitionRunner overhead is per-transition; cold-start has one transition (OFFLINE → ONLINE); cheap.

## What this enables

- Informed answer to "can JustSearch ship to a 4GB-RAM machine?", a 16GB machine, or a 32GB workstation. 488 assumed a constraint; this measures it.
- A target for the next round of work: if cold-start is 12 seconds on a small corpus, the substrate work has earned out *and* there's room to spend; if it's 40 seconds, that's the next round.
- Calibration for 540 (multi-model routing) — VRAM budget already constrains, but if heap is also tight, the two-model topology forecast changes.
- Pre-condition for 534 (workflows) — workflows add wiring; knowing the baseline lets us catch regressions.

## What this is NOT

- Not optimization. The slice produces measurements. Subsequent slices fix what the measurements expose. Premature optimization is the trap; premature measurement is not.
- Not a continuous monitoring pipeline. One-shot baseline. If something looks bad, that becomes its own slice.
- Not a benchmark vs other products. Internal calibration.
- Not user telemetry.

## Open questions

- **Reproducibility** — cold-start time varies with disk cache state, OS scheduling, JIT warm-up. Median of N runs after `clear-disk-cache` is the right framing.
- **What's an acceptable cold-start budget?** No documented target exists. Probably: ≤5s API-ready on small corpus; ≤15s API-ready on 100K-doc corpus; FE first-paint ≤3s in production build; first-interactive ≤5s.
- **How to baseline against a counterfactual?** Hard. The substrate is shipped; there's no "without-substrate" head process to compare. Best proxy: pre-487 commits (~6 months ago), built and profiled. Worth doing once.
- **Where do measurements live?** `evidence/cold-start-profile/<date>/<host>/<corpus>.ndjson`. Same shape as eval baselines (537).
- **Worth automating?** Probably yes for the simple metrics (API-ready time) — composes with 537's eval campaign. Heap analysis stays manual; too noisy for CI.

---

## observations.md inbox processing (was 540)

*(consolidated from `540-observations-inbox-processing.md`)*

### 540 — observations.md inbox processing

## The pressure

CLAUDE.md formalized the inbox convention:

> "When you notice an issue outside your current task's scope — pre-existing bug, dead code, stale comment, broken-but-unrelated test, config drift — append one line to the `## Inbox` section of `docs/observations.md` and keep working."

The inbox is the relief valve for noticed-but-out-of-scope findings. It is the mechanism that resolves the tension between *Stay Focused* (scope discipline) and *don't lose knowledge* (recording cross-cutting findings). Without it, every observation either becomes scope creep or evaporates.

But: the inbox accretes monotonically. Items go in; nothing automatically takes them out. After a few months of high-velocity development, the inbox is a wall of one-liners — many resolved by side effects of unrelated work, many promotable into their own slices, some retracted by re-reading the code, some genuinely waiting.

Without periodic disposition, the inbox becomes another knowledge graveyard. Each new agent looking at it sees a pile and bounces; the next observation joins the pile; nothing earns its way out. The convention is correct; the disposition loop is missing.

## The idea

A periodic inbox-processing pass. Each item gets one of four dispositions:

### Resolved-in-flight

A recent slice already fixed the underlying issue. The observation predicts something that subsequent work happened to address.

Disposition: archive with the closing commit ref. Move the line to a `## Archive` section (or delete it; the git history retains the original).

### Promoted

The observation deserves a slice of its own. Either it's a real cross-cutting bug, a structural pressure, or a feature.

Disposition: open a new tempdoc; cite the inbox row as the originating observation; delete the row.

### Folded

The observation belongs in an existing tempdoc's residue tracker (e.g., 504 §Residue tracker, 511-future-directions, 491 deferred items).

Disposition: move the line to the appropriate tempdoc's residue section; cross-link.

### Retracted

A re-read of the codebase shows the observation was incorrect at the time, OR the underlying issue is already gone by other means.

Disposition: delete the row with a one-line note in the disposition log explaining why.

## Cadence

Trigger options:

- **Periodic**: every ~50 commits, or weekly, whichever comes first.
- **Threshold-based**: when the inbox crosses N items (probably 30).
- **Substrate-event-triggered**: after a substrate-shipping slice merges, run a pass against items potentially affected.

Recommendation: **threshold-based with a periodic backstop**. Threshold prevents the inbox from sprawling; periodic backstop catches the long-tail items that never trigger the threshold.

## Output

Each pass produces:

- A smaller inbox (items resolved/promoted/folded/retracted).
- A short disposition log appended to `docs/observations.md` (or a sibling file `docs/observations-log.md`):
  ```
  ## 2026-05-20 disposition pass
  - F-2 (stale enum) → resolved-in-flight (commit 8dbd2ce38).
  - F-7 (no-CTA on chat error) → promoted (new tempdoc 540).
  - F-15 (chat surface activity empty) → folded into 504 §Residue.
  - F-19 (duplicate citation fire) → retracted (re-read shows already handled by 493 FIX).
  ```
- Optionally: an updated `## Inbox` section pruned to only the genuinely waiting items.

## Who runs it

Either:

- A human curator on a periodic cadence.
- An agent with the explicit task "disposition the inbox" — bounded scope, ~30 items per pass, structured output.

Agent-driven is cheaper and probably good enough for first pass; human-curated is the right model for high-stakes items (security-adjacent, architectural). A hybrid is plausible — agent drafts dispositions; human ratifies.

## What this dissolves

- The inbox becoming a knowledge graveyard.
- The asymmetry between "easy to add to" and "easy to take stock of" — the former is one line of bash; the latter has no convention.
- The repeating "audit-of-the-audits" cost — without disposition, every new agent reads the full inbox to know what's live.

## What this is NOT

- Not an automation. The disposition decisions require judgment.
- Not a substitute for fixing things. Disposition is "what should we do with this observation?", not "let's fix all of these now."
- Not a one-shot. The whole point is the loop; one disposition pass leaves the next pass with the residue.
- Not a new tempdoc style. The four dispositions match existing conventions (resolved-in-flight ≈ closure note; promoted ≈ new slice; folded ≈ residue tracker; retracted ≈ supersession).

## Composition

- **531 (substrate-consumer drift)** is a sibling hygiene loop. Both are periodic checks on accumulated state.
- **504 §Residue tracker** is the model for "folded" disposition — observations belong in their topical tempdoc's residue, not in a global pile.
- **530 (class-size ratchet)** is the structural-prevention counterpart — automated where this is human-judgment.

## Open questions

- **Format of the disposition log** — append to `observations.md` (clutter) vs sibling file (more bookkeeping). Probably sibling, with a pointer from `observations.md`.
- **Archive granularity** — keep the last 90 days inline; older into a yearly archive? Probably overengineered; trust git history.
- **Agent-driven vs human-driven** — V1: agent-drafted, human-ratified. V2: agent runs autonomously on the threshold trigger, with the disposition log as the audit trail.
- **Categories** — should observations be tagged by type at write-time (bug / dead-code / config-drift / discipline-gap)? Cheap discipline; probably yes for new entries; don't backfill.
- **Inbox max size as ratchet** — like 530's class-size ratchet, the inbox could have a soft ceiling (50 items) that triggers a disposition pass when exceeded.
