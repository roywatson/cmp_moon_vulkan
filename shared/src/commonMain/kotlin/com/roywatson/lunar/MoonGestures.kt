package com.roywatson.lunar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Touch and mouse control for the globe: one pointer orbits; two or more pan by their
 * centroid. [onOrbit] receives the drag in pixels (y down); [onPan] receives it in normalized
 * device coordinates (y up), so a full-width drag pans by 2.
 */
internal fun Modifier.moonGestures(
    onOrbit: (dxPixels: Float, dyPixels: Float) -> Unit,
    onPan: (dxNdc: Float, dyNdc: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previousCentroid: Offset? = null
        var previousCount = 0
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val centroid =
                pressed.fold(Offset.Zero) { sum, change -> sum + change.position } /
                    pressed.size.toFloat()
            val previous = previousCentroid
            // Skip the event where the pointer count changes so the centroid does not jump.
            if (previous != null && pressed.size == previousCount) {
                val delta = centroid - previous
                if (pressed.size == 1) {
                    onOrbit(delta.x, delta.y)
                } else if (size.width > 0 && size.height > 0) {
                    onPan(delta.x * 2f / size.width, -delta.y * 2f / size.height)
                }
            }
            previousCentroid = centroid
            previousCount = pressed.size
            event.changes.forEach { it.consume() }
        }
    }
}
