// Glitch 故障艺术
// 画面撕裂、RGB 通道分离、像素错位、偶发色块。数字故障美学。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uTearStrength;  // 撕裂强度，默认 1.0
uniform float uRgbShift;      // RGB 分离，默认 1.0
uniform float uPixelGlitch;   // 像素错位，默认 1.0
uniform float uGlitchFreq;    // 故障频率，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;

    // 0. 故障触发：基于 uTime 的周期性强故障（每 3-5 秒一次）
    float cycle = sin(uTime * uGlitchFreq * 0.6) * 0.5 + 0.5;
    float burst = step(0.92, hash21(vec2(floor(uTime * uGlitchFreq * 3.0), 1.0)));
    float glitchAmt = mix(cycle * 0.3, 1.0, burst);

    // 1. 撕裂：基于 uv.y 分段水平偏移
    float band = floor(uv.y * 12.0);
    float tearRand = hash21(vec2(band, floor(uTime * 10.0)));
    float tearOffset = (tearRand - 0.5) * 0.08 * uTearStrength * glitchAmt;
    uv.x += tearOffset;

    // 2. RGB 分离：R 左移、B 右移（偏移量随故障强度变化）
    float rgbOffset = 0.005 * uRgbShift * glitchAmt;
    vec2 uvR = uv + vec2(-rgbOffset, 0.0);
    vec2 uvB = uv + vec2(rgbOffset, 0.0);
    float r = texture2D(uTexture, uvR).r;
    float g = texture2D(uTexture, uv).g;
    float b = texture2D(uTexture, uvB).b;
    vec3 color = vec3(r, g, b);

    // 3. 像素错位：低概率 8x8 像素块错位
    float blockRand = hash21(floor(uv * uResolution / 8.0) + floor(uTime * 5.0));
    float blockGlitch = step(0.97, blockRand) * uPixelGlitch;
    if (blockGlitch > 0.5) {
        vec2 blockOffset = vec2(
            (hash21(floor(uv * uResolution / 8.0) + 7.0) - 0.5) * 0.2,
            0.0
        );
        color = texture2D(uTexture, uv + blockOffset).rgb;
    }

    // 4. 偶发色块叠加（数字故障美学）
    float tintMask = step(0.98, hash21(floor(uv * uResolution / 16.0) + floor(uTime * 8.0)));
    color = mix(color, color * vec3(1.2, 0.6, 1.1), tintMask * glitchAmt * 0.5);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
