/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Post-hoc {@link StreamConsumer} that enriches the substrate-emitted {@code done} payload
 * for {@code SummarizeShape} with legacy-compatible fields.
 *
 * <p>Per tempdoc 491 §C2.1: the substrate-default {@code done} payload is
 * {@code {finalResponse, iterationsUsed}}. The legacy {@code /api/summarize/stream} done
 * payload had {@code {docId, summary, hierarchical, usedDocumentStore, finishReason}}. This
 * consumer contributes the legacy field set so FE consumers see both — substrate defaults
 * remain (they cannot be overridden by enrichers; engine writes them last).
 *
 * <p>Stateless singleton; relies on the request body for {@code docId} and the final
 * iteration's accumulated text for {@code summary}.
 */
public final class SummaryDoneEnricher implements StreamConsumer {

  /** Stable id used by {@code ConversationShape.streamConsumerIds}. */
  public static final String ID = "core.summary-done-enricher";

  public static final SummaryDoneEnricher INSTANCE = new SummaryDoneEnricher();

  private SummaryDoneEnricher() {}

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
    Object docId = ctx.requestBody().get("docId");
    if (docId != null) {
      entries.put("docId", docId);
    }
    entries.put("summary", fullText == null ? "" : fullText);
    entries.put("hierarchical", false);
    entries.put("fullCoverage", true);
    return StreamConsumerResult.donePayloadOnly(entries);
  }
}
