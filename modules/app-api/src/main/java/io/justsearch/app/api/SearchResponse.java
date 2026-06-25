/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Search response (app-api stable DTO).
 */
public record SearchResponse(
    List<Hit> hits,
    Map<String, Map<String, Integer>> facets,
    Cursor cursor,
    Map<String, Object> metadata) {

  public SearchResponse {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public static record Hit(
      String doc_id,
      double score,
      Map<String, List<String>> highlights
  ) {}

  public static record Cursor(String mode, String token, Long expiresAtEpochMs, Map<String, Object> extras) {
    public Cursor {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(token, "token");
      extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
  }
}
