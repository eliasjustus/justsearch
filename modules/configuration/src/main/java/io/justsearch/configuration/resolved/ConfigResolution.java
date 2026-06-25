/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import java.util.List;
import java.util.Objects;

/**
 * Source trace for a single resolved configuration value.
 *
 * <p>Records which source won and all sources that were considered, in descending ordinal order.
 * Modeled on SmallRye {@code ConfigValue} and HOCON {@code ConfigOrigin}. Queryable at runtime via
 * {@link ResolvedConfig#resolution(String)}.
 *
 * @param key config key (e.g., "justsearch.data.dir")
 * @param value resolved value, or null if no source provided a value
 * @param sourceName winning source name (e.g., "env_var")
 * @param sourceOrdinal winning source ordinal (e.g., 400)
 * @param sourceDetail winning source detail (e.g., "JUSTSEARCH_DATA_DIR")
 * @param considered all sources checked, in descending ordinal order
 */
public record ConfigResolution(
    String key,
    String value,
    String sourceName,
    int sourceOrdinal,
    String sourceDetail,
    List<SourceCandidate> considered) {

  public ConfigResolution {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(sourceName, "sourceName");
    considered = considered != null ? List.copyOf(considered) : List.of();
  }

  /** Returns true if a value was resolved from any source. */
  public boolean isResolved() {
    return value != null;
  }

  /** Returns a summary string suitable for INFO logging. */
  public String toLogString() {
    if (value == null) {
      return key + "=<unset>";
    }
    String detail = sourceDetail != null ? ":" + sourceDetail : "";
    return key + "=" + value + " (" + sourceName + detail + ", ordinal=" + sourceOrdinal + ")";
  }
}
