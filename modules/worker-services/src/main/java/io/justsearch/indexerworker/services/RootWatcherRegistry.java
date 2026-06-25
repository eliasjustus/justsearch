/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of Worker-side watch subscriptions (tempdoc 418 Phase A scaffolding,
 * Phase B real-event-delivery wiring).
 *
 * <p>Phase A landed bookkeeping only; Phase B (this revision) injects an optional
 * {@link WorkerMethvinWatcher} so {@code watch}/{@code unwatch} drive real Methvin
 * subscriptions. Constructed without a watcher (legacy / test mode), it falls back to
 * registry-only bookkeeping; constructed with a watcher (production path via
 * {@code DefaultWorkerAppServices}), each {@code watch} also opens a Methvin
 * {@code DirectoryWatcher} feeding {@link io.justsearch.indexerworker.queue.JobQueue}.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}; idempotent: a second {@code watch} call for the
 * same root replaces the prior subscription (collection update) and restarts the underlying
 * watcher.
 */
public final class RootWatcherRegistry {
  private static final Logger log = LoggerFactory.getLogger(RootWatcherRegistry.class);

  private final Map<Path, Subscription> subscriptions = new ConcurrentHashMap<>();
  private final WorkerMethvinWatcher watcher;

  public RootWatcherRegistry() {
    this(null);
  }

  public RootWatcherRegistry(WorkerMethvinWatcher watcher) {
    this.watcher = watcher;
  }

  WatchResult watch(String rootPath, String collection) {
    Path normalized;
    try {
      normalized = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return new WatchResult(false, "WatchRootRequest.root_path is not a valid path: " + e.getMessage());
    }
    if (!Files.isDirectory(normalized)) {
      return new WatchResult(false, "WatchRootRequest.root_path is not a directory: " + normalized);
    }
    String coll = collection == null || collection.isBlank() ? null : collection;
    Subscription prior = subscriptions.put(normalized, new Subscription(normalized, coll));
    if (watcher != null) {
      watcher.registerRoot(normalized, coll);
    }
    if (prior == null) {
      log.info(
          "Root watcher registered ({}): {}",
          watcher == null ? "registry-only" : "with worker watcher",
          normalized);
    } else {
      log.debug("Root watcher subscription replaced for {}", normalized);
    }
    return new WatchResult(true, null);
  }

  boolean unwatch(String rootPath) {
    Path normalized;
    try {
      normalized = Path.of(rootPath).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return false;
    }
    Subscription removed = subscriptions.remove(normalized);
    if (removed != null) {
      if (watcher != null) {
        watcher.unregisterRoot(normalized);
      }
      log.info(
          "Root watcher unregistered ({}): {}",
          watcher == null ? "registry-only" : "with worker watcher",
          normalized);
      return true;
    }
    return false;
  }

  @SuppressWarnings("unused") // RootWatcherRegistryTest only — see UnreferencedCodeTest exemption.
  Set<Path> watchedRoots() {
    return Collections.unmodifiableSet(subscriptions.keySet());
  }

  record Subscription(Path root, String collection) {}

  record WatchResult(boolean watching, String errorMessage) {}
}
