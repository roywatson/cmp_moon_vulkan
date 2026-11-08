// Windows desktop bridge: gets the AWT Canvas' HWND through JAWT and drives the shared
// Vulkan renderer (renderer/src/moon_renderer.cpp) through the system Vulkan loader
// (vulkan-1.dll, installed with any Vulkan-capable GPU driver).
//
// Threading and ownership rules this file follows (in addition to those in desktop_bridge.h):
//  * The HWND belongs to AWT: its toolkit thread pumps the window's messages and moves and
//    sizes it with the Canvas. This file never subclasses it; Vulkan only presents into it.
//  * Kotlin destroys the renderer in Canvas.removeNotify, before AWT destroys the HWND, so
//    the VkSurfaceKHR never outlives its window.
//  * vulkan-1.dll is delay-loaded, so this DLL still loads on machines without Vulkan (for
//    example most VMs). Creation probes for the loader first and fails cleanly instead.

#ifndef VK_USE_PLATFORM_WIN32_KHR
#define VK_USE_PLATFORM_WIN32_KHR
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <jawt.h>
#include <jawt_md.h>
#include <vulkan/vulkan.h>

#include "desktop_bridge.h"
#include "moon_log.h"

#include <cstdlib>

namespace moon::desktop {
namespace {

// Must run before the first vk* call: with delay loading, a missing vulkan-1.dll would
// otherwise surface as a structured exception inside vkCreateInstance and crash the JVM.
bool VulkanLoaderAvailable() {
  static const bool available = LoadLibraryW(L"vulkan-1.dll") != nullptr;
  return available;
}

HWND CanvasWindow(JNIEnv* env, jobject canvas) {
  JAWT awt{};
  awt.version = JAWT_VERSION_9;
  if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
    LogError("JAWT_GetAWT failed");
    return nullptr;
  }
  JAWT_DrawingSurface* drawing_surface = awt.GetDrawingSurface(env, canvas);
  if (!drawing_surface) {
    LogError("JAWT GetDrawingSurface failed (canvas not displayable?)");
    return nullptr;
  }
  HWND window = nullptr;
  if ((drawing_surface->Lock(drawing_surface) & JAWT_LOCK_ERROR) == 0) {
    JAWT_DrawingSurfaceInfo* info = drawing_surface->GetDrawingSurfaceInfo(drawing_surface);
    if (info) {
      if (auto* win32 = static_cast<JAWT_Win32DrawingSurfaceInfo*>(info->platformInfo)) window = win32->hwnd;
      drawing_surface->FreeDrawingSurfaceInfo(info);
    }
    drawing_surface->Unlock(drawing_surface);
  }
  awt.FreeDrawingSurface(drawing_surface);
  if (!window) LogError("JAWT returned no HWND for the canvas");
  return window;
}

class WindowsMoonRenderer final : public PlatformRenderer {
 public:
  // Joins the render thread, which destroys the renderer and its VkSurfaceKHR.
  ~WindowsMoonRenderer() override = default;

  bool Create(JNIEnv* env, jobject canvas, const RgbaImage& albedo, const RgbaImage& elevation, int width, int height,
              float scale) {
    if (!VulkanLoaderAvailable()) {
      LogError("vulkan-1.dll not found: this machine has no Vulkan driver installed");
      return false;
    }
    HWND window = CanvasWindow(env, canvas);
    if (!window) return false;
    RendererConfig config;
    config.instance_extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME};
    config.create_surface = [window](VkInstance instance, VkSurfaceKHR* surface_out) {
      VkWin32SurfaceCreateInfoKHR info{VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR};
      info.hinstance = GetModuleHandleW(nullptr);
      info.hwnd = window;
      return vkCreateWin32SurfaceKHR(instance, &info, nullptr, surface_out);
    };
    config.albedo = albedo;
    config.elevation = elevation;
    // Win32 surfaces report the HWND's client size as currentExtent, so the swapchain
    // follows the real window; these are only the initial values.
    config.width = PixelSize(width, scale);
    config.height = PixelSize(height, scale);
    // Debug hook: MOON_MAX_TEXTURE_DIMENSION=4096 simulates a GPU at Vulkan's minimum limit.
    if (const char* cap = std::getenv("MOON_MAX_TEXTURE_DIMENSION"))
      config.max_texture_dimension = static_cast<uint32_t>(std::strtoul(cap, nullptr, 10));
    std::unique_ptr<Renderer> renderer = CreateRenderer(config);
    if (!renderer) {
      LogError("Vulkan renderer creation failed (no Vulkan-capable GPU driver?)");
      return false;
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
  std::unique_ptr<RenderThread> render_thread_;
};

}  // namespace

std::unique_ptr<PlatformRenderer> CreatePlatformRenderer(JNIEnv* env, jobject canvas, const RgbaImage& albedo,
                                                         const RgbaImage& elevation, int width, int height,
                                                         float scale) {
  auto renderer = std::make_unique<WindowsMoonRenderer>();
  if (!renderer->Create(env, canvas, albedo, elevation, width, height, scale)) return nullptr;
  return renderer;
}

}  // namespace moon::desktop
