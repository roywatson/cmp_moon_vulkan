package com.roywatson.lunar.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.roywatson.lunar.MoonView
import com.roywatson.lunar.RendererUnavailable
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.renderer_library_failed
import com.roywatson.lunar.resources.renderer_textures_failed
import com.roywatson.lunar.resources.renderer_vulkan_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsListener
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

private val hostOs: String = System.getProperty("os.name")
private val isMacHost = hostOs.startsWith("Mac")

/** True where a native desktop bridge exists; elsewhere the app uses the Compose preview. */
val desktopVulkanSupported: Boolean = isMacHost || hostOs.startsWith("Windows") || hostOs == "Linux"

/**
 * Compose's desktop boundary for the native renderer. An AWT [Canvas] hands its native
 * surface to the bridge via JAWT: a CAMetalLayer rendered through MoltenVK on macOS, or
 * the Canvas' HWND (Windows) or X11 window (Linux) rendered through the system Vulkan loader.
 * Only [MoonView] state crosses into native code.
 *
 * The canvas is a heavyweight AWT component, so Compose never sees mouse input over it; the
 * canvas reports drags through [onOrbit] and [onPan] itself.
 */
@Composable
fun DesktopVulkanSurface(
    view: MoonView,
    onOrbit: (dxPixels: Float, dyPixels: Float) -> Unit,
    onPan: (dxNdc: Float, dyNdc: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    DesktopMoonRenderer.loadError?.let { error ->
        RendererUnavailable(stringResource(Res.string.renderer_library_failed), modifier, details = error.message)
        return
    }
    var failure by remember { mutableStateOf<RendererFailure?>(null) }
    failure?.let { (message, details) ->
        RendererUnavailable(stringResource(message), modifier, details)
        return
    }
    val currentOnOrbit by rememberUpdatedState(onOrbit)
    val currentOnPan by rememberUpdatedState(onPan)
    val canvas = remember {
        VulkanCanvas(
            onOrbit = { dx, dy -> currentOnOrbit(dx, dy) },
            onPan = { dx, dy -> currentOnPan(dx, dy) },
            onFailure = { failure = it },
        )
    }
    SideEffect { canvas.updateView(view) }
    SwingPanel(modifier = modifier, factory = { canvas })
    DisposableEffect(canvas) {
        onDispose { canvas.destroyRenderer() }
    }
}

private class VulkanCanvas(
    private val onOrbit: (Float, Float) -> Unit,
    private val onPan: (Float, Float) -> Unit,
    private val onFailure: (RendererFailure) -> Unit,
) : Canvas() {
    private var handle = 0L
    private var failed = false
    private var view = MoonView()
    private var previousX = 0
    private var previousY = 0

    init {
        background = Color(0x05, 0x07, 0x0D)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (handle == 0L) createRendererIfReady() else nativeResizeToCanvas()
                }
            },
        )
        // AWT only repositions a heavyweight's layer when *its own* bounds change. When an
        // ancestor moves (window resize, Compose relayout) nudge it so the layer follows.
        addHierarchyBoundsListener(
            object : HierarchyBoundsListener {
                override fun ancestorMoved(e: HierarchyEvent) = refreshLayerPosition()

                override fun ancestorResized(e: HierarchyEvent) = refreshLayerPosition()
            },
        )
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                previousX = e.x
                previousY = e.y
            }

            override fun mouseDragged(e: MouseEvent) {
                val dx = (e.x - previousX).toFloat()
                val dy = (e.y - previousY).toFloat()
                previousX = e.x
                previousY = e.y
                // Right-drag or Shift-drag pans, matching the two-finger pan on touch screens.
                if ((SwingUtilities.isRightMouseButton(e) || e.isShiftDown) && width > 0 && height > 0) {
                    onPan(dx * 2f / width, -dy * 2f / height)
                } else {
                    onOrbit(dx, dy)
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    // Vulkan presents over the whole canvas; skip AWT's background clear to avoid flicker.
    override fun update(g: Graphics) = Unit

    override fun paint(g: Graphics) = Unit

    override fun addNotify() {
        super.addNotify()
        // Leave the AWT tree lock held by addNotify before touching JAWT.
        SwingUtilities.invokeLater(::createRendererIfReady)
    }

    override fun removeNotify() {
        // The native layer belongs to this peer; release it before the peer goes away.
        destroyRenderer()
        super.removeNotify()
    }

    fun updateView(newView: MoonView) {
        view = newView
        pushView()
    }

    fun destroyRenderer() {
        if (handle != 0L) {
            DesktopMoonRenderer.nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun createRendererIfReady() {
        if (handle != 0L || failed || !isDisplayable || width <= 0 || height <= 0) return
        // The renderer must be created during the window's initial layout: on macOS a Metal
        // layer attached later stays hidden until the window is resized. preloadDesktopTextures()
        // started decoding at launch, so this normally waits briefly, if at all.
        val textures = MoonTextures.preloaded.runCatching { get() }.getOrElse {
            fail(RendererFailure(Res.string.renderer_textures_failed, (it.cause ?: it).message))
            return
        }
        handle = DesktopMoonRenderer.nativeCreate(
            this,
            textures.albedo.pixels,
            textures.albedo.width,
            textures.albedo.height,
            textures.elevation.pixels,
            textures.elevation.width,
            textures.elevation.height,
            width,
            height,
            backingScale(),
        )
        if (handle == 0L) {
            fail(RendererFailure(Res.string.renderer_vulkan_failed, details = null))
            return
        }
        pushView()
        refreshLayerPosition()
    }

    private fun fail(reason: RendererFailure) {
        failed = true
        onFailure(reason)
    }

    private fun nativeResizeToCanvas() {
        if (handle != 0L && width > 0 && height > 0) {
            DesktopMoonRenderer.nativeResize(handle, width, height, backingScale())
        }
    }

    private fun pushView() {
        if (handle == 0L) return
        DesktopMoonRenderer.nativeUpdateView(
            handle,
            view.longitude,
            view.latitude,
            view.distance,
            view.sunAzimuth,
            view.translationX,
            view.translationY,
            view.terrainDetail.ordinal,
            view.relief,
        )
    }

    /**
     * macOS: forces AWT to send the canvas' window-relative bounds to its CALayer host.
     * Windows and Linux need no nudge: the canvas is a native child window AWT moves itself.
     */
    private fun refreshLayerPosition() {
        if (handle == 0L) return
        if (isMacHost) {
            val x = x
            val y = y
            setLocation(x + 1, y)
            setLocation(x, y)
        }
        nativeResizeToCanvas()
    }

    private fun backingScale(): Float = (graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat()
}

/** Why the renderer could not start: a localized message and any technical detail. */
private data class RendererFailure(val message: StringResource, val details: String?)

/** A decoded image as tightly packed RGBA8 bytes in a direct buffer the bridge can read. */
private class RgbaTexture(val pixels: ByteBuffer, val width: Int, val height: Int)

/**
 * Starts decoding the lunar maps on a background thread. Call it at launch, before the window
 * opens, so decoding overlaps start-up instead of delaying the renderer.
 */
fun preloadDesktopTextures() {
    MoonTextures.preloaded
}

private class MoonTextures(val albedo: RgbaTexture, val elevation: RgbaTexture) {
    companion object {
        private const val RESOURCE_ROOT = "composeResources/com.roywatson.lunar.resources/files/moon"
        private const val OPAQUE: Byte = -1 // 0xFF

        val preloaded: CompletableFuture<MoonTextures> by lazy { CompletableFuture.supplyAsync(::load) }

        private fun load() = MoonTextures(
            albedo = loadRgba("$RESOURCE_ROOT/lroc_color_4k.jpg"),
            elevation = loadRgba("$RESOURCE_ROOT/ldem_16_8bit.jpg"),
        )

        private fun loadRgba(path: String): RgbaTexture {
            val stream =
                requireNotNull(MoonTextures::class.java.classLoader.getResourceAsStream(path)) { "Missing $path" }
            val image: BufferedImage = stream.use { requireNotNull(ImageIO.read(it)) { "Unable to decode $path" } }
            val rgba = ByteBuffer.allocateDirect(image.width * image.height * 4)
            if (image.type == BufferedImage.TYPE_BYTE_GRAY) {
                // Read the stored samples: getRGB treats gray as linear and gamma-converts it to
                // sRGB (101 becomes 169), which would distort the elevation data.
                val gray = image.raster.getSamples(0, 0, image.width, image.height, 0, null as IntArray?)
                for (sample in gray) {
                    val value = sample.toByte()
                    rgba.put(value).put(value).put(value).put(OPAQUE)
                }
            } else {
                for (pixel in image.getRGB(0, 0, image.width, image.height, null, 0, image.width)) {
                    rgba.put((pixel shr 16).toByte())
                    rgba.put((pixel shr 8).toByte())
                    rgba.put(pixel.toByte())
                    rgba.put((pixel ushr 24).toByte())
                }
            }
            return RgbaTexture(rgba.flip(), image.width, image.height)
        }
    }
}

/** JNI contract (desktop_jni.cpp): lifecycle and view state are the only values Kotlin exposes. */
private object DesktopMoonRenderer {
    val loadError: Throwable? = runCatching {
        // Load the running JDK's JAWT first so the bridge binds to it, never another JDK's copy.
        System.loadLibrary("jawt")
        // Bundled as a Compose app resource, for both `./gradlew :desktopApp:run` and packaged apps.
        val resources = checkNotNull(System.getProperty("compose.application.resources.dir")) {
            "compose.application.resources.dir is not set; launch with ./gradlew :desktopApp:run"
        }
        System.load(File(resources, System.mapLibraryName("desktop_moon_renderer")).absolutePath)
    }.exceptionOrNull()

    @JvmStatic external fun nativeCreate(
        canvas: Canvas,
        albedo: ByteBuffer,
        albedoWidth: Int,
        albedoHeight: Int,
        elevation: ByteBuffer,
        elevationWidth: Int,
        elevationHeight: Int,
        width: Int,
        height: Int,
        scale: Float,
    ): Long

    @JvmStatic external fun nativeResize(handle: Long, width: Int, height: Int, scale: Float)

    @JvmStatic external fun nativeUpdateView(
        handle: Long,
        longitude: Float,
        latitude: Float,
        distance: Float,
        sunAzimuth: Float,
        translationX: Float,
        translationY: Float,
        terrainDetail: Int,
        relief: Float,
    )

    @JvmStatic external fun nativeDestroy(handle: Long)
}
