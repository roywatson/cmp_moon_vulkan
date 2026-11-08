#pragma once

// Render loop around the shared renderer, used by the macOS, iOS, Windows, and Linux bridges.
// On Apple platforms moon_render_thread.cpp must be compiled as Objective-C++ so each
// frame runs inside an autorelease pool (see the note at the top of that file).

#include "moon_renderer.h"

#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>

namespace moon {

// Owns the renderer and a dedicated thread that makes every Vulkan call after creation.
// Public methods may be called from any thread; they only update a guarded snapshot.
//
// Frames are drawn on demand: when the view or size changes, or while the renderer has work
// pending, not continuously at the display rate, so an idle globe costs no GPU time or battery.
// A one-second heartbeat still redraws, for windowing systems that lose a window's contents
// when it is uncovered.
class RenderThread {
 public:
  explicit RenderThread(std::unique_ptr<Renderer> renderer);
  ~RenderThread();  // Stops and joins the thread, then destroys the renderer.
  RenderThread(const RenderThread&) = delete;
  RenderThread& operator=(const RenderThread&) = delete;

  void Resize(uint32_t pixel_width, uint32_t pixel_height);
  void UpdateView(const ViewState& view);
  // iOS must not submit GPU work while the app is in the background.
  void SetPaused(bool paused);

 private:
  void Run();

  std::unique_ptr<Renderer> renderer_;
  std::mutex mutex_;
  std::condition_variable wake_;
  bool running_ = true;
  bool paused_ = false;
  ViewState view_;
  bool view_pending_ = true;  // Draws the first frame.
  bool resize_pending_ = false;
  bool redraw_pending_ = false;
  uint32_t width_ = 1, height_ = 1;
  std::thread thread_;  // Declared last: starts after every other member is initialised.
};

}  // namespace moon
