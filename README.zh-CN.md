# Photoria · 后室相机

> [English](README.md) | 简体中文

Android 原生相机 App，基于 OpenGL ES 3.0 实时渲染管线 + CameraX + Jetpack Compose，将照片与视频转化为多种艺术风格，并集成多帧前处理（HDR+ / 夜景 / 智能场景识别）与复古 VHS 取景器叠加。

<p align="center">
  <!-- 截图占位：开源后请把链接替换为 docs/screenshots/ 下的真实截图 -->
  <img src="docs/screenshots/preview.png" alt="Photoria 预览" width="280" />
</p>

---

## 特性

- **23 款实时滤镜**：后室 / 胶片 / 蒙太奇 / 浮雕 / 深度 / LUT / 像素 / 青橙 / 霓虹 / 人像 / 清新 / 漂白 / 故障 / 风光 / 黑白 / 变形 / VHS / HDR+ / 夜景 / 徕卡鲜艳 / 黄蓝等，每个滤镜支持多组可调参数与 3 个预设（默认 / 强效 / 克制）。
- **多帧前处理管线**（接入 UI 真正生效到成片）：
  - **HDR+**：Camera2 Burst 包围曝光（-2 / 0 / +2 EV）+ 高斯金字塔块匹配对齐 + SAD 残差 + 双边加权 + 曝光亮度融合。
  - **夜景模式**：多帧对齐 + 时域降噪（≈ √N 降噪量级），与 HDR+ 互斥。
  - **智能场景识别**：取景待机帧降采样亮度统计，暗光自动建议开启夜景。
- **专业采集控制**（Camera2 Interop）：ISO / 快门 / 曝光补偿 / 白平衡（暖冷 + 强度）/ 手动曝光；前后摄重绑后自动恢复。
- **复古 VHS 取景器叠加**：老式录像机边框 + REC 红点 + 时间码 + 日期 + 电池图标，**信息录进视频文件**（GL 管线合成，所见即所得）。
- **录像**：单 pass EGL 共享纹理直渲 MediaCodec H.264 + AAC 音频，最长 3 分钟，MediaStore 保存到 `DCIM/Photoria`。
- **后室主题 UI**：荧光黄 / 奶油黄 / 暗黄棕配色，深色基调保证取景器可视性。
- **APK 瘦身**：R8 + 资源裁剪 + ABI 过滤（arm64-v8a + armeabi-v7a），Release ≈ 4 MB。

## 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin 1.9.22 |
| UI | Jetpack Compose（BOM 2024.02.00）+ Material 3 |
| 相机 | CameraX 1.4.2（含 Camera2 Interop）|
| 渲染 | OpenGL ES 3.0（GLSurfaceView + EGL 共享 Context + FBO 滤镜链）|
| 编码 | MediaCodec H.264 + AAC + MediaMuxer |
| 构建 | AGP 8.2.0 / Gradle 8.5 / JDK 17 |
| 平台 | minSdk 24 / targetSdk 34 / compileSdk 34 |

## 构建指南

### 环境要求

- JDK 17
- Android SDK（compileSdk 34）
- Android Studio Hedgehog (2023.1) 或更高版本

### 步骤

```bash
# 1. 克隆仓库
git clone https://github.com/<your-username>/backrooms-camera.git
cd backrooms-camera

# 2. 配置 SDK 路径（不会入库）
# Windows
echo "sdk.dir=D\:\\AndroidSdk" > local.properties
# macOS / Linux
echo "sdk.dir=/Users/yourname/Library/Android/sdk" > local.properties

# 3. Debug 构建
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 4. 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release 签名

仓库**不包含**任何签名密钥。如需自行构建 Release 版本：

1. 生成自己的密钥库：
   ```bash
   keytool -genkeypair -v -keystore my-release.keystore -alias my-key -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 在项目根目录创建 `keystore.properties`（已被 `.gitignore` 忽略）：
   ```properties
   storeFile=my-release.keystore
   storePassword=你的口令
   keyAlias=my-key
   keyPassword=你的口令
   ```
3. `./gradlew assembleRelease` 即可生成签名 APK。

### 国内网络（可选）

`settings.gradle.kts` 已配置阿里云 Maven 镜像加速依赖下载。海外开发者可按需移除镜像行，回退到官方 `google()` / `mavenCentral()`。

## 项目结构

```
app/src/main/
├── java/com/photoria/backrooms/
│   ├── PhotoriaApp.kt                # Application
│   ├── MainActivity.kt              # 入口 Activity
│   ├── camera/                       # CameraX 管理 + 多帧前处理
│   │   ├── CameraManager.kt
│   │   ├── PreProcessor.kt          # YUV 队列 + 亮度统计
│   │   └── VideoRecorder.kt
│   ├── gl/                           # OpenGL 渲染层
│   │   ├── GLRenderer.kt             # 核心渲染器
│   │   ├── CameraGLSurfaceView.kt
│   │   ├── FilterChain.kt
│   │   └── filter/                   # 23 款滤镜实现
│   ├── encoder/                      # MediaCodec H.264/AAC + EGL 共享
│   ├── ui/                           # Compose UI
│   │   ├── screen/CameraScreen.kt
│   │   ├── components/
│   │   ├── theme/Theme.kt
│   │   └── viewmodel/CameraViewModel.kt
│   └── util/                         # Shader / Texture / 持久化 / 图片保存
└── assets/shaders/                   # GLSL（含 #include 预处理）
    ├── vertex/
    └── fragment/                     # 29 个 shader（含多帧对齐 / 融合 / 下采样）
```

## 渲染管线

```
CameraX Preview
  → SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
    → OES→2D shader → FBO
      → FilterChain.apply() → 滤镜纹理
        → 屏幕渲染（letterbox 顶对齐）
        → 录制：滤镜纹理 blit 到编码器 Surface（单 pass）
```

多帧 HDR+ 路径在快门按下时额外走：

```
Camera2 Burst([-2,0,+2] EV) → 3 帧 YUV → GL_RED 纹理
  → 高斯金字塔 → align_blockmatch.glsl 块匹配 → warp.glsl 变形
  → hdr_merge.glsl（SAD + 双边 + 曝光加权）→ FilterChain → 保存
```

## 已知问题

- **录像停止瞬间闪退**（红米 K90 / HyperOS 真机复现）：怀疑 native 崩溃（GL / MediaCodec / EGL 驱动层），Java try-catch 抓不到。已尝试 4 次修复（EOS 排空 / EglCore.release 不 terminate / unbindCurrent 顺序 / stopRecording 两阶段）均未解决，**需先抓 native 崩溃栈（`adb logcat` / MatLog / bugreport）再动手**，欢迎社区贡献定位思路。
- **取景器叠加杂纹**：开启复古取景器后偶发画面杂乱纹路，怀疑合成 FBO 每帧未 `glClear` 或 blend 状态污染。

## 贡献

欢迎 Issue 与 PR。提 PR 前请：

1. 跑通 `./gradlew assembleDebug` 与 `./gradlew lint`。
2. 保持 Kotlin 代码风格（`kotlin.code.style=official`）。
3. 涉及 GL 修改请遵守 Shader 约定：单 shader 纹理采样 ≤9 次；新增 shader 用 `#include "shaders/fragment/common.glsl"` 复用 `hash21` / `valueNoise` / `luma`。

## 协议

[Apache License 2.0](LICENSE)

Copyright 2026 Photoria Contributors
