/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.app.services.conversation.CoreConversationShapeCatalog;
import io.justsearch.app.services.conversation.spi.StreamingCitationMatcher;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 §5.11 — a shape DECLARES {@code rag.citation_matches} exactly when it registers the
 * matcher that produces it.
 *
 * <p>{@code ConversationEngine} forwards consumer events to the sink WITHOUT filtering against the
 * declared schema, so an undeclared event still reaches the browser. That is why the two can drift
 * silently, and why summarize shipped for a while producing an event its manifest denied. This
 * pins the biconditional per shape rather than growing a schema-enforcement feature inside a
 * citation slice (§6, non-goals).
 */
@DisplayName("Conversation shapes — the citation-matches declaration matches the producer")
final class CitationMatchesDeclarationTest {

  private static final String EVENT = "rag.citation_matches";

  @Test
  @DisplayName("every shape declares the event iff it registers the matcher")
  void declarationFollowsTheProducer() {
    for (ConversationShape shape : CoreConversationShapeCatalog.catalog().definitions()) {
      boolean produces = shape.streamConsumerIds().contains(StreamingCitationMatcher.ID);
      boolean declares = names(shape.eventSchema()).contains(EVENT);
      assertEquals(
          produces,
          declares,
          shape.id().value()
              + ": registers the matcher = "
              + produces
              + " but declares "
              + EVENT
              + " = "
              + declares
              + ". A shape whose manifest denies an event it emits cannot be reasoned about by a"
              + " consumer that trusts the manifest.");
    }
  }

  @Test
  @DisplayName("summarize is one of them — the declaration S2/S3 added is not incidental")
  void summarizeDeclaresIt() {
    ConversationShape summarize = SummarizeShape.definition();
    assertEquals(
        true,
        summarize.streamConsumerIds().contains(StreamingCitationMatcher.ID),
        "the matcher has always been a registered consumer here");
    assertEquals(
        true,
        names(summarize.eventSchema()).contains(EVENT),
        "and the manifest now says so");
  }

  private static List<String> names(List<EventDescriptor> schema) {
    return schema.stream().map(EventDescriptor::name).toList();
  }
}
