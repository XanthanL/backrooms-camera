package com.photoria.backrooms.gl

import kotlin.math.max
import kotlin.math.min

/**
 * 调色曲线内核（X 批）—— PS「曲线」面板的 CPU 端。
 *
 * 控制点经 Fritsch–Carlson 单调三次样条插值后，采样成 256 格 LUT，
 * 四通道（RGB 合成 + R + G + B）打包进一张 256×1 RGBA 纹理，
 * 由调色 pass 逐通道采样应用（先合成后单通道，与 PS 顺序一致）。
 *
 * 为什么是单调样条：PS 也强制曲线单调不减 —— 非单调会把亮部拉回暗部，
 * 产生无法挽回的色调分离。Fritsch–Carlson 在不超样（无过冲振铃）的
 * 前提下保持段内单调，是"手感接近 PS、数学上不会翻车"的那一个。
 *
 * 纯 Kotlin：无 Android/GL 依赖，SmokeMain 可直接断言插值与打包。
 */
object CurveEngine {

    const val LUT_SIZE = 256
    const val MAX_POINTS = 10

    /** 通道键（持久化/GL 打包共用）。合成曲线放 alpha 分量。 */
    val CHANNELS = listOf("curve_rgb", "curve_r", "curve_g", "curve_b")

    val CHANNEL_LABELS = mapOf(
        "curve_rgb" to "RGB", "curve_r" to "R", "curve_g" to "G", "curve_b" to "B",
    )

    /** 通道键 → LUT RGBA 分量索引 */
    fun channelIndex(key: String): Int = CHANNELS.indexOf(key)

    /** 对角线（未调）控制点，flat 形式 [x0,y0,x1,y1,...] */
    fun defaultPoints(): List<Float> = listOf(0f, 0f, 1f, 1f)

    fun isDefault(points: List<Float>): Boolean {
        if (points.size <= 4) return true
        // 任何中间点离开对角线即非默认
        var i = 0
        while (i + 1 < points.size) {
            if (kotlin.math.abs(points[i] - points[i + 1]) > 1e-4f) return false
            i += 2
        }
        return true
    }

