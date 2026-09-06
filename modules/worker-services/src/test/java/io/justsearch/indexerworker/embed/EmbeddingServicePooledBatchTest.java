/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.onnxruntime.OrtException;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingBackend;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder.EmbedResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the production service-to-ONNX route that avoids materializing unused chunk vectors. */
final class EmbeddingServicePooledBatchTest {
  @TempDir Path temporaryDirectory;

  private OnnxEmbeddingEncoder encoder;
  private EmbeddingTelemetryEvents events;
  private EmbeddingService service;

  @BeforeEach
  void setUp() {
    encoder = mock(OnnxEmbeddingEncoder.class);
    events = mock(EmbeddingTelemetryEvents.class);
    EmbeddingConfig config =
        new EmbeddingConfig(
            true, temporaryDirectory.resolve("unused-model"), "onnx", false, 0, 0L,
            512, false, 8192);
    service =
        EmbeddingService.createWithBackend(
            new OnnxEmbeddingBackend(encoder, 0, 512), config, events,
            "document-task: ", "query-task: ");
  }

  @AfterEach
  void closeService() {
    service.close();
  }

  @Test
  void pooledRoutePreservesOrderFailuresFirstPositiveDimensionAndValidChunkEvents()
      throws Exception {
    List<String> prefixed = List.of(
        "document-task: empty-first", "document-task: first-valid",
        "document-task: empty-later", "document-task: second-valid");
    float[] firstVector = {0.25f, 0.75f};
    float[] secondVector = {0.1f, 0.2f, 0.7f};
    when(encoder.embedBatchPooled(prefixed)).thenReturn(List.of(
        new EmbedResult(new float[0], List.of(), 7),
        new EmbedResult(firstVector, List.of(), 3),
        new EmbedResult(new float[0], List.of(), 4),
        new EmbedResult(secondVector, List.of(), 1)));

    List<float[]> actual = service.embedDocumentBatch(
        List.of("empty-first", "first-valid", "empty-later", "second-valid"));

    verify(encoder).embedBatchPooled(prefixed);
    assertEquals(4, actual.size());
    assertNull(actual.get(0));
    assertArrayEquals(firstVector, actual.get(1));
    assertNull(actual.get(2));
    assertArrayEquals(secondVector, actual.get(3));
    assertEquals(2, service.dimension(), "first successful dimension wins over later results");
    verify(events).onChunked(3);
    verifyNoMoreInteractions(events); // Empty vectors must not emit successful chunk work.
    assertFullChunkRouteUnused();
  }

  @Test
  void allEmptyResultsLeaveDimensionUnchangedAndProduceNullSlots() throws Exception {
    int originalDimension = service.dimension();
    when(encoder.embedBatchPooled(List.of("document-task: a", "document-task: b")))
        .thenReturn(List.of(
            new EmbedResult(new float[0], List.of(), 2),
            new EmbedResult(new float[0], List.of(), 9)));

    List<float[]> actual = service.embedDocumentBatch(List.of("a", "b"));

    assertEquals(2, actual.size());
    assertNull(actual.get(0));
    assertNull(actual.get(1));
    assertEquals(originalDimension, service.dimension());
    verifyNoInteractions(events);
    verify(encoder).embedBatchPooled(List.of("document-task: a", "document-task: b"));
    assertFullChunkRouteUnused();
  }

  @Test
  void singletonBatchAlsoUsesPooledDocumentRoute() throws Exception {
    float[] vector = {0.5f, 0.5f};
    when(encoder.embedBatchPooled(List.of("document-task: only")))
        .thenReturn(List.of(new EmbedResult(vector, List.of(), 2)));

    List<float[]> actual = service.embedDocumentBatch(List.of("only"));

    assertEquals(1, actual.size());
    assertArrayEquals(vector, actual.get(0));
    verify(encoder).embedBatchPooled(List.of("document-task: only"));
    verify(events).onChunked(2);
    verifyNoMoreInteractions(events);
    assertFullChunkRouteUnused();
  }

  @Test
  void ortFailureRetainsBatchBackendExceptionTelemetryAndNullReturn() throws Exception {
    when(encoder.embedBatchPooled(List.of("document-task: a", "document-task: b")))
        .thenThrow(new OrtException("simulated pooled batch failure"));

    assertNull(service.embedDocumentBatch(List.of("a", "b")));

    verify(events).onInvokeFailure(Operation.BATCH, InvokeFailureReason.BACKEND_EXCEPTION);
    verifyNoMoreInteractions(events);
    verify(encoder).embedBatchPooled(List.of("document-task: a", "document-task: b"));
    assertFullChunkRouteUnused();
  }

  private void assertFullChunkRouteUnused() throws OrtException {
    verify(encoder, never()).embedBatchWithChunking(anyList());
    verify(encoder, never()).embed(anyString());
  }
}
