package io.justsearch.ui.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass.Predicates;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.ui.api", importOptions = ImportOption.DoNotIncludeTests.class)
class UiApiGuardrailsTest {
  @ArchTest
  static final ArchRule uiApiMustNotReadEnvOrSystemProperties =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.ui.api..")
          .should()
          .callMethod(System.class, "getenv")
          .orShould()
          .callMethod(System.class, "getenv", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class, String.class)
          .orShould()
          .callMethod(System.class, "setProperty", String.class, String.class)
          .orShould()
          .callMethod(System.class, "clearProperty", String.class);

  // Exception classes in ipc.* (e.g. CircuitBreakerOpenException) are legitimate to catch in
  // error handlers. Tempdoc 400 §22 Issue A (LR6-a refactor) moved the
  // @BuildContract / @AdvisoryContract annotations out of
  // io.justsearch.ipc.contracts into a new dep-free core-contracts module at
  // io.justsearch.contracts; they are no longer in the ipc.. tree, so the
  // previous exemption is no longer required.
  @ArchTest
  static final ArchRule uiApiMustNotSpreadProtoDtosBeyondKnownControllers =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.ui.api..")
          .should()
          .dependOnClassesThat(
              Predicates.resideInAnyPackage("io.justsearch.ipc..")
                  .and(DescribedPredicate.not(Predicates.simpleNameEndingWith("Exception"))))
          .as(
              "ui.api must not depend on ipc proto message types"
                  + " (use app-api contracts instead; ipc exception classes are permitted)");
}
