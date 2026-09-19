package com.photoria.backrooms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.photoria.backrooms.gl.HistogramBins
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow

// 直方图通道色：在暗黄底上比纯 R/G/B 更易分辨
private val HistRed = Color(0xFFFF6B6B)
private val HistGreen = Color(0xFF6BE58A)
private val HistBlue = Color(0xFF6FA8FF)

/**
 * 取景器实时直方图（RGB + 亮度）。
 *
 * 亮度为荧光黄填充，R/G/B 为半透明描边 —— 与专业相机的
 * 「亮度堆叠 + 通道轮廓」读法一致：先看整体曝光分布，再看哪条通道贴边。
 * 四条曲线共用同一归一化比例（[HistogramBins.peak]），
 * 因此通道之间的相对高低是可信的。
 *
 * 接收 [State] 并在本叶子组件内读取：直方图约 6Hz 刷新，
 * 若在 CameraScreen 里解包会让整屏跟着重组。
 */
@Composable
fun HistogramBox(binsState: State<HistogramBins?>, modifier: Modifier = Modifier) {
    val bins = binsState.value
    if (bins == null) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BackroomsShadow.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 96.dp, height = 60.dp)) {
            val n = HistogramBins.BINS
            val scaleX = size.width / (n - 1)
            val scaleY = size.height / bins.peak

            /** 返回该通道的折线路径；fill=true 时闭合到底边用于填充 */
            fun buildPath(values: IntArray, fill: Boolean): Path {
                val path = Path()
                if (fill) path.moveTo(0f, size.height)
                for (i in 0 until n) {
                    val y = size.height - values[i] * scaleY
                    val p = Offset(i * scaleX, y)
                    if (fill || i > 0) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                }
                if (fill) {
                    path.lineTo(size.width, size.height)
                    path.close()
                }
                return path
            }

            drawPath(
                path = buildPath(bins.luma, fill = true),
                color = BackroomsYellow.copy(alpha = 0.45f)
            )
            listOf(
                bins.red to HistRed,
                bins.green to HistGreen,
                bins.blue to HistBlue
            ).forEach { (values, color) ->
                drawPath(
                    path = buildPath(values, fill = false),
                    color = color.copy(alpha = 0.75f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
