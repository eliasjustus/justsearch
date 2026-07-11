package io.justsearch.ort.testing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-fixture helper that walks up from {@code user.dir} looking for an asset-gated model
 * directory, so every SPLADE/NER/embedding integration test resolves models the same way.
 *
 * <p>Tempdoc 710 Move 6 / {@code obs:spladebatchsweeptest}: several test families independently
 * re-implemented this walk with a 5-level (or 6-level) bound copied from an older sibling, while
 * a newer sibling (the tempdoc 686 bounded-tokenize tests) had already fixed the bound to 8
 * levels — the depth a worktree checkout actually needs (a Gradle test's {@code user.dir} for
 * {@code .claude/worktrees/<name>/modules/<module>} sits ~7 levels below the shared {@code
 * models/} directory in the MAIN checkout). The 5-level walkers silently skipped every
 * asset-gated test in every worktree session. This class is the ONE walker every caller now
 * uses, so a future depth regression can't creep back in per-test-family.
 *
 * <p>Lives in {@code ort-common} testFixtures (not {@code worker-core}) for the same reason as
 * {@link InferenceCompositionRootTestHelper} — reachable from worker-core, worker-services, and
 * indexer-worker test sources without a circular module dependency.
 */
public final class ModelDirTestResolver {

  /**
   * Parent-directory levels to walk from {@code user.dir} while probing for a model directory.
   * Empirically the deepest known caller ({@code .claude/worktrees/<name>/modules/<module>})
   * needs 7; 8 leaves one level of headroom.
   */
  public static final int MAX_WALK_DEPTH = 8;

  private ModelDirTestResolver() {}

  /** Outcome of a {@link #discover} attempt: exactly one of the two fields is non-null. */
  public record Discovery(Path modelDir, String missDescription) {}

  /**
   * Walks up to {@link #MAX_WALK_DEPTH} parent directories from {@code user.dir} looking for
   * {@code relativeModelDir} containing every file in {@code requiredFiles}. Falls back to
   * {@code envVarOverride} (if non-null) when the walk doesn't find it.
   *
   * @param relativeModelDir repo-relative model directory, e.g. {@code
   *     "models/splade/naver-splade-v3"}
   * @param envVarOverride environment variable naming an explicit model directory override, or
   *     {@code null} if this caller has no env-var fallback
   * @param requiredFiles files that must exist directly under the resolved directory, e.g. {@code
   *     "model.onnx"}, {@code "tokenizer.json"}
   * @return a {@link Discovery} whose {@code modelDir()} is non-null on success, or whose {@code
   *     missDescription()} names every attempted path — suitable for a loud {@code assumeTrue}
   *     skip message
   */
  public static Discovery discover(
      String relativeModelDir, String envVarOverride, String... requiredFiles) {
    Path start = Path.of(System.getProperty("user.dir"));
    Path candidate = start;
    for (int i = 0; i < MAX_WALK_DEPTH && candidate != null; i++) {
      Path dir = candidate.resolve(relativeModelDir);
      if (allPresent(dir, requiredFiles)) {
        return new Discovery(dir, null);
      }
      candidate = candidate.getParent();
    }

    if (envVarOverride != null) {
      String envPath = System.getenv(envVarOverride);
      if (envPath != null && !envPath.isBlank()) {
        Path envDir = Path.of(envPath);
        if (allPresent(envDir, requiredFiles)) {
          return new Discovery(envDir, null);
        }
      }
    }

    String miss =
        relativeModelDir
            + " (requires "
            + String.join(", ", requiredFiles)
            + ") not found walking "
            + MAX_WALK_DEPTH
            + " parent director"
            + (MAX_WALK_DEPTH == 1 ? "y" : "ies")
            + " up from "
            + start
            + (envVarOverride != null
                ? "; " + envVarOverride + " env override also unset or invalid"
                : "");
    return new Discovery(null, miss);
  }

  /**
   * Walks up to {@link #MAX_WALK_DEPTH} parent directories from {@code user.dir} looking for a
   * directory containing {@code relativeMarkerPath} (e.g. {@code "models"}), for callers that
   * resolve the repo root once and then derive several model subpaths from it.
   *
   * @return the resolved repo root, or {@code null} if the marker wasn't found within {@link
   *     #MAX_WALK_DEPTH} levels
   */
  public static Path findRepoRootByMarker(String relativeMarkerPath) {
    Path candidate = Path.of(System.getProperty("user.dir"));
    for (int i = 0; i < MAX_WALK_DEPTH && candidate != null; i++) {
      if (Files.exists(candidate.resolve(relativeMarkerPath))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    return null;
  }

  private static boolean allPresent(Path dir, String... files) {
    for (String f : files) {
      if (!Files.exists(dir.resolve(f))) {
        return false;
      }
    }
    return true;
  }
}
