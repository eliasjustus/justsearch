/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  private static final int DEFAULT_CAPACITY = 500;

  private final int capacity;
  // id -> event, insertion-ordered + bounded. Eviction is explicit (index-first) in append(), not
  // via removeEldestEntry, so a flood of index events cannot drop older actor events. Guarded by
  // `this` (LinkedHashMap is not thread-safe; append/recent may race across broadcast threads).
  private final LinkedHashMap<String, ActionEvent> byId;

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
   */
  public synchronized void append(ActionEvent event) {
    if (event == null || event.id() == null || byId.containsKey(event.id())) {
      return;
    }
    byId.put(event.id(), event);
    if (byId.size() > capacity) {
      evictOne();
    }
  }

  /**
   * Evict one entry to return to capacity: the oldest {@code INDEX} event if any exists (so an
   * indexing burst sacrifices its own oldest rows, never actor history), else the eldest overall.
   * Insertion-order iteration means the first match is the oldest of its kind.
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
    }
    byId.remove(victim);
  }

  /** A snapshot of the current log, oldest-first. */
  public synchronized List<ActionEvent> recent() {
    return new ArrayList<>(byId.values());
  }
}
