#include "moon_renderer.h"

#include "moon_frag.spv.h"
#include "moon_log.h"
#include "moon_vert.spv.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <numbers>
#include <optional>
#include <string_view>
#include <vector>

namespace moon {
namespace {

constexpr float kRadiansPerDegree = std::numbers::pi_v<float> / 180.0f;
constexpr uint32_t kNoMemoryType = UINT32_MAX;
// MoltenVK is a portability implementation: the spec requires enabling this extension whenever
// the device advertises it. (Its *_EXTENSION_NAME macro is only defined for beta headers.)
constexpr std::string_view kPortabilitySubset = "VK_KHR_portability_subset";
// Textures are read by both shader stages: elevation displaces vertices and shades fragments.
constexpr VkPipelineStageFlags kTextureReadStages =
    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

// The vertex shader's push-constant block (moon.vert: MoonView).
struct PushConstants {
  float projection[16];  // Column-major.
  float model[16];       // Column-major.
  float sun[4];          // xyz: direction towards the sun; w: relief strength.
};
static_assert(sizeof(PushConstants) == 36 * sizeof(float));

struct Vertex {
  float position[3];
  float normal[3];
  float uv[2];
};

struct Buffer {
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;

  void Destroy(VkDevice device) {
    if (buffer) vkDestroyBuffer(device, buffer, nullptr);
    if (memory) vkFreeMemory(device, memory, nullptr);
    *this = {};
  }
};

struct Texture {
  VkImage image = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  VkSampler sampler = VK_NULL_HANDLE;

