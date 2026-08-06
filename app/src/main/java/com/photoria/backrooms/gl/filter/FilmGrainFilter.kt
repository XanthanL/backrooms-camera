package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 胶片（Film Grain）滤镜。
 *
 * 效果特征：
 *   - 褪色效果（Lifted Blacks）
 *   - 暖色偏（老胶片色调）
 *   - 动态银盐颗粒噪点
 *   - 高光光晕（Bloom 近似）
 *   - 暗角 + 水平扫描线
 *
 * Shader uniform：
 *   - uTime：系统启动时间（秒），驱动胶片颗粒动态跳动
 *   - uResolution：渲染目标宽高（用于颗粒大小和扫描线计算）
 */
class FilmGrainFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/film_grain.glsl"

    override fun onSetup() {
        // 无自定义 uniform，uTime / uResolution 由基类自动处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 更新时间值，驱动胶片颗粒动态效果
        timeValue = System.nanoTime() / 1_000_000_000f
        // 立即更新 uniform（覆盖基类在 apply() 中设置的旧值）
        setFloat(timeHandle, timeValue)
        // 应用用户可调参数
        applyAdjustableParams()
    }

    override fun getName(): String = "胶片"
}
