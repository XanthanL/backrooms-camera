package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Ilford HP5 Plus（黑白胶片）滤镜。
 *
 * 效果特征：
 *   - BT.601 灰度转换
 *   - 曝光宽容度（黑场 0.03、高光 0.95 保留细节）
 *   - 可调 S 曲线反差（模拟黄绿红滤光镜）
 *   - Mono 颗粒（仅亮度通道，比彩色颗粒更明显）
 *
 * 纯粹黑白胶片质感，无色调分离，与「黑白蒙太奇」区分。
 */
class Hp5Filter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/hp5.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        timeValue = System.nanoTime() / 1_000_000_000f
        setFloat(timeHandle, timeValue)
        applyAdjustableParams()
    }

    override fun getName(): String = "黑白"
}
