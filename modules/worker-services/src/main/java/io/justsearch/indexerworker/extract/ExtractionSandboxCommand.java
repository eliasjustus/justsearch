/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the extraction child's argv in-process (tempdoc 885 item 14, design decision 1).
 *
 * <p>Tempdoc 410 shipped the {@code process} sandbox mode but required the operator to author the
 * command, which is why it was unreachable as shipped. The recipe is the one the retired
 * {@code ProcessExtractionSandboxTest} already proved: the running JVM's launcher plus its own
 * classpath. The Worker runs from a plain {@code -cp lib\*} classpath
 * ({@code WorkerSpawner.buildCommand}), not a jlink image, so the same pair works in production.
 *
 * <p>Both are read as <b>JVM self-introspection</b>, not as configuration: the launcher from
 * {@link ProcessHandle} and the classpath from {@link ManagementFactory}, so no configuration key
 * is invented for something the process already knows about itself. Only the operator-facing knobs
 * (heap, pool size, request budget, full argv override) go through {@code EnvRegistry}.
 */
public final class ExtractionSandboxCommand {

  private static final String CHILD_MAIN =
      "io.justsearch.indexerworker.extract.ExtractionSandboxChild";

  /** Floor for the child heap (design decision 1). */
  static final long MIN_HEAP_BYTES = 512L * 1024 * 1024;

  /** Multiple of the largest accepted input the child heap must cover. */
  static final int HEAP_INPUT_MULTIPLE = 4;

  private static final String AOT_CACHE_FLAG = "-XX:AOTCache=";

  private ExtractionSandboxCommand() {}

  /**
   * The default child command.
   *
   * @param policy the extraction policy; its {@code maxInputBytes} sizes the child heap
   * @param heapOverride an explicit heap string (e.g. {@code 768m}), or blank for the default
   */
  public static List<String> defaultCommand(TikaExtractionPolicy policy, String heapOverride) {
    List<String> argv = new ArrayList<>();
    argv.add(javaBinary());
    // Serial GC: the child is a single-request-at-a-time parser with a small heap, so parallel GC
    // threads are pure overhead against the Worker's own CPU budget.
    argv.add("-XX:+UseSerialGC");
    argv.add("-Xmx" + heapSpec(policy, heapOverride));
    argv.add("-Dfile.encoding=UTF-8");
    // Tika's PDFBox/POI paths make FFM downcalls; JDK 25 warns without this and a later JDK
    // refuses outright (same reason WorkerSpawner passes it to the Worker).
    argv.add("--enable-native-access=ALL-UNNAMED");
    String aot = inheritedAotCache();
    if (aot != null) {
      argv.add(AOT_CACHE_FLAG + aot);
    }
    argv.add("-cp");
    argv.add(ManagementFactory.getRuntimeMXBean().getClassPath());
    argv.add(CHILD_MAIN);
    return List.copyOf(argv);
  }

  /**
   * Child heap. {@code TikaExtractionPolicy.maxInputBytes} (default 100 MB, matching
   * {@code ContentExtractor.MAX_FILE_SIZE}) is the largest file the Worker will hand to a parser,
   * and POI needs 10-20x a document's size in heap — so the child gets at least 4x the largest
   * accepted input, with a 512m floor for the small-policy case.
   */
  static String heapSpec(TikaExtractionPolicy policy, String heapOverride) {
    if (heapOverride != null && !heapOverride.isBlank()) {
      return heapOverride.trim();
    }
    long effectivePolicyBytes =
        policy == null ? TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES : policy.maxInputBytes();
    long bytes = Math.max(MIN_HEAP_BYTES, effectivePolicyBytes * HEAP_INPUT_MULTIPLE);
    return (bytes / (1024 * 1024)) + "m";
  }

  /**
   * The running JVM's own launcher. Asked of the OS rather than reconstructed from
   * {@code os.name} + {@code java.home}: the child must be the same JVM the Worker runs, and a
   * bare {@code java} PATH lookup can resolve to an incompatible JDK (the trap tempdoc 696 fixed
   * for {@code WorkerSpawner}). The {@code java.home} branch is a fallback for a platform where
   * {@link ProcessHandle.Info#command()} is unavailable, and picks the launcher by file existence
   * rather than by parsing a platform string.
   */
  static String javaBinary() {
    Optional<String> command = ProcessHandle.current().info().command();
    if (command.isPresent() && !command.get().isBlank()) {
      return command.get();
    }
    Path bin = Path.of(System.getProperty("java.home"), "bin");
    Path windows = bin.resolve("java.exe");
    return Files.exists(windows) ? windows.toString() : bin.resolve("java").toString();
  }

  /**
   * The Worker's own AOT cache path, if it was launched with one and the file still exists.
   * Reusing it is free (identical classpath, so the cache is valid) and removes most of the
   * child's class-loading cost; a stale or absent path is skipped rather than passed on, because
   * an unusable cache is a warning on every spawn for no benefit.
   */
  static String inheritedAotCache() {
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (arg != null && arg.startsWith(AOT_CACHE_FLAG)) {
        String path = arg.substring(AOT_CACHE_FLAG.length());
        if (!path.isBlank() && Files.exists(Path.of(path))) {
          return path;
        }
        return null;
      }
    }
    return null;
  }
}
