package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FileOpsTest {

  private static final Logger LOG = LoggerFactory.getLogger(FileOpsTest.class);

  @Test
  void deleteRecursivelyBestEffort_deletesAllFiles(@TempDir Path tmp) throws IOException {
    Path dir = tmp.resolve("gen");
    Files.createDirectories(dir.resolve("sub"));
    Files.writeString(dir.resolve("a.txt"), "a");
    Files.writeString(dir.resolve("sub/b.txt"), "b");

    FileOps.DeleteResult result = FileOps.deleteRecursivelyBestEffort(dir, LOG);

    // 4 entries: dir, sub, a.txt, sub/b.txt
    assertTrue(result.deleted() >= 3, "Expected at least 3 deletions, got " + result.deleted());
    assertEquals(0, result.failed());
    assertFalse(Files.exists(dir));
  }

  @Test
  void deleteRecursivelyBestEffort_nullDir() throws IOException {
    FileOps.DeleteResult result = FileOps.deleteRecursivelyBestEffort(null, LOG);
    assertEquals(0, result.deleted());
    assertEquals(0, result.failed());
  }

  @Test
  void deleteRecursivelyBestEffort_nonexistentDir(@TempDir Path tmp) throws IOException {
    Path dir = tmp.resolve("does-not-exist");
    FileOps.DeleteResult result = FileOps.deleteRecursivelyBestEffort(dir, LOG);
    assertEquals(0, result.deleted());
    assertEquals(0, result.failed());
  }
}
