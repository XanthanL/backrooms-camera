// Kodak Portra 400（人像胶片）
// 暖肤色、柔和对比、轻微过曝、奶油色高光、细腻颗粒。
// 专业人像胶片之王，肤色保护是核心。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uWarmShift;    // 暖偏程度，默认 1.0
uniform float uSkinProtect;  // 肤色保护强度，默认 1.0
uniform float uGrain;        // 颗粒强度，默认 1.0
uniform float uContrast;     // 对比，默认 1.0
uniform float uVignette;     // 暗角，默认 1.0

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
    vec3 color = texture2D(uTexture, vTexCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 暖色偏：R 轻提 + B 压暗，高光区 B 略增（奶油高光）
    float highlightMask = smoothstep(0.5, 1.0, luma);
    vec3 warmShift = vec3(0.04, 0.0, -0.05) * uWarmShift;
    vec3 creamHighlight = vec3(-0.02, 0.0, 0.03) * uWarmShift * highlightMask;
    color += warmShift + creamHighlight;

    // 2. 柔和 S 曲线（黑场 0.04，gamma 0.9）
    float blackPoint = 0.04 * uContrast;
    float gamma = mix(0.9, 1.0, 1.0 - uContrast * 0.5);
    color = max(color - blackPoint, 0.0) / max(1.0 - blackPoint, 0.001);
    color = pow(clamp(color, 0.0, 1.0), vec3(gamma));
    // 高光柔和压缩
    color = mix(color, color * (1.0 - 0.03 * uContrast) + 0.03 * uContrast, highlightMask);

    // 3. 肤色保护：降低肤色区域的饱和度衰减
    float skin = skinMask(color) * uSkinProtect;
    // 全局轻微降饱和（Portra 肤色柔和），但肤色区不降
    vec3 gray = vec3(luma);
    float satFactor = mix(0.92, 1.0, skin);
    color = mix(gray, color, satFactor);

    // 4. 轻微径向暗角
    vec2 uv = vTexCoord;
    float dist = distance(uv, vec2(0.5));
    float vignette = smoothstep(0.8, 0.4, dist) * uVignette;
    color *= mix(1.0, vignette, 0.15 * uVignette);

    // 5. 细颗粒（比胶片滤镜更细，中间调明显）
    float grainAmt = 0.03 * uGrain;
    float midMask = 1.0 - abs(luma - 0.5) * 2.0;
    float noise = (hash21(uv * uResolution + uTime) - 0.5) * grainAmt * midMask;
    color += noise;

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
