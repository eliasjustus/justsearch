/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;
import java.util.Objects;

/**
 * Paging cursor DTO.
 *
 * <p>Stability: evolving
 */
public record Cursor(String mode, String token, Long expiresAtEpochMs, Map<String, Object> extras) {

  public Cursor {
    if (mode == null || mode.isBlank()) {
      throw new IllegalArgumentException("cursor mode must be non-blank");
    }
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("cursor token must be non-blank");
    }
    extras = extras == null ? Map.of() : Map.copyOf(extras);
  }

  public static Cursor legacy(String token) {
    return new Cursor("legacy", Objects.requireNonNull(token, "token"), null, Map.of());
  }

  @JsonIgnore
  public boolean isLegacy() {
    return "legacy".equals(mode);
  }
}
