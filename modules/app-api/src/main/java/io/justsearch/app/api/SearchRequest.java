/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;
import java.util.Map;

/**
 * Search request (app-api stable DTO).
 */
public record SearchRequest(
    int limit,
    int offset,
    Boolean highlight,
    Filters filters,
    List<String> sort,
    List<Clause> clauses,
    Cursor cursor,
    Context context) {

  public SearchRequest {
    context = context == null ? Context.empty() : context;
  }

  public SearchRequest(
      int limit,
      int offset,
      Boolean highlight,
      Filters filters,
      List<String> sort,
      List<Clause> clauses,
      Cursor cursor) {
    this(limit, offset, highlight, filters, sort, clauses, cursor, Context.empty());
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

  public static record Cursor(String mode, String token, Long expiresAtEpochMs, Map<String, Object> extras) {
    public Cursor {
      // Cursor is optional - if provided, token must be valid
      // This allows null cursor in SearchRequest without triggering validation
      if (token != null && token.isBlank()) {
        throw new IllegalArgumentException("cursor token must be non-blank when provided");
      }
      mode = (mode == null || mode.isBlank()) ? "legacy" : mode;
      extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /**
     * Returns true if this cursor has a valid token.
     */
    public boolean isValid() {
      return token != null && !token.isBlank();
    }
  }

  public SearchRequest withContext(Context context) {
    return new SearchRequest(limit, offset, highlight, filters, sort, clauses, cursor, context);
  }

  public static record Context(Map<String, Object> translatorMeta) {
    public Context {
      translatorMeta = translatorMeta == null ? Map.of() : Map.copyOf(translatorMeta);
    }

    public static Context empty() {
      return new Context(Map.of());
    }

    public Context withTranslatorMeta(Map<String, Object> meta) {
      if (meta == null || meta.isEmpty()) {
        return this;
      }
      return new Context(meta);
    }
  }
}
