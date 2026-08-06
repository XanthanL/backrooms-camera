precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数（由上层 setAdjustableParam 注入，默认值在 main 内兜底）
uniform float uPixelSize;        // 像素块边长（像素），2..48，默认 8（中档）
uniform float uPosterizeLevels;  // 色彩量化级数（每通道），2..32，默认 8
uniform float uSaturation;       // 饱和度，0..2，默认 1.2
uniform float uContrast;         // 对比度，0..2，默认 1.1

varying vec2 vTexCoord;

// ─────────────────────────────────────────────────────────
// 像素风格（Pixel Art）
// 将画面量化为正方形色块 + 色彩层级分离，模拟 8/16-bit 复古游戏画风。
// 仅 1 次纹理采样，满足 ≤5 sample 性能约束。
// ─────────────────────────────────────────────────────────

// 色调分离：将每通道连续值量化为 levels 级
vec3 posterize(vec3 c, float levels) {
    return floor(c * levels + 0.5) / levels;
}

void main() {
    // 兜底默认值（uniform 未设置时为 0）
    float pixelSize = (uPixelSize > 1.5)        ? uPixelSize       : 8.0;
    float levels    = (uPosterizeLevels > 1.5)  ? uPosterizeLevels : 8.0;
    float sat       = (uSaturation > 0.0001)    ? uSaturation      : 1.2;
    float con       = (uContrast   > 0.0001)    ? uContrast        : 1.1;

    // ── 1. 像素化：将 UV 量化到块中心 ──
    // block = 像素块在 UV 空间的尺寸（横纵分别除以分辨率，保证块为正方形）
    vec2 block = vec2(pixelSize) / uResolution;
    vec2 quantizedUV = (floor(vTexCoord / block) + 0.5) * block;
    quantizedUV = clamp(quantizedUV, vec2(0.0), vec2(1.0));

    // 单次采样：取块中心颜色作为整块代表色
    vec3 color = texture2D(uTexture, quantizedUV).rgb;

    // ── 2. 色彩量化（Posterize）──
    color = posterize(color, levels);

    // ── 3. 对比度（绕中灰 0.5 拉伸）──
    color = (color - 0.5) * con + 0.5;

    // ── 4. 饱和度（绕亮度混合）──
    float l = luma(color);
    color = mix(vec3(l), color, sat);

    color = clamp(color, 0.0, 1.0);
    gl_FragColor = vec4(color, 1.0);
}
