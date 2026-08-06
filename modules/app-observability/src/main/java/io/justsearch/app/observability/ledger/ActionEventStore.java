/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 550 thesis I — the ONE action-event log.
 *
 * <p>A single append-only, bounded, <b>id-keyed</b> store of {@link ActionEvent}s. Every
 * backend-authoritative source (operation invocations, navigations, trust-gate firings, grant
 * lifecycle) and the process-spanning FE-effect ingest fan into THIS one log via
 * {@link ActionLedgerChangeRegistry}. The action-ledger snapshot + live stream both read this one
 * store, so the receipt / timeline / trust-audit / undo are projections over one log rather than a
 * read-time re-join of several per-kind stores.
 *
 * <p><b>Idempotent by id (tempdoc 550 critical-analysis F1):</b> {@link #append} keeps the FIRST
 * event for a given {@link ActionEvent#id()} and ignores later duplicates. A log keyed by id is
 * idempotent by construction, so re-delivery — e.g. the FE re-POSTing its localStorage-persisted
 * Effect Journal on a page reload or from a second tab — cannot inflate the log with duplicate
 * rows. In-memory ring (bounded at {@code capacity}); the per-kind stores remain authoritative for
 * their own non-ledger consumers.
 *
 * <p><b>Index-first eviction (tempdoc 550 thesis III(b) follow-up):</b> when the ring is full the
 * OLDEST {@code INDEX} event is evicted first; an actor event (operation / gate / grant /
 * navigation / effect) is only evicted when no index event remains to sacrifice. Folding terminal
 * indexing outcomes into the one log (thesis I) otherwise let a large indexing run flood the ring
 * and starve actor history from Activity. Activity prioritizes "what an actor did"; the
 * authoritative live indexing view is the indexing-jobs Resource, not this session ring — so
 * sacrificing the oldest indexing rows first is the correct retention priority.
 */
public final class ActionEventStore {

  private static final Logger log = LoggerFactory.getLogger(ActionEventStore.class);
  private static final int DEFAULT_CAPACITY = 500;

  private final int capacity;
  // id -> event, insertion-ordered + bounded. Eviction is explicit (index-first) in append(), not
  // via removeEldestEntry, so a flood of index events cannot drop older actor events. Guarded by
  // `this` (LinkedHashMap is not thread-safe; append/recent may race across broadcast threads).
  private final LinkedHashMap<String, ActionEvent> byId;
  // Tempdoc 812 D1 — actor-cliff observability. Guarded by `this`, like byId.
  private boolean actorEvictionEpisodeOpen;
  private long actorEvictions;

  public ActionEventStore() {
    this(DEFAULT_CAPACITY);
  }

  public ActionEventStore(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    this.byId = new LinkedHashMap<>(16, 0.75f, false);
  }

  /**
   * Append one event, keyed by {@link ActionEvent#id()}. Idempotent: the first event for an id is
   * kept (preserving its position + value); a later duplicate id is ignored. Bounded — when over
   * capacity, the oldest {@code INDEX} event is evicted first (see class javadoc), falling back to
   * the eldest overall only when no index event remains.
   *
   * @return {@code true} when this call actually added the event; {@code false} for a null/id-less
   *     event or a duplicate id. Tempdoc 812 D1: the durable journal writes only on {@code true},
   *     so the log's id-idempotency extends to disk for free instead of being re-implemented there.
   */
  public synchronized boolean append(ActionEvent event) {
    if (event == null || event.id() == null || byId.containsKey(event.id())) {
      return false;
    }
    byId.put(event.id(), event);
    if (byId.size() > capacity) {
      evictOne();
    }
    return true;
  }

  /**
   * Evict one entry to return to capacity: the oldest {@code INDEX} event if any exists (so an
   * indexing burst sacrifices its own oldest rows, never actor history), else the eldest overall.
   * Insertion-order iteration means the first match is the oldest of its kind.
   *
   * <p>Tempdoc 812 D1: evicting an ACTOR row is the ring's audit cliff — the point past which the
   * hot feed silently stops being the whole record. It is no longer silent: the first eviction of
   * an episode WARNs (once per episode, not per event — a session that stays over capacity would
   * otherwise emit one line per action), and the durable journal holds the grant/gate/operation
   * rows regardless. An index eviction closes the episode, so a later actor cliff WARNs again.
   */
  private void evictOne() {
    String victim = null;
    for (Map.Entry<String, ActionEvent> e : byId.entrySet()) {
      if (e.getValue().kind() == ActionEvent.ActionEventKind.INDEX) {
        victim = e.getKey();
        break;
      }
    }
    if (victim == null) {
      victim = byId.keySet().iterator().next();
      actorEvictions++;
      if (!actorEvictionEpisodeOpen) {
        actorEvictionEpisodeOpen = true;
        log.warn(
            "Action-event ring at capacity {} with no index rows left to sacrifice — evicting actor"
                + " history (first of this episode; {} total so far). Durable grant/gate/operation"
                + " rows remain in the audit journal.",
            capacity,
            actorEvictions);
      }
    } else {
      actorEvictionEpisodeOpen = false;
    }
    byId.remove(victim);
  }

  /** Total actor rows this ring has evicted (test/diagnostic view of the cliff). */
  public synchronized long actorEvictions() {
    return actorEvictions;
  }

  /** A snapshot of the current log, oldest-first. */
  public synchronized List<ActionEvent> recent() {
    return new ArrayList<>(byId.values());
  }
}
