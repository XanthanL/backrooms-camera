package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager

/**
 * 黄蓝（选择性色彩增强）滤镜。
 *
 * 效果特征：
 *   - 让画面中黄色部分更黄、蓝色部分更蓝（color pop 思路）
 *   - 基于 HSV 色相掩码：只在黄色区间（约 40°~75°）与蓝色区间（约 190°~255°）
 *     提升饱和度，肤色/绿色等其它色相基本不动
 *   - 低饱和像素跳过（避免放大噪点），色相范围与双色强度均可调
 *
 * 与全局饱和度滤镜的区别：不影响整幅画面，适合突出暖黄与冷蓝对比的构图。
 */
class YellowBlueFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    override fun getFragmentShaderPath(): String = "shaders/fragment/yellow_blue.glsl"

    override fun onSetup() {
        // 无自定义 uniform 句柄，可调参数通过 applyAdjustableParams 统一处理
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        applyAdjustableParams()
    }

    override fun getName(): String = "黄蓝"
}
