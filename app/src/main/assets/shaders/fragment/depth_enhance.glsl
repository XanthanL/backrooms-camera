precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数（由上层 setAdjustableParam 注入，默认值在 main 内兜底）
uniform float uBlurStrength;   // 景深强度（0..2，默认 1.0）
uniform float uMicroAmount;    // 细节锐化（0..2，默认 1.0）
uniform float uVignette;       // 暗角强度（0..2，默认 1.0）

// ─────────────────────────────────────────────────────────
// 深度感增强 (Depth Enhance)
// 径向景深 + 微对比度增强 + 暗角中心提亮 → 立体感
// 优化：radialBlur 复用外部已采样的 center，总采样数 10 → 9。
// ─────────────────────────────────────────────────────────

// 简易径向模糊：模拟浅景深（边缘模糊、中心清晰）
// centerSample 为外部已采样的中心像素，避免重复采样
vec3 radialBlur(vec2 uv, sampler2D tex, float blurStrength, vec3 centerSample) {
    vec2 center = vec2(0.5);
    vec2 dir = uv - center;
    float dist = length(dir);

    // 模糊强度随距离中心递增
    float blurAmount = dist * blurStrength;
    vec2 blurDir = normalize(dir) * blurAmount / uResolution;

    // 4 采样点径向模糊 + 复用中心样本 = 5 点
    vec3 sum = centerSample;
    sum += texture2D(tex, uv + blurDir * 1.0).rgb;
    sum += texture2D(tex, uv + blurDir * 2.0).rgb;
    sum += texture2D(tex, uv + blurDir * 3.0).rgb;
    sum += texture2D(tex, uv + blurDir * 4.0).rgb;
    sum /= 5.0;

    return sum;
}

// 微对比度增强：局部 Laplacian 锐化（作用于传入的 color，而非重新采样原图）
vec3 microContrast(vec2 uv, sampler2D tex, vec2 texelSize, float amount, vec3 centerColor) {
    vec3 top    = texture2D(tex, clamp(uv + vec2(0.0, texelSize.y), 0.0, 1.0)).rgb;
    vec3 bottom = texture2D(tex, clamp(uv - vec2(0.0, texelSize.y), 0.0, 1.0)).rgb;
    vec3 left   = texture2D(tex, clamp(uv - vec2(texelSize.x, 0.0), 0.0, 1.0)).rgb;
    vec3 right  = texture2D(tex, clamp(uv + vec2(texelSize.x, 0.0), 0.0, 1.0)).rgb;

    vec3 laplacian = centerColor * 4.0 - top - bottom - left - right;
    return centerColor + laplacian * amount;
}

void main() {
    vec2 uv = vTexCoord;
    vec2 texelSize = 1.0 / uResolution;

    // 兜底默认值（uniform 未设置时为 0）
    float blurStrength = (uBlurStrength > 0.0001) ? uBlurStrength : 1.0;
    float microAmount  = (uMicroAmount  > 0.0001) ? uMicroAmount  : 1.0;
    float vignetteAmt  = (uVignette     > 0.0001) ? uVignette     : 1.0;

    // ── 1. 径向景深模糊 ──
    vec2 center = vec2(0.5);
    float distFromCenter = length(uv - center);
    float blurMask = smoothstep(0.15, 0.65, distFromCenter);

    vec3 sharp = texture2D(uTexture, uv).rgb;
    // 复用 sharp 作为径向模糊的中心采样，避免重复采样
    vec3 blurred = radialBlur(uv, uTexture, 2.5 * blurStrength, sharp);
    // 中心清晰，边缘模糊
    vec3 color = mix(sharp, blurred, blurMask * 0.7);

    // ── 2. 微对比度增强（修正：对景深混合后的 color 锐化，不再覆盖景深效果）──
    color = microContrast(uv, uTexture, texelSize, 0.18 * microAmount, color);

    // ── 3. 暗角 + 中心提亮 ──
    float centerBright = 1.0 + (1.0 - smoothstep(0.0, 0.5, distFromCenter)) * 0.15;
    float vignette = 1.0 - smoothstep(0.25, 0.85, distFromCenter) * 0.55 * vignetteAmt;
    color *= centerBright * vignette;

    // ── 4. 饱和度微调 ──
    float satMask = 1.0 - smoothstep(0.0, 0.6, distFromCenter) * 0.3;
    float gray = luma(color);
    color = mix(vec3(gray), color, satMask);

    // ── 5. 整体对比度提升 ──
    color = (color - 0.5) * 1.08 + 0.5;

    color = clamp(color, 0.0, 1.0);
    gl_FragColor = vec4(color, 1.0);
}
