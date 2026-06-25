---
title: "267: Eval Campaign Root Cause Synthesis"
type: tempdoc
status: done
created: 2026-03-09
---

> NOTE: Noncanonical analysis doc. May drift. Promote only stable conclusions
> into canonical docs.

# 267: Eval Campaign Root Cause Synthesis

## Purpose

Collapse the issues documented in:

- `tmp/overnight-plan.md`
- `tmp/eval-campaign-progress.md`

into a small number of real root-cause classes, so future work fixes the
underlying system rather than repeatedly working around symptoms.

This doc is not a run log. It is a root-cause synthesis for long-running
search-eval campaigns, especially on Windows.

---

## Scope

Included:

- Phase 5 long-doc config sweep execution issues
- medium / large mixed-corpus matrix execution issues
- SPLADE campaign failures
- eval harness and workflow runner failures encountered during those runs

Excluded:

- search-quality conclusions such as "CE is bad on long docs" unless they were
  incorrectly framed as operational failures
- generic model-quality debates unrelated to campaign execution

---

## Executive Summary

Most of the logged issues are not independent. They reduce to eight root-cause
classes:

1. weak lifecycle ownership and cleanup across Head / Worker / inference
2. non-resumable campaign orchestration
3. static timeout sizing for workload-dependent operations
4. shallow or racy readiness / gate semantics
5. under-specified config precedence and startup side effects
6. contract mismatches between runtime outputs and eval tooling
7. resource contention without explicit phase scheduling
8. observability gaps that make run-state recovery slower than it should be

The dominant blockers are not retrieval quality. They are:

- Windows process / lock cleanup
- long-run workflow resilience
- readiness / timeout policy that is too optimistic for large corpora

---

## Root Cause Classes

### RC1. Weak lifecycle ownership and cleanup

This is the largest root cause.

Symptoms explained:

- orphaned `llama-server`
- stale `app.lock` and `default.index.lock`
- Worker startup timeout
- Worker crash loops after failed runs
- workflow lock persistence after a cell already finished
- shutdown hang after eval work completed
- manual kill-and-clean cycles to continue the campaign

Deeper cause:

- the eval system has multiple processes with coupled state
- stop / restart semantics are not fully synchronous or authoritative
- on Windows, child-process cleanup and lock release are not being treated as a
  first-class part of lifecycle completion

Evidence:

- `tmp/overnight-plan.md`:
  - orphaned llama-server
- `tmp/eval-campaign-progress.md`:
  - P1, P2, P6, P7, P9
  - W3, W4

Implication:

- future long campaigns will continue to fail unless lifecycle completion means
  "all child processes exited and all locks are gone", not merely "parent stop
  command returned"

### RC2. Non-resumable campaign orchestration

Symptoms explained:

- one failed cell crashes the whole mode
- multi-hour progress can be lost by a single transient fault
- manual "remaining cells" scripts were needed
- child workflow hangs block the whole matrix
- operators must manually infer which cells are complete

Deeper cause:

- the matrix runner behaves like a batch script, not like a resumable campaign
  controller
- there is no durable `resume`, `skip completed`, or authoritative child-run
  reconciliation model

Evidence:

- `tmp/eval-campaign-progress.md`:
  - W1
  - W4
  - P7 required `run-remaining-large-cells.mjs`

Implication:

- this is why long runs are fragile even when the underlying retrieval code is
  mostly working

### RC3. Static timeout sizing for workload-dependent operations

Symptoms explained:

- 15s gRPC deadline too short on large or CE-heavy queries
- 30s HTTP timeout too short on large-corpus queries
- 2h indexing timeout too short for large hybrid chunk embedding
- repeated reruns after timeout-only failures

Deeper cause:

- deadlines are configured as fixed constants
- actual cost scales with corpus size, chunk count, mode, and whether CE /
  embeddings / SPLADE are active

Evidence:

- `tmp/overnight-plan.md`:
  - 15s gRPC deadline still exceeded on legal_case_reports with CE
- `tmp/eval-campaign-progress.md`:
  - P4
  - P5

Implication:

- timeout policy needs to become workload-aware or at least corpus-tier-aware

### RC4. Shallow or racy readiness / gate semantics

Symptoms explained:

- SPLADE gate fails right after restart even though data is actually present
- large hybrid runs can finish with weak ANN evidence while still looking
  superficially complete
- operators have to override gates and then manually reason about whether the
  result is trustworthy

Deeper cause:

- some gates check once instead of polling to a stable state
- health and readiness are still conflated in parts of the workflow
- semantic sanity checks are weaker than technical availability checks

Evidence:

- `tmp/eval-campaign-progress.md`:
  - P3
- mixed-corpus hybrid artifacts:
  - several completed hybrid cells have `ann.proof_status=FAIL`

Implication:

- "run completed" is not enough; the workflow needs stable readiness and proof
  semantics before a result is treated as decision-grade

### RC5. Under-specified config precedence and startup side effects

Symptoms explained:

- relative CE model path works in some launch paths and fails in others
- `lambdamartEnabled` in pipeline config does not control runtime the way the
  operator expects
- persisted GPL triples trigger automatic LM training unexpectedly
- debug notes about CE/LM interaction do not match explicit pipeline behavior

Deeper cause:

- behavior is jointly controlled by:
  - env vars
  - pipeline JSON
  - persisted state
  - startup bootstrap logic
- precedence exists in code but is not explicit enough in the operator-facing
  contract

Evidence:

- `tmp/overnight-plan.md`:
  - CE model not loaded due to relative path
  - LambdaMART auto-trains from persisted triples
  - CE is not skipped when LM is active

Implication:

- future eval agents will continue to make false assumptions unless config
  precedence is documented and, where possible, simplified

### RC6. Contract mismatches between runtime outputs and eval tooling

Symptoms explained:

- CE reranked results missing `filename` broke the harness
- zero-result SPLADE run produced formal metrics output instead of failing
- manual retry scripts duplicated fragile logic from the main runner

Deeper cause:

