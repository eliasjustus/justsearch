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
import io.justsearch.app.services.conversation.spi.ExternalContextInjector;
import io.justsearch.app.services.conversation.spi.SelectionContextInjector;
import io.justsearch.app.services.conversation.spi.ValidatingController;
import io.justsearch.app.services.conversation.spi.ValidationConsumer;
import java.util.List;
import java.util.Optional;

/**
 * Slice 496 §3.C — Extract shape: structured output with JSON schema
 * constraint + retry.
 *
 * <p>First SUBSTRATE_DRIVEN + WITHIN_TURN_ITERATION shape. The engine drives
 * a retry loop: the LLM produces output → ValidationConsumer checks if it's
 * valid JSON → ValidatingController decides CONTINUE (retry) or STOP_SUCCESS
 * (done). On retry, the consumer injects a correction-prompt message delta so
 * the LLM sees "your output was invalid, please try again with valid JSON."
 *
 * <p>Request body: {@code {prompt: "Extract entities from...", schema: "{...}"}}.
 * The schema is passed through the context's requestBody; the consumer reads
 * it for the correction prompt.
 */
public final class ExtractShape {

  public static final ConversationShapeRef ID = new ConversationShapeRef("core.extract");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.extract.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.extract.description");

  /**
   * {@code rag.citations} is declared because {@link SelectionContextInjector} genuinely emits it
   * for the item / text-range / citation arms — it names the document the extraction was taken
   * from. Suppressing it would leave a structured answer with no visible provenance, which is the
   * opposite of what a grounded extraction should offer.
   */
  private static final List<String> EVENT_SCHEMA =
      List.of("chunk", "reasoning_chunk", "rag.citations", "done", "error");

  private ExtractShape() {}

  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.WITHIN_TURN_ITERATION,
        PersistenceMode.EPHEMERAL,
        List.of(),
        // Injected messages compose in declaration order, before the user message: prior chat
        // turns, then the document the user picked, then the extraction instruction. Without
        // SelectionContextInjector the shape saw no document at all — the schema constraint was
        // honoured against the prompt and chat history alone, i.e. confabulated structured output.
        List.of(ExternalContextInjector.ID, SelectionContextInjector.ID, "core.user-prompt"),
        List.of(ValidationConsumer.ID),
        ValidatingController.ID,
        EventDescriptor.namesOnly(EVENT_SCHEMA));
  }
}
