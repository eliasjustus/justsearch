/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 696 regression guard: the Worker must launch via the running (Head) JVM's own {@code
 * java.home}, NEVER a bare {@code java}/{@code java.exe} PATH lookup. A bare lookup resolves to
 * whatever JDK is first on PATH (can be an incompatible JDK 8 → {@code UnsupportedClassVersionError}
 * on the JDK-25 Worker). If a future edit reverts {@link WorkerSpawner#workerJavaBinary(boolean)} to a
 * bare name, this test fails.
 */
class WorkerSpawnerJavaBinaryTest {

  @Test
  void workerJavaBinary_usesRunningJvmHome_notBarePathLookup() {
    String javaHome = System.getProperty("java.home");
    for (boolean isWindows : new boolean[] {true, false}) {
      String bin = WorkerSpawner.workerJavaBinary(isWindows);

      assertTrue(
          bin.startsWith(javaHome),
          "worker java binary must live under the running JVM's java.home ("
              + javaHome
              + "), got: "
              + bin);

      String leaf = isWindows ? "java.exe" : "java";
      assertTrue(
          bin.endsWith(Path.of("bin", leaf).toString()),
          "worker java binary must end with bin/" + leaf + ", got: " + bin);

      assertNotEquals("java", bin, "must not be a bare `java` PATH lookup");
      assertNotEquals("java.exe", bin, "must not be a bare `java.exe` PATH lookup");
    }
  }
}
