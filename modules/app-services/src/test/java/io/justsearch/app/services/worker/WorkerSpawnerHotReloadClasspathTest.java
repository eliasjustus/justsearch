/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 844 §4.2 R4 — the Worker's classpath must come from ONE tree.
 *
 * <p>The running Worker is launched with {@code -cp <workerLibDir>/*} (the installDist jar set)
 * while hot reload pushes from {@code build/classes/java/main}. Only the classes already loaded get
 * redefined; every class loaded afterwards comes from the STALE jar — in a JVM whose build stamp
 * the reload tool has just updated to claim it is current. Prefixing the classpath with the same
 * classes dir the JDWP push uses closes that gap (it is what 305 Phase 2's child classloader was
 * for, reached by classpath ordering instead).
 *
 * <p>The gate matters as much as the ordering: with hot reload off — production, and every dev
 * start before this change — the classpath must be byte-identical to what it was.
 */
@DisplayName("WorkerSpawner hot-reload classpath ordering")
class WorkerSpawnerHotReloadClasspathTest {

  private static final Path REPO = Path.of("build", "hotreload-classpath-test", "repo");
  private static final Path LIB = REPO.resolve("modules/indexer-worker/build/install/indexer-worker/lib");

  @Test
  @DisplayName("hot reload OFF: the classpath is exactly the jar wildcard, unchanged")
  void hotReloadOff_classpathUnchanged() {
    String cp = WorkerSpawner.buildWorkerClasspath(LIB, null);

    assertEquals(LIB.toAbsolutePath() + File.separator + "*", cp);
  }

  @Test
  @DisplayName("hot reload ON: the classes dir comes FIRST, ahead of the jar wildcard")
  void hotReloadOn_classesDirPrecedesJars() {
    Path classes = WorkerSpawner.devHotReloadClassesDir(REPO);

    String cp = WorkerSpawner.buildWorkerClasspath(LIB, classes);

    String[] entries = cp.split(java.util.regex.Pattern.quote(File.pathSeparator));
    assertEquals(2, entries.length, "expected exactly <classesDir><sep><libDir>/*, got: " + cp);
    assertEquals(classes.toAbsolutePath().toString(), entries[0]);
    assertEquals(LIB.toAbsolutePath() + File.separator + "*", entries[1]);
    assertTrue(
        cp.indexOf(classes.toString()) < cp.indexOf(LIB.toString()),
        "the hot-reload classes dir must precede the jars, or redefined classes keep losing to the"
            + " stale jar: " + cp);
  }

  @Test
  @DisplayName("this side's half of the identity-token layout: modules/<m>/build/classes/java/main")
  void devHotReloadClassesDirLayoutIsPinnedOnThisSide() {
    // Tempdoc 844 M6: this test used to claim "both sides are pinned here". It was not — it
    // restated devHotReloadClassesDir's implementation and never referenced dev-runner.cjs, so a
    // change to the JS side would leave this green while every reload refused with
    // TARGET_IDENTITY_MISMATCH. Renamed to what it actually checks.
    //
    // The cross-side pin lives where both sides are readable from one process:
    // scripts/dev/test-dev-mcp-hot-reload.mjs asserts the dev-runner writes this layout into
    // run.json and that the reload tool parses the module back out of it
    // (reloadModuleFromClassesDir). What is checked HERE is only that WorkerSpawner produces the
    // documented shape, absolutely.
    Path repo = REPO;

    Path classes = WorkerSpawner.devHotReloadClassesDir(repo);

    assertEquals(
        repo.resolve("modules").resolve("worker-services").resolve("build").resolve("classes")
            .resolve("java").resolve("main").toAbsolutePath(),
        classes);
    assertTrue(classes.isAbsolute(), "the identity token must be absolute");
  }
}
