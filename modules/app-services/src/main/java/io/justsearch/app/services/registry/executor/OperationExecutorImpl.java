/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.agent.api.registry.TrustGateDeniedException;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.app.observability.advisory.OperationCompletionEvent;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationOutcome;
import io.justsearch.app.services.intent.IntentGateEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Trust-tier-aware {@link OperationDispatcher} implementation.
 *
 * <p>Per tempdoc 429 §C decision A: substrate types live in {@code app-agent-api},
 * behavior lives in {@code app-services}. This is the behavioral counterpart to the
 * substrate-side {@link OperationDispatcher} interface.
 *
 * <p>Per §A.5 + §B.D + §C.A.5: the trust-tier switch routes CORE through the handler
 * registry, TRUSTED_PLUGIN identically (V1's trust model is "you wrote it, or you
 * know who did" — equivalence is intentional, not dead code), UNTRUSTED_PLUGIN throws
 * with a V1.5 sandbox doc pointer.
 *
 * <p>Per §E.3: {@link #undo(Operation, String)} checks
 * {@code op.policy().undoSupported()} before delegating; operations without undo
 * support fail fast with a typed denial, never reaching the handler.
 */
public final class OperationExecutorImpl implements OperationDispatcher {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(OperationExecutorImpl.class);


  private final HandlerRegistry handlers;
  // Slice 444b: optional callback that receives one entry per completed dispatch.
  // Null in legacy/test wiring; non-null when HeadAssembly injects the
  // OperationHistoryStore + ChangeRegistry pair.
  private final Consumer<OperationHistoryEntry> historyEmitter;
  // Slice 490 §6.3 + Group B2 follow-up: routing table mapping advisory-class
  // ResourceRef → per-class emitter. Empty in legacy/test wiring; populated when
  // HeadAssembly injects the advisory substrate. When an Operation declares
  // {@code OperationPolicy.advisoryClass} present, the executor looks up the emitter
  // by ResourceRef and publishes the event. Future advisory classes register
  // additional entries here without touching the executor.
  private final Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters;
  private final Clock clock;
  // Slice 487 §4.4 + tempdoc 550 thesis III: the ONE intent-gate computation (source-tier
  // derivation + (SourceTier × RiskTier) lattice + Global Hard Stop override). Built from the
  // injected TrustEvaluator + IntentSourceCatalog; null in legacy/test wiring (deps absent), in
  // which case the dispatcher skips the lattice (backward compat). When present (production wiring
  // via HeadAssembly) the dispatcher enforces the gate between validateProvenance and
  // inputValidator.validate, and the SAME instance is shared with the Preview endpoint so a
  // preview can never disagree with enforcement (the F1 drift class is structurally impossible).
  private final IntentGateEvaluator intentGateEvaluator;
  private final java.util.function.Function<RequiredCapability, Boolean> capabilityResolver;
  // Tempdoc 550 Slice A1 (Authorize face): optional consent-capsule verifier. Null in
  // legacy/test wiring. When present, a valid bound capsule in the confirmation token
  // satisfies a non-AUTO gate with cryptographic proof of user approval for THIS exact
  // (operation, args) — ADDITIVELY, alongside the legacy non-blank-token path that
  // un-migrated callers (ActionButton, agent-loop) still use. Removing the legacy path
  // is the flagged wide migration (550 §Review-package C2), not done here.
  private final io.justsearch.agent.api.registry.ConsentCapsuleAuthority capsuleService;
  // Tempdoc 550 Outcome face: optional sink for trust-gate decisions. Null in legacy/test
  // wiring. When present, enforceTrustLattice emits one AuthorizationOutcomeEntry per non-AUTO
  // decision (GATED / DENIED / APPROVED) so the action ledger — and the 538 trust-firing audit
  // as a read-view of it — sees gate FIRINGS, not only completed-dispatch outcomes. ADDITIVE:
  // the emit is a pure side-effect; the gate's throw/return (fail-closed) semantics are
  // unchanged.
  private final Consumer<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>
      authorizationOutcomeEmitter;
  /**
   * Tempdoc 550 E2: wire the process-wide emergency stop the lattice consults. Forwarded into the
   * one {@link IntentGateEvaluator} (the verdict reflects it) so enforcement and the shared Preview
   * read the same hard-stop state. Late-bind, like other optional collaborators. No-op when the
   * lattice is absent (legacy/test wiring).
   */
  public void setGlobalHardStop(GlobalHardStop hardStop) {
    if (intentGateEvaluator != null) {
      intentGateEvaluator.setHardStopSignal(hardStop != null ? hardStop::isEngaged : null);
    }
  }

  /** The shared intent-gate computation (tempdoc 550 thesis III); null when the lattice is absent. */
  public IntentGateEvaluator intentGateEvaluator() {
    return intentGateEvaluator;
  }

  // Tempdoc 550 thesis IV: optional durable "allow-always" grants. When a durable grant covers
  // (operation, sourceTier), a non-AUTO gate is satisfied WITHOUT a fresh capsule. Late-bind
  // (null = no durable grants wired; legacy/test paths).
  private volatile io.justsearch.app.services.intent.DurableGrantStore durableGrantStore;

  /** Wire the durable allow-always grant store the gate consults (tempdoc 550 thesis IV). */
  public void setDurableGrantStore(io.justsearch.app.services.intent.DurableGrantStore store) {
    this.durableGrantStore = store;
  }
  // Slice 3a-2-c Phase C: declarative input-schema validation. The validator
  // is process-singleton (cached compiled schemas keyed by OperationRef) so
  // creating it per-executor is fine; concurrent dispatch reuses compiled
  // schemas via ConcurrentHashMap.
  private final OperationInputSchemaValidator inputValidator =
      new OperationInputSchemaValidator();

  public OperationExecutorImpl(HandlerRegistry handlers) {
    this(handlers, null, Map.of(), Clock.systemUTC(), null, null);
  }

  /** Pre-slice-490 constructor — legacy callers compile unchanged with no advisory wiring. */
  public OperationExecutorImpl(
      HandlerRegistry handlers, Consumer<OperationHistoryEntry> historyEmitter, Clock clock) {
    this(handlers, historyEmitter, Map.of(), clock, null, null);
  }

  /**
   * Pre-Group-B2 constructor — single-emitter form (slice 490 §6.3 v1 shape). The
   * single emitter is registered under
   * {@code core.advisory-operation-completed}'s ResourceRef. New callers should
   * use the {@code Map} form directly.
   */
  public OperationExecutorImpl(
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Consumer<OperationCompletionEvent> advisoryEmitter,
      Clock clock) {
    this(
        handlers,
        historyEmitter,
        advisoryEmitter == null
            ? Map.of()
            : Map.of(
                new ResourceRef("core.advisory-operation-completed"), advisoryEmitter),
        clock,
        null,
        null);
  }

  /**
   * Slice 490 Group B2 constructor — multi-emitter routing form. Pre-slice-487 callers
   * (no trust lattice). Delegates to the slice-487 6-arg form with null lattice deps.
   */
  public OperationExecutorImpl(
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock) {
    this(handlers, historyEmitter, advisoryEmitters, clock, null, null);
  }

  /**
   * Slice 487 §4.4 canonical constructor — accepts the trust lattice + source-tier
   * lookup. Production wiring (HeadAssembly) uses this form; the lattice fires
   * for every dispatch.
   *
   * <p>When {@code trustEvaluator} or {@code intentSourceCatalog} is null, the lattice
   * is skipped (legacy/test compat). Both must be present for the lattice to enforce.
   */
  public OperationExecutorImpl(
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog) {
    this(handlers, historyEmitter, advisoryEmitters, clock, trustEvaluator, intentSourceCatalog, null);
  }

  /**
   * Tempdoc 502 §B1: canonical constructor with capability resolver. When
   * {@code capabilityResolver} is non-null, the dispatcher checks each operation's
   * {@code requiredCapabilities} before invoking the handler. If any required
   * capability is unavailable, dispatch returns CAPABILITY_UNAVAILABLE without
   * reaching the handler.
   */
  public OperationExecutorImpl(
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog,
      java.util.function.Function<RequiredCapability, Boolean> capabilityResolver) {
    this(
        handlers,
        historyEmitter,
        advisoryEmitters,
        clock,
        trustEvaluator,
        intentSourceCatalog,
        capabilityResolver,
        null);
  }

  /**
   * Tempdoc 550 Slice A1 canonical constructor — adds the optional {@link
   * io.justsearch.app.services.intent.ConsentCapsuleService}. When non-null, a valid
   * consent capsule satisfies a non-AUTO gate (additive to the legacy non-blank-token
   * path). Production wiring (HeadAssembly / OperationSubstrateInit) uses this form.
   */
  public OperationExecutorImpl(
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog,
      java.util.function.Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority capsuleService) {
    this(
        handlers,
        historyEmitter,
        advisoryEmitters,
        clock,
        trustEvaluator,
        intentSourceCatalog,
        capabilityResolver,
        capsuleService,
        null);
  }

  /**
   * Tempdoc 550 Outcome-face canonical constructor — adds the optional gate-decision sink
   * ({@code authorizationOutcomeEmitter}). When non-null, every non-AUTO trust-gate decision
   * is recorded so the action ledger / trust audit can read gate firings. Production wiring
   * (OperationSubstrateInit) uses this form.
   */
  public OperationExecutorImpl(
      HandlerRegistry handlers,
      Consumer<OperationHistoryEntry> historyEmitter,
      Map<ResourceRef, Consumer<OperationCompletionEvent>> advisoryEmitters,
      Clock clock,
      TrustEvaluator trustEvaluator,
      IntentSourceCatalog intentSourceCatalog,
      java.util.function.Function<RequiredCapability, Boolean> capabilityResolver,
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority capsuleService,
      Consumer<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>
          authorizationOutcomeEmitter) {
    this.handlers = Objects.requireNonNull(handlers, "handlers");
    this.historyEmitter = historyEmitter;
    this.advisoryEmitters =
        advisoryEmitters == null ? Map.of() : Map.copyOf(advisoryEmitters);
    this.clock = Objects.requireNonNull(clock, "clock");
    // Tempdoc 550 thesis III: collapse source-tier derivation + lattice + hard-stop into the one
    // IntentGateEvaluator. Absent trust deps (legacy/test wiring) → null → lattice skipped.
    this.intentGateEvaluator =
        (trustEvaluator != null && intentSourceCatalog != null)
            ? new IntentGateEvaluator(trustEvaluator, intentSourceCatalog)
            : null;
    this.capabilityResolver = capabilityResolver;
    this.capsuleService = capsuleService;
    this.authorizationOutcomeEmitter = authorizationOutcomeEmitter;
  }

  @Override
  public OperationResult dispatch(Operation op, String argumentsJson) {
    // Legacy 2-arg overload — defaults to system-internal provenance. Per slice 490
    // §4.B, new call sites should use the 3-arg overload to carry transport / executor
    // context. The fallback preserves observed shape for tests, agent-loop callers,
    // and any code path that has not yet been threaded for provenance.
    return dispatch(op, argumentsJson, InvocationProvenance.systemInternal(clock.instant()));
  }

  @Override
  public OperationResult dispatch(
      Operation op, String argumentsJson, InvocationProvenance provenance) {
    return dispatch(op, argumentsJson, provenance, Optional.empty());
  }

  @Override
  public OperationResult dispatch(
      Operation op,
      String argumentsJson,
      InvocationProvenance provenance,
      Optional<String> confirmationToken) {
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(confirmationToken, "confirmationToken");
    // Slice 490 follow-up — provenance integrity validation. A {@code TRUSTED_PLUGIN}
    // caller cannot spoof user-facing transports (BUTTON / URL_BAR / LLM_EMISSION etc.)
    // — only system-tier or plugin-tier transports are admissible. {@code CORE}
    // dispatch is unrestricted (callers within the head process are trusted to claim
    // their actual transport); {@code UNTRUSTED_PLUGIN} throws below regardless.
    validateProvenance(op, provenance);

    // Slice 487 §4.4: (SourceTier × RiskTier) → GateBehavior lattice. Runs between
    // validateProvenance (transport spoofing defense) and inputValidator.validate
    // (schema validation). Skipped silently when trust deps absent (legacy/test wiring).
    if (intentGateEvaluator != null) {
      enforceTrustLattice(op, argumentsJson, provenance, confirmationToken);
    }

    // Tempdoc 502 §B1: check required capabilities before dispatch.
    if (capabilityResolver != null) {
      var missingCap = checkCapabilities(op);
      if (missingCap != null) {
        Instant t = clock.instant();
        String msg = "Required capability unavailable: " + missingCap;
        OperationResult denied =
            OperationResult.failure(msg, "CAPABILITY_UNAVAILABLE", Map.of("capability", missingCap), true);
        emitHistory(op, t, OperationOutcome.FAILURE, msg, provenance, Optional.empty());
        return denied;
      }
    }

    Instant startTime = clock.instant();

    // Slice 3a-2-c Phase C: validate args against the declared input schema
    // BEFORE invoking the handler. Catches "missing required arg" / "wrong
    // type" centrally so per-handler ad-hoc parsing simplifies. On invalid
    // args, return a typed BAD_REQUEST failure WITHOUT calling the handler;
    // emit FAILURE history (consistent with uncaught-exception path).
    var validationFailure = inputValidator.validate(op, argumentsJson);
    if (validationFailure.isPresent()) {
      OperationInputSchemaValidator.ValidationResult vr = validationFailure.get();
      OperationResult invalid =
          OperationResult.failure(vr.message(), "BAD_REQUEST", vr.details(), false);
      emitHistory(op, startTime, OperationOutcome.FAILURE, vr.message(), provenance, Optional.empty());
      return invalid;
    }

    OperationResult result;
    try {
      result =
          switch (op.provenance().tier()) {
            case CORE -> dispatchCore(op, argumentsJson, provenance);
            case TRUSTED_PLUGIN -> dispatchTrustedPlugin(op, argumentsJson, provenance);
            case UNTRUSTED_PLUGIN -> throw new UnsupportedOperationException(
                "Untrusted plugin operations require V1.5 sandbox infrastructure. "
                    + "See docs/tempdocs/421-frontend-destination-architecture/421-stack.md "
                    + "§Plugin trust model — V1.5 tier.");
          };
    } catch (RuntimeException e) {
      // Per slice 444b: still emit a FAILURE history entry on uncaught dispatch error
      // before propagating. The thrown exception is the truthful result; the entry
      // captures that the dispatch happened.
      emitHistory(op, startTime, OperationOutcome.FAILURE, e.getMessage(), provenance, Optional.empty());
      throw e;
    }
    OperationOutcome outcome = result.success() ? OperationOutcome.SUCCESS : OperationOutcome.FAILURE;
    Optional<String> undoExecutionId =
        (outcome == OperationOutcome.SUCCESS && op.policy().undoSupported())
            ? result.executionId()
            : Optional.empty();
    emitHistory(op, startTime, outcome, null, provenance, undoExecutionId);
    return result;
  }

  private void emitHistory(
      Operation op,
      Instant startTime,
      OperationOutcome outcome,
      String diagnosticsLink,
      InvocationProvenance provenance,
      Optional<String> executionId) {
    Instant completedAt = clock.instant();
    if (historyEmitter != null) {
      try {
        historyEmitter.accept(
            new OperationHistoryEntry(
                op.id(),
                // Slice 444b: head-process identifier. Per slice 490 §4.B the canonical
                // answer to "who triggered this?" is the typed {@code provenance} field
                // (initiator + transport + executor). The {@code actor} string remains
                // literal "head" for wire-shape stability — derivation from provenance
                // would be a silent contract change for FE / MCP / audit consumers that
                // grep on the value.
                "head",
                // Slice 444b §B.D: empty until the Operation.policy.audit axis drives
                // redaction. The previous "(redacted)" literal was uninformative.
                Optional.empty(),
                startTime,
                completedAt,
                outcome,
                Optional.ofNullable(diagnosticsLink),
                provenance,
                // Tempdoc 550 G6: carry the backend execution id (undo-supported ops) so the
                // unified ledger can collapse this row with the FE Effect Journal entry that
                // dispatched it (journalEntryId ↔ executionId).
                executionId));
      } catch (RuntimeException e) {
        // History emission must never break dispatch. Swallow with no log to avoid
        // recursive log paths; a separate health-event would surface persistent breakage.
      }
    }
    // Slice 490 §6.3 + Group B2 follow-up: per-Operation typed-class advisory
    // emission. The dispatcher looks up the operation's declared
    // {@code OperationPolicy.advisoryClass} (Optional<ResourceRef>) in the routing
    // table and fires the matching emitter. Missing entry means the operation
    // declared a class no emitter is registered for — log-only (don't break
    // dispatch). Empty Optional means the operation opted out — no emission.
    Optional<ResourceRef> advisoryClass = op.policy().advisoryClass();
    if (advisoryClass.isPresent()) {
      Consumer<OperationCompletionEvent> emitter =
          advisoryEmitters.get(advisoryClass.get());
      if (emitter != null) {
        try {
          emitter.accept(
              new OperationCompletionEvent(
                  op.id(),
                  outcome,
                  completedAt,
                  Optional.ofNullable(diagnosticsLink),
                  provenance,
                  executionId));
        } catch (RuntimeException e) {
          // Same discipline as historyEmitter: advisory emission must never break
          // dispatch. Swallow with no log to avoid recursive paths.
        }
      }
    }
  }

  private String checkCapabilities(Operation op) {
    var required = op.policy().requiredCapabilities();
    if (required.isEmpty()) return null;
    for (RequiredCapability req : required) {
      if (!Boolean.TRUE.equals(capabilityResolver.apply(req))) {
        return switch (req) {
          case RequiredCapability.WorkerOnline w -> "worker-online";
          case RequiredCapability.InferenceOnline i -> "inference-online";
          case RequiredCapability.IndexedRoot r -> "indexed-root";
          case RequiredCapability.GpuAvailable g -> "gpu-available";
        };
      }
    }
    return null;
  }

  /**
   * Slice 490 follow-up — provenance-integrity validation. The dispatcher's caller may
   * supply any {@link InvocationProvenance}; this validator rejects user-facing
   * {@link TransportTag} values when the registered Operation comes from a plugin-tier
   * declaration. Rationale: plugin code dispatching an Operation cannot claim its
   * dispatch arrived "from a button click" or "from an LLM emission" without
   * compromising the audit trail; only plugin-tier or system-tier transports are
   * admissible. {@code CORE}-tier Operations are dispatched by trusted in-process code
   * and may claim any transport — they're the producers of user-facing transport
   * claims (e.g. {@code OperationsController}'s {@link InvocationProvenance#uiButton}).
   *
   * <p>Throws {@link IllegalArgumentException} on rejection. The dispatcher's caller is
   * expected to either supply a substrate-supplied factory (e.g.
   * {@link InvocationProvenance#systemInternal}) or know what tier its Operation is.
   */
  private static void validateProvenance(Operation op, InvocationProvenance provenance) {
    TrustTier tier = op.provenance().tier();
    if (tier == TrustTier.CORE) {
      return; // CORE callers may claim any transport.
    }
    if (tier == TrustTier.TRUSTED_PLUGIN) {
      switch (provenance.transport()) {
        case PLUGIN_EMITTED, SYSTEM_INTERNAL, AGENT_LOOP, WORKFLOW, SCHEDULED, RULE_ENGINE -> {
          return;
        }
        default ->
            throw new IllegalArgumentException(
                "Operation "
                    + op.id().value()
                    + " is TRUSTED_PLUGIN-tier; transport "
                    + provenance.transport()
                    + " is not admissible. Allowed: PLUGIN_EMITTED, SYSTEM_INTERNAL, "
                    + "AGENT_LOOP, WORKFLOW, SCHEDULED, RULE_ENGINE.");
      }
    }
    // UNTRUSTED_PLUGIN is rejected later in the dispatch switch with the V1.5 sandbox
    // doc pointer; no transport validation needed here.
  }

  @Override
  public OperationResult undo(Operation op, String executionId) {
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(executionId, "executionId");
    if (!op.policy().undoSupported()) {
      return OperationResult.failure("Undo not supported by " + op.id().value());
    }
    if (capabilityResolver != null) {
      var missingCap = checkCapabilities(op);
      if (missingCap != null) {
        return OperationResult.failure(
            "Required capability unavailable for undo: " + missingCap,
            "CAPABILITY_UNAVAILABLE",
            Map.of("capability", missingCap),
            true);
      }
    }
    OperationHandler handler =
        handlers
            .resolve(new OperationRef(op.binding().handlerId()))
            .orElseThrow(
                () -> new IllegalStateException(
                    "No handler registered for binding " + op.binding().handlerId()));
    Instant startTime = clock.instant();
    OperationResult result;
    try {
      result = handler.undo(executionId);
    } catch (RuntimeException e) {
      emitHistory(op, startTime, OperationOutcome.FAILURE, e.getMessage(),
          InvocationProvenance.systemInternal(startTime), Optional.empty());
      throw e;
    }
    OperationOutcome outcome = result.success() ? OperationOutcome.UNDONE : OperationOutcome.FAILURE;
    emitHistory(op, startTime, outcome, null,
        InvocationProvenance.systemInternal(startTime), Optional.empty());
    return result;
  }

  private OperationResult dispatchCore(
      Operation op, String argumentsJson, InvocationProvenance provenance) {
    OperationHandler handler =
        handlers
            .resolve(new OperationRef(op.binding().handlerId()))
            .orElseThrow(
                () -> new IllegalStateException(
                    "No handler registered for binding " + op.binding().handlerId()));
    // Slice 491 F6: dispatch with the context-aware overload so handlers that need
    // transport / source-tier visibility (e.g., NavigateToSurfaceHandler) read it
    // from provenance. Handlers that don't override the overload get the default
    // delegation to execute(argumentsJson) — no behavior change.
    return handler.execute(argumentsJson, provenance);
  }

  private OperationResult dispatchTrustedPlugin(
      Operation op, String argumentsJson, InvocationProvenance provenance) {
    // V1's trust model is "you wrote it, or you know who did" — TRUSTED_PLUGIN
    // operations execute equivalently to CORE in V1. This is intentional, not dead
    // code: V1 plugins (per slice 3a.7) DO produce TRUSTED_PLUGIN-marked operations
    // and route through this branch. V1.5 will add a policy floor here (e.g., risk
    // minimum lifted to MEDIUM regardless of declaration). Per §B.D the equivalence
    // is a semantic statement about V1's trust model, not a code-smell stub.
    if (op.provenance().tier() != TrustTier.TRUSTED_PLUGIN) {
      throw new IllegalStateException("Expected TRUSTED_PLUGIN, got " + op.provenance().tier());
    }
    return dispatchCore(op, argumentsJson, provenance);
  }

  /**
   * Slice 487 §4.4: composes the source-side {@link SourceTier} (derived from the
   * intent source's catalog entry via the transport tag) with the operation-side
   * {@link io.justsearch.agent.api.registry.RiskTier} into a {@link GateBehavior},
   * then enforces:
   *
   * <ul>
   *   <li>{@code AUTO} — proceed; no further action.
   *   <li>{@code INLINE_CONFIRM} / {@code TYPED_CONFIRM} — require a non-empty
   *       confirmation token. Absent → throw {@link ConfirmationRequiredException}.
   *       The caller (HTTP endpoint, agent loop) catches and surfaces the
   *       trust-aware elicitation UX, then re-dispatches with a token.
   *   <li>{@code DENY} — throw {@link TrustGateDeniedException}. Today's V1
   *       lattice cell values produce no DENY outcomes; the path is reserved.
   * </ul>
   *
   * <p>{@code SourceTier} fallback: when the transport has no registered
   * {@code IntentSource} in the catalog (an unregistered ingress — Pass-9
   * commitment 4 violation), the lattice treats the dispatch as
   * {@code UNTRUSTED} so the gate behavior errs on the side of caution. The
   * BackendIntentRouter logs the unregistered-ingress condition separately.
   */
  private void enforceTrustLattice(
      Operation op,
      String argumentsJson,
      InvocationProvenance provenance,
      Optional<String> confirmationToken) {
    // Tempdoc 550 thesis III: ONE structural verdict (derived source tier + (SourceTier × RiskTier)
    // lattice gate + Global Hard Stop override), the same computation/instance the Preview endpoint
    // reads. The E2 hard-stop DENY (engaged → DENY every UNTRUSTED dispatch, user-driven untouched)
    // is folded into the verdict's gate, so the DENY case below records DENIED + throws — the
    // circuit breaker enforced at the sole chokepoint, outside the agent's control.
    IntentGateEvaluator.IntentVerdict verdict =
        intentGateEvaluator.evaluate(op.policy().risk(), provenance.transport());
    SourceTier sourceTier = verdict.sourceTier();
    GateBehavior gate = verdict.gateBehavior();
    switch (gate) {
      case AUTO -> {
        // proceed
      }
      case INLINE_CONFIRM, TYPED_CONFIRM -> {
        // Tempdoc 550 thesis IV + 560 §28 (4d): a durable "allow-always" grant satisfies the gate
        // without a fresh capsule — either a per-operation grant, or a grant for this operation's
        // declared capability family (the wider caveat). Checked BEFORE the capsule; recorded APPROVED.
        var durable = this.durableGrantStore;
        if (durable != null
            && durable.isAllowed(op.id().value(), op.policy().capabilityFamily(), sourceTier)) {
          emitGateOutcome(op, provenance, sourceTier, gate,
              io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED);
          return; // durable-grant-satisfied
        }
        String token = confirmationToken.orElse("");
        // Tempdoc 550 A1 + C2 (steps 3+4 complete): the ONLY thing that satisfies a
        // non-AUTO gate is a valid consent capsule — bound to THIS (operation, args),
        // single-use, unexpired, unforgeable without the per-process session key. This
        // holds for ALL source tiers; the V1 nominal token (any non-blank string) is gone.
        // Every FE caller that can reach a non-AUTO gate mints a capsule at the user-approval
        // gesture: ActionButton + OpButton (TRUSTED×HIGH), the Effect typed-confirm path
        // (WA-1), and BrainSurface's host-API invoke (WA-2). The URL/deeplink (MEDIUM),
        // agent-loop, and MCP paths carry no token and are correctly gated until they too
        // route through an approval that mints a capsule (C3 ceremony). With the nominal
        // path removed, the audit caller-migration is complete: a fabricated or stale
        // non-capsule token from ANY source now fails closed.
        if (capsuleService != null
            && capsuleService.verifyAndConsume(token, op.id().value(), argumentsJson)) {
          // Tempdoc 550 Outcome face: record the gate firing as APPROVED, then proceed.
          emitGateOutcome(op, provenance, sourceTier, gate,
              io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED);
          return; // capsule-satisfied
        }
        emitGateOutcome(op, provenance, sourceTier, gate,
            io.justsearch.app.observability.operations.AuthorizationDisposition.GATED);
        throw new ConfirmationRequiredException(op.id(), gate, op.policy().confirm(), sourceTier);
      }
      case DENY -> {
        emitGateOutcome(op, provenance, sourceTier, gate,
            io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED);
        throw new TrustGateDeniedException(op.id(), sourceTier);
      }
    }
  }

  /**
   * Tempdoc 550 Outcome face: record one trust-gate decision (no-op when the sink is unwired).
   * A pure side-effect — it does not alter the gate's throw/return (fail-closed) semantics.
   */
  private void emitGateOutcome(
      Operation op,
      InvocationProvenance provenance,
      SourceTier sourceTier,
      GateBehavior gate,
      io.justsearch.app.observability.operations.AuthorizationDisposition disposition) {
    if (authorizationOutcomeEmitter == null) {
      return;
    }
    try {
      authorizationOutcomeEmitter.accept(
          new io.justsearch.app.observability.operations.AuthorizationOutcomeEntry(
              op.id().value(),
              provenance.transport(),
              sourceTier,
              op.policy().risk(),
              gate,
              disposition,
              clock.instant()));
    } catch (RuntimeException e) {
      // Security-gate-adjacent (tempdoc 550): the gate-firing emit is ADDITIVE. The emitter now
      // also fans the firing into the unified action-ledger change-stream (SSE publish +
      // subscriber callbacks) — a wider failure surface. It must NEVER alter the gate's
      // fail-closed semantics: a throw here would replace the ConfirmationRequired/TrustGateDenied
      // throw (or, on the capsule-APPROVED path, the proceed-return) with an unexpected failure.
      // Same discipline as emitHistory: swallow so the gate decision stands. P3 (tempdoc 550):
      // the swallow is no longer silent — a WARN surfaces a persistent emission failure (the gate
      // decision still stood; only the ledger/audit record of it was lost). Safe to log here: the
      // emitter (AuthorizationOutcomeStore append + ledger SSE broadcast) does not log back into
      // the gate path, so there's no recursive-log hazard. (A metric counter is a follow-on if a
      // registry is wired into the executor.)
      LOG.warn(
          "Trust-gate outcome emit failed for op={} disposition={} ({}); gate decision stood, "
              + "ledger/audit record lost",
          op.id().value(),
          disposition,
          e.toString());
    }
  }
}
