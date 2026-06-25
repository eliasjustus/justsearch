/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tempdoc 577 §2.14 Root I (#13) — the session-local event hub that makes an agent run an OBSERVED
 * entity rather than one owned by the HTTP socket that started it.
 *
 * <p>The loop publishes every {@link AgentEvent} here (in addition to / instead of writing the SSE
 * socket directly). N observers — the initiating SSE writer, a reattaching second tab — each
 * {@link #subscribe} to the hub: they REPLAY the bounded buffer (oldest-first, so a late observer
 * sees the run's history) and then receive ongoing events. The run has ONE event authority behind N
 * views, which is what lets a dropped/extra observer come and go without affecting the run.
 *
 * <p>The buffer is bounded (a ring): the newest {@code capacity} events are retained, so a long run
 * can never grow the hub without bound. A reattach therefore replays a recent window, not the entire
 * history (the full history is on {@code events.ndjson} via {@link RunEventStore} for the record).
 *
 * <p>Thread-safety: {@link #publish} and {@link #subscribe} are mutually exclusive (synchronized on
 * the hub), so a subscriber's replay + registration is atomic w.r.t. a concurrent publish — an event
 * is delivered to a new subscriber either via the replay or via the live fan-out, never both, never
 * neither. Subscriber exceptions are swallowed (a broken SSE socket must not break the run or the
 * other observers).
 */
final class RunEventHub {

  private final int capacity;
  private final Deque<AgentEvent> buffer = new ArrayDeque<>();
  private final List<Consumer<AgentEvent>> subscribers = new CopyOnWriteArrayList<>();
  private volatile boolean closed = false;

  RunEventHub(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  /** Publish an event: append to the bounded ring (evicting the oldest) and fan out to observers. */
  synchronized void publish(AgentEvent event) {
    if (closed) {
      return;
    }
    if (buffer.size() >= capacity) {
      buffer.pollFirst();
    }
    buffer.addLast(event);
    for (Consumer<AgentEvent> sub : subscribers) {
      deliver(sub, event);
    }
  }

  /**
   * Subscribe an observer: synchronously REPLAY the buffered events (oldest-first) then register for
   * ongoing events. Returns an unsubscribe handle (idempotent). Atomic w.r.t. {@link #publish}.
   */
  synchronized Runnable subscribe(Consumer<AgentEvent> observer) {
    return subscribe(observer, Long.MIN_VALUE);
  }

  /**
   * Tempdoc 585 §D Phase 2 (B1) — subscribe, replaying ONLY the buffered events newer than {@code
   * fromSeq} (the SSE {@code Last-Event-ID} the reattaching client last saw, per
   * {@link io.justsearch.agent.api.TraceContext#seq()}). A precise reconnect therefore resumes from
   * the cursor instead of re-delivering the whole window — no duplicate replay. {@code
   * Long.MIN_VALUE} replays everything (the {@link #subscribe(Consumer)} default), and an untraced
   * event ({@code seq() == -1}) is replayed only for that full-replay default, never filtered out by
   * a real cursor it cannot be compared against meaningfully. Atomic w.r.t. {@link #publish}.
   */
  synchronized Runnable subscribe(Consumer<AgentEvent> observer, long fromSeq) {
    for (AgentEvent e : buffer) {
      long seq = e.trace() == null ? -1L : e.trace().seq();
      if (fromSeq == Long.MIN_VALUE || seq > fromSeq) {
        deliver(observer, e);
      }
    }
    subscribers.add(observer);
    return () -> subscribers.remove(observer);
  }

  /** How many observers are currently attached (the zero-observer policy reads this). */
  int observerCount() {
    return subscribers.size();
  }

  /** Close the hub once the run is terminal: drop subscribers + the buffer, refuse further publishes. */
  synchronized void close() {
    closed = true;
    subscribers.clear();
    buffer.clear();
  }

  private void deliver(Consumer<AgentEvent> sub, AgentEvent event) {
    try {
      sub.accept(event);
    } catch (RuntimeException dead) {
      // A throwing observer is a dead socket. Two reasons to EVICT it (not merely swallow): (1) it
      // must not break the run or the other observers; (2) observerCount must reflect LIVE observers
      // so the §2.14 Root I zero-observer policy is reachable — a dead-socket observer left counted
      // would mean "no watcher" never registers. Safe to remove during the publish snapshot iteration
      // (CopyOnWriteArrayList). A reattach re-subscribes; the buffer replays what it missed.
      subscribers.remove(sub);
    }
  }
}
