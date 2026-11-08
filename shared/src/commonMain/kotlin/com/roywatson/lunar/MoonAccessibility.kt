package com.roywatson.lunar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.action_tilt_north
import com.roywatson.lunar.resources.action_tilt_south
import com.roywatson.lunar.resources.action_turn_east
import com.roywatson.lunar.resources.action_turn_west
import com.roywatson.lunar.resources.globe_description
import com.roywatson.lunar.resources.globe_tilted_north
import com.roywatson.lunar.resources.globe_tilted_south
import com.roywatson.lunar.resources.globe_turned_east
import com.roywatson.lunar.resources.globe_turned_west
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** How far one accessibility action turns or tilts the globe. */
internal const val ACCESSIBILITY_TURN_DEGREES = 15f

/** The globe's orientation in whole degrees, as a screen reader describes it. */
internal data class GlobeOrientation(val east: Int, val north: Int)

/** Longitude is unbounded, so it is described as the nearest equivalent within ±180°. */
internal fun MoonView.orientation() =
    GlobeOrientation(east = ((longitude % 360f + 540f) % 360f - 180f).roundToInt(), north = latitude.roundToInt())

/** For example "The Moon, turned 23 degrees west and tilted 8 degrees north". */
@Composable
internal fun globeDescription(view: MoonView): String {
    val (east, north) = view.orientation()
    val turn = if (east >= 0) {
        pluralStringResource(Res.plurals.globe_turned_east, east, east)
    } else {
        pluralStringResource(Res.plurals.globe_turned_west, -east, -east)
    }
    val tilt = if (north >= 0) {
        pluralStringResource(Res.plurals.globe_tilted_north, north, north)
    } else {
        pluralStringResource(Res.plurals.globe_tilted_south, -north, -north)
    }
    return stringResource(Res.string.globe_description, turn, tilt)
}

/** Localized names of the globe's accessibility actions. */
internal data class GlobeActionLabels(
    val turnEast: String,
    val turnWest: String,
    val tiltNorth: String,
    val tiltSouth: String,
)

@Composable
internal fun globeActionLabels() = GlobeActionLabels(
    turnEast = stringResource(Res.string.action_turn_east),
    turnWest = stringResource(Res.string.action_turn_west),
    tiltNorth = stringResource(Res.string.action_tilt_north),
    tiltSouth = stringResource(Res.string.action_tilt_south),
)

/**
 * Describes the globe and offers turn and tilt actions, so screen-reader users get the control
 * that dragging gives everyone else. [onTurn] receives degrees east and north.
 */
internal fun Modifier.globeSemantics(
    description: String,
    actions: GlobeActionLabels,
    onTurn: (eastDegrees: Float, northDegrees: Float) -> Unit,
) = semantics {
    contentDescription = description
    customActions = listOf(
        CustomAccessibilityAction(actions.turnEast) {
            onTurn(ACCESSIBILITY_TURN_DEGREES, 0f)
            true
        },
        CustomAccessibilityAction(actions.turnWest) {
            onTurn(-ACCESSIBILITY_TURN_DEGREES, 0f)
            true
        },
        CustomAccessibilityAction(actions.tiltNorth) {
            onTurn(0f, ACCESSIBILITY_TURN_DEGREES)
            true
        },
        CustomAccessibilityAction(actions.tiltSouth) {
            onTurn(0f, -ACCESSIBILITY_TURN_DEGREES)
            true
        },
    )
}
