precision mediump float;

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 用户可调参数（默认值均为 1.0）
uniform float uContrast;     // 对比度
uniform float uPosterize;    // 灰度级数
uniform float uVignette;     // 暗角强度

varying vec2 vTexCoord;

// 公共工具函数（hash21 / valueNoise）
#include "shaders/fragment/common.glsl"

// ─────────────────────────────────────────────────────────
// 灰度转换（ITU-R BT.601 加权）
// ─────────────────────────────────────────────────────────
float toGrayscale(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

// ─────────────────────────────────────────────────────────
// 色调分离（Posterize）— 单通道标量版本
// ─────────────────────────────────────────────────────────
float posterizeScalar(float value, float levels) {
    return floor(value * levels + 0.5) / levels;
}

// ─────────────────────────────────────────────────────────
// 主函数
// ─────────────────────────────────────────────────────────
void main() {
    vec2 uv = vTexCoord;

    // ── 1. 采样原始纹理 ──
    vec4 texColor = texture2D(uTexture, uv);
    vec3 color = texColor.rgb;

    // ── 2. 转为灰度 ──
    float gray = toGrayscale(color);

    // ── 3. 高对比度增强（S 曲线近似，比彩色蒙太奇稍强）──
    // 对比度可调
    float contrastPower = mix(1.0, 1.7, uContrast);
    gray = pow(gray, contrastPower);
    // 线性拉伸恢复亮度范围
    float contrastStretch = mix(1.0, 1.2, uContrast);
    float contrastOffset = mix(0.0, 0.06, uContrast);
    gray = clamp(gray * contrastStretch - contrastOffset, 0.0, 1.0);

    // ── 4. 灰度色调分离（减少灰度层级 → 海报化效果）──
    // 灰度级数可调：uPosterize=1.0 对应 5 级，0.0 对应无分离
    float posterizeLevels = mix(64.0, 5.0, uPosterize);
    gray = posterizeScalar(gray, posterizeLevels);

    // ── 5. 分裂色调 → 黑白明暗对比增强 ──
    // 阴影区域微提亮，高光区域微压暗 → 增强层次分离感
    float shadowWeight = 1.0 - smoothstep(0.0, 0.45, gray);
    float highlightWeight = smoothstep(0.55, 1.0, gray);

    const float splitStrength = 0.08;
    gray += shadowWeight * splitStrength;
    gray -= highlightWeight * splitStrength * 0.5;

    // ── 6. 暗角效果 ──
    vec2 vigUV = uv - 0.5;
    float vigDist = dot(vigUV, vigUV);
    float vignette = 1.0 - smoothstep(0.18, 0.85, vigDist) * 0.7 * uVignette; // 暗角强度可调
    gray *= vignette;

    // ── 7. 胶片颗粒感（防止色带过于明显）──
    float grain = hash21(uv * uResolution + vec2(uTime * 23.0, uTime * 17.0));
    gray += (grain - 0.5) * 0.03;

    // 最终钳位
    gray = clamp(gray, 0.0, 1.0);

    // 输出灰度图像
    gl_FragColor = vec4(vec3(gray), texColor.a);
}
