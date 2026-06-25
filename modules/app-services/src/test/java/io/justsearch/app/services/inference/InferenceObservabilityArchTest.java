package io.justsearch.app.services.inference;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Tempdoc 412 follow-up — observability contract enforcement for the inference subsystem.
 *
 * <p>The events-interface idiom (defined in {@code app-inference}, implemented in
 * {@code app-services/inference}) keeps domain code free of telemetry imports. These rules
 * make that convention structural rather than honor-system: a future commit that imports
 * {@link InferenceMetricCatalog} or {@code MetricRegistry} from outside the adapter package
 * fails CI with a precise pointer.
 */
@AnalyzeClasses(
    packages = {"io.justsearch.app.inference", "io.justsearch.app.services"},
    importOptions = ImportOption.DoNotIncludeTests.class)
class InferenceObservabilityArchTest {

  /**
   * The {@link InferenceMetricCatalog} typed instrument fields are touched ONLY by code in the
   * {@code app-services.inference} package (the adapter + bootstrap construction). Everything
   * else must go through {@code InferenceTelemetryEvents} — the domain-layer events interface
   * — which keeps the wire format and tag schemas owned by one place.
   *
   * <p>Bootstrap construction is allowed to reference the catalog (it instantiates it against
   * a {@code MetricRegistry}), so the rule excludes the bootstrap factory.
   */
  @ArchTest
  static final ArchRule onlyAdapterAndBootstrapMayReferenceCatalog =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.justsearch.app.services.inference..")
          .and()
          .doNotHaveFullyQualifiedName(
              "io.justsearch.app.services.bootstrap.BootstrapInferenceFactory")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "io.justsearch.app.services.inference.InferenceMetricCatalog");

  /**
   * Domain code in {@code app-inference} must not import telemetry catalog types directly. This
   * is the structural rationale for the events-interface idiom: emit through the interface (in
   * {@code app-inference.telemetry}), not through {@code MetricRegistry} or {@code MetricCatalog}.
   */
  @ArchTest
  static final ArchRule appInferenceMustNotImportTelemetryCatalog =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.inference..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.telemetry.catalog..");
}
