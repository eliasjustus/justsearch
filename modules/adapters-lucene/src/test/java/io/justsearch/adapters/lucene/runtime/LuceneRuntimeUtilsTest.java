package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.indexing.SchemaFields;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for LuceneRuntimeUtils utility methods.
 *
 * <p>Tests static utility methods extracted from LuceneIndexRuntime.
 */
@DisplayName("LuceneRuntimeUtils")
class LuceneRuntimeUtilsTest {

  private static final String TEST_ID_FIELD = "doc_id";

  @Nested
  @DisplayName("buildRuntimeSort()")
  class BuildRuntimeSortTests {

    @Test
    @DisplayName("null sort throws NullPointerException")
    void nullSort_throwsNullPointerException() {
      NullPointerException ex =
          assertThrows(
              NullPointerException.class,
              () -> LuceneRuntimeUtils.buildRuntimeSort(null, TEST_ID_FIELD));
      assertEquals("sort must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("null idField throws NullPointerException")
    void nullIdField_throwsNullPointerException() {
      NullPointerException ex =
          assertThrows(
              NullPointerException.class,
              () -> LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.RELEVANCE, null));
      assertEquals("idField must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("RELEVANCE sort uses score and idField tie-breaker")
    void relevanceSort_usesScoreAndIdField() {
      Sort sort = LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.RELEVANCE, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(2, fields.length);
      assertEquals(SortField.FIELD_SCORE, fields[0]);
      assertEquals(TEST_ID_FIELD, fields[1].getField());
      assertEquals(SortField.Type.STRING, fields[1].getType());
    }

    @Test
    @DisplayName("MODIFIED_DESC sort uses modified_at descending with idField tie-breaker")
    void modifiedDescSort_usesModifiedAtDescending() {
      Sort sort =
          LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.MODIFIED_DESC, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(2, fields.length);
      assertEquals(SchemaFields.MODIFIED_AT, fields[0].getField());
      assertEquals(SortField.Type.LONG, fields[0].getType());
      assertEquals(true, fields[0].getReverse()); // descending
      assertEquals(TEST_ID_FIELD, fields[1].getField());
    }

    @Test
    @DisplayName("MODIFIED_ASC sort uses modified_at ascending with idField tie-breaker")
    void modifiedAscSort_usesModifiedAtAscending() {
      Sort sort =
          LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.MODIFIED_ASC, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(2, fields.length);
      assertEquals(SchemaFields.MODIFIED_AT, fields[0].getField());
      assertEquals(SortField.Type.LONG, fields[0].getType());
      assertEquals(false, fields[0].getReverse()); // ascending
      assertEquals(TEST_ID_FIELD, fields[1].getField());
    }

    @Test
    @DisplayName("SIZE_DESC sort uses size_bytes descending with idField tie-breaker")
    void sizeDescSort_usesSizeBytesDescending() {
      Sort sort = LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.SIZE_DESC, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(2, fields.length);
      assertEquals(SchemaFields.SIZE_BYTES, fields[0].getField());
      assertEquals(SortField.Type.LONG, fields[0].getType());
      assertEquals(true, fields[0].getReverse()); // descending
      assertEquals(TEST_ID_FIELD, fields[1].getField());
    }

    @Test
    @DisplayName("SIZE_ASC sort uses size_bytes ascending with idField tie-breaker")
    void sizeAscSort_usesSizeBytesAscending() {
      Sort sort = LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.SIZE_ASC, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(2, fields.length);
      assertEquals(SchemaFields.SIZE_BYTES, fields[0].getField());
      assertEquals(SortField.Type.LONG, fields[0].getType());
      assertEquals(false, fields[0].getReverse()); // ascending
      assertEquals(TEST_ID_FIELD, fields[1].getField());
    }

    @Test
    @DisplayName("PATH_ASC sort uses idField ascending only")
    void pathAscSort_usesIdFieldAscending() {
      Sort sort = LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.PATH_ASC, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(1, fields.length);
      assertEquals(TEST_ID_FIELD, fields[0].getField());
      assertEquals(SortField.Type.STRING, fields[0].getType());
      assertEquals(false, fields[0].getReverse()); // ascending
    }

    @Test
    @DisplayName("PATH_DESC sort uses idField descending only")
    void pathDescSort_usesIdFieldDescending() {
      Sort sort = LuceneRuntimeUtils.buildRuntimeSort(RuntimeSearchSort.PATH_DESC, TEST_ID_FIELD);

      assertNotNull(sort);
      SortField[] fields = sort.getSort();
      assertEquals(1, fields.length);
      assertEquals(TEST_ID_FIELD, fields[0].getField());
      assertEquals(SortField.Type.STRING, fields[0].getType());
      assertEquals(true, fields[0].getReverse()); // descending
    }

    @Test
    @DisplayName("all sort types produce valid Sort instances")
    void allSortTypes_produceValidSort() {
      for (RuntimeSearchSort sortType : RuntimeSearchSort.values()) {
        Sort sort = LuceneRuntimeUtils.buildRuntimeSort(sortType, TEST_ID_FIELD);
        assertNotNull(sort, "Sort for " + sortType + " should not be null");
        assertNotNull(sort.getSort(), "SortFields for " + sortType + " should not be null");
      }
    }
  }
}
