---
title: "361: Agentic Workflow Improvements"
type: tempdoc
status: done
created: 2026-03-26
---

# 361: Agentic Workflow Improvements

## Goal

Reduce avoidable waste in agent verification workflows and prevent
systematic skill-loading failures. Derived from tempdoc 360 retrospective
where ~85 minutes were lost to unnecessary restart cycles, late
diagnostics, and missing domain context from unloaded skills.

## Problem Analysis

### 1. Agents don't load skills when they should

The CLAUDE.md instructions are clear: "Before starting work in these
domains, load the corresponding skill." But agents scope skill loading
by task description ("migrate a class"), not by code impact (modifying
GPU detection, ORT sessions, VRAM limits). The mismatch is systematic
— it occurs whenever the task framing doesn't match the code domains
being touched.

**Observed failure in tempdoc 360:** The task was "migrate reranker
from Head to Worker" — framed as an IPC/architecture task. The agent
never loaded `/inference-runtime` or `/search-quality` despite
modifying GPU arbitration, ORT session lifecycle, VRAM limits,
reranker config defaults, and search pipeline behavior. This caused:
- VRAM budget (512MB default) discovered by OOM instead of by reading
  the register's documented baselines
- Stale `maxSeqLen` default (8192 in ResolvedConfigBuilder) not caught
  until VRAM profiling
- gRPC deadline set to 5s (standard) instead of recognizing CE
  inference is a heavyweight operation with documented latency ranges
- NaN batch padding bug not anticipated despite the register's
  documentation of D4 (batch-size bucketing) and its interaction with
  ModernBERT's global attention

**Root cause:** The agent's decision to load a skill is a conscious
choice made once at task start, based on the task description. But
code impact accumulates throughout the session as the agent discovers
what actually needs changing. By the time the agent is editing ORT
session code, the "should I load inference-runtime?" decision was made
hours ago based on different information.

### 2. Unnecessary restart cycles during verification

Agent treated "change → restart → re-ingest → test" as the atomic
verification unit. The actual dependency graph:

- **Code change** → needs restart (Worker runs from installDist JARs)
- **Config change** → needs restart (config read once at init, immutable)
- **Search test** → needs running backend + indexed data (no restart)

Most iterations were config changes (VRAM, seqLen, GPU enable) or
search tests. Re-ingestion was never necessary after the first clean
ingest. Even restarts were only necessary because `RerankerConfig` is
immutable after construction.

### 3. Late diagnostic habits

When `MODEL_NOT_LOADED` appeared, the agent debugged gRPC dispatch
(inspecting JARs with javap, adding debug logs) for 30 minutes before
checking `grep ERROR worker.log` — which showed the root cause (schema
mismatch) in 5 seconds. This is a systematic issue: agents prefer
reasoning from code structure over checking empirical evidence.

## Proposed Improvements

### I1. Skill-trigger hook (PreToolUse on Edit)

A hook that fires on every Edit, checks the file path against a
static mapping, and emits a **warning** (not a block) if the file
falls in a skill-relevant module and that skill hasn't been loaded.

**Mapping file** (`.claude/skill-triggers.json`):
```json
{
  "inference-runtime": [
    "modules/reranker/",
    "modules/ort-common/",
    "modules/worker-core/**/embed/",
    "modules/worker-core/**/splade/",
    "modules/worker-core/**/ner/",
    "modules/worker-core/**/bgem3/",
    "modules/ai-bridge/"
  ],
  "search-quality": [
    "modules/adapters-lucene/**/search/",
    "modules/worker-services/**/SearchOrchestrator",
    "modules/app-services/**/KnowledgeHttpApiAdapter",
    "modules/reranker/"
  ]
}
```

**Hook behavior:**
1. Read `tool_input.file_path` from stdin
2. Match against `skill-triggers.json` patterns
3. Check session state file (`tmp/agent-telemetry/skill-warnings-{sessionId}.json`)
   for whether this skill was already warned about
4. If not warned: emit stderr message — "This file is in the
   inference-runtime domain. Consider loading `/inference-runtime`
   if you haven't already."
5. Record the warning in session state (warn once per skill per session)
6. Exit 0 (warning only, never blocks)

**Implementation:** ~40 lines of JS in `scripts/agent-analytics/hooks/skill-trigger.mjs`.
Registered as a PreToolUse hook on Edit matcher in `settings.local.json`.

**Why warning, not block:** Blocking would prevent legitimate quick
fixes to inference code without the overhead of loading a full register.
The value is the reminder, not the gate. The agent can ignore it if it
already has the context.

### I2. Tempdoc skill declarations

Each tempdoc declares required skills in its frontmatter:

```yaml
---
title: "360: Migrate Reranker to Worker Process"
skills: [inference-runtime, search-quality]
---
```

