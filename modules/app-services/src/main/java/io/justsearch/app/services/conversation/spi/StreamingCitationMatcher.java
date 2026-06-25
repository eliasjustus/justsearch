/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streaming citation matcher — emits per-sentence citation deltas during the LLM stream,
 * then runs authoritative embedding-based matching at stream end.
 *
 * <p>Replaces the post-hoc-only {@link CitationMatcher} with a two-phase approach:
 * <ol>
 *   <li><b>During stream</b> ({@link #onChunk}): accumulates streamed text, detects sentence
 *       boundaries via {@link BreakIterator}, matches each completed sentence against retrieved
 *       citations using fast lexical word-overlap, and emits {@code rag.citation_delta} SSE
 *       events per sentence. These are "draft" attributions — fast but approximate.</li>
 *   <li><b>At stream end</b> ({@link #onDone}): runs the existing Worker-side embedding
 *       similarity matching via {@link DocumentService#matchCitations} (the authoritative
 *       path) and emits the final {@code rag.citation_matches} event for backward compat.</li>
 * </ol>
 *
 * <p>Slice 493 — citation substrate. Uses the existing {@link StreamConsumer} SPI
 * (no new SPI category needed — the interface already supports mid-stream emission
 * via {@code onChunk}, confirmed by {@code ConversationEngine.streamLlm()} lines 338-348).
 */
public final class StreamingCitationMatcher implements StreamConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamingCitationMatcher.class);

  public static final String ID = "core.streaming-citation-matcher";

  static final Duration MATCH_TIMEOUT = Duration.ofSeconds(5);
  // Tempdoc 565 §15.A — the ONE answer↔source citation-grounding cutoff (shared with the agent path).
  static final double DEFAULT_THRESHOLD = DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD;
  static final int MIN_WORD_LENGTH = 4;
  static final int MIN_WORD_HITS = 2;

  private final DocumentService documents;
  private final Duration timeout;
  private final double threshold;

  public StreamingCitationMatcher(DocumentService documents) {
    this(documents, MATCH_TIMEOUT, DEFAULT_THRESHOLD);
  }

  public StreamingCitationMatcher(
      DocumentService documents, Duration timeout, double threshold) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.threshold = Math.max(0.01, Math.min(1.0, threshold));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    if (chunkText == null || chunkText.isEmpty()) {
      return StreamConsumerResult.empty();
    }
    @SuppressWarnings("unchecked")
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    if (citations == null || citations.isEmpty()) {
      return StreamConsumerResult.empty();
    }

    StringBuilder buffer = getOrCreateBuffer(ctx);
    buffer.append(chunkText);

    int prevSentenceCount = getSentenceCount(ctx);
    List<String> newSentences = extractCompleteSentences(buffer);
    if (newSentences.isEmpty()) {
      return StreamConsumerResult.empty();
    }

    List<SseEvent> events = new ArrayList<>();
    for (int i = 0; i < newSentences.size(); i++) {
      String sentence = newSentences.get(i);
      int sentenceIndex = prevSentenceCount + i;
      List<Map<String, Object>> matched = matchSentenceLexical(sentence, citations);
      if (!matched.isEmpty()) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sentenceIndex", sentenceIndex);
        payload.put("sentenceText", sentence);
        payload.put("citations", matched);
        events.add(new SseEvent("rag.citation_delta", Map.copyOf(payload)));
      }
    }
    setSentenceCount(ctx, prevSentenceCount + newSentences.size());

    return events.isEmpty()
        ? StreamConsumerResult.empty()
        : StreamConsumerResult.eventsOnly(events);
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    if (fullText == null || fullText.isBlank()) {
      return StreamConsumerResult.empty();
    }
    @SuppressWarnings("unchecked")
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    if (citations == null || citations.isEmpty()) {
      return StreamConsumerResult.empty();
    }
    try {
      CitationMatchResult result =
          documents
              .matchCitations(fullText, citations, threshold)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (result == null || result.matches().isEmpty()) {
        return StreamConsumerResult.empty();
      }
      Map<String, Object> payload = toCitationMatchPayload(result);
      // Tempdoc 561 P-A (evidence non-divergence): emit the live SSE event AND contribute the matches
      // to the done-payload, so ConversationEngine persists the per-claim grounding ON the record. A
      // reloaded conversation then renders the same per-claim marks FROM the record (renderUnifiedItem),
      // not only the live path — the evidence record is total, the two render paths cannot diverge.
      return new StreamConsumerResult(
          List.of(new SseEvent("rag.citation_matches", payload)),
          List.of(),
          List.of(),
          Map.of("claimMatches", payload));
    } catch (Exception e) {
      LOG.debug("Authoritative citation matching failed (non-fatal): {}", e.getMessage());
      return StreamConsumerResult.empty();
    }
  }

  // -- Sentence segmentation --

  private static final String BUFFER_KEY = "streaming.citation.buffer";
  private static final String SENTENCE_COUNT_KEY = "streaming.citation.sentenceCount";

  private static StringBuilder getOrCreateBuffer(ConversationContext ctx) {
    Object existing = ctx.attributes().get(BUFFER_KEY);
    if (existing instanceof StringBuilder sb) {
      return sb;
    }
    StringBuilder sb = new StringBuilder();
    ctx.attributes().put(BUFFER_KEY, sb);
    return sb;
  }

  private static int getSentenceCount(ConversationContext ctx) {
    Object val = ctx.attributes().get(SENTENCE_COUNT_KEY);
    return val instanceof Integer i ? i : 0;
  }

  private static void setSentenceCount(ConversationContext ctx, int count) {
    ctx.attributes().put(SENTENCE_COUNT_KEY, count);
  }

  /**
   * Extracts complete sentences from the buffer, leaving any incomplete trailing
   * text in the buffer for the next chunk.
   */
  static List<String> extractCompleteSentences(StringBuilder buffer) {
    String text = buffer.toString();
    if (text.isBlank()) {
      return List.of();
    }

    BreakIterator bi = BreakIterator.getSentenceInstance(Locale.ENGLISH);
    bi.setText(text);

    List<String> sentences = new ArrayList<>();
    int start = bi.first();
    int end = bi.next();
    int lastCompleteEnd = 0;

    while (end != BreakIterator.DONE) {
      int nextEnd = bi.next();
      if (nextEnd == BreakIterator.DONE) {
        // Last segment — might be incomplete (no following sentence boundary).
        // Only include if it ends with sentence-terminal punctuation.
        String candidate = text.substring(start, end).trim();
        if (!candidate.isEmpty() && endsWithTerminal(candidate)) {
          sentences.add(candidate);
          lastCompleteEnd = end;
        }
        break;
      }
      String sentence = text.substring(start, end).trim();
      if (!sentence.isEmpty()) {
        sentences.add(sentence);
      }
      lastCompleteEnd = end;
      start = end;
      end = nextEnd;
    }

    if (sentences.isEmpty() && lastCompleteEnd == 0) {
      return List.of();
    }

    // Remove consumed text from buffer, keep the incomplete tail.
    buffer.delete(0, lastCompleteEnd);
    return sentences;
  }

  private static boolean endsWithTerminal(String s) {
    char last = s.charAt(s.length() - 1);
    return last == '.' || last == '!' || last == '?' || last == '\n';
  }

  // -- Lexical matching --

  static List<Map<String, Object>> matchSentenceLexical(
      String sentence, List<ContextCitation> citations) {
    String lower = sentence.toLowerCase(Locale.ROOT);
    List<Map<String, Object>> matched = new ArrayList<>();

    for (int i = 0; i < citations.size(); i++) {
      ContextCitation c = citations.get(i);
      String excerpt = c.excerpt();
      if (excerpt == null || excerpt.isBlank()) {
        continue;
      }
      String[] words = excerpt.toLowerCase(Locale.ROOT).split("\\s+");
      int hits = 0;
      int significantWords = 0;
      for (String w : words) {
        if (w.length() >= MIN_WORD_LENGTH) {
          significantWords++;
          if (lower.contains(w)) {
            hits++;
          }
        }
      }
      if (significantWords == 0) {
        continue;
      }
      double overlap = (double) hits / significantWords;
      boolean isMatch =
          hits >= MIN_WORD_HITS || (significantWords <= 3 && hits >= 1) || overlap >= 0.5;
      if (isMatch) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("chunkIndex", c.chunkIndex());
        entry.put("parentDocId", c.parentDocId());
        entry.put("score", Math.round(overlap * 100.0) / 100.0);
        matched.add(Map.copyOf(entry));
      }
    }
    return matched;
  }

  // -- Payload formatting (matches legacy CitationMatcher) --

  private static Map<String, Object> toCitationMatchPayload(CitationMatchResult result) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sentencesTotal", result.sentencesTotal());
    out.put("sentencesMatched", result.sentencesMatched());
    out.put("tookMs", result.tookMs());
    List<Map<String, Object>> matches = new ArrayList<>(result.matches().size());
    for (CitationMatchEntry m : result.matches()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("sentenceIndex", m.sentenceIndex());
      entry.put("sentenceText", m.sentenceText());
      entry.put("chunkIndex", m.chunkIndex());
      entry.put("similarity", m.similarity());
      entry.put("parentDocId", m.parentDocId());
      matches.add(Map.copyOf(entry));
    }
    out.put("matches", matches);
    return Map.copyOf(out);
  }
}
