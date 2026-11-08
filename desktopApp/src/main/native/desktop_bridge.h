#pragma once

// Contract between the platform-neutral JNI exports (desktop_jni.cpp) and the per-OS
// bridges: desktop_bridge_macos.mm (JAWT CAMetalLayer + MoltenVK), desktop_bridge_win32.cpp
// (JAWT HWND + the system Vulkan loader), and desktop_bridge_linux.cpp (JAWT X11 window).
//
// Each bridge follows the same threading rules: JAWT is only touched on the AWT event
// thread inside CreatePlatformRenderer, and after creation every Vulkan call happens on
// moon::RenderThread. Kotlin-side updates only write the render thread's guarded snapshot.

#include <jni.h>

#include "moon_render_thread.h"
#include "moon_renderer.h"

#include <algorithm>
#include <cstdint>
#include <memory>

namespace moon::desktop {

class PlatformRenderer {
 public:
  virtual ~PlatformRenderer() = default;  // Stops rendering before the native surface goes.
  // Width and height are AWT (logical) units; scale converts them to device pixels.
  virtual void Resize(int width, int height, float scale) = 0;
  virtual void UpdateView(const ViewState& view) = 0;
};

// Called on the AWT event thread with a displayable Canvas. Returns nullptr (after logging
// the reason to stderr) when the surface or the Vulkan renderer cannot be created.
std::unique_ptr<PlatformRenderer> CreatePlatformRenderer(JNIEnv* env, jobject canvas, const RgbaImage& albedo,
                                                         const RgbaImage& elevation, int width, int height,
                                                         float scale);

inline uint32_t PixelSize(int units, float scale) {
  return static_cast<uint32_t>(std::max(1.0f, units * scale + 0.5f));
}

}  // namespace moon::desktop
