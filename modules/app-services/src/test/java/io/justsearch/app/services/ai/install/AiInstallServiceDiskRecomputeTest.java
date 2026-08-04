package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ModelRegistry;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

  // ── Tempdoc 804 §B8 (round-10 F2): completeness is a claim about the CONTRACT that installed this
  //    machine, not about the current registry. A newer app version that REGISTERS a package must not
  //    retroactively un-install a complete installation. ──

  /**
   * A contract covering the given packages, each claiming ONE file named {@code <id>.bin}.
   *
   * <p>Tempdoc 805 G.3 re-pin: completeness is now measured per FILE, not per package, so this
   * fixture names a distinct file per package and the tests below plan downloads whose target path
   * ends in that same filename when they mean "the contracted file is gone". Pre-805 the fixture
   * claimed {@code model.bin} for every package while the real-gap test planned {@code
   * chat/model.gguf} — under package-level matching that was still a "contracted gap", under
   * file-level matching it would silently have become a registry addition. The assertions'
   * INTENT is unchanged; only the fixture is made consistent with the finer granularity.
   */
  private static InstallContract contractCovering(String... packageIds) {
    var models = new java.util.LinkedHashMap<String, InstallContract.InstalledModel>();
    for (String id : packageIds) {
      String file = id + ".bin";
      models.put(
          id,
          new InstallContract.InstalledModel(
              id, file, null, null, id, "sha", List.of(file), false, null));
    }
    return new InstallContract(2, 1L, null, DownloadProfile.values()[0], Map.copyOf(models));
  }

  @Test
  void applyInstalledFromPlan_staysInstalled_whenOnlyANewlyRegisteredArtifactIsMissing()
      throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    // The exact round-10 shape: a completed install (contract covers embedding + chat), and the NEW
    // version's registry adds one cuda-runtime package the contract never covered.
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(
                new InstallPlan.PlannedDownload(
                    "cuda-runtime", "https://example/cuda12.zip", "runtime/cuda12.zip", "sha", 167L, false)),
            List.of(),
            167L,
            List.of("embedding", "chat"));

    boolean flipped = svc.applyInstalledFromPlan(plan, MINIMAL_REGISTRY, contractCovering("embedding", "chat"));

    assertTrue(flipped, "a registry ADDITION must not un-install a contract-satisfied installation");
    AiInstallStatus after = statusOf(svc);
    assertTrue(after.installedFully, "installedFully is measured against the contract that installed it");
    assertEquals(
        List.of("cuda-runtime"),
        after.pendingRegistryAdditions,
        "the newly-registered artifact surfaces as its own state, not as retroactive non-installation");
    assertFalse(after.packages.isEmpty(), "packages[] is populated — the round saw it empty");
  }

  @Test
  void applyInstalledFromPlan_staysNotInstalled_whenAContractedPackageIsMissing() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    // A genuinely incomplete install: the contract CLAIMS chat's file, but the planner still wants
    // to download it (the file is gone) — that is a real gap, not a registry addition.
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(
                new InstallPlan.PlannedDownload(
                    "chat", "https://example/chat.bin", "chat/chat.bin", "sha", 100L, true)),
            List.of(),
            100L,
            List.of("embedding"));

    boolean flipped = svc.applyInstalledFromPlan(plan, MINIMAL_REGISTRY, contractCovering("embedding", "chat"));

    assertFalse(flipped, "a file the CONTRACT covered and disk lacks is a genuine gap");
    AiInstallStatus after = statusOf(svc);
    assertFalse(after.installedFully, "installedFully stays false — the honest 'Not Installed'");
    assertTrue(
        after.pendingRegistryAdditions.isEmpty(),
        "an incomplete install reports no pending registry additions (it is not the additions case)");
    assertTrue(after.repairNeeded, "tempdoc 805 G.3 — a missing required file warrants repair");
  }

  // ── Tempdoc 805 G.3 (round-11 F3/F4): the consequence signal. `installedFully` answers "was the
  //    recorded install complete?"; `repairNeeded` answers "is a required file missing NOW?" —
  //    round 11 had the second true while the first was (correctly) also true, and nothing said so. ──

  @Test
  void applyInstalledFromPlan_setsRepairNeeded_whenARegistryAdditionIsMissing() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    InstallPlan plan =
        new InstallPlan(
            DownloadProfile.values()[0],
            List.of(
                new InstallPlan.PlannedDownload(
                    "cuda-runtime",
                    "https://example/ort-native-cuda12-v1.24.3.zip",
                    "runtime/ort-native-cuda12-v1.24.3.zip",
                    "sha",
                    167L,
                    false)),
            List.of(),
            167L,
            List.of("embedding", "chat"));

    boolean flipped = svc.applyInstalledFromPlan(plan, MINIMAL_REGISTRY, contractCovering("embedding", "chat"));

    assertTrue(flipped, "an addition does not un-install a contract-satisfied installation (804 §B8)");
    AiInstallStatus after = statusOf(svc);
    assertTrue(after.installedFully, "the recorded install is complete on its own terms");
    assertTrue(
        after.repairNeeded,
        "…and the missing file still has a consequence the UI must be able to route to Repair");
  }

  @Test
  void applyInstalledFromPlan_clearsRepairNeeded_whenNothingIsMissing() throws Exception {
    AiInstallService svc = new AiInstallService(null, null, null, null, tmp);
    AiInstallStatus status = statusOf(svc);
    status.repairNeeded = true; // e.g. a prior recompute round

    svc.applyInstalledFromPlan(
        new InstallPlan(DownloadProfile.values()[0], List.of(), List.of(), 0L, List.of("embedding")),
        MINIMAL_REGISTRY);

    assertFalse(statusOf(svc).repairNeeded, "a complete disk state must retract the repair advisory");
  }
}
