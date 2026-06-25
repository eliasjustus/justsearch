---
title: "Session Policy Explainer (C4 backend slice)"
type: tempdocs
status: done
created: 2026-04-27
---

# 422 — Session Policy Explainer (C4 backend slice)

## Status

**IMPLEMENTED 2026-04-27.** Backend slice shipped: the proto extension
folded all 6 encoders' `OrtCudaProbeResult` into `GpuDiagnostics`,
collapsing Path A/B into a unified Path A explainer, and the new
`GET /api/inference/encoders` endpoint produces the per-encoder
"why CPU/GPU/unavailable?" answer for the active encoder set. Frontend
slice remains explicitly deferred to a separate product tempdoc.

CPU-forced-mode honest-reporting (case 5 in the explainer's decision
tree) is the only deferred follow-up: the wording "GPU configured but
not yet attempted; running on CPU until first inference" is a slight
over-claim before any inference has happened. Closing the gap requires
plumbing the assembler's accelerator-decision through a typed channel,
which exceeds this tempdoc's scope.

Sibling work: tempdoc 414 (shipped 2026-04-26) added the
`ort.session.*` operational telemetry that this explainer can
optionally consume for trend evidence (Tier 2 below). Tempdoc 419's C4
disposition was *"Surface as read-only explanation in diagnostics/
export and possibly Brain advanced details. Do not make it a settings
editor."* This tempdoc honors that framing.

## Goal

Produce a single read-only API endpoint that answers, per encoder,
the question:

> **"Why is encoder X currently on CPU/GPU/unavailable?"**

Today this question requires correlating three separate surfaces
manually: `/api/status` `*OrtCuda` blocks, `/api/debug/session-policies`
policy snapshot, and (post-414) `metrics-worker.ndjson` for trend
evidence. None of those surfaces are individually framed as a
user/agent answer; the consumer has to assemble the answer themselves.

This tempdoc ships the assembly: a derived endpoint that returns a
structured per-encoder explanation, suitable for both an MCP tool
(exposed in a follow-up) and a future Brain/Health UI surface
(separate frontend tempdoc).

## Persona (from 419 C4)

- Power user / support workflow asking *"why is GPU not active for
  embedding right now?"*
- AI agent (in-app or MCP) explaining a degraded retrieval/indexing
  outcome.
- Developer triaging a support report without a full debug session.

This is **not** a settings-editor surface — the policy data is
derived from boot-time resolution and would not be safely
user-mutable.

## Out of scope

- **Frontend.** A Brain/Health "encoder state" panel is a separate
  product tempdoc that consumes this endpoint.
- **MCP tool wrapping.** A `justsearch_inference_encoders` MCP tool
  is a follow-up; the API endpoint is the substrate.
- **Trend-evidence data (Tier 2).** Including "fallbacks in last 30
  minutes" requires `archivedTo(STANDARD)` on a few 414 metrics; that
  is deliberately deferred to keep this tempdoc to one focused slice.
- **Settings editor.** No mutation surface; per 419 C4 disposition.
- **Cross-process streaming of transition events.** That's a separate
  follow-up to 414's substrate.
- **Per-session VRAM accounting.** NVML provides whole-GPU VRAM; per-
  session attribution is a separate (likely never) problem.
- **Coverage of the `bgem3` and `citation` encoders that are missing
  from `GpuDiagnosticsView` today.** This tempdoc covers all 6
  encoders via the new endpoint; adding them to `GpuDiagnosticsView`
  is a separate hygiene fix.

## Empirical grounding (investigation, 2026-04-27)

