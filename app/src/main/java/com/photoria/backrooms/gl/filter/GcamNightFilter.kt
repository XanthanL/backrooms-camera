package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Gcam Night Sight（夜景模拟）滤镜。
 *
 * 效果特征：
 *   - 极暗场景提亮（gamma 0.6）
 *   - 噪点抑制（亮度通道轻高斯模糊）
 *   - 色彩还原（提亮后饱和度补偿）
 *   - 局部对比保持细节
 *
 * 单帧模拟，非真实长曝光多帧合成。
 */
class GcamNightFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/gcam_night.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "夜景"
}
