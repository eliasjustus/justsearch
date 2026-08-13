/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.AiInstallStatus;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The functional-status projection is refreshed on READ (tempdoc 824 §3.3c) and the FE polls {@code
 * GET /api/ai/install/status} at ~1 Hz for the whole length of an install — so without a window,
 * every poll re-resolves four encoder rows, two of them off the Worker's policy snapshot. A 5 s TTL
 * keeps the read cheap without letting the surface show a capability state a user could notice as
 * stale.
 */
final class AiInstallServiceFunctionalStatusCacheTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static void addPackage(AiInstallStatus status, String id) {
    var ps = new AiInstallStatus.PackageStatus();
    ps.packageId = id;
    ps.state = "installed";
    status.packages.add(ps);
  }

  @Test
  @DisplayName("two status reads inside the TTL resolve the projection once")
  void repeatedPollsInsideTheTtlProjectOnce() {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AtomicInteger projections = new AtomicInteger();
    svc.setFunctionalStatusSource(
        () -> {
          projections.incrementAndGet();
          return Map.of("splade", "active");
        });
    AtomicLong nanos = new AtomicLong(0L);
    svc.setNanoClockForTest(nanos::get);

    svc.getStatus();
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(4));
    svc.getStatus();

    assertEquals(1, projections.get(), "a 1 Hz install poll must not fan the projection out per read");
  }

  @Test
  @DisplayName("the first read past the TTL sees the new capability state")
  void aReadAfterTheTtlRefreshesTheProjection() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    addPackage(statusOf(svc), "splade");
    AtomicInteger projections = new AtomicInteger();
    svc.setFunctionalStatusSource(
        () -> Map.of("splade", projections.incrementAndGet() == 1 ? "active" : "inactive"));
    AtomicLong nanos = new AtomicLong(0L);
    svc.setNanoClockForTest(nanos::get);

    assertEquals("active", svc.getStatus().packages.get(0).functionalStatus);
    nanos.addAndGet(TimeUnit.SECONDS.toNanos(6));

    assertEquals(
        "inactive",
        svc.getStatus().packages.get(0).functionalStatus,
        "the window bounds staleness — it must not pin the first answer forever");
    assertEquals(2, projections.get());
  }
}
