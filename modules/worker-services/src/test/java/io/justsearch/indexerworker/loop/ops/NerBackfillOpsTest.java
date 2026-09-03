package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@DisplayName("NerBackfillOps")
@ExtendWith(MockitoExtension.class)
class NerBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock CommitOps commitOps;
  @Mock NerService nerService;

  @Nested
  @DisplayName("processNerBackfill()")
  class ProcessNerBackfill {

    @Test
    @DisplayName("no-op when no pending documents")
    void noop_whenNoPendingDocs() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
          .thenReturn(List.of());

      NerBackfillOps.BackfillContext context =
          new NerBackfillOps.BackfillContext(
              documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(), () -> nerService, () -> true, 100,
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      NerBackfillOps.processNerBackfill(context);

      verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
      verify(commitOps, never()).commitAndTrack(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("escalates via the retry seam when content is blank — never COMPLETED-without-data")
    void escalatesDoc_whenContentBlank() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1"));
      when(documentFieldOps.getDocumentContent("doc1")).thenReturn("");

      NerBackfillOps.BackfillContext context =
          new NerBackfillOps.BackfillContext(
              documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(), () -> nerService, () -> true, 100,
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      NerBackfillOps.processNerBackfill(context);

      // NER never ran, so nothing may claim COMPLETED: the doc takes the retry-count escalation.
      verify(indexingCoordinator)
          .updateDocument(
              eq("doc1"),
              argThat(
                  (Map<String, Object> map) ->
                      "1".equals(map.get(SchemaFields.NER_RETRY_COUNT))
                          && !map.containsKey(SchemaFields.NER_STATUS)));
      verify(indexingCoordinator, never()).updateDocumentsBatch(anyList());
      verify(nerService, never()).extractEntities(anyString());
    }

    @Test
    @DisplayName("marks COMPLETED_EMPTY when NER ran and extracted no entities")
    void marksCompletedEmpty_whenNerFoundNoEntities() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1"));
      when(documentFieldOps.getDocumentContent("doc1")).thenReturn("content with no entities");
      when(nerService.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));
      when(indexingCoordinator.updateDocumentsBatch(anyList()))
          .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

      NerBackfillOps.BackfillContext context =
          new NerBackfillOps.BackfillContext(
              documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(), () -> nerService, () -> true, 100,
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      NerBackfillOps.processNerBackfill(context);

      verify(indexingCoordinator)
          .updateDocumentsBatch(
              argThat(
                  (List<Map.Entry<String, Map<String, Object>>> entries) ->
                      entries.size() == 1
                          && entries.get(0).getKey().equals("doc1")
                          && SchemaFields.NER_STATUS_COMPLETED_EMPTY.equals(
                              entries.get(0).getValue().get(SchemaFields.NER_STATUS))));
    }

    @Test
    @DisplayName("marks COMPLETED when NER extracted at least one entity")
    void marksCompleted_whenNerFoundEntities() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1"));
      when(documentFieldOps.getDocumentContent("doc1")).thenReturn("Ada Lovelace wrote this");
      when(nerService.extractEntitiesBatch(anyList()))
          .thenReturn(List.of(new NerResult(List.of("Ada Lovelace"), List.of(), List.of())));
      when(indexingCoordinator.updateDocumentsBatch(anyList()))
          .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

      NerBackfillOps.BackfillContext context =
          new NerBackfillOps.BackfillContext(
              documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(), () -> nerService, () -> true, 100,
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      NerBackfillOps.processNerBackfill(context);

      verify(indexingCoordinator)
          .updateDocumentsBatch(
              argThat(
                  (List<Map.Entry<String, Map<String, Object>>> entries) ->
                      entries.size() == 1
                          && SchemaFields.NER_STATUS_COMPLETED.equals(
                              entries.get(0).getValue().get(SchemaFields.NER_STATUS))));
    }

    @Test
    @DisplayName("graceful exit when NER service is null (H2 fix)")
    void gracefulExit_whenNerServiceNull() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1"));
      when(documentFieldOps.getDocumentContent("doc1")).thenReturn("Some content about Paris");

      NerBackfillOps.BackfillContext context =
          new NerBackfillOps.BackfillContext(
              documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(), () -> null, () -> true, 100,
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      // Should not throw NPE
      NerBackfillOps.processNerBackfill(context);

      // No extraction should have happened
      verify(nerService, never()).extractEntities(anyString());
    }

    @Test
    @DisplayName("stops processing on shutdown signal")
    void stopsOnShutdown() {
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
          .thenReturn(List.of("doc1", "doc2"));
      // runningSupplier returns false immediately — should break before processing any docs
      NerBackfillOps.BackfillContext context =
          new NerBackfillOps.BackfillContext(
              documentFieldOps, indexingCoordinator, commitOps, IndexingPacing.unthrottled(), () -> nerService, () -> false, 100,
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      NerBackfillOps.processNerBackfill(context);

      verify(documentFieldOps, never()).getDocumentContent(anyString());
    }
  }

  @Nested
  @DisplayName("handleNerFailure()")
  class HandleNerFailure {

    @Test
    @DisplayName("increments retry count on first failure")
    void incrementsRetryCount() {
      when(documentFieldOps.getDocumentField("doc1", SchemaFields.NER_RETRY_COUNT)).thenReturn(null);
      when(indexingCoordinator.updateDocument(anyString(), anyMap())).thenReturn(true);

      int result =
          NerBackfillOps.handleNerFailure(
              documentFieldOps, indexingCoordinator, "doc1", "test error",
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      assertEquals(0, result); // not permanently failed
      verify(indexingCoordinator)
          .updateDocument(
              eq("doc1"),
              argThat(
                  (Map<String, Object> map) -> "1".equals(map.get(SchemaFields.NER_RETRY_COUNT))));
    }

    @Test
    @DisplayName("marks FAILED after max retries")
    void marksFailedAfterMaxRetries() {
      when(documentFieldOps.getDocumentField("doc1", SchemaFields.NER_RETRY_COUNT))
          .thenReturn(String.valueOf(SchemaFields.NER_MAX_RETRIES - 1));
      when(indexingCoordinator.updateDocument(anyString(), anyMap())).thenReturn(true);

      int result =
          NerBackfillOps.handleNerFailure(
              documentFieldOps, indexingCoordinator, "doc1", "persistent error",
              LoggerFactory.getLogger(NerBackfillOpsTest.class));

      assertEquals(1, result); // permanently failed
      verify(indexingCoordinator)
          .updateDocument(
              eq("doc1"),
              argThat(
                  (Map<String, Object> map) ->
                      SchemaFields.NER_STATUS_FAILED.equals(map.get(SchemaFields.NER_STATUS))));
    }
  }

  @Nested
  @DisplayName("applyEntityFieldUpdates()")
  class ApplyEntityFieldUpdates {

    @Test
    @DisplayName("no-op when result is empty")
    void noop_whenResultEmpty() {
      Map<String, Object> updates = new java.util.HashMap<>();

      NerBackfillOps.applyEntityFieldUpdates(updates, NerResult.EMPTY);

      assertTrue(updates.isEmpty());
    }

    @Test
    @DisplayName("writes RAW list and space-joined TEXT for every non-empty entity type")
    void writesRawAndText_forEveryNonEmptyEntityType() {
      Map<String, Object> updates = new java.util.HashMap<>();
      NerResult result =
          new NerResult(
              List.of("Ada Lovelace", "Alan Turing"), List.of("NASA"), List.of("Paris", "Berlin"));

      NerBackfillOps.applyEntityFieldUpdates(updates, result);

      assertEquals(List.of("Ada Lovelace", "Alan Turing"), updates.get(SchemaFields.ENTITY_PERSONS_RAW));
      assertEquals("Ada Lovelace Alan Turing", updates.get(SchemaFields.ENTITY_PERSONS_TEXT));
      assertEquals(List.of("NASA"), updates.get(SchemaFields.ENTITY_ORGANIZATIONS_RAW));
      assertEquals("NASA", updates.get(SchemaFields.ENTITY_ORGANIZATIONS_TEXT));
      assertEquals(List.of("Paris", "Berlin"), updates.get(SchemaFields.ENTITY_LOCATIONS_RAW));
      assertEquals("Paris Berlin", updates.get(SchemaFields.ENTITY_LOCATIONS_TEXT));
    }

    @Test
    @DisplayName("omits RAW/TEXT fields for entity types with no extracted values")
    void omitsFields_forEmptyEntityTypes() {
      Map<String, Object> updates = new java.util.HashMap<>();
      NerResult result = new NerResult(List.of("Ada Lovelace"), List.of(), List.of());

      NerBackfillOps.applyEntityFieldUpdates(updates, result);

      assertTrue(updates.containsKey(SchemaFields.ENTITY_PERSONS_RAW));
      assertTrue(updates.containsKey(SchemaFields.ENTITY_PERSONS_TEXT));
      assertFalse(updates.containsKey(SchemaFields.ENTITY_ORGANIZATIONS_RAW));
      assertFalse(updates.containsKey(SchemaFields.ENTITY_ORGANIZATIONS_TEXT));
      assertFalse(updates.containsKey(SchemaFields.ENTITY_LOCATIONS_RAW));
      assertFalse(updates.containsKey(SchemaFields.ENTITY_LOCATIONS_TEXT));
    }
  }
}
