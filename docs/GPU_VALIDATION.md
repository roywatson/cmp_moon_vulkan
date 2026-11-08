# Manual GPU validation checklist

Run this on real hardware before tagging a release, after any change under `renderer/`, a
platform bridge, the shaders, the textures, or MoltenVK, and on every new device/GPU family.
The automated tests (`./gradlew :shared:jvmTest`) only cover the Compose shell; nothing
automated exercises Vulkan, MoltenVK, or the GPU.

Copy the **Results log** row template at the bottom for each run. A checklist item fails if
the described result is not seen exactly; record what you saw instead.

## 0. Build from a clean state

Stale native code has shipped silently before (iOS once linked an old `libmoon_ios.a`), so
validate a clean build:

- [ ] `./gradlew clean`
- [ ] Desktop: `./gradlew :desktopApp:run` builds `desktopApp/build/native/libdesktop_moon_renderer.dylib`
      (Windows: `desktopApp/build/native/desktop_moon_renderer.dll`) fresh.
- [ ] Android: `./gradlew :androidApp:assembleDebug`, then install the APK.
- [ ] iOS: build and run the `iosApp` scheme from Xcode (its Gradle phase rebuilds `libmoon_ios.a`).

## 1. Startup and renderer creation (every platform)

- [ ] App launches to the full UI: header, render view, four sliders, footer naming the backend.
- [ ] The Moon is visible within ~2 s; no message that Vulkan could not start.
- [ ] Logs show device and swapchain creation with no `[mvk-error]` lines (Apple) and no Vulkan
      errors in `adb logcat` (Android). Apple: MoltenVK prints `Created VkDevice to run on GPU …`
      and `Created 3 swapchain images with size (W, H) and contents scale S`.
- [ ] Swapchain size equals view points × display scale (e.g. contents scale 2.0 on Retina iPad,
      3.0 on iPhone, 1.0 on a non-Retina display).

## 2. Image correctness

- [ ] **No hole in the globe centre** (regression: displaced terrain clipped by the near plane).
      Check at DISTANCE 72 %, 100 %, and 220 %, and RELIEF 200 %.
- [ ] Globe is round (not stretched) in portrait, landscape, and a very wide/tall window.
- [ ] Craters are crisp at DISTANCE 220 %: rims are sharp, not soft blobs (per-pixel relief lighting).
- [ ] Craters stay sharp toward the limb, without shimmering (anisotropic filtering).
- [ ] No seam along the 180° meridian when it rotates into view, and no artefacts at the poles
      (tilt to ±78° latitude).
- [ ] SUN AZIMUTH starts at **−54°**; sweeping −85° → 85° moves the terminator smoothly across the disc.
- [ ] RELIEF 50 % → 200 % visibly strengthens crater shading without black or white blowouts.
- [ ] TERRAIN DETAIL LOW → BALANCED → HIGH changes the limb smoothness, with no flicker,
      missing frame, or stall when switching (the sphere mesh is rebuilt).
- [ ] Colours look the same across platforms (compare screenshots side by side).

## 3. Interaction

- [ ] One-finger / left-mouse drag orbits: surface features follow the drag on both axes, and the
      direction is the same on every platform (regression: vertical orbit was inverted).
- [ ] Latitude stops at ±78° instead of flipping over the pole.
- [ ] **Two-finger pan (touch) / right- or Shift-drag (desktop) moves the globe with the fingers on
      both axes**: left/right and up/down (regression: vertical pan was inverted).
- [ ] Pan stops at the ±1.5 limit; the globe can be dragged partly off-screen and back.
- [ ] DISTANCE 72 % → 220 % zooms smoothly about the centre.
- [ ] Changing finger count mid-gesture (1 → 2 → 1) does not make the globe jump.
- [ ] With TalkBack (Android) or VoiceOver (iOS): the globe is announced with its orientation
      ("The Moon, turned … and tilted …"), its actions (Turn east/west, Tilt north/south) turn it
      and update the announcement, each slider is announced by name and value (sun azimuth in
      degrees, terrain detail as Low/Balanced/High), and the visible labels are not read twice.

