import java.time.Duration

plugins {
  id("conventions.jvm-base")
}

description = "JustSearch System Tests - Chaos, Relevance, and Integration Testing"

fun flagEnabled(gradleProp: String, envVar: String): Boolean {
  val propValue = findProperty(gradleProp)?.toString()?.trim()?.toBooleanStrictOrNull()
  if (propValue != null) return propValue
  val envValue = System.getenv(envVar)?.trim()?.toBooleanStrictOrNull()
  if (envValue != null) return envValue
  return false
}

fun intOverride(gradleProp: String, envVar: String, defaultValue: Int): Int {
  val propValue = findProperty(gradleProp)?.toString()?.trim()
  val parsedProp = propValue?.toIntOrNull()
  if (parsedProp != null && parsedProp > 0) return parsedProp

  val envValue = System.getenv(envVar)?.trim()
  val parsedEnv = envValue?.toIntOrNull()
  if (parsedEnv != null && parsedEnv > 0) return parsedEnv

  return defaultValue
}

val includeSystemTests = flagEnabled("includeSystemTests", "JUSTSEARCH_INCLUDE_SYSTEM_TESTS")
val includeSoakTests = flagEnabled("includeSoakTests", "JUSTSEARCH_INCLUDE_SOAK_TESTS")
val includeAiTests = flagEnabled("includeAiTests", "JUSTSEARCH_INCLUDE_AI_TESTS")
val includeAgentTests = flagEnabled("includeAgentTests", "JUSTSEARCH_INCLUDE_AGENT_TESTS")
val ragEvalTimeoutMinutes = intOverride(
  "ragEvalTimeoutMinutes",
  "JUSTSEARCH_RAG_EVAL_TIMEOUT_MINUTES",
  30
)

dependencies {
  // Internal dependencies
  testImplementation(project(":modules:app-services"))
  testImplementation(project(":modules:worker-services"))
  testImplementation(project(":modules:indexer-worker"))
  api(project(":modules:ipc-common"))
  runtimeOnly(project(":modules:adapters-lucene"))
  api(project(":modules:ai-backend"))

  // gRPC for IPC
  implementation(libs.grpc.stub)
  runtimeOnly(libs.grpc.netty.shaded)

  // Jackson for JSON manifests
  api(libs.jackson.databind)
  implementation(libs.jackson.core)
  runtimeOnly(libs.jackson.dataformat.yaml)

  // Logging
  implementation(libs.slf4j.api)
  runtimeOnly(libs.logback.classic)

  // Testing
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for system test tiers")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for system test tiers")
    }
  }
}

