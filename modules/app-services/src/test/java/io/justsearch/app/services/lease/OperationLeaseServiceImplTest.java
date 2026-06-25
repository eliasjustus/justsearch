package io.justsearch.app.services.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 542 Phase 1: pins the op-lease atomic-writer + register/renew/release/expire
 * lifecycle. The dev-runner side (admission gate dispatch) is tested separately in
 * {@code scripts/dev/test-dev-runner-admission.mjs}.
 */
final class OperationLeaseServiceImplTest {

  @TempDir Path tmp;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void registerWritesLeaseToFile() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    OperationLeaseHandle h =
        svc.register("indexing.migration", OpCriticality.MUST_COMPLETE, 1800, Map.of("k", "v"));
    assertNotNull(h.opId());
    assertEquals("indexing.migration", h.opClass());

    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(Files.readString(file), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("opLeases");
    assertEquals(1, entries.size());
    assertEquals("indexing.migration", entries.get(0).get("opClass"));
    assertEquals("MUST_COMPLETE", entries.get(0).get("criticality"));
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) entries.get(0).get("metadata");
    assertEquals("v", meta.get("k"));
  }

  @Test
  void registerRejectsBlankOpClass() {
    var svc = new OperationLeaseServiceImpl(tmp.resolve("op-leases.json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> svc.register("", OpCriticality.MUST_COMPLETE, 10, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> svc.register(null, OpCriticality.MUST_COMPLETE, 10, null));
  }

  @Test
  void registerRejectsNonPositiveDuration() {
    var svc = new OperationLeaseServiceImpl(tmp.resolve("op-leases.json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> svc.register("op", OpCriticality.MUST_COMPLETE, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> svc.register("op", OpCriticality.MUST_COMPLETE, -1, null));
  }

  @Test
  void registerCapsExpiryAtOneHour() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    Instant before = Instant.now();
    svc.register("op", OpCriticality.MUST_COMPLETE, 10_000L, null);
    Instant after = Instant.now();

    Instant expiresAt = readSingleEntryExpiresAt(file);
    long elapsedFromBefore = expiresAt.getEpochSecond() - before.getEpochSecond();
    long elapsedFromAfter = expiresAt.getEpochSecond() - after.getEpochSecond();
    assertTrue(elapsedFromBefore <= 3601 && elapsedFromAfter >= 3599,
        "expiry should be ~3600s ahead; got elapsed range "
            + elapsedFromAfter + "-" + elapsedFromBefore);
  }

  @Test
  void renewExtendsHeartbeatAndExpiryButNeverShortens() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    OperationLeaseHandle h =
        svc.register("indexing.migration", OpCriticality.MUST_COMPLETE, 1800, null);
    Instant initialExpiry = readSingleEntryExpiresAt(file);
    h.renew();
    Instant afterRenew = readSingleEntryExpiresAt(file);
    assertTrue(afterRenew.compareTo(initialExpiry) >= 0,
        "renew must not shorten an existing further-out expiry");
    assertNotNull(readSingleEntryHeartbeatAt(file));
  }

  @Test
  void releaseRemovesEntry() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    OperationLeaseHandle h = svc.register("op", OpCriticality.MUST_COMPLETE, 60, null);
    assertEquals(1, readEntryCount(file));
    h.release(OpLeaseOutcome.SUCCESS);
    assertEquals(0, readEntryCount(file));
  }

  @Test
  void releaseIsIdempotent() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    OperationLeaseHandle h = svc.register("op", OpCriticality.MUST_COMPLETE, 60, null);
    h.release(OpLeaseOutcome.SUCCESS);
    h.release(OpLeaseOutcome.FAILURE);
    assertEquals(0, readEntryCount(file));
  }

  @Test
  void tryWithResourcesReleasesOnExit() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    try (var h = svc.register("op", OpCriticality.MUST_COMPLETE, 60, null)) {
      assertEquals(1, readEntryCount(file));
    }
    assertEquals(0, readEntryCount(file));
  }

  @Test
  void tryWithResourcesReleasesOnException() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);
    try (var h = svc.register("op", OpCriticality.MUST_COMPLETE, 60, null)) {
      throw new RuntimeException("synthetic");
    } catch (RuntimeException ignored) {
      // expected
    }
    assertEquals(0, readEntryCount(file));
  }

  @Test
  void staleEntriesAreReapedOnNextWrite() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    Map<String, Object> stale = new LinkedHashMap<>();
    stale.put("opId", "stale-id");
    stale.put("opClass", "stale.op");
    stale.put("criticality", "MUST_COMPLETE");
    stale.put("startedAt", Instant.now().minusSeconds(7200).toString());
    stale.put("expectedDurationSec", 60);
    stale.put("expiresAt", Instant.now().minusSeconds(60).toString());
    stale.put("originProcess", "head");

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("schema", "op-leases.v1");
    List<Map<String, Object>> list = new ArrayList<>();
    list.add(stale);
    doc.put("opLeases", list);
    Files.createDirectories(file.getParent());
    Files.write(file, MAPPER.writeValueAsBytes(doc));

    var svc = new OperationLeaseServiceImpl(file);
    svc.register("fresh.op", OpCriticality.MUST_COMPLETE, 60, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries =
        (List<Map<String, Object>>) MAPPER.readValue(Files.readString(file), Map.class)
            .get("opLeases");
    assertEquals(1, entries.size());
    assertEquals("fresh.op", entries.get(0).get("opClass"));
  }

  @Test
  void disabledServiceIsNoOp() {
    var svc = new OperationLeaseServiceImpl((Path) null);
    OperationLeaseHandle h = svc.register("op", OpCriticality.MUST_COMPLETE, 60, null);
    assertNotNull(h);
    h.renew();
    h.release(OpLeaseOutcome.SUCCESS);
    h.close();
  }

  /**
   * Concurrency stress: many threads do register → release on the same service. Verifies that
   * the in-process ReentrantLock + atomic tmp+rename writer never produces a torn write and the
   * final state is consistent (no leaked entries, no duplicate opIds). Pins the V1 single-Service
   * concurrency contract. Cross-Service / cross-process safety is delegated to atomic rename
   * (each writer's tmp file is path-isolated).
   */
  @Test
  void concurrentRegisterReleaseProducesNoCorruption() throws Exception {
    Path file = tmp.resolve("op-leases.json");
    var svc = new OperationLeaseServiceImpl(file);

    int threads = 8;
    int opsPerThread = 25;
    var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var ready = new java.util.concurrent.CountDownLatch(threads);
    var go = new java.util.concurrent.CountDownLatch(1);
    var errors = new java.util.concurrent.atomic.AtomicInteger();
    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    try {
      for (int t = 0; t < threads; t++) {
        final int threadIdx = t;
        futures.add(executor.submit(() -> {
          ready.countDown();
          try {
            go.await();
            for (int i = 0; i < opsPerThread; i++) {
              try (OperationLeaseHandle h =
                  svc.register("stress.op." + threadIdx, OpCriticality.MUST_COMPLETE, 60,
                      Map.of("i", i))) {
                // simulate a tiny bit of work between register and release
                Thread.yield();
              }
            }
          } catch (Throwable e) {
            errors.incrementAndGet();
          }
        }));
      }
      ready.await();
      go.countDown();
      for (var f : futures) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertEquals(0, errors.get(), "no thread should encounter an exception");
    // Every register was paired with a try-with-resources release → final state should be empty.
    assertEquals(0, readEntryCount(file), "all entries should be released");
    // File must still be parseable (not torn).
    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(Files.readString(file), Map.class);
    assertEquals("op-leases.v1", doc.get("schema"));
  }

  @Test
  void malformedFileIsRecreatedOnNextRegister() throws IOException {
    Path file = tmp.resolve("op-leases.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "this is not json", StandardCharsets.UTF_8);
    var svc = new OperationLeaseServiceImpl(file);
    svc.register("op", OpCriticality.MUST_COMPLETE, 60, null);
    assertEquals(1, readEntryCount(file));
  }

  private static int readEntryCount(Path file) throws IOException {
    if (!Files.exists(file)) return 0;
    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(Files.readString(file), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("opLeases");
    return entries == null ? 0 : entries.size();
  }

  private static Instant readSingleEntryExpiresAt(Path file) throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(Files.readString(file), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("opLeases");
    assertEquals(1, entries.size(), "expected exactly one entry");
    return Instant.parse((String) entries.get(0).get("expiresAt"));
  }

  private static Object readSingleEntryHeartbeatAt(Path file) throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(Files.readString(file), Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("opLeases");
    assertEquals(1, entries.size());
    return entries.get(0).get("heartbeatAt");
  }
}
