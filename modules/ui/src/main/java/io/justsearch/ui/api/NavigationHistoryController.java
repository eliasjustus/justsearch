/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST endpoint for the Navigation history ledger (tempdoc 550 Slice F1, Outcome face).
 *
 * <p>Sibling to {@link OperationHistoryController}: exposes the {@link
 * NavigationHistoryStore} ring buffer so forwarded Navigation intents — the
 * lattice-bypassing action kind that previously left no record — are reviewable like
 * Operation invocations. A snapshot-only REST view is the minimal live-verifiable
 * surface; the SSE append-stream + the unified action-ledger read-view (merging
 * Operation + Navigation + Effect records) are the flagged cross-cutting follow-up, not
 * this additive slice.
 *
 * <p>Wire shape: {@code { "entries": NavigationHistoryEntry[] }} in chronological order
 * (most recent last).
 */
public final class NavigationHistoryController {

  private static final Logger log = LoggerFactory.getLogger(NavigationHistoryController.class);

  private static final ObjectMapper REST_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private final NavigationHistoryStore store;

  public NavigationHistoryController(NavigationHistoryStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** Handles {@code GET /api/navigation-history}. Returns the current snapshot. */
  public void handleGet(Context ctx) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", store.recent());
      ctx.contentType("application/json").result(REST_MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize navigation-history snapshot", e);
      throw new IllegalStateException("Navigation-history snapshot serialization failed", e);
    }
  }
}
