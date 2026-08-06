// 赛博朋克霓虹（Cyberpunk Neon）
// 高饱和品红/青对比、暗部偏紫、霓虹光晕、色差（Chromatic Aberration）。
// Blade Runner 2049 风。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uChromaticAberration; // 色差强度，默认 1.0
uniform float uNeonGlow;            // 霓虹光晕强度，默认 1.0
uniform float uColorShift;          // 品红/青偏移，默认 1.0
uniform float uContrast;            // 对比度，默认 1.0

varying vec2 vTexCoord;

// 径向缩放 uv（用于光晕采样）
vec2 radialOffset(vec2 uv, vec2 center, float amount) {
    vec2 dir = uv - center;
    return uv + dir * amount;
}

void main() {
    vec2 uv = vTexCoord;
    vec2 center = vec2(0.5);

    // 1. 色差：R/B 通道向相反方向径向偏移
    float ca = 0.004 * uChromaticAberration;
    float r = texture2D(uTexture, radialOffset(uv, center, -ca)).r;
    float g = texture2D(uTexture, uv).g;
    float b = texture2D(uTexture, radialOffset(uv, center, ca)).b;
    vec3 color = vec3(r, g, b);
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 2. 霓虹光晕：高亮区检测 + 5 采样径向模糊叠加
    float bright = smoothstep(0.75, 1.0, luma);
    vec3 glow = vec3(0.0);
    float glowAmt = uNeonGlow * 0.15 * bright;
    glow += texture2D(uTexture, radialOffset(uv, center, 0.02)).rgb;
    glow += texture2D(uTexture, radialOffset(uv, center, -0.02)).rgb;
    glow += texture2D(uTexture, radialOffset(uv, center, 0.04)).rgb;
    glow += texture2D(uTexture, radialOffset(uv, center, -0.04)).rgb;
    glow += texture2D(uTexture, uv).rgb;
    glow *= 0.2;
    // 光晕偏品红/青
    glow *= vec3(1.1, 0.8, 1.3);
    color += glow * glowAmt;

    // 3. 暗部偏紫，高光偏青
    float shadowMask = smoothstep(0.5, 0.0, luma);
    float highlightMask = smoothstep(0.5, 1.0, luma);
    color += vec3(0.05, 0.0, 0.10) * uColorShift * shadowMask;   // 暗部紫
    color += vec3(-0.05, 0.05, 0.10) * uColorShift * highlightMask; // 高光青

    // 4. 对比度（中高）
    float gamma = mix(1.0, 1.15, uContrast);
    color = pow(clamp(color, 0.0, 1.0), vec3(gamma));

    // 5. 饱和度增强
    vec3 gray = vec3(dot(color, vec3(0.299, 0.587, 0.114)));
    color = mix(gray, color, 1.25 * uContrast);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
