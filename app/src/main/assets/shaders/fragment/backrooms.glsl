precision mediump float;

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 用户可调参数（默认值均为 1.0）
uniform float uColorShift;    // 色偏强度
uniform float uSaturation;    // 饱和度
uniform float uVignette;      // 暗角强度
uniform float uNoise;         // 噪点强度
uniform float uDistortion;    // 畸变强度
uniform float uFlicker;       // 频闪强度

varying vec2 vTexCoord;

// 公共工具函数（hash21 / valueNoise）
#include "shaders/fragment/common.glsl"

// ─────────────────────────────────────────────────────────
// 增强版荧光灯频闪模型（降频优化版）
// 降低熄灭概率与频闪剧烈程度，保留后室氛围但不刺眼：
//   - 低频段：缓慢的亮度漂移（更平稳）
//   - 高频段：偶尔的轻微闪烁（频率/幅度降低）
//   - 极低概率：灯管短暂熄灭（概率从 ~1%/帧 降至 ~0.15%/帧）
// ─────────────────────────────────────────────────────────
float fluorescentFlicker(float t) {
    // 慢速漂移（~1.5Hz 等效），幅度收窄使基线更稳
    float slow = valueNoise(vec2(t * 1.5, 0.0)) * 0.5 + 0.5;
    // 快速抖动（~5Hz 等效，原 8Hz），阈值提高、幅度降低
    float fast = valueNoise(vec2(t * 5.0, 13.7));
    fast = smoothstep(0.78, 0.97, fast);
    // 灯管熄灭事件：极低概率（约 0.15%/帧，原 1%/帧）
    float blackout = valueNoise(vec2(t * 0.18, 42.0));
    float kill = smoothstep(0.992, 0.998, blackout);
    float recovery = 1.0 - kill * 0.35; // 压暗幅度从 50% 降至 35%
    // 合成：基础亮度 + 偶尔的明亮脉冲，乘以熄灭因子
    return clamp((slow + fast * 0.22) * recovery, 0.0, 1.0);
}

// ─────────────────────────────────────────────────────────
// 桶形畸变 + 波浪扭曲（模拟监控摄像头 + 空间扭曲感）
// ─────────────────────────────────────────────────────────
vec2 barrelDistort(vec2 uv, float k) {
    vec2 centered = uv - 0.5;
    float r2 = dot(centered, centered);
    // 二次桶形畸变
    vec2 distorted = uv + centered * k * r2;
    // 叠加轻微波浪扭曲（模拟空间不稳定感）
    float waveAmp = 0.002;
    distorted.x += sin(centered.y * 18.0 + uTime * 0.7) * waveAmp;
    distorted.y += cos(centered.x * 14.0 + uTime * 0.5) * waveAmp;
    return distorted;
}

// ─────────────────────────────────────────────────────────
// 扫描线效果（模拟老式监控/录像带）
// ─────────────────────────────────────────────────────────
float scanline(vec2 uv, float time) {
    // 基础扫描线：基于屏幕 y 坐标
    float line = sin(uv.y * uResolution.y * 1.5) * 0.5 + 0.5;
    // 让扫描线有轻微滚动效果
    line += sin(uv.y * 80.0 + time * 2.0) * 0.15;
    // 偶尔出现的宽干扰带（模拟录像带跟踪不良）
    float interference = sin(uv.y * 3.0 + time * 0.8) * sin(uv.y * 47.0 + time * 12.0);
    interference = smoothstep(0.85, 1.0, interference) * 0.12;
    return line * 0.05 + interference;
}

// ─────────────────────────────────────────────────────────
// 主函数
// ─────────────────────────────────────────────────────────
void main() {
    // ── 1. 桶形畸变 + 波浪扭曲（增强）──
    float barrelStrength = -0.25 * uDistortion; // 畸变强度可调
    vec2 uv = barrelDistort(vTexCoord, barrelStrength);

    // 边界检查：畸变后超出 [0,1] 则采样边缘
    uv = clamp(uv, 0.001, 0.999);

    // ── 2. 采样原始纹理 ──
    vec4 texColor = texture2D(uTexture, uv);
    vec3 color = texColor.rgb;

    // ── 3. 黄色/绿色调偏移（后室标志性色调）──
    // Color matrix：提升 R/G，压制 B（原始值）
    const mat3 colorMatrix = mat3(
        // R out   G out   B out
        1.15,    0.05,   0.00,   // R in 贡献
        0.10,    1.05,  -0.05,   // G in 贡献
       -0.10,   -0.05,   0.70    // B in 贡献
    );
    color = colorMatrix * color;

    // 额外暖色叠加：轻微增加黄色感（原始值），乘以色偏强度
    color += vec3(0.04, 0.035, -0.02) * uColorShift;

    // ── 4. 降低饱和度（压抑感，原始值）──
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    float saturation = 0.45 * uSaturation; // 饱和度可调
    color = mix(vec3(luminance), color, saturation);

    // ── 5. 荧光灯频闪（原始幅度 + 偶尔熄灭）──
    float flicker = fluorescentFlicker(uTime);
    color *= (0.82 + flicker * 0.36 * uFlicker); // 频闪强度可调

    // ── 6. 对比度微调（偏灰，模拟廉价荧光照明，原始值）──
    const float contrast = 0.88;
    color = (color - 0.5) * contrast + 0.5;

    // ── 7. 暗角效果（增强）──
    vec2 vigUV = vTexCoord - 0.5;
    float vigDist = dot(vigUV, vigUV);
    // 暗角范围更大、压暗更狠
    float vignette = 1.0 - smoothstep(0.15, 0.75, vigDist) * 0.78 * uVignette; // 暗角强度可调
    color *= vignette;

    // ── 8. 噪点（增强）──
    float grain = hash21(vTexCoord * uResolution + vec2(uTime * 37.0, uTime * 11.0));
    // 噪点强度提升
    color += (grain - 0.5) * 0.10 * uNoise; // 噪点强度可调

    // ── 9. 扫描线效果（新增，老式监控/录像带质感）──
    float scan = scanline(vTexCoord, uTime);
    color -= scan;

    // ── 10. 整体亮度压低（后室不会太亮，原始值）──
    color *= 0.88;

    // 最终钳位
    color = clamp(color, 0.0, 1.0);

    gl_FragColor = vec4(color, texColor.a);
}
