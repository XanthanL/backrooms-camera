package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 像素风格滤镜。
 *
 * 效果特征：
 *   - 像素化（马赛克）：将画面量化为正方形色块，每块取中心色作为代表色
 *   - 色彩量化（Posterize）：减少每通道色彩层级，模拟 8/16-bit 复古游戏画风
 *   - 饱和度 / 对比度微调，让色块更鲜明
 *
 * 通过 uPixelSize 控制像素块边长，提供「高 / 中 / 低」三档分辨率预设：
 *   - 低分辨率：大色块（32px），强量化 → 强烈马赛克
 *   - 中分辨率：中色块（8px），中度量化 → 经典像素风
 *   - 高分辨率：小色块（3px），轻量化 → 细腻像素
 *
 * 静态滤镜（非 animated），可在 WHEN_DIRTY 按需渲染模式下工作。
 */
class PixelFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/pixel.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "像素"
}
