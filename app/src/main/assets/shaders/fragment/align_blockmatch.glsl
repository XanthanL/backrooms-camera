precision mediump float;

// ─────────────────────────────────────────────────────────
// 金字塔分层块匹配帧对齐
//
// 3 层金字塔（L2=1/4, L1=1/2, L0=原分辨率）逐级精修：
//   L2: 搜索 ±4px（等效全分辨率 ±16px），捕捉大运动
//   L1: 以上层结果为搜索中心，搜索 ±4px（±8px 精修）
//   L0: 以 L1 结果为中心，搜索 ±4px（±4px 精修）
//   等效搜索范围 ±28px，远超单层 ±4px
//
// 输出：vec4(motion.xy, normalizedSad, 1.0)
//   - rg: 总运动矢量（本级累计），归一化到 [0,1]，范围 ±MAX_OFFSET
//   - b : 归一化 SAD（0=完美匹配，1=完全失配）
//
// ES 2.0 兼容：所有循环边界为常量
// ─────────────────────────────────────────────────────────

#define BLOCK_SIZE    16.0
#define SEARCH_RADIUS 4.0
#define BLOCK_STEP    2.0
#define SAMPLES_PER_BLOCK 64.0
// 3 层金字塔最大总偏移：4*(1+2+4) = 28
#define MAX_OFFSET    28.0

uniform sampler2D uRefY;            // 参考帧 Y（本级分辨率）
uniform sampler2D uAltY;            // 目标帧 Y（本级分辨率）
uniform sampler2D uSearchCenter;    // 上层运动矢量场（归一化），L2 绑定 1×1 零纹理
uniform vec2 uTexelSize;            // vec2(1.0/levelW, 1.0/levelH) 本级纹素大小
uniform float uCoarseScale;         // 上层→本级缩放（L2=0, L1=2, L0=2）

varying vec2 vTexCoord;

void main() {
    vec2 blockIdx = floor(gl_FragCoord.xy);
    vec2 blockCenter = blockIdx * BLOCK_SIZE + BLOCK_SIZE * 0.5;

    // 从上层运动矢量场获取搜索中心（本级像素坐标）
    vec2 searchCenter = vec2(0.0);
    if (uCoarseScale > 0.5) {
        vec2 coarseMotion = texture2D(uSearchCenter, vTexCoord).xy;
        // 反归一化（上层像素坐标）→ 缩放到本级
        searchCenter = (coarseMotion - 0.5) * (2.0 * MAX_OFFSET) * uCoarseScale;
    }

    float bestSAD = 1e10;
    vec2 bestOffset = searchCenter;

    // 搜索窗口：以 searchCenter 为中心，±SEARCH_RADIUS
    for (float dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy += 1.0) {
        for (float dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += 1.0) {
            vec2 candidate = searchCenter + vec2(dx, dy);
            float sad = 0.0;

            for (float by = 0.0; by < BLOCK_SIZE; by += BLOCK_STEP) {
                for (float bx = 0.0; bx < BLOCK_SIZE; bx += BLOCK_STEP) {
                    vec2 refPos = blockCenter + vec2(bx, by);
                    vec2 altPos = refPos + candidate;
                    float refVal = texture2D(uRefY, refPos * uTexelSize).r;
                    float altVal = texture2D(uAltY, altPos * uTexelSize).r;
                    sad += abs(refVal - altVal);
                }
            }

            if (sad < bestSAD) {
                bestSAD = sad;
                bestOffset = candidate;
            }
        }
    }

    // 归一化：offset ∈ [-MAX_OFFSET, MAX_OFFSET] → [0, 1]
    vec2 normalized = (bestOffset + MAX_OFFSET) / (2.0 * MAX_OFFSET);
    float normalizedSad = clamp(bestSAD / SAMPLES_PER_BLOCK, 0.0, 1.0);
    gl_FragColor = vec4(normalized, normalizedSad, 1.0);
}
