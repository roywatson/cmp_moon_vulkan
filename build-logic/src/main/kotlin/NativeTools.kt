import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * The host and the tools the native builds need. CMake and glslc can be pinned in
 * local.properties (`cmake=`, `glslc=`); otherwise the usual install locations are searched.
 */
class NativeTools(private val project: Project) {
    val isMacHost: Boolean = hostOs.startsWith("Mac")
    val isWindowsHost: Boolean = hostOs.startsWith("Windows")
    val isLinuxHost: Boolean = hostOs == "Linux"

    private val localProperties = Properties().apply {
        project.rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    private val androidSdk: File? = (localProperty("sdk.dir") ?: System.getenv("ANDROID_HOME"))?.let(::File)

    /** A value from local.properties, which holds machine-specific settings and is not committed. */
    fun localProperty(name: String): String? = localProperties.getProperty(name)

    /**
     * On Windows, prefer the CMake bundled with Visual Studio: it always knows its own Visual
     * Studio generator, whereas an older CMake (such as the Android SDK's 3.22) does not know
     * newer Visual Studio releases, falls back to NMake, and fails with "CMAKE_C_COMPILER not set".
     */
    val cmake: String by lazy {
        localProperty("cmake")
            ?: if (isWindowsHost) {
                visualStudioCmake() ?: onPath("cmake") ?: androidSdkCmake() ?: "cmake"
            } else {
                androidSdkCmake() ?: onPath("cmake") ?: "cmake"
            }
    }

    /** glslc ships with the Android NDK and the LunarG Vulkan SDK; use whichever is present. */
    val glslc: String by lazy {
        localProperty("glslc") ?: ndkGlslc() ?: vulkanSdkGlslc() ?: onPath("glslc") ?: "glslc"
    }

    private fun executable(name: String) = if (isWindowsHost) "$name.exe" else name

    private fun onPath(name: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, executable(name)) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath

    /** The newest version inside an SDK directory that contains [relativePath]. */
    private fun newestIn(directory: File, relativePath: String): String? = directory.listFiles().orEmpty()
        .sortedDescending()
        .map { File(it, relativePath) }
        .firstOrNull { it.canExecute() }
        ?.absolutePath

    private fun androidSdkCmake() = androidSdk?.let { newestIn(File(it, "cmake"), "bin/${executable("cmake")}") }

    private fun ndkGlslc(): String? {
        val hostTag = when {
            isWindowsHost -> "windows-x86_64"
            isMacHost -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        return androidSdk?.let { newestIn(File(it, "ndk"), "shader-tools/$hostTag/${executable("glslc")}") }
    }

    private fun vulkanSdkGlslc(): String? = System.getenv("VULKAN_SDK")?.let { sdk ->
        listOf("Bin", "bin").map { File(sdk, "$it/${executable("glslc")}") }.firstOrNull { it.canExecute() }
    }?.absolutePath

    /** The CMake inside the newest Visual Studio (or Build Tools) install with the C++ toolset. */
    private fun visualStudioCmake(): String? {
        val programFiles = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val vswhere = File(programFiles, "Microsoft Visual Studio/Installer/vswhere.exe")
        if (!vswhere.isFile) return null
        val installation = project.providers.exec {
            commandLine(
                vswhere.absolutePath,
                "-latest",
                "-products",
                "*",
                "-requires",
                "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property",
                "installationPath",
            )
            isIgnoreExitValue = true
        }.standardOutput.asText.get().lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        return installation
            ?.let { File(it, "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe") }
            ?.takeIf { it.canExecute() }
            ?.absolutePath
    }

    private companion object {
        val hostOs: String = System.getProperty("os.name")
    }
}