- eval tooling assumes stronger invariants than the runtime always provides
- helper scripts reimplement orchestration logic instead of calling one safe
  canonical path

Evidence:

- `tmp/overnight-plan.md`:
  - null filename in CE-reranked results breaks harness
- `tmp/eval-campaign-progress.md`:
  - P8

Implication:

- search output contracts and workflow runner contracts need stronger validation
  at the boundary

### RC7. Resource contention without explicit phase scheduling

Symptoms explained:

- CE evaluation slows embedding throughput
- previous long runs showed embedding and other AI work stalling each other
- long campaigns spend time waiting on shared compute without an explicit
  scheduler

Deeper cause:

- expensive workloads share CPU / GPU resources
- the system largely relies on operator sequencing rather than an enforced phase
  policy

Evidence:

- `tmp/overnight-plan.md`:
  - CE eval competes with embedding for GPU / CPU

Implication:

- campaign reliability depends on disciplined sequencing because the runtime is
  not yet scheduling these workloads explicitly

### RC8. Observability and run-state recovery gaps

Symptoms explained:

- stale logs and stale progress files misled the operator
- empty rerun logs made a live rerun look dead
- ETA estimates were initially wrong
- operators had to infer true state from multiple partial signals

Deeper cause:

- there is no single authoritative "campaign state" view
- telemetry exists, but it is fragmented across workflow logs, progress files,
  lifecycle metadata, backend status, and lock files

Evidence:

- `tmp/overnight-plan.md`:
  - embedding rate measurement error
  - multiple background processes pile up
- `tmp/eval-campaign-progress.md`:
  - W2

Implication:

- even when work is progressing, humans and agents pay too much coordination
  cost just to determine what is happening

---

## Issue-to-Root-Cause Mapping

| Logged issue | Primary root cause | Secondary root cause |
|---|---|---|
| stale dependency lockfile | RC5 | RC8 |
| orphaned llama-server | RC1 | - |
| embedding rate estimate error | RC8 | - |
| CE model path broken | RC5 | - |
| CE slows embedding | RC7 | RC3 |
| null filename in CE results | RC6 | - |
| LM auto-trains from persisted triples | RC5 | - |
| LM no measurable effect | model/data mismatch | - |
| CE not skipped when LM active | RC5 | RC8 |
| CE deadline exceeded on long docs | RC3 | RC7 |
| multiple background PowerShell processes | RC2 | RC8 |
| SPLADE gate timing race | RC4 | RC1 |
| scifact-splade stuck in shutdown | RC1 | RC2 |
| Worker startup timeout from stale locks | RC1 | RC3 |
| chunk embedding timeout on large hybrid corpus | RC3 | RC7 |
| stale workflow lock blocks rerun | RC1 | RC2 |
| workflow lock not released after success | RC1 | RC2 |
| zero SPLADE metrics from SkipIngest bug | RC6 | RC2 |
| Worker crash loop after stale index lock | RC1 | RC2 |
| matrix runner all-or-nothing behavior | RC2 | - |
| stale run discovery wasted time | RC8 | RC2 |
| no inter-cell delay in matrix runner | RC1 | RC2 |
| workflow runner hang blocks whole matrix | RC1 | RC2 |

---

## What Was Not a Workflow Root Cause

Two important findings should not be mistaken for orchestration bugs:

1. **Current CE is catastrophic on long documents.**
   - This is a model/regime mismatch, not a campaign defect.
2. **GPL-trained LambdaMART adds little or nothing here.**
   - This is a model/data mismatch, not primarily an eval-runner failure.

These still matter, but they belong in search-quality decision docs rather than
in workflow reliability remediation.

---

## Priority Interpretation

### Must-fix before more multi-hour eval campaigns

1. RC1 lifecycle ownership and cleanup
2. RC2 resumable campaign orchestration
3. RC3 timeout sizing for large workloads
4. RC4 readiness polling / stable gate semantics

### Should-fix soon

1. RC5 config precedence clarity
2. RC6 runtime-tooling contract validation
3. RC8 authoritative campaign-state visibility

### Important but second-order

1. RC7 resource scheduling

---

## Coordination Consequence

Future long-running eval work should assume:

1. the main blocker is campaign reliability, not missing retrievers
2. SPLADE and mixed-corpus work are currently bottlenecked more by orchestration
   and lifecycle behavior than by search-quality uncertainty
3. any further large eval effort should be paired with reliability work, not
   treated as purely experimental execution

---

## Suggested Follow-up Streams

1. lifecycle and lock cleanup hardening
2. resumable matrix runner / campaign state machine
3. workload-aware timeout policy
4. stable readiness polling for SPLADE / dense / ANN proof
5. config precedence documentation and simplification

---

## Strategic Conclusion

Long term, the project should not keep shifting effort toward broader and more
elaborate benchmark machinery. The durable value is in improving JustSearch's
actual search behavior: routing defaults, ingestion quality, chunking,
mixed-collection retrieval, and evidence quality.

However, the current campaign logs show that one bounded round of reliability
work is still necessary before those product decisions can be trusted. The
target is not "more eval infrastructure" in the abstract. The target is to make
eval reliable enough that it stops misleading the team.

That implies the following sequencing:

1. fix the minimum reliability blockers in RC1-RC4
2. stop expanding benchmark scope once campaign execution is stable
3. shift the center of gravity back to product logic and routing decisions

In program terms:

1. **Immediate next phase:** predominantly targeted eval hardening
2. **After campaign reliability stabilizes:** predominantly search/product logic

The intended steady state is:

1. evaluation exists to support product decisions
2. evaluation is not allowed to become the main product

---

## Implementation Confidence After Code Inspection

After inspecting the relevant implementation paths, confidence is moderate-to-high
for making long-running eval campaigns reliable enough to stop wasting sessions,
but only moderate for eliminating every documented failure mode in one pass.

### Overall confidence

1. `~0.75` confidence that campaign reliability can be improved enough to make
   future long-running eval usable and decision-grade
2. `~0.55-0.60` confidence that all currently documented issues can be fully
   eliminated in one pass without further edge-case discovery

### Main reason for this confidence level

