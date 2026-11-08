import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs a native build tool (CMake) through [ExecOperations] rather than being an `Exec` task.
 * When you debug a Gradle run configuration, IntelliJ's Kotlin/Native debugger init script takes
 * over every `Exec` task: it swaps the executable for LLDB's debugserver and waits for the IDE to
 * attach. For CMake that attach never happens, so the build hangs.
 */
abstract class NativeToolTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input
    abstract val toolCommandLine: ListProperty<String>

    /**
     * Set on a configure step: a CMake build tree configured by a *different* cmake (and so
     * possibly a different generator) is discarded first, because CMake refuses to switch
     * generators in place ("Does not match the generator used previously").
     */
    @get:Internal
    abstract val cmakeBuildDirectory: DirectoryProperty

    @TaskAction
    fun runTool() {
        cmakeBuildDirectory.orNull?.asFile?.let(::discardForeignCmakeCache)
        execOperations.exec { commandLine(toolCommandLine.get()) }
    }

    private fun discardForeignCmakeCache(buildDirectory: File) {
        val cache = File(buildDirectory, "CMakeCache.txt")
        if (!cache.isFile) return
        val previousCmake = cache.useLines { lines ->
            lines.firstOrNull { it.startsWith("CMAKE_COMMAND:INTERNAL=") }?.substringAfter('=')
        } ?: return
        val cmake = toolCommandLine.get().first()
        if (File(previousCmake).canonicalFile == File(cmake).canonicalFile) return
        logger.lifecycle("CMake changed ($previousCmake -> $cmake); reconfiguring from scratch")
        cache.delete()
        File(buildDirectory, "CMakeFiles").deleteRecursively()
    }
}
