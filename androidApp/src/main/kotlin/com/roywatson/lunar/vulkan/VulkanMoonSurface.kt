package com.roywatson.lunar.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.roywatson.lunar.MoonView
import com.roywatson.lunar.RendererUnavailable
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.renderer_vulkan_failed
import org.jetbrains.compose.resources.stringResource
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Compose's Android boundary for the native renderer. The C++ code receives only an
 * ANativeWindow; Vulkan handles deliberately do not cross into the shared UI layer. Touch is
 * handled by the shared gestures in App: the SurfaceView does not consume input.
 */
@Composable
fun VulkanMoonSurface(view: MoonView, modifier: Modifier = Modifier) {
    var isAvailable by remember { mutableStateOf(true) }
    if (!isAvailable) {
        RendererUnavailable(stringResource(Res.string.renderer_vulkan_failed), modifier)
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { context -> VulkanSurfaceView(context) { isAvailable = false } },
        update = { surface -> surface.updateView(view) },
    )
}

/**
 * Owns one native renderer per surface lifetime. The renderer draws on its own native thread,
 * so this view only creates it, forwards size and view changes, and destroys it.
 *
 * Creation (decoding the maps and uploading them to the GPU) takes long enough to drop
 * frames, so it runs on [worker]. Fields written by both threads are volatile; the worker
 * publishes [handle] before reading [view] and [size], so whichever thread writes last also
 * delivers its value to the renderer.
 */
private class VulkanSurfaceView(context: Context, private val onUnavailable: () -> Unit) :
    SurfaceView(context),
    SurfaceHolder.Callback {
    // One per attachment: Compose can detach this view and attach it again, and a shut-down
    // executor cannot be restarted.
    private var worker: ExecutorService? = null
    private var creation: Future<*>? = null

    @Volatile private var handle = 0L

    @Volatile private var view = MoonView()

    @Volatile private var size: Pair<Int, Int>? = null

    init {
        holder.addCallback(this)
        // The Compose box around this view describes the globe and offers its actions.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateView(newView: MoonView) {
        view = newView
        handle.takeIf { it != 0L }?.let { AndroidMoonRenderer.updateView(it, newView) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        creation = checkNotNull(worker) { "Surface created while detached" }.submit { create(surface) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        size = width to height
        handle.takeIf { it != 0L }?.let { AndroidMoonRenderer.resize(it, width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The surface must not be used after this returns, so finish any creation in flight first.
        creation?.get()
        creation = null
        handle.takeIf { it != 0L }?.let(AndroidMoonRenderer::destroy)
        handle = 0L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker = Executors.newSingleThreadExecutor()
    }

    override fun onDetachedFromWindow() {
        worker?.shutdown()
        worker = null
        super.onDetachedFromWindow()
    }

    /** Runs on [worker]. */
    private fun create(surface: Surface) {
        val created = try {
            val albedo = context.loadLunarMap("lroc_color_4k.jpg")
            val elevation = context.loadLunarMap("ldem_16_8bit.jpg")
            AndroidMoonRenderer.create(surface, albedo, elevation).also {
                // The renderer copied the pixels to the GPU; free the CPU copies now, not at the next GC.
                albedo.recycle()
                elevation.recycle()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Unable to load the lunar maps", e)
            0L
        } catch (e: OutOfMemoryError) {
            // The two maps need about 100 MB as bitmaps; a low-memory device can fail here.
            Log.e(TAG, "Not enough memory to decode the lunar maps", e)
            0L
        }
        if (created == 0L) {
            post { onUnavailable() }
            return
        }
        handle = created
        AndroidMoonRenderer.updateView(created, view)
        size?.let { (width, height) -> AndroidMoonRenderer.resize(created, width, height) }
    }
}

private const val TAG = "moon"

/**
 * Decodes one of the lunar maps from the shared module's Compose resources, which are packaged
 * as APK assets. ARGB_8888 is RGBA byte order in memory, which is what the renderer expects.
 */
private fun Context.loadLunarMap(fileName: String): Bitmap =
    assets.open("composeResources/com.roywatson.lunar.resources/files/moon/$fileName").use { input ->
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeStream(input, null, options) ?: throw IOException("Unable to decode $fileName")
    }

/** JNI contract (moon_renderer_jni.cpp): lifecycle and view state are the only values Kotlin exposes. */
private object AndroidMoonRenderer {
    init {
        System.loadLibrary("moon_renderer")
    }

    fun create(surface: Surface, albedo: Bitmap, elevation: Bitmap): Long = nativeCreate(surface, albedo, elevation)

    fun resize(handle: Long, width: Int, height: Int) = nativeResize(handle, width, height)

    fun updateView(handle: Long, view: MoonView) = nativeUpdateView(
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

    fun destroy(handle: Long) = nativeDestroy(handle)

    @JvmStatic private external fun nativeCreate(surface: Surface, albedo: Bitmap, elevation: Bitmap): Long

    @JvmStatic private external fun nativeResize(handle: Long, width: Int, height: Int)

    @JvmStatic private external fun nativeUpdateView(
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

    @JvmStatic private external fun nativeDestroy(handle: Long)
}