    /**
     * 单调三次样条求值（Fritsch–Carlson）。
     *
     * @param points flat [x,y,...]，要求按 x 升序（UI 维护该不变量）
     * 输入 x∈[0,1]（越界钳到端点值），输出钳到 [0,1]。
     */
    fun evaluate(points: List<Float>, x: Float): Float {
        val n = points.size / 2
        if (n == 0) return x
        if (n == 1) return points[1].coerceIn(0f, 1f)
        val xs = FloatArray(n) { points[it * 2] }
        val ys = FloatArray(n) { points[it * 2 + 1] }
        val xv = x.coerceIn(xs[0], xs[n - 1])

        // 段斜率 D_k
        val d = FloatArray(n - 1)
        for (k in 0 until n - 1) {
            val dx = xs[k + 1] - xs[k]
            d[k] = if (dx <= 1e-6f) 0f else (ys[k + 1] - ys[k]) / dx
        }
        // 节点切线 m_k：两端单侧差商，内部算术平均
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = (d[k - 1] + d[k]) * 0.5f
        // 单调性修正：a²+b²>9 时缩到 3/√(a²+b²) 圆上
        for (k in 0 until n - 1) {
            if (d[k] == 0f) {
                m[k] = 0f; m[k + 1] = 0f
            } else {
                val a = m[k] / d[k]
                val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) {
                    val tau = 3f / kotlin.math.sqrt(s)
                    m[k] = tau * a * d[k]
                    m[k + 1] = tau * b * d[k]
                }
            }
        }
        // 定位段（xs 升序，线性扫描即可 —— n≤10）
        var seg = n - 2
        for (k in 0 until n - 1) {
            if (xv <= xs[k + 1] || k == n - 2) { seg = k; if (xv <= xs[k + 1]) break }
        }
        val x0 = xs[seg]; val x1 = xs[seg + 1]
        val h = x1 - x0
        if (h <= 1e-6f) return ys[seg].coerceIn(0f, 1f)
        val t = (xv - x0) / h
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * ys[seg] +
            (t3 - 2 * t2 + t) * h * m[seg] +
            (-2 * t3 + 3 * t2) * ys[seg + 1] +
            (t3 - t2) * h * m[seg + 1]
        return y.coerceIn(0f, 1f)
    }

    /** 单通道 LUT（256 项，每项 = evaluate(i/255)） */
    fun buildLut(points: List<Float>?): FloatArray {
        val out = FloatArray(LUT_SIZE)
        if (points == null || points.size < 4) {
            for (i in 0 until LUT_SIZE) out[i] = i / 255f
        } else {
            for (i in 0 until LUT_SIZE) out[i] = evaluate(points, i / 255f)
        }
        return out
    }

    /**
     * 四通道 → 256×1 RGBA 字节序 LUT（R=r 曲线, G=g, B=b, A=RGB 合成）。
     * 缺省/对角通道直接落斜坡（identity），[isIdentity] 据此整图判空转。
     */
    fun buildRgbaBytes(curves: Map<String, List<Float>>): ByteArray {
        val out = ByteArray(LUT_SIZE * 4)
        val luts = CHANNELS.map { ch -> buildLut(curves[ch]?.takeIf { it.size >= 4 }) }
        for (i in 0 until LUT_SIZE) {
            // 斜坡判定复用 buildLut(null) 语义：size<4 的点表按斜坡处理
            val src = i * 4
            out[src] = toByte(luts[1][i]); out[src + 1] = toByte(luts[2][i])
            out[src + 2] = toByte(luts[3][i]); out[src + 3] = toByte(luts[0][i])
        }
        return out
    }

    private fun toByte(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** 整图 = 恒等斜坡 → 曲线不改变画面，管线跳过采样 */
    fun isIdentity(bytes: ByteArray): Boolean {
        if (bytes.size != LUT_SIZE * 4) return false
        for (i in 0 until LUT_SIZE) {
            for (c in 0 until 4) {
                if ((bytes[i * 4 + c].toInt() and 0xFF) != i) return false
            }
        }
        return true
    }

    /**
     * UI 拖点钳制：x 钳在左右邻点之间（留 eps 防竖段），y 钳在
     * 相邻 y 之间保持单调、再钳 [0,1]。端点只许动 y。
     */
    fun clampDrag(points: List<Float>, index: Int, nx: Float, ny: Float): Pair<Float, Float> {
        val n = points.size / 2
        val eps = 1f / 512f
        val isEnd = index == 0 || index == n - 1
        val lo = if (index == 0) 0f else points[(index - 1) * 2] + (if (isEnd) 0f else eps)
        val hi = if (index == n - 1) 1f else points[(index + 1) * 2] - (if (isEnd) 0f else eps)
        val x = if (isEnd) points[index * 2] else nx.coerceIn(min(lo, hi), max(lo, hi))
        val yLo = if (index == 0) 0f else points[(index - 1) * 2 + 1]
        val yHi = if (index == n - 1) 1f else points[(index + 1) * 2 + 1]
        val y = ny.coerceIn(min(yLo, yHi), max(yLo, yHi)).coerceIn(0f, 1f)
        return Pair(x, y)
    }

    /** 在 flat 点表插入新点（保持 x 序），超上限返回原表 */
    fun insertPoint(points: List<Float>, x: Float, y: Float): List<Float> {
        if (points.size / 2 >= MAX_POINTS) return points
        val out = points.toMutableList()
        var at = out.size / 2
        for (k in 0 until out.size / 2) {
            if (out[k * 2] > x) { at = k; break }
        }
        out.add(at * 2, y)
        out.add(at * 2, x)
        return out
    }

    fun removePoint(points: List<Float>, index: Int): List<Float> {
        val n = points.size / 2
        if (index == 0 || index == n - 1 || n <= 2) return points
        val out = points.toMutableList()
        out.removeAt(index * 2 + 1)
        out.removeAt(index * 2)
        return out
    }
}
