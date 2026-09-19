package com.photoria.backrooms.capture

import android.graphics.Bitmap
import android.util.Log
import com.photoria.backrooms.gl.CameraGLSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** 单帧捕获结果 */
sealed class BurstFrame {
    /** 成功：全尺寸位图，所有权交给调用方（降采样后必须 recycle） */
    class Ready(val bitmap: Bitmap) : BurstFrame()

    /** 失败：GL 侧报错或这一帧彻底没回来 */
    class Failed(val reason: String) : BurstFrame()
}

/** 整批结果；[frames] 已降采样到格子尺寸，可直接拼图或编码 */
class BurstResult(
    val frames: List<Bitmap>,
    val failedReason: String?
) {
    val isComplete: Boolean get() = failedReason == null
}

/**
 * 连拍：串行抓 N 帧带滤镜的预览帧。
 *
 * 为什么必须串行：GLRenderer 只存一个 captureCallback，两次请求不排队就会
 * 让后一次覆盖前一次，表现为丢帧 + 回调错配。
 *
 * 为什么每帧立刻降采样：单帧 ≈1440×1920×4 ≈ 11MB，9 帧全尺寸驻留就是 ~106MB，
 * 而 manifest 没开 largeHeap —— 不降采样就是必闪退。
 * 降采样放在 Default 线程：在 GL 线程上做缩放会直接卡住预览。
 */
object BurstCapture {

    private const val TAG = "BurstCapture"

    /** 单帧等待上限：GL 线程被占用时宁可少一帧，也不要整批卡死 */
    const val FRAME_TIMEOUT_MS = 1_200L

    /** 帧间隔：太短会拍到同一瞬间（失去连拍意义），太长用户等不住 */
    const val DEFAULT_INTERVAL_MS = 220L

    /** 格子长边：9 格 ≈ 8MB，且足够填满相册里的九宫格 */
    const val CELL_LONG_SIDE = 540

    /** 每帧的时间预算（超时 + 降采样 + 调度余量） */
    const val FRAME_BUDGET_MS = 1_500L

    /** 整批的看门狗期限：必须比这个长，否则快门会在批次中途被超时看门狗放开 */
    fun deadlineFor(frameCount: Int, intervalMs: Long): Long =
        maxOf(1, frameCount).toLong() * (intervalMs + FRAME_BUDGET_MS)

    /**
     * 抓一帧。
     *
     * 超时与 GL 侧失败都归一成 [BurstFrame.Failed]：调用方只需要"这批到此为止"，
     * 不需要区分是谁没回来的。
     */
    suspend fun captureOne(
        glView: CameraGLSurfaceView,
        timeoutMs: Long = FRAME_TIMEOUT_MS
    ): BurstFrame = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<BurstFrame> { cont ->
            glView.capturePhoto(
                callback = { bitmap ->
                    if (cont.isActive) cont.resume(BurstFrame.Ready(bitmap))
                    // 已超时/已取消：这张没人要了，立刻回收，别占着堆
                    else bitmap.recycle()
                },
                onError = { reason ->
                    if (cont.isActive) cont.resume(BurstFrame.Failed(reason))
                }
            )
        }
    } ?: BurstFrame.Failed("取景器无响应")

    /**
     * 跑完一批。
     *
     * 中途失败就停止收集（返回已拿到的帧 + 失败原因）：缺帧比强行凑数诚实，
     * 拼图会按实际帧数收缩画布。
     */
    suspend fun runBurst(
        glView: CameraGLSurfaceView,
        frameCount: Int,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        cellLongSide: Int = CELL_LONG_SIDE
    ): BurstResult = withContext(Dispatchers.Default) {
        val frames = ArrayList<Bitmap>(frameCount)
        var failed: String? = null
        for (i in 0 until frameCount) {
            when (val frame = captureOne(glView)) {
                is BurstFrame.Ready -> {
                    val full = frame.bitmap
                    val cell = scaleToLongSide(full, cellLongSide)
                    if (cell !== full) full.recycle()
                    Log.d(
                        TAG,
                        "Burst: frame=${frames.size + 1}/$frameCount " +
                            "scaled=${cell.width}x${cell.height} kb=${cell.allocationByteCount / 1024}"
                    )
                    frames.add(cell)
                }
                is BurstFrame.Failed -> {
                    failed = frame.reason
                    break
                }
            }
            if (i != frameCount - 1) delay(intervalMs)
        }
        BurstResult(frames, failed)
    }

    /**
     * 等比缩放到长边 = [longSide]。
     *
     * 尺寸已经够小时原样返回（[Bitmap.createScaledBitmap] 在这种情况下会直接返回
     * 同一个对象，若照例 recycle 原件就会把结果一起回收掉）。
     */
    fun scaleToLongSide(src: Bitmap, longSide: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= longSide || long <= 0) return src
        val scale = longSide.toFloat() / long
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
