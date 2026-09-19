// ─────────────────────────────────────────────────────────
// 直方图取样用的 4-tap 盒式降采样
//
// 为什么不用 LINEAR 滤波单点取样：把上千像素高的画面缩到 128×96 时，
// 单点取样等于每个输出像素只「抽」一个源像素，细小高光（灯泡、反光）
// 会被整片跳过，直方图右尾假空。4-tap 铺在源像素盒上（uStep 由调用方
// 按「一个输出像素覆盖多少源像素」算出），至少能覆盖盒内 4 处。
// ─────────────────────────────────────────────────────────

precision mediump float;

uniform sampler2D uTexture;
uniform vec2 uStep;    // 邻域取样偏移（UV 单位）

varying vec2 vTexCoord;

void main() {
    vec4 sum = texture2D(uTexture, vTexCoord + vec2(-uStep.x, -uStep.y));
    sum += texture2D(uTexture, vTexCoord + vec2(uStep.x, -uStep.y));
    sum += texture2D(uTexture, vTexCoord + vec2(-uStep.x, uStep.y));
    sum += texture2D(uTexture, vTexCoord + vec2(uStep.x, uStep.y));
    gl_FragColor = sum * 0.25;
}
