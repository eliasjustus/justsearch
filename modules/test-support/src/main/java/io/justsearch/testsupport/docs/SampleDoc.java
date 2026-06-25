/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.testsupport.docs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable representation of a deterministic sample document used by MiniIndexFixture runs. */
public record SampleDoc(
    String id,
    String title,
    String body,
    Map<String, List<String>> facets,
    Map<String, Object> metadata,
    List<Double> embedding) {

  public SampleDoc {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("Document id must not be blank");
    }
    title = Objects.requireNonNullElse(title, "");
    body = Objects.requireNonNullElse(body, "");
    facets = wrapFacets(facets);
    metadata = wrapMetadata(metadata);
    embedding = wrapEmbedding(embedding);
  }

  private static Map<String, List<String>> wrapFacets(Map<String, List<String>> input) {
    if (input == null || input.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    input.forEach(
        (key, values) -> {
          if (key != null && values != null) {
            copy.put(key, List.copyOf(values));
          }
        });
    return Collections.unmodifiableMap(copy);
  }

  private static Map<String, Object> wrapMetadata(Map<String, Object> input) {
    if (input == null || input.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    copy.putAll(input);
    return Collections.unmodifiableMap(copy);
  }

  private static List<Double> wrapEmbedding(List<Double> input) {
    if (input == null || input.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(new ArrayList<>(input));
  }
}
