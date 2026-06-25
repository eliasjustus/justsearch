/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.config;

import java.time.Instant;
import java.util.Objects;

/** Immutable configuration snapshot with a load timestamp. */
public record ConfigSnapshot(Instant loadedAt) {
  public ConfigSnapshot {
    Objects.requireNonNull(loadedAt, "loadedAt");
  }
}
