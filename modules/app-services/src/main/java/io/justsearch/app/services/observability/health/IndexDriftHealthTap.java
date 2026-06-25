/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 626 §Axis-C — the per-root index-drift legibility tap. Realizes the tempdoc's titular thesis
 * (the reconciler's drift observation becomes a first-class condition on the {@link ConditionStore}
 * seam — an instance of 627's <em>Observation-Actuation Closure</em>): a watched root whose last
 * reconcile could NOT verify index-vs-disk delete correspondence (the cap-skipped delete-detection scan,
 * §Axis-B) is surfaced as a persistent {@code index.drift-unknown} {@link AssertedCondition} carrying a
 * {@code core.reindex} recovery, instead of dead-ending silently.
 *
 * <p>This is a HEAD-side tap (unlike {@link WorkerSnapshotTap}) because the per-root drift signal lives
 * Head-side: {@code WorkerOperationalView} is scalar/global and cannot carry per-root data, whereas the
 * flag already rides {@link IndexingService.WatchedRoot#deleteDetectionUnverified()} (set from the
 * reconcile response). Snapshot-triggered from {@code StatusLifecycleHandler.buildStatusMap()} alongside
 * the lifecycle / worker taps — no new threading.
 *
 * <p>Per-root subjects: unlike the worker tap's fixed subjects, each root gets its own condition keyed by
 * {@code subject = "index.drift-unknown/<sha256(path)>"} ({@link ConditionStore} keys on {@code (id,
 * subject)}). Raw paths never reach this wire (ADR-0028) — the subject is the path hash, mirroring
 * {@code IndexedRootView.pathHash}. The tap tracks its own asserted subjects so a root that becomes
 * verified (or is removed) clears its condition — "treat unknown ≠ healthy" applied to reconciliation
 * completeness (595).
 */
public final class IndexDriftHealthTap {
  private static final Logger log = LoggerFactory.getLogger(IndexDriftHealthTap.class);

  static final String CONDITION_ID = "index.drift-unknown";
  private static final String REASON = "DeleteDetectionUnverified";
  private static final Severity SEVERITY = Severity.WARNING;

  /**
   * Tempdoc 626 §Axis-C (drift-corrected) — the one-shot Occurrence id emitted when a reconcile prunes
   * stale entries a live DELETE missed (drift found AND corrected). Informational (INFO), not a standing
   * Condition: by the time it surfaces the index is already correct.
   */
  static final String OCCURRENCE_ID = "index.drift-corrected";

  /**
   * Recovery (tempdoc 626 §Recency, Move C) — the granularity-matched action: re-verify THIS folder via
   * {@code core.reconcile-root {pathHash}} (re-prune + re-walk the one root), rather than a corpus-wide
   * {@code core.reindex}. Built per-subject so the affordance targets the specific drifted root; the
   * forced reconcile also refreshes the root's verification state (clears the flag, stamps lastVerifiedAt).
   */
  private static io.justsearch.agent.api.registry.OperationInvocation recoveryFor(String pathHash) {
    return new io.justsearch.agent.api.registry.OperationInvocation(
        new io.justsearch.agent.api.registry.OperationRef("core.reconcile-root"),
        "{\"pathHash\":\"" + pathHash + "\"}");
  }

  private final ConditionStore conditions;
  private final OccurrenceLog occurrences;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;
  private final Supplier<List<IndexingService.WatchedRoot>> rootsSupplier;

  /** Subjects currently asserted, so verified/removed roots get their condition cleared. */
  private final Set<String> activeSubjects = ConcurrentHashMap.newKeySet();

  /** Per-root (pathHash) last drift-corrected at-ms emitted, so each orphan-prune fires once (dedup). */
  private final Map<String, Long> priorDriftAtMs = new ConcurrentHashMap<>();

  public IndexDriftHealthTap(
      ConditionStore conditions,
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock,
      Supplier<List<IndexingService.WatchedRoot>> rootsSupplier) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.rootsSupplier = Objects.requireNonNull(rootsSupplier, "rootsSupplier");
  }

  /**
   * Snapshot hook called from {@code StatusLifecycleHandler.buildStatusMap()}: pulls the current
   * watched roots and reconciles their drift conditions. Decoupled from the handler (the tap owns its
   * input source); {@link #reconcile(List)} is the testable pure-ish core.
   */
  public void accept() {
    reconcile(rootsSupplier.get());
  }

  /**
   * Reconciles the per-root drift flag with the {@link ConditionStore}: assert {@code
   * index.drift-unknown} for each unverified root, clear it for verified roots and roots no longer
   * watched. {@code null}/empty roots clears everything (mirrors the worker tap's stale short-circuit:
   * absence is healthy, but a known-unverified root is not).
   */
  synchronized void reconcile(List<IndexingService.WatchedRoot> roots) {
    Set<String> stillUnverified = ConcurrentHashMap.newKeySet();
    Set<String> livePathHashes = ConcurrentHashMap.newKeySet();
    if (roots != null) {
      for (IndexingService.WatchedRoot root : roots) {
        if (root == null || root.path() == null) {
          continue;
        }
        String pathHash = sha256Hex(root.path().toString());
        livePathHashes.add(pathHash);
        // Tempdoc 626 §Axis-C (drift-corrected) — emit a one-shot Occurrence when a reconcile pruned
        // stale entries (a deletion the live watcher missed). Dedup on the at-ms advance per root.
        maybeEmitDriftCorrected(pathHash, root.driftOrphanCount(), root.driftOrphanAtMs());
        if (!root.deleteDetectionUnverified()) {
          continue;
        }
        String subject = CONDITION_ID + "/" + pathHash;
        stillUnverified.add(subject);
        upsert(subject, pathHash);
      }
    }
    // Clear conditions for subjects that are no longer unverified (verified or root removed).
    for (String prior : Set.copyOf(activeSubjects)) {
      if (!stillUnverified.contains(prior)) {
        clear(prior);
      }
    }
    activeSubjects.clear();
    activeSubjects.addAll(stillUnverified);
    // Prune the drift-corrected dedup map to the live roots, so a removed (or removed-then-re-added)
    // root doesn't retain a stale at-ms that would suppress a future legitimate emission.
    priorDriftAtMs.keySet().retainAll(livePathHashes);
  }

  private void upsert(String subject, String pathHash) {
    HealthEvent event =
        new HealthEvent(
            CONDITION_ID,
            clock.instant(),
            source,
            SEVERITY,
            Optional.of("health-events." + CONDITION_ID + ".message"),
            new AssertedCondition(
                subject,
                ConditionStatus.TRUE, // §A.6 (rev-3.6): "named condition holds" = the drift-unknown state
                REASON,
                clock.instant(),
                Optional.of("Deletions for this folder could not be verified — reindex to be sure."),
                Optional.of(recoveryFor(pathHash)),
                List.of()));
    ConditionStore.Transition t = conditions.upsert(event);
    switch (t) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        /* store preserved prior record — no broadcast */
      }
    }
  }

  private void clear(String subject) {
    Optional<HealthEvent> removed = conditions.clear(CONDITION_ID, subject);
    removed.ifPresent(e -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, e));
  }

  /**
   * Emits one {@code index.drift-corrected} Occurrence per orphan-prune event (tempdoc 626 §Axis-C),
   * deduped on the per-root at-ms advance — mirroring {@code WorkerSnapshotTap.detectJobFailureOccurrence}.
   * The {@code missing}/re-enqueue half of the reconcile diff is deliberately NOT surfaced (it re-counts
   * in-flight files — noisy); only orphans-pruned (a confirmed missed deletion) is a clean signal.
   */
  private void maybeEmitDriftCorrected(String pathHash, int orphanCount, long atMs) {
    if (orphanCount <= 0 || atMs <= 0) {
      return;
    }
    Long prior = priorDriftAtMs.get(pathHash);
    if (prior != null && prior == atMs) {
      return; // already emitted for this prune event
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("pathHash", pathHash);
    attributes.put("orphanCount", orphanCount);
    attributes.put("atMs", atMs);
    attributes.put(
        "message",
        "Corrected this folder's index by pruning "
            + orphanCount
            + (orphanCount == 1 ? " stale entry" : " stale entries")
            + " a live delete had missed.");
    HealthEvent event =
        new HealthEvent(
            OCCURRENCE_ID,
            clock.instant(),
            source,
            Severity.INFO,
            Optional.of("health-events." + OCCURRENCE_ID + ".message"),
            new LifecycleEvent(attributes, Optional.empty()));
    occurrences.append(event);
    changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
    priorDriftAtMs.put(pathHash, atMs);
  }

  /** SHA-256 hex of the normalized path — the privacy-safe per-root key (ADR-0028). */
  private static String sha256Hex(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
