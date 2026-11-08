package com.roywatson.lunar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.control_distance
import com.roywatson.lunar.resources.control_relief
import com.roywatson.lunar.resources.control_sun_azimuth
import com.roywatson.lunar.resources.control_terrain_detail
import com.roywatson.lunar.resources.data_credit
import com.roywatson.lunar.resources.drag_hint
import com.roywatson.lunar.resources.header_series
import com.roywatson.lunar.resources.header_title
import com.roywatson.lunar.resources.lroc_color_2k
import com.roywatson.lunar.resources.render_preview
import com.roywatson.lunar.resources.terrain_balanced
import com.roywatson.lunar.resources.terrain_high
import com.roywatson.lunar.resources.terrain_low
import com.roywatson.lunar.resources.value_degrees_short
import com.roywatson.lunar.resources.value_degrees_spoken
import com.roywatson.lunar.resources.value_percent
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A platform's native globe renderer. It draws [view]; a surface that receives input natively
 * (rather than through Compose) reports drags through [onOrbit] (pixels, y down) and [onPan]
 * (normalized device coordinates, y up).
 */
typealias NativeSurface = @Composable (
    view: MoonView,
    onOrbit: (dxPixels: Float, dyPixels: Float) -> Unit,
    onPan: (dxNdc: Float, dyNdc: Float) -> Unit,
) -> Unit

/**
 * The shared shell: header, globe, and controls. [nativeSurface] draws the globe with the
 * platform's renderer; without one a Compose preview stands in. Touch over the globe is handled
 * here for every platform, so only surfaces that capture input themselves (the desktop's
 * heavyweight AWT canvas) need the drag callbacks.
 */
@Composable
fun App(nativeSurface: NativeSurface? = null) {
    var view by rememberSaveable(stateSaver = MoonView.Saver) { mutableStateOf(MoonView()) }
    val orbit = { dx: Float, dy: Float -> view = view.orbitedBy(dx, dy) }
    val pan = { dx: Float, dy: Float -> view = view.pannedBy(dx, dy) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(LunarColors.Space)) {
            // The background stays edge-to-edge; content avoids the status bar, notch/Dynamic
            // Island, and home indicator (iOS + Android edge-to-edge; zero on desktop).
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
                // Name what is actually drawing the globe: the native backend, or the Compose preview.
                Header(
                    rendererLabel = if (nativeSurface ==
                        null
                    ) {
                        stringResource(Res.string.render_preview)
                    } else {
                        rendererBackend
                    },
                    onAbout = { showAbout = true },
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .sizeIn(minHeight = 300.dp)
                        .moonGestures(onOrbit = orbit, onPan = pan)
                        .globeSemantics(globeDescription(view), globeActionLabels()) { east, north ->
                            view = view.turnedBy(east, north)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (nativeSurface == null) {
                        MoonCanvas(view)
                    } else {
                        nativeSurface(view, orbit, pan)
                    }
                    Text(
                        stringResource(Res.string.drag_hint).uppercase(),
                        color = LunarColors.Muted.copy(alpha = .78f),
                        style = MaterialTheme.typography.labelSmall,
                        // A visual hint only: screen readers get the globe's turn and tilt actions.
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                            .clearAndSetSemantics {},
                    )
                }
                Controls(view, onViewChange = { view = it })
            }
            AboutOverlay(visible = showAbout, onDismiss = { showAbout = false })
        }
    }
}

@Composable
private fun Header(rendererLabel: String, onAbout: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.header_series).uppercase(),
                color = LunarColors.Accent,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.header_title),
                color = LunarColors.Moonlight,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            rendererLabel.uppercase(),
            color = LunarColors.Muted,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
        AboutButton(onClick = onAbout)
    }
}

