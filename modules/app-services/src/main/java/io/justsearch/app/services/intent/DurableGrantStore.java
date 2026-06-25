/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 550 thesis IV + 560 §28 (4d) — the durable "allow-always" grant: the second member of the
 * one Grant model (alongside the single-use consent capsule).
 *
 * <p>A durable grant says "auto-approve future invocations without re-prompting." Two scopes realize
 * the two positions of the {@link Grant} model's caveat:
 *
 * <ul>
 *   <li>an <b>operation grant</b> {@code (operationId, sourceTier)} — args-independent allow-always for
 *       exactly one operation (the per-op position);
 *   <li>a <b>family grant</b> {@code (capabilityFamily, sourceTier)} — allow-always for every operation
 *       declaring that {@link io.justsearch.agent.api.registry.OperationPolicy#capabilityFamily() family}
 *       (the {@link Grant.CapabilityFamily} position, now realized — wider than one operation). The bare
 *       {@code CapabilityFamily} scope is fail-closed because op→family membership is not self-resolvable;
 *       this store IS the consumer that resolves it, via the family the executor reads off the policy.
 * </ul>
 *
 * <p>It is revocable (operation, family, or — for the Global Hard Stop — every non-user grant at once)
 * and audited via the one action-event log.
 *
 * <p><b>Persistence (560 §28).</b> Grants are persisted to {@code $JUSTSEARCH_HOME/ui/durable-grants.json}
 * so an allow-always survives a restart (previously process-lifetime only — a restart silently dropped
 * the grant, re-prompting the user). Persistence is mode-aware ({@link PersistenceMode}): READ_WRITE for
 * real use, IN_MEMORY for prod/CI isolation and tests (the no-arg constructors). Mirrors
 * {@code UiSettingsStore} / {@code FileConversationStore}; best-effort writes (a failure is logged,
 * never alters grant semantics).
 */
public final class DurableGrantStore {

  private static final Logger LOG = LoggerFactory.getLogger(DurableGrantStore.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** An operation grant: args-independent allow-always for one operation from a source tier. */
  private record OperationKey(String operationId, SourceTier sourceTier) {}

  /** A family grant: allow-always for every operation in a capability family from a source tier. */
  private record FamilyKey(String family, SourceTier sourceTier) {}

  private final java.util.Set<OperationKey> grantedOps = ConcurrentHashMap.newKeySet();
  private final java.util.Set<FamilyKey> grantedFamilies = ConcurrentHashMap.newKeySet();
  private final Clock clock;
  /** Persistence target; {@code null} ⇒ in-memory only (tests / prod isolation). */
  private final Path persistenceFile;
  private volatile Consumer<ActionEvent> grantEventSink;

  public DurableGrantStore() {
    this(Clock.systemUTC());
  }

  public DurableGrantStore(Clock clock) {
    this(clock, null);
  }

  public DurableGrantStore(Clock clock, Path persistenceFile) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.persistenceFile = persistenceFile;
    load();
  }

  /**
   * 560 §28 — a persistence-backed store at the default location, mode-aware: READ_WRITE persists to
   * {@code $JUSTSEARCH_HOME/ui/durable-grants.json}; IN_MEMORY (prod/CI/test isolation) stays in-memory.
   */
  public static DurableGrantStore persistent() {
    return persistent(Clock.systemUTC());
  }

  public static DurableGrantStore persistent(Clock clock) {
    PersistenceMode mode = PersistenceMode.resolveMode();
    Path file = mode.isWritable() ? resolveDefaultGrantsFile() : null;
    return new DurableGrantStore(clock, file);
  }

  /** Wire the action-event log sink that records durable-grant lifecycle (tempdoc 550 thesis IV). */
  public void setGrantEventSink(Consumer<ActionEvent> sink) {
    this.grantEventSink = sink;
  }

  // ── Operation grants ────────────────────────────────────────────────────────────────────────────

  /** Record an allow-always grant for {@code operationId} at {@code sourceTier} (idempotent). */
  public void grantAllowAlways(String operationId, SourceTier sourceTier) {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(sourceTier, "sourceTier");
    if (grantedOps.add(new OperationKey(operationId, sourceTier))) {
      persist();
      emit("durable:" + sourceTier + ":" + operationId, operationId, "GRANTED_ALWAYS");
    }
  }

  /** Revoke a single operation allow-always grant. */
  public void revoke(String operationId, SourceTier sourceTier) {
    if (grantedOps.remove(new OperationKey(operationId, sourceTier))) {
      persist();
      emit("durable:" + sourceTier + ":" + operationId, operationId, "REVOKED");
    }
  }

  // ── Family grants (560 §28 / 4d) ──────────────────────────────────────────────────────────────────

  /** Record an allow-always grant for an entire capability {@code family} at {@code sourceTier}. */
  public void grantFamilyAllowAlways(String family, SourceTier sourceTier) {
    Objects.requireNonNull(family, "family");
    Objects.requireNonNull(sourceTier, "sourceTier");
    if (grantedFamilies.add(new FamilyKey(family, sourceTier))) {
      persist();
      emit("durable-family:" + sourceTier + ":" + family, family, "GRANTED_ALWAYS");
    }
  }

  /** Revoke a family allow-always grant. */
  public void revokeFamily(String family, SourceTier sourceTier) {
    if (grantedFamilies.remove(new FamilyKey(family, sourceTier))) {
      persist();
      emit("durable-family:" + sourceTier + ":" + family, family, "REVOKED");
    }
  }

  // ── Gate query ────────────────────────────────────────────────────────────────────────────────────

  /** True if an operation grant covers {@code operationId} from {@code sourceTier} (no family check). */
  public boolean isAllowed(String operationId, SourceTier sourceTier) {
    return isAllowed(operationId, Optional.empty(), sourceTier);
  }

  /**
   * True if a durable grant covers {@code operationId} from {@code sourceTier} — either a per-operation
   * grant, or (when the operation declares one) a grant for its {@code capabilityFamily}. The executor
   * resolves the family off the operation's policy and passes it here; this is the consumer that makes
   * the {@link Grant.CapabilityFamily} scope real.
   */
  public boolean isAllowed(
      String operationId, Optional<String> capabilityFamily, SourceTier sourceTier) {
    if (grantedOps.contains(new OperationKey(operationId, sourceTier))) {
      return true;
    }
    return capabilityFamily
        .map(family -> grantedFamilies.contains(new FamilyKey(family, sourceTier)))
        .orElse(false);
  }

  /**
   * Revoke every non-user ({@code UNTRUSTED}) durable grant — operation AND family — the consumer the
   * Global Hard Stop drives, matching the gate's hard-stop scope.
   */
  public void revokeNonUser() {
    boolean changed = false;
    for (OperationKey key : List.copyOf(grantedOps)) {
      if (key.sourceTier() == SourceTier.UNTRUSTED && grantedOps.remove(key)) {
        changed = true;
        emit(
            "durable:" + key.sourceTier() + ":" + key.operationId(),
            key.operationId(),
            "REVOKED");
      }
    }
    for (FamilyKey key : List.copyOf(grantedFamilies)) {
      if (key.sourceTier() == SourceTier.UNTRUSTED && grantedFamilies.remove(key)) {
        changed = true;
        emit(
            "durable-family:" + key.sourceTier() + ":" + key.family(),
            key.family(),
            "REVOKED");
      }
    }
    if (changed) {
      persist();
    }
  }

  // ── Read model ──────────────────────────────────────────────────────────────────────────────────

  /** The kind of durable grant — which caveat scope it realizes. */
  public enum GrantKind {
    OPERATION,
    FAMILY
  }

  /** A read-model row of one durable grant (operation or family) — for a management surface. */
  public record DurableGrant(GrantKind kind, String target, SourceTier sourceTier) {}

  /** A snapshot of all current durable grants (operation + family) — for a management view. */
  public List<DurableGrant> snapshot() {
    List<DurableGrant> out = new ArrayList<>(grantedOps.size() + grantedFamilies.size());
    for (OperationKey k : grantedOps) {
      out.add(new DurableGrant(GrantKind.OPERATION, k.operationId(), k.sourceTier()));
    }
    for (FamilyKey k : grantedFamilies) {
      out.add(new DurableGrant(GrantKind.FAMILY, k.family(), k.sourceTier()));
    }
    return List.copyOf(out);
  }

  // ── Persistence ─────────────────────────────────────────────────────────────────────────────────

  /** Serialized shape on disk: one flat list of grants (kind + target + tier). */
  private record PersistedState(List<DurableGrant> grants) {}

  private void load() {
    if (persistenceFile == null || !Files.exists(persistenceFile)) {
      return;
    }
    try {
      PersistedState state = MAPPER.readValue(persistenceFile.toFile(), PersistedState.class);
      if (state == null || state.grants() == null) {
        return;
      }
      for (DurableGrant g : state.grants()) {
        if (g.kind() == GrantKind.OPERATION) {
          grantedOps.add(new OperationKey(g.target(), g.sourceTier()));
        } else {
          grantedFamilies.add(new FamilyKey(g.target(), g.sourceTier()));
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to read durable grants (starting empty)", e);
    }
  }

  private void persist() {
    if (persistenceFile == null) {
      return;
    }
    try {
      Files.createDirectories(persistenceFile.getParent());
      MAPPER.writeValue(persistenceFile.toFile(), new PersistedState(snapshot()));
    } catch (IOException e) {
      LOG.warn("Failed to persist durable grants", e);
    }
  }

  /** Default grants file — a sibling of {@code settings.json}, resolved like {@code UiSettingsStore}. */
  private static Path resolveDefaultGrantsFile() {
    String homeOverride = EnvRegistry.HOME.get().orElse(null);
    Path base;
    if (homeOverride != null && !homeOverride.isBlank()) {
      base = Path.of(homeOverride);
    } else {
      Path userHome = PlatformPaths.resolveUserHome();
      if (PlatformPaths.isWindows()) {
        base = userHome.resolve("AppData").resolve("Roaming").resolve("justsearch");
      } else if (PlatformPaths.isMac()) {
        base = userHome.resolve("Library").resolve("Application Support").resolve("justsearch");
      } else {
        base = userHome.resolve(".config").resolve("justsearch");
      }
    }
    return base.resolve("ui").resolve("durable-grants.json");
  }

  private void emit(String idTail, String target, String action) {
    Consumer<ActionEvent> sink = this.grantEventSink;
    if (sink == null) {
      return;
    }
    try {
      sink.accept(
          new ActionEvent.Grant(
              "grant:" + action + ":" + idTail,
              clock.instant(),
              "user",
              "ALLOW_ALWAYS",
              idTail,
              action,
              target));
    } catch (RuntimeException ignored) {
      // Best-effort audit — never alter grant/revoke semantics (fail-closed discipline).
    }
  }
}
