/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.List;

/**
 * A computed download plan — what to download given the registry, hardware, and current state.
 *
 * <p>The plan is inspectable (can be shown to the user before downloading) and re-computable (run
 * the planner again after hardware changes to get a delta plan).
 *
 * @param profile the download profile selected for this hardware
 * @param downloads files to download (model variants + supporting files)
 * @param skipped model packages skipped due to hardware constraints
 * @param totalBytes total download size in bytes
 * @param alreadyInstalled model packages that are already correctly installed
 */
public record InstallPlan(
    DownloadProfile profile,
    List<PlannedDownload> downloads,
    List<SkippedPackage> skipped,
    long totalBytes,
    List<String> alreadyInstalled) {

  public InstallPlan {
    if (downloads == null) downloads = List.of();
    if (skipped == null) skipped = List.of();
    if (alreadyInstalled == null) alreadyInstalled = List.of();
  }

  /**
   * A single file to download.
   *
   * @param packageId which model package this belongs to
   * @param url download URL
   * @param targetPath relative path under modelsDir (e.g., "onnx/gte-multilingual-base/model.onnx")
   * @param sha256 expected SHA-256 hash
   * @param sizeBytes expected file size
   * @param isModelVariant true if this is the ONNX model file (vs supporting file)
   * @param extract when true, after download + SHA verification the file is unzipped into its
   *     parent directory. Used for the alpha.15 CUDA runtime package — bundled DLLs are too
   *     large for the NSIS installer, so they ship as a downloaded + extracted archive instead.
   *     The archive is kept on disk so the planner's {@code isAlreadyInstalled} check skips
   *     re-download on subsequent installs.
   */
  public record PlannedDownload(
      String packageId,
      String url,
      String targetPath,
      String sha256,
      long sizeBytes,
      boolean isModelVariant,
      boolean extract) {

    /** Backwards-compat constructor — non-extracted file (existing behavior). */
    public PlannedDownload(
        String packageId,
        String url,
        String targetPath,
        String sha256,
        long sizeBytes,
        boolean isModelVariant) {
      this(packageId, url, targetPath, sha256, sizeBytes, isModelVariant, false);
    }
  }

  /**
   * A model package that was skipped.
   *
   * @param packageId which model package
   * @param reason why it was skipped (e.g., "Insufficient VRAM for GGUF (6 GB < 7.5 GB)")
   */
  public record SkippedPackage(String packageId, String reason) {}
}
