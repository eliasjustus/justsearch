/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.shapes;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.services.conversation.spi.BatchDocAccess;
import io.justsearch.app.services.conversation.spi.BatchSummaryDoneEnricher;
import io.justsearch.app.services.conversation.spi.SummarizationStyle;
import java.util.List;
import java.util.Optional;

/**
 * Multi-document summarize {@link ConversationShape}.
 *
 * <p>Per tempdoc 491 §C2.2: replaces the legacy {@code /api/summarize/batch/stream} endpoint
 * with a substrate-driven shape. {@link BatchDocAccess} fetches the docs and concatenates
 * them with the legacy {@code "--- File: ---"} delimiter; {@link SummarizationStyle}
 * contributes the consistent summarization preamble; {@link BatchSummaryDoneEnricher} adds
 * {@code fileCount}/{@code docIds}/{@code fullCoverage} to the done payload.
 *
 * <p>Cell: {@link IterationMode#ONE_SHOT} × {@link PersistenceMode#EPHEMERAL}.
 */
public final class BatchSummarizeShape {

  /** Stable {@link ConversationShapeRef} for the batch-summarize shape. */
  public static final ConversationShapeRef ID = new ConversationShapeRef("core.batch-summarize");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.batch-summarize.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.batch-summarize.description");

  /** SSE event vocabulary: substrate-defaults plus the injector's {@code progress} event. */
  private static final List<String> EVENT_SCHEMA = List.of("chunk", "reasoning_chunk", "progress", "done", "error");

  private BatchSummarizeShape() {}

  /** Build the {@link ConversationShape} manifest entry. */
  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        List.of(SummarizationStyle.ID),
        List.of(BatchDocAccess.ID),
        List.of(BatchSummaryDoneEnricher.ID),
        SingleHopController.ID,
        EventDescriptor.namesOnly(EVENT_SCHEMA));
  }
}
