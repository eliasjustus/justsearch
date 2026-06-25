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
import java.util.List;
import java.util.Optional;

/**
 * Slice 496 §3.B — FreeChat shape: plain "talk to the LLM" with persistent
 * thread history. The simplest possible persistent shape:
 *
 * <ul>
 *   <li>ExecutionMode: SUBSTRATE_DRIVEN (engine drives the LLM call)
 *   <li>IterationMode: ONE_SHOT (one LLM call per user message)
 *   <li>PersistenceMode: PERSISTENT (conversation history persists via
 *       ConversationStore)
 *   <li>No PromptContributors (bare system prompt)
 *   <li>No ContextInjectors (user message comes from a
 *       UserMessageInjector that extracts body.message)
 *   <li>No StreamConsumers
 *   <li>SingleHopController (always STOP_SUCCESS)
 * </ul>
 *
 * <p>This is the first non-agent persistent shape — validates the
 * ConversationStore integration at the engine level.
 */
public final class FreeChatShape {

  public static final ConversationShapeRef ID = new ConversationShapeRef("core.free-chat");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.free-chat.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.free-chat.description");

  private static final List<String> EVENT_SCHEMA =
      List.of("chunk", "reasoning_chunk", "done", "error");

  private FreeChatShape() {}

  public static ConversationShape definition() {
    return new ConversationShape(
        ID,
        new Presentation(LABEL_KEY, DESCRIPTION_KEY, Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.PERSISTENT,
        List.of(),
        List.of(ExternalContextInjector.ID, "core.user-prompt"),
        // Tempdoc 561 P-E: the passive learning producer runs on free chat too.
        List.of(io.justsearch.app.services.conversation.spi.MemoryExtractionConsumer.ID),
        "core.single-hop",
        EventDescriptor.namesOnly(EVENT_SCHEMA));
  }
}
