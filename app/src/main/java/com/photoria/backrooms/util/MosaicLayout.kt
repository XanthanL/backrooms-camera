package com.photoria.backrooms.util

/**
 * 连拍拼图的格子布局（纯数学，零 Android 依赖，可在 JVM 上直接断言）。
 *
 * 与 `android.graphics.Canvas` 的绘制部分分开，是为了让「九宫格到底排成几行几列、
 * 画布多大、每格落在哪」这件事在没有真机时也能被验证 —— 排版算错会让照片被裁掉，
 * 而这恰好是最不容易靠肉眼在预览里发现的一类错误。
 */
data class MosaicCell(val index: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/** 一张拼图的画布尺寸与格子位置 */
data class MosaicLayout(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val cells: List<MosaicCell>
) {
    companion object {

        /**
         * 由一帧的宽高推出格子尺寸：长边固定为 [longSide]，短边按帧比例缩放。
         *
         * 竖屏 3:4 的帧会得到 405×540，而不是被拉成正方形 —— 拼图把每帧等比
         * 居中贴入格子，格子比例跟帧比例对不上时就会出现黑边。
         *
         * @return 格子宽高；帧尺寸非法时返回 null
         */
        fun cellFor(srcWidth: Int, srcHeight: Int, longSide: Int): Pair<Int, Int>? {
            if (srcWidth <= 0 || srcHeight <= 0 || longSide <= 0) return null
            return if (srcWidth >= srcHeight) {
                longSide to (longSide.toLong() * srcHeight / srcWidth).toInt().coerceAtLeast(1)
            } else {
                (longSide.toLong() * srcWidth / srcHeight).toInt().coerceAtLeast(1) to longSide
            }
        }

        /**
         * 按「左→右、上→下」的顺序排布格子。
         *
         * @param cellCount 帧数（< columns×rows 时尾部留空，画布按实际格数收缩）
         * @param columns 列数
         * @param cellWidth 单格宽
         * @param cellHeight 单格高
         * @param gap 格间距，四周一并留出
         */
        fun of(
            cellCount: Int,
            columns: Int,
            cellWidth: Int,
            cellHeight: Int,
            gap: Int
        ): MosaicLayout {
            require(cellCount > 0) { "拼图至少 1 格" }
            require(columns > 0) { "列数必须为正" }
            require(cellWidth > 0 && cellHeight > 0) { "单格尺寸非法：${cellWidth}x$cellHeight" }
            require(gap >= 0) { "间距不能为负" }

            val cols = minOf(columns, cellCount)
            val rows = (cellCount + cols - 1) / cols
            val cells = ArrayList<MosaicCell>(cellCount)
            for (i in 0 until cellCount) {
                val col = i % cols
                val row = i / cols
                cells.add(
                    MosaicCell(
                        index = i,
                        x = gap + col * (cellWidth + gap),
                        y = gap + row * (cellHeight + gap),
                        width = cellWidth,
                        height = cellHeight
                    )
                )
            }
            return MosaicLayout(
                width = cols * cellWidth + (cols + 1) * gap,
                height = rows * cellHeight + (rows + 1) * gap,
                columns = cols,
                rows = rows,
                cells = cells
            )
        }
    }
}
