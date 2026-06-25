package io.justsearch.app.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class WindowsJobObjectTest {

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void createOrNull_returnsNullOnNonWindows() {
    assertNull(WindowsJobObject.createOrNull());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void createAndClose_succeeds() {
    WindowsJobObject job = WindowsJobObject.createOrNull();
    assertNotNull(job, "Job Object should be created on Windows");
    assertDoesNotThrow(job::close);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void assign_killsChildOnClose() throws Exception {
    // Use assignOrThrow to get diagnostics on failure
    WindowsJobObject job = WindowsJobObject.createOrNull();
    assertNotNull(job, "Job Object should be created on Windows");
    try {
      // Spawn a long-running child process (ping is a standalone executable)
      Process child =
          new ProcessBuilder("ping", "-n", "300", "127.0.0.1")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();

      // Small delay to let the process fully start
      Thread.sleep(500);
      assert child.isAlive() : "Child should be alive after spawn";

      job.assign(child.pid());

      // Close job — OS should kill the child
      job.close();
      job = null;

      // Wait briefly for the OS to propagate the kill
      boolean exited = child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      if (!exited) {
        child.destroyForcibly();
        child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.fail("Child should have been killed by Job Object close");
      }
    } finally {
      if (job != null) {
        job.close();
      }
    }
  }
}
