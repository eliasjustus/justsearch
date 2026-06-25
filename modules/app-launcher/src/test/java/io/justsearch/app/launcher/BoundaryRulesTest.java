package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch")
class BoundaryRulesTest {
  @ArchTest
  static final ArchRule launcherMayOnlyDependOnAppApi =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.launcher..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              // The launcher CLI remains isolated from UI surfaces and application services.
              "io.justsearch.ui..",
              "io.justsearch.app.services.."
          );
}
