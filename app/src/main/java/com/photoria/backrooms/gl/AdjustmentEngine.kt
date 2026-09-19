package com.photoria.backrooms.gl

import kotlin.math.abs

/**
 * 实时调色引擎的参数层（W1）—— PS/Lightroom 式"影调 → 色温 → 色域 → 饱和"。
 *
 * 本文件是**纯 Kotlin**（无 GL 依赖）：参数定义、归一化打包、identity 判断
 * 与 GLSL 源码字符串都在这里，SmokeMain 可以不开真机直接断言数学部分。
 *
 * 设计要点：
 *   - 所有滑杆统一 -100..100 的 UI 值域（曝光映射 ±1.5EV、色相 ±50°），
 *     打包成 36 个 float 一次过桥到 GL 线程 —— 避免 28 个 setter。
 *   - 全 0 = identity：FilterChain 直接跳过调整 pass，
 *     不用调色的用户零成本（不多一个 FBO、不多一趟全屏绘制）。
 *   - 处理顺序对齐 Lightroom：曝光 → 区域影调 → 对比度 → 白平衡 →
 *     HSL 色域 → 自然饱和度 → 饱和度。顺序错了观感会完全不同。
 */
object AdjustmentEngine {

    /** 色相带顺序与 shader uBand 索引一致：红 黄 绿 青 蓝 洋红 */
    val BANDS = listOf("红", "黄", "绿", "青", "蓝", "洋红")

    /** 影调组（面板第一屏） */
    val TONE_KEYS = listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks")

    /** 色彩组 */
    val COLOR_KEYS = listOf("temperature", "tint", "vibrance", "saturation")

    /** 色相带键名后缀，顺序与 [BANDS] 一致 */
    val BAND_SUFFIX = listOf("red", "yellow", "green", "cyan", "blue", "magenta")

    /** 全部 UI 键（含 6 带 × hue/sat/lum） */
    val ALL_KEYS: List<String> = buildList {
        addAll(TONE_KEYS)
        addAll(COLOR_KEYS)
        for (b in BAND_SUFFIX) {
            add("hue_$b"); add("sat_$b"); add("lum_$b")
        }
    }

    /** 键 → 中文名（面板标签） */
    val LABELS = mapOf(
        "exposure" to "曝光", "contrast" to "对比度",
        "highlights" to "高光", "shadows" to "阴影",
        "whites" to "白色", "blacks" to "黑色",
        "temperature" to "色温", "tint" to "色调",
        "vibrance" to "自然饱和", "saturation" to "饱和度",
    )

    /** 打包后的 float 布局长度（与 [pack] 返回值一致） */
    const val PACK_SIZE = 36

    /**
     * UI 值（全部 -100..100）→ shader uniform 数组。
     *
     * 布局：
     *   [0..3]  uA0 = exposure(EV), contrast, highlights, shadows
     *   [4..7]  uA1 = whites, blacks, temperature, tint
     *   [8..11] uA2 = saturation, vibrance, bandsAny, -
     *   [12..35] uBand[6] × (hueShiftDeg, satDelta, lumDelta, -)
     *   —— 实际使用 12 + 24 = 36，与 [PACK_SIZE] 同步。
     */
    fun pack(values: Map<String, Float>): FloatArray {
        val out = FloatArray(36)
        fun norm(key: String) = (values[key] ?: 0f).coerceIn(-100f, 100f) / 100f
        out[0] = norm("exposure") * 1.5f  // ±100 → ±1.5 EV
        out[1] = norm("contrast")
        out[2] = norm("highlights")
        out[3] = norm("shadows")
        out[4] = norm("whites")
        out[5] = norm("blacks")
        out[6] = norm("temperature")
        out[7] = norm("tint")
        out[8] = norm("saturation")
        out[9] = norm("vibrance")
        val bandSuffix = BAND_SUFFIX
        var bandsAny = 0f
        bandSuffix.forEachIndexed { i, b ->
            val base = 12 + i * 4
            out[base] = norm("hue_$b") * 50f          // 色相偏移 ±50°
            out[base + 1] = norm("sat_$b")            // 饱和增量（shader 里 1+Δ）
            out[base + 2] = norm("lum_$b")            // 明度增量
            if (out[base] != 0f || out[base + 1] != 0f || out[base + 2] != 0f) bandsAny = 1f
        }
        out[10] = bandsAny  // shader 用它跳过全零时的 HSL 往返
        return out
    }