| Source | Surface today | Per-encoder coverage | What it provides |
|---|---|---|---|
| `OrtCudaView` (`modules/app-api/.../status/`) | `/api/status` via `GpuDiagnosticsView` | Only 3 of 6 encoders (reranker, splade, embed); missing ner, citation, bgem3 | `attempted`, `available`, `configured`, `failureReason`, `missingDlls`, `nativePath`, `variantId` |
| `PolicySnapshot` (`modules/ort-common/.../PolicySnapshot.java`) | `/api/debug/session-policies` via `SessionPoliciesController` | All 6 encoders (`models[ROLE]` map) | `RuntimePolicy` + per-`EncoderRole` `ModelSessionPolicy` (variant, gpu config, lifecycle, runOptions) |
| `ort.session.*` metrics (tempdoc 414) | `metrics-worker.ndjson` only — **NOT RRD-archived**, **NOT `surfacedAt`** | Per-`consumer` tag (all 6 encoders) | `gpu_init_total{outcome}`, `gpu_init_failure_total{cause}`, `fallback_total`, `recovery_total{cause}`, `release_total{outcome}`, `retry_total`, `retry_interval_ms`, `assembler_failure_total{kind}`, `semaphore_wait_us` |

**Findings that scope this tempdoc:**

1. **`OrtCudaView` covers only 3 of 6 encoders today**, and the gap is at the gRPC proto layer, not just the Head-side mapper. `WorkerStatus.proto`'s `gpuStatus` message has `spladeOrtCuda` / `embedOrtCuda` / `rerankerOrtCuda` fields only. NER, citation, and bgem3 have no runtime `OrtCudaView` field anywhere — the data exists Worker-side via `SessionHandle.status()` but isn't exposed cross-process. The C4 endpoint can either:
   - **(a)** Ship V1 with reduced fidelity for NER/citation/bgem3 (only `policy` + `configuredAccelerator`, no runtime `currentAccelerator` / `available` / `failureReason`). Document the gap.
   - **(b)** Extend the gRPC proto + `WorkerStatusMapper` to expose all 6 sub-statuses. Proper fix, larger scope, separate hygiene work.
   - **(c)** Add a NEW gRPC RPC `GetEncoderRuntimeStatus` that returns runtime state for all 6 encoders. Cleanest, also larger scope.
   - **Decision**: V1 ships with (a); document (b) as the natural V2 expansion. (c) is overkill for one tempdoc.
2. **`RemoteKnowledgeClient.getSessionPolicies()` returns untyped `Map<String, Object>`.** Per §14.28 U4, the proto wire format is decoupled from `RuntimePolicy` schema evolution by carrying JSON strings, which the client deserializes via Jackson. The explainer therefore parses the policy data from `Map<String, Object>` paths (`runtime.cudaProvider.cudaDeviceId`, `models.EMBEDDING.gpu.arenaCapBytes`, etc.) — it cannot consume typed `RuntimePolicy` records on the Head side without either re-resolving (which §14.28 U4 explicitly forbids) or deserializing the JSON into typed records (defeats the decoupling).
3. **My 414 metrics are not currently queryable for trend.** The catalog has zero `archivedTo(...)` declarations. So Tier 1 of this tempdoc cannot include "X fallbacks in last 30 minutes" without first adding archiving — which is a real change to the 414 catalog. That's why Tier 2 (with trend data) is deferred.
4. **The existing `/api/debug/session-policies` endpoint is dev-namespaced.** C4 explicitly wants a user/agent-facing surface, not a debug one. The new endpoint is therefore **`/api/inference/encoders`**, not under `/api/debug/`.

## Design

### Tier 1 (this tempdoc) — point-in-time derived explainer

New endpoint:

```text
GET /api/inference/encoders
```

Response shape (typed via new `app-api` records):

```json
{
  "encoders": {
    "embed": {
      "currentAccelerator": "cuda",
      "configuredAccelerator": "cuda",
      "available": true,
      "explanation": "GPU initialized successfully on CUDA device 0; arena cap 3072 MB.",
      "policy": {
        "executionProvider": "CUDA",
        "arenaCapBytes": 3221225472,
        "deferCpuSession": false,
        "gpuRetryEnabled": true,
        "gpuRetryIntervalMs": 60000
      },
      "details": {
        "variantId": "onnxruntime-gpu",
        "nativePath": "F:/JustSearch/.../cuda-12.4-v1.24.3",
        "failureReason": "",
        "missingDlls": []
      }
    },
    "ner": { ... },
    "splade": { ... },
    "reranker": { ... },
    "citation": { ... },
    "bgem3": { ... }
  },
  "snapshotStatus": "ok" | "worker-unreachable" | "policy-unavailable"
}
```

