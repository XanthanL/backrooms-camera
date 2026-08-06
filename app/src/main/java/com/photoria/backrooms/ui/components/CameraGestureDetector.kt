package com.photoria.backrooms.ui.components

import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import com.photoria.backrooms.camera.CameraManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 相机预览手势检测：点击对焦/测光 + 捏合缩放 + 长按看原图。
 *
 * - 单击（短暂按下且无明显移动）：在点击位置发起对焦+测光
 * - 双指捏合：调整缩放
 * - 长按（单指按住不动 ≥ 500ms）：临时切换到原图预览，松手恢复
 *
 * @param cameraManager 相机管理器（提供 zoom/focus 接口）
 * @param previewSize 预览视图尺寸，用于创建 MeteringPointFactory
 * @param onFocusStarted 当对焦成功发起时回调点击位置（UI 绘制对焦框）
 * @param onLongPressStart 长按触发时回调（UI 临时显示原图）
 * @param onLongPressEnd 长按松手时回调（UI 恢复滤镜）
 */
fun Modifier.cameraGestures(
    cameraManager: CameraManager,
    previewSize: () -> IntSize,
    onFocusStarted: (Offset) -> Unit,
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {}
): Modifier = this then pointerInput(cameraManager) {
    val zoomSensitivity = 0.005f
    val moveThreshold = 24f
    val longPressTimeoutMs = 500L

    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        var longPressed = false
        var lastPinchDistance = 0f

        while (true) {
            // 用超时检测长按：若 500ms 内无新事件且仍单指按下 → 触发长按
            val event: PointerEvent? = withTimeoutOrNull(longPressTimeoutMs) {
                awaitPointerEvent()
            }

            if (event == null) {
                // 超时无新事件：若仍单指且未移动/未长按 → 触发长按
                if (!moved && !longPressed) {
                    longPressed = true
                    onLongPressStart()
                }
                continue
            }

            val active = event.changes.filter { it.pressed }

            if (active.size >= 2) {
                // 双指：捏合缩放
                val p0 = active[0].position
                val p1 = active[1].position
                val dist = (p1 - p0).getDistance()
                if (lastPinchDistance > 0f) {
                    val delta = (dist - lastPinchDistance) * zoomSensitivity
                    if (abs(delta) > 0.0005f) {
                        cameraManager.zoomBy(delta)
                    }
                }
                lastPinchDistance = dist
                active.forEach { it.consume() }
                moved = true
            } else if (active.size == 1) {
                val change = active.first()
                if (change.positionChange().getDistance() > moveThreshold) {
                    moved = true
                }
            }

            // 所有指针抬起 → 手势结束
            if (event.changes.all { !it.pressed }) {
                if (longPressed) onLongPressEnd()
                break
            }
        }

        // 若为单指点击（未移动且未长按）→ 对焦
        if (!moved && !longPressed) {
            val size = previewSize()
            if (size.width > 0 && size.height > 0) {
                val factory = SurfaceOrientedMeteringPointFactory(
                    size.width.toFloat(), size.height.toFloat()
                )
                val point = factory.createPoint(firstDown.position.x, firstDown.position.y)
                if (cameraManager.focusAndMeter(point)) {
                    onFocusStarted(firstDown.position)
                }
            }
        }
    }
}
