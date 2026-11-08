package com.roywatson.lunar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.roywatson.lunar.desktop.DesktopVulkanSurface
import com.roywatson.lunar.desktop.desktopVulkanSupported
import com.roywatson.lunar.desktop.preloadDesktopTextures
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.app_name
import org.jetbrains.compose.resources.stringResource

fun main() {
    // Windows/Linux: stop AWT painting the Vulkan canvas' background on resize (flicker).
    // Must be set before the AWT toolkit starts.
    System.setProperty("sun.awt.noerasebackground", "true")
    // Give popups (the about screen) their own windows: the Vulkan canvas is a heavyweight AWT
    // component, so anything Compose draws in the main window would appear beneath it.
    System.setProperty("compose.layers.type", "WINDOW")
    // Windows: Skiko's Direct3D renderer crashes natively (in PictureRecorder.finishRecordingAsPicture)
    // as soon as a popup gets its own window, so draw Compose with OpenGL there instead.
    if (System.getProperty("os.name").startsWith("Windows")) System.setProperty("skiko.renderApi", "OPENGL")
    if (desktopVulkanSupported) preloadDesktopTextures()
    application {
        Window(onCloseRequest = ::exitApplication, title = stringResource(Res.string.app_name)) {
            // Native bridges exist for macOS, Windows, and Linux; elsewhere App shows its Compose preview.
            App(nativeSurface = if (desktopVulkanSupported) vulkanSurface else null)
        }
    }
}

private val vulkanSurface: NativeSurface = { view, onOrbit, onPan ->
    DesktopVulkanSurface(view, onOrbit, onPan, Modifier.fillMaxSize())
}
