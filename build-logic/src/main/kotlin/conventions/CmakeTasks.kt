package conventions

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

/**
 * Configuration-cache-compatible task to run CMake configure step.
 *
 * Generates build files for a CMake project.
 */
abstract class CmakeConfigureTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** CMake source directory containing CMakeLists.txt. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** CMake build output directory. */
    @get:OutputDirectory
    abstract val buildDir: DirectoryProperty

    /** CMake build type (Release, Debug, etc.). */
    @get:Input
    abstract val buildType: Property<String>

    init {
        group = "build"
        description = "Generate CMake build files"
        buildType.convention("Release")
    }

    @TaskAction
    fun run() {
        val buildDirectory = buildDir.get().asFile
        buildDirectory.mkdirs()

        execOperations.exec {
            commandLine(
                "cmake",
                "-S", sourceDir.get().asFile.absolutePath,
                "-B", buildDirectory.absolutePath,
                "-DCMAKE_BUILD_TYPE=${buildType.get()}"
            )
        }
    }
}

/**
 * Configuration-cache-compatible task to run CMake build step.
 *
 * Compiles a previously configured CMake project.
 */
abstract class CmakeBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** CMake build directory (output from configure step). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildDir: DirectoryProperty

    /** CMake build type (Release, Debug, etc.). */
    @get:Input
    abstract val buildType: Property<String>

    init {
        group = "build"
        description = "Compile CMake project"
        buildType.convention("Release")
    }

    @TaskAction
    fun run() {
        execOperations.exec {
            commandLine(
                "cmake",
                "--build", buildDir.get().asFile.absolutePath,
                "--config", buildType.get()
            )
        }
    }
}
