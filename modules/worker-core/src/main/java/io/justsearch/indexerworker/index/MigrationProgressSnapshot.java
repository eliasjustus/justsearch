/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

/**
 * Best-effort migration progress snapshot for observability.
 *
 * <p>All fields are advisory; values may reset on restart and are not persisted.
 */
public record MigrationProgressSnapshot(
    boolean enumeratorRunning,
    boolean enumeratorDone,
    long rootsTotal,
    long rootsDone,
    long filesSeen,
    long filesEnqueued,
    long startedAtMs,
    long finishedAtMs,
    String lastPath) {}
