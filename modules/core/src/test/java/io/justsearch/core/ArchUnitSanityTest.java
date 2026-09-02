package io.justsearch.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchUnitSanityTest {
  @ArchTest
  static final ArchRule noAwtInCore =
      noClasses().that().resideInAnyPackage("io.justsearch..").should().dependOnClassesThat()
          .resideInAnyPackage("java.awt..", "javax.swing..");

  @ArchTest
  static final ArchRule coreShouldNotDependOnAdaptersOrUi =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.adapters..",
              "io.justsearch.ui..",
              "io.justsearch.aibackend..",
              "org.apache.lucene..");

  @ArchTest
  static final ArchRule dtoMustNotDependOnLuceneOrAIBridge =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.core.dto..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.lucene..", "io.justsearch.aibackend..");

  // `coreMustNotReadEnvOrSystemProperties` was retired in tempdoc 883 decision 5 — see the note in
  // io.justsearch.deadcode.SystemAccessFunnelTest (modules/dead-code-audit), the single repo-wide
  // replacement for the six per-module copies of this rule.
}
