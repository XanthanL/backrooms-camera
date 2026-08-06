// 黄蓝（选择性色彩增强）
// 让画面中黄色部分更黄、蓝色部分更蓝：检测像素色相，在黄色区间（约 40°~75°）
// 与蓝色区间（约 190°~255°）内按色相距离加权提升饱和度，其余颜色基本不动。
// 与全局饱和度滤镜的区别：不影响肤色/绿色等其它色相，适合突出暖黄与冷蓝对比。

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数
uniform float uYellowStrength; // 黄色增强强度，默认 1.2
uniform float uBlueStrength;   // 蓝色增强强度，默认 1.2
uniform float uRange;          // 色相范围宽度（越大命中的颜色越多），默认 1.0

varying vec2 vTexCoord;

// RGB -> HSV（hue 归一化到 [0,1]）
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

// 以 center 为中心、half 为半宽的色相带掩码（边缘平滑过渡，处理色相环回绕）
float hueBandMask(float hue, float center, float half) {
    float d = abs(hue - center);
    d = min(d, 1.0 - d); // 色相是环形，取最短距离
    return 1.0 - smoothstep(half * 0.6, half, d);
}

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;
    vec3 hsv = rgb2hsv(color);
    float luma = dot(color, vec3(0.299, 0.587, 0.114));

    // 黄色区间中心 0.16（约 58°），蓝色区间中心 0.61（约 220°）。
    // 饱和度门槛 0.15：跳过灰蒙蒙的低饱和像素，避免放大噪点。
    float yellowMask = hueBandMask(hsv.x, 0.16, 0.05 * uRange) * step(0.15, hsv.y);
    float blueMask = hueBandMask(hsv.x, 0.61, 0.07 * uRange) * step(0.15, hsv.y);

    // 选择性提升饱和度：mask 内放大，其余区域维持原状
    float satBoost = 1.0 + yellowMask * uYellowStrength + blueMask * uBlueStrength;
    vec3 outColor = mix(vec3(luma), color, satBoost);

    // 轻微亮度整形：黄更亮一点，蓝更深沉一点（增强色彩对比）
    outColor *= 1.0 + 0.06 * yellowMask * uYellowStrength - 0.03 * blueMask * uBlueStrength;

    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);
}