## 4. Resize and swapchain recreation

- [ ] Desktop: drag-resize the window quickly, larger and smaller. The globe follows with no
      stretched frames that persist, no black flashes that persist, and no crash.
- [ ] Desktop: the render view stays aligned with its Compose slot after resizing and after
      moving the window between displays with different scales (Retina ↔ non-Retina).
- [ ] Desktop: minimise and restore; rendering resumes.
- [ ] iOS/Android: rotate portrait ↔ landscape several times; the globe stays round and centred.
- [ ] Android: check all four rotations, including upside-down portrait and reverse landscape
      (`adb shell wm user-rotation lock 0|1|2|3`, then `free`). The globe is round and upright
      in each, and a horizontal drag turns it horizontally (regression: on a tablet in portrait,
      a missing swapchain pre-rotation gave a tall oval with swapped drag axes).
- [ ] iPad: Split View / Stage Manager resize, if available.

## 5. Lifecycle

- [ ] iOS: background the app for 10 s and return. Rendering resumes, and the Xcode console shows no
      GPU-work-in-background errors (the render thread pauses on `didEnterBackground`).
- [ ] iOS: lock and unlock the device while the app is visible.
- [ ] Android: Home → return, and Recents → return; the surface is recreated and the Moon reappears.
- [ ] Desktop: close the window; the process exits cleanly (no hang joining the render thread,
      no crash log).
- [ ] Relaunch immediately after quitting, on every platform.

## 6. Texture-size fallback

The elevation map is 5760×2880; Vulkan only guarantees 4096. GPUs below the map size downsample it.

- [ ] Desktop simulation: `MOON_MAX_TEXTURE_DIMENSION=4096 ./gradlew :desktopApp:run` logs
      `[moon] 5760x2880 texture exceeds the 4096 limit; downsampled to 2880x1440`, and the Moon
      still renders with correct (if softer) relief.
- [ ] On any device whose `maxImageDimension2D` is below 5760 (some older Android GPUs), the same
      `[moon] … downsampled` line appears and the app renders.

## 7. Performance and stability

- [ ] Continuous orbiting for 60 s stays smooth at the display's refresh rate (FIFO-paced).
      Use Xcode's FPS gauge on iOS, Android GPU Inspector or `adb shell dumpsys gfxinfo` on Android.
- [ ] Memory is stable over 5 minutes of interaction (Xcode Memory gauge / Android Studio profiler);
      no growth per frame (autorelease pools drain on the Apple render thread).
- [ ] The device does not throttle or get hot within 5 minutes.

## 8. Optional deep validation

Run these when touching Vulkan calls, synchronisation, or shaders.

- **Metal API + shader validation (macOS/iOS).** Xcode scheme → Run → Diagnostics → enable
  *Metal API Validation* and *Shader Validation*. For desktop, set `MTL_DEBUG_LAYER=1` and
  `MTL_SHADER_VALIDATION=1` in the environment of `./gradlew :desktopApp:run`.
