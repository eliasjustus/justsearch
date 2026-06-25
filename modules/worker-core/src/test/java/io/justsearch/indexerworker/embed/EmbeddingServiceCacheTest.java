package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.embed.EmbeddingService.ChunkedEmbedding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for EmbeddingService cache behavior with rate-limited eviction.
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>Cache TTL expiration (5 seconds)
 *   <li>Rate-limited eviction (1 second interval)
 *   <li>No memory accumulation under sustained load
 *   <li>Cache cleared on close
 * </ul>
 *
 * <p>These tests use package-private test hooks to directly test cache behavior without requiring an
 * actual embedding model.
 */
final class EmbeddingServiceCacheTest {

  @TempDir Path tempDir;

  private EmbeddingService service;

  @BeforeEach
  void setUp() throws Exception {
    // Create a non-existent model path - service won't be functional but cache can be tested
    Path fakeModelPath = tempDir.resolve("nonexistent-model.gguf");
    service = new EmbeddingService(fakeModelPath, EmbeddingConfig.DISABLED);
  }

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.close();
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper Methods
  // ─────────────────────────────────────────────────────────────────────────────

  /** Creates a test embedding with given values. */
  private ChunkedEmbedding testEmbedding() {
    return new ChunkedEmbedding(new float[] {0.1f, 0.2f, 0.3f}, List.of(), 1);
  }

  /** Populates cache with a test entry. */
  private void populateCacheEntry(String key, long expiresAt) {
    service.putCacheEntryForTesting(key, testEmbedding(), expiresAt);
  }

