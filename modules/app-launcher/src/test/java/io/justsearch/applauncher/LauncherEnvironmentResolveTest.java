package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.util.RepoPaths;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

final class LauncherEnvironmentResolveTest {

  @Test
  void resolveProfilePathFindsProfileUnderRepoRoot() throws Exception {
    LauncherEnvironment environment = allocateEnvironment();
    Method resolve = LauncherEnvironment.class.getDeclaredMethod("resolveProfilePath", String.class);
    resolve.setAccessible(true);

    Path resolved = (Path) resolve.invoke(environment, "smoke");
    Path expected =
        RepoPaths.findRepoRoot()
            .resolve(Paths.get("config", "profiles", "smoke.yaml"))
            .toAbsolutePath()
            .normalize();
    assertEquals(expected, resolved);
  }

  @Test
  void resolveProfilePathThrowsWhenProfileMissing() throws Exception {
    LauncherEnvironment environment = allocateEnvironment();
    Method resolve = LauncherEnvironment.class.getDeclaredMethod("resolveProfilePath", String.class);
    resolve.setAccessible(true);

    InvocationTargetException ex =
        assertThrows(InvocationTargetException.class, () -> resolve.invoke(environment, "absent"));
    assertTrue(ex.getCause() instanceof IOException);
  }

  private LauncherEnvironment allocateEnvironment() {
    return Mockito.mock(LauncherEnvironment.class, Mockito.CALLS_REAL_METHODS);
  }
}
