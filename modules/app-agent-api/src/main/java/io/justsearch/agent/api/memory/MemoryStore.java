/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.memory;

import java.util.List;

/**
 * Tempdoc 561 P-E — the ONE authority for the agent's learned memory ({@link MemoryRecord}). Mirrors
 * the thread/action-log discipline: a single durable store, with read-views that are projections of
 * it, never independent models. Its two user-facing projections are first-class obligations, not
 * afterthoughts: {@link #whatItKnows()} (inspect — single authority made legible) and {@link
 * #forget(String)} (user control — the on-device privacy story). A SECOND memory authority is a
 * build failure (the {@code operation-surface} register's memory single-authority declaration).
 */
public interface MemoryStore {

  /** Persist a learned item. Idempotent on {@code record.id()} (re-remember replaces). */
  void remember(MemoryRecord record);

  /** The inspectable "what it knows" projection — every learned item, newest first. */
  List<MemoryRecord> whatItKnows();

  /** User control — forget one item by id. No-op if absent. */
  void forget(String id);

  /** User control — forget everything. */
  void clear();

  /** Null Object for environments without a configured memory store. */
  static MemoryStore noop() {
    return NoOpMemoryStore.INSTANCE;
  }
}
