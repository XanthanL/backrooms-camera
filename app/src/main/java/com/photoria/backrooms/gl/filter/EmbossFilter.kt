package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 浮雕滤镜。
 * 通过邻域像素差值模拟光照下的凹凸质感，结合灰度化与边缘增强。
 * 使用 assets/shaders/fragment/emboss.glsl。
 */
class EmbossFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/emboss.glsl"

    override fun getName(): String = "浮雕"

    override fun onSetup() {
        // 无额外自定义 uniform（使用基类的 uTime / uResolution）
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 应用用户可调参数（uEmbossStrength / uEdgeStrength / uGrayMix）
        applyAdjustableParams()
    }
}
