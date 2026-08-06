package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Teal & Orange（好莱坞大片色）滤镜。
 *
 * 效果特征：
 *   - 阴影偏青蓝、高光偏橙暖的分裂色调
 *   - 肤色保护（HSV 检测橙红色相，降低青蓝偏移）
 *   - 中强 S 曲线对比
 *   - 阴影/高光区额外饱和度增强
 *
 * 与 LUT 滤镜互补：LUT 为固化青橙调色，本滤镜可实时调节双色强度与肤色保护。
 */
class TealOrangeFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/teal_orange.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "青橙"
}
