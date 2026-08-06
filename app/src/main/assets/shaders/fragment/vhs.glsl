// VHS 录像带
// 扫描线、色差、噪点、画面抖动、边缘模糊。复古怀旧。
// 与「后室」区分：强化色差和抖动，去除黄调和频闪。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uChromaticAberration; // 色差，默认 1.0
uniform float uScanline;            // 扫描线，默认 1.0
uniform float uNoise;               // 噪点，默认 1.0
uniform float uJitter;              // 抖动，默认 1.0

varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;

    // 1. 画面水平抖动（基于 uTime，幅度 0.003）
    float jitter = (hash21(vec2(floor(uTime * 30.0), 1.0)) - 0.5) * 0.006 * uJitter;
    uv.x += jitter;

    // 2. 色差：R/B 通道水平偏移（模拟磁头对不准）
    float ca = 0.003 * uChromaticAberration;
    float r = texture2D(uTexture, uv + vec2(-ca, 0.0)).r;
    float g = texture2D(uTexture, uv).g;
    float b = texture2D(uTexture, uv + vec2(ca, 0.0)).b;
    vec3 color = vec3(r, g, b);

    // 3. 扫描线（水平亮带，移动速度比后室快）
    float scanLine = sin(uv.y * uResolution.y * 1.5 + uTime * 5.0) * 0.5 + 0.5;
    scanLine = mix(1.0, scanLine, 0.12 * uScanline);
    color *= scanLine;

    // 4. 粗颗粒噪点 + 偶发水平噪声带
    float noise = (hash21(uv * uResolution + uTime) - 0.5) * 0.12 * uNoise;
    color += noise;

    // 偶发水平噪声带（低概率）
    float bandY = floor(uv.y * 50.0);
    float bandRand = hash21(vec2(bandY, floor(uTime * 3.0)));
    float bandNoise = step(0.96, bandRand) * (hash21(uv * uResolution * 2.0 + uTime) - 0.5) * 0.2 * uNoise;
    color += bandNoise;

    // 5. 边缘模糊：画面左右边缘水平模糊
    float edgeMask = smoothstep(0.05, 0.0, uv.x) + smoothstep(0.95, 1.0, uv.x);
    vec2 texel = 1.0 / uResolution;
    vec3 blur = (texture2D(uTexture, uv + vec2(-texel.x * 3.0, 0.0)).rgb +
                 texture2D(uTexture, uv + vec2(texel.x * 3.0, 0.0)).rgb) * 0.5;
    color = mix(color, blur, edgeMask * 0.5);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
