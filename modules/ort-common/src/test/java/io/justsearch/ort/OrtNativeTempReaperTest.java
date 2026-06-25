/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("OrtNativeTempReaper: stale ORT temp-dir reaping")
final class OrtNativeTempReaperTest {

  private static Path makeOrtDir(Path tmp, String suffix, Instant mtime) throws Exception {
    Path dir = Files.createDirectory(tmp.resolve("onnxruntime-java" + suffix));
    Path lib = Files.writeString(dir.resolve("onnxruntime.dll"), "stub");
    Files.writeString(dir.resolve("onnxruntime_providers_cuda.dll"), "stub2");
    Files.setLastModifiedTime(lib, FileTime.from(mtime));
    Files.setLastModifiedTime(dir, FileTime.from(mtime));
    return dir;
  }

  @Test
  @DisplayName("an old, unlocked onnxruntime-java dir is reaped")
  void reapsOldUnlockedDir(@TempDir Path tmp) throws Exception {
    Path old = makeOrtDir(tmp, "111", Instant.now().minus(Duration.ofMinutes(30)));

    int reaped = OrtNativeTempReaper.reap(tmp);

    assertEquals(1, reaped, "the stale dir should be reaped");
    assertFalse(Files.exists(old), "the stale dir should be deleted");
  }

  @Test
  @DisplayName("a freshly-modified dir is skipped (possible live sibling mid-extraction)")
  void skipsFreshDir(@TempDir Path tmp) throws Exception {
    Path fresh = makeOrtDir(tmp, "222", Instant.now());

    int reaped = OrtNativeTempReaper.reap(tmp);

    assertEquals(0, reaped, "a fresh dir must not be reaped");
    assertTrue(Files.exists(fresh), "a fresh dir must be preserved");
  }

  @Test
  @DisplayName("non-matching directories are never touched")
  void ignoresUnrelatedDirs(@TempDir Path tmp) throws Exception {
    Path unrelated =
        Files.createDirectory(tmp.resolve("some-other-temp"));
    Files.writeString(unrelated.resolve("data.bin"), "keep me");
    Files.setLastModifiedTime(
        unrelated, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

    int reaped = OrtNativeTempReaper.reap(tmp);

    assertEquals(0, reaped);
    assertTrue(Files.exists(unrelated.resolve("data.bin")), "unrelated temp dir must be untouched");
  }
}
