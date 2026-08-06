// ─────────────────────────────────────────────────────────
// 公共 Shader 片段
// 通过 ShaderHelper 的 #include 预处理引入，避免重复定义。
// 用法：在 fragment shader 顶部写 #include "shaders/fragment/common.glsl"
// ─────────────────────────────────────────────────────────

// 2D hash：返回 [0,1) 的伪随机值
float hash21(vec2 p) {
    p = fract(p * vec2(234.34, 435.345));
    p += p.xy;
    return fract(p.x * p.y);
}

// 值噪声：双线性插值的平滑伪随机场，返回 [0,1]
float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);

    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));

    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Rec.601 亮度：RGB → 灰度标量
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}
