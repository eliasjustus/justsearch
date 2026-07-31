/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWritesTest {
  @TempDir Path tempDir;

  @Test
  void replacesExistingBytesAndCleansTemporaryFile() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");

    AtomicFileWrites.replaceUtf8(target, "new");

    assertEquals("new", Files.readString(target));
    try (var files = Files.list(tempDir)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void createsParentDirectories() throws Exception {
    Path target = tempDir.resolve("nested").resolve("state.bin");
    AtomicFileWrites.replace(target, new byte[] {1, 2, 3});
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target));
  }

  @Test
  void fallsBackWhenAtomicMoveIsUnsupported() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.atomicUnsupported = true;

    AtomicFileWrites.replace(target, "new".getBytes(), files);

    assertEquals("new", Files.readString(target));
    assertEquals(1, files.fallbackMoves);
  }

  @Test
  void writeFailurePreservesOriginalAndDeletesTemp() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.failWrite = true;

    assertThrows(
        IOException.class, () -> AtomicFileWrites.replace(target, "new".getBytes(), files));

    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  @Test
  void moveFailurePreservesOriginalAndDeletesTemp() throws Exception {
    Path target = tempDir.resolve("state.json");
    Files.writeString(target, "old");
    RecordingFileAccess files = new RecordingFileAccess();
    files.failAtomicMove = true;

    assertThrows(
        IOException.class, () -> AtomicFileWrites.replace(target, "new".getBytes(), files));

    assertEquals("old", Files.readString(target));
    assertFalse(Files.exists(files.createdTemp));
  }

  private static final class RecordingFileAccess implements AtomicFileWrites.FileAccess {
    private Path createdTemp;
    private boolean failWrite;
    private boolean failAtomicMove;
    private boolean atomicUnsupported;
    private int fallbackMoves;

    @Override
    public void createDirectories(Path directory) throws IOException {
      Files.createDirectories(directory);
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
      createdTemp = Files.createTempFile(directory, prefix, suffix);
      return createdTemp;
    }

    @Override
    public void write(Path path, byte[] content) throws IOException {
      if (failWrite) throw new IOException("injected write failure");
      Files.write(path, content);
    }

    @Override
    public void moveAtomicReplace(Path source, Path target) throws IOException {
      if (atomicUnsupported) {
        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
      }
      if (failAtomicMove) throw new IOException("injected move failure");
      Files.move(
          source,
          target,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void moveReplace(Path source, Path target) throws IOException {
      fallbackMoves += 1;
      Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
      Files.deleteIfExists(path);
    }
  }
}
