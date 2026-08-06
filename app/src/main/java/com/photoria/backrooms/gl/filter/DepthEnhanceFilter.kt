package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 深度感增强滤镜。
 * 径向景深模糊 + 微对比度增强 + 暗角中心提亮，营造立体感。
 * 使用 assets/shaders/fragment/depth_enhance.glsl。
 */
class DepthEnhanceFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/depth_enhance.glsl"

    override fun getName(): String = "深度"

    override fun onSetup() {
        // 无额外自定义 uniform（使用基类的 uTime / uResolution）
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 应用用户可调参数（uBlurStrength / uMicroAmount / uVignette）
        applyAdjustableParams()
    }
}
