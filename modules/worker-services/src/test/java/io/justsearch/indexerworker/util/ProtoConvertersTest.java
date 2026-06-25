package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchSort;
import io.justsearch.ipc.TimeRangeMs;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProtoConverters}. */
class ProtoConvertersTest {

  @Nested
  class ToRuntimeSortTests {

    @Test
    void nullInput_returnsRelevance() {
      assertEquals(RuntimeSearchSort.RELEVANCE, ProtoConverters.toRuntimeSort(null));
    }

    @Test
    void unrecognized_returnsRelevance() {
      assertEquals(
          RuntimeSearchSort.RELEVANCE,
          ProtoConverters.toRuntimeSort(SearchSort.UNRECOGNIZED));
    }

    @Test
    void modifiedDesc_mapsCorrectly() {
      assertEquals(
          RuntimeSearchSort.MODIFIED_DESC,
          ProtoConverters.toRuntimeSort(SearchSort.SEARCH_SORT_MODIFIED_DESC));
    }

    @Test
    void modifiedAsc_mapsCorrectly() {
      assertEquals(
          RuntimeSearchSort.MODIFIED_ASC,
          ProtoConverters.toRuntimeSort(SearchSort.SEARCH_SORT_MODIFIED_ASC));
    }

    @Test
    void sizeDesc_mapsCorrectly() {
      assertEquals(
          RuntimeSearchSort.SIZE_DESC,
          ProtoConverters.toRuntimeSort(SearchSort.SEARCH_SORT_SIZE_DESC));
    }

    @Test
    void sizeAsc_mapsCorrectly() {
      assertEquals(
          RuntimeSearchSort.SIZE_ASC,
          ProtoConverters.toRuntimeSort(SearchSort.SEARCH_SORT_SIZE_ASC));
    }

    @Test
    void pathAsc_mapsCorrectly() {
      assertEquals(
          RuntimeSearchSort.PATH_ASC,
          ProtoConverters.toRuntimeSort(SearchSort.SEARCH_SORT_PATH_ASC));
    }

    @Test
    void pathDesc_mapsCorrectly() {
      assertEquals(
          RuntimeSearchSort.PATH_DESC,
          ProtoConverters.toRuntimeSort(SearchSort.SEARCH_SORT_PATH_DESC));
    }
  }

  @Nested
  class ToRuntimeFiltersTests {

    @Test
    void nullInput_returnsNull() {
      assertNull(ProtoConverters.toRuntimeFilters(null));
    }

    @Test
    void emptyFilters_returnsFiltersWithNullLists() {
      SearchFilters empty = SearchFilters.newBuilder().build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(empty);

      assertNotNull(result);
      assertNull(result.mime());
      assertNull(result.language());
      assertNull(result.fileKind());
      assertEquals("", result.pathPrefix());
      assertNull(result.modifiedFromMs());
      assertNull(result.modifiedToMs());
      assertFalse(result.includeChunks());
    }

    @Test
    void populatedMimeList_preservesValues() {
      SearchFilters filters =
          SearchFilters.newBuilder().addMime("text/plain").addMime("application/pdf").build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertNotNull(result.mime());
      assertEquals(2, result.mime().size());
      assertTrue(result.mime().contains("text/plain"));
      assertTrue(result.mime().contains("application/pdf"));
    }

    @Test
    void populatedLanguageList_preservesValues() {
      SearchFilters filters =
          SearchFilters.newBuilder().addLanguage("en").addLanguage("de").build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertNotNull(result.language());
      assertEquals(2, result.language().size());
      assertTrue(result.language().contains("en"));
      assertTrue(result.language().contains("de"));
    }

    @Test
    void pathPrefix_preservesValue() {
      SearchFilters filters = SearchFilters.newBuilder().setPathPrefix("/docs/").build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertEquals("/docs/", result.pathPrefix());
    }

    @Test
    void dateRange_preservesValues() {
      SearchFilters filters =
          SearchFilters.newBuilder()
              .setModifiedAt(TimeRangeMs.newBuilder().setFromMs(1000L).setToMs(2000L).build())
              .build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertEquals(1000L, result.modifiedFromMs());
      assertEquals(2000L, result.modifiedToMs());
    }

    @Test
    void dateRangeWithZeroFrom_returnsNullFrom() {
      SearchFilters filters =
          SearchFilters.newBuilder()
              .setModifiedAt(TimeRangeMs.newBuilder().setFromMs(0L).setToMs(2000L).build())
              .build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertNull(result.modifiedFromMs());
      assertEquals(2000L, result.modifiedToMs());
    }

    @Test
    void includeChunksTrue_preservesValue() {
      SearchFilters filters = SearchFilters.newBuilder().setIncludeChunks(true).build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertTrue(result.includeChunks());
    }

    @Test
    void fileKindList_preservesValues() {
      SearchFilters filters =
          SearchFilters.newBuilder().addFileKind("code").addFileKind("document").build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertNotNull(result.fileKind());
      assertEquals(2, result.fileKind().size());
      assertTrue(result.fileKind().contains("code"));
      assertTrue(result.fileKind().contains("document"));
    }

    @Test
    void mimeBaseList_preservesValues() {
      SearchFilters filters =
          SearchFilters.newBuilder().addMimeBase("text").addMimeBase("application").build();
      RuntimeSearchFilters result = ProtoConverters.toRuntimeFilters(filters);

      assertNotNull(result.mimeBase());
      assertEquals(2, result.mimeBase().size());
      assertTrue(result.mimeBase().contains("text"));
      assertTrue(result.mimeBase().contains("application"));
    }
  }
}
