package com.photoria.backrooms.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.min

/**
 * 连拍九宫格拼图的绘制部分（[MosaicLayout] 只管坐标，这里把位图贴进去）。
 *
 * 分两个文件是因为排版是纯数学（能在 JVM 上断言「九格到底排成 3×3 且互不重叠」），
 * 而这一半依赖 android.graphics.Canvas，只能在真机上看效果。
 */
object MosaicComposer {

    /**
     * 贴出拼图。帧数少于格数时尾部留空（画布按实际格数算，不会多出一块背景）。
     *
     * 每帧等比放进格子内居中，绝不拉伸：连拍是同一台相机同一画幅，
     * 正常路径下正好铺满；这个保护只在异常尺寸时生效。
     *
     * @return 新位图（调用方负责 recycle）；无帧可贴时返回 null
     */
    fun compose(
        frames: List<Bitmap>,
        layout: MosaicLayout,
        backgroundArgb: Int = 0xFF000000.toInt()
    ): Bitmap? {
        if (frames.isEmpty()) return null
        val out = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(backgroundArgb)
        // 双线性：缩略图直接贴到格子里，不开过滤会有明显锯齿
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        layout.cells.forEachIndexed { i, cell ->
            val src = frames.getOrNull(i) ?: return@forEachIndexed
            if (src.isRecycled) return@forEachIndexed
            val dst = fitCenter(src.width, src.height, cell.x, cell.y, cell.width, cell.height)
            canvas.drawBitmap(src, null, dst, paint)
        }
        return out
    }

    /** 等比放入并居中后的目标矩形 */
    private fun fitCenter(
        srcW: Int,
        srcH: Int,
        x: Int,
        y: Int,
        cellW: Int,
        cellH: Int
    ): Rect {
        if (srcW <= 0 || srcH <= 0) return Rect(x, y, x + cellW, y + cellH)
        val scale = min(cellW.toFloat() / srcW, cellH.toFloat() / srcH)
        val w = (srcW * scale).toInt().coerceAtLeast(1)
        val h = (srcH * scale).toInt().coerceAtLeast(1)
        val left = x + (cellW - w) / 2
        val top = y + (cellH - h) / 2
        return Rect(left, top, left + w, top + h)
    }
}
