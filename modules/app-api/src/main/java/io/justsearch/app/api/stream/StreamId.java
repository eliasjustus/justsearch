/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.stream;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stream identifier for the universal SSE envelope.
 *
 * <p>Per slice 436 §B.3: kind-prefixed slug-cased identifier, format {@code <kind>:<id>}.
 * Three kinds: {@code registry} (catalog-shaped streams like {@code registry:capabilities}),
 * {@code surface} (UI-rendered surfaces like {@code surface:health-events}),
 * {@code system} (system-level streams like {@code system:status}).
 *
 * <p>Serializes as a bare colon-separated string via {@link JsonValue} (e.g.,
 * {@code "registry:capabilities"}). The colon separator matches the spec at
 * {@code archive/source-tempdocs/421-protocols.md} §"Streaming primitive specification"
 * lines 309-323.
 */
public record StreamId(@JsonValue String value) {

  private static final Pattern PATTERN =
      Pattern.compile("^(registry|surface|system):[a-z][a-z0-9-]*$");

  @JsonCreator
  public StreamId {
    Objects.requireNonNull(value, "value");
    if (!PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "StreamId must match " + PATTERN.pattern() + ", got: " + value);
    }
  }

  /** Constructs a {@code registry:<id>} StreamId (catalog-shaped streams). */
  public static StreamId registry(String id) {
    return new StreamId("registry:" + id);
  }

  /** Constructs a {@code surface:<id>} StreamId (UI-rendered surfaces). */
  public static StreamId surface(String id) {
    return new StreamId("surface:" + id);
  }

  /** Constructs a {@code system:<id>} StreamId (system-level streams). */
  public static StreamId system(String id) {
    return new StreamId("system:" + id);
  }

  /** Returns the kind prefix (registry/surface/system). */
  public String kind() {
    int colon = value.indexOf(':');
    return value.substring(0, colon);
  }

  /** Returns the slug (post-colon identifier). */
  public String id() {
    int colon = value.indexOf(':');
    return value.substring(colon + 1);
  }
}
