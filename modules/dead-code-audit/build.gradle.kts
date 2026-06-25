plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

// Tempdoc 638 — whole-program closed-world dead-code gate.
//
// This module's ONLY purpose is to host an ArchUnit analysis whose classpath is the union of
// every production module, so cross-process callers (Head <-> Worker) are visible — the blind
// spot that makes the process-scoped UnreferencedCodeTest miss public/cross-module dead code.
// It is a dependency SINK (nothing depends on it -> no cycles) and produces no production code.
//
// The deps are deliberately on EVERY module that carries production io.justsearch classes, as
// `testImplementation` (the analysis runs as a test). app-api-tck is omitted (no src/main).
dependencies {
  val auditedModules =
      listOf(
          "adapters-lucene",
          "ai-backend",
          "api-contract-projection-java",
          "app-agent",
          "app-agent-api",
          "app-api",
          "app-config",
          "app-inference",
          "app-launcher",
          "app-observability",
          "app-services",
          "app-util",
          "benchmarks",
          "configuration",
          "core",
          "core-contracts",
          "extension-substrate",
          "gpu-bridge",
          "indexer-worker",
          "indexing",
          "infra-core",
          "ipc-common",
          "ort-common",
          "prompt-support",
          "reranker",
          "ssot-tools",
          "telemetry",
          "ui",
          "worker-core",
          "worker-services",
      )
  for (m in auditedModules) {
    testImplementation(project(":modules:$m"))
  }
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.archunit.junit5)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
  }
}

// Whole-program import holds ~1,300 classes + their members in memory; give the test room.
tasks.named<Test>("test") {
  maxHeapSize = "2g"
  // The ArchUnit test writes its report to the repo-root tmp/ dir; pass the absolute path so the
  // location is independent of the test's working directory.
  systemProperty(
      "deadcode.reportPath",
      rootProject.layout.projectDirectory.file("tmp/dead-code-jvm-report.json").asFile.absolutePath,
  )
}
