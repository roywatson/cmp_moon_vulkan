package com.roywatson.lunar

import kotlin.test.Test
import kotlin.test.assertEquals

/** The orientation a screen reader describes. The wording itself is covered by AppAccessibilityTest. */
class MoonAccessibilityTest {
    @Test
    fun defaultViewIsTurnedWestAndTiltedNorth() {
        assertEquals(GlobeOrientation(east = -23, north = 8), MoonView().orientation())
    }

    @Test
    fun longitudeIsDescribedWithinHalfATurn() {
        // 350° east is the same orientation as 10° west.
        assertEquals(-10, MoonView(longitude = 350f).orientation().east)
        assertEquals(10, MoonView(longitude = -710f).orientation().east)
        assertEquals(-180, MoonView(longitude = 180f).orientation().east)
    }

    @Test
    fun latitudeIsRoundedToWholeDegrees() {
        assertEquals(-12, MoonView(latitude = -11.6f).orientation().north)
    }

    @Test
    fun turningByOneActionStepChangesTheOrientationByThatStep() {
        val turned = MoonView(longitude = 0f, latitude = 0f)
            .turnedBy(eastDegrees = ACCESSIBILITY_TURN_DEGREES, northDegrees = -ACCESSIBILITY_TURN_DEGREES)

        assertEquals(GlobeOrientation(east = 15, north = -15), turned.orientation())
    }

    @Test
    fun tiltingStopsShortOfThePoles() {
        val view = MoonView(latitude = MoonView.LATITUDE_RANGE.endInclusive)

        assertEquals(MoonView.LATITUDE_RANGE.endInclusive, view.turnedBy(eastDegrees = 0f, northDegrees = 15f).latitude)
    }
}
