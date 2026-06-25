/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Resource entry surfacing the head's {@link OperationHistoryStore} as an
 * {@link Category#EVENT_STREAM} Resource.
 *
 * <p>Per slice 444b post-implementation §B.B reclassification (2026-05-05): originally
 * declared HISTORY × DURABLE, but the underlying store is an in-memory ring buffer —
 * declaring DURABLE Mode for an in-memory store contradicts {@link HistoryPolicy}'s
 * javadoc (DURABLE = "Persistent store"). The structurally correct framing is
 * EVENT_STREAM × RING_BUFFER, matching {@code core.health-events}'s shape exactly.
 * Conforms to {@code 30-agent-workflows/01b-add-event-stream-resource.md}:
 *
 * <ul>
 *   <li>{@link Category#EVENT_STREAM} Category.
 *   <li>{@link SubscriptionMode#SSE_STREAM} subscription mode (live tail of new appends);
 *       snapshot reads served at {@code /api/operation-history}.
 *   <li>{@link HistoryPolicy} declares {@link HistoryPolicy.Mode#RING_BUFFER} with
 *       capacity 200 — matches the bounded-in-memory implementation in
 *       {@link OperationHistoryStore}.
 * </ul>
 *
 * <p>Recovery cross-link is empty — operation history doesn't have a singular per-Resource
 * recovery; per-entry remediation is the Operation that was invoked.
 */
public final class OperationHistoryResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the operation-history Resource entry. */
  public static final String NAMESPACE = "core";

  /** Stable id for the Resource entry. */
  public static final ResourceRef OPERATION_HISTORY_ID =
      new ResourceRef("core.operation-history");

  /** SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/api/operation-history/stream";

  /** Schema URL for the wire payload (OperationHistoryEntry record). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/operation-history-entry.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "operation-history";

  /**
   * Ring-buffer capacity for {@link OperationHistoryStore} — pinned here so the declared
   * {@link HistoryPolicy} matches the implementation. If
   * {@code OperationHistoryStore.DEFAULT_CAPACITY} ever changes, update both in lockstep.
   */
  public static final int HISTORY_CAPACITY = 200;

  /** Resume window — same shape as {@link io.justsearch.app.observability.health.HealthResourceCatalog}. */
  public static final Duration RESUME_WINDOW = Duration.ofMinutes(5);

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              OPERATION_HISTORY_ID,
              Presentation.of(
                  new I18nKey("registry-resource.operation-history.label"),
                  new I18nKey("registry-resource.operation-history.description")),
              SCHEMA_URL,
              Category.EVENT_STREAM,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.of(
                  new HistoryPolicy(
                      HistoryPolicy.Mode.RING_BUFFER,
                      Optional.of(HISTORY_CAPACITY),
                      Optional.empty(),
                      OnOverflow.EVICT_OLDEST,
                      RESUME_WINDOW)),
              Optional.empty(),
              Provenance.core("1.0"),
              // Slice 445 substrate-extension. OperationHistoryEntry has no
              // path-typed fields.
              Privacy.noPaths(),
              Set.of(),
              Set.of(),
              ""));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
