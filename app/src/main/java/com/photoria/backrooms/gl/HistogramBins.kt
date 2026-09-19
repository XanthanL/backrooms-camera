package com.photoria.backrooms.gl

import java.nio.ByteBuffer

/**
 * 实时直方图统计结果：R/G/B/亮度 四条等宽桶计数。
 *
 * 纯 Kotlin、不碰 GL，便于单独推演与日后补测试。
 *
 * @param red 红通道计数
 * @param green 绿通道计数
 * @param blue 蓝通道计数
 * @param luma Rec.601 亮度计数
 * @param peak 四条桶中的最大计数，绘制时按它归一化（各通道共用同一比例，
 *   才能像相机那样看出「红通道比绿通道更亮」的相对关系）
 */
class HistogramBins(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luma: IntArray,
    val peak: Int
) {
    companion object {
        /** 桶数：256 级 → 每桶 4 级 */
        const val BINS = 64
        private const val BIN_SHIFT = 2

        /**
         * 从 RGBA/UNSIGNED_BYTE 回读缓冲统计。
         *
         * glReadPixels 的第 0 行对应纹理底部，与屏幕上下相反；
         * 直方图只关心「有多少像素落在某级」，与位置无关，故无需翻行。
         */
        fun from(buffer: ByteBuffer, width: Int, height: Int): HistogramBins {
            val r = IntArray(BINS)
            val g = IntArray(BINS)
            val b = IntArray(BINS)
            val y = IntArray(BINS)
            buffer.position(0)

            val total = width * height
            for (i in 0 until total) {
                val rv = buffer.get().toInt() and 0xFF
                val gv = buffer.get().toInt() and 0xFF
                val bv = buffer.get().toInt() and 0xFF
                buffer.get() // alpha：取景统计不需要

                r[rv shr BIN_SHIFT]++
                g[gv shr BIN_SHIFT]++
                b[bv shr BIN_SHIFT]++
                // 定点 Rec.601（×256）避免逐像素浮点乘法
                val lv = (rv * 76 + gv * 150 + bv * 30) shr 8
                y[lv.coerceAtMost(255) shr BIN_SHIFT]++
            }

            var peak = 0
            for (i in 0 until BINS) {
                if (r[i] > peak) peak = r[i]
                if (g[i] > peak) peak = g[i]
                if (b[i] > peak) peak = b[i]
                if (y[i] > peak) peak = y[i]
            }
            return HistogramBins(r, g, b, y, peak.coerceAtLeast(1))
        }
    }
}
