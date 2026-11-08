#include "moon_render_thread.h"

#include <chrono>

// MoltenVK hands out autoreleased CAMetalDrawables, and a raw std::thread never drains
// them, so Apple builds compile this file as Objective-C++ (CMake LANGUAGE OBJCXX) and
// wrap every frame in an autorelease pool. Other platforms need no per-frame scope.
#if defined(__APPLE__) && !defined(__OBJC__)
#error "On Apple platforms compile moon_render_thread.cpp as Objective-C++ (LANGUAGE OBJCXX)"
#endif
#if defined(__OBJC__)
#define MOON_FRAME_SCOPE @autoreleasepool
#else
#define MOON_FRAME_SCOPE
#endif

namespace moon {

RenderThread::RenderThread(std::unique_ptr<Renderer> renderer)
    : renderer_(std::move(renderer)), thread_([this] { Run(); }) {}

RenderThread::~RenderThread() {
  {
    std::lock_guard lock(mutex_);
    running_ = false;
  }
  wake_.notify_all();
  if (thread_.joinable()) thread_.join();
  renderer_.reset();
}

void RenderThread::Resize(uint32_t pixel_width, uint32_t pixel_height) {
  if (pixel_width == 0 || pixel_height == 0) return;
  {
    std::lock_guard lock(mutex_);
    width_ = pixel_width;
    height_ = pixel_height;
    resize_pending_ = true;
  }
  wake_.notify_all();
}

void RenderThread::UpdateView(const ViewState& view) {
  {
    std::lock_guard lock(mutex_);
    view_ = view;
    view_pending_ = true;
  }
  wake_.notify_all();
}

void RenderThread::SetPaused(bool paused) {
  {
    std::lock_guard lock(mutex_);
    paused_ = paused;
    redraw_pending_ = !paused;  // The surface may have been discarded while in the background.
  }
  wake_.notify_all();
}

void RenderThread::Run() {
  constexpr auto kHeartbeat = std::chrono::seconds(1);
  constexpr auto kRetryDelay = std::chrono::milliseconds(16);
  while (true) {
    MOON_FRAME_SCOPE {
      ViewState view;
      bool view_pending = false, resize_pending = false;
      uint32_t width = 0, height = 0;
      {
        std::unique_lock lock(mutex_);
        // Sleep until there is something to draw, or the heartbeat is due.
        wake_.wait_for(lock, kHeartbeat, [this] {
          return !running_ || (!paused_ && (view_pending_ || resize_pending_ || redraw_pending_));
        });
        if (!running_) return;
        if (paused_) continue;
        std::swap(view_pending, view_pending_);
        std::swap(resize_pending, resize_pending_);
        redraw_pending_ = false;
        view = view_;
        width = width_;
        height = height_;
      }
      if (view_pending) renderer_->UpdateView(view);
      if (resize_pending) renderer_->Resize(width, height);
      // FIFO presentation paces drawing to the display while frames are requested.
      const bool presented = renderer_->Render();
      if (!presented || renderer_->NeedsFrame()) {
        {
          std::lock_guard lock(mutex_);
          redraw_pending_ = true;
        }
        // Nothing could be presented (e.g. a 0x0 window): back off instead of spinning.
        if (!presented) std::this_thread::sleep_for(kRetryDelay);
      }
    }
  }
}

}  // namespace moon
