package conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar

class ArchivingReproduciblePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.tasks.withType(AbstractArchiveTask::class.java).configureEach {
      isPreserveFileTimestamps = false
      isReproducibleFileOrder = true
    }

    // Sanitize JAR manifests for determinism (no user/host/JDK-specific values)
    project.tasks.withType(Jar::class.java).configureEach {
      // Set stable values for commonly non-deterministic attributes (constant, CC-safe)
      manifest.attributes(mapOf(
          "Created-By" to "Gradle",
          "Built-By" to "justsearch"
      ))
    }
  }
}
