/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.core.util.TokenEstimation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forwards prior conversation messages from the FE thread into the LLM context.
 *
 * <p>Reads an optional {@code context} array from the request body — a list of
 * {@code {role, content}} message objects representing prior turns in the unified
 * chat surface. Returns empty if the key is absent, enabling zero-impact on shapes
 * that don't send it.
 *
 * <p>Token-capped: keeps the most recent messages that fit within
 * {@link #MAX_CONTEXT_TOKENS} (~25% of a conservative 8K context window).
 * Truncates from oldest first to preserve recency.
 */
public final class ExternalContextInjector implements ContextInjector {

  public static final String ID = "core.external-context";
  public static final ExternalContextInjector INSTANCE = new ExternalContextInjector();

  private static final int MAX_CONTEXT_TOKENS = 1000;

  private ExternalContextInjector() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Object raw = ctx.requestBody().get("context");
    if (!(raw instanceof List<?> list) || list.isEmpty()) {
      return InjectorResult.empty();
    }

    List<Map<String, Object>> parsed = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        Object role = map.get("role");
        Object content = map.get("content");
        if (role instanceof String r && content instanceof String c && !c.isBlank()) {
          Map<String, Object> msg = new LinkedHashMap<>();
          msg.put("role", r);
          msg.put("content", c);
          parsed.add(msg);
        }
      }
    }

    if (parsed.isEmpty()) {
      return InjectorResult.empty();
    }

    // Keep most recent messages that fit within the token budget.
    // Walk backwards from newest, accumulating until budget is exhausted.
    List<Map<String, Object>> kept = new ArrayList<>();
    int tokensUsed = 0;
    for (int i = parsed.size() - 1; i >= 0; i--) {
      String content = (String) parsed.get(i).get("content");
      int msgTokens = TokenEstimation.estimateTokens(content);
      if (tokensUsed + msgTokens > MAX_CONTEXT_TOKENS && !kept.isEmpty()) {
        break;
      }
      kept.add(0, parsed.get(i));
      tokensUsed += msgTokens;
    }

    return InjectorResult.messagesOnly(kept);
  }
}
