// iOS bridge: wraps a Kotlin-owned CAMetalLayer in a VkSurfaceKHR and runs the shared
// renderer (renderer/src) on the shared render thread (renderer/thread).
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <QuartzCore/CAMetalLayer.h>

#define VK_USE_PLATFORM_METAL_EXT
#include <vulkan/vulkan.h>

#include "moon_ios.h"
#include "moon_log.h"
#include "moon_render_thread.h"
#include "moon_renderer.h"

#include <memory>
#include <vector>

struct MoonIosRenderer {
  std::unique_ptr<moon::RenderThread> render_thread;
};

namespace {

// Decodes encoded image bytes into tightly packed RGBA8 (grayscale expands to R=G=B).
bool DecodeRgba(const uint8_t* bytes, int32_t length, std::vector<uint8_t>& pixels, moon::RgbaImage& image) {
  if (!bytes || length <= 0) return false;
  NSData* data = [NSData dataWithBytesNoCopy:const_cast<uint8_t*>(bytes)
                                      length:static_cast<NSUInteger>(length)
                                freeWhenDone:NO];
  CGImageSourceRef source = CGImageSourceCreateWithData((__bridge CFDataRef)data, nullptr);
  if (!source) return false;
  CGImageRef decoded = CGImageSourceCreateImageAtIndex(source, 0, nullptr);
  CFRelease(source);
  if (!decoded) return false;
  const size_t width = CGImageGetWidth(decoded), height = CGImageGetHeight(decoded);
  pixels.assign(width * height * 4, 0);
  CGColorSpaceRef color_space = CGColorSpaceCreateDeviceRGB();
  CGContextRef context = CGBitmapContextCreate(
      pixels.data(), width, height, 8, width * 4, color_space,
      static_cast<uint32_t>(kCGImageAlphaNoneSkipLast) | static_cast<uint32_t>(kCGBitmapByteOrder32Big));
  CGColorSpaceRelease(color_space);
  if (context) {
    CGContextDrawImage(context, CGRectMake(0, 0, width, height), decoded);
    CGContextRelease(context);
  }
  CGImageRelease(decoded);
  if (!context) return false;
  image = {pixels.data(), static_cast<uint32_t>(width), static_cast<uint32_t>(height),
           static_cast<uint32_t>(width * 4)};
  return true;
}

}  // namespace

extern "C" MoonIosRenderer* moon_ios_create(void* metal_layer, const uint8_t* albedo, int32_t albedo_length,
                                            const uint8_t* elevation, int32_t elevation_length, int32_t pixel_width,
                                            int32_t pixel_height) {
  CAMetalLayer* layer = (__bridge CAMetalLayer*)metal_layer;
  if (!layer || pixel_width <= 0 || pixel_height <= 0) return nullptr;
  std::vector<uint8_t> albedo_pixels, elevation_pixels;
  moon::RendererConfig config;
  if (!DecodeRgba(albedo, albedo_length, albedo_pixels, config.albedo) ||
      !DecodeRgba(elevation, elevation_length, elevation_pixels, config.elevation)) {
    moon::LogError("unable to decode lunar textures");
    return nullptr;
  }
  config.instance_extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_EXT_METAL_SURFACE_EXTENSION_NAME};
  config.create_surface = [layer](VkInstance instance, VkSurfaceKHR* surface_out) {
    VkMetalSurfaceCreateInfoEXT info{VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT};
    info.pLayer = layer;
    return vkCreateMetalSurfaceEXT(instance, &info, nullptr, surface_out);
  };
  config.width = static_cast<uint32_t>(pixel_width);
  config.height = static_cast<uint32_t>(pixel_height);
  std::unique_ptr<moon::Renderer> renderer = moon::CreateRenderer(config);  // Copies the pixels to the GPU.
  if (!renderer) {
    moon::LogError("MoltenVK renderer creation failed");
    return nullptr;
  }
  auto* handle = new MoonIosRenderer;
  handle->render_thread = std::make_unique<moon::RenderThread>(std::move(renderer));
  return handle;
}

extern "C" void moon_ios_resize(MoonIosRenderer* renderer, int32_t pixel_width, int32_t pixel_height) {
  if (renderer && pixel_width > 0 && pixel_height > 0) {
    renderer->render_thread->Resize(static_cast<uint32_t>(pixel_width), static_cast<uint32_t>(pixel_height));
  }
}

extern "C" void moon_ios_update_view(MoonIosRenderer* renderer, float longitude, float latitude, float distance,
                                     float sun_azimuth, float translation_x, float translation_y,
                                     int32_t terrain_detail, float relief) {
  if (renderer) {
    renderer->render_thread->UpdateView({longitude, latitude, distance, sun_azimuth, translation_x, translation_y,
                                         static_cast<moon::TerrainDetail>(terrain_detail), relief});
  }
}

extern "C" void moon_ios_set_paused(MoonIosRenderer* renderer, int32_t paused) {
  if (renderer) renderer->render_thread->SetPaused(paused != 0);
}

extern "C" void moon_ios_destroy(MoonIosRenderer* renderer) {
  delete renderer;  // Joins the render thread and releases Vulkan before returning.
}
