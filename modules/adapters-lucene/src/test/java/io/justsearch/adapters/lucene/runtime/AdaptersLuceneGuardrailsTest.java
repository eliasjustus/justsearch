package io.justsearch.adapters.lucene.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.adapters.lucene", importOptions = ImportOption.DoNotIncludeTests.class)
class AdaptersLuceneGuardrailsTest {
  @ArchTest
  static final ArchRule adaptersLuceneMustNotReadEnvOrSystemProperties =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.adapters.lucene..")
          .should()
          .callMethod(System.class, "getenv")
          .orShould()
          .callMethod(System.class, "getenv", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class, String.class)
          .orShould()
          .callMethod(System.class, "setProperty", String.class, String.class);
}
