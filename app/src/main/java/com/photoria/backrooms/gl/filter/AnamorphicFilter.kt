package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Anamorphic 变形宽银幕滤镜。
 *
 * 效果特征：
 *   - 横向拉伸蓝色光斑（5 采样水平高斯模糊 + 蓝色偏移）
 *   - 轻微桶形畸变（强度比后室低）
 *   - 椭圆暗角（横向衰减弱于纵向）
 *
 * J.J. Abrams 标志镜头特效，填补"光学物理特效"空白。
 */
class AnamorphicFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/anamorphic.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "变形"
}
