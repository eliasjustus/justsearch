package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class LauncherOptionsTest {

  @Test
  void parseCommandOptionsRejectsUnknownFlag() throws Exception {
    Launcher launcher = new Launcher();
    Method method = Launcher.class.getDeclaredMethod("parseCommandOptions", String[].class);
    method.setAccessible(true);
    InvocationTargetException ex =
        assertThrows(
            InvocationTargetException.class,
            () -> method.invoke(launcher, (Object) new String[] {"--unexpected=value"}));
    assertTrue(ex.getCause().getMessage().contains("--unexpected=value"));
  }

  @Test
  void parseSmokeOptionsAcceptsSeparatedArguments() throws Exception {
    Launcher launcher = new Launcher();
    Method method = Launcher.class.getDeclaredMethod("parseSmokeOptions", String[].class);
    method.setAccessible(true);
    Object result =
        method.invoke(
            launcher, (Object) new String[] {"--profile", "nightly", "--diagnostics-run-id", "run42"});
    Method profile = result.getClass().getDeclaredMethod("profile");
    Method runId = result.getClass().getDeclaredMethod("diagnosticsRunId");
    assertEquals("nightly", profile.invoke(result));
    assertEquals("run42", runId.invoke(result));
  }

}
