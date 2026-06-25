package conventions

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * Copies the built desktop launcher ZIP into the dist/ folder under a stable name.
 */
abstract class CopyLauncherDist @Inject constructor(private val fileOps: org.gradle.api.file.FileSystemOperations) :
    DefaultTask() {

  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sourceZip: RegularFileProperty

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Input abstract val archiveName: Property<String>

  /** Project directory for relative path logging (injected at config time for CC compatibility). */
  @get:Internal abstract val projectDir: DirectoryProperty

  @TaskAction
  fun copy() {
    val targetDir = outputDir.get().asFile
    targetDir.mkdirs()
    val destination = targetDir.resolve(archiveName.get())
    fileOps.copy {
      from(sourceZip)
      into(targetDir)
      rename { _ -> destination.name }
    }
    logger.lifecycle("assembleDesktopDist: wrote {}", destination.relativeTo(projectDir.get().asFile))
  }
}
