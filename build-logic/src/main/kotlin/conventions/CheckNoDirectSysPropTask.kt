package conventions

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/**
 * Configuration-cache-compatible task that fails if production sources contain
 * literal `System.getProperty("justsearch.")` calls.
 *
 * Use EnvRegistry instead. Exempt: telemetry module (architectural constraint)
 * and lines tagged SYS-PROP-LEGACY-COMPAT.
 */
abstract class CheckNoDirectSysPropTask : DefaultTask() {

    /** Production Java source files to scan. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val sourceFiles: ConfigurableFileCollection

    /** Marker file written on success — enables UP-TO-DATE checking. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    init {
        group = "verification"
        description =
            "Fails if production sources contain literal System.getProperty(\"justsearch.\") " +
                "calls. Use EnvRegistry instead. Exempt: telemetry module (architectural " +
                "constraint) and lines tagged SYS-PROP-LEGACY-COMPAT."
    }

    @TaskAction
    fun check() {
        val violations = mutableListOf<String>()
        sourceFiles.files
            .filter { it.isFile && it.name.endsWith(".java") }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (
                        line.contains("System.getProperty(\"justsearch.") &&
                        !line.contains("SYS-PROP-LEGACY-COMPAT")
                    ) {
                        violations += "  ${file.name}:${idx + 1}"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Direct System.getProperty(\"justsearch.*\") calls found in production sources.\n" +
                    "Use EnvRegistry instead " +
                    "(modules/configuration/src/main/.../EnvRegistry.java).\n" +
                    "Violations:\n" +
                    violations.joinToString("\n")
            )
        }
        val out = reportFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText("OK — ${sourceFiles.files.size} files checked\n")
    }
}
