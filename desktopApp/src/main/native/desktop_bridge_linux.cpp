// Linux desktop bridge: gets the AWT Canvas' X11 window through JAWT and drives the shared
// Vulkan renderer (renderer/src/moon_renderer.cpp) through the system Vulkan loader
// (libvulkan.so.1 plus a Mesa or vendor driver; Mesa's lavapipe renders on the CPU in VMs).
//
// Threading and ownership rules this file follows (in addition to those in desktop_bridge.h):
//  * AWT's XToolkit owns the window and its Display connection. Xlib connections are not
//    thread-safe, so this bridge opens its *own* connection to the same X server and only
//    borrows the window ID (X window IDs are server-global). Only the Vulkan WSI uses it.
//  * Java on Wayland desktops runs through XWayland by default, so this also covers Wayland.
//  * Kotlin destroys the renderer in Canvas.removeNotify, before AWT destroys the window, so
//    the VkSurfaceKHR never outlives it; the private connection is closed after the surface.

#ifndef VK_USE_PLATFORM_XLIB_KHR
#define VK_USE_PLATFORM_XLIB_KHR
#endif
#include <X11/Xlib.h>

#include <jawt.h>
#include <jawt_md.h>
#include <vulkan/vulkan.h>

#include "desktop_bridge.h"
#include "moon_log.h"

#include <cstdlib>

namespace moon::desktop {
namespace {

Window CanvasWindow(JNIEnv* env, jobject canvas) {
  JAWT awt{};
  awt.version = JAWT_VERSION_9;
  if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
    LogError("JAWT_GetAWT failed");
    return 0;
  }
  JAWT_DrawingSurface* drawing_surface = awt.GetDrawingSurface(env, canvas);
  if (!drawing_surface) {
    LogError("JAWT GetDrawingSurface failed (canvas not displayable?)");
    return 0;
  }
  Window window = 0;
  if ((drawing_surface->Lock(drawing_surface) & JAWT_LOCK_ERROR) == 0) {
    JAWT_DrawingSurfaceInfo* info = drawing_surface->GetDrawingSurfaceInfo(drawing_surface);
    if (info) {
      if (auto* x11 = static_cast<JAWT_X11DrawingSurfaceInfo*>(info->platformInfo)) window = x11->drawable;
      drawing_surface->FreeDrawingSurfaceInfo(info);
    }
    drawing_surface->Unlock(drawing_surface);
  }
  awt.FreeDrawingSurface(drawing_surface);
  if (!window) LogError("JAWT returned no X11 window (is AWT using XToolkit?)");
  return window;
}

class LinuxMoonRenderer final : public PlatformRenderer {
 public:
  ~LinuxMoonRenderer() override {
    render_thread_.reset();  // Joins, then destroys the VkSurfaceKHR before its connection.
    if (display_) XCloseDisplay(display_);
  }

  bool Create(JNIEnv* env, jobject canvas, const RgbaImage& albedo, const RgbaImage& elevation, int width, int height,
              float scale) {
    const Window window = CanvasWindow(env, canvas);
    if (!window) return false;
    display_ = XOpenDisplay(nullptr);
    if (!display_) {
      LogError("XOpenDisplay failed (DISPLAY='%s')", std::getenv("DISPLAY") ? std::getenv("DISPLAY") : "");
      return false;
    }
    Display* display = display_;
    RendererConfig config;
    config.instance_extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_XLIB_SURFACE_EXTENSION_NAME};
    config.create_surface = [display, window](VkInstance instance, VkSurfaceKHR* surface_out) {
      VkXlibSurfaceCreateInfoKHR info{VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR};
      info.dpy = display;
      info.window = window;
      return vkCreateXlibSurfaceKHR(instance, &info, nullptr, surface_out);
    };
    config.albedo = albedo;
    config.elevation = elevation;
    // X11 surfaces report the window's size as currentExtent, so the swapchain follows the
    // real window; these are only the initial values.
    config.width = PixelSize(width, scale);
    config.height = PixelSize(height, scale);
    // Debug hook: MOON_MAX_TEXTURE_DIMENSION=4096 simulates a GPU at Vulkan's minimum limit.
    if (const char* cap = std::getenv("MOON_MAX_TEXTURE_DIMENSION"))
      config.max_texture_dimension = static_cast<uint32_t>(std::strtoul(cap, nullptr, 10));
    std::unique_ptr<Renderer> renderer = CreateRenderer(config);
    if (!renderer) {
      LogError("Vulkan renderer creation failed (no Vulkan driver? try mesa-vulkan-drivers)");
      return false;  // The destructor closes the connection.
    }
    // Creation ran on this (AWT) thread; from here on only the render thread touches Vulkan.
    render_thread_ = std::make_unique<RenderThread>(std::move(renderer));
    return true;
  }

  void Resize(int width, int height, float scale) override {
    if (width <= 0 || height <= 0 || !render_thread_) return;
    render_thread_->Resize(PixelSize(width, scale), PixelSize(height, scale));
  }

  void UpdateView(const ViewState& view) override {
    if (render_thread_) render_thread_->UpdateView(view);
  }

 private:
  Display* display_ = nullptr;
  std::unique_ptr<RenderThread> render_thread_;
};

}  // namespace

std::unique_ptr<PlatformRenderer> CreatePlatformRenderer(JNIEnv* env, jobject canvas, const RgbaImage& albedo,
                                                         const RgbaImage& elevation, int width, int height,
                                                         float scale) {
  auto renderer = std::make_unique<LinuxMoonRenderer>();
  if (!renderer->Create(env, canvas, albedo, elevation, width, height, scale)) return nullptr;
  return renderer;
}

}  // namespace moon::desktop
