package conventions

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.kotlin.dsl.withType

class SpotBugsConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.plugins.withType(JavaPlugin::class.java) {
      project.plugins.apply("com.github.spotbugs")

      // Add FindSecBugs plugin from version catalog for OWASP security patterns
      val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java)
      val findsecbugs = catalogs?.named("libs")?.findLibrary("findsecbugs")?.orElse(null)
      if (findsecbugs != null) {
        project.dependencies.add("spotbugsPlugins", findsecbugs)
      }

      // Warn-only until baseline violations are triaged. Opt-in to fail via
      // -Pspotbugs.failOnError=true or per-module extra["spotbugs.failOnError"] = "true"
      val failOnError = project.providers.gradleProperty("spotbugs.failOnError")
          .map { it.equals("true", ignoreCase = true) }
          .orElse(
              project.provider {
                project.extensions.extraProperties.has("spotbugs.failOnError") &&
                    project.extensions.extraProperties.get("spotbugs.failOnError")
                        .toString().equals("true", ignoreCase = true)
              }
          )
          .get()

      project.extensions.configure(SpotBugsExtension::class.java) {
        effort.set(Effort.DEFAULT)
        reportLevel.set(Confidence.MEDIUM)
        excludeFilter.set(
            project.rootProject.layout.projectDirectory
                .file("config/spotbugs/exclude-filter.xml")
        )
      }

      // Skip SpotBugs in local builds by default. Override with:
      //   -PskipSpotBugs=false   (force enable locally)
      //   CI=true env var         (auto-enabled in CI)
      val skipSpotBugs = project.providers.gradleProperty("skipSpotBugs")
          .map { it.equals("true", ignoreCase = true) }
          .orElse(
              project.providers.environmentVariable("CI")
                  .map { it.isBlank() || it.equals("false", ignoreCase = true) }
                  .orElse(true)
          )

      // Only run SpotBugs on main sources (not test classes).
      // Use onlyIf (lazy) instead of enabled=false (eager) for configuration-cache safety.
      project.tasks.withType(SpotBugsTask::class.java).configureEach {
        onlyIf { name == "spotbugsMain" && !skipSpotBugs.get() }
        ignoreFailures = !failOnError
      }
    }
  }
}
