package com.roywatson.lunar

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.roywatson.lunar.ios.IosVulkanSurface

/** Entry point for the SwiftUI host (iosApp/ContentView.swift). */
fun mainViewController() = ComposeUIViewController {
    // Compose owns the controls and gestures; iOS contributes only a CAMetalLayer for MoltenVK.
    App { view, _, _ -> IosVulkanSurface(view, Modifier.fillMaxSize()) }
}
