package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 后室（Backrooms）滤镜。
 * 黄色调偏移 + 荧光灯频闪 + 桶形畸变 + 暗角 + 监控噪点。
 * 使用 assets/shaders/fragment/backrooms.glsl。
 */
class BackroomsFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/backrooms.glsl"

    override fun getName(): String = "后室"

    override fun onSetup() {
        // 无额外自定义 uniform（使用基类的 uTime / uResolution）
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 更新时间值，驱动荧光灯频闪动画
        timeValue = System.nanoTime() / 1_000_000_000f
        // 立即更新 uniform（覆盖基类在 apply() 中设置的旧值）
        setFloat(timeHandle, timeValue)
        // 应用用户可调参数
        applyAdjustableParams()
    }
}
