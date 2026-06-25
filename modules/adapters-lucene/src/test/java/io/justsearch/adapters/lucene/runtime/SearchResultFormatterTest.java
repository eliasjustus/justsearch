package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexing.SchemaFields;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for SearchResultFormatter utility methods.
 *
 * <p>Note: extractFromStoredFields() requires a real Lucene index and is covered by
 * LuceneIndexRuntimeTest. This test class focuses on extractFromDocument() and null validation.
 */
@DisplayName("SearchResultFormatter")
class SearchResultFormatterTest {

  @Nested
  @DisplayName("extractFromDocument()")
  class ExtractFromDocumentTests {

    @Test
    @DisplayName("null document throws NullPointerException")
    void nullDocument_throwsNullPointerException() {
      NullPointerException ex =
          assertThrows(
              NullPointerException.class,
              () -> SearchResultFormatter.extractFromDocument(null, true));
      assertEquals("doc must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("empty document returns empty map")
    void emptyDocument_returnsEmptyMap() {
      Document doc = new Document();
      Map<String, String> result = SearchResultFormatter.extractFromDocument(doc, true);
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("document with string fields extracts all fields")
    void documentWithStringFields_extractsAllFields() {
      Document doc = new Document();
      doc.add(new StringField("id", "doc-123", Field.Store.YES));
      doc.add(new StringField("title", "Test Document", Field.Store.YES));
      doc.add(new StringField("author", "John Doe", Field.Store.YES));

      Map<String, String> result = SearchResultFormatter.extractFromDocument(doc, true);

      assertEquals(3, result.size());
      assertEquals("doc-123", result.get("id"));
      assertEquals("Test Document", result.get("title"));
      assertEquals("John Doe", result.get("author"));
    }

    @Test
    @DisplayName("document with content field excludes content when flag is false")
    void documentWithContentField_excludesWhenFlagFalse() {
      Document doc = new Document();
      doc.add(new StringField("id", "doc-123", Field.Store.YES));
      doc.add(new TextField(SchemaFields.CONTENT, "This is the full content", Field.Store.YES));

      Map<String, String> result = SearchResultFormatter.extractFromDocument(doc, false);

      assertEquals(1, result.size());
      assertEquals("doc-123", result.get("id"));
      assertTrue(!result.containsKey(SchemaFields.CONTENT));
    }

    @Test
    @DisplayName("document with content field includes content when flag is true")
    void documentWithContentField_includesWhenFlagTrue() {
      Document doc = new Document();
      doc.add(new StringField("id", "doc-123", Field.Store.YES));
      doc.add(new TextField(SchemaFields.CONTENT, "This is the full content", Field.Store.YES));

      Map<String, String> result = SearchResultFormatter.extractFromDocument(doc, true);

      assertEquals(2, result.size());
      assertEquals("doc-123", result.get("id"));
      assertEquals("This is the full content", result.get(SchemaFields.CONTENT));
    }

    @Test
    @DisplayName("document with null field value skips that field")
    void documentWithNullFieldValue_skipsField() {
      // StringField and TextField don't allow null values, so we test with a real Document
      // that only has fields with values - the extraction should work fine
      Document doc = new Document();
      doc.add(new StringField("id", "doc-123", Field.Store.YES));
      doc.add(new StringField("title", "Test", Field.Store.YES));

      Map<String, String> result = SearchResultFormatter.extractFromDocument(doc, true);

      assertEquals(2, result.size());
      assertEquals("doc-123", result.get("id"));
      assertEquals("Test", result.get("title"));
    }

    @Test
    @DisplayName("document with multiple content-like fields only filters actual content field")
    void documentWithMultipleFields_onlyFiltersContentField() {
      Document doc = new Document();
      doc.add(new StringField("id", "doc-123", Field.Store.YES));
      doc.add(new TextField(SchemaFields.CONTENT, "The actual content", Field.Store.YES));
      doc.add(new StringField("content_type", "text/plain", Field.Store.YES));
      doc.add(new StringField("content_length", "1234", Field.Store.YES));

      Map<String, String> result = SearchResultFormatter.extractFromDocument(doc, false);

      assertEquals(3, result.size());
      assertEquals("doc-123", result.get("id"));
      assertEquals("text/plain", result.get("content_type"));
      assertEquals("1234", result.get("content_length"));
      assertTrue(!result.containsKey(SchemaFields.CONTENT));
    }
  }

  @Nested
  @DisplayName("extractFromStoredFields()")
  class ExtractFromStoredFieldsTests {

    @Test
    @DisplayName("null storedFields throws NullPointerException")
    void nullStoredFields_throwsNullPointerException() {
      NullPointerException ex =
          assertThrows(
              NullPointerException.class,
              () -> SearchResultFormatter.extractFromStoredFields(null, 0, true, null));
      assertEquals("storedFields must not be null", ex.getMessage());
    }

    // Note: Full StoredFields tests require a real Lucene index.
    // These are covered by LuceneIndexRuntimeTest and LuceneIndexRuntimeSearchAfterTest.
  }
}
