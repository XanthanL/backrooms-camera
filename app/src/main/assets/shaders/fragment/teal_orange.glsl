// Teal & Orange（好莱坞大片色）
// 阴影偏青蓝、高光/肤色偏橙暖，高对比，现代商业片标志色。
// 与 LUT（固化青橙）互补：本 shader 可实时调节双色强度与肤色保护。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uTealStrength;   // 青色（阴影）强度，默认 1.0
uniform float uOrangeStrength; // 橙色（高光）强度，默认 1.0
uniform float uContrast;       // 对比度，默认 1.0
uniform float uSkinProtect;    // 肤色保护，默认 1.0

varying vec2 vTexCoord;

// RGB <-> HSV
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

// 肤色检测：返回 [0,1]，橙红色相且饱和度足够时为 1
float skinMask(vec3 color) {
    vec3 hsv = rgb2hsv(color);
    // 肤色色相区间约 0.02~0.13（橙红）
    float hue = hsv.x;
    float sat = hsv.y;
    float inRange = step(0.02, hue) * step(hue, 0.13) * step(0.2, sat);
    return inRange;
}

// S 曲线对比：gamma>1 提对比
vec3 sCurve(vec3 c, float gamma) {
    return pow(c, vec3(gamma));
}

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 1. 分裂色调：阴影偏青蓝，高光偏橙暖
    float shadowMask = smoothstep(0.5, 0.0, luma); // 暗部权重
    float highlightMask = smoothstep(0.5, 1.0, luma); // 亮部权重

    vec3 tealShift = vec3(0.0, 0.15, 0.25) * uTealStrength * shadowMask;
    vec3 orangeShift = vec3(0.25, 0.10, -0.10) * uOrangeStrength * highlightMask;

    // 2. 肤色保护：降低肤色区域的青蓝偏移
    float skin = skinMask(color) * uSkinProtect;
    tealShift *= (1.0 - skin);

    color += tealShift + orangeShift;

    // 3. 对比度（中强 S 曲线）
    float gamma = mix(1.0, 1.1, uContrast);
    color = sCurve(clamp(color, 0.0, 1.0), gamma);

    // 4. 全局饱和度增强（阴影/高光额外增强）
    vec3 gray = vec3(luma);
    float satBoost = 1.2 + (shadowMask + highlightMask) * 0.1 * uContrast;
    color = mix(gray, color, satBoost);

    gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
