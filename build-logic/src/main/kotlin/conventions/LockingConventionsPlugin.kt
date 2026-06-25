package conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel

class LockingConventionsPlugin : Plugin<Project> {
  // Tool-only configurations whose versions don't affect compiled output.
  // These float to whatever the plugin ships — no need to lock them.
  // Uses exact names for tool configs. *PmdAuxClasspath configs are excluded
  // separately (see configureEach below) because they are lazily created by the
  // PMD plugin during configuration-cache serialization and cannot be reached by
  // resolveAndLockAll. Their versions mirror the already-locked compile/runtime
  // classpaths, so excluding them from locking is safe.
  private val toolConfigs = setOf(
      "spotbugs", "spotbugsSlf4j", "spotbugsPlugins",
      "pmd",
      "jacocoAnt", "jacocoAgent",
      "revapiAnt",
      "errorproneJavac",
      "protobufToolsLocator_grpc", "protobufToolsLocator_protoc",
  )

  override fun apply(project: Project) {
    // Activate dependency locking on production/test configurations only.
    // Tool configurations are excluded to prevent tool-vs-app version skew in lockfiles.
    project.gradle.allprojects {
      configurations.configureEach {
        if (name in toolConfigs || name.endsWith("PmdAuxClasspath")) {
          resolutionStrategy.deactivateDependencyLocking()
        } else {
          resolutionStrategy.activateDependencyLocking()
        }
      }
    }

    if (project != project.rootProject) return

    // Root-only helper: resolve all resolvable configurations to (re)generate lockfiles
    project.tasks.register("resolveAndLockAll") {
      group = "dependency management"
      description = "Resolves all resolvable configurations in all projects. Use with --write-locks."
      doFirst {
        val sp = project.gradle.startParameter
        // Best-effort guard: Gradle will only write lock files when --write-locks is set
        val writeLocksEnabled = try {
          sp.isWriteDependencyLocks
        } catch (_: Exception) {
          false
        }
        if (!writeLocksEnabled) {
          project.logger.log(
              LogLevel.LIFECYCLE,
              "resolveAndLockAll: run with --write-locks to persist gradle.lockfile updates.")
        }
      }
      doLast {
        project.gradle.allprojects {
          logger.lifecycle("Locking: resolving project $path")
          configurations
              .matching { it.isCanBeResolved }
              .forEach { cfg ->
            // Skip configs that cannot be resolved in isolation:
            // - revapiOld requires downloading baseline JAR from Maven Central
            // - DAGP (dependency-analysis) internal configs require cross-project resolution
            val skip = cfg.name.equals("revapiOld", ignoreCase = true) ||
                cfg.attributes.keySet().any { it.name == "dagp.internal.artifacts" }
            if (skip) {
              logger.lifecycle("Locking: skipping $path:${cfg.name}")
              return@forEach
            }
            logger.debug("Resolving configuration ${cfg.name} in $path")
            cfg.resolve()
          }
        }
      }
      // Task relies on configuration/runtime resolution and writes files; skip CC
      notCompatibleWithConfigurationCache("Resolves configurations and writes lockfiles")
    }
  }
}
