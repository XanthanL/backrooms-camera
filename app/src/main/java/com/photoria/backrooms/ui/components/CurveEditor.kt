package com.photoria.backrooms.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.gl.CurveEngine
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.BackroomsYellowOnDark
import kotlin.math.hypot

/**
 * 调色曲线编辑器（X 批）—— PS 曲线的触屏版。
 *
 * 交互（与 PS 桌面逻辑对齐，做了触屏裁剪）：
 *   - 按住控制点拖动（端点只许动 Y；x 钳在邻点之间、y 钳单调）
 *   - 空白处按下并拖动 → 落一个新点（上限 [CurveEngine.MAX_POINTS]）
 *   - 长按控制点 ≥500ms → 删除（端点不可删）
 *   - 通道切换：RGB 合成 / R / G / B；未选中通道以淡色垫底显示
 *
 * 拖拽期间只更新本地临时表，松手/删点时一次性回写 VM ——
 * 曲线是稀疏事件，没必要每个 move 都触发 JSON 落盘与 LUT 重建。
 */
@Composable
fun CurveEditor(
    curves: Map<String, List<Float>>,
    onPointsChange: (String, List<Float>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    var channel by remember { mutableStateOf(CurveEngine.CHANNELS[0]) }
    var live by remember { mutableStateOf<List<Float>?>(null) }

    val points = live ?: curves[channel] ?: CurveEngine.defaultPoints()

    Column(modifier = modifier) {
        // ── 通道选择 + 还原 ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CurveEngine.CHANNELS.forEach { ch ->
                val chPts = curves[ch]
                CurveChannelChip(
                    label = CurveEngine.CHANNEL_LABELS[ch] ?: ch,
                    color = channelColor(ch),
                    selected = ch == channel,
                    modified = chPts != null && !CurveEngine.isDefault(chPts),
                    onClick = { channel = ch; live = null }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "还原曲线",
                color = BackroomsCream.copy(alpha = 0.85f),
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        live = null
                        onReset()
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        // ── 画布 + 手势 ────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BackroomsShadow.copy(alpha = 0.55f))
                .pointerInput(channel, curves) {
                    val hitPx = 26.dp.toPx()
                    awaitEachGesture {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w <= 0f || h <= 0f) return@awaitEachGesture
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val base = live ?: curves[channel] ?: CurveEngine.defaultPoints()

                        fun norm(p: Offset) =
                            Pair((p.x / w).coerceIn(0f, 1f), (1f - p.y / h).coerceIn(0f, 1f))

                        fun nearest(p: Offset): Int {
                            val nx = p.x / w; val ny = 1f - p.y / h
                            var best = -1; var bestD = Float.MAX_VALUE
                            for (i in 0 until base.size / 2) {
                                val d = hypot((base[i * 2] - nx) * w, (base[i * 2 + 1] - ny) * h)
                                if (d < bestD) { bestD = d; best = i }
                            }
                            return if (bestD <= hitPx) best else -1
                        }

                        var index = nearest(down.position)
                        var working = base
                        if (index < 0 && working.size / 2 < CurveEngine.MAX_POINTS) {
                            val (nx, ny) = norm(down.position)
                            working = CurveEngine.insertPoint(working, nx, ny)
                            index = nearest(down.position).let {
                                if (it >= 0) it else working.size / 2 - 1
                            }
                        }
                        if (index < 0) return@awaitEachGesture

                        live = working
                        val startPx = down.position
                        var deleted = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            change.consume()
                            // 长按删点：按住 ≥500ms 且没怎么动，删掉非端点的那颗
                            val still = hypot(
                                change.position.x - startPx.x,
                                change.position.y - startPx.y
                            ) < 10f
                            if (still && change.uptimeMillis - down.uptimeMillis >= 500L &&
                                index > 0 && index < working.size / 2 - 1
                            ) {
                                working = CurveEngine.removePoint(working, index)
                                deleted = true
                                break
                            }
                            val (nx, ny) = norm(change.position)
                            val clamped = CurveEngine.clampDrag(working, index, nx, ny)
                            if (clamped.first != working[index * 2] || clamped.second != working[index * 2 + 1]) {
                                val updated = working.toMutableList()
                                updated[index * 2] = clamped.first
                                updated[index * 2 + 1] = clamped.second
                                working = updated
                                live = working
                            }
                        }
                        live = null
                        if (deleted || working != curves[channel]) onPointsChange(channel, working)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                fun toPx(nx: Float, ny: Float) = Offset(nx * w, (1f - ny) * h)

                // 三分网格 + 对角参考线
                val grid = BackroomsCream.copy(alpha = 0.12f)
                for (i in 1..2) {
                    drawLine(grid, toPx(i / 3f, 1f), toPx(i / 3f, 0f), 1f)
                    drawLine(grid, toPx(0f, i / 3f), toPx(1f, i / 3f), 1f)
                }
                drawLine(BackroomsCream.copy(alpha = 0.18f), toPx(0f, 0f), toPx(1f, 1f), 1f)

                // 未选中通道淡色垫底 → 当前通道 → 控制点
                CurveEngine.CHANNELS.forEach { ch ->
                    if (ch != channel) {
                        curves[ch]?.let { pts ->
                            if (pts.size >= 4) drawSplineCurve(pts, channelColor(ch).copy(alpha = 0.22f), 2f)
                        }
                    }
                }
                drawSplineCurve(points, channelColor(channel), 3.5f)
                val selected = channelColor(channel)
                for (i in 0 until points.size / 2) {
                    val c = toPx(points[i * 2], points[i * 2 + 1])
                    drawCircle(BackroomsShadow, radius = 7f, center = c)
                    drawCircle(
                        if (i == 0 || i == points.size / 2 - 1) selected else BackroomsCream,
                        radius = 5f, center = c
                    )
                }
            }
        }

        Text(
            text = "拖动调节点 · 空白按下可加点 · 长按删点 · 上提亮部、下压暗部",
            color = BackroomsCream.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 用样条求值画 64 段折线（所见 = shader 里 LUT 采样的同一条曲线） */
private fun DrawScope.drawSplineCurve(points: List<Float>, color: Color, strokeDp: Float) {
    if (points.size < 4) return
    val path = Path()
    val steps = 64
    val w = size.width
    val h = size.height
    for (s in 0..steps) {
        val x = s / steps.toFloat()
        val y = CurveEngine.evaluate(points, x)
        val px = x * w
        val py = (1f - y) * h
        if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    // 线宽按画布宽度折算（≈340dp 设计宽），小屏不糊、大屏不细
    drawPath(path, color, style = Stroke(width = strokeDp * w / 340f))
}

/** 通道线色：合成=荧光黄，R/G/B 高饱和原色压暗一档，奶油底上不刺眼 */
private fun channelColor(channel: String): Color = when (channel) {
    "curve_r" -> Color(0xFFE5675F)
    "curve_g" -> Color(0xFF7BD97A)
    "curve_b" -> Color(0xFF6FA8FF)
    else -> BackroomsYellow
}

@Composable
private fun CurveChannelChip(
    label: String,
    color: Color,
    selected: Boolean,
    modified: Boolean,
    onClick: () -> Unit
) {
    Box(contentAlignment = Alignment.Center) {
        Text(
            text = label,
            color = if (selected) BackroomsYellowOnDark else BackroomsCream.copy(alpha = 0.8f),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) BackroomsYellow else BackroomsCream.copy(alpha = 0.12f))
                .border(
                    1.dp,
                    if (selected) BackroomsYellow else color.copy(alpha = 0.6f),
                    RoundedCornerShape(50)
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        // 该通道已被改动的小色点
        if (modified && !selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}
