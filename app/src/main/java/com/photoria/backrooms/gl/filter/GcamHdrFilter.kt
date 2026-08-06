package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Gcam HDR+（计算摄影模拟）滤镜。
 *
 * 效果特征：
 *   - 局部色调映射：阴影提亮、高光压制
 *   - 清晰度：局部对比增强（全局应用）
 *   - Vibrance 自然饱和度：低饱和颜色增强更多，肤色保护
 *   - 轻微锐化（Unsharp Mask）
 *
 * 单帧模拟，非真实多帧合成。
 */
class GcamHdrFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/gcam_hdr.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "HDR+"
}
