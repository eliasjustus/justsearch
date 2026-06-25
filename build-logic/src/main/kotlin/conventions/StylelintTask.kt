package conventions

import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

/**
 * Configuration-cache-compatible task to run stylelint via npx.
 *
 * This task follows the CC-compatible pattern for exec-based tasks:
 * - Uses [ExecOperations] injection instead of extending Exec
 * - All inputs/outputs declared as properties with proper annotations
 * - No project references captured at configuration time
 * - System properties evaluated at execution time only
 */
abstract class StylelintTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** CSS files to lint. Task is skipped if empty. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val cssFiles: ConfigurableFileCollection

    /** Stylelint configuration file (e.g., stylelint.config.cjs). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    /** Stylelint version to use via npx. */
    @get:Input
    abstract val stylelintVersion: Property<String>

    /** Glob pattern passed to stylelint for file matching. */
    @get:Input
    abstract val cssPattern: Property<String>

    /** JSON report output file. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /** Working directory for npx execution. */
    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    init {
        group = "verification"
        description = "Run stylelint against UI CSS resources"
        stylelintVersion.convention("16.8.0")
        cssPattern.convention("src/main/resources/css/**/*.css")
    }

    @TaskAction
    fun run() {
        val reportDir = reportFile.get().asFile.parentFile
        reportDir.mkdirs()

        // Evaluate OS at execution time, not configuration time
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
        val npx = if (isWindows) "npx.cmd" else "npx"

        execOperations.exec {
            workingDir = workingDirectory.get().asFile
            environment["FORCE_COLOR"] = "0"
            commandLine(
                npx,
                "--yes",
                "stylelint@${stylelintVersion.get()}",
                cssPattern.get(),
                "--config", configFile.get().asFile.absolutePath,
                "--formatter", "json",
                "--output-file", reportFile.get().asFile.absolutePath
            )
        }
    }
}
