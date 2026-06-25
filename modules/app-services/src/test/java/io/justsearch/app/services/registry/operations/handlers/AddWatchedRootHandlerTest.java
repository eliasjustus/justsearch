package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 450 §2.3 — Operation handler must reject paths that don't resolve
 * to an existing directory, mirroring the REST handler's
 * {@code Files.isDirectory} check.
 */
@DisplayName("AddWatchedRootHandler")
final class AddWatchedRootHandlerTest {

  private static class CountingIndexingService implements IndexingService {
    int addCalled = 0;

    @Override
    public List<Path> getWatchedPaths() {
      return List.of();
    }

    @Override
    public void addWatchedPath(Path path) {}

    @Override
    public int removeWatchedPath(Path path) {
      return 0;
    }

    @Override
    public void flush() {}

    @Override
    public void addWatchedRoot(String collection, Path path) {
      addCalled++;
    }
  }

  @Test
  @DisplayName("non-existent path fails with INVALID_PATH-shaped message; service NOT called (§2.3)")
  void rejectsNonExistentPath() {
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    OperationResult r =
        handler.execute("{\"path\":\"C:\\\\NonExistentTestPath_xyz_slice450\"}");
    assertFalse(r.success(), "must NOT succeed for missing dir");
    assertTrue(
        r.message().toLowerCase().contains("does not exist")
            || r.message().toLowerCase().contains("not a directory"),
        "Message must indicate the path-not-directory failure: " + r.message());
    assertTrue(
        fake.addCalled == 0,
        "addWatchedRoot must NOT be called for invalid paths");
  }

  @Test
  @DisplayName("existing directory passes validation and reaches the service")
  void acceptsExistingDirectory(@TempDir Path tmp) throws Exception {
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    String quoted = tmp.toAbsolutePath().toString().replace("\\", "\\\\");
    OperationResult r = handler.execute("{\"path\":\"" + quoted + "\"}");
    assertTrue(r.success(), "Expected success for existing temp dir, got: " + r.message());
    assertTrue(fake.addCalled == 1, "service must be called once");
  }

  @Test
  @DisplayName("regular file (not a directory) is rejected")
  void rejectsRegularFile(@TempDir Path tmp) throws Exception {
    Path file = Files.createFile(tmp.resolve("not-a-dir.txt"));
    var fake = new CountingIndexingService();
    var handler = new AddWatchedRootHandler(() -> fake);
    String quoted = file.toAbsolutePath().toString().replace("\\", "\\\\");
    OperationResult r = handler.execute("{\"path\":\"" + quoted + "\"}");
    assertFalse(r.success());
    assertTrue(fake.addCalled == 0);
  }
}