The `/start` skill or `SessionStart` hook reads the active tempdoc's
frontmatter and emits a reminder to load the declared skills. This
handles the planned-work case where the tempdoc author (who understands
the task scope) can anticipate code impact.

**Limitation:** Only works when a tempdoc exists. Requires the human
to correctly anticipate code impact at authoring time. Doesn't catch
ad-hoc work without tempdocs.

**Complementary with I1:** Tempdoc declarations handle planned work;
the file-path hook catches what the tempdoc missed. Together they cover
both planned and emergent code impact.

### I3. "Error log first" diagnostic rule

Add to `.claude/rules/agent-lessons.md`:

```
## Diagnostic discipline

When a backend returns an unexpected response (MODEL_NOT_LOADED,
DEADLINE_EXCEEDED, empty results):
1. Check `jseval logs --level ERROR --lines 5` FIRST
2. Only after confirming no errors, investigate code-level causes
3. Never inspect JARs, add debug logs, or do clean rebuilds before
   checking the error log
```

This is a behavioral rule, not a technical mechanism. Its value is in
being explicit about the diagnostic order — the agent's default is to
reason from code, not from logs.

### I4. Verification workflow documentation

Add to CLAUDE.md or a dedicated rule file:

```
## Verification workflow for backend changes

1. Ingest ONCE with `jseval dev --clean` + `jseval run --dataset scifact --max-queries 0 --embedding`
2. For subsequent tests: `jseval search --query "..." --ce` (no restart needed for search tests)
3. For code changes: restart backend only (`jseval dev` without --clean)
4. For config changes: restart backend only (config is immutable after init)
5. NEVER re-ingest unless the index schema changed or data is corrupt
6. Use `jseval preflight` after restart to confirm model wiring before testing
7. Use `jseval logs --level ERROR --lines 5` when any search returns unexpected results
```

### I5. Runtime-reconfigurable reranker (future)

The deepest fix for restart waste: make `CrossEncoderReranker` support
runtime reconfiguration. When config changes (via env var or API):
1. Read new `RerankerConfig` from `ConfigStore`
2. Close old `OrtSessionManager`
3. Create new `CrossEncoderReranker` with updated config
4. Swap the reference atomically in `GrpcSearchService`

This eliminates the "restart per config change" loop entirely. All
VRAM/seqLen/topK combinations could be tested on a single running
backend. Estimated complexity: medium (the swap needs careful
thread-safety around in-flight rerank calls).

**Not proposed for immediate implementation.** The verification
tooling (I1-I4) addresses the most impactful failures. Runtime
reconfiguration is a quality-of-life improvement for future
profiling sessions.

## Missing Skills Analysis

### Current coverage

| Skill | Domain | Trigger |
|-------|--------|---------|
| `/inference-runtime` | GPU, ORT, VRAM, model loading | Modifying GPU detection, ORT sessions, encoders |
| `/search-quality` | Retrieval, fusion, reranking, baselines | Modifying search orchestration, eval code |
| `/jseval` | Eval toolkit reference | Running eval, profiling, benchmarking |

### Gap: `/configuration` skill — **recommended**

**Evidence of need:** Tempdocs 247, 286, 293, 300, 301, 329, 331, 338,
347, 360 all touch the configuration system. In tempdoc 360 alone:
- `ResolvedConfigBuilder` default (8192) out of sync with `EnvRegistry`
  default (2048) and `RerankerConfig.DISABLED` (2048)
- Config immutability forcing unnecessary restarts
- Env var propagation through Gradle → Head → Worker snapshot unclear
  (different ordinals, different timing)

The config system has three separate default locations with no
enforcement that they stay in sync:

1. **`EnvRegistry`** — declares env var name + optional string default
2. **`ResolvedConfigBuilder`** — `resolveInt(key, default)` with its
   own default that may differ from EnvRegistry
3. **Per-module constants** — `RerankerConfig.DISABLED`,
   `CrossEncoderReranker.resolveRerankGpuMemMb()` fallback, etc.

The precedence system (ordinal 100=default, 400=env_var,
450=worker_snapshot, 500=jvm_arg) is non-obvious. The Worker config
snapshot is pushed by the Head and may arrive after the Worker's own
`ConfigStore` initialization.

**Proposed skill content (register format):**
- Current three-location default model with sync checklist
- Ordinal precedence table with examples
- Worker snapshot propagation mechanism and timing
- Known sync issues (with tempdoc citations)
- "All places to update when changing a default" checklist
- Open questions (e.g., should `ResolvedConfigBuilder` defaults be
  eliminated in favor of `EnvRegistry`-only defaults?)

