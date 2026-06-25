package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.GpuDiagnostics;
import io.justsearch.ort.OrtCudaStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the supplier → proto wireup in {@link IndexStatusOps#buildGpu()}.
 *
 * <p>The unit tests for tempdoc 422 ({@code EncoderRuntimeExplainerTest},
 * {@code EncoderRuntimeControllerTest}) exercise the explainer and controller logic against mocked
 * {@code OrtCudaView} inputs — they do not hit the supplier-to-proto path. This test closes that
 * audit-vs-test gap (per CLAUDE.md "Audit-driven fixes need a runnable test"): it builds an
 * {@link IndexStatusOps} with all 9 typed {@code OrtCudaStatus} suppliers wired to distinct values
 * and asserts the resulting {@code GpuDiagnostics} proto carries all 9 {@code *_ort_cuda} fields.
 *
 * <p>Distinct per-encoder values catch off-by-one wireup bugs (e.g., supplier A's data ending up
 * in field B due to a setter/builder method-name typo).
 */
final class IndexStatusOpsGpuDiagnosticsTest {

  @Test
  void allNineOrtCudaSuppliersPopulateDistinctProtoFields() {
    IndexStatusOps ops = newOpsWithNullCollaborators();

    // Distinct OrtCudaStatus per encoder so a swapped wireup is detectable.
    OrtCudaStatus rerankerStatus =
        new OrtCudaStatus(true, true, true, "reranker-v1",
            Path.of("C:/native/reranker"), "", List.of());
    OrtCudaStatus spladeStatus =
        new OrtCudaStatus(true, true, false, "splade-v1",
            Path.of("C:/native/splade"), "splade-failed", List.of("missing-splade.dll"));
    OrtCudaStatus embedStatus =
        new OrtCudaStatus(true, true, true, "embed-v1",
            Path.of("C:/native/embed"), "", List.of());
    OrtCudaStatus nerStatus =
        new OrtCudaStatus(true, false, false, "ner-v1",
            Path.of("C:/native/ner"), "ner-pending", List.of());
    OrtCudaStatus citationStatus =
        new OrtCudaStatus(false, false, false, "",
            null, "citation-cpu-only", List.of());
    OrtCudaStatus bgeM3Status =
        new OrtCudaStatus(true, true, true, "bgeM3-v1",
            Path.of("C:/native/bgeM3"), "", List.of());

    ops.setOrtCudaStatusSupplier(() -> rerankerStatus);
    ops.setSpladeOrtCudaStatusSupplier(() -> spladeStatus);
    ops.setEmbedOrtCudaStatusSupplier(() -> embedStatus);
    ops.setNerOrtCudaStatusSupplier(() -> nerStatus);
    ops.setCitationOrtCudaStatusSupplier(() -> citationStatus);
    ops.setBgeM3OrtCudaStatusSupplier(() -> bgeM3Status);

    GpuDiagnostics gpu = ops.buildGpu();

    // All 6 OrtCuda proto fields are present (proto3 hasXxx works on message-typed fields).
    assertTrue(gpu.hasRerankerOrtCuda(), "reranker_ort_cuda field absent");
    assertTrue(gpu.hasSpladeOrtCuda(), "splade_ort_cuda field absent");
    assertTrue(gpu.hasEmbedOrtCuda(), "embed_ort_cuda field absent");
    assertTrue(gpu.hasNerOrtCuda(), "ner_ort_cuda field absent (tempdoc 422)");
    assertTrue(gpu.hasCitationOrtCuda(), "citation_ort_cuda field absent (tempdoc 422)");
    assertTrue(gpu.hasBgeM3OrtCuda(), "bge_m3_ort_cuda field absent (tempdoc 422)");

    // Each supplier's data lands in its own field — variantId is a cheap distinct-marker.
    assertEquals("reranker-v1", gpu.getRerankerOrtCuda().getVariantId());
    assertEquals("splade-v1", gpu.getSpladeOrtCuda().getVariantId());
    assertEquals("embed-v1", gpu.getEmbedOrtCuda().getVariantId());
    assertEquals("ner-v1", gpu.getNerOrtCuda().getVariantId());
    assertEquals("", gpu.getCitationOrtCuda().getVariantId());
    assertEquals("bgeM3-v1", gpu.getBgeM3OrtCuda().getVariantId());

    // Cross-field detail spot checks — confirms full record passthrough, not just one field.
    assertTrue(gpu.getRerankerOrtCuda().getAvailable());
    assertFalse(gpu.getSpladeOrtCuda().getAvailable());
    assertEquals("splade-failed", gpu.getSpladeOrtCuda().getFailureReason());
    assertEquals(List.of("missing-splade.dll"), gpu.getSpladeOrtCuda().getMissingDllsList());
    assertEquals("citation-cpu-only", gpu.getCitationOrtCuda().getFailureReason());
    assertFalse(gpu.getCitationOrtCuda().getConfigured());
    assertTrue(gpu.getBgeM3OrtCuda().getAvailable());
  }

  @Test
  void absentSuppliersLeaveProtoFieldsUnset() {
    IndexStatusOps ops = newOpsWithNullCollaborators();
    // Wire only embed; leave the other 5 suppliers null.
    OrtCudaStatus embedStatus = OrtCudaStatus.ready("embed-v1", Path.of("C:/native/embed"));
    ops.setEmbedOrtCudaStatusSupplier(() -> embedStatus);

    GpuDiagnostics gpu = ops.buildGpu();

    assertTrue(gpu.hasEmbedOrtCuda());
    // Tempdoc 422 — confirms the if-non-null guard in buildGpu suppresses the proto field
    // when the supplier itself is unset (different from "supplier returns null", which is
    // also guarded one layer down — see asserts below).
    assertFalse(gpu.hasRerankerOrtCuda());
    assertFalse(gpu.hasSpladeOrtCuda());
    assertFalse(gpu.hasNerOrtCuda());
    assertFalse(gpu.hasCitationOrtCuda());
    assertFalse(gpu.hasBgeM3OrtCuda());
  }

  @Test
  void supplierReturningNullSuppressesProtoField() {
    IndexStatusOps ops = newOpsWithNullCollaborators();
    // Suppliers exist but return null — buildGpu's per-encoder null check should still skip
    // the setX() call. Catches a regression where a supplier returns null transiently
    // (e.g., during encoder shutdown / hot-reload) and the proto would otherwise carry a
    // default-valued OrtCuda probe.
    ops.setNerOrtCudaStatusSupplier(() -> null);
    ops.setCitationOrtCudaStatusSupplier(() -> null);
    ops.setBgeM3OrtCudaStatusSupplier(() -> null);

    GpuDiagnostics gpu = ops.buildGpu();

    assertFalse(gpu.hasNerOrtCuda());
    assertFalse(gpu.hasCitationOrtCuda());
    assertFalse(gpu.hasBgeM3OrtCuda());
  }

  /**
   * Builds an {@link IndexStatusOps} with all 15 collaborators set to null. {@code buildGpu()} only
   * touches the supplier fields (all guarded), so it tolerates an otherwise-empty instance. The
   * constructor performs no null-validation; production wires the collaborators via Worker
   * composition and the dedicated setter methods.
   */
  private static IndexStatusOps newOpsWithNullCollaborators() {
    return new IndexStatusOps(
        /* jobQueue */ null,
        /* indexPath */ null,
        /* ingestCountOps */ null,
        /* searchCountOps */ null,
        /* configuredVectorFormat */ null,
        /* storedVectorFormat */ null,
        /* queryVectorFormatActual */ null,
        /* openTimeCommitUserData */ null,
        /* latestCommitUserDataBestEffort */ null,
        /* indexGenerationManager */ null,
        /* migrationProgressSupplier */ null,
        /* metrics */ null,
        /* indexingLoop */ null,
        /* signalBus */ null,
        /* migrationSwitchingMaxDurationMs */ 0L);
  }
}
