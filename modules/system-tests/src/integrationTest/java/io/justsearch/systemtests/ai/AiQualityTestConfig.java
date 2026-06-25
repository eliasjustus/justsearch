package io.justsearch.systemtests.ai;

import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration factory for AI Quality tests.
 *
 * <p>Provides deterministic configuration for repeatable AI testing:
 * <ul>
 *   <li>{@code n_threads=1} - Critical for floating-point determinism</li>
 *   <li>{@code temperature=0.0} - Greedy decoding</li>
 *   <li>{@code seed=42} - Fixed random seed</li>
 * </ul>
 *
 * <p><b>Warning:</b> Do NOT increase threads to "speed up" tests.
 * Multi-threaded floating-point math is non-associative and will cause flaky tests.
 */
public final class AiQualityTestConfig {
  private static final Logger log = LoggerFactory.getLogger(AiQualityTestConfig.class);

  /** Fixed seed for deterministic generation. */
  public static final int DETERMINISTIC_SEED = 42;

  /** Single thread for bit-exact reproducibility. */
  public static final int DETERMINISTIC_THREADS = 1;

  /** Greedy decoding (no randomness). */
  public static final float DETERMINISTIC_TEMPERATURE = 0.0f;

  private AiQualityTestConfig() {}

  /**
   * Finds the TinyLlama model in the project's models directory.
   *
   * @return Path to the TinyLlama GGUF file
   * @throws IllegalStateException if model not found
   */
  public static Path findTinyLlamaModel() {
    return findModel("tinyllama");
  }

  /**
   * Finds the Nomic embedding model in the project's models directory.
   *
   * @return Path to the Nomic GGUF file
   * @throws IllegalStateException if model not found
   */
  public static Path findNomicEmbedModel() {
    return findModel("nomic-embed");
  }

  /**
   * Finds the Qwen model in the project's models directory.
   *
   * @return Path to the Qwen GGUF file
   * @throws IllegalStateException if model not found
   */
  public static Path findQwenModel() {
    return findModel("Qwen3-4B");
  }

  private static Path findModel(String prefix) {
    Path modelsDir = findModelsDirectory();
    if (modelsDir == null) {
      throw new IllegalStateException("Models directory not found. Expected: JustSearch/models/");
    }

    try (var files = Files.list(modelsDir)) {
      return files
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(prefix.toLowerCase(Locale.ROOT)))
          .filter(p -> p.toString().endsWith(".gguf"))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              "Model matching '" + prefix + "' not found in " + modelsDir));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to search models directory", e);
    }
  }

  private static Path findModelsDirectory() {
    // Try relative to current working directory
    Path cwd = Path.of(System.getProperty("user.dir"));
    Path modelsDir = cwd.resolve("models");
    if (Files.isDirectory(modelsDir)) {
      return modelsDir;
    }

    // Try finding project root
    Path current = cwd;
    while (current != null) {
      Path candidate = current.resolve("models");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }

    return null;
  }

  /**
   * Creates a deterministic configuration for generation tests.
   *
   * @param modelPath Path to the GGUF model file
   * @return Configured builder with deterministic settings
   */
  public static LocalIntentTranslatorConfig.Builder deterministicConfig(Path modelPath) {
    return LocalIntentTranslatorConfig.newBuilder()
        .backend("llama")
        .modelPath(modelPath)
        .maxParallelInferences(1)
        .maxSessions(1)
        .threads(DETERMINISTIC_THREADS)
        .contextLength(2048) // Increased from 512 to prevent KV cache exhaustion
        .maxNewTokens(64)
        .deadlineMs(30_000)
        .gpuLayers(0); // CPU only for CI compatibility
  }

  /**
   * Checks if native llama library is available.
   *
   * @return always false — native FFM backend has been removed
   */
  public static boolean isNativeAvailable() {
    return false;
  }
}
