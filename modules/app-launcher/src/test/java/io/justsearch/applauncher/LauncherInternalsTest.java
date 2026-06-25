package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LauncherInternalsTest {

  private String previousDataDir;

  @TempDir Path tempDir;

  @BeforeEach
  void captureProperty() {
    previousDataDir = System.getProperty("app.data_dir");
  }

  @AfterEach
  void restoreProperty() throws Exception {
    if (previousDataDir == null) {
      System.clearProperty("app.data_dir");
    } else {
      System.setProperty("app.data_dir", previousDataDir);
    }
    Path defaultDir = Path.of("build", "applauncher-data");
    if (Files.exists(defaultDir)) {
      try (var stream = Files.walk(defaultDir)) {
        stream.sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                    // best effort cleanup
                  }
                });
      }
    }
  }

  @Test
  void ensureDataDirCreatesDefaultDirectory() throws Exception {
    System.clearProperty("app.data_dir");
    Launcher launcher = new Launcher();
    Method method = Launcher.class.getDeclaredMethod("ensureDataDir");
    method.setAccessible(true);
    method.invoke(launcher);
    String configured = System.getProperty("app.data_dir");
    Path configuredPath = Path.of(configured);
    assertTrue(configuredPath.endsWith(Path.of("build", "applauncher-data")));
    assertTrue(Files.isDirectory(configuredPath));
  }

  @Test
  void ensureDataDirHonoursExistingProperty() throws Exception {
    Path custom = tempDir.resolve("custom-data");
    System.setProperty("app.data_dir", custom.toString());
    Launcher launcher = new Launcher();
    Method method = Launcher.class.getDeclaredMethod("ensureDataDir");
    method.setAccessible(true);
    method.invoke(launcher);
    assertEquals(custom.toString(), System.getProperty("app.data_dir"));
    assertFalse(Files.exists(custom));
  }

  @Test
  void emitCommandResultPrintsMarkersAndFailures() throws Exception {
    Launcher launcher = new Launcher();
    Method method =
        Launcher.class.getDeclaredMethod("emitCommandResult", LauncherCommands.CommandResult.class);
    method.setAccessible(true);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      method.invoke(
          launcher,
          LauncherCommands.CommandResult.success(List.of("OK/MARKER")));
      method.invoke(
          launcher,
          LauncherCommands.CommandResult.failure(
              List.of("FAIL/MARKER"),
              new IllegalStateException("boom")));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("OK/MARKER"));
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("FAIL/MARKER"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("IllegalStateException"));
  }
}
