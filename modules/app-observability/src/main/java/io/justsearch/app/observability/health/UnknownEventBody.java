/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import java.util.Objects;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.node.MissingNode;

/**
 * Forward-compatibility body — Jackson default subtype for unknown {@code kind}.
 *
 * <p>Per tempdoc 430 §B.F: when a wire payload arrives with a {@code kind} value the
 * current binary doesn't know about (e.g., a future {@code "stamped-summary"} variant),
 * Jackson's {@link com.fasterxml.jackson.annotation.JsonTypeInfo#defaultImpl()} routes
 * it here. The original kind string is preserved on {@link #kind()} for diagnostic; the
 * raw JSON payload is captured on {@link #raw()}. The FE renders this with a generic
 * placeholder ({@code "unknown event kind: {kind}"}); backend logs at WARN.
 *
 * <p>This replaces rev-2's {@code agent-session-default} catalog entry — forward-compat
 * is a protocol concern, not a catalog one. Mirrors LSP/CloudEvents conventions where
 * unknown extension fields pass through opaquely.
 *
 * <p>Uses a custom {@link Deserializer} because Jackson's
 * {@code AsPropertyTypeDeserializer} consumes the {@code kind} discriminator before
 * calling the subtype constructor — without the custom path, {@code kind} would arrive
 * {@code null}. The deserializer reads the entire body subtree as a {@link JsonNode}
 * and extracts both {@code kind} and the full payload.
 */
@JsonDeserialize(using = UnknownEventBody.Deserializer.class)
public record UnknownEventBody(String kind, JsonNode raw) implements HealthEventBody {

  public UnknownEventBody {
    Objects.requireNonNull(kind, "kind");
    raw = raw == null ? MissingNode.getInstance() : raw;
  }

  /** Captures the full body subtree so {@code kind} survives discriminator consumption. */
  public static final class Deserializer extends ValueDeserializer<UnknownEventBody> {
    @Override
    public UnknownEventBody deserialize(JsonParser parser, DeserializationContext ctxt) {
      JsonNode node = ctxt.readTree(parser);
      String kind = node.path("kind").asString("unknown");
      return new UnknownEventBody(kind, node);
    }
  }
}
