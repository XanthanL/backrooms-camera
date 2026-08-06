precision mediump float;

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 用户可调参数（默认值均为 1.0）
uniform float uContrast;     // 对比度
uniform float uPosterize;    // 色调分离级数
uniform float uSplitTone;    // 分裂色调强度

varying vec2 vTexCoord;

// 公共工具函数（hash21 / valueNoise）
#include "shaders/fragment/common.glsl"

// ─────────────────────────────────────────────────────────
// 色调分离（Posterize）
// 将连续色调量化为离散层级
// ─────────────────────────────────────────────────────────
float posterizeChannel(float value, float levels) {
    return floor(value * levels + 0.5) / levels;
}

vec3 posterize(vec3 color, float levels) {
    return vec3(
        posterizeChannel(color.r, levels),
        posterizeChannel(color.g, levels),
        posterizeChannel(color.b, levels)
    );
}

// ─────────────────────────────────────────────────────────
// 主函数
// ─────────────────────────────────────────────────────────
void main() {
    vec2 uv = vTexCoord;

    // ── 1. 采样原始纹理 ──
    vec4 texColor = texture2D(uTexture, uv);
    vec3 color = texColor.rgb;

    // ── 2. 高对比度增强 ──
    // S 曲线近似：用幂函数拉伸中间调，对比度可调
    float contrastPower = mix(1.0, 1.6, uContrast);
    color = pow(color, vec3(contrastPower));
    // 再线性拉伸恢复亮度范围
    float contrastStretch = mix(1.0, 1.15, uContrast);
    float contrastOffset = mix(0.0, 0.05, uContrast);
    color = clamp(color * contrastStretch - contrastOffset, 0.0, 1.0);

    // ── 3. 饱和度增强 ──
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    const float saturationBoost = 1.45;
    color = mix(vec3(luminance), color, saturationBoost);

    // ── 4. 色调分离（Posterize）──
    // 色调分离级数可调：uPosterize=1.0 对应 6 级，0.0 对应无分离（大量级数）
    float posterizeLevels = mix(64.0, 6.0, uPosterize);
    color = posterize(color, posterizeLevels);

    // ── 5. 分裂色调（Split Toning）──
    // 计算当前亮度（在 posterize 之后）
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 阴影色调：暖橙/红色
    vec3 shadowTint = vec3(1.0, 0.45, 0.15); // 橙红色
    // 高光色调：冷蓝/青
    vec3 highlightTint = vec3(0.2, 0.6, 1.0); // 冷蓝色

    // 分裂色调的过渡曲线：用 smoothstep 控制阴影/高光区域
    float shadowWeight = 1.0 - smoothstep(0.0, 0.5, luma);  // 暗部权重
    float highlightWeight = smoothstep(0.5, 1.0, luma);      // 亮部权重

    // 应用分裂色调：用加法混合，强度可调
    float splitToneStrength = 0.22 * uSplitTone;
    color += shadowTint * shadowWeight * splitToneStrength;
    color += highlightTint * highlightWeight * splitToneStrength;

    // ── 6. 轻微色彩溢出（增强戏剧感）──
    // 在高饱和区域做轻微通道偏移
    color.r *= 1.0 + shadowWeight * 0.08;  // 阴影更暖
    color.b *= 1.0 + highlightWeight * 0.08; // 高光更冷

    // ── 7. 暗角效果 ──
    vec2 vigUV = uv - 0.5;
    float vigDist = dot(vigUV, vigUV);
    float vignette = 1.0 - smoothstep(0.20, 0.88, vigDist) * 0.65;
    color *= vignette;

    // ── 8. 极细微噪点（防止色带过于明显）──
    float grain = hash21(uv * uResolution + vec2(uTime * 23.0, uTime * 17.0));
    color += (grain - 0.5) * 0.025;

    // 最终钳位
    color = clamp(color, 0.0, 1.0);

    gl_FragColor = vec4(color, texColor.a);
}
