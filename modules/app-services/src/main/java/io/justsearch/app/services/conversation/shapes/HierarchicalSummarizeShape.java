/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.shapes;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import java.util.List;
import java.util.Optional;

/**
 * Hierarchical-summarize {@link ConversationShape} (SHAPE_DRIVEN).
 *
 * <p>Per tempdoc 491 §C2.3: hierarchical summarization is a data-driven multi-LLM-call
 * pipeline (split into N sections → N independent section summaries → 1 streaming
 * synthesis). It does not fit substrate-driven execution (each LLM call has fresh content
 * driven by data, not by prior LLM output). Delegates the entire lifecycle to
 * {@code HierarchicalShapeRunner}.
 *
 * <p>Cell: {@link IterationMode#WITHIN_TURN_ITERATION} × {@link PersistenceMode#EPHEMERAL}.
 */
public final class HierarchicalSummarizeShape {

  public static final ConversationShapeRef ID =
      new ConversationShapeRef("core.hierarchical-summarize");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.hierarchical-summarize.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.hierarchical-summarize.description");

  private static final List<String> EVENT_SCHEMA = List.of("progress", "chunk", "reasoning_chunk", "done", "error");

  private HierarchicalSummarizeShape() {}

  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SHAPE_DRIVEN,
        IterationMode.WITHIN_TURN_ITERATION,
        PersistenceMode.EPHEMERAL,
        List.of(),
        List.of(),
        List.of(),
        // SHAPE_DRIVEN shapes don't consult the substrate iteration controller; runner owns
        // the loop. Pass null — the engine tolerates null for SHAPE_DRIVEN shapes.
        null,
        EventDescriptor.namesOnly(EVENT_SCHEMA));
  }
}
