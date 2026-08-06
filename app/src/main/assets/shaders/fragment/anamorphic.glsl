// Anamorphic 变形宽银幕
// 横向拉伸光斑（蓝色椭圆 flare）、轻微桶形畸变、椭圆暗角。J.J. Abrams 标志。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uFlareStrength;  // 光斑强度，默认 1.0
uniform float uFlareThreshold; // 光斑阈值，默认 1.0
uniform float uDistortion;     // 畸变，默认 1.0
uniform float uVignette;       // 暗角，默认 1.0

varying vec2 vTexCoord;

// 桶形畸变
vec2 barrelDistort(vec2 uv, float amount) {
    vec2 center = uv - 0.5;
    float r2 = dot(center, center);
    return uv + center * r2 * amount;
}

void main() {
    vec2 uv = vTexCoord;

    // 1. 轻微桶形畸变（强度比后室低）
    float distortAmt = -0.03 * uDistortion;
    uv = barrelDistort(uv, distortAmt);
    uv = clamp(uv, 0.0, 1.0);

    vec3 color = texture2D(uTexture, uv).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 2. 横向拉伸光斑：检测高亮像素，水平方向 5 采样高斯模糊叠加
    float threshold = mix(0.95, 0.75, uFlareThreshold);
    float bright = smoothstep(threshold, 1.0, luma);

    vec2 texel = 1.0 / uResolution;
    vec3 flare = vec3(0.0);
    flare += texture2D(uTexture, uv + vec2(-4.0, 0.0) * texel.x).rgb;
    flare += texture2D(uTexture, uv + vec2(-2.0, 0.0) * texel.x).rgb;
    flare += texture2D(uTexture, uv).rgb;
    flare += texture2D(uTexture, uv + vec2(2.0, 0.0) * texel.x).rgb;
    flare += texture2D(uTexture, uv + vec2(4.0, 0.0) * texel.x).rgb;
    flare *= 0.2;

    // 蓝色偏移光斑
    vec3 flareColor = flare * vec3(0.6, 0.8, 1.2);
    color += flareColor * bright * uFlareStrength * 0.6;

    // 3. 椭圆暗角（横向比纵向弱）
    vec2 vc = uv - 0.5;
    vc.x *= 0.85; // 横向衰减弱
    float vigDist = length(vc);
    float vignette = smoothstep(0.7, 0.3, vigDist);
    color *= mix(1.0, vignette, 0.3 * uVignette);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
