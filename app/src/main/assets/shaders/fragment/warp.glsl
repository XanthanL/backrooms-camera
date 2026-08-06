precision mediump float;

// ─────────────────────────────────────────────────────────
// 帧变形：根据运动矢量场对目标帧进行平移对齐
//
// 输入：目标帧 RGBA 纹理 + 运动矢量场（来自金字塔块匹配）
// 输出：对齐后的 RGBA 纹理
//
// 运动矢量归一化范围 ±MAX_OFFSET（须与 align_blockmatch.glsl 一致）
// ─────────────────────────────────────────────────────────

#define MAX_OFFSET 28.0

uniform sampler2D uFrame;      // 目标帧 RGBA
uniform sampler2D uMotion;     // 运动矢量场（rg=偏移, b=SAD）
uniform vec2 uTexelSize;       // vec2(1.0/frameW, 1.0/frameH)

varying vec2 vTexCoord;

void main() {
    // 采样运动矢量（纹理已绑定为 NEAREST → 块级常量）
    vec2 motion = texture2D(uMotion, vTexCoord).xy;
    // 反归一化：[-MAX_OFFSET, +MAX_OFFSET] 像素偏移
    vec2 pixelOffset = (motion - 0.5) * (2.0 * MAX_OFFSET);
    // 转换为 UV 偏移
    vec2 uvOffset = pixelOffset * uTexelSize;

    gl_FragColor = texture2D(uFrame, vTexCoord + uvOffset);
}