  void Destroy(VkDevice device) {
    if (sampler) vkDestroySampler(device, sampler, nullptr);
    if (view) vkDestroyImageView(device, view, nullptr);
    if (image) vkDestroyImage(device, image, nullptr);
    if (memory) vkFreeMemory(device, memory, nullptr);
    *this = {};
  }
};

// Tightly packed texels ready to upload.
struct Texels {
  std::vector<uint8_t> data;
  uint32_t width = 0;
  uint32_t height = 0;
};

// Repacks RGBA input into `channels` (4 = RGBA, 1 = red only, e.g. elevation as R8) and, when
// the image exceeds the GPU's 2D limit, box-filters it down by the smallest integer factor that
// fits. Vulkan only guarantees 4096; older Android GPUs sit at that minimum.
Texels PrepareTexels(const RgbaImage& source, uint32_t channels, uint32_t max_dimension) {
  Texels out;
  if (!source.pixels || source.width == 0 || source.height == 0 || source.stride < source.width * 4 ||
      max_dimension == 0) {
    return out;
  }
  const uint32_t factor =
      std::max((source.width + max_dimension - 1) / max_dimension, (source.height + max_dimension - 1) / max_dimension);
  out.width = std::max(1u, source.width / factor);
  out.height = std::max(1u, source.height / factor);
  if (factor > 1) {
    LogInfo("%ux%u texture exceeds the %u limit; downsampled to %ux%u", source.width, source.height, max_dimension,
            out.width, out.height);
  }
  out.data.resize(static_cast<size_t>(out.width) * out.height * channels);
  const uint32_t area = factor * factor;
  for (uint32_t y = 0; y < out.height; ++y) {
    for (uint32_t x = 0; x < out.width; ++x) {
      for (uint32_t c = 0; c < channels; ++c) {
        uint32_t sum = 0;
        for (uint32_t dy = 0; dy < factor; ++dy) {
          const uint8_t* row = source.pixels + static_cast<size_t>(y * factor + dy) * source.stride;
          for (uint32_t dx = 0; dx < factor; ++dx) sum += row[static_cast<size_t>(x * factor + dx) * 4 + c];
        }
        out.data[(static_cast<size_t>(y) * out.width + x) * channels + c] =
            static_cast<uint8_t>((sum + area / 2) / area);
      }
    }
  }
  return out;
}

// A UV sphere of unit radius; `level` doubles the tessellation per step.
void BuildSphere(uint32_t level, std::vector<Vertex>& vertices, std::vector<uint32_t>& indices) {
  const uint32_t longitude_segments = 64u << level;
  const uint32_t latitude_segments = 32u << level;
  vertices.clear();
  indices.clear();
  vertices.reserve((longitude_segments + 1) * (latitude_segments + 1));
  indices.reserve(longitude_segments * latitude_segments * 6);
  for (uint32_t lat = 0; lat <= latitude_segments; ++lat) {
    const float v = static_cast<float>(lat) / latitude_segments;
    const float latitude = (v - 0.5f) * std::numbers::pi_v<float>;
    for (uint32_t lon = 0; lon <= longitude_segments; ++lon) {
      const float u = static_cast<float>(lon) / longitude_segments;
      const float longitude = (u - 0.5f) * 2.0f * std::numbers::pi_v<float>;
      const float x = std::cos(latitude) * std::sin(longitude);
      const float y = std::sin(latitude);
      const float z = std::cos(latitude) * std::cos(longitude);
      vertices.push_back({{x, y, z}, {x, y, z}, {u, 1.0f - v}});
    }
  }
  for (uint32_t lat = 0; lat < latitude_segments; ++lat) {
    for (uint32_t lon = 0; lon < longitude_segments; ++lon) {
      const uint32_t top = lat * (longitude_segments + 1) + lon;
      const uint32_t bottom = top + longitude_segments + 1;
      indices.insert(indices.end(), {top, bottom, top + 1, top + 1, bottom, bottom + 1});
    }
  }
}

void ImageBarrier(VkCommandBuffer command, VkImage image, uint32_t base_mip, uint32_t mip_count,
                  VkImageLayout old_layout, VkImageLayout new_layout, VkAccessFlags src_access,
                  VkAccessFlags dst_access, VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage) {
  VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  barrier.oldLayout = old_layout;
  barrier.newLayout = new_layout;
  barrier.srcAccessMask = src_access;
  barrier.dstAccessMask = dst_access;
  barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.image = image;
  barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, base_mip, mip_count, 0, 1};
  vkCmdPipelineBarrier(command, src_stage, dst_stage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

// Copies `staging` into mip 0 of `image`, blits the remaining mip levels from it, and leaves every
// level ready for sampling.
void RecordTextureUpload(VkCommandBuffer command, VkBuffer staging, VkImage image, uint32_t width, uint32_t height,
                         uint32_t mip_levels) {
  ImageBarrier(command, image, 0, mip_levels, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
               VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
  VkBufferImageCopy copy{};
  copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  copy.imageExtent = {width, height, 1};
  vkCmdCopyBufferToImage(command, staging, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

  auto mip_width = static_cast<int32_t>(width);
  auto mip_height = static_cast<int32_t>(height);
  for (uint32_t level = 1; level < mip_levels; ++level) {
    ImageBarrier(command, image, level - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                 VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    const int32_t next_width = std::max(1, mip_width / 2);
    const int32_t next_height = std::max(1, mip_height / 2);
    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, level - 1, 0, 1};
    blit.srcOffsets[1] = {mip_width, mip_height, 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1};
    blit.dstOffsets[1] = {next_width, next_height, 1};
    vkCmdBlitImage(command, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                   &blit, VK_FILTER_LINEAR);
    ImageBarrier(command, image, level - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                 VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                 VK_PIPELINE_STAGE_TRANSFER_BIT, kTextureReadStages);
    mip_width = next_width;
    mip_height = next_height;
  }
  ImageBarrier(command, image, mip_levels - 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
               VK_PIPELINE_STAGE_TRANSFER_BIT, kTextureReadStages);
}

// Rotates a column-major clip-space matrix by the swapchain pre-transform, so the presentation
// engine's rotation turns the image upright. VK_SURFACE_TRANSFORM_ROTATE_90 means 90° clockwise
// on screen; with Vulkan's y-down clip space that is x' = -y, y' = x.
void RotateClipSpace(float* matrix, VkSurfaceTransformFlagBitsKHR transform) {
  float c = 1.0f;
  float s = 0.0f;
  switch (transform) {
    case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
      c = 0.0f;
      s = 1.0f;
      break;
    case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
      c = -1.0f;
      break;
    case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
      c = 0.0f;
      s = -1.0f;
      break;
    default:
      return;
  }
  for (int column = 0; column < 4; ++column) {
    const float x = matrix[column * 4];
    const float y = matrix[column * 4 + 1];
    matrix[column * 4] = c * x - s * y;
    matrix[column * 4 + 1] = s * x + c * y;
  }
}

class MoonRenderer final : public Renderer {
 public:
  MoonRenderer() = default;
  ~MoonRenderer() override { Destroy(); }
  MoonRenderer(const MoonRenderer&) = delete;
  MoonRenderer& operator=(const MoonRenderer&) = delete;

  bool Create(const RendererConfig& config) {
    width_ = config.width;
    height_ = config.height;
    texture_dimension_cap_ = config.max_texture_dimension;
    // The setup steps report only success or failure; log which one failed so a field report
    // of "the renderer didn't start" can be traced without a debugger.
    const auto step = [](bool ok, const char* name) {
      if (!ok) LogError("renderer setup failed at %s", name);
      return ok;
    };
    if (!step(CreateInstance(config), "vkCreateInstance") || !step(CreateSurface(config), "surface creation") ||
        !step(SelectDevice(), "device selection (no GPU can present to this surface)") ||
        !step(CreateDevice(), "vkCreateDevice") || !step(ChooseFormats(), "format selection") ||
        !step(CreateRenderPass(), "render pass") || !step(CreateSwapchain(), "swapchain") ||
        !step(CreateDescriptorSet(), "descriptor set") || !step(CreatePipeline(), "graphics pipeline") ||
        !step(CreateMesh(), "mesh buffers") || !step(CreateTextures(config), "textures")) {
      Destroy();
      return false;
    }
    return true;
  }

  void Resize(uint32_t width, uint32_t height) override {
    // Hosts report size on events that do not change it (desktop window moves, for one);
    // rebuilding the swapchain then would stall the GPU for nothing.
    if (width == 0 || height == 0 || (width == width_ && height == height_)) return;
    width_ = width;
    height_ = height;
    recreate_requested_ = true;
  }

  void UpdateView(const ViewState& view) override {
    // The same limits as Kotlin's MoonView ranges.
    const TerrainDetail previous_detail = view_.terrain_detail;
    view_ = view;
    view_.latitude = std::clamp(view.latitude, -78.0f, 78.0f);
    view_.distance = std::clamp(view.distance, 0.72f, 2.2f);
    view_.sun_azimuth = std::clamp(view.sun_azimuth, -85.0f, 85.0f);
    view_.translation_x = std::clamp(view.translation_x, -1.5f, 1.5f);
    view_.translation_y = std::clamp(view.translation_y, -1.5f, 1.5f);
    view_.relief = std::clamp(view.relief, 0.5f, 2.0f);
    view_.terrain_detail = std::min(view.terrain_detail, TerrainDetail::kHigh);
    if (view_.terrain_detail != previous_detail) mesh_recreate_requested_ = true;
  }

  bool Render() override {
    if (!device_ || width_ == 0 || height_ == 0) return false;
    if (recreate_requested_ && !RecreateSwapchain()) return false;
    if (mesh_recreate_requested_) {
      vkDeviceWaitIdle(device_);
      DestroyMesh();
      if (!CreateMesh()) return false;
    }

    vkWaitForFences(device_, 1, &in_flight_, VK_TRUE, UINT64_MAX);
    uint32_t image_index = 0;
    const VkResult acquire =
        vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, image_ready_, VK_NULL_HANDLE, &image_index);
    if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
      recreate_requested_ = true;
      return false;
    }
    if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) return false;
    vkResetFences(device_, 1, &in_flight_);
    vkResetCommandBuffer(command_buffer_, 0);
    if (!RecordFrame(framebuffers_[image_index])) return false;

    const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &image_ready_;
    submit.pWaitDstStageMask = &wait_stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command_buffer_;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &render_done_[image_index];
    if (vkQueueSubmit(queue_, 1, &submit, in_flight_) != VK_SUCCESS) return false;

    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &render_done_[image_index];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain_;
    present.pImageIndices = &image_index;
    const VkResult result = vkQueuePresentKHR(queue_, &present);
    recreate_requested_ = result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR;
    return result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
  }

  bool NeedsFrame() const override { return recreate_requested_ || mesh_recreate_requested_; }

 private:
  // --- Instance and device ---------------------------------------------------------------

  bool CreateInstance(const RendererConfig& config) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Lunar Vulkan";
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = static_cast<uint32_t>(config.instance_extensions.size());
    info.ppEnabledExtensionNames = config.instance_extensions.data();
    return vkCreateInstance(&info, nullptr, &instance_) == VK_SUCCESS;
  }

