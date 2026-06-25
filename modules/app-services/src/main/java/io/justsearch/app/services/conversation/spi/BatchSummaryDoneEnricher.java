/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-hoc {@link StreamConsumer} that enriches the substrate-emitted {@code done} payload
 * for {@code BatchSummarizeShape}.
 *
 * <p>Per tempdoc 491 §C2.2: contributes {@code fileCount}, {@code docIds}, and
 * {@code fullCoverage:true} alongside the substrate defaults
 * ({@code finalResponse}, {@code iterationsUsed}). Reads from
 * {@code ConversationContext.attributes()} where {@link BatchDocAccess} stored the
 * authoritative docId list (after empty-content filtering).
 */
public final class BatchSummaryDoneEnricher implements StreamConsumer {

  /** Stable id used by {@code ConversationShape.streamConsumerIds}. */
  public static final String ID = "core.batch-summary-done-enricher";

  public static final BatchSummaryDoneEnricher INSTANCE = new BatchSummaryDoneEnricher();

  private BatchSummaryDoneEnricher() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    return StreamConsumerResult.empty();
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    Map<String, Object> entries = new LinkedHashMap<>();
    Object fileCount = ctx.attributes().get("batch.fileCount");
    if (fileCount != null) {
      entries.put("fileCount", fileCount);
    }
    Object docIds = ctx.attributes().get("batch.docIds");
    if (docIds instanceof List<?> list) {
      entries.put("docIds", List.copyOf(list));
    }
    entries.put("fullCoverage", true);
    return StreamConsumerResult.donePayloadOnly(entries);
  }
}
