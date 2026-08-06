// 日系小清新（Japanese Light）
// 低对比、轻微过曝、青绿色调、柔焦、淡颗粒。Instagram 日系滤镜风。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uSoftFocus;   // 柔焦强度，默认 1.0
uniform float uOverexpose;  // 过曝，默认 1.0
uniform float uTintShift;   // 青绿偏移，默认 1.0
uniform float uSaturation;  // 饱和度，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;
    vec3 color = texture2D(uTexture, uv).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 低对比（黑场提至 0.1，gamma 0.85）
    color = max(color - 0.0, 0.0);
    float blackLift = 0.1 * uOverexpose;
    color = mix(color, color + blackLift, 0.5);
    color = pow(clamp(color, 0.0, 1.0), vec3(0.85));

    // 2. 过曝：全局 +0.08 亮度
    color += 0.08 * uOverexpose;

    // 3. 青绿色调：高光偏青绿，阴影偏青
    float highlightMask = smoothstep(0.5, 1.0, luma);
    float shadowMask = smoothstep(0.5, 0.0, luma);
    color += vec3(-0.02, 0.03, 0.04) * uTintShift * highlightMask;
    color += vec3(0.0, 0.02, 0.05) * uTintShift * shadowMask;

    // 4. 柔焦：5 采样低强度高斯模糊 + 原图混合（0.15 混合比）
    vec2 texel = 1.0 / uResolution;
    vec3 blur = vec3(0.0);
    blur += texture2D(uTexture, uv + vec2(-texel.x, 0.0)).rgb;
    blur += texture2D(uTexture, uv + vec2(texel.x, 0.0)).rgb;
    blur += texture2D(uTexture, uv + vec2(0.0, -texel.y)).rgb;
    blur += texture2D(uTexture, uv + vec2(0.0, texel.y)).rgb;
    blur += color;
    blur *= 0.2;
    float softMix = 0.15 * uSoftFocus;
    color = mix(color, blur, softMix);

    // 5. 低饱和
    vec3 gray = vec3(dot(color, vec3(0.299, 0.587, 0.114)));
    float satFactor = mix(0.85, 1.0, 1.0 - uSaturation * 0.5);
    color = mix(gray, color, satFactor);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
