package conventions

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.api.artifacts.VersionCatalogsExtension

class JvmBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Base JVM plugins and repo-wide conventions
    project.plugins.apply("java-library")
    project.plugins.apply("pmd")
    project.plugins.apply("com.diffplug.spotless")

    // Conventions from included build
    project.plugins.apply("conventions.archiving-reproducible")
    project.plugins.apply("conventions.coverage")
    project.plugins.apply("conventions.errorprone")
    project.plugins.apply("conventions.spotbugs")

    // Serialize test tasks across parallel project builds to avoid memory pressure.
    // Default: 3 concurrent test JVMs locally (32GB RAM, 12-core 12700K), 1 in CI (conservative).
    // Bumped 2 -> 3 on 2026-04-19 after E-J-N4 measurement (tempdoc 390): test wall
    // 96s -> 75.9s = -20.1%, non-overlapping IQRs. Thermally safe (Gradle build measured
    // 86 C max vs 100 C TjMax, CPU Package Power p95 148W of 190W PL2).
    // The CI value of 1 is a conservative default, NOT a measured memory constraint: the
    // hosted runner is 16 GiB / 4 vCPU (tempdoc 667), so memory is not binding and the real
    // ceiling on useful test parallelism is the 4 vCPUs. Whether CI can move to 2 is an
    // optimization-band question requiring hosted validation, deferred (tempdoc 667), unlike
    // the local value which is measurement-backed (tempdoc 390 above).
    // Override: -PtestParallelism=N
    val testParallelism = project.providers.gradleProperty("testParallelism")
        .map { it.toInt() }
        .orElse(
            project.providers.environmentVariable("CI")
                .map { if (it.isNotBlank() && !it.equals("false", ignoreCase = true)) 1 else 3 }
                .orElse(3)
        )

    // registerIfAbsent is idempotent — safe to call from each project's plugin application.
    val testGate = project.gradle.sharedServices.registerIfAbsent(
        "testGate", TestGateService::class.java
    ) { maxParallelUsages.set(testParallelism) }

    // Jackson 3.x requires jackson-annotations 2.21+. Force across all modules to prevent
    // transitive deps (e.g., Javalin) pulling in older 2.18.x annotations.
    project.configurations.configureEach {
      resolutionStrategy.eachDependency {
        if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
          useVersion("2.21")
          because("Jackson 3.1.0 requires jackson-annotations 2.21+")
        }
      }
    }

    // Toolchain: Java 25 LTS and JUnit Platform across all tests
    project.extensions.findByType(JavaPluginExtension::class.java)?.let { javaExt ->
      javaExt.toolchain.languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(25))

      val includeExperiment = project.findProperty("includeExperiment")?.toString()?.toBoolean() ?: false
      val includeStress = project.findProperty("includeStress")?.toString()?.toBoolean() ?: false
      project.tasks.withType(Test::class.java).configureEach {
        usesService(testGate)
        useJUnitPlatform {
          val excluded = mutableListOf<String>()
          if (!includeExperiment) {
            excluded.add("evidence")
            excluded.add("experiment")
          }
          // Tempdoc 397 §14.21 R3: stress tests (e.g.,
          // OrtSessionManagerConcurrentStressTest) are designed for nightly / merge-gate
          // runs, not fast inner loops. Opt in with -PincludeStress=true to run them.
          if (!includeStress) {
            excluded.add("stress")
          }
          if (excluded.isNotEmpty()) {
            excludeTags(*excluded.toTypedArray())
          }
        }
        maxHeapSize = "384m"
        jvmArgs("--sun-misc-unsafe-memory-access=warn", "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseCompactObjectHeaders")
        systemProperty("junit.jupiter.execution.timeout.default", "30s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        // Retry flaky tests in CI; surface them with failOnPassedAfterRetry.
        // The retry extension is registered by the Develocity plugin (settings.gradle.kts).
        // Accessed via reflection because the type is shaded inside the Develocity plugin.
        // Develocity 4.x: develocity.testRetry on the Test task; 3.x: retry (deprecated).
        val retryExt = run {
          extensions.findByName("develocity")?.let { devExt ->
            try { devExt.javaClass.getMethod("getTestRetry").invoke(devExt) } catch (_: Exception) { null }
          } ?: extensions.findByName("retry")
        }
        retryExt?.let { ext ->
          try {
            val isCi = project.providers.environmentVariable("CI")
                .map { it.isNotBlank() }.orElse(false).get()
            val maxRetriesProp = ext.javaClass.getMethod("getMaxRetries").invoke(ext)
            val failOnPassedProp = ext.javaClass.getMethod("getFailOnPassedAfterRetry").invoke(ext)
            @Suppress("UNCHECKED_CAST")
            (maxRetriesProp as org.gradle.api.provider.Property<Int>).set(if (isCi) 2 else 0)
            @Suppress("UNCHECKED_CAST")
            (failOnPassedProp as org.gradle.api.provider.Property<Boolean>).set(true)
          } catch (e: ReflectiveOperationException) {
            project.logger.warn("Could not configure test retry via reflection: ${e.message}")
          } catch (e: ClassCastException) {
            project.logger.warn("Test retry extension has unexpected type: ${e.message}")
          }
        }
        // Generate JaCoCo report after tests
        finalizedBy(project.tasks.matching { it.name == "jacocoTestReport" })
      }

      project.tasks.withType(JavaExec::class.java).configureEach {
        jvmArgs("--sun-misc-unsafe-memory-access=warn", "--enable-native-access=ALL-UNNAMED")
      }
    }

    // Version catalog access (optional for formatter version)
    val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java)
    val libsCatalog = catalogs?.named("libs")
    val gjfVersionStr: String? =
        libsCatalog?.findVersion("googleJavaFormat")?.map { it.requiredVersion }?.orElse(null)

    // Spotless formatting for Java sources in each module
    project.extensions.configure(SpotlessExtension::class.java) {
      val currentMajor = org.gradle.api.JavaVersion.current().majorVersion.toIntOrNull() ?: 0
      val enableGjf =
          project.providers.gradleProperty("enableGjf")
              .map { it.equals("true", ignoreCase = true) }
              .orElse(false)
              .get()
      if (enableGjf || currentMajor < 23) {
        java {
          // Use catalog-pinned Google Java Format when available
          if (gjfVersionStr != null) {
            googleJavaFormat(gjfVersionStr)
          } else {
            // Fallback to a safe default version if catalog missing
            googleJavaFormat("1.25.2")
          }
          target("src/**/*.java")
        }
      } else {
        // Minimal hygiene fallback for newer JDK until GJF compatibility lands
        format("javaSources") {
          target("src/**/*.java")
          trimTrailingWhitespace()
          endWithNewline()
        }
      }
    }

    // PMD configuration (light ruleset)
    project.extensions.configure(PmdExtension::class.java) {
      toolVersion = "7.16.0"
      isConsoleOutput = true
      isIgnoreFailures = false
      ruleSets = emptyList()
      ruleSetFiles = project.objects.fileCollection()
          .from(project.rootProject.layout.projectDirectory.file("config/pmd/ruleset.xml"))
    }

    // Skip PMD in local builds by default. Override with:
    //   -PskipPmd=false    (force enable locally)
    //   CI=true env var    (auto-enabled in CI)
    val skipPmd = project.providers.gradleProperty("skipPmd")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(
            project.providers.environmentVariable("CI")
                .map { it.isBlank() || it.equals("false", ignoreCase = true) }
                .orElse(true)
        )

    val includePmdTests =
        project.providers.gradleProperty("pmd.includeTests")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(false)
            .get()

    // Gradle's PMD plugin wires all source sets (main, test, integrationTest, etc) into `check` by default.
    // Keep test-source PMD opt-in to avoid blocking `./gradlew check` on test-only hygiene.
    // Skip all PMD locally unless explicitly opted in.
    project.tasks.withType(Pmd::class.java).configureEach {
      onlyIf {
        !skipPmd.get() && (name == "pmdMain" || includePmdTests)
      }
    }
    project.tasks.named("check") {
      dependsOn(project.tasks.matching { it.name == "spotlessCheck" })
      if (includePmdTests) {
        dependsOn(project.tasks.withType(Pmd::class.java))
      } else {
        dependsOn(project.tasks.matching { it.name == "pmdMain" })
      }
    }

    // Skip distribution archives in local builds. Override with:
    //   -PskipDist=false   (force enable locally)
    //   CI=true env var     (auto-enabled in CI)
    // distTar is unconditionally disabled (Windows project, never used).
    // distZip is skipped locally (~68s across 4 modules) but enabled in CI.
    // Explicit tasks like assembleDesktopDist still work via -PskipDist=false.
    val skipDist = project.providers.gradleProperty("skipDist")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(
            project.providers.environmentVariable("CI")
                .map { it.isBlank() || it.equals("false", ignoreCase = true) }
                .orElse(true)
        )

    project.pluginManager.withPlugin("application") {
      project.tasks.named("distTar") {
        enabled = false
      }
      project.tasks.named("distZip") {
        onlyIf { !skipDist.get() }
      }
    }

    // Spotless 8.1.0+ has full configuration cache support (issue #987 resolved)
    project.tasks.withType(SpotlessTask::class.java).configureEach {
      // Spotless tasks can write into source trees; never restore outputs from the Gradle build cache.
      outputs.cacheIf { false }
    }
  }
}
