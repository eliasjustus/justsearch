package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.BackendRequest;
import io.justsearch.aibackend.backend.BackendResponse;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingRequest;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingResult;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.Provenance;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.InvokeFailureReason;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.Operation;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents.UnloadReason;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 413 followup (#3): runnable test for {@link EmbeddingService} telemetry emit sites.
 * Wires an {@link EmbeddingService} with a recording {@link EmbeddingTelemetryEvents} and a mock
 * {@link AiBackend}, drives every emit path, and asserts the recording captured the expected
 * call sequence with the expected tag values.
 *
 * <p>Closes the gap noted in critical-analysis: the original implementation had a smoke test for
 * the catalog and a wire-format regression test for NDJSON, but no test that actually drove
 * EmbeddingService's embed methods to verify events fire.
 */
final class EmbeddingServiceTelemetryEmitTest {

  @TempDir Path tempDir;

  private RecordingEvents events;
  private MockBackend backend;
  private EmbeddingService service;

  @BeforeEach
  void setUp() {
    events = new RecordingEvents();
    backend = new MockBackend();
    EmbeddingConfig config =
        new EmbeddingConfig(
            true,
            tempDir.resolve("fake-model"),
            "auto",
            false,
            0,
            0L,
            2048);
    service = EmbeddingService.createWithBackend(backend, config, events);
  }

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.close();
    }
  }

  @Test
  void cacheMissFiresOncePerUniqueText() {
    backend.queueResult(singleResult(384));
    backend.queueResult(singleResult(384));
    backend.queueResult(singleResult(384));

    service.embedQuery("alpha");
    service.embedQuery("beta");
    service.embedQuery("gamma");

    long misses = events.count(RecordingEvents.Kind.CACHE_MISS);
    assertEquals(3L, misses, "expected one cache_miss per unique query");
  }

  @Test
  void cacheHitFiresAfterFirstCacheMiss() {
    backend.queueResult(singleResult(384));

    // First call goes through inference (miss).
    service.embedQuery("foo");
    // Second call within TTL hits the cache.
    service.embedQuery("foo");

    assertEquals(1L, events.count(RecordingEvents.Kind.CACHE_MISS));
    assertEquals(1L, events.count(RecordingEvents.Kind.CACHE_HIT));
  }

  @Test
  void chunkedFiresWithChunkCountFromSinglePathBackend() {
    backend.queueResult(chunkedResult(384, 5));

    service.embedQuery("long doc that gets chunked by the backend");

    var chunked = events.firstOf(RecordingEvents.Kind.CHUNKED);
    assertEquals(5, chunked.chunkCount, "chunkCount tag must come from backend's result.chunkCount()");
  }

  @Test
  void chunkedFiresOnBatchPath() {
    // Two-text batch: first non-chunked, second chunked at count=8.
    backend.queueBatchResult(List.of(singleResult(384), chunkedResult(384, 8)));

    service.embedDocumentBatch(List.of("short text", "very long text"));

    long chunkedEmits = events.count(RecordingEvents.Kind.CHUNKED);
    assertEquals(1L, chunkedEmits, "exactly one chunk_count emit for the chunked text");
    assertEquals(8, events.firstOf(RecordingEvents.Kind.CHUNKED).chunkCount);
  }

  @Test
  void invokeFailureBackendException_singlePath() {
    backend.queueException(new BackendException("simulated failure"));

    float[] result = service.embedQuery("trigger failure");

    assertTrue(result == null || result.length == 0, "expected null/empty on backend failure");
    var fail = events.firstOf(RecordingEvents.Kind.INVOKE_FAILURE);
    assertEquals(Operation.SINGLE, fail.operation);
    assertEquals(InvokeFailureReason.BACKEND_EXCEPTION, fail.reason);
  }

  @Test
  void invokeFailureBackendException_batchPath() {
    backend.queueBatchException(new BackendException("simulated batch failure"));

    List<float[]> result = service.embedDocumentBatch(List.of("a", "b"));

    assertTrue(result == null, "expected null on batch backend failure");
    var fail = events.firstOf(RecordingEvents.Kind.INVOKE_FAILURE);
    assertEquals(Operation.BATCH, fail.operation);
    assertEquals(InvokeFailureReason.BACKEND_EXCEPTION, fail.reason);
  }

  @Test
  void invokeFailureClosed_singlePath() {
    service.close();

    float[] result = service.embedQuery("after close");

    assertTrue(result == null || result.length == 0);
    var fail = events.firstOf(RecordingEvents.Kind.INVOKE_FAILURE);
    assertEquals(Operation.SINGLE, fail.operation);
    assertEquals(InvokeFailureReason.CLOSED, fail.reason);
  }

  @Test
  void invokeFailureClosed_batchPath() {
    service.close();

    List<float[]> result = service.embedDocumentBatch(List.of("after", "close"));

    assertTrue(result == null);
    var fail = events.firstOf(RecordingEvents.Kind.INVOKE_FAILURE);
    assertEquals(Operation.BATCH, fail.operation);
    assertEquals(InvokeFailureReason.CLOSED, fail.reason);
  }

  @Test
  void invokeFailureNullText_emitsForBlankAndNull() {
    // Call embed(...) directly to hit the NULL_TEXT guard. embedQuery/embedDocument prepend
    // a non-blank prefix that bypasses the null/blank check, so this reason is reachable
    // only from callers that go straight through embed(String) or embedWithChunks.
    service.embed(null);
    service.embed("   ");

    long nullTextEmits =
        events.records.stream()
            .filter(r -> r.kind == RecordingEvents.Kind.INVOKE_FAILURE)
            .filter(r -> r.reason == InvokeFailureReason.NULL_TEXT)
            .count();
    assertEquals(2L, nullTextEmits);
  }

  @Test
  void recordingEvents_capturesRelativeOrder() {
    // First a miss + chunked, then a hit. Order must be MISS, CHUNKED, HIT.
    backend.queueResult(chunkedResult(384, 3));

    service.embedQuery("doc");
    service.embedQuery("doc"); // hit

    List<RecordingEvents.Record> recs = events.records;
    assertEquals(RecordingEvents.Kind.CACHE_MISS, recs.get(0).kind);
    assertEquals(RecordingEvents.Kind.CHUNKED, recs.get(1).kind);
    assertEquals(RecordingEvents.Kind.CACHE_HIT, recs.get(2).kind);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static EmbeddingResult singleResult(int dim) {
    List<Double> v = new ArrayList<>(dim);
    for (int i = 0; i < dim; i++) v.add(0.1);
    return new EmbeddingResult(v, dim, Map.of());
  }

  private static EmbeddingResult chunkedResult(int dim, int chunkCount) {
    List<Double> primary = new ArrayList<>(dim);
    for (int i = 0; i < dim; i++) primary.add(0.1);
    List<List<Double>> chunks = new ArrayList<>(chunkCount);
    for (int c = 0; c < chunkCount; c++) {
      List<Double> chunk = new ArrayList<>(dim);
      for (int i = 0; i < dim; i++) chunk.add(0.2);
      chunks.add(chunk);
    }
    return new EmbeddingResult(primary, chunks, dim, chunkCount, false, "chunked", Map.of());
  }

  /** Recording {@link EmbeddingTelemetryEvents} that captures every method call. */
  static final class RecordingEvents implements EmbeddingTelemetryEvents {

    enum Kind { CACHE_HIT, CACHE_MISS, CHUNKED, INVOKE_FAILURE, UNLOAD }

    static final class Record {
      final Kind kind;
      final int chunkCount;
      final Operation operation;
      final InvokeFailureReason reason;
      final UnloadReason unloadReason;

      Record(Kind kind, int chunkCount, Operation op, InvokeFailureReason r, UnloadReason ur) {
        this.kind = kind;
        this.chunkCount = chunkCount;
        this.operation = op;
        this.reason = r;
        this.unloadReason = ur;
      }
    }

    final List<Record> records = new ArrayList<>();

    @Override
    public synchronized void onCacheHit() {
      records.add(new Record(Kind.CACHE_HIT, 0, null, null, null));
    }

    @Override
    public synchronized void onCacheMiss() {
      records.add(new Record(Kind.CACHE_MISS, 0, null, null, null));
    }

    @Override
    public synchronized void onChunked(int chunkCount) {
      records.add(new Record(Kind.CHUNKED, chunkCount, null, null, null));
    }

    @Override
    public synchronized void onInvokeFailure(Operation operation, InvokeFailureReason reason) {
      records.add(new Record(Kind.INVOKE_FAILURE, 0, operation, reason, null));
    }

    @Override
    public synchronized void onUnload(UnloadReason reason) {
      records.add(new Record(Kind.UNLOAD, 0, null, null, reason));
    }

    long count(Kind kind) {
      return records.stream().filter(r -> r.kind == kind).count();
    }

    Record firstOf(Kind kind) {
      return records.stream()
          .filter(r -> r.kind == kind)
          .findFirst()
          .orElseThrow(() -> new AssertionError("no record of kind " + kind));
    }
  }

  /** Mock {@link AiBackend} with queued {@link EmbeddingResult}s and configurable exceptions. */
  static final class MockBackend implements AiBackend {

    private final List<EmbeddingResult> queue = new ArrayList<>();
    private final List<List<EmbeddingResult>> batchQueue = new ArrayList<>();
    private final AtomicReference<BackendException> nextException = new AtomicReference<>();
    private final AtomicReference<BackendException> nextBatchException = new AtomicReference<>();

    void queueResult(EmbeddingResult result) {
      queue.add(result);
    }

    void queueBatchResult(List<EmbeddingResult> results) {
      batchQueue.add(results);
    }

    void queueException(BackendException e) {
      nextException.set(e);
    }

    void queueBatchException(BackendException e) {
      nextBatchException.set(e);
    }

    @Override
    public BackendResponse translate(BackendRequest request) {
      throw new UnsupportedOperationException("translate not used in embedding tests");
    }

    @Override
    public Session createSession() {
      return new Session() {
        @Override
        public BackendResponse translate(BackendRequest request) {
          throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) throws BackendException {
          BackendException e = nextException.getAndSet(null);
          if (e != null) {
            throw e;
          }
          if (queue.isEmpty()) {
            return singleResult(request.dimension() > 0 ? request.dimension() : 384);
          }
          return queue.remove(0);
        }

        @Override
        public List<EmbeddingResult> embedBatch(List<EmbeddingRequest> requests)
            throws BackendException {
          BackendException e = nextBatchException.getAndSet(null);
          if (e != null) {
            throw e;
          }
          if (batchQueue.isEmpty()) {
            // Fallback: one result per request.
            List<EmbeddingResult> out = new ArrayList<>(requests.size());
            for (EmbeddingRequest req : requests) {
              out.add(singleResult(req.dimension() > 0 ? req.dimension() : 384));
            }
            return out;
          }
          return batchQueue.remove(0);
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Provenance provenance() {
      return new Provenance("", "mock", 0, 384, 2048);
    }

    @Override
    public void close() {}
  }
}
