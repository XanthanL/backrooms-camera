package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 黑白蒙太奇（Montage BW）滤镜。
 *
 * 效果特征：
 *   - 高对比度黑白（去色 + S 曲线增强）
 *   - 灰度色调分离（Posterize）产生海报化效果
 *   - 明暗对比增强（模拟分裂色调的层次感）
 *   - 暗角 + 胶片颗粒
 *
 * Shader uniform：
 *   - uTime：系统启动时间（秒），驱动颗粒动态
 *   - uResolution：渲染目标宽高
 */
class MontageBWFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/montage_bw.glsl"

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

    override fun getName(): String = "黑白蒙太奇"
}
