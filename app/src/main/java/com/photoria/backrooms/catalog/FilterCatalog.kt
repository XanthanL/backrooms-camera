package com.photoria.backrooms.catalog

import android.content.res.AssetManager
import com.photoria.backrooms.gl.filter.AnamorphicFilter
import com.photoria.backrooms.gl.filter.BackroomsFilter
import com.photoria.backrooms.gl.filter.BleachBypassFilter
import com.photoria.backrooms.gl.filter.CyberpunkFilter
import com.photoria.backrooms.gl.filter.DepthEnhanceFilter
import com.photoria.backrooms.gl.filter.EmbossFilter
import com.photoria.backrooms.gl.filter.FilmGrainFilter
import com.photoria.backrooms.gl.filter.Filter
import com.photoria.backrooms.gl.filter.GcamHdrFilter
import com.photoria.backrooms.gl.filter.GcamNightFilter
import com.photoria.backrooms.gl.filter.GlitchFilter
import com.photoria.backrooms.gl.filter.Hp5Filter
import com.photoria.backrooms.gl.filter.JapaneseFilter
import com.photoria.backrooms.gl.filter.LeicaVividFilter
import com.photoria.backrooms.gl.filter.LutFilter
import com.photoria.backrooms.gl.filter.MontageBWFilter
import com.photoria.backrooms.gl.filter.MontageFilter
import com.photoria.backrooms.gl.filter.PassthroughFilter
import com.photoria.backrooms.gl.filter.PixelFilter
import com.photoria.backrooms.gl.filter.PortraFilter
import com.photoria.backrooms.gl.filter.TealOrangeFilter
import com.photoria.backrooms.gl.filter.VelviaFilter
import com.photoria.backrooms.gl.filter.VhsFilter
import com.photoria.backrooms.gl.filter.YellowBlueFilter

/**
 * 滤镜参数定义：显示名称 → uniform 名 → 默认值 / 范围 / 步长
 */
data class FilterParamDef(
    val displayName: String,
    val uniformName: String,
    val defaultValue: Float = 1.0f,
    val min: Float = 0f,
    val max: Float = 2f,
    val step: Float = 0.01f
)

/**
 * 滤镜预设：名称 + 参数快照。
 */
data class FilterPreset(
    val name: String,
    val params: Map<String, Float>
)

/**
 * 滤镜分类。用于选择器上方的分类标签筛选。
 * 显示顺序 = 枚举声明顺序。
 */
enum class FilterCategory(val displayName: String) {
    ALL("全部"),
    BASIC("基础"),
    FILM("胶片"),
    CINEMA("电影"),
    EFFECT("特效"),
    GCAM("Gcam")
}

/**
 * 滤镜定义：名称 + 工厂 + 是否动画 + 可调参数 + 预设。
 *
 * @param animated true 表示 shader 依赖 uTime 产生随时间变化的效果
 *   （频闪/颗粒/撕裂/抖动），需连续渲染；false 为静态滤镜，
 *   可在 WHEN_DIRTY 按需渲染模式下工作（省电）。
 * @param category 选择器分类，用于筛选显示。默认 BASIC。
 */
data class FilterDef(
    val name: String,
    val factory: (AssetManager) -> Filter,
    val animated: Boolean = false,
    val category: FilterCategory = FilterCategory.BASIC,
    val paramDefs: List<FilterParamDef> = emptyList(),
    val presets: List<FilterPreset> = emptyList()
)

/**
 * 滤镜目录：全项目滤镜注册的唯一数据源。
 *
 * 索引 = 列表顺序（0..22）。GL 层创建实例、UI 层名称/参数/预设、
 * 渲染模式决策（animated）全部由此派生，杜绝多处手动同步导致的索引漂移。
 */
object FilterCatalog {

