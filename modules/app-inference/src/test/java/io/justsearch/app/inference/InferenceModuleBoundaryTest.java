package io.justsearch.app.inference;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 518 P4 — module-boundary enforcement (tightened in Appendix G W4.2).
 *
 * <p>The orchestration internals of {@code app-inference} ({@link InferenceLifecycleManager},
 * {@link LlamaServerOps}, {@link OnlineModeOps}, {@link TokenEndpointOps},
 * {@link ServerPropsOps}, {@link TransitionRunner}, {@link InferenceRuntimeView},
 * {@link TransitionOutcome}, {@link TransitionBody}, {@link PropsObserver},
 * {@link ModeStateMachine}) are implementation details. External consumers should depend on
 * the role-typed interfaces in {@code app-api}
 * ({@code OnlineAiService} / {@code OnlineAiRuntimeControl} /
 * {@code OnlineAiRuntimeIntrospection} / {@code OnlineAiLifecycleControl}).
 *
 * <p>This test enforces TWO rules:
 *
 * <ul>
 *   <li><b>Rule 1 (full inference-internals import ban):</b> nothing outside
 *       {@code app-inference} or the three composition-root packages in {@code app-services}
 *       (the root package itself, {@code .bootstrap}, and {@code .worker}) may import the
 *       inference internals at all.
 *   <li><b>Rule 2 (concrete-ILM ban for non-composition-root callers):</b> nothing in
 *       {@code app-services} outside the three composition-root packages may depend on
 *       {@code InferenceLifecycleManager} specifically. Pre-W4.2 this rule was inverted —
 *       the permitted list was the entire {@code app-services..} package because
 *       {@code VduProcessor} (in {@code .vdu}) held a concrete ILM field. Appendix G W4.2
 *       migrated VduProcessor to three role-typed interfaces, and this test tightens to
 *       guard the structural property: only the composition root may construct or hold
 *       concrete ILM references.
 * </ul>
 */
final class InferenceModuleBoundaryTest {

  private static final String INFERENCE_INTERNALS = "io.justsearch.app.inference..";

  /**
   * Packages allowed to import inference internals. Comprises the inference module itself
   * plus the three composition-root packages in app-services that construct or pass through
   * concrete inference types.
   */
  private static final String[] PERMITTED_IMPORTERS = {
    "io.justsearch.app.inference..",
    "io.justsearch.app.services", // AppFacadeBootstrap (top-level composition root)
    "io.justsearch.app.services.bootstrap..", // BootstrapInferenceFactory
    "io.justsearch.app.services.worker..", // KnowledgeServerBootstrap
  };

  @Test
  void inferenceInternalsAreNotImportedOutsidePermittedPackages() {
    var importedClasses =
        new ClassFileImporter()
            .withImportOption(location -> !location.contains("/test/"))
            .importPackages("io.justsearch..");

    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackages(PERMITTED_IMPORTERS)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INFERENCE_INTERNALS);

    rule.check(importedClasses);
  }

  @Test
  void concreteInferenceLifecycleManagerIsOnlyHeldAtTheCompositionRoot() {
    var importedClasses =
        new ClassFileImporter()
            .withImportOption(location -> !location.contains("/test/"))
            .importPackages("io.justsearch..");

    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackages(PERMITTED_IMPORTERS)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(
                "io.justsearch.app.inference.InferenceLifecycleManager");

    rule.check(importedClasses);
  }
}
