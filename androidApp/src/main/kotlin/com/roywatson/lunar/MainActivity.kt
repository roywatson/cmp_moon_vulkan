package com.roywatson.lunar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.roywatson.lunar.vulkan.VulkanMoonSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Compose owns the controls and gestures; Android contributes only the ANativeWindow.
            App { view, _, _ -> VulkanMoonSurface(view, Modifier.fillMaxSize()) }
        }
    }
}

/** Android Studio preview of the shared shell, using its Compose stand-in for the renderer. */
@Preview
@Composable
private fun AppPreview() {
    App()
}
