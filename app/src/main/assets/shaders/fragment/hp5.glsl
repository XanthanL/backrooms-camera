// Ilford HP5 Plus（黑白胶片）
// 经典黑白胶片，中灰丰富层次、明显颗粒、柔和对比、可调反差（模拟滤光镜）。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uContrast;  // 反差（模拟滤光镜），默认 1.0
uniform float uGrain;     // 颗粒，默认 1.0
uniform float uExposure;  // 曝光补偿，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;

    // 1. 灰度转换（BT.601 加权）
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 2. 曝光补偿
    luma *= mix(0.85, 1.15, uExposure);

    // 3. 曝光宽容度：黑场 0.03、高光 0.95（保留细节）
    luma = clamp(luma, 0.03, 0.95);

    // 4. 可调 S 曲线反差（模拟黄绿红滤光镜）
    float gamma = mix(0.9, 1.3, uContrast);
    luma = pow(luma, gamma);

    // 5. Mono 颗粒（仅亮度通道，比彩色颗粒更明显）
    float grainAmt = 0.08 * uGrain;
    float noise = (hash21(vTexCoord * uResolution + uTime) - 0.5) * grainAmt;
    luma += noise;

    vec3 result = vec3(clamp(luma, 0.0, 1.0));
    gl_FragColor = vec4(result, 1.0);
}
