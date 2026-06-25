package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.MigrationGenerationViewBuilder;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.contract.wire.LifecycleState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("StatusLifecycleHandler unit tests")
final class StatusLifecycleHandlerTest {

  @Test
  @DisplayName("healthHttpStatus returns 503 for null state")
  void nullStateReturns503() {
    assertEquals(503, StatusLifecycleHandler.healthHttpStatus(null));
  }

  @ParameterizedTest
  @EnumSource(value = LifecycleState.class, names = {"LIFECYCLE_STATE_READY", "LIFECYCLE_STATE_DEGRADED"})
  @DisplayName("healthHttpStatus returns 200 for READY and DEGRADED")
  void healthyStatesReturn200(LifecycleState state) {
    assertEquals(200, StatusLifecycleHandler.healthHttpStatus(state));
  }

  @ParameterizedTest
  @EnumSource(value = LifecycleState.class, names = {"LIFECYCLE_STATE_STARTING", "LIFECYCLE_STATE_ERROR", "LIFECYCLE_STATE_STOPPING", "LIFECYCLE_STATE_STOPPED"})
  @DisplayName("healthHttpStatus returns 503 for non-healthy states")
  void unhealthyStatesReturn503(LifecycleState state) {
    assertEquals(503, StatusLifecycleHandler.healthHttpStatus(state));
  }

  @Test
  @DisplayName("throughputReadinessReason returns stalled reason when active work has stalled")
  void throughputReadinessReasonReturnsStalled() {
    WorkerOperationalView workerView = workerView(12, 6, 2, "STALLED");
    assertEquals(
        "worker.throughput_stalled",
        StatusLifecycleHandler.throughputReadinessReason(workerView));
  }

  @Test
  @DisplayName("throughputReadinessReason ignores throughput state when no index work is active")
  void throughputReadinessReasonIgnoresIdleBackends() {
    WorkerOperationalView workerView = workerView(0, 0, 0, "STALLED");
    assertNull(StatusLifecycleHandler.throughputReadinessReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason maps embedding BLOCKED_LEGACY → index.embedding_legacy (600 Design A)")
  void compatBlockedReasonMapsEmbeddingLegacy() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("BLOCKED_LEGACY", "LEGACY_INDEX_NO_FINGERPRINT", "", "", "", "", "", true, "embedding_legacy"));
    assertEquals("index.embedding_legacy", StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason maps schema BLOCKED_LEGACY → index.blocked_legacy, prioritized over embedding")
  void compatBlockedReasonMapsSchemaLegacyFirst() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("BLOCKED_MISMATCH", "x", "", "", "", "", "BLOCKED_LEGACY", true, "legacy_index"));
    // Schema is checked before embedding (mirrors the worker's reindexRequiredReason precedence).
    assertEquals("index.blocked_legacy", StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason returns null for a COMPATIBLE / empty compat state")
  void compatBlockedReasonNullWhenCompatible() {
    WorkerOperationalView workerView = compatWorkerView(CompatibilityStatusView.empty());
    assertNull(StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("compatBlockedReason ignores transient REBUILDING (owned by the 595 Stability axis)")
  void compatBlockedReasonIgnoresRebuilding() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("REBUILDING", "REBUILD_IN_PROGRESS", "", "", "", "", "COMPATIBLE", false, ""));
    assertNull(StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("BLOCKED_LEGACY worker view rolls up to retrieval composite DEGRADED + the compat reason (600 Design A end-to-end)")
  void blockedLegacyRollsUpToRetrievalCompositeDegraded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        compatWorkerView(
            new CompatibilityStatusView(
                "BLOCKED_LEGACY", "LEGACY_INDEX_NO_FINGERPRINT", "", "", "", "", "COMPATIBLE", true, "embedding_legacy"));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    // The INDEX_SERVING component is DEGRADED with the specific compat reason...
    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals("index.embedding_legacy", env.components().get("indexServing").reasonCode());
    // ...and it rolls up into the `retrieval` composite the 595 verdict consumes. (The sibling
    // retrieval dims CHUNK_EMBEDDING/LAMBDAMART are DEGRADED-capped by design, so the composite
    // resolves to DEGRADED — never a higher-precedence state that would mask the compat reason.)
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
    assertTrue(
        env.composites().get("retrieval").reasonCodes().contains("index.embedding_legacy"),
        "retrieval composite must carry the compat reason so the verdict can name it");
  }

  @Test
  @DisplayName("COMPATIBLE worker view leaves INDEX_SERVING READY — compat does not degrade it (negative control)")
  void compatibleWorkerViewLeavesIndexServingReady() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view = compatWorkerView(CompatibilityStatusView.empty());
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("READY", env.components().get("indexServing").state());
    String reason = env.components().get("indexServing").reasonCode();
    assertFalse(reason != null && reason.startsWith("index."), "no compat reason when COMPATIBLE");
  }

  // ===== Tempdoc 598 reopen (B-3): dense-serviceability projection onto the retrieval composite =====

  @Test
  @DisplayName("denseUnavailableReason maps UNAVAILABLE compat (no embedding model) → index.dense_unavailable")
  void denseUnavailableReasonMapsUnavailableCompat() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("UNAVAILABLE", "NO_EMBEDDING_MODEL", "", "", "", "", "COMPATIBLE", false, ""));
    assertEquals("index.dense_unavailable", StatusLifecycleHandler.denseUnavailableReason(workerView));
    // It is NOT a compatBlockedReason (no rebuild remedy — a reindex won't add a missing model).
    assertNull(StatusLifecycleHandler.compatBlockedReason(workerView));
  }

