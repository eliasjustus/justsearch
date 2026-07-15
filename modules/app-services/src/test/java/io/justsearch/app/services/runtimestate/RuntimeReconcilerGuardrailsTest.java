/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.app.api.OnlineAiLifecycleControl;

/**
 * Tempdoc 737 §12a — single-writer enforcement. Only {@link RuntimeReconciler} may drive the
 * engine's mode via {@link OnlineAiLifecycleControl#switchToOnlineMode()} /
 * {@link OnlineAiLifecycleControl#switchToIndexingMode()}; every other {@code app-services} class
 * that wants a lifecycle transition must go through the reconciler. This makes §3d's
 * never-switch-back <i>inexpressible</i> — a procedure ends and the reconciler returns the system
 * to spec, because spec is data, not a convention.
 *
 * <p>The rule targets the {@code OnlineAiLifecycleControl}-declared methods specifically. Callers
 * that go through the {@code OnlineAiService}-declared default methods (e.g. the user-facing
 * {@code switchInferenceMode} operation, the install self-test) are a separate surface, migrated in
 * Phase 4 when {@code core.switch-inference-mode} is superseded by a spec write.
 *
 * <p><b>Tempdoc 737 Phase 2:</b> {@code OfflineCoordinator} was rerouted through the reconciler
 * (its Phase A/B engine control now goes through {@code procedureRequireEngine}), so it came OFF
 * the PHASE-2-REMOVE allowlist. {@link RuntimeReconciler} is now the sole permitted caller.
 */
@AnalyzeClasses(
    packages = "io.justsearch.app.services",
    importOptions = ImportOption.DoNotIncludeTests.class)
class RuntimeReconcilerGuardrailsTest {

  @ArchTest
  static final ArchRule onlyReconcilerMayDriveLifecycleTransitions =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          // The permanent single writer.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.runtimestate.RuntimeReconciler")
          .should()
          .callMethod(OnlineAiLifecycleControl.class, "switchToOnlineMode")
          .orShould()
          .callMethod(OnlineAiLifecycleControl.class, "switchToIndexingMode");
}
