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
import io.justsearch.app.services.conversation.spi.URLEmissionGrammar;
import io.justsearch.app.services.conversation.spi.URLExtractor;
import io.justsearch.app.services.conversation.spi.UserPromptInjector;
import java.util.List;
import java.util.Optional;

/**
 * The {@link ConversationShape} that hosts LLM-emitted URL routing — the slice 487
 * URL-emission consumer feature on the slice 491 chat substrate.
 *
 * <p>Per tempdoc 487 §6 step 13: a trivial manifest that directly composes
 * the URL-emission SPI pair — {@link URLEmissionGrammar} (slice 487 Phase
 * 2.2 prompt contributor) + {@link URLExtractor} (slice 487 Phase 2.2
 * stream consumer). The tempdoc named these together as
 * "{@code URLEmissionCapability}" but no Capability class ships: slice 491
 * ConversationShapes compose SPIs by id directly, and no Capability
 * precedent exists in the codebase. The composition lives inline in
 * {@link #definition()}. The user types in chat; Qwen3.5-9B emits a
 * Markdown response containing zero or more {@code justsearch://} URLs;
 * the {@code URLExtractor} parses them, dispatches each via
 * {@code BackendIntentRouter}, and emits namespaced SSE events for the
 * FE to consume.
 *
 * <p>Cell: {@link IterationMode#ONE_SHOT} × {@link PersistenceMode#EPHEMERAL}. No
 * iteration controller branching; one LLM call per request.
 *
 * <p>The empirical anchor: the §3.1 probe verdict (96.8% pass on 31 scenarios,
 * Qwen3.5-9B Q4_K_M, 2026-05-12). The probe ran against llama-server directly; this
 * shape ships the equivalent emission path on the substrate-driven engine. Gate G2
 * (slice 487 Phase 2.5) re-runs the probe through {@code /api/chat/url-emit} to
 * confirm the substrate-driven path preserves the empirical claim.
 */
public final class NavigateChatShape {

  /** Stable {@link ConversationShapeRef} for the URL-emission shape. */
  public static final ConversationShapeRef ID = new ConversationShapeRef("core.navigate-chat");

  public static final I18nKey LABEL_KEY =
      new I18nKey("registry-conversation-shape.navigate-chat.label");

  public static final I18nKey DESCRIPTION_KEY =
      new I18nKey("registry-conversation-shape.navigate-chat.description");

  /**
   * SSE event vocabulary — the standard substrate-emitted events plus the three
   * URL-extractor-namespaced events the {@link URLExtractor} emits per URL.
   */
  private static final List<String> EVENT_SCHEMA =
      List.of(
          "chunk",
          "reasoning_chunk",
          "done",
          "error",
          "navigate.url_extracted",
          "navigate.url_dispatched",
          "navigate.url_rejected",
          "intent.resolution");

  private NavigateChatShape() {}

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
        List.of(URLEmissionGrammar.ID),
        List.of(UserPromptInjector.ID),
        List.of(URLExtractor.ID),
        SingleHopController.ID,
        EventDescriptor.namesOnly(EVENT_SCHEMA));
  }
}
