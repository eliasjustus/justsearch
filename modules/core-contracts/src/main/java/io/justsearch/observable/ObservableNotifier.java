/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.observable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic listener-list with swallow-and-log exception handling.
 *
 * <p>Tempdoc 518 Appendix F W4.1. Extracted as a shared substrate after Appendix E §E.3
 * verified that {@code ConfigStore.fire}, {@code TransitionRunner.notifyListeners}, and
 * the {@code IndexingLoop.additionalChangeListeners} branch share the same shape:
 * {@code CopyOnWriteArrayList} + best-effort dispatch.
 *
 * <h4>Contract</h4>
 *
 * <ul>
 *   <li>{@link #register(Consumer)} appends a listener; duplicates are permitted (the prior
 *       implementations all accepted duplicates).
 *   <li>{@link #unregister(Consumer)} removes the first matching listener (CopyOnWriteArrayList
 *       semantics). Returns true when something was removed.
 *   <li>{@link #notifyAll(Object)} iterates in registration order. A listener that throws is
 *       logged via SLF4J at WARN and the iteration continues; other listeners still receive
 *       the event. Mirrors the prior swallow-and-log policy at each consumer.
 * </ul>
 *
 * <p>Listener notification happens on the caller's thread. No threadlocal context is set.
 * Safe for concurrent registration / notification by virtue of {@code CopyOnWriteArrayList};
 * exception-bounded iteration is the only point of asymmetry vs. raw {@code list.forEach}.
 *
 * <p><b>Not a replacement for</b> {@code IndexingLoop.embeddingProviderChangeListener} —
 * the loop's primary registry is a single-slot {@code volatile Consumer} that semantically
 * differs from this list-typed substrate. Appendix E §E.3 notes the half-mismatch.
 *
 * @param <E> the event payload type emitted to each listener
 */
public final class ObservableNotifier<E> {

  private static final Logger LOG = LoggerFactory.getLogger(ObservableNotifier.class);

  private final String diagnosticName;
  private final List<Consumer<E>> listeners = new CopyOnWriteArrayList<>();

  /**
   * @param diagnosticName short label used in SLF4J WARN messages when a listener throws.
   *     Example: {@code "ConfigStore listener"}, {@code "ModeChangeListener"}. Required so
   *     log entries are attributable to a specific notifier without needing a stack trace.
   */
  public ObservableNotifier(String diagnosticName) {
    this.diagnosticName = Objects.requireNonNull(diagnosticName, "diagnosticName");
  }

  /** Register a listener. Duplicates permitted. */
  public void register(Consumer<E> listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
  }

  /** Unregister the first matching listener instance. Returns true on removal. */
  public boolean unregister(Consumer<E> listener) {
    Objects.requireNonNull(listener, "listener");
    return listeners.remove(listener);
  }

  /** Number of currently registered listeners. Test convenience. */
  public int size() {
    return listeners.size();
  }

  /**
   * Dispatch {@code event} to every registered listener in registration order. Listeners that
   * throw are logged and skipped; iteration continues.
   */
  public void notifyAll(E event) {
    for (Consumer<E> listener : listeners) {
      try {
        listener.accept(event);
      } catch (RuntimeException e) {
        LOG.warn("{} listener threw (best-effort): {}", diagnosticName, e.getMessage());
      }
    }
  }
}
