/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 882 item 4 regression guard: the Worker argv must enable native access and must not
 * carry the inert {@code --add-opens=java.base/java.nio} or a {@code jdk.incubator.vector}
 * reference.
 *
 * <p>{@code buildCommand()} is private, so this reaches it via reflection - the same pattern
 * already used elsewhere in this test package (e.g. {@code KnowledgeServerBootRecoveryTest}'s
 * private-field access) - rather than widening the method's visibility for a test-only need.
 */
final class WorkerSpawnerJvmFlagsTest {

  /** Mirrors {@code KnowledgeServerBootRecoveryTest.configFor}. */
  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir, dir, dir.resolve("worker_signal.lock"),
        15_000L, 1_000L, 3, "256m", 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  @SuppressWarnings("unchecked")
  private static List<String> buildCommand(WorkerSpawner spawner) throws Exception {
    Method method = WorkerSpawner.class.getDeclaredMethod("buildCommand");
    method.setAccessible(true);
    return (List<String>) method.invoke(spawner);
  }

  @Test
  @DisplayName("Worker argv enables native access and carries no add-opens or incubator.vector")
  void argvEnablesNativeAccessWithNoAddOpensOrIncubatorVector(@TempDir Path tempDir) throws Exception {
    MainSignalBus signalBus = new MainSignalBus(tempDir.resolve("worker_signal.lock"));
    WorkerSpawner spawner = new WorkerSpawner(configFor(tempDir), signalBus);

    List<String> argv = buildCommand(spawner);

    assertTrue(
        argv.contains("--enable-native-access=ALL-UNNAMED"),
        "argv must enable native access for FFM downcalls (NVML, Windows job object, GPU driver"
            + " probe): " + argv);
    assertFalse(
        argv.stream().anyMatch(arg -> arg.startsWith("--add-opens")),
        "argv must not carry the inert MMapDirectory add-opens flag: " + argv);
    assertFalse(
        argv.stream().anyMatch(arg -> arg.contains("jdk.incubator.vector")),
        "argv must not reference jdk.incubator.vector (no runtime enables it): " + argv);
  }
}
