package com.roywatson.lunar

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/** Checks what a screen reader is given, using the shared shell with its Compose preview. */
@OptIn(ExperimentalTestApi::class)
class AppAccessibilityTest {
    @Test
    fun slidersAnnounceTheirNameAndValue() = runComposeUiTest {
        setContent { App() }

        onNode(hasContentDescription("Distance")).assert(hasStateDescription("100%"))
        onNode(hasContentDescription("Sun azimuth")).assert(hasStateDescription("-54 degrees"))
        onNode(hasContentDescription("Terrain detail")).assert(hasStateDescription("High"))
        onNode(hasContentDescription("Relief")).assert(hasStateDescription("100%"))
    }

    @Test
    fun visualLabelsAreNotReadTwice() = runComposeUiTest {
        setContent { App() }

        onNodeWithText("DISTANCE").assertDoesNotExist()
        onNodeWithText("100%").assertDoesNotExist()
        onNodeWithText("DRAG TO ORBIT").assertDoesNotExist()
    }

    @Test
    fun titleIsAHeading() = runComposeUiTest {
        setContent { App() }

        onNodeWithText("Rotatable lunar surface").assert(isHeading())
    }

    @Test
    fun globeActionsTurnAndTiltIt() = runComposeUiTest {
        setContent { App() }
        val globe = onNode(hasContentDescription("The Moon", substring = true))
        globe.assertContentDescriptionEquals("The Moon, turned 23 degrees west and tilted 8 degrees north")

        globe.performCustomAccessibilityActionWithLabel("Turn east")
        globe.assertContentDescriptionEquals("The Moon, turned 8 degrees west and tilted 8 degrees north")

        globe.performCustomAccessibilityActionWithLabel("Tilt south")
        globe.assertContentDescriptionEquals("The Moon, turned 8 degrees west and tilted 7 degrees south")
    }

    @Test
    fun descriptionUsesSingularForOneDegree() = runComposeUiTest {
        setContent { Text(globeDescription(MoonView(longitude = 1f, latitude = -1f))) }

        onNodeWithText("The Moon, turned 1 degree east and tilted 1 degree south").assertExists()
    }

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
