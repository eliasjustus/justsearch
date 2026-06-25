package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@DisplayName("DisambiguationBackfillOps")
@ExtendWith(MockitoExtension.class)
class DisambiguationBackfillOpsTest {

  @Mock DocumentFieldOps documentFieldOps;
  @Mock WorkerSignalBus signalBus;
  @Mock DisambiguationService disambiguationService;

  private DisambiguationBackfillOps.BackfillContext context() {
    return new DisambiguationBackfillOps.BackfillContext(
        documentFieldOps,
        signalBus,
        () -> disambiguationService,
        () -> true,
        100,
        LoggerFactory.getLogger(DisambiguationBackfillOpsTest.class));
  }

  @Nested
  @DisplayName("processDisambiguationBackfill()")
  class ProcessBackfill {

    @Test
    @DisplayName("no-op when service is null")
    void noopWhenServiceNull() {
      DisambiguationBackfillOps.BackfillContext ctx =
          new DisambiguationBackfillOps.BackfillContext(
              documentFieldOps,
              signalBus,
              () -> null,
              () -> true,
              100,
              LoggerFactory.getLogger(DisambiguationBackfillOpsTest.class));

      DisambiguationBackfillOps.processDisambiguationBackfill(ctx);

      verify(documentFieldOps, never()).queryDocIdsByField(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("no-op when service is not available")
    void noopWhenServiceUnavailable() {
      when(disambiguationService.isAvailable()).thenReturn(false);

      DisambiguationBackfillOps.processDisambiguationBackfill(context());

      verify(documentFieldOps, never()).queryDocIdsByField(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("no-op when no NER-completed documents")
    void noopWhenNoCompletedDocs() {
      when(disambiguationService.isAvailable()).thenReturn(true);
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_COMPLETED), anyInt()))
          .thenReturn(List.of());

      DisambiguationBackfillOps.processDisambiguationBackfill(context());

      // Should query but find nothing
      verify(documentFieldOps)
          .queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_COMPLETED), anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("collects entity mentions and calls processBatch")
    void happyPathCollectsAndProcesses() throws Exception {
      when(disambiguationService.isAvailable()).thenReturn(true);
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_COMPLETED), anyInt()))
          .thenReturn(List.of("doc1", "doc2"));
      when(signalBus.isUserActive()).thenReturn(false);

      // doc1 has persons and orgs
      when(documentFieldOps.getDocumentFieldValues("doc1", SchemaFields.ENTITY_PERSONS_RAW))
          .thenReturn(List.of("John Smith", "Jane Doe"));
      when(documentFieldOps.getDocumentFieldValues("doc1", SchemaFields.ENTITY_ORGANIZATIONS_RAW))
          .thenReturn(List.of("Acme Corp"));
      when(documentFieldOps.getDocumentFieldValues("doc1", SchemaFields.ENTITY_LOCATIONS_RAW))
          .thenReturn(List.of());

      // doc2 has locations only
      when(documentFieldOps.getDocumentFieldValues("doc2", SchemaFields.ENTITY_PERSONS_RAW))
          .thenReturn(List.of());
      when(documentFieldOps.getDocumentFieldValues("doc2", SchemaFields.ENTITY_ORGANIZATIONS_RAW))
          .thenReturn(List.of());
      when(documentFieldOps.getDocumentFieldValues("doc2", SchemaFields.ENTITY_LOCATIONS_RAW))
          .thenReturn(List.of("New York"));

      when(disambiguationService.processBatch(any(Map.class))).thenReturn(4);

      DisambiguationBackfillOps.processDisambiguationBackfill(context());

      ArgumentCaptor<Map<String, List<String>>> captor = ArgumentCaptor.forClass(Map.class);
      verify(disambiguationService).processBatch(captor.capture());

      Map<String, List<String>> mentions = captor.getValue();
      assertTrue(mentions.containsKey("PERSON"), "Should have PERSON mentions");
      assertTrue(
          mentions.get("PERSON").containsAll(List.of("John Smith", "Jane Doe")),
          "Should contain both person names");
      assertTrue(mentions.containsKey("ORGANIZATION"), "Should have ORGANIZATION mentions");
      assertTrue(
          mentions.get("ORGANIZATION").contains("Acme Corp"), "Should contain org name");
      assertTrue(mentions.containsKey("LOCATION"), "Should have LOCATION mentions");
      assertTrue(mentions.get("LOCATION").contains("New York"), "Should contain location name");
    }

    @Test
    @DisplayName("interrupted when user is active")
    void interruptedWhenUserActive() {
      when(disambiguationService.isAvailable()).thenReturn(true);
      when(documentFieldOps.queryDocIdsByField(
              eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_COMPLETED), anyInt()))
          .thenReturn(List.of("doc1", "doc2"));
      when(signalBus.isUserActive()).thenReturn(true);

      DisambiguationBackfillOps.processDisambiguationBackfill(context());

      // Should not read any document fields because user is active
      verify(documentFieldOps, never()).getDocumentFieldValues(anyString(), anyString());
    }
  }
}
