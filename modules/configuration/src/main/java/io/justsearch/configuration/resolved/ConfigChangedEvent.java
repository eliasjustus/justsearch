/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import java.util.Objects;

/**
 * Event fired when the active {@link ResolvedConfig} snapshot is replaced.
 *
 * <p>Listeners can inspect which keys changed and react accordingly (e.g., surface a
 * "restart required" flag for settings that require a process restart).
 *
 * @param previous the config snapshot before the change
 * @param current the new config snapshot after the change
 */
public record ConfigChangedEvent(ResolvedConfig previous, ResolvedConfig current) {

  public ConfigChangedEvent {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(current, "current");
  }

  /**
   * Returns true if the resolved value for the given key differs between the previous and current
   * snapshots.
   *
   * @param key the config key to check (e.g., "justsearch.data.dir")
   * @return true if the value changed (or was added/removed)
   */
  public boolean keyChanged(String key) {
    ConfigResolution prev = previous.resolution(key);
    ConfigResolution curr = current.resolution(key);
    String prevVal = prev != null ? prev.value() : null;
    String currVal = curr != null ? curr.value() : null;
    return !Objects.equals(prevVal, currVal);
  }
}
