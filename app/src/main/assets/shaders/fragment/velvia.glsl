// Fuji Velvia 50（风光反转片）
// 极高饱和度、深邃蓝天、鲜艳绿色、高对比、干净无颗粒。风光摄影师首选。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uSaturation;   // 全局饱和度，默认 1.0
uniform float uBlueGreen;    // 蓝绿增强，默认 1.0
uniform float uContrast;     // 对比，默认 1.0
uniform float uHueShift;     // 色相偏移，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 全局饱和度增强（1.6-1.8 基准，由 uSaturation 调节）
    float satFactor = mix(1.0, 1.7, uSaturation);
    vec3 gray = vec3(luma);
    color = mix(gray, color, satFactor);

    // 2. 蓝绿通道额外增强（蓝 ×1.3，绿 ×1.2）
    float bgBoost = uBlueGreen;
    color.b *= mix(1.0, 1.3, bgBoost);
    color.g *= mix(1.0, 1.2, bgBoost);

    // 3. 色相微调：绿色向黄绿偏移，蓝色向青偏
    //    简化实现：G 通道加微量 R，B 通道加微量 G
    float hueAmt = 0.05 * uHueShift;
    color.r = mix(color.r, color.r + color.g * hueAmt, step(color.g, 0.5) * 0.0 + step(0.3, color.g));
    color.b = mix(color.b, color.b + color.g * hueAmt * 0.5, step(0.3, color.b));

    // 4. 强 S 曲线对比（gamma 1.15）
    float gamma = mix(1.0, 1.15, uContrast);
    color = pow(clamp(color, 0.0, 1.0), vec3(gamma));

    // 反转片干净，无颗粒无暗角
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
