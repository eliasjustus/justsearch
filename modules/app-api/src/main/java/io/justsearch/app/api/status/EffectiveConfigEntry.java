/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A single entry in the {@code resolvedConfig} section of {@code /api/debug/effective-config}.
 *
 * <p>Each entry represents a resolved configuration key with its winning value, source provenance,
 * and all candidates considered during ordinal-chain resolution. This makes config resolution fully
 * transparent for diagnostics.
 *
 * <p>Stability: diagnostic (may change between releases)
 *
 * @param key config key (e.g., "justsearch.data.dir")
 * @param value resolved value, or null if no source provided a value
 * @param source winning source name (e.g., "jvm_arg", "env_var", "yaml", "default")
 * @param ordinal winning source ordinal (higher ordinal = higher priority)
 * @param detail winning source detail (e.g., the env var name or sysprop name)
 * @param candidates all sources checked during resolution, in descending ordinal order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EffectiveConfigEntry(
    String key,
    String value,
    String source,
    int ordinal,
    String detail,
    List<CandidateEntry> candidates) {

  /**
   * A single source candidate considered during resolution.
   *
   * @param source source name (e.g., "jvm_arg", "env_var")
   * @param ordinal source ordinal
   * @param value value from this source, or null if the source didn't provide a value
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CandidateEntry(String source, int ordinal, String value) {}
}
