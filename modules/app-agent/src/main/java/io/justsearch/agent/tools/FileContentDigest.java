/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Content identity for a path the agent copied, so an undo can prove it is about to delete the
 * agent's own copy and nothing else (tempdoc 909 items 7/8).
 *
 * <p>Undoing a COPY is a DELETE of a file that is, by then, an ordinary file in the user's folders.
 * The guard that shipped before this compared modification TIME against the recorded action time
 * ({@code FileOperationsTool.modifiedSince}), which misses every same-mtime replacement: an editor
 * that restores timestamps, a restore-from-backup over the copy, a sync client writing the server's
 * mtime, or simply a filesystem whose timestamp granularity swallowed the edit. Time is a proxy for
 * "did the content change"; this answers the question directly.
 *
 * <p>Fails to {@code null} rather than to a wrong answer: an unreadable path yields no digest, and
 * every caller treats "no digest" as "cannot prove identity" — which, on the delete path, means
 * preserve.
 */
final class FileContentDigest {

  private static final String FILE_PREFIX = "sha256:";
  private static final String TREE_PREFIX = "tree-sha256:";

  private FileContentDigest() {}

  /**
   * The content identity of {@code path}: a SHA-256 of the bytes for a regular file, or a SHA-256
   * over every contained file's relative path, size and content hash for a directory. Returns
   * {@code null} when the path does not exist or cannot be read in full.
   */
  static String of(Path path) {
    try {
      if (Files.isDirectory(path)) {
        return TREE_PREFIX + hex(treeDigest(path));
      }
      if (Files.isRegularFile(path)) {
        return FILE_PREFIX + hex(fileDigest(path));
      }
      return null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Whether {@code path} still holds exactly the content {@code recorded} names. A blank
   * {@code recorded} (a journal written before digests existed) or an unreadable path is NOT a
   * match — the caller must be able to distinguish "proved identical" from "could not tell".
   */
  static boolean matches(Path path, String recorded) {
    if (recorded == null || recorded.isBlank()) {
      return false;
    }
    String current = of(path);
    return current != null && current.equals(recorded);
  }

  private static byte[] fileDigest(Path file) throws IOException {
    MessageDigest digest = sha256();
    try (InputStream in = Files.newInputStream(file);
        DigestInputStream digesting = new DigestInputStream(in, digest)) {
      byte[] buffer = new byte[8192];
      while (digesting.read(buffer) >= 0) {
        // consuming the stream is the point; DigestInputStream updates the digest
      }
    }
    return digest.digest();
  }

  /**
   * A tree digest that is stable across walk order and platform: entries are sorted by their
   * forward-slash relative path, and each contributes path, size and content hash. Only regular
   * files contribute — an empty directory is structure, not content, and the undo it guards deletes
   * the whole tree either way.
   */
  private static byte[] treeDigest(Path root) throws IOException {
    var entries = new TreeMap<String, String>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String relative = root.relativize(file).toString().replace('\\', '/');
            entries.put(relative, attrs.size() + ":" + hex(fileDigest(file)));
            return FileVisitResult.CONTINUE;
          }
        });
    MessageDigest digest = sha256();
    for (var entry : entries.entrySet()) {
      digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    return digest.digest();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
