// Gcam HDR+（计算摄影模拟）
// 局部色调映射（阴影提亮 / 高光压制）、清晰度、Vibrance 自然饱和度、轻微锐化。
// 单帧模拟，非真实多帧合成。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uHdrStrength;  // HDR 强度，默认 1.0
uniform float uClarity;      // 清晰度（局部对比），默认 1.0
uniform float uVibrance;     // 自然饱和度，默认 1.0
uniform float uSharpen;      // 锐化，默认 1.0

varying vec2 vTexCoord;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

float skinMask(vec3 color) {
    vec3 hsv = rgb2hsv(color);
    float hue = hsv.x;
    float sat = hsv.y;
    return step(0.02, hue) * step(hue, 0.13) * step(0.2, sat);
}

void main() {
    vec2 uv = vTexCoord;
    vec2 texel = 1.0 / uResolution;

    // 5 采样十字（center + 4 邻域），center 复用
    vec3 color = texture2D(uTexture, uv).rgb;
    vec3 n = texture2D(uTexture, uv + vec2(0.0, texel.y)).rgb;
    vec3 s = texture2D(uTexture, uv + vec2(0.0, -texel.y)).rgb;
    vec3 e = texture2D(uTexture, uv + vec2(texel.x, 0.0)).rgb;
    vec3 w = texture2D(uTexture, uv + vec2(-texel.x, 0.0)).rgb;

    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 局部色调映射：阴影提亮 +0.15，高光压制 -0.1
    float shadowMask = smoothstep(0.5, 0.0, luma);
    float highlightMask = smoothstep(0.5, 1.0, luma);
    float toneMap = shadowMask * 0.15 - highlightMask * 0.10;
    color += toneMap * uHdrStrength;

    // 2. 清晰度：局部对比增强（全局应用）
    vec3 neighborAvg = (n + s + e + w) * 0.25;
    color += (color - neighborAvg) * 0.3 * uClarity;

    // 3. 轻微锐化（Unsharp Mask，复用邻域均值）
    color += (color - neighborAvg) * 0.4 * uSharpen;

    // 重新计算 luma 用于 vibrance
    luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 4. Vibrance：饱和度低的颜色增强更多，肤色保护
    vec3 gray = vec3(luma);
    float sat = length(color - gray); // 近似饱和度
    float satBoost = (1.0 - sat) * 0.4 * uVibrance;
    float skin = skinMask(color);
    satBoost *= (1.0 - skin * 0.8);
    color = mix(gray, color, 1.0 + satBoost);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
