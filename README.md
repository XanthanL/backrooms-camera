# Photoria · Backrooms Camera

> English | [简体中文](README.zh-CN.md)

A native Android camera app built on an OpenGL ES 3.0 real-time rendering pipeline + CameraX + Jetpack Compose. It transforms photos and videos into a variety of artistic styles, and integrates multi-frame pre-processing (HDR+ / Night Sight / smart scene recognition) with a retro VHS viewfinder overlay.

<p align="center">
  <!-- Screenshot placeholder: replace the link with a real screenshot under docs/screenshots/ after open-sourcing -->
  <img src="docs/screenshots/preview.png" alt="Photoria preview" width="280" />
</p>

---

## Features

- **23 real-time filters**: Backrooms / Film Grain / Montage / Emboss / Depth / LUT / Pixel / Teal-Orange / Cyberpunk / Portra / Japanese / Bleach Bypass / Glitch / Velvia / HP5 / Anamorphic / VHS / HDR+ / Night Sight / Leica Vivid / Yellow-Blue, etc. Each filter exposes multiple adjustable parameters and 3 presets (Default / Strong / Subtle).
- **Multi-frame pre-processing pipeline** (wired to UI, actually affects final output):
  - **HDR+**: Camera2 burst exposure bracketing (-2 / 0 / +2 EV) + Gaussian pyramid block-matching alignment + SAD residual + bilateral weighting + exposure-brightness fusion.
  - **Night Sight**: multi-frame alignment + temporal denoising (≈ √N noise reduction), mutually exclusive with HDR+.
  - **Smart scene recognition**: downsampled brightness statistics from idle viewfinder frames; automatically suggests Night Sight in low light.
- **Pro capture controls** (Camera2 Interop): ISO / shutter / exposure compensation / white balance (warm-cool + intensity) / manual exposure; auto-restored after front/back camera rebind.
- **Retro VHS viewfinder overlay**: vintage camcorder frame + REC red dot + timecode + date + battery icon. **The overlay is burned into the video file** (composited in the GL pipeline, WYSIWYG).
- **Recording**: single-pass EGL shared texture direct-rendered to MediaCodec H.264 + AAC audio, up to 3 minutes, saved via MediaStore to `DCIM/Photoria`.
- **Backrooms-themed UI**: fluorescent yellow / cream yellow / dark yellow-brown palette; dark base ensures viewfinder visibility.
- **APK slimming**: R8 + resource shrinking + ABI filter (arm64-v8a + armeabi-v7a), Release ≈ 4 MB.

## Tech Stack

| Area | Choice |
|------|--------|
| Language | Kotlin 1.9.22 |
| UI | Jetpack Compose (BOM 2024.02.00) + Material 3 |
| Camera | CameraX 1.4.2 (with Camera2 Interop) |
| Rendering | OpenGL ES 3.0 (GLSurfaceView + EGL shared Context + FBO filter chain) |
| Encoding | MediaCodec H.264 + AAC + MediaMuxer |
| Build | AGP 8.2.0 / Gradle 8.5 / JDK 17 |
| Platform | minSdk 24 / targetSdk 34 / compileSdk 34 |

## Build Guide

### Prerequisites

- JDK 17
- Android SDK (compileSdk 34)
- Android Studio Hedgehog (2023.1) or later

### Steps

```bash
# 1. Clone the repo
git clone https://github.com/<your-username>/backrooms-camera.git
cd backrooms-camera

# 2. Configure SDK path (not tracked by git)
# Windows
echo "sdk.dir=D\:\\AndroidSdk" > local.properties
# macOS / Linux
echo "sdk.dir=/Users/yourname/Library/Android/sdk" > local.properties

# 3. Debug build
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 4. Install to device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release Signing

The repository **does not** include any signing keys. To build a signed Release APK yourself:

1. Generate your own keystore:
   ```bash
   keytool -genkeypair -v -keystore my-release.keystore -alias my-key -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties` in the project root (already in `.gitignore`):
   ```properties
   storeFile=my-release.keystore
   storePassword=your-password
   keyAlias=my-key
   keyPassword=your-password
   ```
3. Run `./gradlew assembleRelease` to produce a signed APK.

### China Mirror (Optional)

`settings.gradle.kts` is configured with Alibaba Cloud Maven mirrors to accelerate dependency downloads in mainland China. Overseas developers can remove those mirror lines and fall back to the official `google()` / `mavenCentral()`.

## Project Structure

```
app/src/main/
├── java/com/photoria/backrooms/
│   ├── PhotoriaApp.kt                # Application
│   ├── MainActivity.kt              # Entry Activity
│   ├── camera/                       # CameraX management + multi-frame pre-processing
│   │   ├── CameraManager.kt
│   │   ├── PreProcessor.kt          # YUV queue + brightness stats
│   │   └── VideoRecorder.kt
│   ├── gl/                           # OpenGL rendering layer
│   │   ├── GLRenderer.kt             # Core renderer
│   │   ├── CameraGLSurfaceView.kt
│   │   ├── FilterChain.kt
│   │   └── filter/                   # 23 filter implementations
│   ├── encoder/                      # MediaCodec H.264/AAC + EGL sharing
│   ├── ui/                           # Compose UI
│   │   ├── screen/CameraScreen.kt
│   │   ├── components/
│   │   ├── theme/Theme.kt
│   │   └── viewmodel/CameraViewModel.kt
│   └── util/                         # Shader / Texture / persistence / image saving
└── assets/shaders/                   # GLSL (with #include preprocessing)
    ├── vertex/
    └── fragment/                     # 29 shaders (incl. multi-frame align / merge / downsample)
```

## Rendering Pipeline

```
CameraX Preview
  → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
    → OES→2D shader → FBO
      → FilterChain.apply() → filtered texture
        → Screen render (letterbox top-aligned)
        → Recording: filtered texture blit to encoder Surface (single pass)
```

The multi-frame HDR+ path additionally runs when the shutter is pressed:

```
Camera2 Burst([-2,0,+2] EV) → 3 YUV frames → GL_RED textures
  → Gaussian pyramid → align_blockmatch.glsl block matching → warp.glsl warping
  → hdr_merge.glsl (SAD + bilateral + exposure weighting) → FilterChain → save
```

## Known Issues

- **Crash on recording stop** (reproduced on Redmi K90 / HyperOS): suspected native crash (GL / MediaCodec / EGL driver layer), not catchable by Java try-catch. Four fix attempts (EOS draining / EglCore.release without terminate / unbindCurrent order / two-phase stopRecording) all failed. **Native crash stack must be captured first (`adb logcat` / MatLog / bugreport) before attempting fixes.** Community help in locating the issue is welcome.
- **Viewfinder overlay artifacts**: occasional stray patterns when the retro viewfinder is enabled, suspected missing `glClear` on the composite FBO or blend-state pollution.

## Contributing

Issues and PRs are welcome. Before opening a PR:

1. Ensure `./gradlew assembleDebug` and `./gradlew lint` pass.
2. Keep Kotlin code style (`kotlin.code.style=official`).
3. For GL changes, follow the shader conventions: max 9 texture samples per shader; new shaders should use `#include "shaders/fragment/common.glsl"` to reuse `hash21` / `valueNoise` / `luma`.

## License

[Apache License 2.0](LICENSE)

Copyright 2026 Photoria Contributors
