/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sandbox round 8 — what {@code GET /api/ai/install/plan-preview} states is what the consent dialog
 * states, so this is where the "you owe 10.14 GB" lie was told over 1.2 GB the user already had.
 *
 * <p>Lives in {@code modules:ui} alongside the rest of the install-flow test suite; {@code
 * AiInstallService.getManifest()} loads the real registry here via the real classpath resource.
 * (Since tempdoc 840 the registry ships from {@code modules:configuration}, so app-services tests
 * can load it too — see {@code ModelRegistryClasspathReachabilityTest} — this placement is for
 * suite continuity, not resource reachability.) The assertions are DIFFERENTIAL (same machine, same
 * registry, one staged {@code .partial} between the two reads), so they hold on any hardware
 * profile without pinning a byte total.
 */
final class AiInstallPlanPreviewResumeTest {

  private static final long STAGED_BYTES = 4_096L;

  @TempDir Path aiHome;

  @Test
  void stagedPartialBytes_areExcludedFromTheConsentTotal_andReportedSeparately() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, aiHome);

    InstallPlanPreview before = svc.previewInstallPlan();
    assertEquals(0, before.resumableBytes, "an empty models dir has nothing staged");

    // Stage a partial for a file this very plan wants, exactly as a cancelled download would leave it.
    InstallPlan plan =
        InstallPlanner.plan(
            svc.getManifest(),
            svc.buildHardwareProfile(),
            svc.installIntent(),
            svc.modelsDir(),
            svc.aiHome());
    assumeFalse(plan.downloads().isEmpty(), "registry planned no downloads on this hardware");
    InstallPlan.PlannedDownload dl =
        plan.downloads().stream()
            .filter(d -> d.sizeBytes() > STAGED_BYTES)
            .findFirst()
            .orElseThrow();
    Path target = svc.modelsDir().resolve(dl.targetPath());
    Files.createDirectories(target.getParent());
    Files.write(InstallPlanner.partialPathFor(target), new byte[(int) STAGED_BYTES]);

    InstallPlanPreview after = svc.previewInstallPlan();

    assertEquals(
        STAGED_BYTES,
        after.resumableBytes,
        "the preview must report the bytes the pause dialog promised would be kept");
    assertEquals(
        before.totalDownloadBytes - STAGED_BYTES,
        after.totalDownloadBytes,
        "the consent total must drop by exactly the staged bytes — quoting the full size charges the"
            + " user for bytes already on their disk (the round-8 defect)");
  }

  /**
   * The status wire carries the same disk-derived number, so a surface that only polls {@code
   * /api/ai/install/status} (the always-on poller the Brain surface reads) can render the paused
   * state without the preview. Pins the emitter: a field nothing populates is invisible to the FE.
   */
  @Test
  void installStatus_publishesTheStagedBytes_afterAPlanHasBeenComputed() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, aiHome);

    InstallPlan plan =
        InstallPlanner.plan(
            svc.getManifest(),
            svc.buildHardwareProfile(),
            svc.installIntent(),
            svc.modelsDir(),
            svc.aiHome());
    assumeFalse(plan.downloads().isEmpty(), "registry planned no downloads on this hardware");
    InstallPlan.PlannedDownload dl =
        plan.downloads().stream()
            .filter(d -> d.sizeBytes() > STAGED_BYTES)
            .findFirst()
            .orElseThrow();
    Path target = svc.modelsDir().resolve(dl.targetPath());
    Files.createDirectories(target.getParent());
    Files.write(InstallPlanner.partialPathFor(target), new byte[(int) STAGED_BYTES]);

    svc.previewInstallPlan(); // any plan derivation refreshes the disk-probed total

    assertEquals(
        STAGED_BYTES,
        svc.getStatus().resumableBytes,
        "the polled status must carry the staged bytes — otherwise the idle Brain surface still says"
            + " 'Not Installed' over a retained download");
  }
}