- **MoltenVK logging.** `MVK_CONFIG_LOG_LEVEL=4` (debug) prints every MoltenVK decision; `0` silences it.
- **Vulkan validation layers (Android 10+).** Install the Khronos validation layer from
  [KhronosGroup/Vulkan-ValidationLayers releases](https://github.com/KhronosGroup/Vulkan-ValidationLayers/releases)
  into an app or package on the device, then:
  ```sh
  adb shell settings put global enable_gpu_debug_layers 1
  adb shell settings put global gpu_debug_app com.roywatson.lunar
  adb shell settings put global gpu_debug_layers VK_LAYER_KHRONOS_validation
  adb shell settings put global gpu_debug_layer_app <package containing the layer>
  ```
  Expect zero validation errors in `adb logcat` across sections 1–5. Turn it off afterwards with
  `adb shell settings delete global enable_gpu_debug_layers`.
- **Vulkan validation layers (Linux desktop).** `sudo apt install vulkan-validationlayers`, then
  `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation ./gradlew :desktopApp:run`.
- **Vulkan validation layers (Windows desktop).** The LunarG SDK ships the Khronos layer; run
  `set VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation` before `gradlew :desktopApp:run` and expect
  zero validation errors on stderr across sections 1–5.

## Troubleshooting notes

- **Windows says Vulkan could not start, or that the native library could not be loaded.** The machine has no Vulkan driver
  (`vulkan-1.dll` missing, or `vulkaninfo --summary` lists no GPU). VMs usually expose only
  DirectX/OpenGL; test on real hardware with a current GPU driver.
- **Linux says the native library could not be loaded, or renders very slowly.** Check
  `vulkaninfo --summary` (package `vulkan-tools`). No devices: install `mesa-vulkan-drivers`.
  Only `llvmpipe` listed: that is lavapipe on the CPU (typical in VMs), so expect a low frame rate.
- **iOS app won't launch (icon pulses, then nothing), or `devicectl` launches hang.** iOS's
  `runningboardd` can wedge after a stuck debug session. A force restart (volume up, volume
  down, hold the top button) fixed it on an iPad Pro running iPadOS 26.6.
- **iOS shows an old behaviour after a native change.** Confirm the bundled library is current:
  the `libmoon_ios.a` inside `shared/build/classes/kotlin/<target>/main/cinterop/…/included/`
  must match `shared/build/native/<target>/libmoon_ios.a` (compare with `shasum`).

## Results log

| Date | Platform | Device | GPU | OS | Build | Sections passed | Notes |
|---|---|---|---|---|---|---|---|
| 2026-11-06 | macOS desktop | Mac (Mac17,16) | Apple M5 Pro | macOS 27.0.1 | pre-checklist | 1, 2, 3 (partial) | Ad-hoc check during development, not a full checklist run. |
| 2026-11-06 | iOS simulator | iPhone 18 Pro, iPad Pro 13" (M5) sims | Apple iOS simulator GPU | Xcode 27.2 simulators | pre-checklist | 1, 3 (iPad) | Ad-hoc; orbit and pan confirmed on the iPad simulator. |
| 2026-11-06 | iOS device | iPad Pro 12.9" 4th gen (iPad8,11) | Apple A12Z | iPadOS 26.6.2 | `db41e99` | 1, 3 (partial) | Ad-hoc; renders, orbit and corrected vertical pan confirmed. |
| 2026-11-07 | Android emulator | Pixel Tablet AVD (API 35) | llvmpipe (software Vulkan in the emulator image) | Android 15 | `d26b559` | 1, 3, 4 (rotation) | Ad hoc on the `cleanup` branch: renders in all four rotations (pre-rotation), shared gestures orbit, view kept across rotation and background/foreground. Not a physical device. |
| | Android device | | | | | | Not yet run on physical hardware since the shared-renderer refactor. |
| 2026-11-06 | Windows desktop | ASUS desktop | Intel UHD Graphics 770 (driver 32.0.101.7088) | Windows 11 Pro 10.0.26300 | `fdbd354` | 1 (partial) | Ad-hoc; `./gradlew :desktopApp:run` launched, rendered the Moon, and exited cleanly when closed. Built with Visual Studio's bundled CMake. |
| 2026-11-06 | Linux desktop | Ubuntu 24.04 VM | llvmpipe (Mesa 25.2.8, software) | Ubuntu 24.04.2 LTS | `24e05ba` + indentation fix | 0 (build/launch only) | `./gradlew :desktopApp:run` built `libdesktop_moon_renderer.so` and launched without crashing for 90s. No screenshot tooling in this VM, so image correctness (section 2) is unconfirmed; GPU is lavapipe (CPU), so expect a low frame rate even if correct. |
