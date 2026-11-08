import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("lunar.native-build")
}

// --- iOS native renderer -------------------------------------------------------------
// The shared C++ Vulkan renderer (renderer/) plus the iOS bridge (src/iosMain/native) are
// built with CMake per iOS target into libmoon_ios.a, which also contains MoltenVK.
// cinterop (src/nativeInterop/cinterop/moon.def) links it into the Shared framework.
val iosNativeSource = layout.projectDirectory.dir("src/iosMain/native")
val rendererSource = rootProject.layout.projectDirectory.dir("renderer")
val moltenVkRoot = rootProject.layout.projectDirectory.dir("third_party/MoltenVK")

/** Registers the CMake configure and build steps for one iOS target; returns the build step and its output directory. */
fun registerIosNativeBuild(
    target: KotlinNativeTarget,
    sdk: String,
    moltenVkSlice: String,
): Pair<TaskProvider<NativeToolTask>, File> {
    val name = target.name.replaceFirstChar { it.uppercase() }
    val outputDirectory = layout.buildDirectory.dir("native/${target.name}").get().asFile
    val moltenVk = moltenVkRoot.file("lib/$moltenVkSlice/libMoltenVK.a").asFile.absolutePath
    val configure = tasks.register<NativeToolTask>("configureMoonNative$name") {
        inputs.dir(iosNativeSource)
        inputs.dir(rendererSource)
        outputs.file(File(outputDirectory, "CMakeCache.txt"))
        cmakeBuildDirectory.set(outputDirectory)
        toolCommandLine.set(
            listOf(
                nativeTools.cmake,
                "-S", iosNativeSource.asFile.absolutePath,
                "-B", outputDirectory.absolutePath,
                "-DCMAKE_SYSTEM_NAME=iOS",
                "-DCMAKE_OSX_SYSROOT=$sdk",
                "-DCMAKE_OSX_ARCHITECTURES=arm64",
                "-DCMAKE_OSX_DEPLOYMENT_TARGET=16.0",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DGLSLC_EXECUTABLE=${nativeTools.glslc}",
                "-DMOLTENVK_LIBRARY=$moltenVk",
            ),
        )
    }
    val build = tasks.register<NativeToolTask>("buildMoonNative$name") {
        dependsOn(configure)
        inputs.dir(iosNativeSource)
        inputs.dir(rendererSource)
        outputs.file(File(outputDirectory, "libmoon_ios.a"))
        toolCommandLine.set(listOf(nativeTools.cmake, "--build", outputDirectory.absolutePath, "--parallel"))
    }
    return build to outputDirectory
}

kotlin {
    listOf(
        iosArm64() to ("iphoneos" to "ios-arm64"),
        iosSimulatorArm64() to ("iphonesimulator" to "ios-arm64_x86_64-simulator"),
    ).forEach { (iosTarget, sdkAndSlice) ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        val (nativeBuild, nativeOutput) = registerIosNativeBuild(iosTarget, sdkAndSlice.first, sdkAndSlice.second)
        iosTarget.compilations.getByName("main").cinterops.create("moon") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/moon.def"))
            includeDirs(iosNativeSource.asFile)
            extraOpts("-libraryPath", nativeOutput.absolutePath)
            tasks.named(interopProcessingTaskName) {
                dependsOn(nativeBuild)
                // cinterop copies libmoon_ios.a into the klib; without this input a C++-only
                // change leaves the task up to date and links the stale library.
                inputs.file(File(nativeOutput, "libmoon_ios.a")).withPropertyName("moonNativeLibrary")
            }
        }
    }
    jvm()
    android {
        namespace = "com.roywatson.lunar.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // Compose resources (the lunar maps) are packaged as Android assets.
        androidResources {
            enable = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            // Skia runtime for headless ImageComposeScene screenshot tests.
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.uiTest)
        }
    }
}

// One stable package for the generated resource accessors (and the packaged resource path
// "composeResources/com.roywatson.lunar.resources/"), independent of the Gradle project name.
compose.resources {
    packageOfResClass = "com.roywatson.lunar.resources"
    // The Android and desktop app modules use the shared strings too.
    publicResClass = true
}

// Screenshot tests (src/jvmTest/.../ComposeShellScreenshotTest.kt).
// Record/update goldens with: ./gradlew :shared:jvmTest -Pscreenshots.record=true
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    val goldens = layout.projectDirectory.dir("src/jvmTest/screenshots")
    systemProperty("screenshots.record", providers.gradleProperty("screenshots.record").getOrElse("false"))
    systemProperty("screenshots.dir", goldens.asFile.absolutePath)
    systemProperty(
        "screenshots.reportDir",
        layout.buildDirectory.dir("reports/screenshots").get().asFile.absolutePath,
    )
    systemProperty("java.awt.headless", "true")
    inputs.files(fileTree(goldens)).withPropertyName("screenshotGoldens")
    // Recording writes goldens; never serve it from the build cache.
    if (providers.gradleProperty("screenshots.record").orNull == "true") outputs.upToDateWhen { false }
}
