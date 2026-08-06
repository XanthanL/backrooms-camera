package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 日系小清新（Japanese Light）滤镜。
 *
 * 效果特征：
 *   - 低对比（黑场提升 + gamma 0.85）+ 轻微过曝
 *   - 高光偏青绿、阴影偏青的色调偏移
 *   - 5 采样低强度高斯模糊柔焦
 *   - 低饱和
 *
 * Instagram 日系滤镜风，填补"柔和小清新"空白。
 */
class JapaneseFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/japanese.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "清新"
}
