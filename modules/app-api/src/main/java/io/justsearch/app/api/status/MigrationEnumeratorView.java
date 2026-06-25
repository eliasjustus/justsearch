/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Migration enumerator sub-view for the worker operational status.
 *
 * <p>Stability: stable (API contract)
 */
public record MigrationEnumeratorView(
    boolean running,
    boolean done,
    long rootsTotal,
    long rootsDone,
    long filesSeen,
    long filesEnqueued,
    long startedAtMs,
    long finishedAtMs,
    String lastPath) {

  public MigrationEnumeratorView {
    lastPath = lastPath == null ? "" : lastPath;
  }
}
