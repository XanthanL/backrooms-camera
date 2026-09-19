package com.photoria.backrooms.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 拍照/录像按钮（后室主题，U1c 手感重塑）。
 *
 * 拍照模式：
 *   外圈：奶油黄大圆环
 *   内圈：荧光黄实心圆（拍照时闪亮）
 *   多帧处理中：外圈叠一段旋转荧光黄弧（比灰掉更有"在干活"的反馈）
 *
 * 录像模式（未录制）：
 *   外圈：奶油黄大圆环
 *   内圈：红色实心圆（功能性警示）
 *
 * 录像模式（录制中）：
 *   外圈：红色大圆环
 *   内圈：红色圆角方形（停止图标）
 *
 * 手感（U1c）：
 *   - 按下走弹簧缩放（0.9 → 回弹），Press 事件真正接到 interactionSource 上
 *     （旧版 interactionSource 没接 pointerInput，按压缩放其实从未触发过）
 *   - 抬起才出片 + 触感震动，中途滑出取消
 *   - 全部动画只驱动 graphicsLayer，不触发重组
 *
 * @param isCapturing 是否正在拍照（内圈提亮）
 * @param isRecording 是否正在录制
 * @param isVideoMode 是否为录像模式
 * @param enabled 是否可点击（拍照处理中禁用，防止重复触发）
 * @param onClick 点击回调
 */
@Composable
fun CaptureButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isVideoMode: Boolean = false,
    isRecording: Boolean = false,

    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current

    // 按下状态由 detectTapGestures.onPress 手动驱动：
    // 旧版把 interactionSource 挂在 collectIsPressedAsState 上却没接到
    // pointerInput，按压缩放其实从未真正触发过 —— 这里直接接上
    var pressed by remember { mutableStateOf(false) }

    // 按压缩放：0.9 起步、弹簧回弹（相机快门"陷下去再弹回来"的记忆点）
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = PhotoriaGlass.PressScale,
        label = "captureScale"
    )

    // 闪白效果：拍照模式下内圈在拍照时变亮
    val innerAlpha by animateFloatAsState(
        targetValue = if (isCapturing && !isVideoMode) 1f else 0.85f,
        label = "innerAlpha"
    )

    // 后室配色：外圈奶油黄，内圈荧光黄；录制中红色（功能性警示）
    val outerBorderColor = when {
        isRecording -> Color.Red
        else -> BackroomsCream
    }

    // 禁用时整体降低不透明度（拍照处理中）
    val disabledAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.55f,
        label = "disabledAlpha"
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = disabledAlpha
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        pressed = true
                        // 等到手指抬起才算一次完整按压；滑出时同样复位
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 外圈
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(width = 3.dp, color = outerBorderColor, shape = CircleShape)
        )

        // 多帧处理中：旋转弧进度环（仅拍照处理期间存在，不会常驻耗电）
        if (!enabled && !isVideoMode && !isRecording) {
            BusyRing()
        }

        // 内圈：根据模式显示不同形状
        when {
            isRecording -> {
                // 录制中：红色圆角方形（停止按钮图标）
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red)
                )
            }
            isVideoMode -> {
                // 录像模式未录制：红色实心圆
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = 0.9f))
                )
            }
            else -> {
                // 拍照模式：荧光黄实心圆
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(BackroomsYellow.copy(alpha = innerAlpha))
                )
            }
        }
    }
}

/** 处理中旋转弧：80° 荧光黄短弧，900ms 一圈；只失效 draw，不重组 */
@Composable
private fun BusyRing() {
    val spin = rememberInfiniteTransition(label = "shutterBusySpin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "shutterBusyAngle"
    )
    Canvas(modifier = Modifier.size(66.dp)) {
        val stroke = 3.dp.toPx()
        drawArc(
            color = BackroomsYellow,
            startAngle = angle,
            sweepAngle = 80f,
            useCenter = false,
            style = Stroke(width = stroke),
            size = Size(size.width - stroke, size.height - stroke),
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
        )
    }
}
