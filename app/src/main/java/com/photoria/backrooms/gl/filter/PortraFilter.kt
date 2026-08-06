package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Kodak Portra 400（人像胶片）滤镜。
 *
 * 效果特征：
 *   - 暖色偏 + 奶油色高光（B 通道高光区略增）
 *   - 柔和 S 曲线对比（黑场 0.04，gamma 0.9）
 *   - HSV 肤色检测，肤色区不降饱和度
 *   - 轻微径向暗角 + 细颗粒（中间调明显）
 *
 * 专业人像胶片之王，比现有「胶片」更精细、更现代。
 */
class PortraFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/portra.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        timeValue = System.nanoTime() / 1_000_000_000f
        setFloat(timeHandle, timeValue)
        applyAdjustableParams()
    }

    override fun getName(): String = "人像"
}
