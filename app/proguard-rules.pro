# ─────────────────────────────────────────────────────────
# Photoria ProGuard / R8 规则
# ─────────────────────────────────────────────────────────

# ── Kotlin 元数据：Compose 运行时依赖 Kotlin 反射元数据解析可组合函数 ──
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# ── Compose 运行时：保留 Composable 函数元数据与 Group 信息 ──
# Compose 编译器生成的 $$Composable 槽表与组键需保留，否则运行时重组崩溃。
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-dontwarn androidx.compose.**

# ── CameraX：内部使用反射构造 Camera2 兼容层 ──
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── 枚举：枚举的 values()/valueOf() 依赖反射 ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── 应用自身的 ViewModel / 数据类：保留字段以供 Compose 状态读取 ──
# StateFlow 内的 Map/List 用的是不可变快照，无需特殊保留。
-keep class com.photoria.backrooms.ui.viewmodel.** { *; }

# ── GLSL 资产通过 AssetManager 读取，与代码混淆无关，无需规则 ──

# ── MediaCodec / MediaMuxer：编码器在原生层，无 Java 反射，无需保留 ──
