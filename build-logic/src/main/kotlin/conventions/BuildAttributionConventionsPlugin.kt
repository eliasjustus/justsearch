package conventions

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry

class BuildAttributionConventionsPlugin @Inject constructor(
    private val events: BuildEventsListenerRegistry,
) : Plugin<Project> {
  override fun apply(project: Project) {
    if (project != project.rootProject) {
      return
    }

    val outputPath =
        project.providers.gradleProperty("justsearchBuildAttributionTasksJson").orNull
            ?: project.providers.gradleProperty("justsearch.buildAttribution.tasksJson").orNull
            ?: return

    val outputFile = project.layout.file(project.provider { project.file(outputPath) })
    val service =
        project.gradle.sharedServices.registerIfAbsent(
            "buildAttributionService",
            BuildAttributionService::class.java,
        ) {
          parameters.outputFile.set(outputFile)
          parameters.rootDir.set(project.layout.projectDirectory)
        }

    events.onTaskCompletion(service)
  }
}
