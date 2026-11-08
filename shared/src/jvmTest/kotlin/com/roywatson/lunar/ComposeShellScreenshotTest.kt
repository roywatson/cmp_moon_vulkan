package com.roywatson.lunar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Golden-image tests for the shared Compose shell, rendered headlessly with Skia (no GPU,
 * no native renderer). Goldens live in `src/jvmTest/screenshots/<name>-<os>.png`; fonts are
 * OS-specific, so each OS has its own goldens.
 *
 * Record or update goldens: ./gradlew :shared:jvmTest -Pscreenshots.record=true
 * On failure, actual and diff images are written to build/reports/screenshots/.
 */
class ComposeShellScreenshotTest {
    /** The shell as every native platform shows it, with a flat stand-in for the Vulkan view. */
    @Test
    fun shellWithNativeSurfaceSlot() = assertScreenshot("shell-native-slot") {
        App { _, _, _ -> Box(Modifier.fillMaxSize().background(NativeSurfaceStandIn)) }
    }

    /** The pure-Compose preview renderer (no native surface): Moon texture, stars, and controls. */
    @Test
    fun previewRenderer() = assertScreenshot("shell-preview") {
        App()
    }

    private fun assertScreenshot(name: String, content: @Composable () -> Unit) {
        val actual = render(content)
        val golden = File(goldenDirectory, "$name-$osKey.png")
        if (recording) {
            golden.parentFile.mkdirs()
            ImageIO.write(actual, "png", golden)
            println("Recorded screenshot golden: $golden")
            return
        }
        if (!golden.exists()) {
            fail("No golden for '$name' on $osKey ($golden). Record it with: $RECORD_COMMAND")
        }
        val expected = ImageIO.read(golden)
        val mismatch = compare(expected, actual)
        if (mismatch != null) {
            reportDirectory.mkdirs()
            ImageIO.write(actual, "png", File(reportDirectory, "$name-$osKey-actual.png"))
            diffImage(expected, actual)?.let { diff ->
                ImageIO.write(diff, "png", File(reportDirectory, "$name-$osKey-diff.png"))
            }
            fail("Screenshot '$name' differs from $golden: $mismatch. Actual and diff images are in $reportDirectory")
        }
    }

    private fun render(content: @Composable () -> Unit): BufferedImage {
        val scene = ImageComposeScene(WIDTH_PX, HEIGHT_PX, Density(DENSITY), content = content)
        try {
            // A few frames let layout, resources, and any first-frame effects settle.
            scene.render(0L)
            scene.render(500_000_000L)
            val image = scene.render(1_000_000_000L)
            val png = requireNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.bytes
            return ImageIO.read(ByteArrayInputStream(png))
        } finally {
            scene.close()
        }
    }

    /** Returns null when the images match within tolerance, else a description of the mismatch. */
    private fun compare(expected: BufferedImage, actual: BufferedImage): String? {
        if (expected.width != actual.width || expected.height != actual.height) {
            return "size ${actual.width}x${actual.height}, expected ${expected.width}x${expected.height}"
        }
        var differing = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (pixelDiffers(expected.getRGB(x, y), actual.getRGB(x, y))) differing++
            }
        }
        return if (differing > MAX_DIFFERING_PIXELS) {
            "$differing pixels differ (limit $MAX_DIFFERING_PIXELS)"
        } else {
            null
        }
    }

    /** Differing pixels in red over a faded copy of the expected image. */
    private fun diffImage(expected: BufferedImage, actual: BufferedImage): BufferedImage? {
        if (expected.width != actual.width || expected.height != actual.height) return null
        val diff = BufferedImage(expected.width, expected.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val e = expected.getRGB(x, y)
                val faded = (0xFF shl 24) or (fade(e shr 16) shl 16) or (fade(e shr 8) shl 8) or fade(e)
                diff.setRGB(x, y, if (pixelDiffers(e, actual.getRGB(x, y))) 0xFFFF0000.toInt() else faded)
            }
        }
        return diff
    }

    /** Lightens one 8-bit channel (the low byte of [channel]) towards white. */
    private fun fade(channel: Int): Int = (channel and 0xFF) / 4 + 192

    private fun pixelDiffers(a: Int, b: Int): Boolean = (0 until 32 step 8).any { shift ->
        abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) > CHANNEL_TOLERANCE
    }

    private companion object {
        // A phone-sized portrait viewport (412x892 dp at 2x).
        const val DENSITY = 2f
        const val WIDTH_PX = 824
        const val HEIGHT_PX = 1784

        // Tolerate anti-aliasing noise, not layout or colour changes.
        const val CHANNEL_TOLERANCE = 8

        // Headless Skia output is deterministic on one OS, so allow only a few stray pixels: a
        // single changed character (about 40 pixels at this density) must fail the test.
        const val MAX_DIFFERING_PIXELS = 16

        const val RECORD_COMMAND = "./gradlew :shared:jvmTest -Pscreenshots.record=true"

        val NativeSurfaceStandIn = Color(0xFF1E3A44)

        val recording = System.getProperty("screenshots.record") == "true"
        val goldenDirectory = File(System.getProperty("screenshots.dir") ?: "src/jvmTest/screenshots")
        val reportDirectory = File(System.getProperty("screenshots.reportDir") ?: "build/reports/screenshots")
        val osKey = System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("mac") -> "macos"
                it.startsWith("windows") -> "windows"
                else -> "linux"
            }
        }
    }
}
