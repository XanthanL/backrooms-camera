#version 300 es
precision mediump float;

// ─────────────────────────────────────────────────────────
// HDR 曝光融合（曝光加权 + 块级 SAD + 像素级双边加权）
//
// 输入：最多 8 帧 RGBA 纹理（已对齐） + 对应的 SAD 残差纹理
// 输出：曝光加权融合后的 RGBA
//
// 原理：对每个像素，曝光良好的中间灰度值权重最高，
// 过暗/过曝像素权重趋近 0。不同曝光帧互补 → 扩展动态范围。
//
// 双边加权（B2）：
//   1. 块级 SAD（对齐质量）：残差越小权重越高，抑制大范围失配重影
//   2. 像素级亮度相似度：与参考帧亮度差越小权重越高（高斯衰减），
//      在块内运动物体边界处精细降权，弥补块级 SAD 的粒度不足
//
// E1 ES 3.0 升级：
//   - sampler 数组 + 动态循环（for i < uFrameCount），帧数上限从 4 提升到 8
//   - 更多曝光帧 → 更细的 EV 采样 → 更平滑的动态范围扩展
//
// 权重 = 曝光权重 × 帧使能 × (1 - normalizedSad) × bilateralWeight
// bilateralWeight = exp(-lumaDiff² / (2σ²))，σ=0.2
// ─────────────────────────────────────────────────────────

// 双边滤波 σ=0.2，1/(2σ²)=12.5
#define BILATERAL_INV_2SIGMA2 12.5
#define MAX_FRAMES 8

uniform sampler2D uTex[MAX_FRAMES];     // RGBA 帧（uTex[0]=参考帧）
uniform sampler2D uSad[MAX_FRAMES - 1]; // SAD 残差（参考帧无，从帧 1 开始）

uniform highp int uFrameCount;          // 实际帧数 [2, MAX_FRAMES]
uniform float uWeights[MAX_FRAMES];

in vec2 vTexCoord;
out vec4 fragColor;

// Rec.601 亮度
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// 曝光权重：中间灰度（0.5）权重最高，过暗/过曝趋零
float exposureWeight(float l) {
    return max(0.0, 1.0 - 4.0 * (l - 0.5) * (l - 0.5));
}

// 像素级双边权重：与参考帧亮度差越小权重越高
float bilateralWeight(float lumaDiff) {
    return exp(-lumaDiff * lumaDiff * BILATERAL_INV_2SIGMA2);
}

void main() {
    vec4 c0 = texture(uTex[0], vTexCoord);
    float l0 = luma(c0.rgb);

    float w0 = uWeights[0] * exposureWeight(l0) * bilateralWeight(0.0);  // 参考帧 SAD=0
    vec3 acc = c0.rgb * w0;
    float wSum = w0;

    // 动态循环：ES 3.0 允许 uniform 作为循环上界
    for (int i = 1; i < uFrameCount; i++) {
        vec4 ci = texture(uTex[i], vTexCoord);
        float li = luma(ci.rgb);
        float sad = texture(uSad[i - 1], vTexCoord).b;
        float b = bilateralWeight(abs(li - l0));
        float w = uWeights[i] * exposureWeight(li) * (1.0 - sad) * b;
        acc += ci.rgb * w;
        wSum += w;
    }

    fragColor = vec4(acc / (wSum + 0.0001), 1.0);
}
