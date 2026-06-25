package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.util.RepoPaths;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LauncherTest {

  private final Launcher launcher = new Launcher();

  @Test
  void parseSmokeOptionsSupportsNamedArguments() {
    Launcher.SmokeOptions opts =
        launcher.parseSmokeOptions(
            new String[] {"--profile=demo", "--diagnostics-run-id=run-1234"});
    assertEquals("demo", opts.profile());
    assertEquals("run-1234", opts.diagnosticsRunId());
  }

  @Test
  void parseCommandOptionsDefaultsToSmoke() {
    Launcher.CommandOptions opts = launcher.parseCommandOptions(new String[0]);
    assertEquals("smoke", opts.profile());
  }

  @Test
  void parseSeedOptionsFallsBackToRepoFixtures() throws Exception {
    Path fixtures = RepoPaths.findRepoRoot().resolve("fixtures").resolve("demo").resolve("corpus.json");

    Launcher.SeedOptions opts = invokeParseSeedOptions(new String[] {"--profile=demo"});

    assertEquals("demo", opts.profile());
    assertEquals(fixtures, opts.fixtures());
  }

  @Test
  void stripFirstRemovesLeadingCommand() throws Exception {
    String[] args = new String[] {"seed", "--profile=demo"};
    Method stripFirst = Launcher.class.getDeclaredMethod("stripFirst", String[].class);
    stripFirst.setAccessible(true);
    String[] result = (String[]) stripFirst.invoke(launcher, (Object) args);
    assertArrayEquals(new String[] {"--profile=demo"}, result);
  }

  private Launcher.SeedOptions invokeParseSeedOptions(String[] args) throws Exception {
    Method parseSeedOptions =
        Launcher.class.getDeclaredMethod("parseSeedOptions", String[].class);
    parseSeedOptions.setAccessible(true);
    return (Launcher.SeedOptions) parseSeedOptions.invoke(launcher, (Object) args);
  }
}
