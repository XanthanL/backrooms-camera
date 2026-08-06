precision mediump float;

#include "shaders/fragment/common.glsl"

uniform sampler2D uTexture;
uniform float uTime;
uniform vec2 uResolution;

// 可调参数（由上层 setAdjustableParam 注入，默认值在 main 内兜底）
uniform float uEmbossStrength;  // 浮雕卷积强度（0..2，默认 1.0）
uniform float uEdgeStrength;    // 边缘增强强度（0..2，默认 1.0）
uniform float uGrayMix;         // 灰度混合度（0..1，默认 0.2）

varying vec2 vTexCoord;

// ─────────────────────────────────────────────────────────
// 浮雕/凹凸效果 (Emboss)
// 通过邻域像素差值模拟光照下的凹凸质感。
// 优化：9 tap（中心 + 8 邻域）在 main 中一次性采样，
//      emboss 与 edgeEnhance 复用同一组样本，总采样数 17 → 9。
// ─────────────────────────────────────────────────────────

// 浮雕卷积核：经典 5 点 Laplacian 变体 + 对角线加权（接收已采样值）
vec3 emboss(vec3 center, vec3 top, vec3 bottom, vec3 left, vec3 right,
            vec3 tl, vec3 tr, vec3 bl, vec3 br, float strength) {
    vec3 result = center * 4.4
                - top    * 0.8
                - bottom * 0.8
                - left   * 0.8
                - right  * 0.8
                - tl     * 0.3
                - tr     * 0.3
                - bl     * 0.3
                - br     * 0.3;

    // 强度混合：原图 * (1-strength) + emboss * strength
    result = mix(center, result, clamp(strength, 0.0, 1.0));

    // 浮雕灰底偏移：DC 增益为 0(权重和 = 4.4-3.2-1.2 = 0)，
    // 平坦区域输出基准 0.5*strength，避免负增益导致整体反相发黑
    result += vec3(0.5 * strength);

    return result;
}

// 边缘增强：Sobel 算子提取边缘（接收已采样的 luma）
float edgeEnhance(float tl, float t, float tr, float l, float r, float bl, float b, float br) {
    float gx = -tl - 2.0*l - bl + tr + 2.0*r + br;
    float gy = -tl - 2.0*t - tr + bl + 2.0*b + br;
    return sqrt(gx*gx + gy*gy);
}

void main() {
    vec2 uv = vTexCoord;
    vec2 t = 1.0 / uResolution;

    // 兜底默认值（uniform 未设置时为 0）
    float embossStrength = (uEmbossStrength > 0.0001) ? uEmbossStrength : 1.0;
    float edgeStrength   = (uEdgeStrength   > 0.0001) ? uEdgeStrength   : 1.0;
    float grayMix        = uGrayMix;

    // ── 9 tap 一次性采样（中心 + 4 正交 + 4 对角），后续复用 ──
    vec3 center = texture2D(uTexture, uv).rgb;
    vec3 top    = texture2D(uTexture, clamp(uv + vec2(0.0,  t.y), 0.0, 1.0)).rgb;
    vec3 bottom = texture2D(uTexture, clamp(uv - vec2(0.0,  t.y), 0.0, 1.0)).rgb;
    vec3 left   = texture2D(uTexture, clamp(uv - vec2(t.x,  0.0), 0.0, 1.0)).rgb;
    vec3 right  = texture2D(uTexture, clamp(uv + vec2(t.x,  0.0), 0.0, 1.0)).rgb;
    vec3 tl     = texture2D(uTexture, clamp(uv + vec2(-t.x,  t.y), 0.0, 1.0)).rgb;
    vec3 tr     = texture2D(uTexture, clamp(uv + vec2( t.x,  t.y), 0.0, 1.0)).rgb;
    vec3 bl     = texture2D(uTexture, clamp(uv + vec2(-t.x, -t.y), 0.0, 1.0)).rgb;
    vec3 br     = texture2D(uTexture, clamp(uv + vec2( t.x, -t.y), 0.0, 1.0)).rgb;

    // ── 1. 浮雕效果 ──
    vec3 embossColor = emboss(center, top, bottom, left, right, tl, tr, bl, br, embossStrength);

    // ── 2. 灰度化增强质感 ──
    float gray = luma(embossColor);
    vec3 color = mix(embossColor, vec3(gray), grayMix);

    // ── 3. 边缘增强（复用邻域 luma）──
    float edge = edgeEnhance(luma(tl), luma(top), luma(tr), luma(left), luma(right),
                             luma(bl), luma(bottom), luma(br));
    color += edge * 0.15 * edgeStrength;

    // ── 4. 对比度微调 ──
    color = (color - 0.5) * 1.15 + 0.5;

    // ── 5. 轻微暗角 ──
    vec2 vigUV = uv - 0.5;
    float vigDist = dot(vigUV, vigUV);
    float vignette = 1.0 - smoothstep(0.2, 0.8, vigDist) * 0.4;
    color *= vignette;

    color = clamp(color, 0.0, 1.0);
    gl_FragColor = vec4(color, 1.0);
}