**Field semantics:**

- `currentAccelerator`: derived enum `cuda` / `cpu` / `unavailable` (the latter when GPU was attempted but failed and no CPU session is active).
- `configuredAccelerator`: derived from `policy.executionProvider`. Distinguishes "CPU because of intentional config" from "CPU because of GPU init failure."
- `available`: true if at least one session (CPU or GPU) is operating.
- `explanation`: derived sentence, one of ~6 prose templates (see Derivation rule below).
- `policy`: subset of `ModelSessionPolicy` fields that are operationally relevant (omits internal flags like `arenaShrinkage`).
- `details`: passthrough of `OrtCudaView` fields when available, OR synthesized from `PolicySnapshot` when not.

### Derivation rule

Decision tree producing `currentAccelerator` + `explanation`. Two paths
based on whether `OrtCudaView` is available for the encoder (gRPC
proto exposes it only for splade/embed/reranker today; finding #1):

**Path A — `OrtCudaView` available (splade / embed / reranker):**

```
1. If snapshotStatus != "ok": currentAccelerator = "unavailable",
     explanation = "Worker not reachable; encoder state unknown."
2. If policy.executionProvider == CPU:
     currentAccelerator = "cpu",
     configuredAccelerator = "cpu",
     explanation = "Encoder is configured for CPU by design."
3. If OrtCudaView.available && OrtCudaView.attempted:
     currentAccelerator = "cuda",
     explanation = "GPU initialized successfully on CUDA device 0; arena cap N MB."
4. If OrtCudaView.attempted && !OrtCudaView.available && OrtCudaView.failureReason.nonBlank:
     currentAccelerator = "cpu",
     explanation = "GPU init failed: <failureReason>. Running on CPU fallback."
5. If !OrtCudaView.attempted && OrtCudaView.configured:
     currentAccelerator = "cpu",
     explanation = "GPU configured but not yet attempted; running on CPU until first inference."
6. Otherwise (catch-all):
     currentAccelerator = "cpu",
     explanation = "GPU not active; running on CPU. See /api/debug/session-policies for raw policy."
```

**Path B — `OrtCudaView` unavailable (ner / citation / bgem3 today):**

```
1. If policy is missing in PolicySnapshot:
     currentAccelerator = "unavailable",
     explanation = "Encoder not active in current configuration."
2. If policy.executionProvider == CPU (citation-scorer always; ner/bgem3 in CPU-only mode):
     currentAccelerator = "cpu",
     configuredAccelerator = "cpu",
     explanation = "Encoder is configured for CPU by design."
3. Otherwise (policy.executionProvider == CUDA but no runtime view):
     currentAccelerator = "unknown",
     configuredAccelerator = "cuda",
     explanation = "Encoder is configured for CUDA. Runtime state unavailable
       (proto gap — see tempdoc 422 finding #1). Add gRPC sub-status field for
       full visibility."
```

The `unknown` value in Path B is honest about the proto gap and points
to the V2 work that closes it. It must NOT be reported as `cpu` —
that would imply a CPU fallback that may not have happened.

This is the heart of the tempdoc: turning disconnected backend surfaces
into one prose answer per encoder, with explicit handling for the
3-of-6 proto coverage gap.

### Module placement

| File | Module | What |
|---|---|---|
| New `EncoderRuntimeView.java` (record) | `modules/app-api/src/main/java/io/justsearch/app/api/inference/` | Per-encoder typed view (the JSON object value). |
| New `EncoderRuntimeResponse.java` (record) | same | Top-level wrapper (`encoders` map + `snapshotStatus`). |
| New `EncoderRuntimeExplainer.java` | `modules/app-services/src/main/java/io/justsearch/app/services/observability/` | Pure derivation logic: `(OrtCudaView, ModelSessionPolicy) → EncoderRuntimeView`. Stateless, table-driven; unit-testable. |
| New `EncoderRuntimeController.java` | `modules/ui/src/main/java/io/justsearch/ui/api/inference/` | Javalin HTTP adapter. Reads `OrtCudaView` per-encoder + `PolicySnapshot` from Worker via `RemoteKnowledgeClient`; calls explainer; returns response. |
| Route registration | `modules/ui/.../api/LocalApiServer.java` | `GET /api/inference/encoders → controller.handle` |
| Tests | `modules/app-services/.../observability/EncoderRuntimeExplainerTest.java` (table-driven derivation cases); `modules/ui/.../inference/EncoderRuntimeControllerTest.java` (smoke + worker-unreachable handling) |

### Tier 2 (deferred) — trend evidence

Out of scope for this tempdoc, but documented as the natural next
slice. Adds:

- `archivedTo(STANDARD)` declarations on `ort.session.gpu_init_failure_total`, `fallback_total`, `recovery_total` in 414's catalog (small change in `worker-services`).
- New optional `trends` block in the response: `recentFallbackCount30m`, `recentInitFailureCount30m`, `recentRecoveryCount30m` per encoder, queried from `RrdMetricStore`.
- Extended `explanation` strings: *"GPU active. 3 fallbacks in last 30 minutes — main process is claiming GPU."*

Tier 2 belongs to a follow-up tempdoc (or this tempdoc's V2) once the operational use-case for trend data justifies the cardinality cost.

## Critical files

**New (5 files, 2 tests):**

- `modules/app-api/.../inference/EncoderRuntimeView.java`
- `modules/app-api/.../inference/EncoderRuntimeResponse.java`
- `modules/app-services/.../observability/EncoderRuntimeExplainer.java`
- `modules/app-services/.../observability/EncoderRuntimeExplainerTest.java`
- `modules/ui/.../api/inference/EncoderRuntimeController.java`
- `modules/ui/.../api/inference/EncoderRuntimeControllerTest.java`

**Modified:**

- `modules/ui/.../api/LocalApiServer.java` — register `GET /api/inference/encoders` route + late-bind `RemoteKnowledgeClient` (mirrors `SessionPoliciesController`'s late-bind pattern).
- `modules/app-services/.../worker/RemoteKnowledgeClient.java` — if not already, expose a typed accessor for `PolicySnapshot` (already exists via `getSessionPolicies`, may need a thinner accessor).
- `docs/explanation/05-ai-architecture.md` — add a brief "Encoder runtime state" subsection explaining the new endpoint.
- `docs/tempdocs/412-observability-pattern-adoption.md` — note in § Required follow-up that 422 takes over the C4 explainer slice.
- `docs/tempdocs/419-unused-user-agent-capability-discovery.md` — add C4 to the Ownership annotations table.

## Sequencing

Estimate: ~1 day. **Actual: shipped 2026-04-27.**

1. ✅ **Phase 1.1 — Proto extension.** Added `ner_ort_cuda` (tag 10), `citation_ort_cuda` (tag 11), `bge_m3_ort_cuda` (tag 12) to `GpuDiagnostics`. Folded into V1 per user direction so all 6 encoders return runtime state instead of 3-of-6 with "unknown" for the rest. Path B in the original design is no longer needed.
2. ✅ **Phase 1.2 — Worker-side supplier wiring.** Extended `GpuDiagnosticSuppliers` record (with backwards-compat constructors), added 3 new `volatile Supplier<OrtCudaStatus>` fields + setters + delegators on `IndexStatusOps` / `GrpcIngestService`, extended `DefaultWorkerAppServices.wireGpuDiagnostics`. Added `getOrtCudaStatus()` to `NerService` (delegates to `BertNerInference`) and `CitationScorer` (delegates to `SessionHandle.status()`). Stored `citationScorerInstance` as a field on `KnowledgeServer` so `DevReloadManager` can preserve the reference across hot-reload.
3. ✅ **Phase 1.3 — Head-side mapper extension.** Extended `GpuDiagnosticsView` record with 3 new `OrtCudaView` fields + null-coalescing in compact ctor; updated `WorkerStatusMapper` to populate them from the new proto fields.
4. ✅ **Phase 1.4 — Schema regen.** `:modules:app-api:updateSchemas -PupdateSchemas` regenerated `status-response.schema.json` + cross-language fixtures. Affected module tests green.
5. ✅ **Phase 2 — Typed accessor on `RemoteKnowledgeClient`.** Added `getEncoderOrtCudaViews()` returning `Map<EncoderRole, OrtCudaView>` keyed by enum (`EnumMap` for deterministic iteration). Promoted `WorkerStatusMapper.mapOrtCudaProbe` from `private static` to package-private so the new accessor can reuse the proto→view mapping.
6. ✅ **Phase 3.1 — API records.** `EncoderRuntimeView` (record: currentAccelerator/configuredAccelerator/available/explanation/policy/details) + `EncoderRuntimeResponse` (record: encoders map keyed by `EncoderRole.consumerName()` + snapshotStatus) in `modules/app-api/.../inference/`. Keys use the lowercase short identifier (`embed`, `bgem3`, `splade`, `ner`, `reranker`, `citation`) so operators can correlate with tempdoc 414's `consumer` metric tag without an additional translation table.
7. ✅ **Phase 3.2/3.3 — `EncoderRuntimeExplainer` + tests.** Pure-static `explain(EncoderRole, OrtCudaView, Map<String,Object>) → EncoderRuntimeView`. 13 tests cover all 6 decision-tree cases × representative encoders + 4 defensive edges (missing executionProvider, malformed variant node, arenaCapBytes as string, arenaCapBytes malformed). All green.
8. ✅ **Phase 4 — Controller + late-bind + route.** `EncoderRuntimeController` mirrors `SessionPoliciesController`'s pattern (volatile client + setClient + package-private `buildResponse`). Iterates `policies.models.entrySet()` for the active encoder set, looks up the per-role view from `getEncoderOrtCudaViews()`, calls the explainer, and emits the response. Registered in `InferenceRoutes.register` (extended signature) and wired through `LocalApiServer` constructor + `lateBindKnowledgeServer`. 7 tests covering null-client, worker-unreachable passthrough, empty-policies, happy-path 2-encoder, unknown role key skip, late-bind flip, unbind. All green.
9. ✅ **Phase 5a — Doc updates.** `docs/explanation/05-ai-architecture.md`, `docs/tempdocs/412-observability-pattern-adoption.md`, this tempdoc.
10. ✅ **Phase 5b — Live-stack validation.** `jseval dev` boot + curl on 2026-04-28; see § Validation evidence below for full output.

## Validation evidence (2026-04-28)

`jseval dev` cold boot, no roots configured. All 5 active encoders populated by the explainer; bgeM3 absent because splade is the active sparse encoder (mutual exclusivity working as designed).

**`GET /api/inference/encoders`** — abbreviated for readability; full responses on disk under `tmp/422-validation/`:

```json
{
  "encoders": {
    "reranker": {
      "currentAccelerator": "cuda",
      "configuredAccelerator": "CUDA",
      "available": true,
      "explanation": "GPU initialized successfully on CUDA device 0; arena cap 2048 MB.",
      "details": {"configured": true, "attempted": true, "available": true,
                  "variantId": "onnxruntime-gpu",
                  "nativePath": "F:\\JustSearch\\tmp\\ort-variant-test\\cuda-12.4-v1.24.3"}
    },
    "embed": {
      "currentAccelerator": "cpu",
      "configuredAccelerator": "CUDA",
      "explanation": "GPU configured but not yet attempted; running on CPU until first inference.",
      "details": {"configured": true, "attempted": false, "available": false,
                  "failureReason": "GPU session not yet initialized (lazy)"}
    },
    "splade": { "...same case 5 shape as embed..." },
    "ner":    { "...same case 5 shape as embed..." },
    "citation": {
      "currentAccelerator": "cpu",
      "configuredAccelerator": "CPU",
      "explanation": "Encoder configured for CPU by design.",
      "details": {"configured": false, "attempted": false, "available": false,
                  "failureReason": "GPU not configured"}
    }
  },
  "snapshotStatus": "ok"
}
```

**`GET /api/status .worker.gpu`** — confirms the proto extension wireup end-to-end: all 9 OrtCuda fields populate correctly, including the 3 new ones from the proto extension (`nerOrtCuda` populated; `citationOrtCuda` + `bgeM3OrtCuda` carry the `notConfigured()` shape because citation is CPU-only by design and bgeM3 isn't loaded in this configuration).

Decision-tree cases observed in this run:
- **Case 2** (CPU by design) → citation: `executionProvider=CPU` policy, returns `currentAccelerator="cpu"` + "configured for CPU by design".
- **Case 3** (GPU available) → reranker: GPU initialized successfully with arena cap reported in MB.
- **Case 5** (configured, not yet attempted) → embed/splade/ner: `details.attempted=false` + lazy GPU init message — this is the deferred CPU-forced-mode honesty case explicitly carried over to a future tempdoc.

Cases 1 (policy missing), 4 (GPU init failed with reason), and 6 (catch-all) are covered by the `EncoderRuntimeExplainerTest` suite — they require specific runtime configurations (encoder disabled, GPU init failure, malformed policy) that this no-roots dev-stack boot doesn't reach. Combined coverage: unit tests exercise all 6 cases × defensive edges, live-stack curl confirms the 3 cases that fire on the default config.

**JAR-bundled CUDA still works (F-011)**: `details.nativePath` shows the variant resolution dir, `details.missingDlls=[]`, and reranker reports `available=true`. The `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=<empty-dir>` reproducer step from the original validation gate is not exercised here — the Phase 5b evidence covers the standard happy path. Triggering F-011 explicitly would require a second boot with the env var override; documented as optional follow-up.

## Validation gate

After dev-stack boot:

- `curl http://127.0.0.1:33221/api/inference/encoders` returns 200 with all 6 encoders in the `encoders` map.
- For each encoder: `currentAccelerator ∈ {cuda, cpu, unavailable}`, `explanation` is a non-empty string.
- Boot the worker with `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=<empty-dir>` (the documented "doesn't actually fail GPU init" reproducer) — confirm `currentAccelerator=cuda` for active encoders (because JAR-bundled CUDA still works) but `details.missingDlls` is non-empty. This validates the derivation handles the "DLLs missing but GPU still works" case correctly.
- Stop Worker mid-session — confirm `snapshotStatus=worker-unreachable` and per-encoder fields default sensibly.

## Long-term considerations

- **MCP tool exposure.** A `justsearch_inference_encoders` MCP tool returning the same JSON would close the agent-facing half of C4. Trivial wrapper if the endpoint is stable.
- **Brain/Health UI panel.** A frontend tempdoc would consume this endpoint and present per-encoder cards in BrainView's advanced section.
- **Cross-tempdoc 412 alignment.** If 412's holder rewrite eventually adds an "InferenceLifecycleManager" runtime explainer for the chat LLM, the same derivation pattern applies. Worth keeping the explainer's API shape generalizable (e.g., `EncoderRuntimeView` could become `OrtRuntimeView` and a sibling `LlmRuntimeView` ships when 412's holder rewrite lands).
- **GpuDiagnosticsView coverage gap.** This tempdoc bypasses that gap by going through `PolicySnapshot` for all 6 encoders. A separate hygiene tempdoc could close the original `GpuDiagnosticsView` 3-of-6 gap.

## Dependencies

- ✅ Tempdoc 414 substrate (shipped 2026-04-26): metrics exist if Tier 2 is later activated.
- ✅ Tempdoc 397 substrate: `PolicySnapshot` + `/api/debug/session-policies` already provide per-encoder policy data.
- Independent of 412 (LLM holder rewrite) and 415 (agent-session work).
- Soft cross-reference with 419 C36 (capability explanation layer): if a C36-shaped surface ships, this endpoint is one of several backends it could read from.
