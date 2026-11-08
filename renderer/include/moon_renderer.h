#pragma once

// Platform-neutral Vulkan Moon renderer shared by Android and desktop (MoltenVK).
// Platform bridges own the native window and image decoding; they hand this core a
// VkSurfaceKHR factory and tightly described RGBA pixels. No platform types cross here.

#include <vulkan/vulkan.h>

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

namespace moon {

struct RgbaImage {
  const uint8_t* pixels = nullptr;
  uint32_t width = 0;
  uint32_t height = 0;
  uint32_t stride = 0;  // Bytes per row.
};

struct RendererConfig {
  std::vector<const char*> instance_extensions;
  std::function<VkResult(VkInstance, VkSurfaceKHR*)> create_surface;
  RgbaImage albedo;
  RgbaImage elevation;
  uint32_t width = 1;
  uint32_t height = 1;
  // Optional cap below the GPU's maxImageDimension2D (0 = device limit). Textures larger
  // than the effective limit are box-downsampled before upload.
  uint32_t max_texture_dimension = 0;
};

// Sphere tessellation level. Values cross from Kotlin (TerrainDetail.ordinal); keep the order.
enum class TerrainDetail : uint32_t { kLow = 0, kBalanced = 1, kHigh = 2 };

// Camera, lighting, and terrain settings for a frame; mirrors Kotlin's MoonView.
struct ViewState {
  float longitude = -23.0f;    // Degrees turned east; unbounded.
  float latitude = 8.0f;       // Degrees tilted north.
  float distance = 1.0f;       // Framing multiplier (1 = default size).
  float sun_azimuth = -54.0f;  // Degrees.
  float translation_x = 0.0f;  // Pan in normalized device coordinates (y up).
  float translation_y = 0.0f;
  TerrainDetail terrain_detail = TerrainDetail::kHigh;
  float relief = 1.0f;  // Elevation shading strength.
};

class Renderer {
 public:
  virtual ~Renderer() = default;
  virtual void Resize(uint32_t width, uint32_t height) = 0;
  // Out-of-range values are clamped: callers on the far side of a C ABI are not trusted.
  virtual void UpdateView(const ViewState& view) = 0;
  // Returns true when a frame was presented; false when it was skipped (e.g. a 0x0 window).
  virtual bool Render() = 0;
  // True while work is pending that only another frame can finish, such as rebuilding the swapchain
  // after the platform reported it out of date or suboptimal.
  virtual bool NeedsFrame() const = 0;
};

// Returns nullptr when Vulkan, the surface, or any GPU resource cannot be created.
std::unique_ptr<Renderer> CreateRenderer(const RendererConfig& config);

}  // namespace moon
