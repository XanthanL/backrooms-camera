package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * Leica Vivid（徕卡鲜艳）拍摄风格滤镜。
 *
 * 效果特征（参考小米徕卡 Vibrant / GCam AGC 徕卡 LUT）：
 *   - 高饱和度 + 红绿通道微增（德味浓郁）
 *   - S 曲线对比，暗部压深不发灰
 *   - 轻微暖调白平衡偏移
 *   - 轻微暗角（徕卡镜头氛围）
 */
class LeicaVividFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/leica_vivid.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "徕卡鲜艳"
}
