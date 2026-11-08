// Android JNI bridge: turns a Surface and two Bitmaps into the platform-neutral renderer's
// inputs and runs it on the shared render thread. All Vulkan work lives in
// renderer/src/moon_renderer.cpp.
//
// Threading: nativeCreate runs on a Kotlin worker thread; every later call may come from the
// UI thread. After creation only moon::RenderThread touches Vulkan, and its public methods are
// thread-safe.
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <jni.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include "moon_log.h"
#include "moon_render_thread.h"
#include "moon_renderer.h"

#include <cstdint>
#include <memory>

namespace {

// Keeps a Bitmap's pixels locked for the lifetime of the object.
class LockedBitmap {
 public:
  LockedBitmap(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
    AndroidBitmapInfo info{};
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
      return;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
    image_ = {static_cast<const uint8_t*>(pixels), info.width, info.height, info.stride};
  }
  ~LockedBitmap() {
    if (image_.pixels) AndroidBitmap_unlockPixels(env_, bitmap_);
  }
  LockedBitmap(const LockedBitmap&) = delete;
  LockedBitmap& operator=(const LockedBitmap&) = delete;
  const moon::RgbaImage& image() const { return image_; }

 private:
  JNIEnv* env_;
  jobject bitmap_;
  moon::RgbaImage image_{};
};

struct WindowRelease {
  void operator()(ANativeWindow* window) const { ANativeWindow_release(window); }
};
using WindowPtr = std::unique_ptr<ANativeWindow, WindowRelease>;

// What a jlong handle points to. Members are destroyed in reverse order: the render thread
// (and with it the VkSurfaceKHR) goes before the window it wraps.
struct RendererHandle {
  WindowPtr window;
  std::unique_ptr<moon::RenderThread> render_thread;
};

RendererHandle* From(jlong handle) { return reinterpret_cast<RendererHandle*>(static_cast<intptr_t>(handle)); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_roywatson_lunar_vulkan_AndroidMoonRenderer_nativeCreate(
    JNIEnv* env, jclass, jobject surface, jobject albedo_bitmap, jobject elevation_bitmap) {
  WindowPtr window(ANativeWindow_fromSurface(env, surface));
  if (!window) {
    moon::LogError("ANativeWindow_fromSurface returned null");
    return 0;
  }
  LockedBitmap albedo(env, albedo_bitmap);
  LockedBitmap elevation(env, elevation_bitmap);
  if (!albedo.image().pixels || !elevation.image().pixels) {
    moon::LogError("lunar textures must be RGBA_8888 bitmaps");
    return 0;
  }
  ANativeWindow* native_window = window.get();
  moon::RendererConfig config;
  config.instance_extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
  config.create_surface = [native_window](VkInstance instance, VkSurfaceKHR* surface_out) {
    VkAndroidSurfaceCreateInfoKHR info{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    info.window = native_window;
    return vkCreateAndroidSurfaceKHR(instance, &info, nullptr, surface_out);
  };
  config.albedo = albedo.image();
  config.elevation = elevation.image();
  config.width = static_cast<uint32_t>(ANativeWindow_getWidth(native_window));
  config.height = static_cast<uint32_t>(ANativeWindow_getHeight(native_window));
  std::unique_ptr<moon::Renderer> renderer = moon::CreateRenderer(config);  // Uploads the pixels.
  if (!renderer) return 0;                                                  // CreateRenderer logged why.
  auto* handle = new RendererHandle{std::move(window), std::make_unique<moon::RenderThread>(std::move(renderer))};
  return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT void JNICALL Java_com_roywatson_lunar_vulkan_AndroidMoonRenderer_nativeResize(JNIEnv*, jclass,
                                                                                                   jlong handle,
                                                                                                   jint width,
                                                                                                   jint height) {
  if (auto* renderer = From(handle); renderer && width > 0 && height > 0) {
    renderer->render_thread->Resize(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_roywatson_lunar_vulkan_AndroidMoonRenderer_nativeUpdateView(
    JNIEnv*, jclass, jlong handle, jfloat longitude, jfloat latitude, jfloat distance, jfloat sun_azimuth,
    jfloat translation_x, jfloat translation_y, jint terrain_detail, jfloat relief) {
  if (auto* renderer = From(handle)) {
    renderer->render_thread->UpdateView({longitude, latitude, distance, sun_azimuth, translation_x, translation_y,
                                         static_cast<moon::TerrainDetail>(terrain_detail), relief});
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_roywatson_lunar_vulkan_AndroidMoonRenderer_nativeDestroy(JNIEnv*, jclass,
                                                                                                    jlong handle) {
  delete From(handle);  // Joins the render thread, releases Vulkan, then the window.
}
