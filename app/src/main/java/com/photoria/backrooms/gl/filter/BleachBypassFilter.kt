package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Bleach Bypass（漂白旁路）滤镜。
 *
 * 效果特征：
 *   - 低饱和（0.4，非黑白）
 *   - 强 S 曲线对比（gamma 1.25）+ 保留黑点
 *   - 轻微冷偏
 *
 * 《拯救大兵瑞恩》质感，冷峻纪实。
 */
class BleachBypassFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/bleach_bypass.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "漂白"
}