    /** 全零 = 不改变画面 → FilterChain 跳过调整 pass */
    fun isIdentity(packed: FloatArray): Boolean {
        for (v in packed) if (abs(v) > 1e-5f) return false
        return true
    }

    /** 内置预设（UI 值域），点一下就是一个能出片的起点 */
    val PRESETS = linkedMapOf(
        "通透" to mapOf(
            "contrast" to 18f, "highlights" to -15f, "shadows" to 22f,
            "whites" to 10f, "blacks" to -6f, "vibrance" to 28f,
            "temperature" to -5f,
        ),
        "胶片" to mapOf(
            "contrast" to 30f, "highlights" to -35f, "shadows" to 28f,
            "blacks" to -14f, "saturation" to -12f,
            "temperature" to 12f, "tint" to 6f,
        ),
        "浓郁" to mapOf(
            "contrast" to 25f, "vibrance" to 35f, "saturation" to 10f,
            "blacks" to 12f, "highlights" to -10f,
        ),
    )

    /** 键是否属于某个色相带（面板 HSL 页用） */
    fun bandOf(key: String): Int? = when (key.substringAfterLast("_")) {
        "red" -> 0; "yellow" -> 1; "green" -> 2
        "cyan" -> 3; "blue" -> 4; "magenta" -> 5
        else -> null
    }
}

/**
 * 调色 pass 的 GLSL ES 2.0 片元源码（无 #include，纯内联）。
 *
 * 注意 mediump 精度下 pow/对数在 0 附近的行为：所有入口色值先 clamp 到
 * [0,1]，色相权重用平滑三次曲线而非归一化除法，避免 0/0。
 */
object AdjustmentShaders {

    const val FRAGMENT = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform sampler2D uTexture;
        uniform sampler2D uCurve; // 256×1 RGBA：r/g/b 通道曲线，a=RGB 合成
        uniform float uCurveOn;   // 0 = 恒等曲线，跳过全部 LUT 采样
        uniform vec4 uA0;   // exposure(EV), contrast, highlights, shadows
        uniform vec4 uA1;   // whites, blacks, temperature, tint
        uniform vec4 uA2;   // saturation, vibrance, bandsAny, -
        uniform vec4 uBand[6]; // hueShiftDeg, satDelta, lumDelta, -
        varying vec2 vTexCoord;

        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

        // LUT 纹素中心对齐：值 v∈[0,1] ↔ 第 v*255 格，采样坐标再加半纹素
        float lutU(float v) { return (v * 255.0 + 0.5) / 256.0; }

        float hue2rgb(float v, float m1, float m2) {
            if (v < 0.0) v += 1.0;
            if (v > 1.0) v -= 1.0;
            if (v < 1.0 / 6.0) return m1 + (m2 - m1) * 6.0 * v;
            if (v < 2.0 / 6.0) return m2;
            if (v < 3.0 / 6.0) return m1 + (m2 - m1) * (2.0 / 3.0 - v) * 6.0;
            return m1;
        }

        vec3 rgb2hsl(vec3 c) {
            float mx = max(max(c.r, c.g), c.b);
            float mn = min(min(c.r, c.g), c.b);
            float l = (mx + mn) * 0.5;
            float d = mx - mn;
            if (d < 0.0005) return vec3(0.0, 0.0, l);
            float s = d / (1.0 - abs(2.0 * l - 1.0) + 0.0005);
            float h;
            if (mx >= c.r - 0.0001) {
                h = (c.g - c.b) / d;
                if (c.g < c.b) h += 6.0;
            } else if (mx >= c.g - 0.0001) {
                h = (c.b - c.r) / d + 2.0;
            } else {
                h = (c.r - c.g) / d + 4.0;
            }
            return vec3(h / 6.0, clamp(s, 0.0, 1.0), l);
        }

        vec3 hsl2rgb(vec3 hsl) {
            if (hsl.y < 0.0005) return vec3(hsl.z);
            float m2 = hsl.z < 0.5 ? hsl.z * (1.0 + hsl.y) : hsl.z + hsl.y - hsl.z * hsl.y;
            float m1 = 2.0 * hsl.z - m2;
            return vec3(
                hue2rgb(hsl.x + 1.0 / 3.0, m1, m2),
                hue2rgb(hsl.x, m1, m2),
                hue2rgb(hsl.x - 1.0 / 3.0, m1, m2)
            );
        }