  /** Triggers eviction if rate limit allows. */
  private void triggerEviction() {
    // Call embedWithChunks with a simple key to trigger eviction logic
    // This will run eviction but fail to produce embeddings (no backend)
    service.embedWithChunks("trigger");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test: Cache TTL Expiration
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void cacheEntryExpiresAfterTtl() throws Exception {
    // Given: A cache entry that expires in 100ms
    long now = System.currentTimeMillis();
    populateCacheEntry("test-key", now + 100);

    assertEquals(1, service.getCacheSizeForTesting(), "Cache should have 1 entry before expiration");

    // When: We wait past the expiration time
    Thread.sleep(150);

    // Allow eviction to run
    service.setLastEvictionTimeForTesting(0);
    triggerEviction();

    // Then: The expired entry should be evicted
    assertEquals(0, service.getCacheSizeForTesting(), "Cache should be empty after TTL expiration");
  }

  @Test
  void cacheEntryNotEvictedBeforeTtl() throws Exception {
    // Given: A cache entry that expires far in the future
    long now = System.currentTimeMillis();
    populateCacheEntry("test-key", now + 60000); // 60 seconds from now

    assertEquals(1, service.getCacheSizeForTesting());

    // Allow eviction to run
    service.setLastEvictionTimeForTesting(0);
    triggerEviction();

    // Then: The entry should still be in the cache (not expired yet)
    assertEquals(1, service.getCacheSizeForTesting(), "Cache entry should not be evicted before TTL");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test: Rate-Limited Eviction
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void evictionIsRateLimited() throws Exception {
    // Given: Multiple expired entries in the cache
    long now = System.currentTimeMillis();
    for (int i = 0; i < 10; i++) {
      populateCacheEntry("key-" + i, now - 1000); // Already expired
    }

    assertEquals(10, service.getCacheSizeForTesting());

    // Set last eviction time to far in the future to ensure rate-limiting is always active
    service.setLastEvictionTimeForTesting(Long.MAX_VALUE / 2);

    // Trigger eviction attempts - should be rate-limited
    for (int i = 0; i < 5; i++) {
      triggerEviction();
    }

    // Then: Eviction should NOT have run (rate-limited)
    assertEquals(10, service.getCacheSizeForTesting(), "Eviction should be rate-limited (cache not cleared)");
  }

  @Test
  void evictionRunsAfterRateLimitInterval() throws Exception {
    // Given: Multiple expired entries
    long now = System.currentTimeMillis();
    for (int i = 0; i < 5; i++) {
      populateCacheEntry("key-" + i, now - 10000); // Expired 10 seconds ago
    }

    assertEquals(5, service.getCacheSizeForTesting());

    // Set last eviction time to distant past (>1s rate limit interval)
    service.setLastEvictionTimeForTesting(0);

    // Trigger eviction
    triggerEviction();

    // Then: Eviction should have run and removed expired entries
    assertEquals(0, service.getCacheSizeForTesting(), "Expired entries should be evicted after rate limit interval");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test: Concurrent Access Safety
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void concurrentCacheAccessIsSafe() throws Exception {
    // Given: Pre-populate cache with some entries
    long now = System.currentTimeMillis();
    for (int i = 0; i < 20; i++) {
      // Mix of expired and non-expired
      long expiresAt = (i % 2 == 0) ? (now - 1000) : (now + 5000);
      populateCacheEntry("initial-" + i, expiresAt);
    }

    // Force eviction to be allowed
    service.setLastEvictionTimeForTesting(0);

    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Future<?>> futures = new ArrayList<>();

    // When: 50 concurrent cache operations from 8 threads
    for (int i = 0; i < 50; i++) {
      final int idx = i;
      futures.add(
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                  // Mix of adds and eviction triggers
                  if (idx % 5 == 0) {
                    service.setLastEvictionTimeForTesting(0);
                    triggerEviction();
                  } else {
                    populateCacheEntry("concurrent-" + idx, System.currentTimeMillis() + 5000);
                  }
                  successCount.incrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }));
    }

    // Release all threads at once
    startLatch.countDown();

    // Wait for completion
    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

    // Then: All operations should complete without exceptions
    assertEquals(50, successCount.get(), "All concurrent operations should complete");

    // And: Cache should be in a consistent state
    int cacheSize = service.getCacheSizeForTesting();
    assertTrue(cacheSize >= 0, "Cache size should be non-negative");
    assertTrue(cacheSize <= 70, "Cache size should be bounded");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test: Memory Accumulation Under Sustained Load
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void noMemoryAccumulationUnderSustainedLoad() throws Exception {
    // Given: Allow eviction to run freely
    service.setLastEvictionTimeForTesting(0);

    // When: Simulate sustained load by adding many entries over time
    long testStart = System.currentTimeMillis();
    int entriesAdded = 0;

    // Add entries over 3 seconds (60 entries at 50ms intervals)
    for (int i = 0; i < 60; i++) {
      long now = System.currentTimeMillis();
      // Entry expires 500ms from now (short TTL for test)
      populateCacheEntry("load-" + i, now + 500);
      entriesAdded++;

      // Allow eviction by resetting the rate limit occasionally
      if (i % 10 == 0) {
        service.setLastEvictionTimeForTesting(0);
        triggerEviction();
      }

      Thread.sleep(50);
    }

    long testDuration = System.currentTimeMillis() - testStart;

    // Then: Cache should not have accumulated all entries
    // With 500ms TTL and 3s test duration, early entries should be gone
    int finalSize = service.getCacheSizeForTesting();

    // Allow some tolerance - we expect most old entries to be evicted
    assertTrue(
        finalSize < 30,
        String.format(
            "Cache should not accumulate indefinitely. "
                + "Added %d entries over %dms, final size=%d",
            entriesAdded, testDuration, finalSize));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test: Cache Cleared on Close
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void cacheClearedOnClose() throws Exception {
    // Given: Cache with some entries
    long now = System.currentTimeMillis();
    for (int i = 0; i < 10; i++) {
      populateCacheEntry("key-" + i, now + 5000);
    }

    assertEquals(10, service.getCacheSizeForTesting());

    // When: Service is closed
    service.close();

    // Then: Cache should be cleared
    assertEquals(0, service.getCacheSizeForTesting(), "Cache should be cleared on close");
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Test: Service State
  // ─────────────────────────────────────────────────────────────────────────────

  @Test
  void serviceReportsUnavailableAfterClose() {
    service.close();
    assertTrue(!service.isAvailable(), "Service should be unavailable after close");
  }
}
