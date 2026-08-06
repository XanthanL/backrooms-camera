package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 赛博朋克霓虹（Cyberpunk Neon）滤镜。
 *
 * 效果特征：
 *   - RGB 通道径向偏移色差（Chromatic Aberration）
 *   - 高亮区霓虹光晕（5 采样径向模糊，偏品红/青）
 *   - 暗部偏紫、高光偏青的色调偏移
 *   - 中高对比 + 饱和度增强
 *
 * Blade Runner 2049 风格，填补"科幻氛围"空白。
 */
class CyberpunkFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/cyberpunk.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "霓虹"
}