        float angDist(float a, float b) {
            float d = abs(a - b);
            return min(d, 1.0 - d);
        }

        void main() {
            vec4 tex = texture2D(uTexture, vTexCoord);
            vec3 c = clamp(tex.rgb, 0.0, 1.0);

            // 0. 调色曲线（X）：先 RGB 合成、后单通道 —— PS 的应用顺序。
            //    放在曝光/影调之前：曲线定义这台机器的"底片反差"，
            //    后续区域影调在曲线后的亮度上工作，观感才与 LR 一致。
            if (uCurveOn > 0.5) {
                c.r = texture2D(uCurve, vec2(lutU(c.r), 0.5)).a;
                c.g = texture2D(uCurve, vec2(lutU(c.g), 0.5)).a;
                c.b = texture2D(uCurve, vec2(lutU(c.b), 0.5)).a;
                c.r = texture2D(uCurve, vec2(lutU(c.r), 0.5)).r;
                c.g = texture2D(uCurve, vec2(lutU(c.g), 0.5)).g;
                c.b = texture2D(uCurve, vec2(lutU(c.b), 0.5)).b;
            }

            // 1. 曝光（EV 乘性，高光侧由后续区域压回）
            c *= pow(2.0, uA0.x);

            // 2. 区域影调：按亮度掩蔽做局部曝光
            float l = dot(clamp(c, 0.0, 1.0), LUMA);
            c *= pow(2.0, uA0.z * 0.7 * smoothstep(0.5, 0.95, l));   // highlights
            c *= pow(2.0, uA0.w * 0.7 * (1.0 - smoothstep(0.05, 0.5, l))); // shadows
            c *= pow(2.0, uA1.x * 0.5 * smoothstep(0.65, 1.0, l));   // whites
            c += uA1.y * 0.12 * (1.0 - smoothstep(0.0, 0.35, l));    // blacks

            // 3. 对比度（S 形中点枢轴）
            c = clamp((c - 0.5) * (1.0 + uA0.y) + 0.5, 0.0, 1.0);

            // 4. 白平衡：色温沿红-蓝轴，色调沿绿-洋红轴
            c.r += uA1.z * 0.07; c.b -= uA1.z * 0.07;
            c.g -= uA1.w * 0.05; c.r += uA1.w * 0.025; c.b += uA1.w * 0.025;

            // 5. HSL 色域：6 带平滑权重（红黄绿青蓝洋红，间隔 60°）
            //    bandsAny（uA2.z）= CPU 侧「任一带被调过」标志：全零时
            //    跳过 rgb2hsl→hsl2rgb 往返，避免浮点往返误差白染每一帧
            vec3 hsl = rgb2hsl(clamp(c, 0.0, 1.0));
            if (uA2.z > 0.5 && hsl.y > 0.02) {
                float wSum = 0.0; float hueS = 0.0; float satS = 0.0; float lumS = 0.0;
                for (int i = 0; i < 6; i++) {
                    float center = float(i) * (1.0 / 6.0);
                    float w = max(0.0, 1.0 - angDist(hsl.x, center) * 6.0);
                    w = w * w * (3.0 - 2.0 * w);
                    wSum += w;
                    hueS += uBand[i].x * w;
                    satS += uBand[i].y * w;
                    lumS += uBand[i].z * w;
                }
                if (wSum > 0.001) {
                    hsl.x = fract(hsl.x + (hueS / wSum) / 360.0);
                    hsl.y = clamp(hsl.y * (1.0 + satS / wSum), 0.0, 1.0);
                    hsl.z = clamp(hsl.z * (1.0 + lumS / wSum), 0.0, 1.0);
                    c = hsl2rgb(hsl);
                }
            }

            // 6. 自然饱和（保护已饱和色与肤色）→ 全局饱和
            float gray = dot(c, LUMA);
            c = mix(c, vec3(gray), -uA2.y * (1.0 - hsl.y));
            gray = dot(c, LUMA);
            c = mix(vec3(gray), c, 1.0 + uA2.x);

            gl_FragColor = vec4(clamp(c, 0.0, 1.0), tex.a);
        }
    """
}
