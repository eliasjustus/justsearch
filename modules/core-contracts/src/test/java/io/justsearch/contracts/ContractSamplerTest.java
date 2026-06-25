package io.justsearch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 402 §3.3: ContractSampler gate + SampleKey validation. */
class ContractSamplerTest {

  @BeforeEach
  void resetFixture() {
    ContractSampler.reset();
  }

  @AfterEach
  void tearDown() {
    ContractSampler.reset();
  }

  @Test
  void shouldSampleFiresOnEveryNthCall() {
    SampleKey key = new SampleKey("test.every5", 5);

    assertFalse(ContractSampler.shouldSample(key), "call 1: not a multiple of 5");
    assertFalse(ContractSampler.shouldSample(key), "call 2");
    assertFalse(ContractSampler.shouldSample(key), "call 3");
    assertFalse(ContractSampler.shouldSample(key), "call 4");
    assertTrue(ContractSampler.shouldSample(key), "call 5: fires");
    assertFalse(ContractSampler.shouldSample(key), "call 6");
    assertFalse(ContractSampler.shouldSample(key), "call 7");
    assertFalse(ContractSampler.shouldSample(key), "call 8");
    assertFalse(ContractSampler.shouldSample(key), "call 9");
    assertTrue(ContractSampler.shouldSample(key), "call 10: fires");
  }

  @Test
  void sampleRateOneFiresEveryTime() {
    SampleKey key = new SampleKey("test.every1", 1);
    for (int i = 0; i < 20; i++) {
      assertTrue(ContractSampler.shouldSample(key), "rate-1 key must fire on call " + (i + 1));
    }
  }

  @Test
  void separateKeysTrackIndependently() {
    SampleKey a = new SampleKey("test.a", 3);
    SampleKey b = new SampleKey("test.b", 3);

    // Hit a three times (a fires on call 3); b not hit yet.
    assertFalse(ContractSampler.shouldSample(a));
    assertFalse(ContractSampler.shouldSample(a));
    assertTrue(ContractSampler.shouldSample(a));

    // Now hit b three times — b must fire on its own call 3, not on a's count.
    assertFalse(ContractSampler.shouldSample(b));
    assertFalse(ContractSampler.shouldSample(b));
    assertTrue(ContractSampler.shouldSample(b));
  }

  @Test
  void resetClearsCounters() {
    SampleKey key = new SampleKey("test.reset", 2);
    assertFalse(ContractSampler.shouldSample(key)); // call 1
    assertTrue(ContractSampler.shouldSample(key));  // call 2 fires

    ContractSampler.reset();

    // After reset, counter starts at 0; next call is call 1 again.
    assertFalse(ContractSampler.shouldSample(key), "after reset, call 1 does not fire");
    assertTrue(ContractSampler.shouldSample(key), "after reset, call 2 fires");
  }

  @Test
  void sampleKeyRejectsZeroOrNegativeSampleRate() {
    assertThrows(IllegalArgumentException.class, () -> new SampleKey("x", 0));
    assertThrows(IllegalArgumentException.class, () -> new SampleKey("x", -1));
    assertThrows(IllegalArgumentException.class, () -> new SampleKey("x", Integer.MIN_VALUE));
  }

  @Test
  void sampleKeyRejectsNullName() {
    assertThrows(NullPointerException.class, () -> new SampleKey(null, 1));
  }

  @Test
  void concurrentIncrementsStaySound() throws Exception {
    // 4 threads × 1000 calls each = 4000 total increments on a rate-10 key.
    // Expected fires: exactly 400. Atomic counter means no lost or duplicated
    // fires.
    SampleKey key = new SampleKey("test.concurrent", 10);
    int threads = 4;
    int iterationsPerThread = 1000;
    AtomicInteger firedCount = new AtomicInteger(0);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(threads);

    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            try {
              startGate.await();
              for (int i = 0; i < iterationsPerThread; i++) {
                if (ContractSampler.shouldSample(key)) {
                  firedCount.incrementAndGet();
                }
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneGate.countDown();
            }
          });
    }
    startGate.countDown();
    assertTrue(doneGate.await(30, TimeUnit.SECONDS), "threads did not finish in time");
    pool.shutdown();

    int expected = (threads * iterationsPerThread) / 10;
    assertEquals(
        expected,
        firedCount.get(),
        "atomic counter must be exact: 4000 increments / 10 = 400 fires");
  }
}
