/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.config;

/** Listener invoked whenever a new configuration snapshot is produced. */
@FunctionalInterface
public interface ConfigSnapshotListener {
  void onConfigSnapshot(ConfigSnapshot snapshot);
}
