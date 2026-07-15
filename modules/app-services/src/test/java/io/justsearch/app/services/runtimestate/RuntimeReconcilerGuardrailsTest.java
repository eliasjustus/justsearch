/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiService;

/**
 * Tempdoc 737 §12a — single-writer enforcement. Only {@link RuntimeReconciler} may drive the
 * engine's mode; every other {@code app-services} class that wants a lifecycle transition must go
 * through the reconciler (spec write + convergence, or a procedure). This makes §3d's
 * never-switch-back <i>inexpressible</i> — a procedure ends and the reconciler returns the system
 * to spec, because spec is data, not a convention.
 *
 * <p>Two rules cover both declarations of the switch primitives:
 *
 * <ul>
 *   <li>the {@link OnlineAiLifecycleControl}-declared {@code switchToOnlineMode()} /
 *       {@code switchToIndexingMode()} (the bootstrap / VDU / reconciler surface), and
 *   <li>the {@link OnlineAiService}-declared default overloads of the same names (the user-facing
 *       surface the install self-test and the {@code switchInferenceMode} operation used to call
 *       directly — now routed through the reconciler / the {@code core.set-chat-enabled} spec write,
 *       tempdoc 737 fix pack fix 3/4).
 * </ul>
 *
 * <p><b>Tempdoc 737 Phase 2:</b> {@code OfflineCoordinator} was rerouted through the reconciler (its
 * Phase A/B engine control now goes through {@code procedureRequireEngine}). <b>Fix pack:</b>
 * {@code BrainRuntimeServiceImpl.switchInferenceMode} became a spec write; the only remaining
 * {@code OnlineAiService}-declared caller is {@code AiInstallService}'s smoke-test legacy fallback
 * (used only when no reconciler is wired), allowlisted below with a {@code LEGACY-FALLBACK} marker.
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

  /**
   * Tempdoc 737 fix pack (fix 5): the {@code OnlineAiService}-declared default overloads must not be
   * called directly either. Allowlist: {@link RuntimeReconciler} (the single writer) and
   * {@code AiInstallService} (its smoke-test // LEGACY-FALLBACK raw call, live only when no
   * reconciler is wired — a test / non-configured construction).
   */
  @ArchTest
  static final ArchRule onlyReconcilerMayCallServiceSwitchPrimitives =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.runtimestate.RuntimeReconciler")
          // LEGACY-FALLBACK: AiInstallService.smokeTestBestEffort keeps a raw switchToOnlineMode()
          // ONLY for the no-reconciler path (test / non-configured); production wires the reconciler
          // and takes the procedureRequireEngine path.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.install.AiInstallService")
          .should()
          .callMethod(OnlineAiService.class, "switchToOnlineMode")
          .orShould()
          .callMethod(OnlineAiService.class, "switchToIndexingMode");
}
