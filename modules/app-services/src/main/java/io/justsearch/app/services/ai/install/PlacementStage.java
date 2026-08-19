/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 2 of an install run: getting verified bytes to their final home, and recording what landed.
 *
 * <p>Two entry points, at two different rhythms, because that is what the run actually does:
 *
 * <ul>
 *   <li>{@link #place} runs PER ITEM, from inside acquisition. A file is promoted the moment it
 *       verifies, not after the whole set — an install interrupted halfway leaves the files it
 *       finished at their real paths, which is what makes the planner's already-installed check
 *       skip them on the next run.
 *   <li>{@link #writeContract} runs ONCE, after the set. The bill of materials describes a run, not
 *       a file.
 * </ul>
 */
final class PlacementStage {

  private static final Logger log = LoggerFactory.getLogger(PlacementStage.class);

  private final Path modelsDir;

  PlacementStage(Path modelsDir) {
    this.modelsDir = modelsDir;
  }

  /**
   * Promotes one verified {@code .partial} to its target path and expands it when the plan says the
   * package ships an archive.
   *
   * @return the user-facing failure message, or {@code null} when the item is in place. Failures are
   *     returned rather than thrown because one file failing must not end the set — the caller marks
   *     the package failed and moves to the next item.
   */
  String place(InstallPlan.PlannedDownload dl) {
    Path targetFile = modelsDir.resolve(dl.targetPath());
    Path partialFile = InstallPlanner.partialPathFor(targetFile);
    try {
      DownloadExecutor.moveAtomicBestEffort(partialFile, targetFile);
    } catch (IOException e) {
      return "Failed to finalize: " + e.getMessage();
    }

    // Tempdoc 374 alpha.15 fix B: archive extraction. The cuda-runtime package ships its DLLs in a
    // single zip (too large for the NSIS installer payload). After download + SHA verification the
    // zip is expanded into the same directory; the archive itself stays on disk so the planner's
    // isAlreadyInstalled check skips re-download next time.
    if (dl.extract()) {
      try {
        AiInstallService.extractZipInPlace(targetFile, targetFile.getParent());
      } catch (IOException e) {
        return "Failed to extract " + targetFile.getFileName() + ": " + e.getMessage();
      }
    }
    return null;
  }

  /**
   * Writes the run's install contract.
   *
   * <p>Deliberately NOT best-effort: a failure here propagates. The contract is the runtime's only
   * bill of materials, and a run that reported completion without one leaves the next boot resolving
   * models by guesswork.
   */
  void writeContract(InstallContract contract, Path homeDir) {
    InstallContractIO.write(contract, homeDir);
    log.info("Install contract written to {}", homeDir.resolve(InstallContract.CONTRACT_FILENAME));
  }
}
