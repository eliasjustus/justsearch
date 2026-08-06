package io.justsearch.app.services.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.ort.EncoderRole;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 805 G.3 — the observed-EP cache in front of the Worker RPCs.
 *
 * <p>Written after the implementation's own bug: a {@code Long.MIN_VALUE} "never fetched" sentinel
 * makes {@code now - cachedAtMs} overflow to a large NEGATIVE value, so the freshness test passes
 * forever and the first fetch never happens. That failure is silent by construction — every ONNX
 * feature reports {@code executionProvider: "unknown"} instead of the observation this whole
 * workstream exists to deliver.
 */
final class WorkerEncoderRuntimeCacheTest {

  private static Map<EncoderRole, EncoderRuntimeView> oneView(String failureReason) {
    return Map.of(
        EncoderRole.RERANKER,
        EncoderRuntimeExplainer.explain(
            EncoderRole.RERANKER,
            new OrtCudaView(true, true, false, "cuda12", "path", failureReason, java.util.List.of()),
            Map.of("variant", Map.of("executionProvider", "CUDA"))));
  }

  @Test
  @DisplayName("the FIRST call fetches — a wall-clock timestamp must never read as fresh")
  void firstCallFetches() {
    AtomicInteger fetches = new AtomicInteger();
    var cache =
        new WorkerEncoderRuntimeCache(
            () -> {
              fetches.incrementAndGet();
              return oneView("first");
            },
            () -> 1_754_300_000_000L); // a real epoch-ms value, the case the sentinel bug broke

    Map<EncoderRole, EncoderRuntimeView> views = cache.encoderRuntime();

    assertEquals(1, fetches.get(), "the first read must reach the Worker");
    assertTrue(views.containsKey(EncoderRole.RERANKER));
  }

  @Test
  @DisplayName("repeat reads inside the TTL do not re-hit the Worker; a later read does")
  void ttlBoundsTheRpcRate() {
    AtomicInteger fetches = new AtomicInteger();
    AtomicLong now = new AtomicLong(1_754_300_000_000L);
    var cache =
        new WorkerEncoderRuntimeCache(
            () -> {
              fetches.incrementAndGet();
              return oneView("r" + fetches.get());
            },
            now::get);

    cache.encoderRuntime();
    now.addAndGet(WorkerEncoderRuntimeCache.TTL_MS - 1);
    cache.encoderRuntime();
    assertEquals(1, fetches.get(), "the status endpoint is polled ~1/s; the TTL is what bounds it");

    now.addAndGet(1);
    cache.encoderRuntime();
    assertEquals(2, fetches.get(), "past the TTL the observation refreshes");
  }

  @Test
  @DisplayName("a throwing or empty fetch retains the last known observation, never regresses it")
  void retainsLastKnownGood() {
    AtomicReference<Boolean> healthy = new AtomicReference<>(true);
    AtomicLong now = new AtomicLong(1_754_300_000_000L);
    var cache =
        new WorkerEncoderRuntimeCache(
            () -> {
              if (Boolean.TRUE.equals(healthy.get())) return oneView("known");
              throw new IllegalStateException("worker mid-restart");
            },
            now::get);

    Map<EncoderRole, EncoderRuntimeView> first = cache.encoderRuntime();
    healthy.set(false);
    now.addAndGet(WorkerEncoderRuntimeCache.TTL_MS);

    assertSame(first, cache.encoderRuntime(), "an RPC failure must not blank a known observation");

    // An empty derivation (worker-unreachable policy snapshot) is the same non-regression case.
    healthy.set(true);
    now.addAndGet(WorkerEncoderRuntimeCache.TTL_MS);
    var emptyCache =
        new WorkerEncoderRuntimeCache(Map::of, () -> 1_754_300_000_000L);
    assertTrue(emptyCache.encoderRuntime().isEmpty(), "nothing known yet stays empty, not a claim");
  }
}
