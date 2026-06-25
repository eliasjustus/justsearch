/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observes {@link WorkerOperationalView} transitions on each
 * {@code StatusLifecycleHandler.buildStatusMap()} call and emits {@link HealthEvent}
 * records for the 7 worker-side events that aren't surfaced through the readiness
 * envelope (which {@code LifecycleSnapshotTap} covers).
 *
 * <p>Per tempdoc 430 §A.10 Phase 6 (rev 3.7 spec) + §B.T (rev-3.6 §B.S patterns
 * applied upfront): the tap reads compatibility / queue-db / failure fields directly
 * from the gRPC-projected worker view the head already receives. Two emission modes:
 *
 * <ul>
 *   <li><strong>5 Conditions</strong> via {@link ConditionStore} + {@code CONDITION_*}
 *       broadcasts: {@code schema.blocked}, {@code schema.reindex-required},
 *       {@code embedding.blocked}, {@code queue-db.unhealthy}, {@code queue-db.check-failed}.
 *   <li><strong>2 Occurrences</strong> via {@link OccurrenceLog} +
 *       {@code OCCURRENCE_APPENDED} broadcasts (the first OccurrenceLog producers in V1):
 *       {@code worker.job.failed}, {@code worker.job.retry-scheduled}.
 * </ul>
 *
 * <p>Stale-view handling (§B.T.5): {@code accept(view, stale=true)} short-circuits
 * before reading any field. {@link WorkerOperationalView#fallback(String)} reports
 * {@code queueDbHealthy=true} regardless of worker reachability — without the
 * stale-flag short-circuit, a fallback view would falsely <em>clear</em> a real
 * {@code queue-db.unhealthy} condition. Treat unknown ≠ healthy.
 *
 * <p>Mapping-table outcomes follow rev-3.6 §B.S tri-state lookup:
 *
 * <ul>
 *   <li>HEALTHY (default value) → clear prior assertion.
 *   <li>MAPPED → upsert / swap to the new conditionId.
 *   <li>UNMAPPED-UNHEALTHY (e.g., a future {@code BLOCKED_FOO} state) → preserve prior
 *       assertion + WARN-once. {@link Set} of warned keys deduplicates the log.
 * </ul>
 *
 * <p>{@link ConditionStatus#TRUE} on the wire means "the named condition holds" per the
 * rev-3.6 §A.6 k8s convention — e.g., {@code id=schema.blocked} + {@code status=TRUE}
 * means the schema IS blocked. Healthy = absence-from-store; the FE renders banners
 * only for present Conditions.
 *
 * <p>Thread safety: {@link #accept(WorkerOperationalView, boolean)} is
 * {@code synchronized}. Snapshots are computed per {@code /api/status} request
 * (low-frequency); contention is negligible. {@link ConditionStore} is independently
 * thread-safe.
 */
public final class WorkerSnapshotTap {

  private static final Logger log = LoggerFactory.getLogger(WorkerSnapshotTap.class);

  /** Resolved condition target for a field's state value. */
  private record ConditionMapping(
      String conditionId,
      String subject,
      Severity severity,
      Optional<io.justsearch.agent.api.registry.OperationInvocation> recovery) {

    /** Backwards-compatible constructor for Conditions without a default recovery. */
    ConditionMapping(String conditionId, String subject, Severity severity) {
      this(conditionId, subject, severity, Optional.empty());
    }
  }

  // ----- Mapping tables (state-string fields) -----

  /**
   * Maps verbatim {@code indexSchemaCompatState} values produced by
   * {@code IndexStatusOps.computeIndexSchemaCompatState} to catalog conditions.
   * {@code COMPATIBLE} / empty → healthy (clear). {@code UNAVAILABLE} → unmapped
   * (preserve prior + WARN-once); a future V1.5 may add {@code schema.unavailable}.
   */
  private static final Map<String, ConditionMapping> SCHEMA_COMPAT_TABLE;

  /**
   * Maps verbatim {@code embeddingCompatState} values produced by
   * {@code EmbeddingCompatibilityController.State.name()} to catalog conditions.
   * {@code COMPATIBLE} / empty → healthy. {@code REBUILDING} / {@code UNAVAILABLE} →
   * unmapped (preserve prior + WARN-once); REBUILDING is conceptually covered by the
   * EMBEDDING readiness dim's {@code embedding.not-ready} from Phase 5.
   */
  private static final Map<String, ConditionMapping> EMBEDDING_COMPAT_TABLE;

  static {
    Map<String, ConditionMapping> schema = new HashMap<>();
    schema.put(
        "BLOCKED_LEGACY",
        new ConditionMapping("schema.blocked", "worker.schema", Severity.ERROR));
    schema.put(
        "BLOCKED_MISMATCH",
        new ConditionMapping("schema.blocked", "worker.schema", Severity.ERROR));
    SCHEMA_COMPAT_TABLE = Map.copyOf(schema);

    Map<String, ConditionMapping> embedding = new HashMap<>();
    embedding.put(
        "BLOCKED_LEGACY",
        new ConditionMapping("embedding.blocked", "worker.embedding", Severity.ERROR));
    embedding.put(
        "BLOCKED_MISMATCH",
        new ConditionMapping("embedding.blocked", "worker.embedding", Severity.ERROR));
    EMBEDDING_COMPAT_TABLE = Map.copyOf(embedding);
  }

  // ----- Mapping constants (boolean fields) -----

  // Per 442 §B.9 row 547 + 447-impl-B: schema.reindex-required's recovery is
  // core.reindex with force=true. Path-A's Optional<OperationRef> couldn't carry the
  // force=true default; OperationInvocation.defaultArgsJson now does.
  private static final ConditionMapping REINDEX_MAPPING =
      new ConditionMapping(
          "schema.reindex-required",
          "worker.schema",
          Severity.WARNING,
          Optional.of(
              new io.justsearch.agent.api.registry.OperationInvocation(
                  new io.justsearch.agent.api.registry.OperationRef("core.reindex"),
                  "{\"force\":true}")));
  private static final ConditionMapping QUEUE_DB_UNHEALTHY =
      new ConditionMapping("queue-db.unhealthy", "worker.queue-db", Severity.ERROR);
  private static final ConditionMapping QUEUE_DB_CHECK_FAILED =
      new ConditionMapping("queue-db.check-failed", "worker.queue-db", Severity.WARNING);

  /**
   * Returns the catalog IDs this tap can emit. Includes:
   *
   * <ul>
   *   <li>Mapping-table-derived Condition IDs ({@code schema.blocked},
   *       {@code embedding.blocked}, {@code schema.reindex-required},
   *       {@code queue-db.unhealthy}, {@code queue-db.check-failed}).
   *   <li>The two literal Occurrence IDs from
   *       {@code detectJobFailureOccurrence} / {@code detectRetryScheduledOccurrence}
   *       — per Phase 6 §B.T.4, these come from per-instance prior-value tracking,
   *       not from a static mapping table.
   * </ul>
   *
   * <p>Per tempdoc 430 §A.10 Phase 10 + rev 3.15 §B.AB.1: exposed for {@code
   * HealthEventEmitCoverageTest} to assert every catalog ID in §A.2 has an emit site.
   */
  public static Set<String> emittableIds() {
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    for (ConditionMapping m : SCHEMA_COMPAT_TABLE.values()) {
      ids.add(m.conditionId());
    }
    for (ConditionMapping m : EMBEDDING_COMPAT_TABLE.values()) {
      ids.add(m.conditionId());
    }
    ids.add(REINDEX_MAPPING.conditionId());
    ids.add(QUEUE_DB_UNHEALTHY.conditionId());
    ids.add(QUEUE_DB_CHECK_FAILED.conditionId());
    // Phase 6 §B.T.4: the two occurrence events come from per-instance prior-value
    // tracking in detect{JobFailure,RetryScheduled}Occurrence — not from any static
    // mapping table. Declared as literals here so the coverage test sees the full
    // emittable surface.
    ids.add("worker.job.failed");
    ids.add("worker.job.retry-scheduled");
    return Set.copyOf(ids);
  }

  // ----- Substrate refs -----

  private final ConditionStore conditions;
  private final OccurrenceLog occurrences;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;

  // ----- Per-instance prior memory -----

  private String activeSchemaConditionId;
  private String activeEmbeddingConditionId;
  private boolean priorReindexRequired;
  private boolean priorQueueDbHealthy = true; // default healthy
  private boolean priorQueueDbCheckOk = true;
  private long priorLastFailedAtMs;
  private long priorNextRetryAtMs;

  // ----- WARN dedup (per §B.T.6) -----

  private final Set<String> warnedSchemaStates = ConcurrentHashMap.newKeySet();
  private final Set<String> warnedEmbeddingStates = ConcurrentHashMap.newKeySet();

  public WorkerSnapshotTap(
      ConditionStore conditions,
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Reconciles each worker-side field's current value with the {@link ConditionStore}
   * and {@link OccurrenceLog}. Called by {@code StatusLifecycleHandler} after the
   * readiness envelope has been built.
   *
   * <p>Skips emission entirely when {@code view == null} or {@code stale == true} —
   * unknown ≠ healthy (rev-3.6 §B.S Severity 1; §B.T.5).
   *
   * @param view the worker's current operational view (may be a fallback view if the
   *     worker is unavailable)
   * @param stale {@code true} when the head failed to refresh the view via gRPC
   *     (cached or fallback view); {@code false} when the view reflects a successful
   *     RPC. Sourced from {@code StatusLifecycleHandler.workerRpcStale}.
   */
  public synchronized void accept(WorkerOperationalView view, boolean stale) {
    if (view == null || stale) {
      return;
    }
    reconcileSchema(view.compatibility());
    reconcileReindex(view.compatibility());
    reconcileEmbedding(view.compatibility());
    reconcileQueueDbHealthy(view.queueDb());
    reconcileQueueDbCheck(view.queueDb());
    detectJobFailureOccurrence(view.failure());
    detectRetryScheduledOccurrence(view.failure());
  }

  // ----- Condition reconcile helpers (state-string fields) -----

  private void reconcileSchema(CompatibilityStatusView compat) {
    String state = compat.indexSchemaCompatState();
    ConditionMapping target = resolveSchemaTarget(state);
    boolean unmappedUnhealthy =
        !isHealthyState(state) && target == null;
    if (unmappedUnhealthy) {
      warnUnmappedSchemaState(state);
      return;
    }
    swapCondition(target, activeSchemaConditionId, compat.indexSchemaCompatState(), Optional.empty());
    activeSchemaConditionId = target == null ? null : target.conditionId();
  }

  private void reconcileEmbedding(CompatibilityStatusView compat) {
    String state = compat.embeddingCompatState();
    ConditionMapping target = resolveEmbeddingTarget(state);
    boolean unmappedUnhealthy =
        !isHealthyState(state) && target == null;
    if (unmappedUnhealthy) {
      warnUnmappedEmbeddingState(state);
      return;
    }
    Optional<String> message =
        compat.embeddingCompatReason().isEmpty()
            ? Optional.empty()
            : Optional.of(compat.embeddingCompatReason());
    swapCondition(target, activeEmbeddingConditionId, state, message);
    activeEmbeddingConditionId = target == null ? null : target.conditionId();
  }

  private static boolean isHealthyState(String state) {
    return state == null || state.isEmpty() || "COMPATIBLE".equals(state);
  }

  private static ConditionMapping resolveSchemaTarget(String state) {
    if (isHealthyState(state)) {
      return null;
    }
    return SCHEMA_COMPAT_TABLE.get(state);
  }

  private static ConditionMapping resolveEmbeddingTarget(String state) {
    if (isHealthyState(state)) {
      return null;
    }
    return EMBEDDING_COMPAT_TABLE.get(state);
  }

  private void warnUnmappedSchemaState(String state) {
    if (warnedSchemaStates.add(state)) {
      log.warn(
          "WorkerSnapshotTap: unmapped indexSchemaCompatState={}; preserving prior"
              + " assertion (unknown ≠ healthy)",
          state);
    }
  }

  private void warnUnmappedEmbeddingState(String state) {
    if (warnedEmbeddingStates.add(state)) {
      log.warn(
          "WorkerSnapshotTap: unmapped embeddingCompatState={}; preserving prior"
              + " assertion (unknown ≠ healthy)",
          state);
    }
  }

  /**
   * Generic clear-and-assert helper for state-string Condition slots. Clears the prior
   * conditionId if it differs from the new target; then upserts the new condition
   * (with reason derived from the source state value via PascalCase conversion).
   */
  private void swapCondition(
      ConditionMapping target,
      String priorConditionId,
      String reasonSource,
      Optional<String> message) {
    if (priorConditionId != null
        && (target == null || !priorConditionId.equals(target.conditionId()))) {
      // Subject is consistent for a given conditionId within a slot (e.g. schema.blocked
      // always maps to worker.schema). Recover via the static tables.
      String subject = subjectForConditionId(priorConditionId);
      if (subject != null) {
        clearCondition(priorConditionId, subject);
      }
    }
    if (target != null) {
      upsertCondition(target, pascalReason(reasonSource), message);
    }
  }

  /** Reverse-lookup: find the subject for a known active conditionId. */
  private static String subjectForConditionId(String conditionId) {
    for (ConditionMapping m : SCHEMA_COMPAT_TABLE.values()) {
      if (m.conditionId().equals(conditionId)) {
        return m.subject();
      }
    }
    for (ConditionMapping m : EMBEDDING_COMPAT_TABLE.values()) {
      if (m.conditionId().equals(conditionId)) {
        return m.subject();
      }
    }
    return null;
  }

  // ----- Condition reconcile helpers (boolean fields) -----

  private void reconcileReindex(CompatibilityStatusView compat) {
    boolean required = compat.reindexRequired();
    if (required) {
      Optional<String> message =
          compat.reindexRequiredReason().isEmpty()
              ? Optional.empty()
              : Optional.of(compat.reindexRequiredReason());
      upsertCondition(REINDEX_MAPPING, "ReindexRequired", message);
    } else if (priorReindexRequired) {
      // Was required, now isn't → clear the prior condition.
      clearCondition(REINDEX_MAPPING.conditionId(), REINDEX_MAPPING.subject());
    }
    priorReindexRequired = required;
  }

  private void reconcileQueueDbHealthy(QueueDbStatusView queueDb) {
    boolean healthy = queueDb.queueDbHealthy();
    if (!healthy) {
      upsertCondition(QUEUE_DB_UNHEALTHY, "QueueDbUnhealthy", Optional.empty());
    } else if (!priorQueueDbHealthy) {
      clearCondition(QUEUE_DB_UNHEALTHY.conditionId(), QUEUE_DB_UNHEALTHY.subject());
    }
    priorQueueDbHealthy = healthy;
  }

  private void reconcileQueueDbCheck(QueueDbStatusView queueDb) {
    boolean ok = queueDb.queueDbLastQuickCheckOk();
    if (!ok) {
      upsertCondition(QUEUE_DB_CHECK_FAILED, "QuickCheckFailed", Optional.empty());
    } else if (!priorQueueDbCheckOk) {
      clearCondition(QUEUE_DB_CHECK_FAILED.conditionId(), QUEUE_DB_CHECK_FAILED.subject());
    }
    priorQueueDbCheckOk = ok;
  }

  // ----- Occurrence detection -----

  private void detectJobFailureOccurrence(FailureTrackingView failure) {
    long ts = failure.lastFailedAtMs();
    if (ts == 0 || ts == priorLastFailedAtMs) {
      return; // no failure yet, or same failure as last time
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("path", failure.lastFailedPath());
    attributes.put("errorMessage", failure.lastFailedErrorMessage());
    attributes.put("atMs", ts);
    HealthEvent event =
        new HealthEvent(
            "worker.job.failed",
            clock.instant(),
            source,
            Severity.INFO,
            Optional.of("health-events.worker.job.failed.message"),
            new LifecycleEvent(attributes, Optional.empty()));
    occurrences.append(event);
    changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
    priorLastFailedAtMs = ts;
  }

  private void detectRetryScheduledOccurrence(FailureTrackingView failure) {
    long ts = failure.nextRetryAtMs();
    if (ts == 0 || ts == priorNextRetryAtMs) {
      return;
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("path", failure.lastFailedPath());
    attributes.put("nextRetryAtMs", ts);
    HealthEvent event =
        new HealthEvent(
            "worker.job.retry-scheduled",
            clock.instant(),
            source,
            Severity.INFO,
            Optional.of("health-events.worker.job.retry-scheduled.message"),
            new LifecycleEvent(attributes, Optional.empty()));
    occurrences.append(event);
    changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
    priorNextRetryAtMs = ts;
  }

  // ----- Common Condition helpers -----

  private void upsertCondition(
      ConditionMapping target, String reason, Optional<String> message) {
    HealthEvent event =
        new HealthEvent(
            target.conditionId(),
            clock.instant(),
            source,
            target.severity(),
            Optional.of("health-events." + target.conditionId() + ".message"),
            new AssertedCondition(
                target.subject(),
                ConditionStatus.TRUE, // §A.6 (rev-3.6): "named condition holds" = bad
                reason,
                clock.instant(), // ConditionStore preserves prior on UNCHANGED / status-equal
                message,
                target.recovery(), // 442 §B.9 + 447-impl-B: now carries OperationInvocation when mapping declared one
                List.of())); // relatedMetrics — slice 3a.1.4 Phase 6 populates for trend-correlated Conditions
    ConditionStore.Transition t = conditions.upsert(event);
    switch (t) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED ->
          changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        /* no broadcast — store preserved prior record */
      }
    }
  }

  private void clearCondition(String conditionId, String subject) {
    Optional<HealthEvent> removed = conditions.clear(conditionId, subject);
    removed.ifPresent(
        e -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, e));
  }

  /**
   * Converts a snake-case state value (e.g., {@code "BLOCKED_LEGACY"}) to a PascalCase
   * reason code (e.g., {@code "BlockedLegacy"}) per the k8s {@link AssertedCondition}
   * regex. Mirrors {@code LifecycleSnapshotTap.toPascalReason} but is a separate copy
   * to keep the tap classes self-contained.
   */
  static String pascalReason(String stateValue) {
    if (stateValue == null || stateValue.isBlank()) {
      return "Unknown";
    }
    String[] segments = stateValue.split("[._]");
    StringBuilder out = new StringBuilder();
    for (String segment : segments) {
      if (segment.isEmpty()) {
        continue;
      }
      out.append(Character.toUpperCase(segment.charAt(0)));
      if (segment.length() > 1) {
        out.append(segment.substring(1).toLowerCase());
      }
    }
    return out.length() == 0 ? "Unknown" : out.toString();
  }
}
