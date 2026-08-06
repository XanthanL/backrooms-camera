precision mediump float;

uniform sampler2D uTexture;
uniform sampler2D uLutTexture;   // LUT atlas（运行时生成）
uniform float uLutIntensity;     // LUT 强度（0=原图，1=完全 LUT，可>1增强）

varying vec2 vTexCoord;

// LUT atlas 参数：N=32 → atlas 宽=1024(32*32)，高=32
const float LUT_SIZE = 32.0;
const float ATLAS_WIDTH = 1024.0;  // LUT_SIZE * LUT_SIZE

void main() {
    vec4 texColor = texture2D(uTexture, vTexCoord);
    vec3 color = texColor.rgb;

    // ── 采样 2D LUT atlas（模拟 3D LUT）──
    // blue 决定 slice 索引（横向排列），green 决定 slice 内 x，red 决定 y
    float blueSlice = color.b * (LUT_SIZE - 1.0);
    float bFloor = floor(blueSlice);
    float bCeil = min(bFloor + 1.0, LUT_SIZE - 1.0);
    float bFrac = blueSlice - bFloor;

    // +0.5 像素中心偏移，避免采样到 slice 边界
    float u1 = (bFloor * LUT_SIZE + color.g * (LUT_SIZE - 1.0) + 0.5) / ATLAS_WIDTH;
    float u2 = (bCeil  * LUT_SIZE + color.g * (LUT_SIZE - 1.0) + 0.5) / ATLAS_WIDTH;
    float v = (color.r * (LUT_SIZE - 1.0) + 0.5) / LUT_SIZE;

    vec3 lut1 = texture2D(uLutTexture, vec2(u1, v)).rgb;
    vec3 lut2 = texture2D(uLutTexture, vec2(u2, v)).rgb;
    vec3 lutColor = mix(lut1, lut2, bFrac);

    // 混合原图与 LUT 结果
    vec3 result = mix(color, lutColor, clamp(uLutIntensity, 0.0, 1.0));
    // 强度 > 1 时额外增强 LUT 效果
    if (uLutIntensity > 1.0) {
        result = mix(result, lutColor, (uLutIntensity - 1.0) * 0.5);
    }

    gl_FragColor = vec4(clamp(result, 0.0, 1.0), texColor.a);
}
