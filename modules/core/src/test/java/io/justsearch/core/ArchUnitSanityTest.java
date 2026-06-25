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

  @ArchTest
  static final ArchRule coreMustNotReadEnvOrSystemProperties =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.core..")
          .should()
          .callMethod(System.class, "getenv", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class, String.class)
          .orShould()
          .callMethod(System.class, "setProperty", String.class, String.class);
}
