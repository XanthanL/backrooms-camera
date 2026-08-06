package com.photoria.backrooms.camera

import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * 多帧前处理器。
 *
 * 管理相机 YUV_420_888 帧队列，为前处理（多帧降噪/HDR 融合）提供数据源。
 *
 * 工作模式：
 *   - 待机：analyzer 回调做轻量亮度采样后关闭 ImageProxy（Phase 3：场景识别）
 *   - 捕获：调用 [requestCapture] 后，连续收集 N 帧紧凑 YUV 数据，满则回调
 *
 * 帧数据拷贝说明：
 *   ImageProxy 的 plane 可能含 rowStride padding，且 U/V pixelStride 可能是 1（I420）
 *   或 2（NV12/NV21）。拷贝时按行按 pixelStride 提取，输出为无 padding 的紧凑
 *   I420 布局（Y = w×h, U = w/2×h/2, V = w/2×h/2），便于后续直接上传为 3 个
 *   GL_RED 纹理。
 *
 * 线程模型：
 *   - [onImage] 在 [CameraManager.cameraExecutor] 单线程调用
 *   - [requestCapture] / [releaseFrames] 可从主线程调用
 *   - 内部用 synchronized 保护队列与状态
 */
class PreProcessor {

    companion object {
        private const val TAG = "PreProcessor"
        /** 单帧 YUV 紧凑布局：Y(w·h) + U(w/2·h/2) + V(w/2·h/2) = w·h·3/2 */
        private const val YUV_BYTES_PER_PIXEL = 1.5f
        /** 默认多帧捕获张数 */
        const val DEFAULT_FRAME_COUNT = 4
        /** 亮度采样间隔帧数（约 250ms@30fps，平衡灵敏度与 CPU 开销） */
        private const val BRIGHTNESS_SAMPLE_INTERVAL = 8
        /** 亮度采样降采样步长（每 16×16 取 1 个 Y 像素，640×480 → 1200 点） */
        private const val BRIGHTNESS_SAMPLE_STRIDE = 16
        /** 亮度 EMA 平滑系数（越小越平滑，0.15 ≈ ~6 帧时间常数） */
        private const val BRIGHTNESS_EMA_ALPHA = 0.15f
    }

