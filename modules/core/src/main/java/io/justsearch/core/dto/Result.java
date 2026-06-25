/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Search result DTO.
 *
 * <p>Stability: stable
 */
public record Result(
    List<Hit> hits,
    Map<String, Map<String, Integer>> facets,
    Cursor cursor,
    Map<String, Object> metadata) {

  public Result {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  public static record Hit(
      String doc_id,
      double score,
      Map<String, List<String>> highlights
  ) {}
}
