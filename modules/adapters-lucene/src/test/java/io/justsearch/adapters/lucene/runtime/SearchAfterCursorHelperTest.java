package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.SearchAfterCursorHelper.DecodedCursor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for SearchAfterCursorHelper utility methods.
 *
 * <p>Tests cursor encoding and decoding for all sort types.
 */
@DisplayName("SearchAfterCursorHelper")
class SearchAfterCursorHelperTest {

  private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();

  @Nested
  @DisplayName("decode()")
  class DecodeTests {

    @Test
    @DisplayName("null token returns null")
    void nullToken_returnsNull() {
      assertNull(SearchAfterCursorHelper.decode(null));
    }

    @Test
    @DisplayName("blank token returns null")
    void blankToken_returnsNull() {
      assertNull(SearchAfterCursorHelper.decode(""));
    }

    @Test
    @DisplayName("whitespace token returns null")
    void whitespaceToken_returnsNull() {
      assertNull(SearchAfterCursorHelper.decode("   "));
    }

    @Test
    @DisplayName("invalid prefix throws IllegalArgumentException")
    void invalidPrefix_throwsIllegalArgument() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> SearchAfterCursorHelper.decode("invalid-prefix:relevance:abc:1.0:_:_"));
      assertEquals("invalid_cursor_prefix", ex.getMessage());
    }

    @Test
    @DisplayName("invalid format (wrong number of parts) throws IllegalArgumentException")
    void invalidFormat_throwsIllegalArgument() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> SearchAfterCursorHelper.decode("safter-v1:relevance:abc"));
      assertEquals("invalid_cursor_format", ex.getMessage());
    }

    @Test
    @DisplayName("invalid sort key throws IllegalArgumentException")
    void invalidSort_throwsIllegalArgument() {
      String docIdB64 = B64_ENC.encodeToString("doc-123".getBytes(StandardCharsets.UTF_8));
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  SearchAfterCursorHelper.decode(
                      "safter-v1:invalid_sort:" + docIdB64 + ":1.0:_:_"));
      assertEquals("invalid_cursor_sort", ex.getMessage());
    }

    @Test
    @DisplayName("invalid base64 doc ID throws IllegalArgumentException")
    void invalidBase64DocId_throwsIllegalArgument() {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> SearchAfterCursorHelper.decode("safter-v1:relevance:!!!invalid!!!:1.0:_:_"));
      assertEquals("invalid_cursor_doc_id", ex.getMessage());
    }

    @Test
    @DisplayName("valid relevance cursor decodes correctly")
    void validRelevanceCursor_decodesCorrectly() {
      String docIdB64 = B64_ENC.encodeToString("doc-123".getBytes(StandardCharsets.UTF_8));
      String token = "safter-v1:relevance:" + docIdB64 + ":0.95:_:_";

      DecodedCursor result = SearchAfterCursorHelper.decode(token);

      assertNotNull(result);
      assertEquals(RuntimeSearchSort.RELEVANCE, result.sort());
      assertEquals("doc-123", result.docId());
      assertEquals(0.95f, result.score(), 0.001f);
      assertNull(result.modifiedAt());
      assertNull(result.sizeBytes());
    }

    @Test
    @DisplayName("valid modified_desc cursor decodes correctly")
    void validModifiedDescCursor_decodesCorrectly() {
      String docIdB64 = B64_ENC.encodeToString("doc-456".getBytes(StandardCharsets.UTF_8));
      String token = "safter-v1:modified_desc:" + docIdB64 + ":_:1705123456789:_";

      DecodedCursor result = SearchAfterCursorHelper.decode(token);

      assertNotNull(result);
      assertEquals(RuntimeSearchSort.MODIFIED_DESC, result.sort());
      assertEquals("doc-456", result.docId());
      assertNull(result.score());
      assertEquals(1705123456789L, result.modifiedAt());
      assertNull(result.sizeBytes());
    }

    @Test
    @DisplayName("valid size_asc cursor decodes correctly")
    void validSizeAscCursor_decodesCorrectly() {
      String docIdB64 = B64_ENC.encodeToString("doc-789".getBytes(StandardCharsets.UTF_8));
      String token = "safter-v1:size_asc:" + docIdB64 + ":_:_:102400";

      DecodedCursor result = SearchAfterCursorHelper.decode(token);

      assertNotNull(result);
      assertEquals(RuntimeSearchSort.SIZE_ASC, result.sort());
      assertEquals("doc-789", result.docId());
      assertNull(result.score());
      assertNull(result.modifiedAt());
      assertEquals(102400L, result.sizeBytes());
    }

    @Test
    @DisplayName("valid path_asc cursor decodes correctly")
    void validPathCursor_decodesCorrectly() {
      String docIdB64 = B64_ENC.encodeToString("/path/to/file.txt".getBytes(StandardCharsets.UTF_8));
      String token = "safter-v1:path_asc:" + docIdB64 + ":_:_:_";

      DecodedCursor result = SearchAfterCursorHelper.decode(token);

      assertNotNull(result);
      assertEquals(RuntimeSearchSort.PATH_ASC, result.sort());
      assertEquals("/path/to/file.txt", result.docId());
      assertNull(result.score());
      assertNull(result.modifiedAt());
      assertNull(result.sizeBytes());
    }

    @Test
    @DisplayName("token with whitespace is trimmed before decoding")
    void tokenWithWhitespace_isTrimmed() {
      String docIdB64 = B64_ENC.encodeToString("doc-123".getBytes(StandardCharsets.UTF_8));
      String token = "  safter-v1:relevance:" + docIdB64 + ":0.5:_:_  ";

      DecodedCursor result = SearchAfterCursorHelper.decode(token);

      assertNotNull(result);
      assertEquals("doc-123", result.docId());
    }
  }

  @Nested
  @DisplayName("encode()")
  class EncodeTests {

    @Test
    @DisplayName("null sort returns null")
    void nullSort_returnsNull() {
      ScoreDoc scoreDoc = new ScoreDoc(0, 1.0f);
      assertNull(SearchAfterCursorHelper.encode(null, scoreDoc, "doc-123"));
    }

    @Test
    @DisplayName("null docId returns null")
    void nullDocId_returnsNull() {
      ScoreDoc scoreDoc = new ScoreDoc(0, 1.0f);
      assertNull(SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, scoreDoc, null));
    }

    @Test
    @DisplayName("blank docId returns null")
    void blankDocId_returnsNull() {
      ScoreDoc scoreDoc = new ScoreDoc(0, 1.0f);
      assertNull(SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, scoreDoc, "   "));
    }

    @Test
    @DisplayName("null ScoreDoc returns null")
    void nullScoreDoc_returnsNull() {
      assertNull(SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, null, "doc-123"));
    }

    @Test
    @DisplayName("relevance sort encodes score and docId")
    void relevanceSort_encodesScoreAndDocId() {
      ScoreDoc scoreDoc = new ScoreDoc(42, 0.85f);
      String result = SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, scoreDoc, "doc-123");

      assertNotNull(result);
      assertTrue(result.startsWith("safter-v1:relevance:"));
      assertTrue(result.contains(":0.85:"));
      assertTrue(result.endsWith(":_:_"));
    }

    @Test
    @DisplayName("modified_desc sort encodes modifiedAt from FieldDoc")
    void modifiedDescSort_encodesModifiedAt() {
      FieldDoc fieldDoc = new FieldDoc(42, Float.NaN, new Object[] {1705123456789L});
      String result =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.MODIFIED_DESC, fieldDoc, "doc-456");

      assertNotNull(result);
      assertTrue(result.startsWith("safter-v1:modified_desc:"));
      assertTrue(result.contains(":_:1705123456789:_"));
    }

    @Test
    @DisplayName("size_desc sort encodes sizeBytes from FieldDoc")
    void sizeDescSort_encodesSizeBytes() {
      FieldDoc fieldDoc = new FieldDoc(42, Float.NaN, new Object[] {102400L});
      String result =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.SIZE_DESC, fieldDoc, "doc-789");

      assertNotNull(result);
      assertTrue(result.startsWith("safter-v1:size_desc:"));
      assertTrue(result.contains(":_:_:102400"));
    }

    @Test
    @DisplayName("path_asc sort encodes docId only")
    void pathSort_encodesDocIdOnly() {
      ScoreDoc scoreDoc = new ScoreDoc(42, Float.NaN);
      String result =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.PATH_ASC, scoreDoc, "/path/to/file.txt");

      assertNotNull(result);
      assertTrue(result.startsWith("safter-v1:path_asc:"));
      assertTrue(result.endsWith(":_:_:_"));
    }

    @Test
    @DisplayName("modified sort with plain ScoreDoc (no fields) uses underscore")
    void modifiedSortWithPlainScoreDoc_usesUnderscore() {
      ScoreDoc scoreDoc = new ScoreDoc(42, Float.NaN);
      String result =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.MODIFIED_DESC, scoreDoc, "doc-123");

      assertNotNull(result);
      assertTrue(result.contains(":_:_:_"));
    }
  }

  @Nested
  @DisplayName("roundtrip")
  class RoundtripTests {

    @Test
    @DisplayName("encode and decode preserves all fields for relevance sort")
    void encodeAndDecode_preservesAllFieldsForRelevance() {
      ScoreDoc scoreDoc = new ScoreDoc(42, 0.95f);
      String encoded =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, scoreDoc, "doc-123");

      DecodedCursor decoded = SearchAfterCursorHelper.decode(encoded);

      assertNotNull(decoded);
      assertEquals(RuntimeSearchSort.RELEVANCE, decoded.sort());
      assertEquals("doc-123", decoded.docId());
      assertEquals(0.95f, decoded.score(), 0.001f);
    }

    @Test
    @DisplayName("encode and decode preserves all fields for modified sort")
    void encodeAndDecode_preservesAllFieldsForModified() {
      FieldDoc fieldDoc = new FieldDoc(42, Float.NaN, new Object[] {1705123456789L});
      String encoded =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.MODIFIED_ASC, fieldDoc, "doc-456");

      DecodedCursor decoded = SearchAfterCursorHelper.decode(encoded);

      assertNotNull(decoded);
      assertEquals(RuntimeSearchSort.MODIFIED_ASC, decoded.sort());
      assertEquals("doc-456", decoded.docId());
      assertEquals(1705123456789L, decoded.modifiedAt());
    }

    @Test
    @DisplayName("encode and decode preserves all fields for size sort")
    void encodeAndDecode_preservesAllFieldsForSize() {
      FieldDoc fieldDoc = new FieldDoc(42, Float.NaN, new Object[] {204800L});
      String encoded =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.SIZE_ASC, fieldDoc, "doc-789");

      DecodedCursor decoded = SearchAfterCursorHelper.decode(encoded);

      assertNotNull(decoded);
      assertEquals(RuntimeSearchSort.SIZE_ASC, decoded.sort());
      assertEquals("doc-789", decoded.docId());
      assertEquals(204800L, decoded.sizeBytes());
    }

    @Test
    @DisplayName("encode and decode handles special characters in docId")
    void encodeAndDecode_handlesSpecialCharactersInDocId() {
      String complexDocId = "/path/to/file with spaces & special=chars?.txt";
      ScoreDoc scoreDoc = new ScoreDoc(42, 0.5f);
      String encoded =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, scoreDoc, complexDocId);

      DecodedCursor decoded = SearchAfterCursorHelper.decode(encoded);

      assertNotNull(decoded);
      assertEquals(complexDocId, decoded.docId());
    }

    @Test
    @DisplayName("encode and decode handles Unicode in docId")
    void encodeAndDecode_handlesUnicodeInDocId() {
      String unicodeDocId = "/path/to/日本語ファイル.txt";
      ScoreDoc scoreDoc = new ScoreDoc(42, 0.5f);
      String encoded =
          SearchAfterCursorHelper.encode(RuntimeSearchSort.RELEVANCE, scoreDoc, unicodeDocId);

      DecodedCursor decoded = SearchAfterCursorHelper.decode(encoded);

      assertNotNull(decoded);
      assertEquals(unicodeDocId, decoded.docId());
    }
  }
}
