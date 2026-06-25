/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that renders deterministic, schema-shaped JSON payloads. Used by the deterministic backend
 * and as a fallback path when the llama backend degrades.
 */
public final class IntentJsonTemplate {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{Nd}]+");
  private IntentJsonTemplate() {}

  public static String render(String text, Locale locale, boolean degraded, String reason)
      throws IOException {
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    root.put("limit", 10);
    root.put("offset", 0);
    root.put("highlight", true);

    if (locale != null) {
      ObjectNode filters = root.putObject("filters");
      filters.put("language", locale.getLanguage());
    }

    ArrayNode clauses = root.withArray("clauses");
    ObjectNode clause = clauses.addObject();
    clause.put("type", degraded ? "fallback" : "text");
    clause.put("field", "content_all");
    clause.put("value", text == null ? "" : text);

    ArrayNode tokensNode = clause.putArray("tokens");
    for (String token : extractTokens(text)) {
      tokensNode.add(token);
    }

    return OBJECT_MAPPER.writeValueAsString(root);
  }

  private static List<String> extractTokens(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> tokens = new ArrayList<>();
    Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      tokens.add(matcher.group());
    }
    return tokens;
  }
}
