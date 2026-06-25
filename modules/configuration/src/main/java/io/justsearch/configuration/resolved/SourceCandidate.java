/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import java.util.Objects;

/**
 * A single source candidate considered during config resolution.
 *
 * <p>Part of the source-tracing system modeled on SmallRye {@code ConfigValue} and HOCON {@code
 * ConfigOrigin}. Every config key resolution records all candidates checked, enabling runtime
 * diagnostics and the {@code /api/effective-config} endpoint.
 *
 * @param sourceName human-readable source name (e.g., "jvm_arg", "env_var", "yaml")
 * @param ordinal priority ordinal — higher ordinal wins
 * @param rawValue value from this source, or null if the source didn't provide a value
 */
public record SourceCandidate(String sourceName, int ordinal, String rawValue) {

  public SourceCandidate {
    Objects.requireNonNull(sourceName, "sourceName");
  }

  /** Returns true if this source provided a non-blank value. */
  public boolean hasValue() {
    return rawValue != null && !rawValue.isBlank();
  }
}
