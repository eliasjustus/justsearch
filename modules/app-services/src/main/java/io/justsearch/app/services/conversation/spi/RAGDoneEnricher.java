/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.QualitySignals;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-hoc {@link StreamConsumer} that enriches the substrate-emitted {@code done} payload
 * for the RAG-ask shape.
 *
 * <p>Per tempdoc 491 §C3: contributes {@code fileCount}, {@code usedRag}, {@code chunksUsed},
 * {@code chunksFound}, {@code citations} alongside the substrate defaults
 * ({@code finalResponse}, {@code iterationsUsed}). Reads from
 * {@link ConversationContext#attributes} where {@link RAGContext} stored the authoritative
 * retrieval metadata.
 */
public final class RAGDoneEnricher implements StreamConsumer {

  public static final String ID = "core.rag-done-enricher";

  public static final RAGDoneEnricher INSTANCE = new RAGDoneEnricher();

  private RAGDoneEnricher() {}

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
    Object fileCount = ctx.attributes().get(RAGContext.ATTR_FILE_COUNT);
    if (fileCount != null) {
      entries.put("fileCount", fileCount);
    }
    Object usedRag = ctx.attributes().get(RAGContext.ATTR_USED_RAG);
    if (usedRag != null) {
      entries.put("usedRag", usedRag);
    }
    Object chunksUsed = ctx.attributes().get(RAGContext.ATTR_CHUNKS_USED);
    if (chunksUsed != null) {
      entries.put("chunksUsed", chunksUsed);
    }
    Object chunksFound = ctx.attributes().get(RAGContext.ATTR_CHUNKS_FOUND);
    if (chunksFound != null) {
      entries.put("chunksFound", chunksFound);
    }
    @SuppressWarnings("unchecked")
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    if (citations != null && !citations.isEmpty()) {
      List<Map<String, Object>> projected = new ArrayList<>(citations.size());
      for (ContextCitation c : citations) {
        projected.add(toCitationMap(c));
      }
      entries.put("citations", List.copyOf(projected));
    } else {
      entries.put("citations", List.of());
    }
    // Tempdoc 561 P-A/P-B — the producer-owned calibration for the answer's evidence. Projected from
    // the CRAG QualitySignals the retrieval producer computed; the engine persists it WITH the
    // assistant turn so the thread renders the producer's confidence FROM the record (not re-derived).
    Object quality = ctx.attributes().get(RAGContext.ATTR_QUALITY);
    if (quality instanceof QualitySignals qs) {
      entries.put("calibration", toCalibrationMap(qs));
    }
    return StreamConsumerResult.donePayloadOnly(entries);
  }

  /** Project the producer's {@link QualitySignals} into the persisted/wire calibration map. */
  static Map<String, Object> toCalibrationMap(QualitySignals qs) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("bestChunkScore", qs.bestChunkScore());
    m.put("scoreGap", qs.scoreGap());
    m.put("retrievalCoverage", qs.retrievalCoverage());
    m.put("chunksConsidered", qs.chunksConsidered());
    m.put("chunksIncluded", qs.chunksIncluded());
    return Map.copyOf(m);
  }

  static Map<String, Object> toCitationMap(ContextCitation c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("parentDocId", c.parentDocId());
    m.put("chunkIndex", c.chunkIndex());
    m.put("chunkTotal", c.chunkTotal());
    m.put("startChar", c.startChar());
    m.put("endChar", c.endChar());
    m.put("score", c.score());
    m.put("excerpt", c.excerpt());
    m.put("startLine", c.startLine());
    m.put("endLine", c.endLine());
    m.put("headingText", c.headingText());
    m.put("headingLevel", c.headingLevel());
    return Map.copyOf(m);
  }
}
