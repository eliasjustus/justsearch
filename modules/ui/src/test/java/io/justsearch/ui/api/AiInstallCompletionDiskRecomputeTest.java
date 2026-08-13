/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.services.ai.install.AiInstallService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 824 §3.3d — the completion-time disk recompute is REACHABLE in production, not just in
 * the injected-verdict unit tests.
 *
 * <p>This lives in {@code modules:ui} for the same reason {@code AiInstallPlanPreviewResumeTest}
 * does: this is the module carrying {@code ai/model-registry.v2.json} on its classpath, so
 * {@code AiInstallService.getManifest()} — and therefore the disk probe — actually answers here. In
 * {@code app-services} the probe always returns "indeterminate" and every assertion about it would
 * be about the degradation branch. Without this test, "the completion claim is checked against
 * disk" would be an audit conclusion rather than a demonstrated one ({@code audit-without-test}).
 *
 * <p>The setup is deliberately adversarial: the packages are staged as {@code "installed"} while the
 * models directory is EMPTY. Pre-824 the run would publish {@code installedFully: true} — the exact
 * {@code unreachable-seed-green} shape, bookkeeping asserting a state nothing verified, and the
 * completing session had no path to catch it because the restart-time recompute is gated on
 * {@code "idle".equals(status.state)}.
 */
final class AiInstallCompletionDiskRecomputeTest {

  @TempDir Path aiHome;

  @Test
  @DisplayName("a run claiming success over an empty models dir is contradicted by disk")
  void completionClaimIsCheckedAgainstDisk() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, aiHome);

    Field statusField = AiInstallService.class.getDeclaredField("status");
    statusField.setAccessible(true);
    AiInstallStatus status = (AiInstallStatus) statusField.get(svc);
    for (String id : new String[] {"embedding", "splade"}) {
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = id;
      ps.state = "installed";
      status.packages.add(ps);
    }

    Method apply = AiInstallService.class.getDeclaredMethod("applyCompletionState");
    apply.setAccessible(true);
    apply.invoke(svc);

    assertFalse(
        status.installedFully,
        "nothing is on disk — a completed run must not publish a green claim it never verified");
    assertTrue(status.repairNeeded, "and the required files that are genuinely missing are named");
  }
}