Most of the problems live in the orchestration and lifecycle wrapper layer, not
in Lucene retrieval core or BEIR metric math. That is good news, because it
keeps the fix surface relatively local:

1. [run-mixed-corpus-matrix.mjs](/d:/code/JustSearch/scripts/search/run-mixed-corpus-matrix.mjs)
   is still a synchronous all-or-nothing loop with no resume/skip-completed
   behavior
2. [run-search-workflow.mjs](/d:/code/JustSearch/scripts/search/run-search-workflow.mjs)
   has workflow locking and progress output, but no child-level timeout inside
   `runManagedPowerShell()`
3. [backend-launcher.mjs](/d:/code/JustSearch/scripts/lib/bench/backend-launcher.mjs)
   has a usable ownership model, but Windows stop/cleanup remains best-effort
   and therefore still carries risk
4. [BeirEval.Search.psm1](/d:/code/JustSearch/scripts/search/lib/BeirEval.Search.psm1)
   is in materially better shape than the campaign runner and already contains
   stricter gating than earlier phases

### Confidence by root-cause area

1. RC2 resumable campaign orchestration: high confidence
   - The code is simple enough to extend with resume, skip-completed-cell, and
     partial-recovery behavior.
2. RC3 workload-aware timeout sizing: high confidence
   - The current timeout policy is mostly hard-coded and local to a few scripts.
3. RC4 stable readiness and gate semantics: high confidence
   - The status surface is already rich enough; the missing work is primarily
     orchestration policy, not missing telemetry.
4. RC6 runtime/tooling contract mismatches: high confidence
   - The recent BEIR centralization already moved the codebase in the right
     direction.
5. RC8 observability and run-state recovery: high confidence
   - Progress files and workflow telemetry already exist; they are just not yet
     being consumed as the primary control plane.
6. RC1 lifecycle ownership and cleanup: moderate confidence
   - This is fixable, but Windows multi-process shutdown and lock release remain
     the riskiest operational area.
7. RC5 config precedence clarity: moderate confidence
   - Fixable for the eval path, but more difficult if expanded into a repo-wide
     cleanup across env vars, settings, sysprops, and persisted state.
8. RC7 resource scheduling: moderate-to-low confidence
   - Eval-phase mitigation is feasible via sequencing and phase isolation.
   - A true runtime scheduler is a larger product/system change.

### Important practical conclusion

The code inspection weakens the idea that these issues require major search-core
rewrites. The strongest near-term path is:

1. standardize eval on the direct lifecycle engine
2. make the matrix runner resumable
3. add workload-aware timeouts and stronger stop-time cleanup
4. treat GPU-heavy phases as explicitly scheduled instead of concurrent by default

The main residual risk is not retrieval semantics. It is Windows process
ownership and shutdown behavior across Head, Worker, and auxiliary inference
processes.

---

## Investigation Update 2026-03-09 11:55 CET

This section records the first full code-backed confidence pass for RC1-RC8.
It is fixability-first and intentionally focuses on owner paths, actual failure
mechanisms, and remediation shape rather than on implementation detail.

### Methods used

Code paths inspected:

- `scripts/lib/bench/eval-backend-lifecycle.mjs`
- `scripts/lib/bench/backend-launcher.mjs`
- `scripts/search/run-search-workflow.mjs`
- `scripts/search/run-mixed-corpus-matrix.mjs`
- `scripts/search/lib/BeirEval.Search.psm1`
- `scripts/search/beir-eval-win.ps1`
- `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java`
- `modules/ui/src/main/java/io/justsearch/ui/config/ConfigStoreRebuilder.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/DebugStateController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/AppFacadeBootstrap.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerConfig.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java`
- `modules/app-util/src/main/java/io/justsearch/app/util/AppInstanceLock.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOps.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOps.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/util/IndexRootLock.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfigBuilder.java`
- `modules/reranker/src/main/java/io/justsearch/reranker/RerankerConfig.java`

Local confirmations gathered:

1. `node .\scripts\lib\bench\test-eval-backend-lifecycle.mjs` => PASS
2. `node .\scripts\search\test-search-workflow-runner.mjs` => PASS
3. `node .\scripts\search\test-run-mixed-corpus-matrix.mjs` => PASS
4. isolated direct-lifecycle probe on `tmp/investigation-rc1`
   - `start --clean hard` succeeded
   - `status` reported `index_serving`
   - `stop` reported `ok`
   - `app.lock` and `default.index.lock` files still existed after stop
   - immediate restart with `--clean none` on the same data dir also succeeded
   - after `stop` returned, Java PIDs could remain alive briefly before exiting

This live probe materially changes one earlier assumption:

- **file presence is not equal to stale lock ownership**
- `app.lock` and sibling `.index.lock` are designed to remain as files after a
  clean stop; the real question is whether a process still holds the lock or
  still has the JVM/file channel alive

### External corroboration used

Primary sources consulted for platform-dependent claims:

