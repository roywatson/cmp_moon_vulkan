package com.roywatson.lunar.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import cnames.structs.MoonIosRenderer
import com.roywatson.lunar.MoonView
import com.roywatson.lunar.RendererUnavailable
import com.roywatson.lunar.native.moon_ios_create
import com.roywatson.lunar.native.moon_ios_destroy
import com.roywatson.lunar.native.moon_ios_resize
import com.roywatson.lunar.native.moon_ios_set_paused
import com.roywatson.lunar.native.moon_ios_update_view
import com.roywatson.lunar.resources.Res
import com.roywatson.lunar.resources.renderer_vulkan_failed
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.posix.QOS_CLASS_USER_INITIATED
import kotlin.math.roundToInt

/**
 * Compose's iOS boundary for the shared native renderer. A UIKit view contributes a
 * CAMetalLayer; MoltenVK renders the shared C++ Vulkan renderer into it. The interop view is
 * non-interactive, so touches reach the shared gesture handling in App, and only [MoonView]
 * state crosses into native code.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
@Composable
fun IosVulkanSurface(view: MoonView, modifier: Modifier = Modifier) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        RendererUnavailable(stringResource(Res.string.renderer_vulkan_failed), modifier)
        return
    }
    val textures by produceState<MoonTextures?>(initialValue = null) {
        value = MoonTextures(
            albedo = Res.readBytes("files/moon/lroc_color_4k.jpg"),
            elevation = Res.readBytes("files/moon/ldem_16_8bit.jpg"),
        )
    }
    val holder = remember { MetalViewHolder() }
    SideEffect { holder.view?.updateView(view) }

    val loaded = textures ?: return
    UIKitView(
        factory = {
            MoonMetalView(loaded, onFailure = { failed = true }).also {
                it.updateView(view)
                holder.view = it
            }
        },
        modifier = modifier,
        onRelease = {
            it.destroy()
            holder.view = null
        },
        // null = non-interactive: touches stay with Compose.
        properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
    )
}

private class MetalViewHolder {
    var view: MoonMetalView? = null
}

private class MoonTextures(val albedo: ByteArray, val elevation: ByteArray)

@OptIn(ExperimentalForeignApi::class)
private class MoonMetalView(private val textures: MoonTextures, private val onFailure: () -> Unit) :
    UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val metalLayer = CAMetalLayer()
    private var renderer: CPointer<MoonIosRenderer>? = null
    private var creating = false
    private var failed = false
    private var destroyed = false
    private var paused = false
    private var view = MoonView()
    private var pixelSize: Pair<Int, Int>? = null
    private val observers: List<NSObjectProtocol>

    init {
        backgroundColor = UIColor.blackColor
        // MoltenVK assigns the layer's MTLDevice when it creates the swapchain.
        metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm
        metalLayer.opaque = true
        layer.addSublayer(metalLayer)
        // iOS forbids GPU work in the background; park the render thread meanwhile.
        val center = NSNotificationCenter.defaultCenter
        observers = listOf(
            center.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ -> setPaused(true) },
            center.addObserverForName(
                UIApplicationWillEnterForegroundNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ -> setPaused(false) },
        )
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val scale = window?.screen?.scale ?: UIScreen.mainScreen.scale
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.contentsScale = scale
        metalLayer.frame = bounds
        CATransaction.commit()
        val (width, height) = bounds.useContents {
            (size.width * scale).roundToInt() to
                (size.height * scale).roundToInt()
        }
        if (width <= 0 || height <= 0) return
        pixelSize = width to height
        val current = renderer
        if (current != null) moon_ios_resize(current, width, height) else create()
    }

    fun updateView(newView: MoonView) {
        view = newView
        val current = renderer ?: return
        moon_ios_update_view(
            current,
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

    fun destroy() {
        destroyed = true
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        renderer?.let(::moon_ios_destroy)
        renderer = null
    }

    private fun setPaused(paused: Boolean) {
        this.paused = paused
        renderer?.let { moon_ios_set_paused(it, if (paused) 1 else 0) }
    }

    /**
     * Creates the renderer on a background queue: decoding the 4K maps and uploading them to the GPU
     * takes long enough to stall the UI. The result is adopted on the main queue, where every other
     * use of [renderer] happens.
     */
    private fun create() {
        val (width, height) = pixelSize ?: return
        if (creating || failed) return
        creating = true
        val layer = metalLayer
        val textures = textures
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED.toLong(), 0u)) {
            val created = createRenderer(layer, textures, width, height)
            dispatch_async(dispatch_get_main_queue()) { adopt(created) }
        }
    }

    private fun adopt(created: CPointer<MoonIosRenderer>?) {
        creating = false
        when {
            destroyed -> created?.let(::moon_ios_destroy)
            created == null -> {
                failed = true
                onFailure()
            }
            else -> {
                renderer = created
                // Catch up on anything that changed while the renderer was being created.
                updateView(view)
                pixelSize?.let { (width, height) -> moon_ios_resize(created, width, height) }
                if (paused) moon_ios_set_paused(created, 1)
            }
        }
    }
}

/** Runs off the main thread: touches only its arguments, never UIKit. */
@OptIn(ExperimentalForeignApi::class)
private fun createRenderer(layer: CAMetalLayer, textures: MoonTextures, width: Int, height: Int) =
    textures.albedo.usePinned { albedo ->
        textures.elevation.usePinned { elevation ->
            moon_ios_create(
                interpretCPointer<CPointed>(layer.objcPtr()),
                albedo.addressOf(0).reinterpret(),
                textures.albedo.size,
                elevation.addressOf(0).reinterpret(),
                textures.elevation.size,
                width,
                height,
            )
        }
    }