    /**
     * 当前场景亮度（0..1，已 EMA 平滑）。
     *
     * Phase 3：基于 Y plane 降采样平均亮度，供 UI 判断暗光场景并建议夜景模式。
     * 在 cameraExecutor 线程更新，UI 线程 collect。
     */
    private val _brightness = MutableStateFlow(1f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    /**
     * 紧凑 I420 YUV 帧。
     *
     * @param width  帧宽（像素）
     * @param height 帧高（像素）
     * @param yData  Y plane，长度 = width·height
     * @param uData  U plane，长度 = (width/2)·(height/2)
     * @param vData  V plane，长度 = (width/2)·(height/2)
     * @param timestampNs 帧时间戳（纳秒，来自 ImageProxy.imageInfo.timestamp）
     */
    data class YuvFrame(
        val width: Int,
        val height: Int,
        val yData: ByteArray,
        val uData: ByteArray,
        val vData: ByteArray,
        val timestampNs: Long
    )

    /** 捕获状态 */
    private enum class State { IDLE, CAPTURING }

    @Volatile
    private var state = State.IDLE
    /** 目标捕获帧数 */
    private var targetCount = 0
    /** 已捕获的帧队列 */
    private val frameQueue = ArrayList<YuvFrame>()
    /** 捕获完成回调（主线程不安全，在 cameraExecutor 调用） */
    private var captureCallback: ((List<YuvFrame>) -> Unit)? = null
    /** 亮度采样帧计数（每 N 帧采样一次） */
    private var brightnessFrameCounter = 0

    /**
     * ImageAnalysis analyzer 入口。由 CameraManager 绑定。
     *
     * 待机状态：每 N 帧做一次轻量亮度采样（Phase 3 场景识别），其余直接关闭。
     * 捕获状态：拷贝 YUV 后关闭。
     * 必须确保 image.close() 被调用，否则 CameraX 会停止送帧。
     */
    fun onImage(image: ImageProxy) {
        if (state != State.CAPTURING) {
            // Phase 3：待机时降采样统计亮度，供智能场景识别
            if (++brightnessFrameCounter >= BRIGHTNESS_SAMPLE_INTERVAL) {
                brightnessFrameCounter = 0
                try {
                    sampleBrightness(image)
                } catch (e: Exception) {
                    Log.w(TAG, "亮度采样失败", e)
                }
            }
            image.close()
            return
        }
        try {
            val frame = extractYuvFrame(image)
            synchronized(frameQueue) {
                frameQueue.add(frame)
                if (frameQueue.size >= targetCount) {
                    state = State.IDLE
                    val frames = ArrayList(frameQueue)
                    frameQueue.clear()
                    captureCallback?.invoke(frames)
                    captureCallback = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV 帧提取失败", e)
        } finally {
            image.close()
        }
    }

    /**
     * 轻量亮度采样：对 Y plane 降采样求平均，EMA 平滑后更新 [brightness]。
     *
     * 只读 Y plane（亮度通道），按 [BRIGHTNESS_SAMPLE_STRIDE] 步长跳采样，
     * 不拷贝完整帧，CPU 开销极低（640×480 → ~1200 个 byte 读取）。
     */
    private fun sampleBrightness(image: ImageProxy) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val stride = BRIGHTNESS_SAMPLE_STRIDE

        var sum = 0L
        var count = 0
        var rowPos = buffer.position()
        for (y in 0 until h step stride) {
            buffer.position(rowPos)
            for (x in 0 until w step stride) {
                // Y 值 0..255，直接累加
                sum += buffer.get(rowPos + x).toInt() and 0xFF
                count++
            }
            rowPos += rowStride * stride
        }
        if (count == 0) return
        val avg = (sum.toFloat() / count) / 255f
        // EMA 平滑，避免帧间抖动导致建议频繁触发
        val smoothed = _brightness.value * (1 - BRIGHTNESS_EMA_ALPHA) + avg * BRIGHTNESS_EMA_ALPHA
        _brightness.value = smoothed
    }

    /**
     * 请求多帧捕获。
     *
     * @param count 目标帧数（3-5）
     * @param callback 捕获完成回调（在 cameraExecutor 线程）
     * @return true 如果成功启动捕获；false 如果正在捕获中
     */
    fun requestCapture(
        count: Int = DEFAULT_FRAME_COUNT,
        callback: (List<YuvFrame>) -> Unit
    ): Boolean {
        synchronized(frameQueue) {
            if (state == State.CAPTURING) {
                Log.w(TAG, "已在捕获中，忽略重复请求")
                return false
            }
            frameQueue.clear()
            targetCount = count.coerceIn(2, 8)
            captureCallback = callback
            state = State.CAPTURING
            Log.d(TAG, "启动多帧捕获：目标 $targetCount 帧")
            return true
        }
    }

    /** 当前是否正在捕获 */
    fun isCapturing(): Boolean = state == State.CAPTURING

    /** 释放所有缓存的帧数据（取消未完成的捕获） */
    fun releaseFrames() {
        synchronized(frameQueue) {
            state = State.IDLE
            frameQueue.clear()
            captureCallback = null
        }
    }

    /**
     * 从 ImageProxy 提取紧凑 I420 帧。
     *
     * 处理 rowStride padding 与 pixelStride（兼容 I420/NV12/NV21）。
     */
    private fun extractYuvFrame(image: ImageProxy): YuvFrame {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvW = w / 2
        val uvH = h / 2
        val uvSize = uvW * uvH

        val yData = ByteArray(ySize)
        val uData = ByteArray(uvSize)
        val vData = ByteArray(uvSize)

        val planes = image.planes
        // Y plane
        copyPlane(planes[0], yData, w, h, 1)
        // U plane
        copyPlane(planes[1], uData, uvW, uvH, planes[1].pixelStride)
        // V plane
        copyPlane(planes[2], vData, uvW, uvH, planes[2].pixelStride)

        val ts = image.imageInfo.timestamp * 1_000_000_000L
        return YuvFrame(w, h, yData, uData, vData, ts)
    }

    /**
     * 按 pixelStride 逐行拷贝 plane 到紧凑 ByteArray（去除 rowStride padding）。
     *
     * @param plane      源 plane
     * @param dest       目标数组（长度 = outW·outH）
     * @param outW       输出宽度
     * @param outH       输出高度
     * @param pixelStride 像素步长（1=planar I420，2=semi-planar NV12/NV21）
     */
    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        dest: ByteArray,
        outW: Int,
        outH: Int,
        pixelStride: Int
    ) {
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        if (pixelStride == 1 && rowStride == outW) {
            // 最优路径：无 padding，直接整块读取
            buffer.get(dest)
            return
        }
        // 逐行拷贝，处理 padding 与 pixelStride
        var pos = buffer.position()
        var destPos = 0
        for (row in 0 until outH) {
            buffer.position(pos)
            if (pixelStride == 1) {
                buffer.get(dest, destPos, outW)
                destPos += outW
            } else {
                // semi-planar：每隔 pixelStride 取一个值
                for (col in 0 until outW) {
                    dest[destPos++] = buffer.get(pos + col * pixelStride)
                }
            }
            pos += rowStride
        }
    }
}
