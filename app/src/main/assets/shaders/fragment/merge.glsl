#version 300 es
precision mediump float;

// ─────────────────────────────────────────────────────────
// 多帧双边加权融合（块级 SAD + 像素级亮度相似度）
//
// 输入：最多 8 帧 RGBA 纹理（已对齐） + 对应的 SAD 残差纹理
// 输出：加权平均后的 RGBA
//
// 降噪原理：对 N 帧静态场景取平均，随机噪声方差降为 1/N。
//
// 双边加权（B2）：
//   1. 块级 SAD（对齐质量）：16×16 块的残差越小权重越高，抑制大范围失配
//   2. 像素级亮度相似度：与参考帧亮度差越小权重越高（高斯衰减），
//      在块内运动物体边界处精细降权，弥补块级 SAD 的粒度不足
//
// E1 ES 3.0 升级：
//   - sampler 数组 + 动态循环（for i < uFrameCount），帧数上限从 4 提升到 8
//   - 8 帧 RGBA + 7 SAD = 15 纹理单元，ES 3.0 保证 ≥16
//
// 权重 = 帧使能 × (1 - normalizedSad) × bilateralWeight
// bilateralWeight = exp(-lumaDiff² / (2σ²))，σ=0.2
// ─────────────────────────────────────────────────────────

// 双边滤波 σ=0.2，1/(2σ²)=12.5
#define BILATERAL_INV_2SIGMA2 12.5
#define MAX_FRAMES 8

uniform sampler2D uTex[MAX_FRAMES];     // RGBA 帧（uTex[0]=参考帧）
uniform sampler2D uSad[MAX_FRAMES - 1]; // SAD 残差（参考帧无，从帧 1 开始）

// 帧使能权重（不足 8 帧时多余分量归零）
uniform highp int uFrameCount;          // 实际帧数 [2, MAX_FRAMES]
uniform float uWeights[MAX_FRAMES];

in vec2 vTexCoord;
out vec4 fragColor;

// Rec.601 亮度
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// 像素级双边权重：与参考帧亮度差越小权重越高
float bilateralWeight(float lumaDiff) {
    return exp(-lumaDiff * lumaDiff * BILATERAL_INV_2SIGMA2);
}

void main() {
    vec4 c0 = texture(uTex[0], vTexCoord);
    float l0 = luma(c0.rgb);

    float w0 = uWeights[0] * bilateralWeight(0.0);  // 参考帧 SAD=0
    vec3 acc = c0.rgb * w0;
    float wSum = w0;

    // 动态循环：ES 3.0 允许 uniform 作为循环上界
    for (int i = 1; i < uFrameCount; i++) {
        vec4 ci = texture(uTex[i], vTexCoord);
        float sad = texture(uSad[i - 1], vTexCoord).b;
        float b = bilateralWeight(abs(luma(ci.rgb) - l0));
        float w = uWeights[i] * (1.0 - sad) * b;
        acc += ci.rgb * w;
        wSum += w;
    }

    fragColor = vec4(acc / (wSum + 0.0001), 1.0);
}
