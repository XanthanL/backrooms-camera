package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * VHS 录像带滤镜。
 *
 * 效果特征：
 *   - 画面水平抖动（基于 uTime，幅度 0.003）
 *   - R/B 通道水平偏移色差（模拟磁头对不准）
 *   - 移动扫描线 + 粗颗粒噪点 + 偶发水平噪声带
 *   - 左右边缘水平模糊
 *
 * 与「后室」区分：强化色差和抖动，去除黄调和频闪。
 */
class VhsFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/vhs.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        timeValue = System.nanoTime() / 1_000_000_000f
        setFloat(timeHandle, timeValue)
        applyAdjustableParams()
    }

    override fun getName(): String = "录像"
}
