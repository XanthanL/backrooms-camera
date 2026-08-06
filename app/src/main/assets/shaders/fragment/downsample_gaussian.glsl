precision mediump float;

// ─────────────────────────────────────────────────────────
// 高斯下采样：2× 降采样 + 3×3 高斯滤波
//
// 用于构建金字塔：L0→L1→L2，每层缩小一半。
// 高斯滤波抑制高频混叠，保证粗层块匹配稳定。
//
// 输入：源纹理（Y luminance 或 RGBA）
// 输出：2× 下采样后的纹理
// ─────────────────────────────────────────────────────────

uniform sampler2D uTex;
uniform vec2 uTexelSize;   // vec2(1.0/srcW, 1.0/srcH) 源纹理纹素大小

varying vec2 vTexCoord;

void main() {
    // 3×3 高斯核（分离形式简化为单 pass）
    //  [1 2 1]
    //  [2 4 2]   归一化系数 = 16
    //  [1 2 1]
    float kCorner = 1.0;
    float kEdge   = 2.0;
    float kCenter = 4.0;

    vec2 s = uTexelSize;
    float sum = 0.0;

    sum += texture2D(uTex, vTexCoord + vec2(-s.x, -s.y)).r * kCorner;
    sum += texture2D(uTex, vTexCoord + vec2( 0.0, -s.y)).r * kEdge;
    sum += texture2D(uTex, vTexCoord + vec2( s.x, -s.y)).r * kCorner;
    sum += texture2D(uTex, vTexCoord + vec2(-s.x,  0.0)).r * kEdge;
    sum += texture2D(uTex, vTexCoord + vec2( 0.0,  0.0)).r * kCenter;
    sum += texture2D(uTex, vTexCoord + vec2( s.x,  0.0)).r * kEdge;
    sum += texture2D(uTex, vTexCoord + vec2(-s.x,  s.y)).r * kCorner;
    sum += texture2D(uTex, vTexCoord + vec2( 0.0,  s.y)).r * kEdge;
    sum += texture2D(uTex, vTexCoord + vec2( s.x,  s.y)).r * kCorner;

    gl_FragColor = vec4(sum / 16.0, 0.0, 0.0, 1.0);
}