  @Test
  @DisplayName("denseUnavailableReason maps COMPATIBLE-but-embedder-down (embeddingReady=false) → index.dense_unavailable")
  void denseUnavailableReasonMapsCompatibleButEmbedderDown() {
    WorkerOperationalView workerView =
        compatWorkerView(
            new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""),
            false);
    assertEquals("index.dense_unavailable", StatusLifecycleHandler.denseUnavailableReason(workerView));
  }

  @Test
  @DisplayName("denseUnavailableReason returns null for COMPATIBLE + embedder ready, and for unknown (null embeddingReady)")
  void denseUnavailableReasonNullWhenServiceableOrUnknown() {
    // COMPATIBLE + embeddingReady=true → dense serviceable → no reason (no false alarm).
    assertNull(
        StatusLifecycleHandler.denseUnavailableReason(
            compatWorkerView(
                new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""),
                true)));
    // COMPATIBLE + embeddingReady=null (unknown) → we never alarm on "don't know".
    assertNull(
        StatusLifecycleHandler.denseUnavailableReason(
            compatWorkerView(
                new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""))));
  }

  @Test
  @DisplayName("denseUnavailableReason does NOT fire for REBUILDING (owned by the 595 Stability axis)")
  void denseUnavailableReasonIgnoresRebuilding() {
    assertNull(
        StatusLifecycleHandler.denseUnavailableReason(
            compatWorkerView(
                new CompatibilityStatusView("REBUILDING", "REBUILD_IN_PROGRESS", "", "", "", "", "COMPATIBLE", false, ""),
                false)));
  }

  @Test
  @DisplayName("UNAVAILABLE compat rolls up to retrieval composite DEGRADED + index.dense_unavailable (end-to-end)")
  void unavailableCompatRollsUpToRetrievalCompositeDegraded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        compatWorkerView(
            new CompatibilityStatusView("UNAVAILABLE", "NO_EMBEDDING_MODEL", "", "", "", "", "COMPATIBLE", false, ""));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals("index.dense_unavailable", env.components().get("indexServing").reasonCode());
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
  }

  @Test
  @DisplayName("COMPATIBLE-but-embedder-down rolls up to retrieval composite DEGRADED + index.dense_unavailable (end-to-end)")
  void compatibleButEmbedderDownRollsUpToRetrievalCompositeDegraded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        compatWorkerView(
            new CompatibilityStatusView("COMPATIBLE", "FINGERPRINT_MATCH", "", "", "", "", "COMPATIBLE", false, ""),
            false);
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("DEGRADED", env.components().get("indexServing").state());
    assertEquals("index.dense_unavailable", env.components().get("indexServing").reasonCode());
    assertEquals("DEGRADED", env.composites().get("retrieval").state());
  }

  @Test
  @DisplayName("OCR blocker degrades retrieval only when visual text extraction is needed")
  void ocrBlockerDegradesRetrievalWhenVisualTextNeeded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, false, "tesseract", "ocr.engine_missing", 2L, 0L, null));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("DEGRADED", env.components().get("visualTextExtraction").state());
    assertEquals("ocr.engine_missing", env.components().get("visualTextExtraction").reasonCode());
    assertTrue(env.composites().get("retrieval").reasonCodes().contains("ocr.engine_missing"));
  }

  @Test
  @DisplayName("OCR blocker is ignored when no visual text extraction is pending")
  void ocrBlockerIgnoredWhenNoVisualTextNeeded() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, false, "tesseract", "ocr.engine_missing", 0L, 0L, null));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("READY", env.components().get("visualTextExtraction").state());
    assertFalse(env.composites().get("retrieval").reasonCodes().contains("ocr.engine_missing"));
  }

  @Test
  @DisplayName("VDU blocker degrades retrieval when baseline visual text still needs VDU")
  void vduBlockerDegradesRetrievalWhenBaselineTextNeedsVdu() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, true, "tesseract", null, 2L, 0L, "vdu.circuit_open"));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("DEGRADED", env.components().get("visualTextExtraction").state());
    assertEquals("vdu.circuit_open", env.components().get("visualTextExtraction").reasonCode());
    assertTrue(env.composites().get("retrieval").reasonCodes().contains("vdu.circuit_open"));
  }

  @Test
  @DisplayName("VDU enrichment blocker degrades aiFeatures without degrading retrieval")
  void vduEnrichmentBlockerDegradesAiFeaturesOnly() {
    StatusLifecycleHandler handler = newHandler();
    WorkerOperationalView view =
        withVisualExtraction(
            compatWorkerView(CompatibilityStatusView.empty()),
            new VisualExtractionView(true, true, "tesseract", null, 0L, 4L, "vdu.missing_mmproj"));
    ReadinessEnvelopeView env = handler.buildReadinessEnvelope(view, readySnapshot());

    assertEquals("READY", env.components().get("visualTextExtraction").state());
    assertEquals("DEGRADED", env.components().get("visualDocumentUnderstanding").state());
    assertEquals(
        "vdu.missing_mmproj", env.components().get("visualDocumentUnderstanding").reasonCode());
    assertFalse(env.composites().get("retrieval").reasonCodes().contains("vdu.missing_mmproj"));
    assertTrue(env.composites().get("aiFeatures").reasonCodes().contains("vdu.missing_mmproj"));
  }

  /** A lifecycle snapshot with all components READY (so INDEX_SERVING is not gated by worker state). */
  private static LifecycleSnapshotV1 readySnapshot() {
    LifecycleSnapshotV1.Component ready =
        new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY);
    return LifecycleSnapshotV1.now(
        new LifecycleSnapshotV1.Lifecycle(LifecycleState.LIFECYCLE_STATE_READY),
        new LifecycleSnapshotV1.Components(ready, ready, ready));
  }

  /** Minimal handler — buildReadinessEnvelope null-guards every non-retrieval dimension's suppliers. */
  private static StatusLifecycleHandler newHandler() {
    return new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        null,
        Instant.now(),
        () -> "OK",
        null,
        null,
        null,
        mock(io.justsearch.app.services.lifecycle.WorkerCapability.class),
        mock(io.justsearch.app.services.lifecycle.InferenceCapability.class));
  }

  private static WorkerOperationalView compatWorkerView(CompatibilityStatusView compat) {
    return compatWorkerView(compat, null);
  }

  /** Tempdoc 598 reopen: overload that sets the health-derived embeddingReady (== isAvailable() on the Worker). */
  private static WorkerOperationalView compatWorkerView(
      CompatibilityStatusView compat, Boolean embeddingReady) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
        .compatibility(compat)
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
        .searchConfig(SearchConfigView.empty())
        .embeddingReady(embeddingReady)
        .build();
  }

  private static WorkerOperationalView withVisualExtraction(
      WorkerOperationalView view, VisualExtractionView visualExtraction) {
    return WorkerOperationalViewBuilder.builder()
        .core(view.core())
        .failure(view.failure())
        .migration(view.migration())
        .compatibility(view.compatibility())
        .queueDb(view.queueDb())
        .enrichment(view.enrichment())
        .gpu(view.gpu())
        .vectorFormat(view.vectorFormat())
        .telemetry(view.telemetry())
        .searchConfig(view.searchConfig())
        .visualExtraction(visualExtraction)
        .buildStamp(view.buildStamp())
        .aiReady(view.aiReady())
        .embeddingReady(view.embeddingReady())
        .build();
  }

  private static WorkerOperationalView workerView(
      long pendingJobsCount, long processingJobsCount, long pendingReadyJobsCount, String throughputWindowState) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, pendingJobsCount, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationViewBuilder.builder()
            .pendingJobsCount(pendingJobsCount)
            .processingJobsCount(processingJobsCount)
            .pendingReadyJobsCount(pendingReadyJobsCount)
            .migrationEnumerator(MigrationGenerationView.empty().migrationEnumerator())
            .build())
        .compatibility(CompatibilityStatusView.empty())
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, throughputWindowState))
        .searchConfig(SearchConfigView.empty())
        .build();
  }
}
