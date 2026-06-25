/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Role;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The single Resource entry that surfaces the HealthEvent stream.
 *
 * <p>Per tempdoc 430 §"Initial entries / data shape" + §B.C: one Resource entry, not 27.
 * The 27 known event IDs are payload variants discriminated by {@link HealthEvent#id()}
 * and {@link HealthEventBody}'s {@code kind} discriminator. The Resource's
 * {@link Resource#schema()} URL describes the wire payload type; {@link Resource#kind()}
 * = {@code "health-event-stream"} discriminates the renderer for the Health view.
 *
 * <p>Per {@code 421-data-plane.md} §"Anti-patterns": a primitive whose entries are all
 * variants of one another belongs in a single entry's body, not as separate entries.
 *
 * <p>The {@link ResourceRef} is {@code "core.health-events"} — the spec text uses
 * {@code health.events} but the namespace regex requires {@code core.<id>} form.
 *
 * <p><strong>Slice 444a Phase 2 retrofit</strong> (per §B.6 + §B.A.1 + §B.A.2): the
 * Resource entry now declares {@link Category#EVENT_STREAM} explicitly + a
 * {@link HistoryPolicy} matching {@link OccurrenceLog}'s semantics
 * ({@code RING_BUFFER}, capacity 200, {@code EVICT_OLDEST}, 5-minute resume window).
 * The {@code recovery} cross-link is left empty because per-event recovery (one Operation
 * per HealthEvent {@code id} value) lives at the body level via slice 438; the
 * Resource-record-level slot would be a singular per-Resource recovery, which doesn't
 * apply to the heterogeneous-events catalog.
 */
public final class HealthResourceCatalog implements ResourceCatalog {

  /** Stable namespace for the HealthEvent Resource entry — shares "core" with operations. */
  public static final String NAMESPACE = "core";

  /** Stable id for the single Resource entry. */
  public static final ResourceRef HEALTH_EVENTS_ID = new ResourceRef("core.health-events");

  /** SSE endpoint advertised by the Resource entry. */
  public static final String ENDPOINT = "/api/health/events/stream";

  /** Schema URL for the wire payload (HealthEvent record with sealed body). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/health-event.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher. */
  public static final String KIND = "health-event-stream";

  /**
   * In-memory ring-buffer capacity for {@link OccurrenceLog} — pinned here so the
   * declared {@link HistoryPolicy} matches the implementation. If
   * {@code OccurrenceLog.DEFAULT_CAPACITY} ever changes, update both in lockstep.
   */
  public static final int OCCURRENCE_BUFFER_CAPACITY = 200;

  /** Per slice 444a §B.6 + 1.8 streaming envelope: how far back a reconnect can resume. */
  public static final Duration RESUME_WINDOW = Duration.ofMinutes(5);

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
              HEALTH_EVENTS_ID,
              Presentation.of(
                  new I18nKey("registry-resource.health-events.label"),
                  new I18nKey("registry-resource.health-events.description")),
              SCHEMA_URL,
              Category.EVENT_STREAM,
              SubscriptionMode.SSE_STREAM,
              ENDPOINT,
              KIND,
              Optional.of(
                  new HistoryPolicy(
                      HistoryPolicy.Mode.RING_BUFFER,
                      Optional.of(OCCURRENCE_BUFFER_CAPACITY),
                      Optional.empty(),
                      OnOverflow.EVICT_OLDEST,
                      RESUME_WINDOW)),
              Optional.empty(),
              Provenance.core("1.0"),
              // Slice 445 substrate-extension: typed Privacy axis. HealthEvent
              // schema declares no path-typed fields; NO_PATHS is the sane default.
              Privacy.noPaths(),
              // No item / collection Operations declared today.
              Set.of(),
              Set.of(),
              "")
              // tempdoc 571 §4c: operator-facing health stream — DIAGNOSTIC role lifts a consuming
              // surface (Health) to Altitude.DIAGNOSTIC by derivation.
              .withRole(Role.DIAGNOSTIC));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