**Trigger:** Modifying `EnvRegistry.java`, `ResolvedConfigBuilder.java`,
`ConfigStore`, `ResolvedConfig` records, or any per-module config
constant (`DISABLED`, `fromEnv()`, static fallback defaults).

### Considered and rejected

**Worker lifecycle skill:** Tempdocs 303, 304, 308, 324, 330, 332, 334,
360 modify `initDeferredModels()`, signal bus, shutdown ordering. The
constraints are real but better addressed by improving the existing
`explanation/03-knowledge-server.md` doc to surface ordering constraints
and failure modes — not a register (no experiments to track, no baselines
to protect).

**gRPC/IPC contract skill:** The `common-workflows.md` "Add a gRPC
method" checklist covers the mechanical steps. The constraint knowledge
(deadline categories, delegating service routing, circuit breaker
behavior) is better added to the existing workflow doc or
`reference/api-contract-map.md` than a separate register. No
experiment-tracking dimension.

**General principle:** Skills/registers are valuable when agents
repeatedly waste time re-discovering settled findings or re-running
settled experiments. The configuration system qualifies (agents keep
hitting sync issues between the same three locations). Worker lifecycle
and gRPC contracts don't — the failures are procedural (missed a step)
not epistemic (didn't know the answer).

## Coverage gap analysis

I1-I6 address ~60% of the workflow failures observed in tempdoc 360.
The remaining 40% fall into three categories that need separate fixes.

### Gap A: Distributed systems reasoning (not caught by any item)

**Failure:** Shipped gRPC STANDARD deadline (5s) for an operation that
takes 42s on CPU. The insight "local call → RPC adds a transport timeout
that didn't exist before" is architectural reasoning, not domain knowledge.
No register contains this.

**Fix (I7):** Add to `common-workflows.md` under "Add a gRPC method":

> If the operation was previously a local call, set the gRPC deadline
> to accommodate worst-case execution time. Local calls have no transport
> timeout; gRPC calls do. Use `RERANK` (60s) or `LONG_RUNNING` (300s)
> for heavyweight inference operations. Check the inference-runtime
> register for documented latency ranges.

### Gap B: Operational discipline (not caught by any item)

**Failure:** Launched 6+ background backend tasks, lost track of which
was running with which code, tested against stale Worker instances.

**Fix (I8):** Add to `agent-lessons.md`:

> ## Backend verification discipline
>
> - Always use `jseval dev` in the foreground for interactive testing.
>   Never background the backend with `&` or `run_in_background`.
> - One backend at a time. Kill all Java before starting a new one.
> - After restart, always run `jseval preflight` before testing.
> - Build verification tools (jseval commands) BEFORE starting the
>   verification cycle, not after discovering you need them.

### Gap C: Regression baselines in registers (partially addressed by I6)

**Failure:** Didn't verify that 42s CPU inference was the same as
pre-migration. Had no baseline to compare against. The inference-runtime
register has GPU CE latency (40-80ms per D-001) but no CPU baseline.

**Fix (I9):** Add CPU CE latency baselines to the inference-runtime
register. Every time a latency-sensitive operation is profiled, record
both the GPU and CPU measurements:

> | CE rerank top-20 CPU | GTE-ModernBERT | ~42s | seq=2048, RTX 4070 host | 360 |
> | CE rerank top-20 GPU | GTE-ModernBERT | ~2.2s | seq=512, 2048MB arena, RTX 4070 | 360 |

This gives future agents a reference point for "is this a regression
or expected behavior?"

## Priority

| Item | Impact | Effort | Priority |
|------|--------|--------|----------|
| I1 | High — prevents systematic skill-loading failures | Low (~40 lines JS) | **P1** |
| I3 | High — prevents 30-min diagnostic detours | Trivial (4 lines in rules) | **P1** |
| I6 | High — prevents config sync bugs and propagation confusion | Medium (new register + skill) | **P1** |
| I7 | Medium — prevents deadline misconfiguration on RPC migration | Trivial (doc addition) | **P1** |
| I8 | Medium — prevents background task confusion | Trivial (4 lines in rules) | **P1** |
| I9 | Medium — enables regression detection for latency changes | Low (register entries) | **P2** |
| I4 | Medium — prevents unnecessary restart/re-ingest cycles | Low (doc update) | **P2** |
| I2 | Medium — covers planned work; complementary with I1 | Low (~15 lines in session hook) | **P2** |
| I5 | Low — niche benefit for profiling sessions | Medium (thread-safe swap) | **P3** |

## Related

- **360**: Source of all findings (reranker Worker migration retrospective)
- **353**: Agent friction log (earlier observations of agent workflow issues)
- **293**: Configuration documentation sweep (earlier config system work)
- **300**: Config resolution unification (settled the ordinal model)

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 53 days at audit time.

