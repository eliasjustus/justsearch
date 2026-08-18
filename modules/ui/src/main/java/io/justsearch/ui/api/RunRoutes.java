/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;

/**
 * Route registration for the run-stream family (tempdoc 834 §1.6).
 *
 * <p>Both routes are POST managed SSE — {@code new SseHandler(consumer)} behind a validating
 * {@link io.javalin.http.Handler} — and are therefore already covered by {@code ApiSecurityFilters}'
 * session-token requirement with NO filter change (probe D1, §14.1d, confirmed end-to-end in prod
 * mode). §5's {@code GET /api/chat/runs/live} enumeration is S4's, and it is the one that DOES need
 * a path-scoped filter change, because the filter returns early for GET.
 *
 * <p>Under {@code /api/chat/*} like every other conversational route; the removed {@code
 * /api/agent/*} namespace is not resurrected (Hard Invariant #3).
 */
public final class RunRoutes {
  private RunRoutes() {}

  public static void register(Javalin app, RunStreamController runs) {
    if (runs == null) {
      return;
    }
    // Creates the run AND observes it: with the creating call BEING the first stream, execution
    // stays synchronous on the handler thread (§1.7) and no executor move is needed.
    app.post("/api/chat/runs", runs::handleCreate);
    // An additional / reattaching observer. Unknown or long-retired ⇒ typed 404, never a 200 with
    // an empty stream.
    app.post("/api/chat/runs/{runId}/observe", runs::handleObserve);
  }
}
