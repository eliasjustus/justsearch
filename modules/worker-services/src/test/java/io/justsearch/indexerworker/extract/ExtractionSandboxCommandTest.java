package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shipped child argv (tempdoc 885 item 14) — the piece tempdoc 410 left to the operator. */
final class ExtractionSandboxCommandTest {

  @Test
  void defaultCommandLaunchesTheChildOnThisJvmAndClasspath() {
    List<String> argv = ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "");

    assertTrue(
        argv.get(0).startsWith(System.getProperty("java.home")),
        "must reuse the running JVM, not a PATH lookup: " + argv.get(0));
    assertEquals(
        "io.justsearch.indexerworker.extract.ExtractionSandboxChild",
        argv.get(argv.size() - 1));
    assertEquals("-cp", argv.get(argv.size() - 3));
    assertEquals(System.getProperty("java.class.path"), argv.get(argv.size() - 2));
    assertTrue(argv.contains("--enable-native-access=ALL-UNNAMED"), argv.toString());
    assertTrue(argv.contains("-XX:+UseSerialGC"), argv.toString());
  }

  @Test
  void heapFlagIsPresentAndSizedFromThePolicysLargestAcceptedInput() {
    List<String> argv = ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "");

    // Default policy accepts 100 MB inputs; 4x that is 400 MB, below the 512m floor.
    assertTrue(argv.contains("-Xmx512m"), argv.toString());
    assertEquals("512m", ExtractionSandboxCommand.heapSpec(TikaExtractionPolicy.defaults(), null));

    TikaExtractionPolicy large =
        new TikaExtractionPolicy(
            "large-input",
            1024,
            400L * 1024 * 1024,
            1024,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            Set.of(),
            Set.of());
    assertEquals("1600m", ExtractionSandboxCommand.heapSpec(large, ""));
    assertEquals("768m", ExtractionSandboxCommand.heapSpec(large, "768m"));
  }

  @Test
  void aotCacheIsOnlyInheritedWhenTheWorkerHasOneAndTheFileExists() {
    String inherited = ExtractionSandboxCommand.inheritedAotCache();
    if (inherited == null) {
      // The Gradle test JVM is launched without -XX:AOTCache, which is the common case.
      assertFalse(
          ExtractionSandboxCommand.defaultCommand(TikaExtractionPolicy.defaults(), "").stream()
              .anyMatch(a -> a.startsWith("-XX:AOTCache=")),
          "no AOT flag may be invented when the parent JVM has none");
    } else {
      assertTrue(Files.exists(Path.of(inherited)), "an inherited AOT cache path must exist");
    }
  }
}
