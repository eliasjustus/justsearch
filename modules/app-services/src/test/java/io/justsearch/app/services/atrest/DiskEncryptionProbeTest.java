package io.justsearch.app.services.atrest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 629 (FLOOR) — the disk-encryption probe. Verifies the cache window (one read per TTL), the
 * value passthrough, and the never-throws contract (a failing read mechanism degrades to UNKNOWN
 * rather than propagating).
 */
final class DiskEncryptionProbeTest {

  private static final Path VOL = Path.of("C:/data");

  @Test
  void passesThroughTheReadResult() {
    AtRestProtection enc =
        new AtRestProtection(AtRestProtection.State.ENCRYPTED, "shell-property", AtRestProtection.Confidence.MEDIUM);
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, () -> 0L, p -> enc);
    assertEquals(enc, probe.current());
  }

  @Test
  void cachesWithinTheTtlAndRefreshesAfter() {
    AtomicInteger reads = new AtomicInteger();
    AtomicLong clock = new AtomicLong(0L);
    Function<Path, AtRestProtection> mech =
        p -> {
          reads.incrementAndGet();
          return new AtRestProtection(
              AtRestProtection.State.NOT_ENCRYPTED, "shell-property", AtRestProtection.Confidence.MEDIUM);
        };
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, clock::get, mech);

    probe.current();
    probe.current();
    assertEquals(1, reads.get(), "second call within the TTL is served from cache");

    clock.set(DiskEncryptionProbe.CACHE_TTL.toMillis() + 1);
    probe.current();
    assertEquals(2, reads.get(), "a call past the TTL re-reads");
  }

  @Test
  void neverThrows_degradesToUnknown() {
    DiskEncryptionProbe probe =
        new DiskEncryptionProbe(
            VOL,
            () -> 0L,
            p -> {
              throw new RuntimeException("boom");
            });
    assertEquals(AtRestProtection.State.UNKNOWN, probe.current().state());
  }
}
