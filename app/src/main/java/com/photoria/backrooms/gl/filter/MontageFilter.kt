package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 蒙太奇（Montage）滤镜。
 *
 * 效果特征：
 *   - 高对比度增强（S 曲线近似）
 *   - 饱和度增强
 *   - 色调分离（Posterize）产生色带效果
 *   - 分裂色调（Split Toning）：阴影暖橙 / 高光冷蓝
 *   - 暗角 + 轻微噪点
 *
 * Shader uniform：
 *   - uTime：系统启动时间（秒），驱动噪点动态
 *   - uResolution：渲染目标宽高
 */
class MontageFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/montage.glsl"

    override fun onSetup() {
        // 无自定义 uniform，uTime / uResolution 由基类自动处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 更新时间值，驱动噪点动态效果
        timeValue = System.nanoTime() / 1_000_000_000f
        // 立即更新 uniform（覆盖基类在 apply() 中设置的旧值）
        setFloat(timeHandle, timeValue)
        // 应用用户可调参数
        applyAdjustableParams()
    }

    override fun getName(): String = "蒙太奇"
}
