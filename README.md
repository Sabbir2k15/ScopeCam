# ScopeCam

Fresh LSPosed virtual camera module. Swap the camera feed in any app selected via LSPosed scope.

## Features (v1)
- **Camera1** (`android.hardware.Camera`) hooking via PreviewCallback wrapping
- **Camera2** (`android.hardware.camera2`) hooking via ImageReader frame pumping
- **Video sources**: local video file (looped), single image (looped), network stream (downloaded once, then looped)
- **Block on failure**: shows black instead of leaking the real camera
- Premium dark Compose UI

## How it works
1. Install the APK, enable in LSPosed Manager → Modules → ScopeCam
2. Select target apps in Scope → reboot
3. Open ScopeCam, pick a video/image/stream, tap Save
4. Target apps now receive the fake camera feed

Config: `/sdcard/DCIM/ScopeCam/config.json`

## Build
GitHub Actions builds the debug APK on push to main (see `.github/workflows/build.yml`).

## Structure
- `XposedInit.java` - module entry point
- `HookMain.java` - routes to camera engines
- `Camera1Hook.java` - Camera1 PreviewCallback wrapping
- `Camera2Hook.java` - Camera2 ImageReader frame pumping via ImageWriter
- `FrameProvider.java` - NV21 frame decoding (video/image/stream)
- `Config.java` - JSON config reader
- `ui/MainActivity.kt` - Compose settings UI
