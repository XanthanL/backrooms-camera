// Gcam Night Sight（夜景模拟）
// 极暗场景提亮、噪点抑制、色彩还原、保持细节。
// 单帧模拟，非真实长曝光多帧合成。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uBrighten;    // 提亮强度，默认 1.0
uniform float uDenoise;     // 降噪，默认 1.0
uniform float uSaturation;  // 饱和度，默认 1.0
uniform float uContrast;    // 对比，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;
    vec2 texel = 1.0 / uResolution;

    // 5 采样十字（center + 4 邻域）
    vec3 color = texture2D(uTexture, uv).rgb;
    vec3 n = texture2D(uTexture, uv + vec2(0.0, texel.y)).rgb;
    vec3 s = texture2D(uTexture, uv + vec2(0.0, -texel.y)).rgb;
    vec3 e = texture2D(uTexture, uv + vec2(texel.x, 0.0)).rgb;
    vec3 w = texture2D(uTexture, uv + vec2(-texel.x, 0.0)).rgb;

    vec3 neighborAvg = (n + s + e + w) * 0.25;

    // 1. 噪点抑制：轻高斯（center 权重 0.5，邻域均值权重 0.5）
    vec3 denoised = mix(color, color * 0.5 + neighborAvg * 0.5, uDenoise);

    // 2. 全局提亮：gamma 0.6 强提亮暗部
    float brightenGamma = mix(1.0, 0.6, uBrighten);
    vec3 lifted = pow(clamp(denoised, 0.0, 1.0), vec3(brightenGamma));

    // 3. 色彩还原：提亮后饱和度补偿 1.15
    float newLuma = dot(lifted, vec3(0.299, 0.587, 0.114));
    vec3 gray = vec3(newLuma);
    float satFactor = mix(1.0, 1.15, uSaturation);
    lifted = mix(gray, lifted, satFactor);

    // 4. 局部对比保持细节（基于原始 center 与邻域差异）
    lifted += (color - neighborAvg) * 0.2 * uContrast;

    gl_FragColor = vec4(clamp(lifted, 0.0, 1.0), 1.0);
}
