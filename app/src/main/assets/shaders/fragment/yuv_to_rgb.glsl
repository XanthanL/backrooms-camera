precision mediump float;

// ─────────────────────────────────────────────────────────
// YUV → RGB 转换（单帧）
//
// 输入：3 个 GL_LUMINANCE 纹理（Y/U/V），来自 PreProcessor 紧凑 I420 数据
// 输出：RGBA 纹理
//
// 采用 BT.601 full range 转换（CameraX YUV_420_888 默认 full range）
// ─────────────────────────────────────────────────────────

uniform sampler2D uY;  // Y plane（亮度）
uniform sampler2D uU;  // U plane（色度蓝，半分辨率）
uniform sampler2D uV;  // V plane（色度红，半分辨率）

varying vec2 vTexCoord;

void main() {
    // 采样 Y（全分辨率）和 U/V（半分辨率，GL_LINEAR 会自动插值）
    float y = texture2D(uY, vTexCoord).r;
    float u = texture2D(uU, vTexCoord).r - 0.5;
    float v = texture2D(uV, vTexCoord).r - 0.5;

    // BT.601 full range YUV → RGB
    float r = y + 1.402 * v;
    float g = y - 0.344 * u - 0.714 * v;
    float b = y + 1.772 * u;

    gl_FragColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
}
