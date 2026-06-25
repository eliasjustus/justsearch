package conventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plugin that enforces dependency version hygiene by detecting dynamic or SNAPSHOT versions.
 *
 * Applied only at the root project level; registers [EnforceDependencyVersionsTask] which
 * scans all *.gradle.kts and libs.versions.toml files for version hygiene violations.
 */
class DependencyHygienePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Only register the repository-wide scan once at the root project
        if (project != project.rootProject) return

        val task = project.tasks.register(
            "enforceDependencyVersions",
            EnforceDependencyVersionsTask::class.java
        ) {
            // Explicitly collect build files from known locations instead of walking the
            // entire project directory tree. The previous asFileTree glob was scanning
            // tmp/ (1M+ files) and .claude/worktrees/ (90K+ files), taking 20-50s.
            val rootDir = project.layout.projectDirectory

            // Root-level build files
            buildFiles.from(
                rootDir.file("build.gradle.kts"),
                rootDir.file("settings.gradle.kts"),
                rootDir.file("gradle/libs.versions.toml")
            )

            // build-logic build files
            buildFiles.from(
                rootDir.file("build-logic/build.gradle.kts"),
                rootDir.file("build-logic/settings.gradle.kts")
            )

            // All subproject build files (filter to existing files only)
            project.subprojects.forEach { sub ->
                val buildFile = sub.layout.projectDirectory.file("build.gradle.kts").asFile
                if (buildFile.exists()) {
                    buildFiles.from(buildFile)
                }
            }

            this.rootDir.set(rootDir)
            reportFile.set(project.layout.buildDirectory.file("reports/dep-versions-ok.txt"))

            // The previous fileTree approach required mustRunAfter on overlapping tasks
            // because Gradle detected implicit directory overlaps. With explicit file
            // inputs, this ordering is no longer needed.
        }

        // Participate in the root lifecycle
        project.tasks.named("check") { dependsOn(task) }
    }
}
