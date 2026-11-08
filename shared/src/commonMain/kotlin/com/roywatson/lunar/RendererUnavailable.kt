package com.roywatson.lunar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Takes the globe's place when the platform's native renderer cannot start: a localized [message],
 * then any technical [details] (such as an exception message) as they are.
 */
@Composable
fun RendererUnavailable(message: String, modifier: Modifier = Modifier, details: String? = null) {
    Column(
        modifier.fillMaxSize().background(LunarColors.Space).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            color = LunarColors.Moonlight,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (details != null) {
            Text(
                details,
                color = LunarColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
