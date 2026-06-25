/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

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
 * The Resource entry surfacing the unified action ledger (tempdoc 550 Outcome face) as an
 * {@link Category#EVENT_STREAM} Resource — and, per tempdoc 571 §4c, the {@link Role#TRUST}-role
 * authority that makes the <strong>Activity</strong> surface derive {@link
 * io.justsearch.agent.api.registry.Altitude#TRUST}.
 *
 * <p>Before 571 the Activity surface rendered the {@code jf-action-ledger} read-view out-of-band —
 * its trust authority was NOT in its {@code consumes} graph, so altitude could not be derived from
 * consumption (571 §8 R1, the de-risk gap). Promoting the ledger to a declarable Resource closes
 * that: Activity declares {@code consumes.resources += core.action-ledger}, and its TRUST altitude
 * falls out of the consumed Resource's {@code role}. Consuming a second non-PRODUCT authority would
 * be a derivation <em>conflict</em> — the merge-foreclosure (571 §4c) for free.
 *
 * <p>Shape mirrors {@code core.operation-history} exactly ({@link Category#EVENT_STREAM} ×
 * {@link SubscriptionMode#SSE_STREAM} × {@link HistoryPolicy.Mode#RING_BUFFER}) — the live tail at
 * {@link #ENDPOINT} is the same {@code ActionLedgerController} stream the receipt / timeline / undo /
 * trust-audit already read. The recovery cross-link is empty: per-entry
 * remediation is the Operation that produced the row, not a singular per-Resource recovery.
 */
public final class ActionLedgerResourceCatalog implements ResourceCatalog {

  /** Shared namespace with the other core catalogs. */
  public static final String NAMESPACE = "core";

  /** Stable id for the action-ledger Resource entry. */
  public static final ResourceRef ACTION_LEDGER_ID = new ResourceRef("core.action-ledger");

  /** SSE endpoint advertised by the Resource entry — the unified live read-view (tempdoc 550). */
  public static final String ENDPOINT = "/api/action-ledger/stream";

  /** Schema URL for the wire payload (the projected ActionEvent row). */
  public static final String SCHEMA_URL =
      "https://ssot.justsearch/v1/schemas/action-ledger-entry.v1.json";

  /** Discriminates the renderer in the FE generic dispatcher (the {@code jf-action-ledger} view). */
  public static final String KIND = "action-ledger";

  /**
   * Ring-buffer capacity for {@link ActionEventStore} — pinned here so the declared
   * {@link HistoryPolicy} matches the implementation. If {@code ActionEventStore.DEFAULT_CAPACITY}
   * ever changes, update both in lockstep.
   */
  public static final int HISTORY_CAPACITY = 500;

  /** Resume window — same shape as the other ledger / event-stream Resources. */
  public static final Duration RESUME_WINDOW = Duration.ofMinutes(5);

  private static final List<Resource> DEFINITIONS =
      List.of(
          new Resource(
                  ACTION_LEDGER_ID,
                  Presentation.of(
                      new I18nKey("registry-resource.action-ledger.label"),
                      new I18nKey("registry-resource.action-ledger.description")),
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
                  // The ledger projects coarse attribution + subjects; no path-typed fields.
                  Privacy.noPaths(),
                  Set.of(),
                  Set.of(),
                  "")
              // tempdoc 571 §4c: the trust / authorization-lifecycle record — TRUST role lifts a
              // consuming surface (Activity) to Altitude.TRUST by derivation. CORE-only by
              // construction (the surface-altitude gate forecloses a plugin TRUST surface).
              .withRole(Role.TRUST));

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Resource> definitions() {
    return DEFINITIONS;
  }
}
