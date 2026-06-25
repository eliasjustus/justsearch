/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of an Operation invocation.
 *
 * <p>Per tempdoc 429 §E.3: the {@code executionId} field threads the batch identifier
 * through the dispatch path so {@link OperationHandler#undo(String)} can later look up
 * the batch. {@link io.justsearch.agent.tools.FileOperationExecutor} (existing 1.1.d
 * code) generates {@code batchId} via {@code UUID.randomUUID().toString()} at execute
 * time; the new substrate threads that same UUID through {@code OperationResult.executionId}
 * so the existing log format stays readable.
 *
 * <p>{@code structuredData} is an optional handler-specific payload (e.g., search results,
 * browse listings); the agent loop's text-projection logic consumes {@code message} for
 * LLM-visible output and may inspect {@code structuredData} for richer surfaces.
 *
 * <p>Slice 3a-2-c Phase B: typed error metadata. {@code errorCode}, {@code errorDetails},
 * and {@code retryable} restore the typed-error semantics that 7 migrated Operations lost
 * by going through the substrate. Legacy HTTP handlers carry typed
 * {@link io.justsearch.app.api.ApiErrorCode} values (POLICY_ONLINE_AI_DISABLED,
 * INSUFFICIENT_VRAM, PACK_IMPORT_RUNNING, etc.); FE consumers branch on these for
 * banners, retry hints, and PERMANENT/TRANSIENT classification. The fields are populated
 * from typed exceptions (ModeTransitionException, AiInstallException,
 * UserPolicyWriteException) at handler-result-construction time; success paths leave
 * them empty.
 */
public record OperationResult(
    boolean success,
    String message,
    Optional<String> executionId,
    Map<String, Object> structuredData,
    Optional<String> errorCode,
    Map<String, Object> errorDetails,
    Optional<Boolean> retryable) {

  public OperationResult {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(executionId, "executionId");
    Objects.requireNonNull(errorCode, "errorCode");
    Objects.requireNonNull(retryable, "retryable");
    structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    errorDetails = errorDetails == null ? Map.of() : Map.copyOf(errorDetails);
  }

  /** Success without undo support. */
  public static OperationResult success(String message) {
    return new OperationResult(
        true, message, Optional.empty(), Map.of(), Optional.empty(), Map.of(), Optional.empty());
  }

  /** Success with an undo-eligible execution id (e.g., FileOperations batchId). */
  public static OperationResult success(String message, String executionId) {
    return new OperationResult(
        true,
        message,
        Optional.of(executionId),
        Map.of(),
        Optional.empty(),
        Map.of(),
        Optional.empty());
  }

  /** Success with structured payload (e.g., search results). */
  public static OperationResult success(String message, Map<String, Object> structuredData) {
    return new OperationResult(
        true,
        message,
        Optional.empty(),
        structuredData,
        Optional.empty(),
        Map.of(),
        Optional.empty());
  }

  /**
   * Tempdoc 577 §2.14 Root III (#18) — return a copy carrying the text-provenance lineage in
   * {@code structuredData.lineage}, so the FE can frame the tool output by where its bytes came from
   * (corpus-quoted vs runtime). Idempotent merge over the existing structuredData; other fields
   * unchanged. Applied once at the dispatch seam — the single authoritative stamp.
   */
  public OperationResult withLineage(OutputLineage lineage) {
    java.util.Map<String, Object> merged = new java.util.HashMap<>(structuredData);
    merged.put("lineage", lineage.wireToken());
    return new OperationResult(
        success, message, executionId, merged, errorCode, errorDetails, retryable);
  }

  /** Failure with reason. No executionId attached (failed invocations cannot be undone). */
  public static OperationResult failure(String message) {
    return new OperationResult(
        false, message, Optional.empty(), Map.of(), Optional.empty(), Map.of(), Optional.empty());
  }

  /**
   * Failure with typed error metadata (slice 3a-2-c Phase B).
   *
   * @param message human-readable error message
   * @param errorCode canonical error token consumers can branch on (e.g.,
   *     "POLICY_ONLINE_AI_DISABLED", "INSUFFICIENT_VRAM"); typically maps to a value
   *     in {@code io.justsearch.app.api.ApiErrorCode}
   * @param errorDetails optional structured details (e.g., {@code mode}, {@code causes}
   *     chain); empty map if not applicable
   * @param retryable whether the caller should retry; null if unknown / not classified
   */
  public static OperationResult failure(
      String message,
      String errorCode,
      Map<String, Object> errorDetails,
      Boolean retryable) {
    return new OperationResult(
        false,
        message,
        Optional.empty(),
        Map.of(),
        Optional.ofNullable(errorCode),
        errorDetails == null ? Map.of() : errorDetails,
        Optional.ofNullable(retryable));
  }
}
