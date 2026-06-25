package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelRegistry;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 562 — the on-disk installed-state recompute. {@code installedFully} is session-ephemeral (only
 * set true at the end of an install RUN, never rehydrated), so after a process restart a returning user with
 * models already on disk read a false "Not Installed" + a ~10 GB re-download prompt. {@code getStatus()} now
 * rehydrates it once from on-disk model presence (the planner's own already-installed detection).
 *
 * <p>The POSITIVE path (models present on disk → {@code installedFully} recomputed true → Brain shows the
 * honest "AI Offline / Start AI") is exercised LIVE against the dev backend, which carries the full model set
 * on disk — that is the verification tier for the disk-dependent behavior (a unit fixture would have to stage
 * the entire registry's files to make the planner emit zero downloads). These tests pin the deterministic
 * SAFETY guards: the recompute must never clobber a real install run's state. Reflection stages the private
 * status — no production test-seam, no class growth (mirrors {@link AiInstallServiceReaperTest}).
 */
final class AiInstallServiceDiskRecomputeTest {

  @TempDir Path tmp;

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  @Test
  void recompute_doesNotOverrideAnAlreadyCompletedInstallRun() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    status.state = "completed";
    status.installedFully = true;

    AiInstallStatus after = svc.getStatus(); // must NOT re-derive over a real completed run

    assertTrue(
        after.installedFully,
        "a completed install run's installedFully must survive getStatus (the recompute only fills the"
            + " post-restart idle gap, never overrides an in-session run)");
    assertEquals("completed", after.state, "the recompute must not rewrite a non-idle terminal state");
  }

  @Test
  void recompute_skipsWhileAnInstallIsRunning() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    status.state = "running";
    status.updatedAtEpochMs = System.currentTimeMillis(); // fresh — not reaped by the liveness backstop

    assertFalse(
        svc.getStatus().installedFully,
        "the on-disk recompute must not fire mid-install — only the post-restart idle case");
    assertEquals("running", svc.getStatus().state, "a live running install is left untouched");
  }

  // ── The positive path: the actual fix (models present on disk → installedFully recomputes true). Injects
  //    the plan rather than staging the registry's full file set, so it is deterministic. ──

  // A minimal registry — populateStatusPackages tolerates an unknown id (label falls back to the id), so the
  // decision logic is testable without the bundled `ai/model-registry.v2.json` resource (absent on the
  // app-services test classpath) and without staging real model files.
  private static final ModelRegistry MINIMAL_REGISTRY = new ModelRegistry(2, "test", List.of());

  @Test
  void applyInstalledFromPlan_flipsInstalledFully_whenNothingLeftToDownload() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    // Nothing left to download AND something already installed = fully on disk (the post-restart case).
    InstallPlan plan =
        new InstallPlan(DownloadProfile.values()[0], List.of(), List.of(), 0L, List.of("embedding"));

    boolean flipped = svc.applyInstalledFromPlan(plan, MINIMAL_REGISTRY);

    assertTrue(flipped, "a 'nothing left to download' plan must recompute installedFully=true (tempdoc 562)");
    AiInstallStatus after = statusOf(svc);
    assertTrue(
        after.installedFully,
        "a returning user with models on disk reads installed (→ 'AI Offline / Start AI'), not 'Not Installed'");
    assertFalse(after.packages.isEmpty(), "the already-installed packages are reflected in the status");
  }

  @Test
  void applyInstalledFromPlan_staysNotInstalled_whenDownloadsRemain() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    // A plan that still has a file to download = NOT fully installed. This is the property the loose
    // runtime-exe signal lacked: a fresh machine with the bundled CPU exe but no model must NOT read installed.
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(
                new InstallPlan.PlannedDownload(
                    "chat", "https://example/model.gguf", "chat/model.gguf", "sha", 100L, true)),
            List.of(),
            100L,
            List.of());

    boolean flipped = svc.applyInstalledFromPlan(plan, MINIMAL_REGISTRY);

    assertFalse(flipped, "a plan with remaining downloads must NOT claim installed");
    assertFalse(statusOf(svc).installedFully, "installedFully stays false — the honest 'Not Installed'");
  }
}
