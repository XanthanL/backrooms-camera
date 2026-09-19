package com.photoria.backrooms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.camera.LevelMath
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import kotlin.math.abs

/**
 * 气泡水平仪（取景器浮层）。
 *
 * 两条固定刻度之间留出空隙，气泡（竖直亮条）漂到正中间即水平；
 * 归零时刻度与气泡一起变荧光黄。满量程 ±[FULL_SCALE_DEG] 度。
 *
 * 接收 [StateFlow] 并在本叶子组件内收集：传感器读数频率远高于 6Hz，
 * 若在 CameraScreen 里解包会让整屏跟着高频重组。
 * 读数为 null（传感器尚未出值或无可用传感器）时不绘制任何东西。
 */
@Composable
fun BubbleLevel(
    roll: StateFlow<Float?>,
    modifier: Modifier = Modifier
) {
    // 在本叶子组件内收集：传感器读数频率远高于 6Hz
    val value by roll.collectAsState()
    val rollDeg = value ?: return
    val level = abs(rollDeg) <= LevelMath.LEVEL_TOLERANCE_DEG

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(BackroomsShadow.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(width = 132.dp, height = 20.dp)) {
                val halfW = size.width / 2f
                val top = 2.dp.toPx()
                val bottom = size.height - 2.dp.toPx()
                val idle = BackroomsCream.copy(alpha = 0.5f)
                val active = BackroomsYellow
                val mark = if (level) active else idle

                // 固定参考刻度（居中对称，气泡夹在中间即水平）
                val gap = 8.dp.toPx()
                drawLine(
                    color = mark,
                    start = Offset(halfW - gap, top),
                    end = Offset(halfW - gap, bottom),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = mark,
                    start = Offset(halfW + gap, top),
                    end = Offset(halfW + gap, bottom),
                    strokeWidth = 2.dp.toPx()
                )

                // 气泡：向高处漂 = 与「右沿偏低」的读数反号
                val t = (-rollDeg / FULL_SCALE_DEG).coerceIn(-1f, 1f)
                val travel = halfW - gap - 4.dp.toPx()
                drawLine(
                    color = active,
                    start = Offset(halfW + t * travel, top),
                    end = Offset(halfW + t * travel, bottom),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            Text(
                text = String.format("%+.1f°", rollDeg),
                color = if (level) BackroomsYellow else BackroomsCream.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp).width(40.dp)
            )
        }
    }
}

/** 气泡打满两端的量程（度）：超过它气泡停在边缘 */
private const val FULL_SCALE_DEG = 12f
