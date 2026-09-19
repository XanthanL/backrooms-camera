package com.photoria.backrooms.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import android.graphics.Bitmap
import com.photoria.backrooms.camera.CameraManager
import com.photoria.backrooms.camera.PreProcessor
import com.photoria.backrooms.catalog.FilterCatalog
import com.photoria.backrooms.encoder.GLVideoRecorder
import com.photoria.backrooms.gl.filter.BaseFilter
import com.photoria.backrooms.gl.filter.Filter

/**
 * 自定义 GLSurfaceView，集成 CameraX 预览与滤镜渲染管线。
 *
 * 职责：
 *   - 创建并配置 GLSurfaceView（OpenGL ES 2.0）
 *   - 持有 GLRenderer 实例
 *   - 实现 Preview.SurfaceProvider 供 CameraX 绑定
 *   - 对外暴露滤镜设置接口
 */
class CameraGLSurfaceView(
    context: Context,
    internal val cameraManager: CameraManager,
    private val lifecycleOwner: LifecycleOwner
) : GLSurfaceView(context), Preview.SurfaceProvider {

    companion object {
        private const val TAG = "CameraGLSurfaceView"
    }

    val renderer: GLRenderer

    /** 获取相机用的 SurfaceTexture（CameraX 绑定用） */
    fun getSurfaceTexture(): SurfaceTexture? = cameraSurfaceTexture

    /** 设置滤镜（通过 queueEvent 在 GL 线程执行） */
    fun setFilter(filter: Filter) {
        queueEvent {
            renderer.setFilter(filter)
        }
    }

    /** 滤镜实例缓存（index → 已 setup 的 Filter），避免每次切换重编译 shader */
    private val filterCache = mutableMapOf<Int, Filter>()

    /** 当前选中的滤镜索引（GL 上下文重建后用于恢复） */
    @Volatile
    private var currentFilterIndex = 0

    /**
     * 按需渲染模式决策。
     * 连续渲染：动画滤镜、视频录制中；按需渲染：静态滤镜（省电）。
     * GLSurfaceView.setRenderMode 可从任意线程调用。
     */
    private fun updateRenderMode() {
        val needsContinuous = activeRecorder?.isRecording == true || isAnimatedFilter(currentFilterIndex)
        if (needsContinuous) {
            if (renderMode != RENDERMODE_CONTINUOUSLY) renderMode = RENDERMODE_CONTINUOUSLY
        } else {
            if (renderMode != RENDERMODE_WHEN_DIRTY) renderMode = RENDERMODE_WHEN_DIRTY
            // 切到按需模式后请求一帧，立即应用新滤镜/布局
            requestRender()
        }
    }

    /** 动画滤镜（shader 依赖 uTime 产生频闪/颗粒/撕裂/抖动等随时间变化的效果）必须连续渲染 */
    private fun isAnimatedFilter(index: Int): Boolean = FilterCatalog.isAnimated(index)

    /**
     * 按索引切换滤镜，使用缓存避免 shader 重编译。
     * 首次使用某滤镜时创建并 setup，之后切换复用缓存实例。
     */
    fun setFilterByIndex(index: Int) {
        currentFilterIndex = index
        updateRenderMode()
        queueEvent {
            try {
                val filter = filterCache.getOrPut(index) {
                    createFilter(index).also { it.setup() }
                }
                renderer.filterChain.activateFilter(filter)
            } catch (e: Exception) {
                // 防御：滤镜创建/setup 异常不应杀死 GL 渲染线程
                Log.e(TAG, "创建滤镜失败 index=$index", e)
            }
        }
    }

    /** 创建指定索引对应的滤镜实例（数据源：FilterCatalog） */
    private fun createFilter(index: Int): Filter {
        return FilterCatalog.create(index, context.assets)
    }

    /**
     * 设置滤镜参数（通过 queueEvent 在 GL 线程执行）。
     *
     * 注意：必须用 getActiveOrPendingFilter() 而非 getCurrentFilter()：
     * 切换滤镜后参数立即推送，但滤镜真正生效要等下一帧 apply() 的
     * swapFilterIfNeeded()；若查 currentFilter 会把参数设到旧滤镜上，
     * 新滤镜的 uniform 保持 GLSL 默认值 0（LUT 等参数驱动滤镜会失效）。
     */
    fun setFilterParam(name: String, value: Float) {
        queueEvent {
            renderer.filterChain.getActiveOrPendingFilter()?.let { filter ->
                if (filter is BaseFilter) {
                    filter.setAdjustableParam(name, value)
                }
            }
        }
        // 按需渲染模式下参数变化需请求一帧
        requestRender()
    }

    /** 设置目标画幅（W/H，-1=FULL 匹配屏幕）。在 GL 线程生效。 */
    fun setTargetAspect(aspect: Float) {
        queueEvent {
            renderer.setTargetAspect(aspect)
        }
        requestRender()
    }

    /** 设置画幅填充模式（0=填充裁剪，1=适配黑边）。在 GL 线程生效。 */
    fun setFitMode(mode: Int) {
        queueEvent {
            renderer.setFitMode(mode)
        }
        requestRender()
    }

    /** 设置滤镜强度（0..1）。在 GL 线程生效。 */
    fun setFilterStrength(value: Float) {
        queueEvent {
            renderer.filterChain.strength = value
        }
        requestRender()
    }

    /**
     * 设置取景辅助配置（斑马纹 / 峰值对焦 / 直方图）。在 GL 线程生效。
     *
     * 这些辅助只影响屏幕叠加层，不会进入照片与录像。
     */
    fun setProOverlay(cfg: ProOverlayConfig) {
        queueEvent {
            renderer.proOverlayConfig = cfg
        }
        // 按需渲染下切换开关需立即重绘一帧，否则要等下一个相机帧才见效
        requestRender()
    }

    /** 获取当前内容尺寸（用于录像分辨率），在 GL 线程读取。 */
    fun getContentSize(): Pair<Int, Int> = renderer.getContentSize()

    /**
     * 拍照：在 GL 线程中捕获当前带滤镜的帧。
     * 回调在 GL 线程执行，如需保存文件请切换到 IO 线程。
     *
     * @param callback 出片回调（GL 线程）
     * @param onError 无法出片回调（GL 线程），调用方据此复位"处理中"状态
     */
    fun capturePhoto(callback: (Bitmap) -> Unit, onError: (String) -> Unit) {
        queueEvent {
            renderer.capturePhoto(callback, onError)
        }
        // 按需渲染模式下拍照请求需触发一帧来执行捕获
        requestRender()
    }

    // ── 多帧前处理捕获（阶段1 基础设施）──────────────────────────

    /**
     * 请求多帧 YUV 捕获。
     *
     * 通过 CameraX ImageAnalysis 连续收集 N 帧紧凑 I420 数据。
     * 回调在 cameraExecutor 线程执行（非 GL 线程、非主线程）。
     *
     * @param count 目标帧数（默认 4）
     * @param onFramesReady 帧就绪回调
     * @return true 如果成功启动；false 如果正在捕获中
     */
    fun requestMultiFrameCapture(
        count: Int = PreProcessor.DEFAULT_FRAME_COUNT,
        onFramesReady: (List<PreProcessor.YuvFrame>) -> Unit,
        onFailure: ((String) -> Unit)? = null
    ): Boolean = cameraManager.requestMultiFrameCapture(count, onFramesReady, onFailure)

    /**
     * 将多帧 YUV 数据上传为 GL 纹理队列。
     *
     * 必须在 GL 线程执行上传，回调在 GL 线程。
     * 每帧上传为 3 个 GL_LUMINANCE 纹理（Y/U/V），调用方负责用
     * [releaseYuvFrameSet] 释放。
     *
     * @param frames YUV 帧列表（来自 [requestMultiFrameCapture] 回调）
     * @param onTexturesReady 纹理就绪回调，每个元素 = [yTexId, uTexId, vTexId]
     */
    fun uploadYuvFramesToGl(
        frames: List<PreProcessor.YuvFrame>,
        onTexturesReady: (List<IntArray>) -> Unit
    ) {
        queueEvent {
            val textures = renderer.uploadYuvFrames(frames)
            onTexturesReady(textures)
        }
    }

    /** 释放多帧 YUV 纹理队列（在 GL 线程执行） */
    fun releaseYuvFrameSet(textureSet: List<IntArray>) {
        queueEvent { renderer.releaseYuvFrameSet(textureSet) }
    }

    // ── 多帧融合拍照（阶段2：简单平均降噪）──────────────────────

    /**
     * 多帧降噪拍照：触发多帧 YUV 捕获 → GL 融合 → Bitmap。
     *
     * 编排跨线程数据流：
     *   1. 主线程调用 → cameraExecutor 收集 N 帧 YUV
     *   2. YUV 就绪 → 切到 GL 线程执行融合渲染
     *   3. GL 线程回读 Bitmap → 回调
     *
     * 回调在 GL 线程执行，如需保存文件请切换到 IO 线程。
     *
     * @param frameCount 帧数（默认 4，建议 3-5）
     * @param callback 拍照完成回调
     */
    fun captureMergedPhoto(
        frameCount: Int = PreProcessor.DEFAULT_FRAME_COUNT,
        callback: (Bitmap) -> Unit
    ) {
        val started = requestMultiFrameCapture(
            count = frameCount,
            onFramesReady = { frames ->
                // YUV 帧在 cameraExecutor 就绪 → 切到 GL 线程融合
                queueEvent {
                    renderer.captureMergedPhoto(frames, callback)
                }
                requestRender()
            }
        )
        if (!started) {
            Log.w(TAG, "多帧捕获启动失败：正在捕获中")
        }
    }

    // ── 对齐多帧拍照（阶段3：块匹配 + 时域降噪/HDR）─────────────

    /**
     * 对齐时域降噪拍照：多帧捕获 → 块匹配对齐 → 等权平均 → Bitmap。
     *
     * 适用于手持夜景等静态场景。通过块匹配补偿手持抖动后多帧平均，
     * 降噪效果 ≈ √N（4 帧 ≈ 2 倍），优于阶段2 的无对齐平均。
     *
     * 回调在 GL 线程执行，如需保存文件请切换到 IO 线程。
     *
     * @param frameCount 帧数（默认 4，建议 3-4）
     * @param callback 拍照完成回调
     * @param onError 无法出片回调（启动失败 / 帧采集失败 / GL 融合失败）
     */
    fun captureAlignedPhoto(
        frameCount: Int = 4,
        callback: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val started = requestMultiFrameCapture(
            frameCount,
            { frames ->
                queueEvent {
                    renderer.captureAlignedPhoto(frames, hdrMode = false, callback, onError)
                }
                requestRender()
            },
            { reason -> onError("捕获失败：$reason") }
        )
        if (!started) {
            Log.w(TAG, "对齐捕获启动失败：正在捕获中")
            onError("相机正忙（上一次捕获未完成）")
        }
    }

    /**
     * HDR 拍照：EV 包围曝光捕获 → 块匹配对齐 → 曝光加权融合 → Bitmap。
     *
     * 依次以不同 EV（如 -2/0/+2）捕获帧，对齐后按像素亮度加权融合，
     * 扩展动态范围：暗部由高曝光帧补充，亮部由低曝光帧保留。
     *
     * D1：使用 Camera2 Burst 捕获（帧间 ~80ms，3 EV 总计 ~240ms），
     * 相比普通包围曝光（~600ms）延迟降低约 2.5×。手动曝光模式下自动
     * 回退到普通多帧捕获（AE OFF 时 EV 补偿无效）。
     *
     * 回调在 GL 线程执行，如需保存文件请切换到 IO 线程。
     *
     * @param evValues EV 补偿值列表（默认 [-2, 0, 2]）
     * @param callback 拍照完成回调
     * @param onError 无法出片回调（启动失败 / 包围曝光失败 / GL 融合失败）
     */
    fun captureHdrPhoto(
        evValues: List<Int> = listOf(-2, 0, 2),
        callback: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val started = cameraManager.requestBurstCapture(
            evValues,
            { frames ->
                queueEvent {
                    renderer.captureAlignedPhoto(frames, hdrMode = true, callback, onError)
                }
                requestRender()
            },
            { reason -> onError("HDR 捕获失败：$reason") }
        )
        if (!started) {
            Log.w(TAG, "HDR 捕获启动失败：正在捕获中")
            onError("相机正忙（上一次捕获未完成）")
        }
    }

    // ── 滤镜缩略图 ──────────────────────────────────────────────

    /** 缩略图完成回调（主线程，按滤镜索引排序） */
    private var thumbnailCallback: ((List<Bitmap>) -> Unit)? = null

    /**
     * 请求生成全量滤镜缩略图（异步）。
     *
     * 实现：在 GL 线程注册滤镜提供器（复用滤镜缓存，未创建则创建并 setup），
     * 由 GLRenderer 跨帧分批生成（每帧 2 张，分摊 shader 编译成本），
     * 完成后在主线程回调 [callback]。
     * 可重复调用（重新生成，反映最新的自定义参数）。
     *
     * @param paramsProvider 滤镜索引 → 生效参数表。缩略图滤镜实例与预览共用缓存，
     *        但只有当前选中的滤镜会被 [setFilterParam] 推过参数，
     *        其余滤镜必须在此显式注入，否则 uniform 停留在 GL 默认值 0，
     *        缩略图与预览效果不一致。
     */
    fun requestFilterThumbnails(
        callback: (List<Bitmap>) -> Unit,
        paramsProvider: ((Int) -> Map<String, Float>)? = null
    ) {
        thumbnailCallback = callback
        queueEvent {
            renderer.thumbnailFilterProvider = { idx ->
                filterCache.getOrPut(idx) { createFilter(idx).also { it.setup() } }.also { filter ->
                    if (filter is BaseFilter) {
                        paramsProvider?.invoke(idx)?.forEach { (name, value) ->
                            filter.setAdjustableParam(name, value)
                        }
                    }
                }
            }
            renderer.onThumbnailsReady = { bitmaps ->
                // 生成完成后恢复按需渲染（若当前是动画滤镜则由 updateRenderMode 保持连续）
                updateRenderMode()
                renderer.onThumbnailsReady = null
                post {
                    thumbnailCallback?.invoke(bitmaps)
                    thumbnailCallback = null
                }
            }
            renderer.requestFilterThumbnails(FilterCatalog.filters.size)
            // 生成期间必须连续渲染，让跨帧批量处理持续推进
            if (renderMode != RENDERMODE_CONTINUOUSLY) {
                renderMode = RENDERMODE_CONTINUOUSLY
                requestRender()
            }
        }
    }

    /** 相机 SurfaceTexture 引用，由 renderer 回调赋值 */
    private var cameraSurfaceTexture: SurfaceTexture? = null

    /** 待处理的 SurfaceRequest（SurfaceTexture 尚未就绪时暂存） */
    private var pendingSurfaceRequest: SurfaceRequest? = null

    /** 外部回调：当 GL Surface 就绪后触发（用于延迟绑定 CameraX 预览） */
    var onGlSurfaceReady: (() -> Unit)? = null

    /** 外部回调：直方图统计结果（主线程，~6Hz） */
    var onHistogramBins: ((HistogramBins) -> Unit)? = null

    init {
        // 配置 OpenGL ES 3.0（E1：升级以支持 sampler 数组 + 动态循环，提升多帧上限至 8）
        // minSdk=24（Android 7.0）设备 ES 3.0 覆盖率 ~100%，无需 2.0 回退
        setEGLContextClientVersion(3)

        // 创建渲染器
        renderer = GLRenderer(context.assets)

        // 设置 SurfaceTexture 就绪回调（在 GL 线程调用）
        renderer.onSurfaceTextureReady = { st ->
            cameraSurfaceTexture = st
            // 切到主线程处理
            post {
                // 处理暂存的 SurfaceRequest
                pendingSurfaceRequest?.let { request ->
                    pendingSurfaceRequest = null
                    provideSurfaceForRequest(request)
                }
                // 通知外部 GL 表面已就绪
                onGlSurfaceReady?.invoke()
            }
        }

        // 新相机帧到达时请求重绘：WHEN_DIRTY 模式下画面持续更新
        renderer.onNewFrameAvailable = {
            requestRender()
        }

        // 直方图统计在 GL 线程产出，切主线程再更新 UI 状态
        renderer.histogramProbe.onBins = { bins ->
            post { onHistogramBins?.invoke(bins) }
        }

        // GL 上下文重建：旧 context 下的 program/纹理句柄全部失效，
        // 重建滤镜缓存的 GL 资源（实例保留 → 可调参数不丢），并恢复当前滤镜。
        renderer.onGlContextRecreated = {
            Log.d(TAG, "GL 上下文重建，重建滤镜缓存资源")
            filterCache.values.forEach { filter ->
                runCatching {
                    filter.destroy()
                    filter.setup()
                }.onFailure {
                    Log.e(TAG, "滤镜资源重建失败: ${filter.getName()}", it)
                }
            }
            val idx = currentFilterIndex
            if (idx > 0) {
                filterCache[idx]?.let { filter ->
                    // 覆盖 onSurfaceCreated 里 pending 的 Passthrough，直接切回当前滤镜
                    renderer.filterChain.activateFilter(filter)
                }
            }
        }

        // 保留 GL 上下文，避免 onPause/onResume 时丢失
        setPreserveEGLContextOnPause(true)

        // 设置渲染器（必须在 renderMode 之前调用）
        setRenderer(renderer)

        // 设置渲染模式为连续渲染
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // ── Preview.SurfaceProvider 实现 ──────────────────────────────

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val surfaceTexture = cameraSurfaceTexture
        if (surfaceTexture != null) {
            provideSurfaceForRequest(request)
        } else {
            // SurfaceTexture 尚未就绪，暂存请求等待 GL 线程创建后处理
            Log.d(TAG, "SurfaceTexture 未就绪，暂存 SurfaceRequest")
            pendingSurfaceRequest = request
        }
    }

    /**
     * 为给定的 SurfaceRequest 提供 Surface。
     * 必须在主线程调用。
     */
    private fun provideSurfaceForRequest(request: SurfaceRequest) {
        val surfaceTexture = cameraSurfaceTexture
        if (surfaceTexture == null) {
            request.willNotProvideSurface()
            return
        }

        // 注册变换信息监听（仅用于调试日志）
        val mainExecutor = ContextCompat.getMainExecutor(context)
        request.setTransformationInfoListener(mainExecutor, object : SurfaceRequest.TransformationInfoListener {
            override fun onTransformationInfoUpdate(info: SurfaceRequest.TransformationInfo) {
                Log.d(TAG, "TransformationInfo: rotation=${info.rotationDegrees}°")
            }
        })

        val size = request.resolution
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        // 把相机缓冲尺寸传给渲染器，用于推导相机比例与画幅裁剪
        renderer.setCameraBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)
        request.provideSurface(surface, cameraManager.cameraExecutor) {
            surface.release()
        }
    }

    /**
     * 释放资源。
     * 应在 Activity/Fragment 销毁时调用。
     */
    fun release() {
        queueEvent {
            renderer.release()
            // 释放所有缓存的滤镜
            filterCache.values.forEach { it.destroy() }
            filterCache.clear()
        }
    }

    // ── 视频录制控制 ────────────────────────────────────────────

    /** 当前活跃的录制器引用 */
    private var activeRecorder: GLVideoRecorder? = null

    /**
     * 开始录制视频（带滤镜）。
     * 在 GL 线程中初始化编码器并开始录制。
     *
     * @param recorder GLVideoRecorder 实例
     * @param onResult 录制启动结果回调（在主线程调用）
     */
    fun startVideoRecording(recorder: GLVideoRecorder, onResult: (Boolean) -> Unit) {
        queueEvent {
            try {
                activeRecorder = recorder
                renderer.setVideoRecorder(recorder)
                // 录像尺寸跟随当前画幅：取内容尺寸，长边限制 1080，偶数化
                val (cw, ch) = renderer.getContentSize()
                val (vw, vh) = capRecordingSize(cw, ch)
                recorder.startRecording(vw, vh)
                // 录制期间必须连续渲染，保证每一帧都输出到编码器
                updateRenderMode()
                post { onResult(true) }
            } catch (e: Exception) {
                Log.e(TAG, "启动录制失败", e)
                activeRecorder = null
                renderer.setVideoRecorder(null)
                post { onResult(false) }
            }
        }
    }

    /** 录像尺寸：保持画幅比例，长边限制 maxLong，并偶数化。 */
    private fun capRecordingSize(w: Int, h: Int, maxLong: Int = 1080): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return Pair(1280, 720)
        val long = maxOf(w, h)
        val scale = if (long > maxLong) maxLong.toFloat() / long else 1f
        var rw = (w * scale).toInt() and 0x7FFFFFFE
        var rh = (h * scale).toInt() and 0x7FFFFFFE
        if (rw < 2) rw = 2
        if (rh < 2) rh = 2
        return Pair(rw, rh)
    }

    /**
     * 停止录制视频。
     * 在 GL 线程中停止编码器并完成视频文件。
     */
    fun stopVideoRecording() {
        queueEvent {
            activeRecorder?.stopRecording()
            activeRecorder = null
            renderer.setVideoRecorder(null)
            // 恢复按需渲染（若当前滤镜是动画滤镜则由 updateRenderMode 保持连续）
            updateRenderMode()
        }
    }
}
