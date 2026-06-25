package io.justsearch.aibackend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.aibackend")
class ArchUnitEgressTest {
  private static final String[] HTTP_PACKAGES = {
    "java.net..",
    "javax.net..",
    "okhttp3..",
    "com.squareup.okhttp..",
    "org.apache.http..",
    "retrofit2.."
  };

  @ArchTest
  static final ArchRule translatorLayersMustStayOffline =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.aibackend.local..", "io.justsearch.aibackend.backend..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(HTTP_PACKAGES);
}
