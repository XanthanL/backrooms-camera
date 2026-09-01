package com.photoria.backrooms.camera

import android.content.Context
import android.view.Surface
import android.util.Log
import android.util.Range
import android.os.Handler
import android.os.Looper
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.photoria.backrooms.gl.CameraGLSurfaceView
import com.photoria.backrooms.util.ExifOrientations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 白平衡预设。自动 = 交给设备 AWB；暖色/冷色 = 手动增益（按强度插值）。
 */
enum class WbPreset(val display: String) {
    AUTO("自动"),
    WARM("暖色"),
    COOL("冷色")
}

/**
 * CameraX 相机管理器。
 * 负责 CameraProvider 初始化、前后摄切换、Preview + ImageAnalysis 绑定。
 *
 * Preview 使用最高可用分辨率的 ResolutionSelector（4:3），使预览缓冲达到传感器
 * 最大尺寸；GLRenderer.capturePhoto 读取的就是这个全分辨率帧，无需额外管线。
 *
 * 注意：视频录制已改为 GL 滤镜帧 → MediaCodec → MP4 方案，
 * 不再需要 CameraX VideoCapture。
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        /** 对焦/测光取消时长（3 秒后自动取消，恢复连续模式） */
        private const val FOCUS_METERING_DURATION_MS = 3000L
    }

    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    /** 多帧前处理器（YUV 帧队列管理，供前处理融合使用） */
    val preProcessor = PreProcessor()
    /** ImageAnalysis UseCase（YUV_420_888 输出，供 preProcessor 消费） */
    private var imageAnalysis: ImageAnalysis? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    /** 当前已绑定的 Camera 实例（用于缩放/对焦/测光控制） */
    private var boundCamera: Camera? = null
    /** 当前线性缩放值（0..1），切换前后摄时保留 */
    private var currentZoomRatio: Float = 1f
    /** 当前缩放倍率（zoomRatio），供 UI 显示（如 1.0x/2.0x） */
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    /**
     * 当前场景亮度（0..1，已 EMA 平滑）。
     *
     * Phase 3：由 PreProcessor 在待机帧降采样 Y plane 计算，供 UI 智能建议夜景模式。
     * 捕获期间暂停更新（PreProcessor 进入 CAPTURING 状态）。
     */
    val brightness: StateFlow<Float> get() = preProcessor.brightness

    // ── 专业采集控制状态（Camera2 Interop）──
    /** 曝光补偿档位（自动曝光模式下生效） */
    private var lastEvIndex = 0
    /** 白平衡预设（自动/暖色/冷色） */
    private var wbPreset = WbPreset.AUTO
    /** 白平衡强度 0..1（手动模式生效） */
    private var wbIntensity = 0.5f
    /** 是否手动曝光（AE 关闭，固定 ISO + 快门） */
    private var manualExposureActive = false
    /** 手动 ISO */
    private var manualIso = 400
    /** 手动快门（纳秒） */
    private var manualExposureTimeNs = 16_700_000L

    fun initialize(onReady: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        glSurfaceView: CameraGLSurfaceView,
        rotation: Int = Surface.ROTATION_0
    ) {
        val provider = cameraProvider ?: return

        provider.unbindAll()

        // Preview：加高分辨率选择器，使预览缓冲达到传感器最大可用分辨率
        // （GLRenderer.capturePhoto 读取的就是这个缓冲，从而获得全分辨率出片）
        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(previewResolutionSelector)
            .build()
            .also {
                it.setSurfaceProvider(glSurfaceView)
            }

        // ImageAnalysis：YUV_420_888 输出，供 PreProcessor 多帧捕获
        // 4:3 画幅与 Preview 一致；KEEP_ONLY_LATEST 避免积压导致内存压力
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, preProcessor::onImage) }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        try {
            boundCamera = provider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageAnalysis
            )
            // 应用上次缩放（切换前后摄保持缩放）
            applyZoom(currentZoomRatio)
            // 恢复曝光补偿与 Camera2 手动选项（切换前后摄后需重新下发）
            restoreAfterRebind()
            // 同步缩放倍率显示（前置摄像头一般只支持 1.0x）
            _zoomRatio.value = cameraInfoOrNull()?.zoomState?.value?.zoomRatio ?: 1f
        } catch (e: Exception) {
            Log.e(TAG, "绑定预览失败", e)
        }
    }

    /** 返回当前绑定的 CameraInfo（无则 null） */
    private fun cameraInfoOrNull(): androidx.camera.core.CameraInfo? = boundCamera?.cameraInfo

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        glSurfaceView: CameraGLSurfaceView,
        rotation: Int = Surface.ROTATION_0
    ) {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        // 切换摄像头时强制关闭闪光灯（前置一般无闪光灯）
        enableTorch(false)
        bindPreview(lifecycleOwner, glSurfaceView, rotation)
    }

    fun isFrontCamera(): Boolean = currentLensFacing == CameraSelector.LENS_FACING_FRONT

    /**
     * 由最近一帧的传感器旋转角推导 EXIF 方向（HDR/夜景 YUV 管线专用）。
     *
     * YUV 像素是传感器方向，出片时靠此值写 EXIF 标签；普通 GL 路径像素已正立，
     * 直接传 ORIENTATION_NORMAL 即可。
     */
    fun yuvCaptureOrientation(): Int =
        ExifOrientations.forCamera(preProcessor.lastRotationDegrees, isFrontCamera())

    // ── 缩放 ───────────────────────────────────────────────────────

    /**
     * 设置线性缩放（0..1）。
     * 0 = 最广角（1x），1 = 最大变焦。内部映射到 zoomRatio。
     */
    fun setLinearZoom(linear: Float) {
        val camera = boundCamera ?: return
        val clamped = linear.coerceIn(0f, 1f)
        runCatching {
            camera.cameraControl.setLinearZoom(clamped)
            // 同步当前 zoomRatio（用于切换前后摄保留）
            currentZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            _zoomRatio.value = currentZoomRatio
        }.onFailure { Log.w(TAG, "setLinearZoom 失败", it) }
    }

    /**
     * 相对缩放（捏合）：按增量调整线性缩放。
     * @param delta 缩放增量（正=放大，负=缩小）
     */
    fun zoomBy(delta: Float) {
        val camera = boundCamera ?: return
        val current = camera.cameraInfo.zoomState.value?.linearZoom ?: 0f
        setLinearZoom(current + delta)
    }

    /** 获取当前线性缩放（0..1） */
    fun getLinearZoom(): Float {
        return boundCamera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f
    }

    /** 应用指定 zoomRatio（切换前后摄后恢复） */
    private fun applyZoom(zoomRatio: Float) {
        val camera = boundCamera ?: return
        runCatching {
            camera.cameraControl.setZoomRatio(zoomRatio.coerceIn(
                camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
                camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            ))
            _zoomRatio.value = camera.cameraInfo.zoomState.value?.zoomRatio ?: zoomRatio
        }
    }

    // ── 闪光灯 ──────────────────────────────────────────────────────

    /**
     * 开关闪光灯（手电筒模式）。
     */
    fun enableTorch(on: Boolean) {
        val camera = boundCamera ?: return
        runCatching { camera.cameraControl.enableTorch(on) }
            .onFailure { Log.w(TAG, "enableTorch 失败", it) }
    }

    /**
     * 当前是否可用闪光灯（后摄且具备闪光灯单元）。
     */
    fun isTorchAvailable(): Boolean {
        return currentLensFacing == CameraSelector.LENS_FACING_BACK &&
            (cameraInfoOrNull()?.hasFlashUnit() ?: false)
    }

    // ── 专业采集控制（Camera2 Interop）──────────────────────────────
    //
    // CameraX 1.4 的 CameraControl 只提供 EV 补偿（setExposureCompensationIndex），
    // ISO / 快门 / 白平衡需通过 Camera2Interop 的 Camera2CameraControl 下发原始
    // CaptureRequest 选项。所有选项统一组装成一份 CaptureRequestOptions 并整体
    // 替换，切换前后摄重新绑定后由 restoreAfterRebind() 恢复。

    /** 曝光补偿支持范围（取决于当前相机的 ExposureState） */
    fun getExposureCompensationRange(): Range<Int> =
        cameraInfoOrNull()?.exposureState?.exposureCompensationRange ?: Range(-3, 3)

    /** 当前曝光补偿档位 */
    fun getCurrentEvIndex(): Int = lastEvIndex

    /**
     * 设置曝光补偿档位（自动曝光模式下生效，范围自动收敛）。
     */
    fun setExposureCompensation(index: Int) {
        val camera = boundCamera ?: return
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        lastEvIndex = index.coerceIn(range.lower, range.upper)
        if (manualExposureActive) return // 手动曝光下由 HAL 忽略，交给 UI 置灰
        runCatching {
            camera.cameraControl.setExposureCompensationIndex(lastEvIndex)
        }.onFailure { Log.w(TAG, "setExposureCompensation 失败", it) }
    }

    /** ISO 支持范围（默认 100..3200，取决于设备传感器） */
    fun getIsoRange(): Range<Int> {
        val info = cameraInfoOrNull() ?: return Range(100, 3200)
        return runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            ) ?: Range(100, 3200)
        }.getOrElse { Range(100, 3200) }
    }

    /** 快门时间支持范围（纳秒，默认 1ms..1s） */
    fun getExposureTimeRangeNs(): Range<Long> {
        val info = cameraInfoOrNull() ?: return Range(1_000_000L, 1_000_000_000L)
        return runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            ) ?: Range(1_000_000L, 1_000_000_000L)
        }.getOrElse { Range(1_000_000L, 1_000_000_000L) }
    }

    /** 是否处于手动曝光（AE 关闭） */
    fun isManualExposure(): Boolean = manualExposureActive

    /** 开启手动曝光：关闭 AE，固定 ISO 与快门 */
    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        manualExposureActive = true
        manualIso = iso
        manualExposureTimeNs = exposureTimeNs
        applyCamera2Options()
    }

    /** 恢复自动曝光（AE 重新接管） */
    fun resetManualExposure() {
        manualExposureActive = false
        applyCamera2Options()
    }

    fun getWbPreset(): WbPreset = wbPreset

    fun getWbIntensity(): Float = wbIntensity

    /** 设置白平衡预设（自动 = 交给设备 AWB） */
    fun setWbPreset(preset: WbPreset) {
        wbPreset = preset
        applyCamera2Options()
    }

    /** 设置白平衡强度 0..1（仅手动预设生效） */
    fun setWbIntensity(intensity: Float) {
        wbIntensity = intensity.coerceIn(0f, 1f)
        applyCamera2Options()
    }

    /** 当前是否手动白平衡（非自动） */
    fun isWbManual(): Boolean = wbPreset != WbPreset.AUTO

    /** 重新绑定（切换前后摄）后恢复曝光补偿与 Camera2 手动选项 */
    private fun restoreAfterRebind() {
        if (!manualExposureActive) {
            runCatching {
                boundCamera?.cameraControl?.setExposureCompensationIndex(lastEvIndex)
            }.onFailure { Log.w(TAG, "恢复曝光补偿失败", it) }
        }
        applyCamera2Options()
    }

    /** 组装当前全部 Camera2 手动选项并整体下发 */
    private fun applyCamera2Options() {
        val camera = boundCamera ?: return
        val builder = buildCaptureRequestOptionsBuilder()

        runCatching {
            Camera2CameraControl.from(camera.cameraControl)
                .setCaptureRequestOptions(builder.build())
        }.onFailure { Log.w(TAG, "下发 Camera2 选项失败", it) }
    }

    /**
     * 组装当前 Camera2 手动选项的 Builder（曝光 + 白平衡）。
     *
     * 提取为独立方法供 burst 捕获复用：burst 时在基础选项之上叠加
     * CONTROL_AE_EXPOSURE_COMPENSATION，保证 WB/手动曝光等设置不被覆盖。
     */
    private fun buildCaptureRequestOptionsBuilder(): CaptureRequestOptions.Builder {
        val builder = CaptureRequestOptions.Builder()

        // 曝光：手动 = AE OFF + ISO + 快门；否则 AE ON
        if (manualExposureActive) {
            builder
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY, manualIso
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTimeNs
                )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON
            )
        }

        // 白平衡：手动 = AWB_MANUAL + 颜色校正增益（暖/冷，按强度插值）
        if (wbPreset != WbPreset.AUTO) {
            val target = when (wbPreset) {
                WbPreset.WARM -> floatArrayOf(1.45f, 1f, 1f, 0.72f)
                WbPreset.COOL -> floatArrayOf(0.75f, 1f, 1f, 1.38f)
                else -> floatArrayOf(1f, 1f, 1f, 1f)
            }
            val t = wbIntensity
            val gains = floatArrayOf(
                1f + (target[0] - 1f) * t,
                1f,
                1f,
                1f + (target[3] - 1f) * t
            )
            builder
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                .setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains[0], 1f, 1f, gains[3])
                )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        return builder
    }

    // ── 对焦/测光 ──────────────────────────────────────────────────
    /**
     * 在指定测光区域点对焦并测光。
     * @param factoryPoint MeteringPointFactory 创建的点（已归一化到传感器坐标）
     * @return 是否成功发起对焦（用于 UI 显示对焦框）
     */
    fun focusAndMeter(factoryPoint: androidx.camera.core.MeteringPoint): Boolean {
        val camera = boundCamera ?: return false
        val action = FocusMeteringAction.Builder(
            factoryPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(FOCUS_METERING_DURATION_MS, TimeUnit.MILLISECONDS).build()
        return runCatching {
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({ /* 结果由 UI 短暂显示对焦框后清除 */ }, ContextCompat.getMainExecutor(context))
            true
        }.getOrDefault(false)
    }

    // ── 多帧前处理捕获 ──────────────────────────────────────────────
    //
    // 阶段1 基础设施：通过 ImageAnalysis 持续接收 YUV_420_888 帧，
    // 拍照时触发 PreProcessor 收集 3-5 帧，供阶段2 融合算法使用。
    // 当前不改变现有单帧 GL 拍照流程，多帧数据通过回调交付上层。

    /**
     * 请求多帧 YUV 捕获。
     *
     * @param count 目标帧数（默认 4）
     * @param callback 捕获完成回调（在 cameraExecutor 线程，返回紧凑 I420 帧列表）
     * @param onFailure 失败回调（超时或帧数不足），见 [PreProcessor.requestCapture] 的终结保证
     * @return true 如果成功启动；false 如果正在捕获中
     */
    fun requestMultiFrameCapture(
        count: Int = PreProcessor.DEFAULT_FRAME_COUNT,
        callback: (List<PreProcessor.YuvFrame>) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ): Boolean = preProcessor.requestCapture(count, callback, onFailure)

    /** 当前是否正在多帧捕获 */
    fun isMultiFrameCapturing(): Boolean = preProcessor.isCapturing()

    // ── EV 包围曝光捕获（阶段3：HDR）──────────────────────────────
    //
    // 依次设置不同 EV 补偿值，等待 AE 稳定后逐帧捕获，
    // 产出不同曝光的 YUV 帧序列供 HDR 融合使用。

    /** AE 稳定等待时间（毫秒），约 5-6 帧 @30fps */
    private val aeStabilizeHandler = Handler(Looper.getMainLooper())
    private val AE_STABILIZE_DELAY_MS = 200L

    /**
     * 请求 EV 包围曝光多帧捕获。
     *
     * 对每个 EV 值：设置曝光补偿 → 等待 AE 稳定 → 捕获 1 帧。
     * 全部完成后回调（在 cameraExecutor 线程），并重置 EV 为 0。
     * 任一档失败即整体终止：恢复 EV 并回调 [onFailure]，不会静默停在中途。
     *
     * @param evValues EV 补偿值列表（如 listOf(-2, 0, 2)）
     * @param callback 帧就绪回调
     * @param onFailure 失败回调（帧数不足或某档捕获失败）
     * @return true 如果成功启动；false 如果正在捕获中
     */
    fun requestBracketedCapture(
        evValues: List<Int>,
        callback: (List<PreProcessor.YuvFrame>) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ): Boolean {
        if (preProcessor.isCapturing()) {
            Log.w(TAG, "已在捕获中，忽略包围曝光请求")
            return false
        }

        val frames = mutableListOf<PreProcessor.YuvFrame>()
        var index = 0
        var finished = false

        fun finish(reason: String?) {
            if (finished) return
            finished = true
            // 成功或失败都要复位 EV，否则预览永久停在最后一档补偿
            setExposureCompensation(0)
            if (reason == null) {
                Log.d(TAG, "包围曝光完成：${frames.size} 帧")
                callback(frames)
            } else {
                Log.w(TAG, "包围曝光失败：$reason")
                onFailure?.invoke(reason)
            }
        }

        fun captureNext() {
            if (index >= evValues.size) {
                finish(if (frames.size < 2) "仅捕获到 ${frames.size} 帧" else null)
                return
            }
            val ev = evValues[index]
            Log.d(TAG, "包围曝光 [$index/${evValues.size}] EV=$ev")
            setExposureCompensation(ev)
            // 等待 AE 稳定后捕获
            aeStabilizeHandler.postDelayed({
                val started = preProcessor.requestCapture(
                    1,
                    { frameList ->
                        frames += frameList
                        index++
                        captureNext()
                    },
                    { reason -> finish("第 $index 档捕获失败：$reason") }
                )
                if (!started) finish("第 $index 档无法启动捕获（已有捕获在进行）")
            }, AE_STABILIZE_DELAY_MS)
        }
        captureNext()
        return true
    }

    // ── Camera2 Burst 捕获（D1：降低 HDR 延迟；E1：帧数上限 8）─────────
    //
    // 与 requestBracketedCapture 的区别：
    //   1. 通过 Camera2 interop 直接设置 CONTROL_AE_EXPOSURE_COMPENSATION，
    //      绕过 CameraX setExposureCompensationIndex 的异步开销
    //   2. 在基础选项之上叠加 EV，WB/手动曝光等设置不被覆盖
    //   3. 缩短帧间等待（80ms vs 200ms），因为 Camera2 interop 选项
    //      直接作用于下一个 repeating 请求，2-3 帧即可生效
    //   4. 完成后恢复原始 Camera2 选项 + EV=0
    //
    // 延迟对比（3 EV）：
    //   旧（bracketed）：3 × 200ms = 600ms
    //   新（burst）：    3 × 80ms  = 240ms（~2.5× 提速）
    //
    // E1：evValues.size 上限 8（ES 3.0 merge shader 支持），但 HDR 默认仍 3 EV

    /** Burst 帧间等待（毫秒），约 2-3 帧 @30fps，足够 Camera2 interop EV 生效 */
    private val BURST_FRAME_DELAY_MS = 80L

    /**
     * 请求 Camera2 Burst 包围曝光捕获（D1）。
     *
     * 通过 Camera2 interop 逐帧直接设置 CONTROL_AE_EXPOSURE_COMPENSATION，
     * 在基础 CaptureRequestOptions（WB/手动曝光）之上叠加 EV，帧间等待仅 80ms。
     * 完成后恢复原始 Camera2 选项并重置 EV 为 0。任一档失败即整体终止，
     * 同样恢复选项与 EV 并回调 [onFailure]。
     *
     * @param evValues EV 补偿值列表（如 listOf(-2, 0, 2)）
     * @param callback 帧就绪回调（cameraExecutor 线程）
     * @param onFailure 失败回调（帧数不足或某档捕获失败）
     * @return true 如果成功启动；false 如果正在捕获中或手动曝光模式
     */
    fun requestBurstCapture(
        evValues: List<Int>,
        callback: (List<PreProcessor.YuvFrame>) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ): Boolean {
        if (preProcessor.isCapturing()) {
            Log.w(TAG, "已在捕获中，忽略 burst 请求")
            return false
        }
        val camera = boundCamera
        if (camera == null || manualExposureActive) {
            // 手动曝光模式下 AE OFF，EV 补偿无效，回退到普通多帧
            Log.w(TAG, "burst 不可用（无相机或手动曝光），回退多帧捕获")
            return requestMultiFrameCapture(
                evValues.size.coerceIn(2, 8), callback, onFailure
            )
        }

        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        val startTime = System.currentTimeMillis()
        val frames = mutableListOf<PreProcessor.YuvFrame>()
        var index = 0
        var finished = false

        fun finish(reason: String?) {
            if (finished) return
            finished = true
            // 成功或失败都要恢复原始 Camera2 选项 + EV=0，
            // 否则预览会停在最后一档曝光补偿上
            applyCamera2Options()
            setExposureCompensation(0)
            if (reason == null) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Burst 完成：${frames.size} 帧, 耗时 ${elapsed}ms")
                callback(frames)
            } else {
                Log.w(TAG, "Burst 失败：$reason")
                onFailure?.invoke(reason)
            }
        }

        fun captureNext() {
            if (index >= evValues.size) {
                finish(if (frames.size < 2) "仅捕获到 ${frames.size} 帧" else null)
                return
            }
            val ev = evValues[index].coerceIn(range.lower, range.upper)
            Log.d(TAG, "Burst [$index/${evValues.size}] EV=$ev")

            // 在基础选项之上叠加 AE 曝光补偿，直接下发到 Camera2 repeating 请求
            val builder = buildCaptureRequestOptionsBuilder()
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev
            )
            runCatching {
                Camera2CameraControl.from(camera.cameraControl)
                    .setCaptureRequestOptions(builder.build())
            }.onFailure { Log.w(TAG, "Burst 设置 EV 失败", it) }

            // 等待 2-3 帧使新 EV 生效，然后捕获 1 帧
            aeStabilizeHandler.postDelayed({
                val started = preProcessor.requestCapture(
                    1,
                    { frameList ->
                        frames += frameList
                        index++
                        captureNext()
                    },
                    { reason -> finish("第 $index 档捕获失败：$reason") }
                )
                if (!started) finish("第 $index 档无法启动捕获（已有捕获在进行）")
            }, BURST_FRAME_DELAY_MS)
        }
        captureNext()
        return true
    }

    fun shutdown() {
        preProcessor.releaseFrames()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}

