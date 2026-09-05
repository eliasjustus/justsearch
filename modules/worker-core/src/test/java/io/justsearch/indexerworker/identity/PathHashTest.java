package io.justsearch.indexerworker.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §P2 — the path key the identity store is keyed on (ADR-0028: hash-keyed reverse
 * lookup, no raw path persisted). The hash is the join between admission and the store, so its
 * shape is a contract: lowercase SHA-256 hex over the UTF-8 bytes of the already-normalized path,
 * and nothing else.
 */
final class PathHashTest {

  @Test
  void isTheLowercaseSha256HexOfTheUtf8Path() {
    // SHA-256("abc") is the FIPS 180-2 example vector; a fixed value pins the algorithm and the
    // encoding, so a swap to another digest or to UTF-16 bytes fails here rather than silently
    // re-keying every identity row on the next boot.
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        PathHash.sha256("abc"));
  }

  @Test
  void isDeterministicAndPathFree() {
    String hash = PathHash.sha256("C:/Users/someone/Documents/report.pdf");
    assertEquals(hash, PathHash.sha256("C:/Users/someone/Documents/report.pdf"));
    assertEquals(64, hash.length());
    assertTrue(hash.matches("[0-9a-f]{64}"), "lowercase hex only, got " + hash);
    assertTrue(
        !hash.contains("report") && !hash.contains("Documents"),
        "the key must not leak any path fragment");
  }

  @Test
  void distinguishesPathsThatDifferOnlyInCase() {
    // Normalization is the caller's job; the hash itself must not fold case, or two distinct
    // normalized paths would collapse onto one identity.
    assertNotEquals(PathHash.sha256("/data/A.txt"), PathHash.sha256("/data/a.txt"));
  }

  @Test
  void hashesNonAsciiPathsByTheirUtf8Bytes() {
    // Two different Unicode paths must hash differently, and the result must be stable across
    // JVM default charsets — the store is shared across boots and machines.
    String umlaut = PathHash.sha256("/docs/Übersicht.md");
    assertNotEquals(umlaut, PathHash.sha256("/docs/Ubersicht.md"));
    // The key stays plain hex whatever the path's script — never the raw bytes.
    assertTrue(umlaut.matches("[0-9a-f]{64}"));
  }

  @Test
  void refusesAMissingOrBlankPath() {
    assertThrows(IllegalArgumentException.class, () -> PathHash.sha256(null));
    assertThrows(IllegalArgumentException.class, () -> PathHash.sha256(""));
    assertThrows(IllegalArgumentException.class, () -> PathHash.sha256("   "));
  }

  @Test
  void theStoreFacadeDelegatesToTheSameHash() {
    // DocumentIdentityStore.pathHash is the name admission calls; it must be this hash and not a
    // second implementation that could drift.
    assertEquals(PathHash.sha256("/x/y.txt"), DocumentIdentityStore.pathHash("/x/y.txt"));
  }
}
