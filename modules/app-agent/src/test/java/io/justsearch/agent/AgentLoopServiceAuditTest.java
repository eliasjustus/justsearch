package io.justsearch.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.agent.api.registry.OperationDispatcher;
import org.junit.jupiter.api.Test;

/**
 * Slice 487 Phase 1.7 §6.1 audit-test gate (post-impl fix A2: ArchUnit migration).
 *
 * <p>The agent loop's tool-call dispatch was migrated from a direct
 * {@code OperationDispatcher.dispatch} call (the long-standing C-016
 * concern-bundling defect in the intent layer) to a routed dispatch through
 * {@code BackendIntentRouter} — so the trust lattice, audit log, and unified
 * observability all fire for agent-emitted actions the same way they fire for
 * UI clicks and URL bar paste.
 *
 * <p>The Pass-8 review of slice 487 explicitly called for an audit-test gate
 * preventing future regressions. The pre-Commit-3 version was a regex grep that
 * matched the literal field names {@code operationExecutor} / {@code operationDispatcher},
 * which would fail open for variable renames (slice-execution test-precision
 * defect class). This ArchUnit version walks the compiled bytecode and matches
 * <em>method invocations</em> on the {@link OperationDispatcher} interface
 * regardless of receiver-variable name.
 *
 * <p>The single permitted direct-dispatch call site is
 * {@code AgentToolDispatcher.dispatchToolCall} (the legacy fallback for test
 * wiring that doesn't construct the full intent substrate; production wiring
 * via {@code HeadAssembly.setBackendIntentRouter} routes through the
 * intent layer). {@code dispatchToolCall} was relocated from AgentLoopService
 * into the AgentToolDispatcher collaborator by tempdoc 240 W5; the invariant is
 * unchanged. Any new direct-dispatch call site fails this test.
 *
 * <p>Per the slice-execution discipline ("audit-driven fixes need a runnable
 * test, not just a passing audit"): without this gate, a future slice that
 * adds a second direct-dispatch site silently rots the migration's
 * "all-agent-actions-flow-through-the-intent-layer" guarantee.
 */
final class AgentLoopServiceAuditTest {

  /**
   * Predicate matching the permitted legacy fallback callsite — and only that one.
   * The {@code dispatchToolCall} method is documented as the slice 487 §6.1
   * fallback path; the method-name match is the architectural exemption.
   */
  private static final DescribedPredicate<JavaCall<?>>
      PERMITTED_LEGACY_FALLBACK_DISPATCH_CALL =
          new DescribedPredicate<>(
              "permitted slice 487 legacy fallback call in AgentToolDispatcher.dispatchToolCall") {
            @Override
            public boolean test(JavaCall<?> call) {
              JavaClass owner = call.getOriginOwner();
              String originMethod = call.getOrigin().getName();
              return owner.getName().equals(AgentToolDispatcher.class.getName())
                  && originMethod.equals("dispatchToolCall");
            }
          };

  /**
   * Predicate matching the {@link OperationDispatcher} interface's own default-method
   * delegations (the 3-arg overload that delegates to the 2-arg; the 4-arg overload
   * that delegates to the 3-arg). These are intra-interface compatibility shims, not
   * direct-dispatch violations. The interface lives in {@code app-agent-api} which
   * is imported alongside {@code app-agent} because both packages start with
   * {@code io.justsearch.agent} — but only the production code in
   * {@code modules/app-agent} is the target of the audit-test gate.
   */
  private static final DescribedPredicate<JavaCall<?>>
      INTRA_INTERFACE_DEFAULT_METHOD_DELEGATION =
          new DescribedPredicate<>(
              "OperationDispatcher default-method body delegating to its other overloads") {
            @Override
            public boolean test(JavaCall<?> call) {
              return call.getOriginOwner()
                  .getName()
                  .equals(OperationDispatcher.class.getName());
            }
          };

  @Test
  void noDirectOperationDispatcherCallsExceptLegacyFallback() {
    // Import non-test classes under modules/app-agent. We restrict to the
    // production source tree (no test classes) because tests legitimately
    // call dispatcher directly when wiring stubs. NOTE: the import also pulls
    // in classes from modules/app-agent-api (sibling package), so the predicate
    // exempts the interface's own default-method delegations explicitly.
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.justsearch.agent");

    ArchRule rule =
        noClasses()
            .should()
            .callMethodWhere(
                new DescribedPredicate<JavaCall<?>>(
                    "calls OperationDispatcher.dispatch outside the permitted fallback") {
                  @Override
                  public boolean test(JavaCall<?> call) {
                    if (PERMITTED_LEGACY_FALLBACK_DISPATCH_CALL.test(call)) {
                      return false;
                    }
                    if (INTRA_INTERFACE_DEFAULT_METHOD_DELEGATION.test(call)) {
                      return false;
                    }
                    return isOperationDispatcherDispatchCall(call);
                  }
                })
            .because(
                "slice 487 Phase 1.7 §6.1 audit-test gate — the agent loop's tool-call "
                    + "dispatch must flow through BackendIntentRouter, not directly through "
                    + "OperationDispatcher.dispatch. The single permitted exception is "
                    + "AgentLoopService.dispatchToolCall's legacy fallback for test wiring "
                    + "that doesn't construct the full intent substrate. New dispatch paths "
                    + "must route via BackendIntentRouter so the trust lattice, audit, and "
                    + "unified observability all fire for agent-emitted actions.");

    rule.check(classes);
  }

  private static boolean isOperationDispatcherDispatchCall(JavaCall<?> call) {
    if (!call.getTargetOwner().getName().equals(OperationDispatcher.class.getName())) {
      // Catches direct calls on OperationDispatcher only. Implementation
      // classes (OperationExecutorImpl) are reached via the interface dispatch
      // signature in normal Java call resolution, so this predicate fires on
      // any caller that goes through the interface — which is the canonical
      // shape of the violation we're guarding against.
      return false;
    }
    return call.getName().equals("dispatch");
  }
}
