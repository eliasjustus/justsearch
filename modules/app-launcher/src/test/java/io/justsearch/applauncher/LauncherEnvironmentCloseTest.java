package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.telemetry.LocalTelemetry;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

final class LauncherEnvironmentCloseTest {

  @TempDir Path tempDir;
  private String originalConfig;
  private String originalEgress;

  @BeforeEach
  void captureProperties() {
    originalConfig = System.getProperty("justsearch.config");
    originalEgress = System.getProperty("egress.block_all");
  }

  @AfterEach
  void restoreProperties() {
    restoreProperty("justsearch.config", originalConfig);
    restoreProperty("egress.block_all", originalEgress);
  }

  @Test
  void closeClearsSystemPropertiesWhenUnsetPreviously() throws Exception {
    System.setProperty("justsearch.config", "temp-config");
    System.setProperty("egress.block_all", "true");
    LocalTelemetry telemetry = new LocalTelemetry(tempDir, 1_000, "launcher-test", "close-null");
    LauncherEnvironment environment =
        allocateEnvironment(telemetry, null, null, tempDir.resolve("profile-null"));

    environment.close();

    assertEquals(null, System.getProperty("justsearch.config"));
    assertEquals(null, System.getProperty("egress.block_all"));
  }

  @Test
  void closeRestoresPreviousSystemProperties() throws Exception {
    System.setProperty("justsearch.config", "temp-config");
    System.setProperty("egress.block_all", "true");
    LocalTelemetry telemetry = new LocalTelemetry(tempDir, 1_000, "launcher-test", "close-restore");
    LauncherEnvironment environment =
        allocateEnvironment(telemetry, "previous-config", "false", tempDir.resolve("profile-restore"));

    environment.close();

    assertEquals("previous-config", System.getProperty("justsearch.config"));
    assertEquals("false", System.getProperty("egress.block_all"));
  }

  @Test
  void accessorsReturnAssignedValues() throws Exception {
    LocalTelemetry telemetry = new LocalTelemetry(tempDir, 1_000, "launcher-test", "accessors");
    Path profile = tempDir.resolve("profile-accessors");
    LauncherEnvironment environment =
        allocateEnvironment(telemetry, null, null, profile);
    setField(environment, "HeadAssembly", null);
    setField(environment, "configManager", null);

    assertEquals(profile, environment.profilePath());
    assertEquals(null, environment.configManager());
    assertEquals(telemetry, environment.telemetry());
    assertEquals(null, environment.HeadAssembly());

    telemetry.close();
  }

  private LauncherEnvironment allocateEnvironment(
      LocalTelemetry telemetry, String previousConfig, String previousEgress, Path profile)
      throws Exception {
    Files.createDirectories(profile);
    LauncherEnvironment environment =
        Mockito.mock(LauncherEnvironment.class, Mockito.CALLS_REAL_METHODS);
    setField(environment, "profilePath", profile);
    setField(environment, "previousConfigProperty", previousConfig);
    setField(environment, "previousEgressProperty", previousEgress);
    setField(environment, "configManager", null);
    setField(environment, "telemetry", telemetry);
    setField(environment, "HeadAssembly", null);
    return environment;
  }

  private void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
