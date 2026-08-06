package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Glitch 故障艺术滤镜。
 *
 * 效果特征：
 *   - 基于 uv.y 分段的画面撕裂（hash21 控制随机性）
 *   - RGB 通道分离（R 左移、B 右移）
 *   - 低概率 8×8 像素块错位
 *   - uTime 控制故障强度周期性变化（偶发强故障）
 *   - 偶发品红色块叠加
 *
 * 数字故障美学，填补"前卫艺术"空白。
 */
class GlitchFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/glitch.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        timeValue = System.nanoTime() / 1_000_000_000f
        setFloat(timeHandle, timeValue)
        applyAdjustableParams()
    }

    override fun getName(): String = "故障"
}