  bool CreateSurface(const RendererConfig& config) {
    return config.create_surface && config.create_surface(instance_, &surface_) == VK_SUCCESS;
  }

  // Picks the GPU to render with: one with a queue family that can both draw and present to the
  // surface, preferring a discrete GPU over an integrated one (dual-GPU laptops), and either over
  // a virtual or CPU implementation such as lavapipe.
  bool SelectDevice() {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance_, &count, nullptr) != VK_SUCCESS || count == 0) return false;
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance_, &count, devices.data());
    int best_rank = -1;
    for (VkPhysicalDevice candidate : devices) {
      const std::optional<uint32_t> family = GraphicsPresentFamily(candidate);
      if (!family) continue;
      VkPhysicalDeviceProperties properties{};
      vkGetPhysicalDeviceProperties(candidate, &properties);
      const int rank = DeviceTypeRank(properties.deviceType);
      if (rank > best_rank) {
        best_rank = rank;
        physical_device_ = candidate;
        queue_family_ = *family;
        device_properties_ = properties;
      }
    }
    if (best_rank < 0) return false;
    vkGetPhysicalDeviceMemoryProperties(physical_device_, &memory_properties_);
    LogInfo("rendering with %s", device_properties_.deviceName);
    return true;
  }

  std::optional<uint32_t> GraphicsPresentFamily(VkPhysicalDevice device) const {
    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &family_count, nullptr);
    std::vector<VkQueueFamilyProperties> families(family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &family_count, families.data());
    for (uint32_t i = 0; i < family_count; ++i) {
      VkBool32 can_present = VK_FALSE;
      vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface_, &can_present);
      if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && can_present) return i;
    }
    return std::nullopt;
  }

  static int DeviceTypeRank(VkPhysicalDeviceType type) {
    switch (type) {
      case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
        return 3;
      case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
        return 2;
      case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
        return 1;
      default:
        return 0;  // CPU or other.
    }
  }

  bool CreateDevice() {
    constexpr float kPriority = 1.0f;
    VkDeviceQueueCreateInfo queue_info{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue_info.queueFamilyIndex = queue_family_;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &kPriority;

    std::vector<const char*> extensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    uint32_t available_count = 0;
    vkEnumerateDeviceExtensionProperties(physical_device_, nullptr, &available_count, nullptr);
    std::vector<VkExtensionProperties> available(available_count);
    vkEnumerateDeviceExtensionProperties(physical_device_, nullptr, &available_count, available.data());
    if (std::any_of(available.begin(), available.end(),
                    [](const VkExtensionProperties& e) { return e.extensionName == kPortabilitySubset; })) {
      extensions.push_back(kPortabilitySubset.data());
    }

    // Anisotropic filtering keeps craters crisp toward the limb, where the surface is viewed at
    // a grazing angle. Enable it only where the device supports it.
    VkPhysicalDeviceFeatures supported{};
    vkGetPhysicalDeviceFeatures(physical_device_, &supported);
    VkPhysicalDeviceFeatures enabled{};
    enabled.samplerAnisotropy = supported.samplerAnisotropy;
    if (supported.samplerAnisotropy) {
      max_anisotropy_ = std::min(16.0f, device_properties_.limits.maxSamplerAnisotropy);
    }

    VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queue_info;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    info.pEnabledFeatures = &enabled;
    if (vkCreateDevice(physical_device_, &info, nullptr, &device_) != VK_SUCCESS) return false;
    vkGetDeviceQueue(device_, queue_family_, 0, &queue_);

    VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;  // The frame buffer is reset each frame.
    pool.queueFamilyIndex = queue_family_;
    if (vkCreateCommandPool(device_, &pool, nullptr, &command_pool_) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocation.commandPool = command_pool_;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device_, &allocation, &command_buffer_) != VK_SUCCESS) return false;

    VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    return vkCreateSemaphore(device_, &semaphore, nullptr, &image_ready_) == VK_SUCCESS &&
           vkCreateFence(device_, &fence, nullptr, &in_flight_) == VK_SUCCESS;
  }

  // --- Swapchain ------------------------------------------------------------------------

  // A surface's formats do not change, so the render pass and pipeline are built once for them.
  bool ChooseFormats() {
    uint32_t count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &count, nullptr);
    if (count == 0) return false;
    std::vector<VkSurfaceFormatKHR> formats(count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &count, formats.data());
    // Prefer an sRGB format so shading in linear space is encoded for display. Desktop drivers
    // usually offer BGRA, Android usually RGBA; accept either.
    const auto srgb = std::find_if(formats.begin(), formats.end(), [](const VkSurfaceFormatKHR& format) {
      return (format.format == VK_FORMAT_B8G8R8A8_SRGB || format.format == VK_FORMAT_R8G8B8A8_SRGB) &&
             format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    });
    surface_format_ = srgb == formats.end() ? formats.front() : *srgb;

    for (VkFormat candidate : {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT}) {
      VkFormatProperties properties{};
      vkGetPhysicalDeviceFormatProperties(physical_device_, candidate, &properties);
      if (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) {
        depth_format_ = candidate;
        return true;
      }
    }
    return false;
  }

  bool CreateRenderPass() {
    VkAttachmentDescription attachments[2]{};
    attachments[0].format = surface_format_.format;
    attachments[0].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[0].finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    attachments[1].format = depth_format_;
    attachments[1].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    const VkAttachmentReference color_reference{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    const VkAttachmentReference depth_reference{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &color_reference;
    subpass.pDepthStencilAttachment = &depth_reference;
    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 2;
    info.pAttachments = attachments;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    return vkCreateRenderPass(device_, &info, nullptr, &render_pass_) == VK_SUCCESS;
  }

  bool CreateSwapchain() {
    VkSurfaceCapabilitiesKHR caps{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_device_, surface_, &caps);
    // Pre-rotation (Android): when the display is turned from its natural orientation the surface
    // reports a ROTATE_* currentTransform and a currentExtent in the *current* orientation.
    // Presenting with preTransform == currentTransform avoids an extra compositor pass (and the
    // VK_SUBOPTIMAL_KHR Android returns otherwise), but then the images must be sized in the
    // natural orientation and their content rotated to match (RotateClipSpace).
    constexpr VkSurfaceTransformFlagsKHR kRotations =
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR |
        VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
    pre_transform_ =
        (caps.currentTransform & kRotations) || !(caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? caps.currentTransform
            : VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    if (caps.currentExtent.width != UINT32_MAX) {
      extent_ = caps.currentExtent;
      if (QuarterTurn()) std::swap(extent_.width, extent_.height);
    } else {
      extent_ = {std::clamp(width_, caps.minImageExtent.width, caps.maxImageExtent.width),
                 std::clamp(height_, caps.minImageExtent.height, caps.maxImageExtent.height)};
    }
    if (extent_.width == 0 || extent_.height == 0) return false;

    uint32_t image_count = std::max(caps.minImageCount + 1, 2u);
    if (caps.maxImageCount) image_count = std::min(image_count, caps.maxImageCount);
    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = surface_;
    info.minImageCount = image_count;
    info.imageFormat = surface_format_.format;
    info.imageColorSpace = surface_format_.colorSpace;
    info.imageExtent = extent_;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = pre_transform_;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(device_, &info, nullptr, &swapchain_) != VK_SUCCESS) return false;
    vkGetSwapchainImagesKHR(device_, swapchain_, &image_count, nullptr);
    std::vector<VkImage> images(image_count);
    vkGetSwapchainImagesKHR(device_, swapchain_, &image_count, images.data());

    if (!CreateImage(extent_.width, extent_.height, 1, depth_format_, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                     &depth_image_, &depth_memory_) ||
        !CreateImageView(depth_image_, depth_format_, VK_IMAGE_ASPECT_DEPTH_BIT, 1, &depth_view_)) {
      return false;
    }
    image_views_.resize(images.size(), VK_NULL_HANDLE);
    framebuffers_.resize(images.size(), VK_NULL_HANDLE);
    // One "rendering finished" semaphore per swapchain image: presentation may still be waiting
    // on an image's semaphore when the next frame is submitted, so a single shared semaphore
    // could be signalled again while still in use.
    render_done_.resize(images.size(), VK_NULL_HANDLE);
    for (size_t i = 0; i < images.size(); ++i) {
      if (!CreateImageView(images[i], surface_format_.format, VK_IMAGE_ASPECT_COLOR_BIT, 1, &image_views_[i])) {
        return false;
      }
      const VkImageView attachments[] = {image_views_[i], depth_view_};
      VkFramebufferCreateInfo framebuffer{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
      framebuffer.renderPass = render_pass_;
      framebuffer.attachmentCount = 2;
      framebuffer.pAttachments = attachments;
      framebuffer.width = extent_.width;
      framebuffer.height = extent_.height;
      framebuffer.layers = 1;
      VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
      if (vkCreateFramebuffer(device_, &framebuffer, nullptr, &framebuffers_[i]) != VK_SUCCESS ||
          vkCreateSemaphore(device_, &semaphore, nullptr, &render_done_[i]) != VK_SUCCESS) {
        return false;
      }
    }
    recreate_requested_ = false;
    return true;
  }

  bool RecreateSwapchain() {
    // A minimised or not-yet-laid-out desktop window reports a 0x0 extent; keep the old swapchain
    // and retry on a later frame instead of tearing everything down.
    VkSurfaceCapabilitiesKHR caps{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_device_, surface_, &caps);
    if (caps.currentExtent.width == 0 || caps.currentExtent.height == 0) return false;
    vkDeviceWaitIdle(device_);
    DestroySwapchain();
    return CreateSwapchain();
  }

  void DestroySwapchain() {
    for (VkFramebuffer framebuffer : framebuffers_) {
      if (framebuffer) vkDestroyFramebuffer(device_, framebuffer, nullptr);
    }
    for (VkImageView view : image_views_) {
      if (view) vkDestroyImageView(device_, view, nullptr);
    }
    for (VkSemaphore semaphore : render_done_) {
      if (semaphore) vkDestroySemaphore(device_, semaphore, nullptr);
    }
    framebuffers_.clear();
    image_views_.clear();
    render_done_.clear();
    if (depth_view_) vkDestroyImageView(device_, depth_view_, nullptr);
    if (depth_image_) vkDestroyImage(device_, depth_image_, nullptr);
    if (depth_memory_) vkFreeMemory(device_, depth_memory_, nullptr);
    if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
    depth_view_ = VK_NULL_HANDLE;
    depth_image_ = VK_NULL_HANDLE;
    depth_memory_ = VK_NULL_HANDLE;
    swapchain_ = VK_NULL_HANDLE;
  }

  bool QuarterTurn() const {
    return (pre_transform_ & (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR)) != 0;
  }

  // --- Pipeline and descriptors -----------------------------------------------------------

  bool CreateDescriptorSet() {
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;  // Albedo.
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    bindings[1].binding = 1;  // Elevation.
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo layout{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layout.bindingCount = 2;
    layout.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device_, &layout, nullptr, &descriptor_set_layout_) != VK_SUCCESS) return false;

    const VkDescriptorPoolSize pool_size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets = 1;
    pool.poolSizeCount = 1;
    pool.pPoolSizes = &pool_size;
    if (vkCreateDescriptorPool(device_, &pool, nullptr, &descriptor_pool_) != VK_SUCCESS) return false;

    VkDescriptorSetAllocateInfo allocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocation.descriptorPool = descriptor_pool_;
    allocation.descriptorSetCount = 1;
    allocation.pSetLayouts = &descriptor_set_layout_;
    return vkAllocateDescriptorSets(device_, &allocation, &descriptor_set_) == VK_SUCCESS;
  }

  bool CreatePipeline() {
    VkPushConstantRange push_constants{VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(PushConstants)};
    VkPipelineLayoutCreateInfo layout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layout.setLayoutCount = 1;
    layout.pSetLayouts = &descriptor_set_layout_;
    layout.pushConstantRangeCount = 1;
    layout.pPushConstantRanges = &push_constants;
    if (vkCreatePipelineLayout(device_, &layout, nullptr, &pipeline_layout_) != VK_SUCCESS) return false;

    // The SPIR-V is compiled from renderer/shaders by CMake and embedded in this library. The
    // modules are only needed while the pipeline is built.
    VkShaderModule vertex_shader = CreateShaderModule(moon_vert, sizeof(moon_vert));
    VkShaderModule fragment_shader = CreateShaderModule(moon_frag, sizeof(moon_frag));
    const bool created = vertex_shader && fragment_shader && CreateGraphicsPipeline(vertex_shader, fragment_shader);
    if (vertex_shader) vkDestroyShaderModule(device_, vertex_shader, nullptr);
    if (fragment_shader) vkDestroyShaderModule(device_, fragment_shader, nullptr);
    return created;
  }

  VkShaderModule CreateShaderModule(const uint32_t* code, size_t size) {
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize = size;
    info.pCode = code;
    VkShaderModule shader = VK_NULL_HANDLE;
    return vkCreateShaderModule(device_, &info, nullptr, &shader) == VK_SUCCESS ? shader : VK_NULL_HANDLE;
  }

  bool CreateGraphicsPipeline(VkShaderModule vertex_shader, VkShaderModule fragment_shader) {
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertex_shader;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragment_shader;
    stages[1].pName = "main";

    const VkVertexInputBindingDescription binding{0, sizeof(Vertex), VK_VERTEX_INPUT_RATE_VERTEX};
    const VkVertexInputAttributeDescription attributes[] = {
        {0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(Vertex, position)},
        {1, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(Vertex, normal)},
        {2, 0, VK_FORMAT_R32G32_SFLOAT, offsetof(Vertex, uv)},
    };
    VkPipelineVertexInputStateCreateInfo vertex_input{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vertex_input.vertexBindingDescriptionCount = 1;
    vertex_input.pVertexBindingDescriptions = &binding;
    vertex_input.vertexAttributeDescriptionCount = 3;
    vertex_input.pVertexAttributeDescriptions = attributes;
    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    // Viewport and scissor are dynamic, so resizing rebuilds only the swapchain.
    VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    const VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamic_states;
    VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_BACK_BIT;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineDepthStencilStateCreateInfo depth{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
    depth.depthTestEnable = VK_TRUE;
    depth.depthWriteEnable = VK_TRUE;
    depth.depthCompareOp = VK_COMPARE_OP_LESS;
    VkPipelineColorBlendAttachmentState blend{};
    blend.colorWriteMask =
        VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo color{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    color.attachmentCount = 1;
    color.pAttachments = &blend;

    VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertex_input;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pDepthStencilState = &depth;
    info.pColorBlendState = &color;
    info.pDynamicState = &dynamic;
    info.layout = pipeline_layout_;
    info.renderPass = render_pass_;
    info.subpass = 0;
    return vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline_) == VK_SUCCESS;
  }

  // --- Frame ------------------------------------------------------------------------------

  bool RecordFrame(VkFramebuffer framebuffer) {
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    if (vkBeginCommandBuffer(command_buffer_, &begin) != VK_SUCCESS) return false;
    VkClearValue clear[2]{};
    clear[0].color = {{0.016f, 0.052f, 0.056f, 1.0f}};  // Blue-green space (linear; the swapchain is sRGB).
    clear[1].depthStencil = {1.0f, 0};
    VkRenderPassBeginInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    pass.renderPass = render_pass_;
    pass.framebuffer = framebuffer;
    pass.renderArea.extent = extent_;
    pass.clearValueCount = 2;
    pass.pClearValues = clear;
    vkCmdBeginRenderPass(command_buffer_, &pass, VK_SUBPASS_CONTENTS_INLINE);

    const VkViewport viewport{0.0f, 0.0f, static_cast<float>(extent_.width), static_cast<float>(extent_.height),
                              0.0f, 1.0f};
    const VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(command_buffer_, 0, 1, &viewport);
    vkCmdSetScissor(command_buffer_, 0, 1, &scissor);
    vkCmdBindPipeline(command_buffer_, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(command_buffer_, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout_, 0, 1, &descriptor_set_,
                            0, nullptr);
    const VkDeviceSize vertex_offset = 0;
    vkCmdBindVertexBuffers(command_buffer_, 0, 1, &vertex_buffer_.buffer, &vertex_offset);
    vkCmdBindIndexBuffer(command_buffer_, index_buffer_.buffer, 0, VK_INDEX_TYPE_UINT32);
    const PushConstants constants = ViewConstants();
    vkCmdPushConstants(command_buffer_, pipeline_layout_, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(constants), &constants);
    vkCmdDrawIndexed(command_buffer_, index_count_, 1, 0, 0, 0);
    vkCmdEndRenderPass(command_buffer_);
    return vkEndCommandBuffer(command_buffer_) == VK_SUCCESS;
  }

  PushConstants ViewConstants() const {
    const float yaw = view_.longitude * kRadiansPerDegree;
    // Positive latitude tilts the north toward the viewer, as in the Compose preview, so dragging
    // down (which raises latitude) moves the near surface down with the finger. The model matrix
    // below rotates the opposite way in Vulkan's y-down clip space, hence the negation.
    const float pitch = -view_.latitude * kRadiansPerDegree;
    const float sun = view_.sun_azimuth * kRadiansPerDegree;
    const float cy = std::cos(yaw);
    const float sy = std::sin(yaw);
    const float cp = std::cos(pitch);
    const float sp = std::sin(pitch);
    // With a quarter-turn pre-transform the swapchain is in the display's natural orientation, so
    // the on-screen aspect ratio is the swapchain's, inverted.
    const float aspect = QuarterTurn() ? static_cast<float>(extent_.height) / static_cast<float>(extent_.width)
                                       : static_cast<float>(extent_.width) / static_cast<float>(extent_.height);
    // The UI's distance is a framing multiplier (72-220%) applied to the projection scale.
    const float scale = 0.72f * view_.distance;
    // Orthographic projection. Depth maps the unit sphere to [0.25, 0.75], not [0, 1]: elevation
    // displacement pushes terrain past radius 1, and the near plane would otherwise clip a hole
    // in the middle of the globe. The UI's pan is y-up; Vulkan clip space is y-down.
    // clang-format off
    PushConstants constants{
        .projection = {scale / aspect, 0.0f,  0.0f,  0.0f,
                       0.0f,           scale, 0.0f,  0.0f,
                       0.0f,           0.0f,  0.25f, 0.0f,
                       view_.translation_x, -view_.translation_y, 0.5f, 1.0f},
        .model = {cy,      0.0f, -sy,     0.0f,
                  sy * sp, cp,   cy * sp, 0.0f,
                  sy * cp, -sp,  cy * cp, 0.0f,
                  0.0f,    0.0f, 0.0f,    1.0f},
        .sun = {std::sin(sun), 0.35f, std::cos(sun), view_.relief},
    };
    // clang-format on
    RotateClipSpace(constants.projection, pre_transform_);
    return constants;
  }

  // --- Resources --------------------------------------------------------------------------

  bool CreateMesh() {
    std::vector<Vertex> vertices;
    std::vector<uint32_t> indices;
    BuildSphere(static_cast<uint32_t>(view_.terrain_detail), vertices, indices);
    // Small and written once, so host-visible memory is fine; no staging copy.
    if (!CreateHostBuffer(vertices.data(), vertices.size() * sizeof(Vertex), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                          vertex_buffer_) ||
        !CreateHostBuffer(indices.data(), indices.size() * sizeof(uint32_t), VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                          index_buffer_)) {
      return false;
    }
    index_count_ = static_cast<uint32_t>(indices.size());
    mesh_recreate_requested_ = false;
    return true;
  }

  void DestroyMesh() {
    vertex_buffer_.Destroy(device_);
    index_buffer_.Destroy(device_);
    index_count_ = 0;
  }

  bool CreateTextures(const RendererConfig& config) {
    const uint32_t max_dimension = texture_dimension_cap_
                                       ? std::min(device_properties_.limits.maxImageDimension2D, texture_dimension_cap_)
                                       : device_properties_.limits.maxImageDimension2D;
    // Mipmaps keep the albedo from shimmering when the globe is small. Elevation is sampled at a
    // fixed scale for displacement and relief, so it needs only one level.
    // The albedo map is sRGB-encoded colour: an _SRGB format makes sampling return linear values,
    // which the sRGB swapchain encodes once. Elevation is data, not colour, so it stays UNORM.
    if (!UploadTexture(PrepareTexels(config.albedo, 4, max_dimension), VK_FORMAT_R8G8B8A8_SRGB, true, albedo_) ||
        !UploadTexture(PrepareTexels(config.elevation, 1, max_dimension), VK_FORMAT_R8_UNORM, false, elevation_)) {
      return false;
    }
    WriteTextureDescriptor(0, albedo_);
    WriteTextureDescriptor(1, elevation_);
    return true;
  }

  bool UploadTexture(const Texels& texels, VkFormat format, bool mipmapped, Texture& texture) {
    if (texels.data.empty()) return false;
    // Generating mipmaps by blitting needs linear filtering support for the format.
    VkFormatProperties properties{};
    vkGetPhysicalDeviceFormatProperties(physical_device_, format, &properties);
    const bool can_blit = (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
    const uint32_t mip_levels =
        mipmapped && can_blit ? static_cast<uint32_t>(std::floor(std::log2(std::max(texels.width, texels.height)))) + 1
                              : 1;
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                                    (mip_levels > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
    Buffer staging;
    const bool uploaded =
        CreateHostBuffer(texels.data.data(), texels.data.size(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, staging) &&
        CreateImage(texels.width, texels.height, mip_levels, format, usage, &texture.image, &texture.memory) &&
        RunOneTimeCommands([&](VkCommandBuffer command) {
          RecordTextureUpload(command, staging.buffer, texture.image, texels.width, texels.height, mip_levels);
        });
    staging.Destroy(device_);
    return uploaded && CreateImageView(texture.image, format, VK_IMAGE_ASPECT_COLOR_BIT, mip_levels, &texture.view) &&
           CreateSampler(mip_levels, &texture.sampler);
  }

  void WriteTextureDescriptor(uint32_t binding, const Texture& texture) {
    const VkDescriptorImageInfo image{texture.sampler, texture.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = descriptor_set_;
    write.dstBinding = binding;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &image;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
  }

  // Longitude wraps around the sphere; latitude stops at the poles.
  bool CreateSampler(uint32_t mip_levels, VkSampler* sampler) {
    VkSamplerCreateInfo info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    info.magFilter = VK_FILTER_LINEAR;
    info.minFilter = VK_FILTER_LINEAR;
    info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    info.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.maxLod = static_cast<float>(mip_levels);
    info.anisotropyEnable = mip_levels > 1 && max_anisotropy_ > 1.0f ? VK_TRUE : VK_FALSE;
    info.maxAnisotropy = std::max(1.0f, max_anisotropy_);
    return vkCreateSampler(device_, &info, nullptr, sampler) == VK_SUCCESS;
  }

  bool CreateImage(uint32_t width, uint32_t height, uint32_t mip_levels, VkFormat format, VkImageUsageFlags usage,
                   VkImage* image, VkDeviceMemory* memory) {
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {width, height, 1};
    info.mipLevels = mip_levels;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device_, &info, nullptr, image) != VK_SUCCESS) return false;
    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(device_, *image, &requirements);
    return Allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, memory) &&
           vkBindImageMemory(device_, *image, *memory, 0) == VK_SUCCESS;
  }

  bool CreateImageView(VkImage image, VkFormat format, VkImageAspectFlags aspect, uint32_t mip_levels,
                       VkImageView* view) {
    VkImageViewCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    info.image = image;
    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    info.format = format;
    info.subresourceRange = {aspect, 0, mip_levels, 0, 1};
    return vkCreateImageView(device_, &info, nullptr, view) == VK_SUCCESS;
  }

  // Creates a host-visible buffer and fills it with `size` bytes of `data`.
  bool CreateHostBuffer(const void* data, VkDeviceSize size, VkBufferUsageFlags usage, Buffer& buffer) {
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device_, &info, nullptr, &buffer.buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device_, buffer.buffer, &requirements);
    void* mapped = nullptr;
    if (!Allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                  &buffer.memory) ||
        vkBindBufferMemory(device_, buffer.buffer, buffer.memory, 0) != VK_SUCCESS ||
        vkMapMemory(device_, buffer.memory, 0, size, 0, &mapped) != VK_SUCCESS) {
      return false;
    }
    std::memcpy(mapped, data, static_cast<size_t>(size));
    vkUnmapMemory(device_, buffer.memory);
    return true;
  }

  bool Allocate(const VkMemoryRequirements& requirements, VkMemoryPropertyFlags properties, VkDeviceMemory* memory) {
    uint32_t type = kNoMemoryType;
    for (uint32_t i = 0; i < memory_properties_.memoryTypeCount; ++i) {
      if ((requirements.memoryTypeBits & (1u << i)) &&
          (memory_properties_.memoryTypes[i].propertyFlags & properties) == properties) {
        type = i;
        break;
      }
    }
    if (type == kNoMemoryType) return false;
    VkMemoryAllocateInfo info{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    info.allocationSize = requirements.size;
    info.memoryTypeIndex = type;
    return vkAllocateMemory(device_, &info, nullptr, memory) == VK_SUCCESS;
  }

  // Records commands into a temporary command buffer, submits them, and waits for completion.
  // Only used during setup, where blocking is acceptable.
  bool RunOneTimeCommands(const std::function<void(VkCommandBuffer)>& record) {
    VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocation.commandPool = command_pool_;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1;
    VkCommandBuffer command = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(device_, &allocation, &command) != VK_SUCCESS) return false;
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    bool ok = vkBeginCommandBuffer(command, &begin) == VK_SUCCESS;
    if (ok) {
      record(command);
      VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
      submit.commandBufferCount = 1;
      submit.pCommandBuffers = &command;
      ok = vkEndCommandBuffer(command) == VK_SUCCESS &&
           vkQueueSubmit(queue_, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS && vkQueueWaitIdle(queue_) == VK_SUCCESS;
    }
    vkFreeCommandBuffers(device_, command_pool_, 1, &command);
    return ok;
  }

  // --- Teardown ---------------------------------------------------------------------------

  void Destroy() {
    if (device_) {
      vkDeviceWaitIdle(device_);
      DestroySwapchain();
      DestroyMesh();
      albedo_.Destroy(device_);
      elevation_.Destroy(device_);
      if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
      if (pipeline_layout_) vkDestroyPipelineLayout(device_, pipeline_layout_, nullptr);
      if (descriptor_pool_) vkDestroyDescriptorPool(device_, descriptor_pool_, nullptr);
      if (descriptor_set_layout_) vkDestroyDescriptorSetLayout(device_, descriptor_set_layout_, nullptr);
      if (render_pass_) vkDestroyRenderPass(device_, render_pass_, nullptr);
      if (image_ready_) vkDestroySemaphore(device_, image_ready_, nullptr);
      if (in_flight_) vkDestroyFence(device_, in_flight_, nullptr);
      if (command_pool_) vkDestroyCommandPool(device_, command_pool_, nullptr);  // Frees command_buffer_.
      vkDestroyDevice(device_, nullptr);
      device_ = VK_NULL_HANDLE;
    }
    if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
    if (instance_) vkDestroyInstance(instance_, nullptr);
    surface_ = VK_NULL_HANDLE;
    instance_ = VK_NULL_HANDLE;
  }

  // Instance and device.
  VkInstance instance_ = VK_NULL_HANDLE;
  VkSurfaceKHR surface_ = VK_NULL_HANDLE;
  VkPhysicalDevice physical_device_ = VK_NULL_HANDLE;
  VkPhysicalDeviceProperties device_properties_{};
  VkPhysicalDeviceMemoryProperties memory_properties_{};
  VkDevice device_ = VK_NULL_HANDLE;
  VkQueue queue_ = VK_NULL_HANDLE;
  uint32_t queue_family_ = 0;
  float max_anisotropy_ = 1.0f;

  // Frame submission.
  VkCommandPool command_pool_ = VK_NULL_HANDLE;
  VkCommandBuffer command_buffer_ = VK_NULL_HANDLE;
  VkSemaphore image_ready_ = VK_NULL_HANDLE;
  VkFence in_flight_ = VK_NULL_HANDLE;

  // Swapchain, rebuilt on resize.
  VkSurfaceFormatKHR surface_format_{};
  VkFormat depth_format_ = VK_FORMAT_UNDEFINED;
  VkSurfaceTransformFlagBitsKHR pre_transform_ = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
  VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
  VkExtent2D extent_{};
  std::vector<VkImageView> image_views_;
  std::vector<VkFramebuffer> framebuffers_;
  std::vector<VkSemaphore> render_done_;  // Per swapchain image, signalled by the frame's submit.
  VkImage depth_image_ = VK_NULL_HANDLE;
  VkDeviceMemory depth_memory_ = VK_NULL_HANDLE;
  VkImageView depth_view_ = VK_NULL_HANDLE;

  // Pipeline, built once.
  VkRenderPass render_pass_ = VK_NULL_HANDLE;
  VkDescriptorSetLayout descriptor_set_layout_ = VK_NULL_HANDLE;
  VkDescriptorPool descriptor_pool_ = VK_NULL_HANDLE;
  VkDescriptorSet descriptor_set_ = VK_NULL_HANDLE;
  VkPipelineLayout pipeline_layout_ = VK_NULL_HANDLE;
  VkPipeline pipeline_ = VK_NULL_HANDLE;

  // Scene.
  Buffer vertex_buffer_;
  Buffer index_buffer_;
  uint32_t index_count_ = 0;
  Texture albedo_;
  Texture elevation_;
  uint32_t texture_dimension_cap_ = 0;

  // State from the render thread.
  ViewState view_;
  uint32_t width_ = 1;
  uint32_t height_ = 1;
  bool recreate_requested_ = false;
  bool mesh_recreate_requested_ = false;
};

}  // namespace

std::unique_ptr<Renderer> CreateRenderer(const RendererConfig& config) {
  auto renderer = std::make_unique<MoonRenderer>();
  if (!renderer->Create(config)) return nullptr;
  return renderer;
}

}  // namespace moon
