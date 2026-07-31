/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.app.api.DocumentService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 799 §Q — the agent half of the citation-cutoff parity regression.
 *
 * <p>Observes the threshold this resolver actually hands to {@link DocumentService#matchCitations},
 * rather than re-deriving it. Paired with
 * {@code StreamingCitationMatcherTest#configuredZeroResolvesToDefaultNotFloor} on the RAG side:
 * together they pin that one configured value cannot produce two effective cutoffs, which is the
 * assertion whose absence let the divergence ship.
 */
class AgentCitationResolverThresholdTest {

  /** Captures the cutoff the resolver passes down, and returns no matches. */
  private static DocumentService capturingDocs(double[] sink) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitations(
          String answerText, List<ContextCitation> citations, double threshold) {
        sink[0] = threshold;
        return CompletableFuture.completedFuture(null);
      }
    };
  }

  private static List<AgentEvent.AgentSource> oneSource() {
    return List.of(
        new AgentEvent.AgentSource(
            "doc-1", 0, "/tmp/doc-1.md", "Doc One", "the grass is green", 1, 2, ""));
  }

  @Test
  @DisplayName("799 Q: a configured 0 resolves to the DEFAULT, matching the RAG path exactly")
  void configuredZeroResolvesToDefault() {
    double[] seen = {-1.0};
    new AgentCitationResolver(capturingDocs(seen), 0.0).resolve("The grass is green.", oneSource());
    assertEquals(DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD, seen[0], 1e-9);
    assertNotEquals(
        0.01, seen[0], 1e-9, "0.01 was the RAG path's old floor — the two must not diverge again");
  }

  @Test
  @DisplayName("799 Q: an in-range configured cutoff passes through untouched")
  void inRangePassesThrough() {
    double[] seen = {-1.0};
    new AgentCitationResolver(capturingDocs(seen), 0.83).resolve("The grass is green.", oneSource());
    assertEquals(0.83, seen[0], 1e-9);
  }
}
