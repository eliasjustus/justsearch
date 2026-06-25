/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Search query DTO.
 *
 * <p>Stability: stable
 */
public record Query(
    int limit,
    int offset,
    Boolean highlight,
    Filters filters,
    List<String> sort,
    List<Clause> clauses,
    Cursor cursor,
    Map<String, Object> context) {

  public Query {
    context = context == null ? Map.of() : Map.copyOf(context);
  }

  public Query(
      int limit,
      int offset,
      Boolean highlight,
      Filters filters,
      List<String> sort,
      List<Clause> clauses,
      Cursor cursor) {
    this(limit, offset, highlight, filters, sort, clauses, cursor, Map.of());
  }

  public static record Filters(
      String mime,
      String language,
      TimeRange timeRange
  ) {}

  public static record TimeRange(
      Long fromMs,
      Long toMs
  ) {}

  public static record Clause(
      String type,
      String field,
      Object value,
      List<String> tokens
  ) {}

  public Query withContext(Map<String, Object> newContext) {
    return new Query(limit, offset, highlight, filters, sort, clauses, cursor, newContext);
  }
}
