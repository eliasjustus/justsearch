/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slice 496 §3.C — StreamConsumer that validates the LLM's output against
 * the request's declared JSON schema. Communicates with
 * {@link ValidatingController} via the {@code ConversationContext.attributes()}
 * scratch pad.
 *
 * <p>On {@code onDone}: reads the schema from {@code ctx.requestBody().get("schema")}
 * (a JSON schema string). Attempts to parse the LLM's output as JSON. If parsing
 * fails or the output doesn't look like valid JSON: sets
 * {@code attributes["validation.passed"] = false} and returns a correction-prompt
 * message delta. If valid: sets {@code attributes["validation.passed"] = true}.
 *
 * <p>V1 validation is simple: checks that the output is parseable JSON (starts with
 * { or [). Full JSON Schema validation against the declared schema is a future
 * enhancement — the correction prompt includes the schema so the LLM can self-correct.
 */
public final class ValidationConsumer implements StreamConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(ValidationConsumer.class);

  public static final String ID = "core.validation-consumer";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
    return StreamConsumerResult.empty();
  }

  @Override
  public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
    String schema = (String) ctx.requestBody().getOrDefault("schema", "{}");
    String trimmed = fullText.trim();
    boolean looksLikeJson = (trimmed.startsWith("{") || trimmed.startsWith("["))
        && (trimmed.endsWith("}") || trimmed.endsWith("]"));

    ctx.attributes().put("validation.passed", looksLikeJson);

    if (!looksLikeJson) {
      LOG.debug("ValidationConsumer: output does not look like valid JSON; requesting retry");
      String correction =
          "Your output was not valid JSON. Please respond with ONLY valid JSON matching this schema:\n"
              + schema
              + "\n\nDo not include any explanation or markdown formatting — just the raw JSON object.";
      return new StreamConsumerResult(
          List.of(),
          List.of(),
          List.of(Map.of("role", "user", "content", correction)),
          Map.of());
    }
    return StreamConsumerResult.empty();
  }
}