1. Node.js child process docs:
   [child_process](https://nodejs.org/api/child_process.html)
   - detached children can outlive the parent
   - `subprocess.unref()` removes the child from the parent's event-loop wait
2. Microsoft `taskkill` reference:
   [taskkill](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/taskkill)
   - `/T` terminates the specified process and any child processes
   - `/F` forcefully ends the process
3. Java `FileLock` API:
   [FileLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html)
   - locks are JVM-held, advisory, and released on channel/JVM termination
4. gRPC deadlines guide:
   [Deadlines](https://grpc.io/docs/guides/deadlines/)
   - no deadline by default; callers must set one intentionally
   - expired deadlines cancel work and surface timeout failure
5. PowerShell `Invoke-RestMethod`:
   [Invoke-RestMethod](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/invoke-restmethod)
   - `TimeoutSec` is caller-specified and DNS/network behavior can exceed naive assumptions
6. PowerShell `Get-NetTCPConnection`:
   [Get-NetTCPConnection](https://learn.microsoft.com/en-us/powershell/module/nettcpip/get-nettcpconnection?view=windowsserver2022-ps)
   - supports port-owner discovery used by the direct launcher

### RC-by-RC findings

#### RC1. Weak lifecycle ownership and cleanup

Owner paths:

- `scripts/lib/bench/eval-backend-lifecycle.mjs`
- `scripts/lib/bench/backend-launcher.mjs`
- `modules/app-services/.../WorkerSpawner.java`
- `modules/app-util/.../AppInstanceLock.java`
- `modules/indexer-worker/.../IndexRootLock.java`

Failure mechanism:

1. direct eval lifecycle uses detached Java children and treats stop success as:
   port closed + runtime artifacts removed
2. Worker/JVM shutdown and file-lock release can lag slightly behind the stop
   return path
3. campaign code that immediately starts the next cell can therefore race the
   tail of the prior process tree

Local evidence:

1. `backend-launcher.mjs` starts backend with `detached: true` and
   `child.unref()`
2. Windows stop path uses `taskkill /PID /T /F`, then polls for port closure
3. `WorkerSpawner.close()` is best-effort:
   scheduler shutdown -> shutdown signal -> bounded wait -> forcible kill
4. live probe:
   - clean stop left `app.lock` and `.index.lock` files present
   - those files did **not** prevent immediate restart with `--clean none`
   - Java PIDs could remain alive briefly even after `stop` returned `ok`

Interpretation:

- some earlier campaign diagnoses likely overfit on visible lock files
- the real RC1 issue is **tail latency of process and handle release**, not
  merely the existence of lock-file paths

Remediation hypothesis:

1. treat lifecycle completion as:
   - port closed
   - targeted PID tree gone
   - optional short grace delay before next start on Windows
2. distinguish:
   - `lock file exists`
   - `lock is actually held / owner still alive`
3. make matrix/campaign restart logic wait for authoritative stop completion,
   not just successful launcher return

Confidence / risk:

- confidence: `0.72`
- risk: `high`

#### RC2. Non-resumable campaign orchestration

Owner paths:

- `scripts/search/run-mixed-corpus-matrix.mjs`
- `scripts/search/run-search-workflow.mjs`

Failure mechanism:

1. matrix runner is a simple nested loop with no resume/skip-completed model
2. any missing metrics artifact or thrown child failure aborts the mode
3. workflow progress is written, but not used as an authoritative source for
   continuation

Local evidence:

1. `run-mixed-corpus-matrix.mjs` writes `ProgressFile` into each cell config
2. the same script does not read those progress files back
3. `test-run-mixed-corpus-matrix.mjs` verifies progress-file creation, not
   resume semantics
4. campaign logs needed ad hoc `run-remaining-large-cells.mjs`

Remediation hypothesis:

1. add per-cell state machine:
   - pending
   - running
   - completed
   - failed
2. compute completion from durable artifacts plus progress metadata
3. add:
   - `--resume`
   - `--rerun-failed`
   - `--skip-completed`
4. make top-level mode summaries append-only and reconcile from child state

Confidence / risk:

- confidence: `0.90`
- risk: `high`

#### RC3. Static timeout sizing for workload-dependent operations

Owner paths:

- `scripts/search/run-mixed-corpus-matrix.mjs`
- `scripts/search/run-search-workflow.mjs`
- `scripts/search/lib/BeirEval.Search.psm1`
- `scripts/lib/bench/backend-launcher.mjs`
- `modules/app-services/.../KnowledgeServerConfig.java`
- `modules/app-services/.../RemoteKnowledgeClient.java`

Failure mechanism:

1. workflow/index/query timeouts are mostly fixed constants or simple operator
   overrides
2. they do not scale from chunk count, document count, query mode, or corpus
   regime
3. long-doc and large mixed-corpus runs therefore hit deadlines that are not
   semantically wrong, only under-sized

Local evidence:

1. `run-mixed-corpus-matrix.mjs` hard-codes `IndexTimeoutSec` per mode
   (`21600` hybrid after manual fix, `7200` otherwise)
2. `KnowledgeServerConfig` still defaults worker deadline to `5000ms`
3. `run-search-workflow.mjs` uses fixed lifecycle ready timeout default `300s`
4. `BeirEval.Search.psm1` now has strict runtime gates, but its HTTP timeout is
   still a caller-side static budget

External corroboration:

- gRPC deadlines are caller-owned and cancellation-on-expiry is expected, so
  too-small budgets directly create `DEADLINE_EXCEEDED`
- `Invoke-RestMethod` timeout behavior is also explicit caller policy, not
  automatic workload adaptation

Remediation hypothesis:

1. introduce corpus-tier timeout policy:
   - small
   - medium
   - large
   - long-doc / large-hybrid
2. derive query deadlines from mode and corpus size
3. derive index wait budgets from expected chunk workload, not document count
4. keep explicit overrides, but stop treating one fixed constant as production
   default for all campaigns

Confidence / risk:

- confidence: `0.86`
- risk: `high`

#### RC4. Shallow or racy readiness / gate semantics

Owner paths:

- `scripts/search/lib/BeirEval.Search.psm1`
- `scripts/search/beir-eval-win.ps1`
- `modules/ui/.../StatusLifecycleHandler.java`
- `modules/indexer-worker/.../IndexStatusOps.java`

Failure mechanism:

1. `/api/health` reports lifecycle readiness, not semantic eval readiness
2. some runtime gates still rely on one-shot status snapshots rather than
   stable polling
3. large evals can therefore start in technically healthy but semantically
   incomplete states

Local evidence:

1. `StatusLifecycleHandler` cleanly separates `/api/health` from `/api/status`
2. `BeirEval.Search.psm1` currently computes SPLADE/dense/LM gates from one
   snapshot, then `Assert-BeirRuntimeGates` fails immediately
3. campaign log `P3` matches this exactly: restart from existing SPLADE index
   passed health, but SPLADE counts were not yet visible when the first gate
   fired
4. hardened BEIR lane already fails closed for dense/SPLADE/LM; the missing
   behavior is **stable poll-until-ready**

Remediation hypothesis:

1. preserve strict runtime gates
2. add bounded stable polling in front of them for restart-sensitive states,
   especially SPLADE and chunk-vector readiness
3. keep `-SkipRuntimeGates` as debug-only escape hatch
4. surface the distinction between:
   - `ready_http`
   - `index_serving`
   - `component_ready_for_requested_mode`

Confidence / risk:

- confidence: `0.82`
- risk: `high`

#### RC5. Under-specified config precedence and startup side effects

Owner paths:

- `modules/configuration/.../ResolvedConfigBuilder.java`
- `modules/ui/.../HeadlessApp.java`
- `modules/ui/.../SettingsController.java`
- `modules/ui/.../ConfigStoreRebuilder.java`
- `modules/ui/.../RuntimeActivationService.java`
- `modules/reranker/.../RerankerConfig.java`
- `modules/app-services/.../KnowledgeServerConfig.java`
- `modules/app-services/.../KnowledgeHttpApiAdapter.java`
- `modules/ui/.../DebugStateController.java`

Failure mechanism:

1. the repo now has a real precedence model in `ResolvedConfigBuilder`, but
   some AI/search features still bypass it
2. reranker and worker configuration still combine:
   - env/sysprop
   - UI settings
   - worker snapshot
   - pipeline request flags
   - persisted training artifacts
3. this creates operator-visible surprises because not all layers obey the same
   precedence contract

Local evidence:

1. `ResolvedConfigBuilder` explicitly defines ordinals:
   - JVM arg 500
   - worker snapshot 450
   - env 400
   - settings 300
   - YAML 200
   - default 100
2. `HeadlessApp` mirrors selected UI settings to sysprops only when blank, then
   builds `ResolvedConfig`, then writes worker snapshot
3. `SettingsController` respects explicit non-UI operator overrides when
   mirroring settings into sysprops
4. `KnowledgeServerConfig` still uses local `envOrProperty()` resolution rather
   than the centralized resolved-config path
5. `RerankerConfig` is also env/sysprop + auto-discovery based, not fully
   aligned with `ResolvedConfig`
6. **confirmed asymmetry:** `KnowledgeHttpApiAdapter` honors
   `pipeline.crossEncoderEnabled()` through `isRerankerEligible()`, but
   LambdaMART reranking only checks whether a model is loaded and never checks
   `pipeline.lambdamartEnabled()`
7. `DebugStateController` still says cross-encoder is "Skipped when LambdaMART
   is active", but the search path now supports cascaded LM + CE execution

Interpretation:

- RC5 is no longer "no precedence exists"
- it is now "precedence exists in part of the stack, but important AI/reranking
  paths still behave outside that contract"

Remediation hypothesis:

1. publish one operator-facing precedence table
2. make request-time pipeline flags symmetric:
   - if CE flag is honored, LM flag must also be honored
3. either migrate `KnowledgeServerConfig` / `RerankerConfig` toward the same
   resolved-config model or clearly document them as exceptions
4. remove stale debug-state notes that imply obsolete behavior

Confidence / risk:

- confidence: `0.74`
- risk: `medium-high`

#### RC6. Contract mismatches between runtime outputs and eval tooling

Owner paths:

- `modules/adapters-lucene/.../SearchResultFormatter.java`
- `scripts/search/lib/BeirEval.Search.psm1`
- `scripts/search/beir-eval-win.ps1`
- Node BEIR artifact selection / validation helpers added in the hardening pass

Failure mechanism:

1. eval tooling still assumes certain search response fields exist and are
   meaningful for doc-id reconstruction
2. missing or differently shaped fields can silently degrade evaluation unless
   caught as a contract failure

Local evidence:

1. the earlier CE `filename` failure was fixed with null-safe access
2. `BeirEval.Search.psm1` still reconstructs BEIR doc IDs from
   `hit.fields.filename`
3. when `filename` is absent, the hit is skipped, not classified as a hard
   schema/contract failure
4. query-error accounting and artifact contract fields are now much stronger
   than before, so RC6 is narrower than it was during the overnight runs

Remediation hypothesis:

1. add explicit contract assertions for BEIR-eligible result hits
2. fail fast when the response shape cannot support doc-id reconstruction for a
   requested eval mode
3. keep v2 artifact selection and parity validation as the single downstream
   consumer contract

Confidence / risk:

- confidence: `0.84`
- risk: `medium`

#### RC7. Resource contention without explicit phase scheduling

Owner paths:

- `modules/app-services/.../AppFacadeBootstrap.java`
- `modules/app-services/.../OfflineCoordinator.java`
- `modules/indexer-worker/.../IndexingLoop.java`
- `modules/indexer-worker/.../EmbeddingBackfillOps.java`
- `modules/indexer-worker/.../SpladeBackfillOps.java`
- `modules/indexer-worker/.../WorkerSignalBus.java`

Failure mechanism:

1. worker-side GPU yielding exists
2. campaign-side scheduling does not own phase sequencing
3. user-facing pause semantics exist in settings/API, but long eval campaigns
   still rely on manual phasing rather than a scheduler or explicit orchestration

Local evidence:

1. `AppFacadeBootstrap` broadcasts main-process GPU status to the MMF signal bus
2. `IndexingLoop.handleGpuStateTransition()` unloads/reloads embedding service
   on GPU ownership changes
3. `EmbeddingBackfillOps` and `SpladeBackfillOps` both interrupt/skip work when
   `isMainGpuActive()` becomes true
4. `OfflineCoordinator` already sequences VDU/LLM first, then indexing mode
5. `pauseIndexingDuringAi` mostly appears in UI/settings and request handlers,
   not as the campaign-level scheduler the logs implicitly wanted

Interpretation:

- RC7 is **not** "missing all coordination"
- it is "worker-side yielding exists, but campaign-level sequencing is still a
  policy/manual concern"

Remediation hypothesis:

1. for eval campaigns, add explicit phase sequencing first
2. do not start with a general runtime scheduler unless campaign-policy fixes
   prove insufficient
3. if user-facing `pauseIndexingDuringAi` is meant to coordinate background
   work, wire it through the same signal/path deliberately instead of leaving it
   as mostly UI-surface state

Confidence / risk:

- confidence: `0.63`
- risk: `medium`

#### RC8. Observability gaps and slow run-state recovery

Owner paths:

- `scripts/search/run-search-workflow.mjs`
- workflow telemetry store helpers
- `scripts/search/run-mixed-corpus-matrix.mjs`
- temp progress/continuation artifacts written by campaign scripts

Failure mechanism:

1. workflow telemetry and progress files exist, but campaign control does not
   consume them as the authoritative run state
2. operators still have to inspect processes, locks, and artifacts manually to
   answer simple questions like:
   - what is still running?
   - what finished?
   - what can resume?

Local evidence:

1. `run-search-workflow.mjs` records workflow events, stale-lock recovery, and
   progress snapshots
2. matrix runner ignores those outputs during restart/reconciliation
3. campaign logs explicitly document wasted time from misleading stale run
   discovery and empty rerun logs

Remediation hypothesis:

1. standardize on one source of truth:
   workflow progress + final artifact manifest
2. teach the matrix runner to reconcile from that state before touching locks
   or rerunning cells
3. surface an explicit `resume plan` artifact at the mode level

Confidence / risk:

- confidence: `0.82`
- risk: `medium-high`

### Updated remediation order

Must-fix before another multi-hour Windows eval campaign:

1. RC2 resumable matrix/workflow orchestration
2. RC1 stricter stop completion and restart sequencing on the direct lifecycle
3. RC3 workload-aware time budgets
4. RC4 stable readiness polling before strict runtime gates

Should-fix in the same general reliability phase:

1. RC8 authoritative run-state recovery
2. RC5 precedence clarification and request-flag symmetry for reranking
3. RC6 stricter response-shape assertions for eval

Can defer behind campaign reliability unless evidence worsens:

1. RC7 broader scheduler work

### Confidence update after this pass

This pass changes confidence in two ways:

1. RC1 is slightly better understood but not materially less risky
   - good news: lock-file presence alone is not the bug
   - bad news: stop completion is still optimistic relative to actual JVM tail
2. RC5 is now clearer and more actionable because one concrete semantic defect
   is confirmed:
   - request-time LambdaMART enablement is not symmetric with request-time CE
     enablement

Revised overall confidence:

1. `~0.78` that the main campaign blockers can be resolved without deep search
   core rewrites
2. `~0.62` that all currently documented issues can be resolved in one coherent
   implementation phase without a second round of design correction

Main residual uncertainty:

- how much of RC1 remains after better orchestration waits and direct-engine
  standardization
- whether any remaining Windows stop races live in launcher code, WorkerSpawner
  timing, or unavoidable JVM/file-lock tail behavior

## Implementation Addendum 2026-03-09

A follow-on implementation branch, `codex/eval-campaign-reliability`, landed the
first concrete reliability slice for this tempdoc. This addendum does not
replace the synthesis above; it records what is now implemented and what is
still not operationally proven.

### Code landed from the implementation branch

Relevant commits:

1. `9e5ee4ae` `feat(eval): harden campaign workflow reliability`
2. `5153f385` `fix(eval): recover stale managed backends before resume`
3. `4f45f1e0` `feat(eval): add phase telemetry and beir contract guards`
4. `9e476df7` `fix(eval): prove hybrid dense evidence across hits`
5. `90f85372` `fix(eval): prove ann via hybrid effective mode`

Implemented scope:

1. `RC2` / `RC8`: `run-mixed-corpus-matrix.mjs` now supports `--resume`,
   incremental summary writes, stale-vs-live reconciliation, and bounded
   per-cell timeouts instead of unbounded child workflow waits.
2. `RC1`: direct lifecycle stop semantics now require both port closure and
   process exit, stop failures surface through workflow progress, and managed
   backend cleanup is stricter on reuse.
3. `RC3` / `RC4`: matrix-generated mode configs now propagate realistic
   `IndexTimeoutSec` and explicit runtime-gate waiting knobs, while indexing and
   runtime gates poll to stable readiness instead of sampling once.
4. `RC4` / `RC8`: workflow progress now includes richer phases plus additive
   backend state summaries so run-state recovery no longer depends on manual log
   inspection.
5. `RC5` / `RC6`: BEIR artifacts now emit effective pipeline and comparability
   truth, enforce stricter result-shape contracts, and request-time LambdaMART
   handling is covered symmetrically with the request pipeline flag.
6. Hybrid ANN proof now uses structural hybrid evidence instead of relying on
   returned-hit dense provenance rates that were too weak for RRF outputs.

### Verification status

Passing during the implementation branch work:

1. `node .\scripts\search\test-search-workflow-runner.mjs`
2. `node .\scripts\search\test-run-mixed-corpus-matrix.mjs`
3. `node .\scripts\lib\bench\test-eval-backend-lifecycle.mjs`
4. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\search\test-beir-eval-search-lib.ps1`
5. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\search\test-beir-eval-indexing-lib.ps1`
6. `node .\scripts\search\test-beir-eval-indexing-integration.mjs`
7. `node .\scripts\bench\test-convert-beir-metrics-v1-to-v2.mjs`
8. `node .\scripts\bench\check-v1-v2-parity.mjs --v2 tmp\beir-eval\slice2fixturesmoke-integration\metrics.v2.json --legacy tmp\beir-eval\slice2fixturesmoke-integration\metrics.json --kind search-eval-beir`
9. `.\gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.worker.KnowledgeHttpApiAdapterHarmfulCombinationsTest --tests io.justsearch.app.services.worker.PipelineConfigPresetExpansionTest`

Known unrelated branch-level verifier gap:

1. `.\gradlew.bat build -x test` is still not a clean signal because the build
   graph executes `:modules:reranker:integrationTest`, which currently fails in
   `CitationScorerIntegrationTest` outside the 267 change set.

### Operational acceptance status

What is proven:

1. Lexical resume is proven in the small mixed-corpus matrix:
   - previously completed lexical cells are reused as `skipped_completed`
   - stale lexical state was recovered and completed successfully
2. Hybrid comparability is proven after the ANN-proof fix:
   - `hybrid/scifact` completed with `comparability.status = "comparable"`
   - `hybrid/fiqa` completed with `comparability.status = "comparable"`
   - the prior `dense_requested_but_ann_proof_failed` blocker is resolved

What is not yet proven:

1. SPLADE operational acceptance is still open.
2. The last live `splade/scifact` run was stopped during merge preparation after
   a clean direct lifecycle stop. No comparable SPLADE artifact was produced,
   so tempdoc 267 is not yet acceptance-complete.
3. `eval-backend-lifecycle stop --data-dir ...` still has a non-blocking CLI
   quirk when no canonical run id is supplied; the managed workflow path uses
   the real run id and was not blocked by this.

### Tempdoc state after merge

Current conclusion:

1. The planned reliability code slice is materially landed.
2. The tempdoc should remain `active` until SPLADE acceptance is either proven
   or explicitly scoped out by the user.

### Mainline merge update 2026-03-10 00:15 CET

The reliability slice is now merged into `main` as commit `f01777cf`.

Merge outcome:

1. the merge was completed in a dedicated integration worktree and then
   fast-forwarded onto `main`
2. the two real merge conflicts were:
   - `docs/tempdocs/267-eval-campaign-root-cause-synthesis.md`
   - `scripts/search/run-mixed-corpus-matrix.mjs`
3. the resolved mainline state keeps:
   - the root-cause synthesis structure from `main`
   - the branch implementation addendum and current acceptance truth
   - the broader resume/runtime-gate propagation logic from the implementation
     branch

Verification fixes that were required during merge validation:

1. `scripts/search/test-beir-eval-indexing-integration.mjs` now passes explicit
   short runtime-gate timeout knobs in the intentional gate-failure cases
   instead of assuming immediate failure under the new runtime-gate waiting
   contract
2. `scripts/bench/convert-beir-metrics-v1-to-v2.mjs` now preserves
   `runtime_gates.timeout_sec` and `runtime_gates.poll_interval_sec`, with
   matching coverage added in `scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs`

Post-merge verification completed on `main`:

1. `node .\scripts\search\test-run-mixed-corpus-matrix.mjs`
2. `node .\scripts\search\test-search-workflow-runner.mjs`
3. `node .\scripts\lib\bench\test-eval-backend-lifecycle.mjs`
4. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\search\test-beir-eval-search-lib.ps1`
5. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\search\test-beir-eval-indexing-lib.ps1`
6. `node .\scripts\search\test-beir-eval-indexing-integration.mjs`
7. `node .\scripts\bench\test-convert-beir-metrics-v1-to-v2.mjs`
8. `node .\scripts\bench\check-v1-v2-parity.mjs --v2 tmp\beir-eval\slice2fixturesmoke-integration\metrics.v2.json --legacy tmp\beir-eval\slice2fixturesmoke-integration\metrics.json --kind search-eval-beir`
9. `.\gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.worker.KnowledgeHttpApiAdapterHarmfulCombinationsTest --tests io.justsearch.app.services.worker.PipelineConfigPresetExpansionTest`
10. `.\gradlew.bat spotlessApply`
11. `.\gradlew.bat assemble`

Current status after merge:

1. lexical resume acceptance remains proven
2. hybrid comparability remains proven
3. SPLADE acceptance remains unproven
4. tempdoc 267 is therefore still active

### SPLADE acceptance update 2026-03-10 11:35 CET

SPLADE was rerun from a fresh isolated worktree on current `main`:
`D:\code\JustSearch-wt\267-splade-acceptance`.

Branch-local preconditions that were explicitly satisfied before the run:

1. worktree-local `models` junction
2. worktree-local `tmp\ort-variant-test\cuda-12.4-pinned` junction
3. worktree-local `tmp\beir-cache\scifact\raw` and `tmp\beir-cache\fiqa\raw`
   junctions
4. fresh branch-local `:modules:ui:installDist` and
   `:modules:indexer-worker:installDist`
5. passing preflight verification:
   - `node .\scripts\search\test-search-workflow-runner.mjs`
   - `node .\scripts\lib\bench\test-eval-backend-lifecycle.mjs`
   - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\search\test-beir-eval-search-lib.ps1`
   - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\search\test-beir-eval-indexing-lib.ps1`

What the reruns proved:

1. the remaining SPLADE problem is not another 267 control-plane failure
2. the managed backend starts cleanly, reaches a healthy worker/index-serving
   state, and stays live while the BEIR child remains active
3. the second rerun did initialize the SPLADE GPU session successfully:
   - `SpladeEncoder GPU session initialized` was logged at
     `2026-03-10 11:03:57 CET`
4. no stale-running / dead-backend / stale-lock recovery defect reappeared in
   the SPLADE lane

What blocked acceptance anyway:

1. the first fresh rerun exposed CUDA preload warnings in the worker log and
   was archived under the isolated worktree at
   `tmp\acceptance-archive\20260310-1002-splade-prepathfix`
2. a second clean rerun was started after a bounded environment retry and was
   archived at `tmp\acceptance-archive\20260310-1031-splade-throughput-blocker`
3. that second rerun showed the backend still ingesting the mixed 62,821-doc
   corpus with no `metrics.v2.json` produced after roughly 25 minutes of real
   work
4. at `2026-03-10 11:29 CET`, `/api/status` reported:
   - `indexedDocuments = 13219`
   - `pendingJobsCount = 49590`
   - `processingJobsCount = 1`
   - `spladeCompletedCount = 13220`
   - `chunkEmbeddingPendingCount = 2382`
5. the worker log at the same stage showed steady per-doc processing in the
   rough `100-1000ms` range for `fiqa` documents, which implies many more hours
   to finish the remaining queue on this machine

Current conclusion from the SPLADE pass:

1. tempdoc 267's remaining gap is now an operational SPLADE-throughput blocker,
   not a lifecycle/readiness/recovery blocker
2. current `main` can start and sustain the SPLADE lane, but the small mixed
   corpus does not finish within a practical acceptance window on this machine
3. tempdoc 267 must remain `active`

### SPLADE closeout update 2026-03-10 15:43 CET

SPLADE acceptance was completed from a fresh isolated worktree on current
`main`: `D:\code\JustSearch-wt\267-splade-throughput`.

What the bounded probe established before the final rerun:

1. a 4,096-doc mixed probe (`mixed_scifact_fiqa_splade_probe_scifact`) ran
   end-to-end cleanly on current `main`
2. the default `2048MB` SPLADE GPU cap already projected the full
   `splade/scifact` lane well under the agreed three-hour budget:
   `27.17 docs/s`, or roughly `0.64h` for 62,821 docs
3. increasing the cap to `4096MB` improved probe throughput only marginally:
   `27.90 docs/s`, about a `2.7%` gain, so no tracked config change was
   justified
4. the probe therefore disproved the earlier "hours-long SPLADE blocker" as a
   current-main acceptance issue and did not require any Java/runtime fix

What the full acceptance run proved on current defaults:

1. `splade/scifact` completed comparably at
   `tmp\beir-eval\matrix\mixed_scifact_fiqa\splade\scifact\metrics.v2.json`
2. the full `splade/scifact` ingest finished at `31.07 docs/s`
   (`ingest_wall_ms = 2020427`) under the existing `2048MB` SPLADE GPU cap
3. `workload.comparability.status == "comparable"`
4. `workload.runtime_gates.gates_passed == true`
5. requested and effective SPLADE are both true
6. `extensions.legacy_v1.results.splade.queryErrorCount == 0`

What the follow-on reuse pass proved:

1. `splade/fiqa` completed comparably at
   `tmp\beir-eval\matrix\mixed_scifact_fiqa\splade\fiqa\metrics.v2.json`
2. `workload.comparability.status == "comparable"`
3. `workload.runtime_gates.gates_passed == true`
4. requested and effective SPLADE are both true
5. `extensions.legacy_v1.results.splade.queryErrorCount == 0`

Final conclusion:

1. tempdoc 267 is acceptance-complete on current `main`
2. no substantive work remains inside 267
3. any future SPLADE performance tuning would be a new optimization tempdoc,
   not unfinished 267 reliability work

## Remaining Work

No substantive work remains for tempdoc 267.

What was closed out:

1. the resume/recovery control-plane slice
2. the LM/CE request-time symmetry slice
3. the v1/v2 converter/parity/runtime-gate contract slice
4. lexical, hybrid, and SPLADE operational acceptance on current `main`

## Valuable Follow-on Lenses

These lenses are not unfinished 267 work. They are the most valuable
perspectives from which to decide what to do next after 267's reliability slice
is complete.

### 1. Regression-prevention lens

Question:

- how do we keep the mixed-corpus resume / workflow / SPLADE path from quietly
  regressing after 267?

Why this matters:

- 267 was acceptance-proven manually
- the remaining risk is not "missing code" but "loss of the proved behavior"

Most likely valuable follow-on:

- add a governed smoke lane for mixed-corpus workflow acceptance on Windows

### 2. Semantic-truth lens

Question:

- are the eval labels, baselines, and comparability stories still semantically
  truthful enough to support product decisions?

Why this matters:

- a reliable runner is still dangerous if "lexical", "hybrid", or baseline
  comparability mean something different from what operators think they mean

Most likely valuable follow-on:

- tighten lexical-mode semantics and refresh any known non-comparable baselines

### 3. Product-decision lens

Question:

- now that the eval control plane is trustworthy, which routing and retrieval
  decisions become the highest-value next product work?

Why this matters:

- 267 was an enabling slice
- the durable value is in using the improved eval stack to make better search
  decisions, not in continuing to center the program on eval infrastructure

Most likely valuable follow-on:

- advance the routing / search-quality program using the now-trustworthy mixed
  corpus and context-quality evidence

### 4. SPLADE-quality lens

Question:

- what quality ceiling remains even though the SPLADE lane is now operationally
  proven?

Why this matters:

- 267 proved that SPLADE runs can complete reliably
- it did not prove that the current SPLADE implementation is quality-optimal
  for long or heterogeneous documents

Most likely valuable follow-on:

- evaluate the known SPLADE truncation and architecture limits as a separate
  search-quality / retrieval-improvement stream

### 5. Operational-stall lens

Question:

- what failures still look "healthy" structurally while being operationally
  degraded or stalled?

Why this matters:

- 267 improved readiness, lifecycle, and progress semantics
- a remaining class of pain is "backend is up, but throughput has collapsed or
  work is no longer meaningfully advancing"

Most likely valuable follow-on:

- consume throughput / progress signals as first-class stall detection rather
  than treating structural readiness as sufficient

### 6. Knowledge-management lens

Question:

- which stable conclusions from 267 should move into canonical docs, and which
  dated run details should be left in tempdocs only?

Why this matters:

- 267 achieved its engineering purpose, but the document drifted partially into
  an execution log
- future engineers should not need to reconstruct durable policy from a long
  historical tempdoc

Most likely valuable follow-on:

- promote stable conclusions into canonical workflow / eval documentation and
  keep tempdoc 267 as archival synthesis rather than living guidance

## Promoted Canonical Docs

The stable follow-on policy from 267 is now promoted into canonical docs:

1. mixed-corpus smoke gate operations and prerequisites:
   - `docs/how-to/validate-workflow-quality.md`
   - `docs/reference/contributing/dag-runner-operations.md`
   - `docs/reference/benchmark-eval-compatibility-matrix.md`
2. operational readiness now consumes throughput-stall signals:
   - `docs/explanation/08-observability.md`
3. eval semantic-truth guidance for lexical BEIR:
   - `docs/how-to/validate-workflow-quality.md`

This tempdoc should now be read as historical synthesis and closeout context,
not as the canonical home for day-to-day workflow-quality policy.

## Deferred Follow-On

Deep SPLADE quality and architecture work remains intentionally out of scope
for 267. That work now lives in:

- `docs/tempdocs/273-splade-quality-and-performance-followup.md`
