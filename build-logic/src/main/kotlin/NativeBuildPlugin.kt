import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Shared setup for modules that build the C++ renderer with CMake: adds the `nativeTools`
 * extension. Register the CMake steps as [NativeToolTask]s.
 */
class NativeBuildPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.add(NativeTools::class.java, "nativeTools", NativeTools(project))
    }
}
