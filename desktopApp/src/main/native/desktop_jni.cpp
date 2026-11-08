// JNI exports for com.roywatson.lunar.desktop.DesktopMoonRenderer. Platform-neutral:
// the per-OS surface work lives behind moon::desktop::CreatePlatformRenderer.

#include "desktop_bridge.h"

namespace {

using moon::desktop::PlatformRenderer;

moon::RgbaImage ImageFrom(JNIEnv* env, jobject buffer, jint width, jint height) {
  auto* pixels = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (!pixels || width <= 0 || height <= 0 || capacity < static_cast<jlong>(width) * height * 4) return {};
  return {pixels, static_cast<uint32_t>(width), static_cast<uint32_t>(height), static_cast<uint32_t>(width) * 4};
}

PlatformRenderer* From(jlong handle) { return reinterpret_cast<PlatformRenderer*>(static_cast<intptr_t>(handle)); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_roywatson_lunar_desktop_DesktopMoonRenderer_nativeCreate(
    JNIEnv* env, jclass, jobject canvas, jobject albedo, jint albedo_width, jint albedo_height, jobject elevation,
    jint elevation_width, jint elevation_height, jint width, jint height, jfloat scale) {
  const moon::RgbaImage albedo_image = ImageFrom(env, albedo, albedo_width, albedo_height);
  const moon::RgbaImage elevation_image = ImageFrom(env, elevation, elevation_width, elevation_height);
  if (!albedo_image.pixels || !elevation_image.pixels) return 0;
  std::unique_ptr<PlatformRenderer> renderer =
      moon::desktop::CreatePlatformRenderer(env, canvas, albedo_image, elevation_image, width, height, scale);
  return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer.release()));
}

extern "C" JNIEXPORT void JNICALL Java_com_roywatson_lunar_desktop_DesktopMoonRenderer_nativeResize(
    JNIEnv*, jclass, jlong handle, jint width, jint height, jfloat scale) {
  if (auto* renderer = From(handle)) renderer->Resize(width, height, scale);
}

extern "C" JNIEXPORT void JNICALL Java_com_roywatson_lunar_desktop_DesktopMoonRenderer_nativeUpdateView(
    JNIEnv*, jclass, jlong handle, jfloat longitude, jfloat latitude, jfloat distance, jfloat sun_azimuth,
    jfloat translation_x, jfloat translation_y, jint terrain_detail, jfloat relief) {
  if (auto* renderer = From(handle)) {
    renderer->UpdateView({longitude, latitude, distance, sun_azimuth, translation_x, translation_y,
                          static_cast<moon::TerrainDetail>(terrain_detail), relief});
  }
}

extern "C" JNIEXPORT void JNICALL Java_com_roywatson_lunar_desktop_DesktopMoonRenderer_nativeDestroy(JNIEnv*, jclass,
                                                                                                     jlong handle) {
  delete From(handle);
}
