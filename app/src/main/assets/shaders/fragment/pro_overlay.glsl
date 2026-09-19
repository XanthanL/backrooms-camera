// ─────────────────────────────────────────────────────────
// 取景辅助叠加：斑马纹（曝光警告）+ 峰值对焦（合焦边缘高亮）
//
// 输入是滤镜链输出的普通 2D 纹理，输出仅画到屏幕的 letterbox 视口，
// 不写回任何纹理 → 斑马纹/峰值对焦不会出现在照片或录像里。
//
// 未命中标记的像素原样输出，因此无需开启 GL_BLEND。
// ─────────────────────────────────────────────────────────

precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform vec2 uTexel;                // 1/纹理尺寸（峰值对焦邻域采样步长）
uniform float uZebraMode;           // 0=关 1=仅过曝 2=过曝+欠曝
uniform float uPeaking;             // 0=关 1=开
uniform float uPeakingThreshold;    // 边缘强度阈值（越小越灵敏）

varying vec2 vTexCoord;

void main() {
    vec3 color = texture2D(uTexture, vTexCoord).rgb;

    // ── 斑马纹：8px 周期对角条纹覆盖过曝/欠曝区 ──────────────
    if (uZebraMode > 0.5) {
        float l = luma(color);
        // 0.93/0.06 对应「亮部即将死白」「暗部彻底堵黑」的常用警告点
        float over = step(0.93, l) * step(0.5, uZebraMode);
        float under = step(1.5, uZebraMode) * step(l, 0.06);
        float mark = max(over, under) * step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 8.0));
        vec3 markColor = mix(vec3(1.0), vec3(0.0, 0.72, 0.95), under);
        color = mix(color, markColor, mark);
    }

    // ── 峰值对焦：4-tap 梯度幅值，合焦边缘染荧光黄 ────────────
    if (uPeaking > 0.5) {
        float lR = luma(texture2D(uTexture, vTexCoord + vec2(uTexel.x, 0.0)).rgb);
        float lL = luma(texture2D(uTexture, vTexCoord - vec2(uTexel.x, 0.0)).rgb);
        float lU = luma(texture2D(uTexture, vTexCoord + vec2(0.0, uTexel.y)).rgb);
        float lD = luma(texture2D(uTexture, vTexCoord - vec2(0.0, uTexel.y)).rgb);
        float edge = abs(lR - lL) + abs(lU - lD);
        float mark = smoothstep(uPeakingThreshold, uPeakingThreshold + 0.08, edge);
        color = mix(color, vec3(0.82, 0.737, 0.333), mark);
    }

    gl_FragColor = vec4(color, 1.0);
}
