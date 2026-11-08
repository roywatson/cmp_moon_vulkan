#ifndef MOON_IOS_H
#define MOON_IOS_H

// C API between Kotlin/Native (via cinterop) and the shared Vulkan renderer on iOS.
// Only a CAMetalLayer pointer, encoded images, and UI state cross this boundary.

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MoonIosRenderer MoonIosRenderer;

// `metal_layer` is a CAMetalLayer*, borrowed: the caller keeps it alive until destroy.
// Images are encoded (JPEG/PNG) bytes, decoded natively with ImageIO.
// Returns NULL if decoding or Vulkan setup fails; the reason is logged.
MoonIosRenderer* moon_ios_create(void* metal_layer, const uint8_t* albedo, int32_t albedo_length,
                                 const uint8_t* elevation, int32_t elevation_length, int32_t pixel_width,
                                 int32_t pixel_height);
void moon_ios_resize(MoonIosRenderer* renderer, int32_t pixel_width, int32_t pixel_height);
void moon_ios_update_view(MoonIosRenderer* renderer, float longitude, float latitude, float distance, float sun_azimuth,
                          float translation_x, float translation_y, int32_t terrain_detail, float relief);
void moon_ios_set_paused(MoonIosRenderer* renderer, int32_t paused);
void moon_ios_destroy(MoonIosRenderer* renderer);

#ifdef __cplusplus
}
#endif

#endif  // MOON_IOS_H
