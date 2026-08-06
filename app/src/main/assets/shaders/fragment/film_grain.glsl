precision mediump float;

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 用户可调参数（默认值均为 1.0）
uniform float uGrainIntensity;  // 颗粒强度
uniform float uFade;            // 褪色程度
uniform float uScanline;        // 扫描线强度
uniform float uVignette;        // 暗角强度

varying vec2 vTexCoord;

// 公共工具函数（hash21 / valueNoise）
#include "shaders/fragment/common.glsl"

// ─────────────────────────────────────────────────────────
// 胶片颗粒专用噪声（随时间跳动）
// ─────────────────────────────────────────────────────────
float filmGrainNoise(vec2 p, float t) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    // 每帧偏移，使颗粒动态跳动
    float a = hash21(i + vec2(t * 0.0, t * 1.0));
    float b = hash21(i + vec2(1.0, 0.0) + vec2(t * 0.0, t * 1.0));
    float c = hash21(i + vec2(0.0, 1.0) + vec2(t * 0.0, t * 1.0));
    float d = hash21(i + vec2(1.0, 1.0) + vec2(t * 0.0, t * 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// ─────────────────────────────────────────────────────────
// 主函数
// ─────────────────────────────────────────────────────────
void main() {
    vec2 uv = vTexCoord;

    // ── 1. 采样原始纹理 ──
    vec4 texColor = texture2D(uTexture, uv);
    vec3 color = texColor.rgb;

    // ── 2. 褪色效果（Lifted Blacks）──
    // 将黑色提升到 ~0.08，模拟老胶片褪色
    const float blackLift = 0.08;
    const float fadeContrast = 0.82;
    // 褪色程度可调：uFade=1.0 为默认，0.0 表示无褪色
    float adjustedBlackLift = blackLift * uFade;
    float adjustedFadeContrast = mix(1.0, fadeContrast, uFade);
    color = (color - 0.5) * adjustedFadeContrast + 0.5 + adjustedBlackLift;

    // ── 3. 暖色偏 ──
    // 整体偏暖：提升红/黄，压制蓝
    color.r *= 1.08;
    color.g *= 1.02;
    color.b *= 0.88;
    // 轻微黄色叠加
    color += vec3(0.02, 0.015, -0.01);

    // ── 4. 胶片颗粒 ──
    // 用屏幕空间高频噪声模拟银盐颗粒
    // 颗粒大小约 1~2 像素，随时间跳动
    vec2 grainUV = uv * uResolution * 0.8;
    float grain = filmGrainNoise(grainUV, uTime * 8.0);
    // 第二层更粗的颗粒叠加，增加真实感
    float grain2 = filmGrainNoise(grainUV * 0.5 + vec2(100.0), uTime * 6.0);
    // 合成颗粒：两层混合
    float combinedGrain = (grain * 0.6 + grain2 * 0.4) - 0.5;
    // 颗粒强度：约 ±6%，在中间调最明显
    float grainMask = 1.0 - abs(dot(color, vec3(0.299, 0.587, 0.114)) - 0.5) * 2.0;
    color += combinedGrain * 0.12 * grainMask * uGrainIntensity; // 颗粒强度可调

    // ── 5. 轻微光晕（高光溢出）── 4 采样优化（原 3x3=9 次）
    // 提取高亮区域，做简易 bloom 近似
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    float highlightMask = smoothstep(0.55, 1.0, luma);
    // 十字 4 采样 + 中心复用（texColor 已采样），近似 3x3 bloom
    vec2 texelSize = vec2(1.0) / uResolution;
    vec3 bloomSum = texColor.rgb; // 中心（复用已有采样）
    bloomSum += texture2D(uTexture, uv + vec2(texelSize.x * 3.0, 0.0)).rgb;
    bloomSum += texture2D(uTexture, uv - vec2(texelSize.x * 3.0, 0.0)).rgb;
    bloomSum += texture2D(uTexture, uv + vec2(0.0, texelSize.y * 3.0)).rgb;
    bloomSum += texture2D(uTexture, uv - vec2(0.0, texelSize.y * 3.0)).rgb;
    bloomSum /= 5.0;
    // 仅在高光区域叠加 bloom
    color += bloomSum * highlightMask * 0.18;

    // ── 6. 暗角效果（明显）──
    vec2 vigUV = uv - 0.5;
    float vigDist = dot(vigUV, vigUV);
    float vignette = 1.0 - smoothstep(0.15, 0.78, vigDist) * 0.85 * uVignette; // 暗角强度可调
    color *= vignette;

    // ── 7. 细微水平扫描线 ──
    // 每隔 2 像素一条极淡的暗线
    float scanline = sin(uv.y * uResolution.y * 3.14159) * 0.5 + 0.5;
    scanline = mix(mix(0.94, 1.0, scanline), 1.0, 1.0 - uScanline); // 扫描线强度可调
    color *= scanline;

    // ── 8. 整体饱和度微调（老胶片饱和度略低）──
    float finalLuma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(finalLuma), color, 0.85);

    // 最终钳位
    color = clamp(color, 0.0, 1.0);

    gl_FragColor = vec4(color, texColor.a);
}
