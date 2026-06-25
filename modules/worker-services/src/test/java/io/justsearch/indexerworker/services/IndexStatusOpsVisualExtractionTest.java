package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.VisualExtractionStatus;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class IndexStatusOpsVisualExtractionTest {

  @Test
  void baselineCountIncludesLegacyPendingRowsWithoutDemandKind() throws Exception {
    IndexCountOps counts = mock(IndexCountOps.class);
    when(counts.countByField(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING))
        .thenReturn(5);
    when(counts.countByFields(anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Map<String, String> filters = invocation.getArgument(0);
              if (SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT.equals(
                  filters.get(SchemaFields.VDU_DEMAND_KIND))) {
                return 2;
              }
              return 0;
            });
    IndexStatusOps ops =
        new IndexStatusOps(
            null,
            null,
            counts,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L);

    VisualExtractionStatus visual = buildVisualExtraction(ops);

    assertEquals(3, visual.getVisualTextNeededCount());
    assertEquals(2, visual.getVisualEnrichmentNeededCount());
  }

  private static VisualExtractionStatus buildVisualExtraction(IndexStatusOps ops) throws Exception {
    Method method = IndexStatusOps.class.getDeclaredMethod("buildVisualExtraction");
    method.setAccessible(true);
    return (VisualExtractionStatus) method.invoke(ops);
  }
}
