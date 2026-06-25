/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Bill of materials written by the install pipeline after successful completion.
 *
 * <p>Records exactly what was installed, which hardware profile was detected, and which variant was
 * selected for each model. The runtime reads this on startup instead of scanning directories —
 * discovery becomes verification (does the declared state match disk?) rather than search.
 *
 * <p>Persisted as {@code install-contract.v2.json} in the AI Home directory.
 *
 * @param schemaVersion always 2
 * @param installedAtEpochMs when the install completed (epoch millis)
 * @param hardwareProfile hardware snapshot at install time
 * @param downloadProfile which download profile was selected
 * @param models per-model install state (keyed by model package ID)
 * @param modelsDir absolute path to the models directory at install time (alpha.20 Bug M).
 *     Nullable on contracts written before alpha.20 — null falls through to runtime resolution
 *     (env var → ConfigStore → aiHome/models). Recorded so the modelsDir context survives
 *     across cold restarts even when {@code JUSTSEARCH_MODELS_DIR} env var doesn't inherit
 *     across GUI launches.
 */
public record InstallContract(
    int schemaVersion,
    long installedAtEpochMs,
    HardwareProfile hardwareProfile,
    DownloadProfile downloadProfile,
    Map<String, InstalledModel> models,
    Path modelsDir) {

  public static final String CONTRACT_FILENAME = "install-contract.v2.json";

  public InstallContract {
    if (models == null) models = Map.of();
  }

  /**
   * Backwards-compat constructor for callers (and Jackson) that pre-date the alpha.20
   * {@code modelsDir} field. Defaults the new field to null.
   */
  public InstallContract(
      int schemaVersion,
      long installedAtEpochMs,
      HardwareProfile hardwareProfile,
      DownloadProfile downloadProfile,
      Map<String, InstalledModel> models) {
    this(schemaVersion, installedAtEpochMs, hardwareProfile, downloadProfile, models, null);
  }

  /**
   * Per-model install state within the contract.
   *
   * @param packageId model package ID from the registry
   * @param variantFilename the model file that was downloaded (e.g., "model.onnx" or "model_fp16.onnx")
   * @param precision precision of the installed variant
   * @param targetEP execution provider the variant targets
   * @param targetDir directory relative to modelsDir where files were placed
   * @param sha256 SHA-256 hash of the installed model file
   * @param installedFiles all files installed for this model (model + supporting)
   * @param skipped true if this model was skipped (e.g., GGUF on CPU)
   * @param skipReason human-readable reason if skipped (nullable)
   */
  public record InstalledModel(
      String packageId,
      String variantFilename,
      ModelPrecision precision,
      ExecutionProvider targetEP,
      String targetDir,
      String sha256,
      List<String> installedFiles,
      boolean skipped,
      String skipReason) {

    public InstalledModel {
      if (installedFiles == null) installedFiles = List.of();
    }

    /** Creates a record for a skipped model. */
    public static InstalledModel skipped(String packageId, String reason) {
      return new InstalledModel(packageId, null, null, null, null, null, List.of(), true, reason);
    }
  }

  /** Returns the installed model for the given package ID, or null if not found. */
  public InstalledModel getModel(String packageId) {
    return models.get(packageId);
  }

  /**
   * Resolves the model file path for the given package ID.
   *
   * @param packageId model package ID
   * @param modelsDir root models directory
   * @return absolute path to the model file, or null if skipped/not found
   */
  public Path resolveModelPath(String packageId, Path modelsDir) {
    InstalledModel model = models.get(packageId);
    if (model == null || model.skipped() || model.variantFilename() == null) {
      return null;
    }
    return modelsDir.resolve(model.targetDir()).resolve(model.variantFilename());
  }
}