@Composable
private fun Controls(view: MoonView, onViewChange: (MoonView) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        ControlRow(stringResource(Res.string.control_distance), percent(view.distance)) { semantics ->
            Slider(
                modifier = semantics,
                value = view.distance,
                onValueChange = { onViewChange(view.copy(distance = it)) },
                valueRange = MoonView.DISTANCE_RANGE,
            )
        }
        val azimuth = view.sunAzimuth.roundToInt()
        ControlRow(
            label = stringResource(Res.string.control_sun_azimuth),
            value = stringResource(Res.string.value_degrees_short, azimuth),
            spokenValue = pluralStringResource(Res.plurals.value_degrees_spoken, abs(azimuth), azimuth),
        ) { semantics ->
            Slider(
                modifier = semantics,
                value = view.sunAzimuth,
                onValueChange = { onViewChange(view.copy(sunAzimuth = it)) },
                valueRange = MoonView.SUN_AZIMUTH_RANGE,
            )
        }
        ControlRow(
            stringResource(Res.string.control_terrain_detail),
            terrainDetailName(view.terrainDetail),
        ) { semantics ->
            Slider(
                modifier = semantics,
                value = view.terrainDetail.ordinal.toFloat(),
                onValueChange = { onViewChange(view.copy(terrainDetail = TerrainDetail.entries[it.roundToInt()])) },
                valueRange = 0f..TerrainDetail.entries.lastIndex.toFloat(),
                steps = TerrainDetail.entries.size - 2,
            )
        }
        ControlRow(stringResource(Res.string.control_relief), percent(view.relief)) { semantics ->
            Slider(
                modifier = semantics,
                value = view.relief,
                onValueChange = { onViewChange(view.copy(relief = it)) },
                valueRange = MoonView.RELIEF_RANGE,
            )
        }
        Text(
            stringResource(Res.string.data_credit),
            color = LunarColors.Muted.copy(alpha = .85f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun percent(fraction: Float) = stringResource(Res.string.value_percent, (fraction * 100).roundToInt())

@Composable
private fun terrainDetailName(detail: TerrainDetail) = stringResource(
    when (detail) {
        TerrainDetail.Low -> Res.string.terrain_low
        TerrainDetail.Balanced -> Res.string.terrain_balanced
        TerrainDetail.High -> Res.string.terrain_high
    },
)

/**
 * A labelled slider row, shown in capitals. The visible label and value are hidden from screen
 * readers; instead [content] receives a modifier that gives the slider itself a spoken name and
 * value ([spokenValue] defaults to [value]), since a bare slider would only announce a percentage
 * of its range.
 */
@Composable
private fun ControlRow(
    label: String,
    value: String,
    spokenValue: String = value,
    content: @Composable (semantics: Modifier) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            color = LunarColors.Muted,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            content(
                Modifier.semantics {
                    contentDescription = label
                    stateDescription = spokenValue
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            value.uppercase(),
            color = LunarColors.Moonlight,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun MoonCanvas(view: MoonView) = BoxWithConstraints(
    Modifier.fillMaxSize().graphicsLayer {
        // Pan is in normalized device coordinates: ±1 spans half the view, y up.
        translationX = view.translationX * size.width / 2
        translationY = -view.translationY * size.height / 2
    },
    contentAlignment = Alignment.Center,
) {
    var textureViewportWidth by remember { mutableIntStateOf(0) }
    // The globe must be square and fit both portrait and landscape windows. Deriving
    // its diameter from the smaller constraint avoids competing fill/aspect constraints.
    val globeDiameter = minOf(maxWidth, maxHeight) * .78f * view.distance
    Canvas(Modifier.fillMaxSize()) {
        val radius = minOf(size.width, size.height) * .39f * view.distance
        drawStars()
        drawCircle(Color(0xFF0A0F19), radius * 1.14f, center)
    }
    // The source is an equirectangular map. Repeating it behind a circular mask lets
    // drag move real lunar features across the globe instead of rotating a static photo.
    Box(
        Modifier
            .size(globeDiameter)
            .clip(CircleShape)
            .onSizeChanged { textureViewportWidth = it.width },
    ) {
        val wrappedLongitude = ((view.longitude % 360f) + 360f) % 360f
        // The map is MAP_SCALE x the globe's height so the latitude shift below (at most
        // 78/720 of the globe per side) never uncovers its top or bottom edge.
        val mapWidth = textureViewportWidth * 2f * MAP_SCALE
        val textureOffset = -wrappedLongitude / 360f * mapWidth
        repeat(3) { copy ->
            Image(
                painter = painterResource(Res.drawable.lroc_color_2k),
                // Decorative: the globe as a whole is described by globeSemantics.
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                // The 2:1 map is wider than the square globe can hold; requiredSize keeps the
                // full map instead of letting layout shrink it to fit.
                modifier = Modifier
                    .requiredSize(width = globeDiameter * 2 * MAP_SCALE, height = globeDiameter * MAP_SCALE)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = textureOffset + (copy - 1) * mapWidth
                        translationY = view.latitude * textureViewportWidth / 720f
                    },
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val radius = minOf(size.width, size.height) * .39f * view.distance
        // Short tick marks either side of the globe's equator.
        val tick = LunarColors.Accent.copy(alpha = .38f)
        drawLine(
            tick,
            Offset(center.x - radius - 16f, center.y),
            Offset(center.x - radius - 3f, center.y),
            1.5f,
            StrokeCap.Round,
        )
        drawLine(
            tick,
            Offset(center.x + radius + 3f, center.y),
            Offset(center.x + radius + 16f, center.y),
            1.5f,
            StrokeCap.Round,
        )
    }
}

/** Map height relative to the globe; covers the ±78° latitude shift with margin. */
private const val MAP_SCALE = 1.25f

private fun DrawScope.drawStars() {
    StarPositions.forEachIndexed { index, (x, y) ->
        val alpha = .34f + index % 3 * .12f
        drawCircle(
            LunarColors.Muted.copy(alpha = alpha),
            radius = 1.2f + index % 2,
            center = Offset(size.width * x, size.height * y),
        )
    }
}

/** Fixed star field, as fractions of the canvas width and height. */
private val StarPositions = listOf(
    .08f to .17f,
    .19f to .32f,
    .79f to .11f,
    .88f to .37f,
    .11f to .69f,
    .91f to .72f,
    .72f to .84f,
    .29f to .88f,
    .61f to .19f,
)
