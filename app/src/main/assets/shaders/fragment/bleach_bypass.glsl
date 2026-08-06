// Bleach Bypass（漂白旁路）
// 低饱和 + 高对比 + 保留黑点，类似《拯救大兵瑞恩》质感。冷峻、纪实。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uDesaturate; // 降饱和程度，默认 1.0
uniform float uContrast;   // 对比度，默认 1.0
uniform float uCoolShift;  // 冷偏，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 低饱和（全局 0.4，非黑白）
    float satFactor = mix(1.0, 0.4, uDesaturate);
    vec3 gray = vec3(luma);
    color = mix(gray, color, satFactor);

    // 2. 强 S 曲线对比（黑场 0、高光 1、gamma 1.25），保留黑点
    float gamma = mix(1.0, 1.25, uContrast);
    color = pow(clamp(color, 0.0, 1.0), vec3(gamma));

    // 3. 轻微冷偏（B ×1.05）
    color += vec3(-0.01, 0.0, 0.03) * uCoolShift;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
