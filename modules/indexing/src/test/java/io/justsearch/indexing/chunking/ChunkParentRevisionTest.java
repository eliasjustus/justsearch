package io.justsearch.indexing.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §C.1 — the revision identity every chunk carries so a read-modify-write can refuse to
 * re-slice a parent that changed underneath it. Writer ({@code ChunkDocumentWriter}) and reader
 * ({@code WritePathOps.preserveChunkContent}) both go through {@link ChunkParentRevision}; these
 * cases pin the hash contract itself so a change to either side's input handling fails here, not
 * as a silent "revision mismatch" on every chunk of an existing index.
 */
final class ChunkParentRevisionTest {

  @Test
  void isTheLowercaseSha256HexOfTheUtf8Content() {
    // FIPS 180-2 example vector: pins both the digest and the UTF-8 encoding. A swap to another
    // digest, to UTF-16 bytes, or to uppercase hex would re-key every stored chunk revision.
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ChunkParentRevision.sha256Hex("abc"));
  }

  @Test
  void hashesTheExactStoredStringNotANormalisation() {
    // The reader compares against the parent's stored `content` verbatim, so the writer must not
    // trim, fold case or collapse whitespace — any of those would hash a string the reader never
    // sees and refuse every RMW.
    String base = ChunkParentRevision.sha256Hex("Alpha beta");
    assertNotEquals(base, ChunkParentRevision.sha256Hex("Alpha beta "));
    assertNotEquals(base, ChunkParentRevision.sha256Hex("alpha beta"));
    assertNotEquals(base, ChunkParentRevision.sha256Hex("Alpha  beta"));
    assertEquals(base, ChunkParentRevision.sha256Hex("Alpha beta"));
  }

  @Test
  void anEqualLengthRewriteHasADifferentRevision() {
    // The defect §C.1 closes: offsets alone cannot tell "same parent" from "rewritten parent of the
    // same length"; the revision must.
    String a = "The court held that the statute applied.";
    String b = "The court held that the statute expired.";
    assertEquals(a.length(), b.length());
    assertNotEquals(ChunkParentRevision.sha256Hex(a), ChunkParentRevision.sha256Hex(b));
  }

  @Test
  void nonAsciiContentHashesByUtf8Bytes() {
    String hash = ChunkParentRevision.sha256Hex("Übersicht – §3 ‑ 東京");
    assertTrue(hash.matches("[0-9a-f]{64}"), "lowercase hex only, got " + hash);
    assertNotEquals(hash, ChunkParentRevision.sha256Hex("Ubersicht - §3 - 東京"));
  }

  @Test
  void emptyContentIsAValidRevisionAndNullIsNot() {
    // An empty parent still has a revision (the empty-string digest); a null one is a caller bug,
    // never "no revision" — the reader treats an ABSENT stored revision as fail-closed.
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        ChunkParentRevision.sha256Hex(""));
    assertThrows(IllegalArgumentException.class, () -> ChunkParentRevision.sha256Hex(null));
  }

  @Test
  void shortFormIsTheFirstEightHexCharsAndNamesAbsence() {
    String hash = ChunkParentRevision.sha256Hex("abc");
    assertEquals("ba7816bf", ChunkParentRevision.shortForm(hash));
    assertEquals("abcdef", ChunkParentRevision.shortForm("abcdef"));
    assertEquals("<absent>", ChunkParentRevision.shortForm(null));
    assertEquals("<absent>", ChunkParentRevision.shortForm("  "));
  }
}
