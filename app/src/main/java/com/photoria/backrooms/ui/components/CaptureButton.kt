package com.photoria.backrooms.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 拍照/录像按钮（后室主题）。
 *
 * 拍照模式：
 *   外圈：奶油黄大圆环
 *   内圈：荧光黄实心圆（拍照时闪亮）
 *
 * 录像模式（未录制）：
 *   外圈：奶油黄大圆环
 *   内圈：红色实心圆（功能性警示）
 *
 * 录像模式（录制中）：
 *   外圈：红色大圆环
 *   内圈：红色圆角方形（停止图标）
 *
 * @param isCapturing 是否正在拍照（闪白效果）
 * @param isRecording 是否正在录制
 * @param isVideoMode 是否为录像模式
 * @param recordingDurationSec 录制时长（秒），录制中显示
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
    recordingDurationSec: Int = 0,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下缩放动画
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
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
        targetValue = if (enabled) 1f else 0.4f,
        label = "disabledAlpha"
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(scale)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        // 外圈
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .border(width = 3.dp, color = outerBorderColor.copy(alpha = disabledAlpha), shape = CircleShape)
        )

        // 内圈：根据模式显示不同形状
        when {
            isRecording -> {
                // 录制中：红色圆角方形（停止按钮图标）
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red.copy(alpha = disabledAlpha))
                )
            }
            isVideoMode -> {
                // 录像模式未录制：红色实心圆
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = 0.9f * disabledAlpha))
                )
            }
            else -> {
                // 拍照模式：荧光黄实心圆
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(BackroomsYellow.copy(alpha = innerAlpha * disabledAlpha))
                )
            }
        }
    }
}
