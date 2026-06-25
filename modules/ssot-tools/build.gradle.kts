plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

// This module holds CLI tooling (SsotValidator, DocsTempdocStatusCheck, etc.) that
// legitimately writes to System.out/err and calls System.exit. Point pmdMain at the
// CLI-tooling ruleset (excludes SystemPrintln + DoNotTerminateVM). See ADR-0026 for
// the manual-CI policy under which these checks run and `config/pmd/ruleset-cli-tools.xml`
// for the rationale.
tasks.named<org.gradle.api.plugins.quality.Pmd>("pmdMain") {
  ruleSetFiles = files(rootProject.layout.projectDirectory.file("config/pmd/ruleset-cli-tools.xml"))
}

dependencies {
  implementation(libs.jackson.databind)
  runtimeOnly(libs.jackson.core)
  implementation(libs.json.schema.validator)
  runtimeOnly(libs.logback.classic)
}

// Tempdoc 410 Slice A.3 — regenerates `SSOT/manifests/repro/repro.v1.json` so the recorded
// SHA-256 of each catalog stays in sync with the canonical-JSON form. Run after editing any
// catalog under `SSOT/catalogs/`.
tasks.register<JavaExec>("regenSsotManifest") {
  description = "Regenerates SSOT/manifests/repro/repro.v1.json from the current catalog state."
  group = "verification"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("io.justsearch.ssot.tools.SsotValidator")
  args(rootProject.layout.projectDirectory.dir("SSOT").asFile.absolutePath)
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}
