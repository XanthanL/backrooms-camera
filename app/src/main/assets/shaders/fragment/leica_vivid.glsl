// Leica Vivid（徕卡鲜艳）拍摄风格
// 参考：小米徕卡 Vibrant 风格 / GCam AGC 徕卡 LUT / Leica Look VIV
// 特征：高饱和、鲜明色彩、S 曲线对比、浓郁暗部、轻微暖调与暗角

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uSaturation;   // 饱和度，默认 1.0
uniform float uContrast;     // 对比度，默认 1.0
uniform float uWarmth;       // 暖调，默认 1.0
uniform float uVignette;     // 暗角，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 饱和度增强（徕卡鲜艳核心：色彩鲜明）
    float sat = 1.0 + 0.32 * uSaturation;
    color = mix(vec3(luma), color, sat);

    // 2. 德味通道强调：红/绿微增（浓郁红绿、深绿植物）
    color.r *= 1.0 + 0.06 * uSaturation;
    color.g *= 1.0 + 0.04 * uSaturation;

    // 3. S 曲线对比：线性拉伸 + 暗部压深（浓郁暗部、不发灰）
    float k = 1.0 + 0.20 * uContrast;
    color = clamp((color - 0.5) * k + 0.5, 0.0, 1.0);
    color = pow(color, vec3(1.0 + 0.08 * uContrast));

    // 4. 暖调偏移（轻微 +R -B，皮肤自然不偏黄绿）
    color *= vec3(1.0 + 0.04 * uWarmth, 1.0 + 0.01 * uWarmth, 1.0 - 0.03 * uWarmth);

    // 5. 轻微暗角（徕卡镜头氛围感）
    vec2 vigUV = vTexCoord - 0.5;
    float vigDist = dot(vigUV, vigUV);
    color *= 1.0 - smoothstep(0.22, 0.85, vigDist) * 0.28 * uVignette;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
