// macOS desktop bridge: attaches a CAMetalLayer to an AWT Canvas through JAWT and drives
// the shared Vulkan renderer (renderer/src/moon_renderer.cpp) on MoltenVK.
//
// Threading rules this file follows (in addition to those in desktop_bridge.h):
//  * Attaching/detaching the layer to AWT's layer tree happens on the AppKit main thread,
//    queued with performSelectorOnMainThread in AWT's run-loop modes (the same mechanism
//    AWT uses itself, so it is ordered with AWT's own bounds updates and never blocks).

#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>

#include <jawt.h>
#include <jawt_md.h>
#define VK_USE_PLATFORM_METAL_EXT
#include <vulkan/vulkan.h>

#include "desktop_bridge.h"
#include "moon_log.h"

#include <cstdlib>

// Runs a block on the AppKit main thread in the modes AWT pumps, without waiting.
@interface MoonMainThreadTask : NSObject
+ (void)run:(dispatch_block_t)block;
@end

@implementation MoonMainThreadTask
+ (void)invoke:(dispatch_block_t)block {
  block();
}
+ (void)run:(dispatch_block_t)block {
  NSArray<NSString*>* modes =
      @[ NSDefaultRunLoopMode, NSModalPanelRunLoopMode, NSEventTrackingRunLoopMode, @"AWTRunLoopMode" ];
  [self performSelectorOnMainThread:@selector(invoke:) withObject:[block copy] waitUntilDone:NO modes:modes];
}
@end

namespace moon::desktop {
namespace {

class MacMoonRenderer final : public PlatformRenderer {
 public:
  ~MacMoonRenderer() override { Destroy(); }

  bool Create(JNIEnv* env, jobject canvas, const RgbaImage& albedo, const RgbaImage& elevation, int width, int height,
              float scale) {
    if (!AttachLayer(env, canvas, width, height, scale)) return false;
    CAMetalLayer* layer = layer_;
    RendererConfig config;
    config.instance_extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_EXT_METAL_SURFACE_EXTENSION_NAME};
    config.create_surface = [layer](VkInstance instance, VkSurfaceKHR* surface_out) {
      VkMetalSurfaceCreateInfoEXT info{VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT};
      info.pLayer = layer;
      return vkCreateMetalSurfaceEXT(instance, &info, nullptr, surface_out);
    };
    config.albedo = albedo;
    config.elevation = elevation;
    config.width = PixelSize(width, scale);
    config.height = PixelSize(height, scale);
    // Debug hook: MOON_MAX_TEXTURE_DIMENSION=4096 simulates a GPU at Vulkan's minimum limit.
    if (const char* cap = std::getenv("MOON_MAX_TEXTURE_DIMENSION"))
      config.max_texture_dimension = static_cast<uint32_t>(std::strtoul(cap, nullptr, 10));
    std::unique_ptr<Renderer> renderer = CreateRenderer(config);
    if (!renderer) {
      LogError("MoltenVK renderer creation failed");
      DetachLayer();
      return false;
    }
    // Creation ran on this (AWT) thread; from here on only the render thread touches Vulkan.
    render_thread_ = std::make_unique<RenderThread>(std::move(renderer));
    return true;
  }

  void Resize(int width, int height, float scale) override {
    if (width <= 0 || height <= 0 || !render_thread_) return;
    render_thread_->Resize(PixelSize(width, scale), PixelSize(height, scale));
    // AWT owns the layer's frame (it repositions it on every Canvas reshape); we only keep
    // the backing scale in sync so MoltenVK reports a full-resolution drawable.
    CAMetalLayer* layer = layer_;
    if (layer && layer.contentsScale != scale) {
      [MoonMainThreadTask run:^{
        layer.contentsScale = scale;
      }];
    }
  }

  void UpdateView(const ViewState& view) override {
    if (render_thread_) render_thread_->UpdateView(view);
  }

 private:
  void Destroy() {
    render_thread_.reset();  // Joins, then destroys the VkSurfaceKHR before the layer goes.
    DetachLayer();
  }

  bool AttachLayer(JNIEnv* env, jobject canvas, int width, int height, float scale) {
    JAWT awt{};
    awt.version = JAWT_VERSION_9;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
      LogError("JAWT_GetAWT failed");
      return false;
    }
    JAWT_DrawingSurface* drawing_surface = awt.GetDrawingSurface(env, canvas);
    if (!drawing_surface) {
      LogError("JAWT GetDrawingSurface failed (canvas not displayable?)");
      return false;
    }
    id<JAWT_SurfaceLayers> surface_layers = nil;
    if ((drawing_surface->Lock(drawing_surface) & JAWT_LOCK_ERROR) == 0) {
      JAWT_DrawingSurfaceInfo* info = drawing_surface->GetDrawingSurfaceInfo(drawing_surface);
      if (info) {
        surface_layers = (__bridge id<JAWT_SurfaceLayers>)info->platformInfo;
        drawing_surface->FreeDrawingSurfaceInfo(info);
      }
      drawing_surface->Unlock(drawing_surface);
    }
    awt.FreeDrawingSurface(drawing_surface);
    if (!surface_layers) {
      LogError("JAWT returned no surface layers");
      return false;
    }

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (!device) return false;
    CAMetalLayer* layer = [CAMetalLayer layer];
    layer.device = device;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.opaque = YES;
    layer.contentsScale = scale;
    // A provisional size so MoltenVK can build the first swapchain; AWT replaces the
    // frame with the canvas' real window position on the next reshape.
    layer.frame = CGRectMake(0, 0, width, height);
    layer_ = layer;
    surface_layers_ = surface_layers;
    [MoonMainThreadTask run:^{
      surface_layers.layer = layer;
    }];
    return true;
  }

  void DetachLayer() {
    id<JAWT_SurfaceLayers> surface_layers = surface_layers_;
    CAMetalLayer* layer = layer_;
    surface_layers_ = nil;
    layer_ = nil;
    if (surface_layers) {
      [MoonMainThreadTask run:^{
        if (surface_layers.layer == layer) surface_layers.layer = nil;
      }];
    }
  }

  CAMetalLayer* layer_ = nil;
  id<JAWT_SurfaceLayers> surface_layers_ = nil;
  std::unique_ptr<RenderThread> render_thread_;
};

}  // namespace

std::unique_ptr<PlatformRenderer> CreatePlatformRenderer(JNIEnv* env, jobject canvas, const RgbaImage& albedo,
                                                         const RgbaImage& elevation, int width, int height,
                                                         float scale) {
  auto renderer = std::make_unique<MacMoonRenderer>();
  if (!renderer->Create(env, canvas, albedo, elevation, width, height, scale)) return nullptr;
  return renderer;
}

}  // namespace moon::desktop