// Handle duplicate resources
tasks.withType<Copy>().configureEach {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Separate source sets for different test tiers
sourceSets {
  // Standard unit tests
  test {
    java.srcDir("src/test/java")
    resources.srcDir("src/test/resources")
  }

  // Integration tests (Golden Corpus)
  create("integrationTest") {
    java.srcDir("src/integrationTest/java")
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
  }

  // System/Chaos tests
  create("systemTest") {
    java.srcDir("src/systemTest/java")
    resources.srcDir("src/systemTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
  }

  // Soak tests (long-running, memory leak detection)
  create("soakTest") {
    java.srcDir("src/soakTest/java")
    resources.srcDir("src/soakTest/resources")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
  }
}

// Configuration for test tiers
val integrationTestImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
  extendsFrom(configurations.implementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
  extendsFrom(configurations.testRuntimeOnly.get())
  extendsFrom(configurations.runtimeOnly.get())
}

val systemTestImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
}
val systemTestRuntimeOnly by configurations.getting {
  extendsFrom(configurations.testRuntimeOnly.get())
}

val soakTestImplementation by configurations.getting {
  extendsFrom(configurations.testImplementation.get())
  extendsFrom(configurations.implementation.get())
}
val soakTestRuntimeOnly by configurations.getting {
  extendsFrom(configurations.testRuntimeOnly.get())
  extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
  add("integrationTestImplementation", project(":modules:adapters-lucene"))
  add("integrationTestImplementation", libs.lucene.core)
  add("integrationTestImplementation", project(":modules:configuration"))
  add("integrationTestImplementation", project(":modules:indexing"))
  add("integrationTestImplementation", project(":modules:reranker"))
  add("integrationTestImplementation", testFixtures(project(":modules:ort-common"))) // §14.28 U1 helper
  add("integrationTestImplementation", project(":modules:app-agent"))
  add("integrationTestImplementation", project(":modules:app-agent-api"))
  add("integrationTestImplementation", project(":modules:app-api"))
  add("integrationTestImplementation", "org.junit.jupiter:junit-jupiter-params:5.14.3")
  add("systemTestImplementation", project(":modules:indexing"))
  add("systemTestImplementation", project(":modules:gpu-bridge"))
  add("systemTestImplementation", "org.junit.jupiter:junit-jupiter-params:5.14.3")
}

// Integration test task (Golden Corpus, Relevance)
val integrationTest = tasks.register<Test>("integrationTest") {
  description = "Runs integration tests (Golden Corpus, Relevance)."
  group = "verification"

  // Tempdoc 419 / T6.2 — IsolatedBackendFixture spawns HeadlessApp, which spawns the Worker
  // subprocess from modules/indexer-worker/build/install/indexer-worker. Without this,
  // fresh-checkout runs see Head boot fine while Worker spawn silently fails (the fixture
  // would then time out in awaitDocumentSearchable instead of failing fast). Mirrors the
  // same dependency on :modules:ui:runHeadless (modules/ui/build.gradle.kts:1844).
  dependsOn(":modules:indexer-worker:installDist")

  testClassesDirs = sourceSets["integrationTest"].output.classesDirs
  classpath = sourceSets["integrationTest"].runtimeClasspath

  useJUnitPlatform {
    if (!includeAiTests && !includeAgentTests) {
      excludeTags("ai")
    }
  }

  // Time limit: 10 minutes default, 30 for agent tests, configurable for AI runs.
  val integrationTestTimeoutMinutes = when {
    includeAiTests -> ragEvalTimeoutMinutes
    includeAgentTests -> 30
    else -> 10
  }
  timeout.set(Duration.ofMinutes(integrationTestTimeoutMinutes.toLong()))

  testLogging {
    events("passed", "skipped", "failed")
    showStandardStreams = true
  }

  // Forward API port system property for HTTP tests
  System.getProperty("justsearch.api.port")?.let { systemProperty("justsearch.api.port", it) }

  // Forward RAG eval context format for agent-style context experiments (tempdoc 213)
  System.getProperty("rag.eval.context.format")?.let { systemProperty("rag.eval.context.format", it) }

  // Tempdoc 419 / T6.2 — IsolatedBackendFixture spawns HeadlessApp in a child JVM whose
  // working directory is this module, not the repo root. Pass the absolute worker lib
  // path through so KnowledgeServerConfig.resolveWorkerLibDir doesn't need to walk
  // relative paths to find the installDist output. Mirrors the pattern systemTest uses
  // for justsearch.worker.dist.dir.
  systemProperty(
      "justsearch.worker.lib.dir",
      project(":modules:indexer-worker").layout.buildDirectory
          .dir("install/indexer-worker/lib").get().asFile.absolutePath)
}

// System test task (Chaos Suite)
val systemTest = tasks.register<Test>("systemTest") {
  description = "Runs system tests (Chaos Suite, Process Coordination)."
  group = "verification"

  // System tests are intentionally opt-in to keep `./gradlew check` runnable in CI/dev by default.
  // Enable with `-PincludeSystemTests=true` or `JUSTSEARCH_INCLUDE_SYSTEM_TESTS=true`.
  enabled = includeSystemTests

  // Ensure all worker artifacts are built before running tests
  // prepareTests builds both shadowJar (for JAR-mode tests) and installDist (for distribution-mode tests)
  dependsOn(rootProject.tasks.named("prepareTests"))

  testClassesDirs = sourceSets["systemTest"].output.classesDirs
  classpath = sourceSets["systemTest"].runtimeClasspath

  useJUnitPlatform {
    // AI system tests require a running external llama-server; exclude by default.
    if (!includeAiTests) {
      excludeTags("ai")
    }
  }

  // Time limit: < 1 hour (Nightly)
  timeout.set(Duration.ofHours(1))

  // System tests may need more heap for process spawning
  maxHeapSize = "1g"

  testLogging {
    events("passed", "skipped", "failed")
    showStandardStreams = true
  }

  // Pass system property for worker distribution directory location
  systemProperty("justsearch.worker.dist.dir", project(":modules:indexer-worker").layout.buildDirectory
      .dir("install/indexer-worker").get().asFile.absolutePath)
}

// Soak test task (Memory Leak Detection, Nightly)
val soakTest = tasks.register<Test>("soakTest") {
  description = "Runs soak tests (memory leak detection, long-running)."
  group = "verification"

  // Soak tests are intentionally opt-in; they can take hours.
  // Enable with `-PincludeSoakTests=true` or `JUSTSEARCH_INCLUDE_SOAK_TESTS=true`.
  enabled = includeSoakTests

  testClassesDirs = sourceSets["soakTest"].output.classesDirs
  classpath = sourceSets["soakTest"].runtimeClasspath

  useJUnitPlatform()

  // Time limit: 4 hours (Nightly)
  timeout.set(Duration.ofHours(4))

  // Soak tests need more heap
  maxHeapSize = "2g"

  // Enable NMT for the test JVM (for self-tracking if needed)
  jvmArgs("-XX:NativeMemoryTracking=summary")

  testLogging {
    events("passed", "skipped", "failed")
    showStandardStreams = true
  }

  // Pass system property for worker distribution directory location
  systemProperty("justsearch.worker.dist.dir", project(":modules:indexer-worker").layout.buildDirectory
      .dir("install/indexer-worker").get().asFile.absolutePath)
}

// Make check depend on unit tests
tasks.named("check") {
  dependsOn(tasks.named("test"))
}

// Disable strict coverage and PMD for test utilities module
// These are test harnesses, not production code
tasks.withType<JacocoCoverageVerification>().configureEach {
  isEnabled = false
}

plugins.withId("pmd") {
  configure<PmdExtension> {
    isIgnoreFailures = true
  }
}

// Generate frozen embeddings for passage-retrieval corpus via llama-server
tasks.register<JavaExec>("generatePassageVectors") {
  description = "Generates frozen embeddings for passage-retrieval corpus via llama-server."
  group = "corpus"
  mainClass.set("io.justsearch.systemtests.corpus.PassageRetrievalVectorGenerator")
  classpath = sourceSets["integrationTest"].runtimeClasspath
  workingDir = rootProject.projectDir

  val serverUrl = findProperty("llamaServerUrl")?.toString()
      ?: "http://127.0.0.1:8081/v1/embeddings"
  systemProperty("llama.server.url", serverUrl)

  // Pass --deterministic if requested
  if (findProperty("deterministic")?.toString()?.toBoolean() == true) {
    args("--deterministic")
  }
}

// Custom task for full test suite (all tiers except soak)
tasks.register("fullTestSuite") {
  description = "Runs all test tiers: unit, integration, and system tests."
  group = "verification"
  dependsOn(tasks.named("test"), integrationTest, systemTest)
}

// Custom task for nightly test suite (includes soak tests)
tasks.register("nightlyTestSuite") {
  description = "Runs all test tiers including soak tests (nightly)."
  group = "verification"
  dependsOn(tasks.named("test"), integrationTest, systemTest, soakTest)
}
