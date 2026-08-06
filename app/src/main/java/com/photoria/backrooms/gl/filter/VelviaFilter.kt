package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Fuji Velvia 50（风光反转片）滤镜。
 *
 * 效果特征：
 *   - 全局高饱和度（1.7 基准）
 *   - 蓝绿通道额外增强（蓝 ×1.3，绿 ×1.2）
 *   - 色相微调（绿向黄绿、蓝向青）
 *   - 强 S 曲线对比（gamma 1.15）
 *   - 干净无颗粒无暗角（反转片特性）
 *
 * 风光摄影师首选，填补"高饱和风光"空白。
 */
class VelviaFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/velvia.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "风光"
}
