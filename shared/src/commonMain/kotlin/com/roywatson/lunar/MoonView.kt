package com.roywatson.lunar

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Everything the renderer needs to draw a frame: camera, lighting, and terrain settings.
 *
 * This is the whole contract between the shared UI and every platform's native renderer; no
 * Vulkan or platform handle crosses it. The native side mirrors it as `moon::ViewState`.
 */
data class MoonView(
    /** Degrees the globe is turned east. Unbounded: it wraps visually. */
    val longitude: Float = -23f,
    /** Degrees the globe is tilted north, within [LATITUDE_RANGE]. */
    val latitude: Float = 8f,
    /** Framing multiplier (1 = default size), within [DISTANCE_RANGE]. */
    val distance: Float = 1f,
    /** Sun direction in degrees, within [SUN_AZIMUTH_RANGE]. */
    val sunAzimuth: Float = -54f,
    /** Horizontal pan in normalized device coordinates, within [TRANSLATION_RANGE]. */
    val translationX: Float = 0f,
    /** Vertical pan in normalized device coordinates (y up), within [TRANSLATION_RANGE]. */
    val translationY: Float = 0f,
    val terrainDetail: TerrainDetail = TerrainDetail.High,
    /** Strength of the elevation-map shading, within [RELIEF_RANGE]. */
    val relief: Float = 1f,
) {
    /**
     * Orbits by a drag of [dx] by [dy] screen pixels (y down). Dragging down tilts the globe so
     * the surface follows the finger.
     */
    fun orbitedBy(dx: Float, dy: Float): MoonView =
        turnedBy(eastDegrees = dx * ORBIT_DEGREES_PER_PIXEL_X, northDegrees = dy * ORBIT_DEGREES_PER_PIXEL_Y)

    /** Turns the globe [eastDegrees] east and tilts it [northDegrees] north, within [LATITUDE_RANGE]. */
    fun turnedBy(eastDegrees: Float, northDegrees: Float): MoonView = copy(
        longitude = longitude + eastDegrees,
        latitude = (latitude + northDegrees).coerceIn(LATITUDE_RANGE),
    )

    /** Pans by [dx] by [dy] in normalized device coordinates (y up). */
    fun pannedBy(dx: Float, dy: Float): MoonView = copy(
        translationX = (translationX + dx).coerceIn(TRANSLATION_RANGE),
        translationY = (translationY + dy).coerceIn(TRANSLATION_RANGE),
    )

    companion object {
        val LATITUDE_RANGE = -78f..78f
        val DISTANCE_RANGE = .72f..2.2f
        val SUN_AZIMUTH_RANGE = -85f..85f
        val TRANSLATION_RANGE = -1.5f..1.5f
        val RELIEF_RANGE = .5f..2f

        private const val ORBIT_DEGREES_PER_PIXEL_X = .34f
        private const val ORBIT_DEGREES_PER_PIXEL_Y = .28f

        /** Keeps the view across Android configuration changes and process death. */
        val Saver: Saver<MoonView, Any> = listSaver(
            save = { view ->
                listOf(
                    view.longitude,
                    view.latitude,
                    view.distance,
                    view.sunAzimuth,
                    view.translationX,
                    view.translationY,
                    view.terrainDetail.ordinal.toFloat(),
                    view.relief,
                )
            },
            restore = { values ->
                MoonView(
                    longitude = values[0],
                    latitude = values[1],
                    distance = values[2],
                    sunAzimuth = values[3],
                    translationX = values[4],
                    translationY = values[5],
                    terrainDetail = TerrainDetail.entries[values[6].toInt()],
                    relief = values[7],
                )
            },
        )
    }
}

/**
 * Sphere tessellation level. The ordinal crosses into native code and must match the order
 * of `moon::TerrainDetail`.
 */
enum class TerrainDetail {
    Low,
    Balanced,
    High,
}
