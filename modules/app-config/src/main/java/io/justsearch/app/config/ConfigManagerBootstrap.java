/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.config;

import io.justsearch.configuration.Faults;
import io.justsearch.configuration.RepoRootLocator;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads config and emits immutable snapshots to listeners. */
public final class ConfigManagerBootstrap {
  private static final Logger LOG = LoggerFactory.getLogger(ConfigManagerBootstrap.class);

  private final List<ConfigSnapshotListener> listeners = new CopyOnWriteArrayList<>();
  private volatile ConfigSnapshot snapshot;

  public ConfigManagerBootstrap() {
    this.snapshot = loadSnapshot();
  }

  /** Returns the most recent snapshot. */
  public ConfigSnapshot currentSnapshot() {
    return snapshot;
  }

  /**
   * Refreshes config, notifying registered listeners when a new snapshot is produced.
   */
  public synchronized void refresh() {
    ConfigSnapshot next = loadSnapshot();
    snapshot = next;
    notifyListeners(next);
  }

  /** Registers a listener; optionally invoke it immediately with the current snapshot. */
  public void registerListener(ConfigSnapshotListener listener, boolean fireImmediately) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    if (fireImmediately) {
      Faults.logAndContinue(
          LOG,
          "config listener (immediate) " + listener.getClass().getSimpleName(),
          () -> listener.onConfigSnapshot(snapshot));
    }
  }

  private ConfigSnapshot loadSnapshot() {
    return new ConfigSnapshot(Instant.now());
  }

  private void notifyListeners(ConfigSnapshot snapshot) {
    for (ConfigSnapshotListener listener : listeners) {
      Faults.logAndContinue(
          LOG,
          "config listener " + listener.getClass().getSimpleName(),
          () -> listener.onConfigSnapshot(snapshot));
    }
  }

  /** Convenience accessor for the resolved repository root. */
  public java.nio.file.Path repoRoot() {
    return RepoRootLocator.findRepoRoot();
  }
}
