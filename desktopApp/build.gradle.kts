import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("lunar.native-build")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.material3)
    implementation(libs.compose.components.resources)
}

// --- Native bridge ---------------------------------------------------------------------
// src/main/native (JNI + JAWT) and the shared renderer build with CMake into one library.
// Bridges exist for macOS (MoltenVK), Windows, and Linux; other hosts skip the native build
// and the app shows its Compose preview instead.
val hasNativeBridge = nativeTools.isMacHost || nativeTools.isWindowsHost || nativeTools.isLinuxHost
val nativeSource = layout.projectDirectory.dir("src/main/native")
val rendererSource = rootProject.layout.projectDirectory.dir("renderer")
val nativeBuild = layout.buildDirectory.dir("native")
// libdesktop_moon_renderer.dylib on macOS, desktop_moon_renderer.dll on Windows, .so on Linux.
val nativeLibrary = nativeBuild.map { it.file(System.mapLibraryName("desktop_moon_renderer")) }

// One JDK for everything native: its headers compile the bridge, its JAWT is linked, and it runs
// the app. Mixing JDKs (e.g. JBR headers/JAWT with a Zulu runtime) crashes inside JAWT. Override
// with `desktop.java.home` in local.properties; the default is the JDK running Gradle.
val desktopJavaHome: String = nativeTools.localProperty("desktop.java.home")
    ?: providers.gradleProperty("desktop.java.home").orNull
    ?: System.getProperty("java.home")

val configureDesktopNative by tasks.registering(NativeToolTask::class) {
    enabled = hasNativeBridge
    inputs.dir(nativeSource)
    inputs.dir(rendererSource)
    inputs.property("javaHome", desktopJavaHome)
    outputs.file(nativeBuild.map { it.file("CMakeCache.txt") })
    cmakeBuildDirectory.set(nativeBuild)
    toolCommandLine.set(
        listOf(
            nativeTools.cmake,
            "-S",
            nativeSource.asFile.absolutePath,
            "-B",
            nativeBuild.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DJAVA_HOME=$desktopJavaHome",
            "-DGLSLC_EXECUTABLE=${nativeTools.glslc}",
        ),
    )
}

val buildDesktopNative by tasks.registering(NativeToolTask::class) {
    enabled = hasNativeBridge
    dependsOn(configureDesktopNative)
    inputs.dir(nativeSource)
    inputs.dir(rendererSource)
    outputs.file(nativeLibrary)
    // --config selects Release under multi-config generators (Visual Studio on Windows).
    toolCommandLine.set(
        listOf(
            nativeTools.cmake,
            "--build",
            nativeBuild.get().asFile.absolutePath,
            "--config",
            "Release",
            "--parallel",
        ),
    )
}

// The native library ships as a Compose "app resource": the run task and packaged apps both
// expose that directory to the app as `compose.application.resources.dir`. It is built for the
// host only, so it goes in the resources shared by every OS.
val appResources = layout.buildDirectory.dir("appResources")
val syncNativeAppResources by tasks.registering(Sync::class) {
    dependsOn(buildDesktopNative)
    from(nativeLibrary)
    into(appResources.map { it.dir("common") })
}
// The Compose plugin registers prepareAppResources after this script runs.
tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(syncNativeAppResources)
}

compose.desktop {
    application {
        mainClass = "com.roywatson.lunar.MainKt"
        javaHome = desktopJavaHome

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "LunarVulkan"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(appResources)
        }
    }
}
