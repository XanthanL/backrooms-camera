package com.photoria.backrooms.gl

/** 斑马纹（曝光警告）模式。序号即 shader 里的 uZebraMode，勿调整顺序。 */
enum class ZebraMode(val display: String) {
    /** 关闭 */
    OFF("关"),

    /** 仅标记过曝（白条纹） */
    HIGHLIGHT("过曝"),

    /** 过曝（白）+ 欠曝（青）都标记 */
    HIGH_AND_LOW("过曝+欠曝")
}

/**
 * 取景辅助叠加配置。
 *
 * 这些开关只影响取景器画面，绝不写入滤镜输出纹理，
 * 因此拍照与录像得到的是干净的（仅含滤镜强度的）图像。
 *
 * @param zebra 斑马纹模式
 * @param peaking 峰值对焦（合焦边缘荧光黄高亮）
 * @param peakingThreshold 峰值对焦灵敏度，边缘强度阈值 0..1，越小越灵敏
 * @param histogram 实时直方图（需要读回像素，故也要求非直通渲染路径）
 */
data class ProOverlayConfig(
    val zebra: ZebraMode = ZebraMode.OFF,
    val peaking: Boolean = false,
    val peakingThreshold: Float = 0.25f,
    val histogram: Boolean = false
) {
    /** 是否需要 GPU 叠加 pass（斑马纹 / 峰值对焦） */
    val active: Boolean
        get() = zebra != ZebraMode.OFF || peaking

    /**
     * 是否需要一张普通 2D 纹理。
     *
     * 原画滤镜下渲染器本可把 OES 纹理直接画到屏幕（省两个 pass），
     * 但 OES 纹理无法被 sampler2D shader 采样，叠加与直方图都需要 2D 纹理，
     * 因此该标志用于关闭直通优化。
     */
    val needsTexture: Boolean
        get() = active || histogram
}