    val filters: List<FilterDef> = listOf(
        FilterDef("原画", { PassthroughFilter(it) }),
        FilterDef(
            "后室", { BackroomsFilter(it) }, animated = true, category = FilterCategory.FILM,
            paramDefs = listOf(
                // 后室默认强化效果：色偏/饱和/暗角/畸变 默认 2（max 4 的中间值），
                // 频闪默认 0.5（避免刺眼），噪点保持 1.0。上限统一拉到 4。
                FilterParamDef("色偏强度", "uColorShift", 2.0f, 0f, 4f),
                FilterParamDef("饱和度", "uSaturation", 2.0f, 0f, 4f),
                FilterParamDef("暗角强度", "uVignette", 2.0f, 0f, 4f),
                FilterParamDef("噪点强度", "uNoise", 1.0f, 0f, 4f),
                FilterParamDef("畸变强度", "uDistortion", 2.0f, 0f, 4f),
                FilterParamDef("频闪强度", "uFlicker", 0.5f, 0f, 4f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uColorShift" to 2.0f, "uSaturation" to 2.0f, "uVignette" to 2.0f,
                    "uNoise" to 1.0f, "uDistortion" to 2.0f, "uFlicker" to 0.5f
                )),
                FilterPreset("浓郁", mapOf(
                    "uColorShift" to 3.0f, "uSaturation" to 1.2f, "uVignette" to 3.2f,
                    "uNoise" to 2.6f, "uDistortion" to 2.4f, "uFlicker" to 1.4f
                )),
                FilterPreset("清淡", mapOf(
                    "uColorShift" to 1.0f, "uSaturation" to 2.4f, "uVignette" to 1.0f,
                    "uNoise" to 0.4f, "uDistortion" to 1.0f, "uFlicker" to 0.3f
                )),
            )
        ),
        FilterDef(
            "胶片", { FilmGrainFilter(it) }, animated = true, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("颗粒强度", "uGrainIntensity", 1.0f, 0f, 2f),
                FilterParamDef("褪色程度", "uFade", 1.0f, 0f, 2f),
                FilterParamDef("扫描线", "uScanline", 1.0f, 0f, 2f),
                FilterParamDef("暗角强度", "uVignette", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uGrainIntensity" to 1.0f, "uFade" to 1.0f, "uScanline" to 1.0f, "uVignette" to 1.0f
                )),
                FilterPreset("复古", mapOf(
                    "uGrainIntensity" to 1.6f, "uFade" to 1.5f, "uScanline" to 1.3f, "uVignette" to 1.4f
                )),
                FilterPreset("鲜艳", mapOf(
                    "uGrainIntensity" to 0.5f, "uFade" to 0.3f, "uScanline" to 0.4f, "uVignette" to 0.6f
                )),
            )
        ),
        FilterDef(
            "蒙太奇", { MontageFilter(it) }, animated = true, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("色调分离", "uPosterize", 1.0f, 0f, 2f),
                FilterParamDef("分裂色调", "uSplitTone", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uContrast" to 1.0f, "uPosterize" to 1.0f, "uSplitTone" to 1.0f
                )),
                FilterPreset("高对比", mapOf(
                    "uContrast" to 1.6f, "uPosterize" to 1.3f, "uSplitTone" to 1.2f
                )),
                FilterPreset("柔和", mapOf(
                    "uContrast" to 0.6f, "uPosterize" to 0.5f, "uSplitTone" to 0.7f
                )),
            )
        ),
        FilterDef(
            "黑白蒙太奇", { MontageBWFilter(it) }, animated = true, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                // 灰度级数：2..64，整数步进
                FilterParamDef("灰度级数", "uPosterize", 8f, 2f, 64f, 1f),
                FilterParamDef("暗角强度", "uVignette", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uContrast" to 1.0f, "uPosterize" to 8f, "uVignette" to 1.0f
                )),
                FilterPreset("高对比", mapOf(
                    "uContrast" to 1.6f, "uPosterize" to 4f, "uVignette" to 1.4f
                )),
                FilterPreset("柔和", mapOf(
                    "uContrast" to 0.6f, "uPosterize" to 16f, "uVignette" to 0.6f
                )),
            )
        ),
        FilterDef(
            "浮雕", { EmbossFilter(it) }, category = FilterCategory.EFFECT,
            paramDefs = listOf(
                FilterParamDef("浮雕强度", "uEmbossStrength", 1.0f, 0f, 2f),
                FilterParamDef("边缘增强", "uEdgeStrength", 1.0f, 0f, 2f),
                // 灰度混合：0..1
                FilterParamDef("灰度混合", "uGrayMix", 0.2f, 0f, 1f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uEmbossStrength" to 1.0f, "uEdgeStrength" to 1.0f, "uGrayMix" to 0.2f
                )),
                FilterPreset("强烈", mapOf(
                    "uEmbossStrength" to 1.8f, "uEdgeStrength" to 1.6f, "uGrayMix" to 0.1f
                )),
                FilterPreset("柔和", mapOf(
                    "uEmbossStrength" to 0.5f, "uEdgeStrength" to 0.6f, "uGrayMix" to 0.5f
                )),
            )
        ),
        FilterDef(
            "深度", { DepthEnhanceFilter(it) }, category = FilterCategory.EFFECT,
            paramDefs = listOf(
                FilterParamDef("景深强度", "uBlurStrength", 1.0f, 0f, 2f),
                FilterParamDef("细节锐化", "uMicroAmount", 1.0f, 0f, 2f),
                FilterParamDef("暗角强度", "uVignette", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uBlurStrength" to 1.0f, "uMicroAmount" to 1.0f, "uVignette" to 1.0f
                )),
                FilterPreset("强景深", mapOf(
                    "uBlurStrength" to 1.8f, "uMicroAmount" to 1.3f, "uVignette" to 1.4f
                )),
                FilterPreset("微弱", mapOf(
                    "uBlurStrength" to 0.4f, "uMicroAmount" to 0.6f, "uVignette" to 0.5f
                )),
            )
        ),
        FilterDef(
            "LUT", { LutFilter(it) },
            paramDefs = listOf(
                FilterParamDef("调色强度", "uLutIntensity", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf("uLutIntensity" to 1.0f)),
                FilterPreset("强烈", mapOf("uLutIntensity" to 1.6f)),
                FilterPreset("轻微", mapOf("uLutIntensity" to 0.5f)),
            )
        ),
        // ── P0 ──────────────────────────────────────────────
        FilterDef(
            "青橙", { TealOrangeFilter(it) }, category = FilterCategory.CINEMA,
            paramDefs = listOf(
                FilterParamDef("青色强度", "uTealStrength", 1.0f, 0f, 2f),
                FilterParamDef("橙色强度", "uOrangeStrength", 1.0f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("肤色保护", "uSkinProtect", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uTealStrength" to 1.0f, "uOrangeStrength" to 1.0f,
                    "uContrast" to 1.0f, "uSkinProtect" to 1.0f
                )),
                FilterPreset("浓郁", mapOf(
                    "uTealStrength" to 1.5f, "uOrangeStrength" to 1.4f,
                    "uContrast" to 1.3f, "uSkinProtect" to 1.0f
                )),
                FilterPreset("清淡", mapOf(
                    "uTealStrength" to 0.6f, "uOrangeStrength" to 0.6f,
                    "uContrast" to 0.8f, "uSkinProtect" to 1.2f
                )),
            )
        ),
        FilterDef(
            "霓虹", { CyberpunkFilter(it) }, category = FilterCategory.CINEMA,
            paramDefs = listOf(
                FilterParamDef("色差强度", "uChromaticAberration", 1.0f, 0f, 2f),
                FilterParamDef("霓虹光晕", "uNeonGlow", 1.0f, 0f, 2f),
                FilterParamDef("品青偏移", "uColorShift", 1.0f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uChromaticAberration" to 1.0f, "uNeonGlow" to 1.0f,
                    "uColorShift" to 1.0f, "uContrast" to 1.0f
                )),
                FilterPreset("强烈", mapOf(
                    "uChromaticAberration" to 1.6f, "uNeonGlow" to 1.5f,
                    "uColorShift" to 1.4f, "uContrast" to 1.3f
                )),
                FilterPreset("克制", mapOf(
                    "uChromaticAberration" to 0.5f, "uNeonGlow" to 0.6f,
                    "uColorShift" to 0.7f, "uContrast" to 1.0f
                )),
            )
        ),
        // ── P1 ──────────────────────────────────────────────
        FilterDef(
            "人像", { PortraFilter(it) }, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("暖偏程度", "uWarmShift", 1.0f, 0f, 2f),
                FilterParamDef("肤色保护", "uSkinProtect", 1.0f, 0f, 2f),
                FilterParamDef("颗粒强度", "uGrain", 1.0f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("暗角强度", "uVignette", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uWarmShift" to 1.0f, "uSkinProtect" to 1.0f, "uGrain" to 1.0f,
                    "uContrast" to 1.0f, "uVignette" to 1.0f
                )),
                FilterPreset("奶油", mapOf(
                    "uWarmShift" to 1.4f, "uSkinProtect" to 1.2f, "uGrain" to 0.6f,
                    "uContrast" to 0.8f, "uVignette" to 0.7f
                )),
                FilterPreset("复古", mapOf(
                    "uWarmShift" to 1.2f, "uSkinProtect" to 1.0f, "uGrain" to 1.5f,
                    "uContrast" to 1.2f, "uVignette" to 1.3f
                )),
            )
        ),
        FilterDef(
            "清新", { JapaneseFilter(it) }, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("柔焦强度", "uSoftFocus", 1.0f, 0f, 2f),
                FilterParamDef("过曝程度", "uOverexpose", 1.0f, 0f, 2f),
                FilterParamDef("青绿偏移", "uTintShift", 1.0f, 0f, 2f),
                FilterParamDef("饱和度", "uSaturation", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uSoftFocus" to 1.0f, "uOverexpose" to 1.0f,
                    "uTintShift" to 1.0f, "uSaturation" to 1.0f
                )),
                FilterPreset("淡雅", mapOf(
                    "uSoftFocus" to 1.3f, "uOverexpose" to 1.2f,
                    "uTintShift" to 0.8f, "uSaturation" to 0.8f
                )),
                FilterPreset("通透", mapOf(
                    "uSoftFocus" to 0.6f, "uOverexpose" to 0.8f,
                    "uTintShift" to 1.2f, "uSaturation" to 1.1f
                )),
            )
        ),
        // ── P2 ──────────────────────────────────────────────
        FilterDef(
            "漂白", { BleachBypassFilter(it) }, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("降饱和", "uDesaturate", 1.0f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("冷偏", "uCoolShift", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uDesaturate" to 1.0f, "uContrast" to 1.0f, "uCoolShift" to 1.0f
                )),
                FilterPreset("冷峻", mapOf(
                    "uDesaturate" to 1.4f, "uContrast" to 1.3f, "uCoolShift" to 1.4f
                )),
                FilterPreset("柔和", mapOf(
                    "uDesaturate" to 0.6f, "uContrast" to 0.8f, "uCoolShift" to 0.6f
                )),
            )
        ),
        FilterDef(
            "故障", { GlitchFilter(it) }, animated = true, category = FilterCategory.EFFECT,
            paramDefs = listOf(
                FilterParamDef("撕裂强度", "uTearStrength", 1.0f, 0f, 2f),
                FilterParamDef("RGB分离", "uRgbShift", 1.0f, 0f, 2f),
                FilterParamDef("像素错位", "uPixelGlitch", 1.0f, 0f, 2f),
                FilterParamDef("故障频率", "uGlitchFreq", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uTearStrength" to 1.0f, "uRgbShift" to 1.0f,
                    "uPixelGlitch" to 1.0f, "uGlitchFreq" to 1.0f
                )),
                FilterPreset("剧烈", mapOf(
                    "uTearStrength" to 1.6f, "uRgbShift" to 1.5f,
                    "uPixelGlitch" to 1.4f, "uGlitchFreq" to 1.6f
                )),
                FilterPreset("微弱", mapOf(
                    "uTearStrength" to 0.4f, "uRgbShift" to 0.5f,
                    "uPixelGlitch" to 0.3f, "uGlitchFreq" to 0.5f
                )),
            )
        ),
        // ── P3 ──────────────────────────────────────────────
        FilterDef(
            "风光", { VelviaFilter(it) }, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("饱和度", "uSaturation", 1.0f, 0f, 2f),
                FilterParamDef("蓝绿增强", "uBlueGreen", 1.0f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("色相偏移", "uHueShift", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uSaturation" to 1.0f, "uBlueGreen" to 1.0f,
                    "uContrast" to 1.0f, "uHueShift" to 1.0f
                )),
                FilterPreset("鲜艳", mapOf(
                    "uSaturation" to 1.4f, "uBlueGreen" to 1.3f,
                    "uContrast" to 1.2f, "uHueShift" to 1.1f
                )),
                FilterPreset("克制", mapOf(
                    "uSaturation" to 0.7f, "uBlueGreen" to 0.6f,
                    "uContrast" to 0.9f, "uHueShift" to 0.8f
                )),
            )
        ),
        FilterDef(
            "黑白", { Hp5Filter(it) }, animated = true, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("反差", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("颗粒", "uGrain", 1.0f, 0f, 2f),
                FilterParamDef("曝光补偿", "uExposure", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uContrast" to 1.0f, "uGrain" to 1.0f, "uExposure" to 1.0f
                )),
                FilterPreset("高反差", mapOf(
                    "uContrast" to 1.5f, "uGrain" to 1.2f, "uExposure" to 1.0f
                )),
                FilterPreset("柔和", mapOf(
                    "uContrast" to 0.7f, "uGrain" to 0.8f, "uExposure" to 1.1f
                )),
            )
        ),
        FilterDef(
            "变形", { AnamorphicFilter(it) }, category = FilterCategory.CINEMA,
            paramDefs = listOf(
                FilterParamDef("光斑强度", "uFlareStrength", 1.0f, 0f, 2f),
                FilterParamDef("光斑阈值", "uFlareThreshold", 1.0f, 0f, 2f),
                FilterParamDef("畸变", "uDistortion", 1.0f, 0f, 2f),
                FilterParamDef("暗角", "uVignette", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uFlareStrength" to 1.0f, "uFlareThreshold" to 1.0f,
                    "uDistortion" to 1.0f, "uVignette" to 1.0f
                )),
                FilterPreset("强烈", mapOf(
                    "uFlareStrength" to 1.6f, "uFlareThreshold" to 1.3f,
                    "uDistortion" to 1.3f, "uVignette" to 1.2f
                )),
                FilterPreset("克制", mapOf(
                    "uFlareStrength" to 0.5f, "uFlareThreshold" to 0.7f,
                    "uDistortion" to 0.6f, "uVignette" to 0.6f
                )),
            )
        ),
        FilterDef(
            "录像", { VhsFilter(it) }, animated = true, category = FilterCategory.EFFECT,
            paramDefs = listOf(
                FilterParamDef("色差", "uChromaticAberration", 1.0f, 0f, 2f),
                FilterParamDef("扫描线", "uScanline", 1.0f, 0f, 2f),
                FilterParamDef("噪点", "uNoise", 1.0f, 0f, 2f),
                FilterParamDef("抖动", "uJitter", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uChromaticAberration" to 1.0f, "uScanline" to 1.0f,
                    "uNoise" to 1.0f, "uJitter" to 1.0f
                )),
                FilterPreset("老旧", mapOf(
                    "uChromaticAberration" to 1.5f, "uScanline" to 1.4f,
                    "uNoise" to 1.5f, "uJitter" to 1.4f
                )),
                FilterPreset("轻微", mapOf(
                    "uChromaticAberration" to 0.5f, "uScanline" to 0.6f,
                    "uNoise" to 0.5f, "uJitter" to 0.5f
                )),
            )
        ),
        FilterDef(
            "HDR+", { GcamHdrFilter(it) }, category = FilterCategory.GCAM,
            paramDefs = listOf(
                FilterParamDef("HDR强度", "uHdrStrength", 1.0f, 0f, 2f),
                FilterParamDef("清晰度", "uClarity", 1.0f, 0f, 2f),
                FilterParamDef("自然饱和度", "uVibrance", 1.0f, 0f, 2f),
                FilterParamDef("锐化", "uSharpen", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uHdrStrength" to 1.0f, "uClarity" to 1.0f,
                    "uVibrance" to 1.0f, "uSharpen" to 1.0f
                )),
                FilterPreset("强效", mapOf(
                    "uHdrStrength" to 1.5f, "uClarity" to 1.3f,
                    "uVibrance" to 1.2f, "uSharpen" to 1.3f
                )),
                FilterPreset("自然", mapOf(
                    "uHdrStrength" to 0.6f, "uClarity" to 0.7f,
                    "uVibrance" to 0.8f, "uSharpen" to 0.6f
                )),
            )
        ),
        FilterDef(
            "夜景", { GcamNightFilter(it) }, category = FilterCategory.GCAM,
            paramDefs = listOf(
                FilterParamDef("提亮强度", "uBrighten", 1.0f, 0f, 2f),
                FilterParamDef("降噪", "uDenoise", 1.0f, 0f, 2f),
                FilterParamDef("饱和度", "uSaturation", 1.0f, 0f, 2f),
                FilterParamDef("对比", "uContrast", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uBrighten" to 1.0f, "uDenoise" to 1.0f,
                    "uSaturation" to 1.0f, "uContrast" to 1.0f
                )),
                FilterPreset("强提亮", mapOf(
                    "uBrighten" to 1.5f, "uDenoise" to 1.3f,
                    "uSaturation" to 1.2f, "uContrast" to 0.9f
                )),
                FilterPreset("克制", mapOf(
                    "uBrighten" to 0.6f, "uDenoise" to 0.7f,
                    "uSaturation" to 0.9f, "uContrast" to 1.1f
                )),
            )
        ),
        // ── 徕卡 ──────────────────────────────────────────────
        FilterDef(
            "徕卡鲜艳", { LeicaVividFilter(it) }, category = FilterCategory.FILM,
            paramDefs = listOf(
                FilterParamDef("饱和度", "uSaturation", 1.0f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.0f, 0f, 2f),
                FilterParamDef("暖调", "uWarmth", 1.0f, 0f, 2f),
                FilterParamDef("暗角", "uVignette", 1.0f, 0f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uSaturation" to 1.0f, "uContrast" to 1.0f,
                    "uWarmth" to 1.0f, "uVignette" to 1.0f
                )),
                FilterPreset("浓郁", mapOf(
                    "uSaturation" to 1.5f, "uContrast" to 1.4f,
                    "uWarmth" to 1.3f, "uVignette" to 1.2f
                )),
                FilterPreset("清淡", mapOf(
                    "uSaturation" to 0.6f, "uContrast" to 0.7f,
                    "uWarmth" to 0.8f, "uVignette" to 0.5f
                )),
            )
        ),
        // ── 黄蓝（选择性色彩增强）──────────────────────────────
        FilterDef(
            "黄蓝", { YellowBlueFilter(it) }, category = FilterCategory.CINEMA,
            paramDefs = listOf(
                FilterParamDef("黄色增强", "uYellowStrength", 1.2f, 0f, 3f),
                FilterParamDef("蓝色增强", "uBlueStrength", 1.2f, 0f, 3f),
                FilterParamDef("色相范围", "uRange", 1.0f, 0.5f, 2f),
            ),
            presets = listOf(
                FilterPreset("默认", mapOf(
                    "uYellowStrength" to 1.2f, "uBlueStrength" to 1.2f, "uRange" to 1.0f
                )),
                FilterPreset("强烈", mapOf(
                    "uYellowStrength" to 2.2f, "uBlueStrength" to 2.2f, "uRange" to 1.3f
                )),
                FilterPreset("柔和", mapOf(
                    "uYellowStrength" to 0.6f, "uBlueStrength" to 0.6f, "uRange" to 0.8f
                )),
            ),
        ),
        // ── 像素（Pixel Art）──────────────────────────────────
        // 通过 uPixelSize 控制像素块边长；预设对应高/中/低三档像素分辨率。
        FilterDef(
            "像素", { PixelFilter(it) }, category = FilterCategory.EFFECT,
            paramDefs = listOf(
                // 像素块边长（像素）：越大越马赛克。整数步进。
                FilterParamDef("像素块大小", "uPixelSize", 8f, 2f, 48f, 1f),
                // 色彩量化级数：越少越接近 8-bit 调色板。整数步进。
                FilterParamDef("色彩级数", "uPosterizeLevels", 8f, 2f, 32f, 1f),
                FilterParamDef("饱和度", "uSaturation", 1.2f, 0f, 2f),
                FilterParamDef("对比度", "uContrast", 1.1f, 0f, 2f),
            ),
            presets = listOf(
                // 低分辨率：大色块 + 强量化 → 强烈马赛克
                FilterPreset("低分辨率", mapOf(
                    "uPixelSize" to 32f, "uPosterizeLevels" to 6f,
                    "uSaturation" to 1.3f, "uContrast" to 1.2f
                )),
                // 中分辨率：经典像素风
                FilterPreset("中分辨率", mapOf(
                    "uPixelSize" to 8f, "uPosterizeLevels" to 10f,
                    "uSaturation" to 1.2f, "uContrast" to 1.1f
                )),
                // 高分辨率：小色块 + 轻量化 → 细腻像素
                FilterPreset("高分辨率", mapOf(
                    "uPixelSize" to 3f, "uPosterizeLevels" to 16f,
                    "uSaturation" to 1.1f, "uContrast" to 1.1f
                )),
            )
        ),
    )

    /** 滤镜显示名称列表（索引 = 列表位置） */
    val names: List<String> = filters.map { it.name }

    /**
     * 按索引创建滤镜实例。
     * 越界索引回退到索引 0（原画），保证任何脏数据都不会导致渲染失败。
     */
    fun create(index: Int, assets: AssetManager): Filter =
        filters.getOrElse(index) { filters[0] }.factory(assets)

    /** 查询滤镜的可调参数定义（按名称，未知名称返回空表） */
    fun paramDefs(name: String): List<FilterParamDef> =
        filters.firstOrNull { it.name == name }?.paramDefs ?: emptyList()

    /** 查询滤镜的预设列表（按名称，未知名称返回空表） */
    fun presets(name: String): List<FilterPreset> =
        filters.firstOrNull { it.name == name }?.presets ?: emptyList()

    /** 该索引的滤镜是否需要连续渲染（动画滤镜） */
    fun isAnimated(index: Int): Boolean = filters.getOrElse(index) { filters[0] }.animated

    /** 指定分类下的滤镜索引列表（ALL 返回全部）。用于选择器筛选显示。 */
    fun indicesByCategory(category: FilterCategory): List<Int> =
        filters.mapIndexedNotNull { idx, def ->
            if (category == FilterCategory.ALL || def.category == category) idx else null
        }
}
