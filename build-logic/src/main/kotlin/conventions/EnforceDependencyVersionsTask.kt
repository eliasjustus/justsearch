package conventions

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/**
 * Configuration-cache-compatible task to enforce dependency version hygiene.
 *
 * Scans build files for dynamic versions (1.+, latest.*, -SNAPSHOT) and
 * fails if any are found.
 */
abstract class EnforceDependencyVersionsTask : DefaultTask() {

    /** Build files to scan (*.gradle.kts, libs.versions.toml). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val buildFiles: ConfigurableFileCollection

    /** Root directory for relative path reporting. */
    @get:Internal
    abstract val rootDir: DirectoryProperty

    /** Marker file written on success — enables UP-TO-DATE checking. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    init {
        group = "verification"
        description = "Fail on dynamic/SNAPSHOT dependency versions and raw GAVs where forbidden"
    }

    @TaskAction
    fun validate() {
        val root = rootDir.get().asFile
        val offenders = mutableListOf<String>()

        buildFiles.forEach { f ->
            val text = f.readText()

            // Detect dynamic/snapshot versions in TOML catalog
            val isTomlCatalog = f.name == "libs.versions.toml"
            val tomlDynamic = Regex("""(?i)"[^"]*\+[^"]*"|latest\.(release|integration)|-SNAPSHOT""")
            val hasDynamicTOML = isTomlCatalog && tomlDynamic.containsMatchIn(text)

            // Detect Gradle GAV strings with dynamic/SNAPSHOT versions
            val gavPattern = Regex(""""([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([^"\s]+)"""")
            val hasDynamicGav = gavPattern.findAll(text).any { m ->
                val ver = m.groupValues[3]
                ver.contains('+') || ver.contains("SNAPSHOT", ignoreCase = true) || ver.startsWith("latest.")
            }

            if (hasDynamicTOML || hasDynamicGav) {
                offenders.add(f.relativeTo(root).path)
            }
        }

        if (offenders.isNotEmpty()) {
            val message = buildString {
                appendLine("Dependency version hygiene violation:")
                appendLine("Dynamic or SNAPSHOT versions detected in:")
                offenders.distinct().sorted().forEach { path -> appendLine("  - $path") }
                appendLine("Use catalog-pinned fixed versions (no '+' or '-SNAPSHOT'); avoid 'latest.*'.")
            }
            throw GradleException(message)
        }
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText("OK\n")
    }
}
