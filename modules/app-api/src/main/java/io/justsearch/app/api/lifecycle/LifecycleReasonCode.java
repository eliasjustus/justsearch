/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stable reason-code taxonomy for {@link LifecycleSnapshotV1} (schema v1).
 *
 * <p>Reason codes must be low-cardinality, stable, and suitable for automation. They must not
 * include dynamic details like file paths, exception messages, or IDs.
 *
 * <p>Stability: stable (API contract)
 */
public enum LifecycleReasonCode {
  // --- Worker ---
  WORKER_SPAWN_FAILED("worker.spawn.failed"),
  WORKER_NOT_CONFIGURED("worker.not_configured"),
  WORKER_STARTING("worker.starting"),
  WORKER_THROUGHPUT_STALLED("worker.throughput_stalled"),
  WORKER_THROUGHPUT_DEGRADED("worker.throughput_degraded"),
  // Tempdoc 600 PART IX — consolidated from raw string literals in StatusLifecycleHandler into the
  // one closed readiness vocabulary (string values unchanged). Worker-availability + embedding-probe
  // readiness states emitted onto the `retrieval`/`aiFeatures` composites.
  WORKER_NOT_STARTED("worker.not_started"),
  WORKER_UNAVAILABLE("worker.unavailable"),
  WORKER_HEALTH_EMBEDDING_NOT_READY("worker.health.embedding_not_ready"),
  WORKER_HEALTH_EMBEDDING_PROBE_MISSING("worker.health.embedding_probe_missing"),
  // Tempdoc 627 — terminal give-up: the supervisor exhausted its restart budget and stopped trying.
  // Distinct from transient worker.unavailable (which retries); this state does not self-recover.
  WORKER_RESTART_EXHAUSTED("worker.restart_exhausted"),
  // Tempdoc 627 — transient: a supervised restart is in flight (capability RECOVERING). Distinct from
  // worker.spawn.failed so the FE verdict renders a routine self-heal as a calm "Restarting…" transient
  // (not an alarming "Service degraded"); it self-recovers when the worker comes back.
  WORKER_RECOVERING("worker.recovering"),

  // --- Index serving / embedding compatibility (tempdoc 600: Design A + PART IX consolidation) ---
  INDEX_NOT_HEALTHY("index.not_healthy"),
  INDEX_BLOCKED_LEGACY("index.blocked_legacy"),
  INDEX_SCHEMA_MISMATCH("index.schema_mismatch"),
  // Tempdoc 628 Stage C: a corruption-triggered rebuild-from-source is in progress (the index was
  // detected corrupt, backed up, and is being rebuilt). Distinct from the BLOCKED_* reindex codes:
  // here the rebuild is already running — the cause is "the index was corrupt", remedy is to wait.
  INDEX_REBUILDING("index.rebuilding"),
  INDEX_EMBEDDING_LEGACY("index.embedding_legacy"),
  INDEX_EMBEDDING_MISMATCH("index.embedding_mismatch"),
  // Tempdoc 598 reopen (B-3): dense/semantic retrieval cannot run for a reason a rebuild does NOT
  // fix — the embedding model is not loaded (`UNAVAILABLE` compat) or the embedder is unavailable on
  // an otherwise-COMPATIBLE index (`embeddingReady=false`). Distinct from the BLOCKED_* legacy/mismatch
  // codes (whose remedy is a reindex). Emitted on the `retrieval` composite so the search banner stops
  // over-claiming "fully semantic" while AUTO has degraded to keyword (the §59 over-claim hole).
  INDEX_DENSE_UNAVAILABLE("index.dense_unavailable"),

  // --- Inference ---
  INFERENCE_STARTING("inference.starting"),
  INFERENCE_OFFLINE("inference.offline"),
  // Tempdoc 656: the AI/Inference capability was the one capability whose failure reasons never
  // reached this closed taxonomy — RuntimeActivationService already detected these causes precisely
  // but only reported them to the immediate ai_activate RPC caller, never to InferenceCapability, so
  // the runtime manifest's ai.pendingReason stayed on generic prose. These mirror the existing
  // VDU_MISSING_MMPROJ / ORT_CUDA_MISSING_DLLS precedent for "a required artifact is absent".
  INFERENCE_MODEL_NOT_CONFIGURED("inference.model_not_configured"),
  INFERENCE_MODEL_NOT_FOUND("inference.model_not_found"),
  INFERENCE_RUNTIME_NOT_INSTALLED("inference.runtime_not_installed"),
  INFERENCE_POLICY_ONLINE_AI_DISABLED("inference.policy_online_ai_disabled"),
  INFERENCE_POLICY_GPU_DISABLED("inference.policy_gpu_disabled"),
  INFERENCE_ACTIVATION_FAILED("inference.activation_failed"),

  // --- Visual text extraction (OCR/VDU) ---
  OCR_DISABLED("ocr.disabled"),
  OCR_ENGINE_MISSING("ocr.engine_missing"),
  OCR_LANGUAGE_MISSING("ocr.language_missing"),
  VDU_AI_OFFLINE("vdu.ai_offline"),
  VDU_INSUFFICIENT_VRAM("vdu.insufficient_vram"),
  VDU_MISSING_MMPROJ("vdu.missing_mmproj"),
  VDU_CIRCUIT_OPEN("vdu.circuit_open"),

  // --- Telemetry ---
  TELEMETRY_UNAVAILABLE("telemetry.unavailable"),
  TELEMETRY_METRICS_STALE("telemetry.metrics.stale"),
  TELEMETRY_METRICS_HIGH_FAILURE_RATE("telemetry.metrics.high_failure_rate"),
  TELEMETRY_DISK_SPACE_LOW("telemetry.disk_space_low"),

  // --- F1: ORT CUDA (GPU reranking) ---
  ORT_CUDA_NOT_CONFIGURED("ort_cuda.not_configured"),
  ORT_CUDA_READY("ort_cuda.ready"),
  ORT_CUDA_MISSING_DLLS("ort_cuda.missing_dlls"),
  ORT_CUDA_PROVIDER_FAILED("ort_cuda.provider_failed"),

  // --- Chunk Embedding (Phase 2 backfill) ---
  CHUNK_EMBEDDING_NOT_READY("chunk_embedding.not_ready"),
  CHUNK_EMBEDDING_IN_PROGRESS("chunk_embedding.in_progress"),

  // --- LambdaMART (reranking model) ---
  LAMBDAMART_NOT_CONFIGURED("lambdamart.not_configured"),
  LAMBDAMART_TRAINING("lambdamart.training"),
  LAMBDAMART_FAILED("lambdamart.failed"),

  // --- GPU saturation (419 C3 V2 P3) ---
  // GPU pinned at high utilization with no current workload (idle leak detection). Monitored
  // by GpuSaturationMonitor + sampler in modules/ui (head-side NVML probe).
  GPU_SATURATED("gpu.saturated");

  private static final Set<String> ALLOWED_CODES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.stream(values()).map(LifecycleReasonCode::code).toList()));

  private final String code;

  LifecycleReasonCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static boolean isKnown(String code) {
    return code != null && ALLOWED_CODES.contains(code);
  }

  public static Set<String> allowedCodes() {
    return ALLOWED_CODES;
  }
}
