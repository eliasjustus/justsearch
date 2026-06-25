package conventions

import java.math.BigDecimal
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.plugins.JavaPluginExtension

class CoverageConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Apply JaCoCo; harmless on projects without Test tasks
    project.pluginManager.apply("jacoco")

    // Skip JaCoCo reports in local builds by default. Override with:
    //   -PskipJacoco=false   (force enable locally)
    //   CI=true env var       (auto-enabled in CI)
    // The finalizedBy wiring in JvmBaseConventionsPlugin still fires, but the
    // task body is skipped via onlyIf — saving ~48s across 25 modules.
    val skipJacoco = project.providers.gradleProperty("skipJacoco")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(
            project.providers.environmentVariable("CI")
                .map { it.isBlank() || it.equals("false", ignoreCase = true) }
                .orElse(true)
        )

    project.tasks.withType(JacocoReport::class.java).configureEach {
      onlyIf { !skipJacoco.get() }
    }

    // Coverage verification is opt-in while the test suite is being built out.
    // Enable with `-Pcoverage.enforce=true`.
    // Coverage enforcement: opt-in via -Pcoverage.enforce=true (global)
    // or extra["coverage.enforce"] = "true" in a module's build.gradle.kts (per-module).
    val enforceCoverage =
        project.providers.gradleProperty("coverage.enforce")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(
                project.provider {
                  project.extensions.extraProperties.has("coverage.enforce") &&
                      project.extensions.extraProperties.get("coverage.enforce")
                          .toString().equals("true", ignoreCase = true)
                }
            )
            .get()

    // Configure tool version (pin; can be moved to catalog later)
    project.extensions.configure(JacocoPluginExtension::class.java) {
      toolVersion = "0.8.14"
    }

    // Thresholds from gradle.properties with safe defaults
    val lineMin: Provider<BigDecimal> =
        project.providers.gradleProperty("coverage.line.min").map { BigDecimal(it) }
            .orElse(BigDecimal("0.80"))
    val branchMin: Provider<BigDecimal> =
        project.providers.gradleProperty("coverage.branch.min").map { BigDecimal(it) }
            .orElse(BigDecimal("0.70"))
    val perClassMin: Provider<BigDecimal> =
        project.providers.gradleProperty("coverage.class.line.min").map { BigDecimal(it) }
            .orElse(BigDecimal("0.60"))

    project.tasks.withType(JacocoCoverageVerification::class.java).configureEach {
      enabled = enforceCoverage
      violationRules {
        rule {
          limit {
            counter = "LINE"
            value = "COVEREDRATIO"
            minimum = lineMin.get()
          }
          limit {
            counter = "BRANCH"
            value = "COVEREDRATIO"
            minimum = branchMin.get()
          }
        }
        rule {
          element = "CLASS"
          limit {
            counter = "LINE"
            value = "COVEREDRATIO"
            minimum = perClassMin.get()
          }
          // Common exclusions; projects can refine further
          excludes = listOf(
            "**/generated/**",
            "**/*$*Companion.class",
            // Inner class $2 contains defensive liveDocs == null check (line 54)
            // that's difficult to test in practice. During Lucene merges, getLiveDocs()
            // rarely returns null since committed segments always have a Bits object.
            // The check is defensive programming per CodecReader API contract but
            // represents an edge case that's impractical to exercise without complex
            // mocking or internal Lucene manipulation. See:
            // modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/TelemetrySoftDeletesMergePolicy.java
            // JaCoCo uses dot notation for inner classes in class name matching
            "io.justsearch.adapters.lucene.runtime.TelemetrySoftDeletesMergePolicy.2",
            // Launcher CLI glue is exercised via end-to-end smoke commands; the factory wiring
            // (CommandRunner, option records) is verified by CLI integration tests rather than
            // unit coverage.
            "io.justsearch.applauncher.Launcher.CommandRunner",
            "io.justsearch.applauncher.Launcher.CommandOptions",
            "io.justsearch.applauncher.Launcher.SeedOptions",
            "io.justsearch.applauncher.Launcher.UiOptions"
          )
        }
      }
    }

    // Create a default coverage verification task per JVM project
    project.pluginManager.withPlugin("java") {
      val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
      val verify = project.tasks.named("jacocoTestCoverageVerification", JacocoCoverageVerification::class.java) {
        executionData(project.fileTree(project.layout.buildDirectory).include("**/jacoco/*.exec"))
        classDirectories.setFrom(javaExt.sourceSets.getByName("main").output)
        sourceDirectories.setFrom(javaExt.sourceSets.getByName("main").allJava.srcDirs)
      }
      // Ensure tests and reports run before verification
      verify.configure {
        dependsOn(project.tasks.withType(Test::class.java))
        dependsOn(project.tasks.matching { it.name == "jacocoTestReport" })
        // Ensure Java Spotless runs before coverage verification to avoid implicit dependency issues
        dependsOn(project.tasks.matching { it.name.startsWith("spotlessJava") && it.name.endsWith("Check") })
      }

      // Configure per-project test report for visibility
      project.tasks.withType(JacocoReport::class.java).configureEach {
        reports { xml.required.set(true); html.required.set(true); csv.required.set(false) }
      }

      // Ensure verification participates in lifecycle
      if (enforceCoverage) {
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
      }
    }

    // Optional per-project aggregate report across all Test tasks (unit + others)
    // Only when Java plugin is present so we can access sourceSets
    project.pluginManager.withPlugin("java") {
      val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
      project.tasks.register("jacocoAllTestsReport", JacocoReport::class.java) {
        // Ensure tests run before generating the aggregate report
        dependsOn(project.tasks.withType(Test::class.java))
        executionData(project.fileTree(project.layout.buildDirectory).include("**/jacoco/*.exec"))
        sourceSets(javaExt.sourceSets.getByName("main"))
        reports { xml.required.set(true); html.required.set(true); csv.required.set(false) }
      }
    }
  }
}
