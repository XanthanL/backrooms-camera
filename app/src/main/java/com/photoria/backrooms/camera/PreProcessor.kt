package com.photoria.backrooms.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt
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
 *   - 捕获：调用 [requestCapture] 后，连续收集 N 帧紧凑 YUV 数据，满则回调；
 *     帧停止到达时由超时兜底，保证每次请求恰好终结一次
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
        /** 单次捕获的超时上限。
         *
         * 相机停止供帧（切后台、解绑、分辨率切换、Surface 销毁）时，
         * 没有超时就永远不会回调，上层快门的 captureProcessing 标记永不复位
         * —— 表现是"拍一张之后再也点不动"。
         */
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        /** 单次捕获请求的帧数上限（与 merge shader 的 8 帧上限一致） */
        private const val MAX_CAPTURE_FRAMES = 8

        /**
         * 从 ImageProxy 提取紧凑 I420 帧。
         *
         * 处理 rowStride padding 与 pixelStride（兼容 I420/NV12/NV21）。
         * 放在 companion：ImageAnalysis（多帧管线）与 ImageCapture（全分辨率
         * 单帧管线）共用同一份提取逻辑，行为不一致会直接变成色彩偏移。
         */
        fun compactI420(image: ImageProxy): YuvFrame {
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

    /**
     * 当前场景亮度（0..1，已 EMA 平滑）。
     *
     * Phase 3：基于 Y plane 降采样平均亮度，供 UI 判断暗光场景并建议夜景模式。
     * 在 cameraExecutor 线程更新，UI 线程 collect。
     */
    private val _brightness = MutableStateFlow(1f)
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    /**
     * 最近一帧的传感器旋转角（ImageProxy.imageInfo.rotationDegrees）。
     *
     * HDR/夜景走 YUV 直传管线，像素是传感器方向；出片时靠这个值推导
     * EXIF 方向（见 util/ExifOrientations）。由 onImage 每帧刷新。
     */
    @Volatile
    var lastRotationDegrees = 0
        private set

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
    /** 捕获失败回调（与 captureCallback 互斥，一次捕获只派发其中一个） */
    private var captureFailureCallback: ((String) -> Unit)? = null
    /**
     * 超时兜底 Handler。
     *
     * 帧不再到达时 [onImage] 根本不会被调用，所以超时只能在捕获线程之外计时。
     * Handler/removeCallbacks 本身线程安全，可从任意线程取消。
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureTimeoutTask = Runnable { onCaptureTimeout() }
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
        // 每帧刷新旋转角：EXIF 方向推导依赖它（HDR/夜景出片时读取）
        lastRotationDegrees = image.imageInfo.rotationDegrees
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
            val frame = compactI420(image)
            val terminal = synchronized(frameQueue) {
                frameQueue.add(frame)
                if (frameQueue.size >= targetCount) finishLocked(null) else null
            }
            dispatchTerminal(terminal)
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
        // P1b：量化到 0.05 档再写入 —— EMA 每 8 帧都会产生"新"浮点数，
        // StateFlow 去重完全失效，稳定场景下整屏每秒仍被拖约 4 次空转重组；
        // 量化后值不变即不发射，夜景建议判定精度（阈值比较）不受影响
        _brightness.value = (smoothed * 20f).roundToInt() / 20f
    }

    /**
     * 请求多帧捕获。
     *
     * **终结保证**：一旦返回 true，[callback] 与 [onFailure] 中有且仅有一个会被
     * 调用一次（帧收满 / 帧停止到达但超时 / 被 [releaseFrames] 取消时不派发）。
     * 上层据此复位"正在处理"状态，不会因相机停止供帧而永久卡住。
     *
     * @param count 目标帧数（1-8；EV 包围曝光每档只要 1 帧）
     * @param callback 帧就绪回调（cameraExecutor 线程；超时时在主线程序列回调）
     * @param onFailure 失败回调（超时且帧数不足以出片时）
     * @return true 如果成功启动捕获；false 如果正在捕获中
     */
    fun requestCapture(
        count: Int = DEFAULT_FRAME_COUNT,
        callback: (List<YuvFrame>) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ): Boolean {
        val started = synchronized(frameQueue) {
            if (state == State.CAPTURING) {
                Log.w(TAG, "已在捕获中，忽略重复请求")
                false
            } else {
                frameQueue.clear()
                targetCount = count.coerceIn(1, MAX_CAPTURE_FRAMES)
                captureCallback = callback
                captureFailureCallback = onFailure
                state = State.CAPTURING
                true
            }
        }
        if (!started) return false

        mainHandler.removeCallbacks(captureTimeoutTask)
        mainHandler.postDelayed(captureTimeoutTask, CAPTURE_TIMEOUT_MS)
        Log.d(TAG, "启动多帧捕获：目标 $targetCount 帧")
        return true
    }

    /** 当前是否正在捕获 */
    fun isCapturing(): Boolean = state == State.CAPTURING

    /**
     * 释放所有缓存的帧数据并取消未完成的捕获。
     *
     * 取消后不派发任何回调：调用方是 [CameraManager.shutdown]，此时视图正在销毁，
     * 回调里排队 GL 事件只会白做功。
     */
    fun releaseFrames() {
        mainHandler.removeCallbacks(captureTimeoutTask)
        synchronized(frameQueue) {
            state = State.IDLE
            frameQueue.clear()
            captureCallback = null
            captureFailureCallback = null
        }
    }

    /**
     * 一次捕获的终结动作（在锁外派发，避免回调内再次申请捕获时自锁）。
     *
     * @param error null 表示成功，可出片
     */
    private class CaptureTerminal(
        val frames: List<YuvFrame>,
        val error: String?,
        val callback: ((List<YuvFrame>) -> Unit)?,
        val onFailure: ((String) -> Unit)?
    )

    /**
     * 结束当前捕获并取出终结动作。必须在持有 [frameQueue] 锁时调用。
     *
     * @param errorHint 触发失败的原因描述（如超时）
     * @return null 表示当前已无进行中的捕获（幂等保护：帧收满与超时同时到达时
     *         只有先到的那个能派发）
     */
    private fun finishLocked(errorHint: String?): CaptureTerminal? {
        if (state != State.CAPTURING) return null
        state = State.IDLE

        val frames = ArrayList(frameQueue)
        frameQueue.clear()
        // 出片至少需要 2 帧（对齐/融合）；但 EV 包围曝光的单档链接只要 1 帧
        val minUseful = if (targetCount <= 1) 1 else 2
        val error = if (frames.size < minUseful) (errorHint ?: "未捕获到足够帧") else null
        return CaptureTerminal(frames, error, captureCallback, captureFailureCallback).also {
            captureCallback = null
            captureFailureCallback = null
        }
    }

    private fun dispatchTerminal(terminal: CaptureTerminal?) {
        terminal ?: return
        mainHandler.removeCallbacks(captureTimeoutTask)
        val error = terminal.error
        if (error == null) {
            terminal.callback?.invoke(terminal.frames)
        } else {
            Log.w(TAG, "捕获失败：$error（收集到 ${terminal.frames.size} 帧）")
            terminal.onFailure?.invoke(error)
        }
    }

    /** 超时兜底：帧停止到达时也要把这次捕获终结掉 */
    private fun onCaptureTimeout() {
        val terminal = synchronized(frameQueue) {
            finishLocked("捕获超时（${CAPTURE_TIMEOUT_MS}ms 内相机未持续供帧）")
        }
        dispatchTerminal(terminal)
    }
}
