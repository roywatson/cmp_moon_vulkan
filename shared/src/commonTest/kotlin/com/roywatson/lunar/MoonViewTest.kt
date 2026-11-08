package com.roywatson.lunar

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoonViewTest {
    @Test
    fun draggingDownRaisesLatitudeSoTheSurfaceFollowsTheFinger() {
        val view = MoonView(latitude = 0f)

        assertTrue(view.orbitedBy(dx = 0f, dy = 10f).latitude > 0f)
        assertTrue(view.orbitedBy(dx = 0f, dy = -10f).latitude < 0f)
    }

    @Test
    fun draggingRightTurnsTheGlobeEast() {
        val view = MoonView(longitude = 0f)

        assertTrue(view.orbitedBy(dx = 10f, dy = 0f).longitude > 0f)
    }

    @Test
    fun latitudeStopsShortOfThePoles() {
        val view = MoonView(latitude = 0f)

        assertEquals(MoonView.LATITUDE_RANGE.endInclusive, view.orbitedBy(dx = 0f, dy = 100_000f).latitude)
        assertEquals(MoonView.LATITUDE_RANGE.start, view.orbitedBy(dx = 0f, dy = -100_000f).latitude)
    }

    @Test
    fun longitudeIsUnbounded() {
        val view = MoonView(longitude = 0f)

        assertTrue(view.orbitedBy(dx = 100_000f, dy = 0f).longitude > 360f)
    }

    @Test
    fun panIsClampedOnBothAxes() {
        val view = MoonView().pannedBy(dx = 10f, dy = -10f)

        assertEquals(MoonView.TRANSLATION_RANGE.endInclusive, view.translationX)
        assertEquals(MoonView.TRANSLATION_RANGE.start, view.translationY)
    }

    @Test
    fun gesturesLeaveTheOtherSettingsAlone() {
        val view = MoonView(distance = 1.5f, sunAzimuth = 10f, terrainDetail = TerrainDetail.Low, relief = .7f)

        val moved = view.orbitedBy(dx = 5f, dy = 5f).pannedBy(dx = .1f, dy = .1f)

        assertEquals(
            view.copy(
                longitude = moved.longitude,
                latitude = moved.latitude,
                translationX = moved.translationX,
                translationY = moved.translationY,
            ),
            moved,
        )
    }

    @Test
    fun saverRestoresEveryField() {
        val view = MoonView(
            longitude = 123f,
            latitude = -45f,
            distance = 1.7f,
            sunAzimuth = 30f,
            translationX = .25f,
            translationY = -.5f,
            terrainDetail = TerrainDetail.Balanced,
            relief = 1.5f,
        )
        val scope = SaverScope { true }

        val saved = with(MoonView.Saver) { scope.save(view) }

        assertEquals(view, MoonView.Saver.restore(saved!!))
    }

    @Test
    fun defaultsLieWithinTheirRanges() {
        val view = MoonView()

        assertTrue(view.latitude in MoonView.LATITUDE_RANGE)
        assertTrue(view.distance in MoonView.DISTANCE_RANGE)
        assertTrue(view.sunAzimuth in MoonView.SUN_AZIMUTH_RANGE)
        assertTrue(view.relief in MoonView.RELIEF_RANGE)
    }
}
