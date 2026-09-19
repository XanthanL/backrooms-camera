package com.photoria.backrooms.gl

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.photoria.backrooms.camera.PreProcessor
import com.photoria.backrooms.encoder.GLVideoRecorder
import com.photoria.backrooms.gl.filter.Filter
import com.photoria.backrooms.gl.filter.PassthroughFilter
import com.photoria.backrooms.util.ShaderHelper
import com.photoria.backrooms.util.TextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * 后室相机 App 的核心 OpenGL 渲染器。
 *
 * 渲染管线流程：
 *   Camera SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *     → OesTo2D shader 渲染到 FBO（OES → 2D 纹理转换）
 *     → FilterChain.apply(2D 纹理) → 滤镜处理 → 输出到屏幕
 *
 * 职责：
 *   - 创建 OES 外部纹理并绑定 SurfaceTexture（供 CameraX 输出）
 *   - OES → 2D 纹理转换（因为滤镜 shader 使用 sampler2D）
 *   - 每帧将转换后的纹理通过 FilterChain 渲染到屏幕
 *   - 拍照：将当前帧渲染到离屏 FBO 并读取像素为 Bitmap
 *
 * 所有方法必须在 GL 线程调用。
 */
class GLRenderer(
    private val assetManager: AssetManager
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "GLRenderer"
        /** 每帧最多生成几张滤镜缩略图（跨帧分摊 shader 编译成本） */
        private const val MAX_THUMBS_PER_FRAME = 2
        /** 块匹配块大小（像素），须与 align_blockmatch.glsl #define 一致 */
        const val ALIGN_BLOCK_SIZE = 16
        /** 块匹配搜索半径（像素），须与 align_blockmatch.glsl #define 一致 */
        const val ALIGN_SEARCH_RADIUS = 4
        /** 金字塔 3 层最大总偏移：4*(1+2+4)=28，须与 align_blockmatch.glsl MAX_OFFSET 一致 */
        const val ALIGN_MAX_OFFSET = 28
        /**
         * 对齐工作分辨率上限（最大边像素）。
         *
         * 多帧对齐需同时驻留 N 个 RGBA + N-1 个变形 RGBA + 1 个合并 FBO。
         * 全分辨率（如 12MP）下峰值可达数百 MB，低端机易 OOM。
         * D3：当捕获分辨率超过此值时，整个对齐+变形+融合管线降级到工作分辨率
         * （保留宽高比），RGBA FBO 显存按 (workScale)² 比例下降。
         * 2048 对应 4:3 约 3.1MP，8 个 FBO ≈ 100MB，安全且仍高于 1080p。
         */
        const val ALIGN_MAX_WORK_DIMENSION = 2048
        /**
         * 多帧融合最大帧数（E1 ES 3.0 升级）。
         *
         * ES 2.0 下 merge shader 硬编码 4 帧（uTex0..3），无法扩展。
         * ES 3.0 支持 sampler 数组 + 动态循环，帧数上限提升至 8。
         * 纹理单元占用：N RGBA + (N-1) SAD = 2N-1，N=8 时 15 个单元，
         * ES 3.0 保证 GL_MAX_TEXTURE_IMAGE_UNITS ≥ 16。
         * 须与 merge.glsl/hdr_merge.glsl 的 MAX_FRAMES 一致。
         */
        const val MAX_MERGE_FRAMES = 8
    }

    /** SurfaceTexture 创建完成后的回调（在 GL 线程调用） */
    var onSurfaceTextureReady: ((android.graphics.SurfaceTexture) -> Unit)? = null

    /**
     * GL 上下文重建后的回调（在 GL 线程调用）。
     *
     * 设备可能在任何时机销毁并重建 EGL 上下文（后台恢复、MIUI 杀上下文、
     * Surface 重建等），此时旧的 program/texture/FBO 句柄全部失效。
     * 外层通过此回调重建滤镜缓存中的 GL 资源，避免用失效句柄渲染导致
     * GL 驱动崩溃（表现为切换滤镜时闪退）。
     */
    var onGlContextRecreated: (() -> Unit)? = null

    /**
     * SurfaceTexture 收到新相机帧的回调（相机生产者线程调用）。
     *
     * WHEN_DIRTY 模式下 GLSurfaceView 只在 requestRender() 时重绘，
     * 必须每来一帧请求一次渲染，预览才能持续更新（否则静态滤镜下
     * 画面停留在第一帧）。连续渲染模式下此回调多余但无害。
     */
    var onNewFrameAvailable: (() -> Unit)? = null

    // ── OES 外部纹理（相机输出）────────────────────────────────────
    /** OES 纹理 ID */
    private var cameraTextureId = 0
    /** SurfaceTexture 用于接收相机帧 */
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    /** SurfaceTexture 是否有新帧可用 */
    @Volatile
    private var frameAvailable = false

    // ── OES → 2D 转换 ─────────────────────────────────────────────
    /** 转换用 shader program */
    private var oesTo2dProgram = 0
    private var oesTo2dPositionHandle = 0
    private var oesTo2dTexCoordHandle = 0
    private var oesTo2dTextureHandle = 0
    private var oesTo2dTransformHandle = 0
    private var oesTo2dUvScaleHandle = 0
    private var oesTo2dUvOffsetHandle = 0
    private var oesTo2dQuadScaleHandle = 0
    private var oesTo2dQuadOffsetHandle = 0
    /** OES→2D 转换 FBO */
    private var oesFboId = 0
    private var oesFboTextureId = 0
    private var oesFboWidth = 0
    private var oesFboHeight = 0

    // ── 多帧融合（阶段2：简单平均降噪）──────────────────────────────
    /** YUV→RGB 转换 program（3 luminance 纹理 → RGBA） */
    private var yuvToRgbProgram = 0
    private var yuvToRgbPositionHandle = 0
    private var yuvToRgbTexCoordHandle = 0
    private var yuvToRgbYHandle = 0
    private var yuvToRgbUHandle = 0
    private var yuvToRgbVHandle = 0
    /** 多帧加权平均 program（最多 8 帧 RGBA → RGBA，E1 ES 3.0） */
    private var mergeProgram = 0
    private var mergePositionHandle = 0
    private var mergeTexCoordHandle = 0
    private var mergeTexHandles = IntArray(MAX_MERGE_FRAMES)
    private var mergeWeightsHandle = 0
    /** merge shader 实际帧数 uniform（uFrameCount） */
    private var mergeFrameCountHandle = 0
    /** merge shader 的 SAD 残差纹理 handle（uSad[0..6]，参考帧无 SAD） */
    private var mergeSadHandles = IntArray(MAX_MERGE_FRAMES - 1)

    // ── 多帧对齐（阶段3：块匹配 + 变形 + HDR 融合）─────────────────
    /** 块匹配 program（ref Y + alt Y → 运动矢量场） */
    private var alignProgram = 0
    private var alignPositionHandle = 0
    private var alignTexCoordHandle = 0
    private var alignRefYHandle = 0
    private var alignAltYHandle = 0
    private var alignTexelSizeHandle = 0
    /** 变形 program（RGBA + 运动矢量 → 对齐 RGBA） */
    private var warpProgram = 0
    private var warpPositionHandle = 0
    private var warpTexCoordHandle = 0
    private var warpFrameHandle = 0
    private var warpMotionHandle = 0
    private var warpTexelSizeHandle = 0
    /** HDR 曝光融合 program（最多 8 帧 RGBA → RGBA，亮度加权，E1 ES 3.0） */
    private var hdrMergeProgram = 0
    private var hdrMergePositionHandle = 0
    private var hdrMergeTexCoordHandle = 0
    private var hdrMergeTexHandles = IntArray(MAX_MERGE_FRAMES)
    private var hdrMergeWeightsHandle = 0
    /** hdr_merge shader 实际帧数 uniform（uFrameCount） */
    private var hdrMergeFrameCountHandle = 0
    /** hdr_merge shader 的 SAD 残差纹理 handle（uSad[0..6]） */
    private var hdrMergeSadHandles = IntArray(MAX_MERGE_FRAMES - 1)

    // ── 金字塔下采样（A1：分层对齐）──────────────────────────────
    /** 高斯下采样 program（Y 纹理 → 1/2 分辨率 Y 纹理） */
    private var downsampleProgram = 0
    private var downsamplePositionHandle = 0
    private var downsampleTexCoordHandle = 0
    private var downsampleTexHandle = 0
    private var downsampleTexelSizeHandle = 0
    /** 块匹配金字塔新增 uniform handles */
    private var alignSearchCenterHandle = 0
    private var alignCoarseScaleHandle = 0
    /** L2 层使用的 1×1 零运动矢量占位纹理（uCoarseScale=0 时 shader 不采样） */
    private var zeroMotionTexId = 0

    // ── 滤镜链 ────────────────────────────────────────────────────
    val filterChain = FilterChain()

    // ── 取景辅助叠加 ──────────────────────────────────────────────
    /** 斑马纹/峰值对焦 pass（构造期不触碰 GL，首次绘制才编译 program） */
    private val proOverlay = ProOverlayPass(assetManager)

    /** 直方图取样器（降采样 + 回读 + 统计，构造期不触碰 GL） */
    val histogramProbe = HistogramProbe(assetManager)

    /** 取景辅助配置（仅 GL 线程写入：外层通过 queueEvent 下发） */
    @Volatile
    var proOverlayConfig = ProOverlayConfig()

    // ── 时间追踪 ──────────────────────────────────────────────────
    private var startTimeNs = 0L

    // ── 全屏四边形数据 ────────────────────────────────────────────
    private val quadVertices: FloatBuffer = createFloatBuffer(floatArrayOf(
        -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    ))
    private val quadTexCoords: FloatBuffer = createFloatBuffer(floatArrayOf(
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    ))

    // ── 拍照相关 ──────────────────────────────────────────────────
    @Volatile
    private var captureRequestPending = false
    private var captureCallback: ((Bitmap) -> Unit)? = null
    /** 拍照失败回调：出不了 Bitmap 的路径也必须给上层一个终结，否则快门永久禁用 */
    private var captureFailureCallback: ((String) -> Unit)? = null
    /** 离屏 FBO（拍照用） */
    private var captureFboId = 0
    private var captureTexId = 0
    private var captureWidth = 0
    private var captureHeight = 0
    /** 拍照用 OES→2D FBO（独立于预览 FBO，避免拍照分辨率与预览相互重建） */
    private var captureOesFboId = 0
    private var captureOesTexId = 0
    private var captureOesWidth = 0
    private var captureOesHeight = 0
    /** 拍照 readPixels 缓冲（跨次复用，避免 12MP 级每拍分配 ~48MB） */
    private var captureReadbackBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    /** 行翻转复用的行缓冲 */
    private var captureRowTmp1 = ByteArray(0)
    private var captureRowTmp2 = ByteArray(0)
    /** 设备 GL 最大纹理尺寸（拍照分辨率上限） */
    private var maxTextureSize = 4096

    // ── 视频录制 ─────────────────────────────────────────────────
    private var videoRecorder: GLVideoRecorder? = null

    // ── 滤镜缩略图 ───────────────────────────────────────────────
    /**
     * 缩略图滤镜提供器（由 CameraGLSurfaceView 注入：按索引从滤镜缓存获取，
     * 未创建则创建并 setup）。返回 null 表示该索引无滤镜（跳过）。
     */
    var thumbnailFilterProvider: ((Int) -> Filter)? = null
    /** 全部缩略图生成完成回调（GL 线程调用，参数为按索引排序的 Bitmap 列表） */
    var onThumbnailsReady: ((List<Bitmap>) -> Unit)? = null
    /** 待生成的滤镜索引队列（跨帧分批处理，避免一次性编译 20 个 shader 卡顿） */
    private val thumbnailQueue = ArrayDeque<Int>()
    /** 已生成的缩略图（index → Bitmap） */
    private val thumbnailBitmaps = mutableMapOf<Int, Bitmap>()
    /** 缩略图总数（用于构建结果列表） */
    private var thumbnailTotalCount = 0
    /** 缩略图输出尺寸（4:3，与预览构图一致） */
    private val thumbW = 96
    private val thumbH = 72
    /** 缩略图 OES→2D 源 FBO */
    private var thumbSourceFboId = 0
    private var thumbSourceTexId = 0
    /** 缩略图滤镜输出 FBO */
    private var thumbOutFboId = 0
    private var thumbOutTexId = 0
    /** 缩略图 readPixels 缓冲（跨批复用） */
    private var thumbReadbackBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)

    // ── 视口尺寸缓存 ──────────────────────────────────────────────
    private var viewWidth = 0
    private var viewHeight = 0

    // ── 画幅与裁剪 ────────────────────────────────────────────────
    /** 目标画幅 W/H，-1 表示匹配屏幕（FULL） */
    private var targetAspect: Float = -1f
    /** 画幅填充模式：0=填充(裁剪)，1=适配(完整显示黑边) */
    private var fitMode: Int = 0
    /** 相机帧缓冲尺寸（传感器原始方向，由 CameraGLSurfaceView 设置） */
    @Volatile private var cameraBufferW = 0
    @Volatile private var cameraBufferH = 0
    /** 相机帧旋转后的 W/H（竖屏锁屏下为竖向比例，如 3:4=0.75） */
    private var cameraAspect: Float = 0.75f
    /** 内容尺寸（= 屏幕内 letterbox 视口尺寸，所有 FBO 用它） */
    /** 内容画幅尺寸（letterbox 视口中的实际相机内容大小） */
    internal var contentW = 0
    internal var contentH = 0
    /** 屏幕渲染视口（letterbox） */
    private var viewportX = 0
    private var viewportY = 0
    private var viewportW = 0
    private var viewportH = 0
    /** OES→2D UV 缩放（裁剪模式：采样子区域；适配模式：1.0） */
    private var uvScaleX = 1f
    private var uvScaleY = 1f
    private var uvOffsetX = 0f
    private var uvOffsetY = 0f
    /** OES→2D quad 缩放（适配模式：在 FBO 内 letterbox；填充模式：1.0） */
    private var quadScaleX = 1f
    private var quadScaleY = 1f
    private var quadOffsetX = 0f
    private var quadOffsetY = 0f

    // 复用的临时数组，避免每帧分配（GL 线程 30-60fps 下减少 GC 压力）
    private val stMatrixBuffer = FloatArray(16)
    private val resolutionBuffer = floatArrayOf(0f, 0f)


    // ──────────────────────────────────────────────────────────────
    // GLSurfaceView.Renderer 实现
    // ──────────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        startTimeNs = System.nanoTime()

        // 查询设备 GL 纹理尺寸上限（决定拍照分辨率上限）
        val maxTex = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        maxTextureSize = maxTex[0].takeIf { it > 0 } ?: 4096
        Log.d(TAG, "GL_MAX_TEXTURE_SIZE=$maxTextureSize")

        // 创建 OES 外部纹理
        cameraTextureId = createOESTexture()

        // 创建 SurfaceTexture 并监听新帧
        surfaceTexture = android.graphics.SurfaceTexture(cameraTextureId).also { st ->
            st.setOnFrameAvailableListener { _ ->
                frameAvailable = true
                onNewFrameAvailable?.invoke()
            }
        }

        // 通知外部 SurfaceTexture 已就绪
        onSurfaceTextureReady?.invoke(surfaceTexture!!)

        // 编译 OES → 2D 转换 shader
        initOesTo2DProgram()

        // 编译多帧融合 shader（YUV→RGB + 加权平均）
        initMergePrograms()

        // 编译对齐管线 shader（块匹配 + 变形 + HDR 融合）
        initAlignmentPrograms()

        // 编译金字塔下采样 shader（A1：分层对齐）
        initDownsampleProgram()

        // 初始化滤镜链，默认 Passthrough
        filterChain.setFilter(PassthroughFilter(assetManager))

        // 通知外层：EGL 上下文已（重新）创建，缓存中的滤镜需要重建 GL 资源
        onGlContextRecreated?.invoke()

        // 旧 context 下的缩略图 FBO 句柄失效，重置以便按需重建
        resetThumbFbos()
        // 同理：滤镜链的 FBO A / passthrough / blend program 句柄也已失效
        filterChain.resetForNewContext()
        proOverlay.resetForNewContext()
        histogramProbe.resetForNewContext()

        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x${height}")
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    // 注：GLSurfaceView.Renderer 接口没有 surfaceDestroyed 回调，
    // 资源释放依赖外层 CameraGLSurfaceView.release() 经 queueEvent 在 GL 线程执行。
    // 极端情况下（Composable dispose 后 GL 线程先退出）事件可能被丢弃，
    // 此时 EGL context 随之销毁，驱动会回收该 context 的 program/纹理/FBO，
    // 不会泄漏到进程之外；显式 release() 仍是首选路径。

    override fun onDrawFrame(gl: GL10?) {
        // ── 1. 更新 SurfaceTexture（消费新的相机帧）────────────────
        val st = surfaceTexture
        if (st != null && frameAvailable) {
            st.updateTexImage()
            frameAvailable = false
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (cameraTextureId == 0) return

        // ── 2. 计算画幅布局（内容尺寸 + letterbox 视口 + 裁剪 UV）──
        computeLayout()

        // ── 2.5 滤镜缩略图分批生成（跨帧分摊 shader 编译与渲染开销）─
        // 录制期间跳过，避免与编码器抢 GL 负载。
        if (thumbnailQueue.isNotEmpty() && videoRecorder?.isRecording != true) {
            processThumbnailBatch(MAX_THUMBS_PER_FRAME)
        }

        // ── 3. 处理待切换滤镜，决定渲染路径 ───────────────────────
        // 原画（Passthrough）且无拍照/录制需求时，OES 纹理可直接渲染到屏幕，
        // 跳过 OES→2D 整帧拷贝 + 滤镜链 FBO pass，节省 GPU 负载。
        val filter = filterChain.beginFrame()
        // 取景辅助需要普通 2D 纹理（OES 纹理无法被 sampler2D 采样），开启时不能让路
        val directOes = filter is PassthroughFilter &&
                !captureRequestPending &&
                !proOverlayConfig.needsTexture &&
                videoRecorder?.isRecording != true

        if (directOes) {
            renderOesToScreen()
            return
        }

        // ── 4. OES → 2D 纹理转换（按内容尺寸 + 裁剪 UV）────────────
        ensureOesFbo(contentW, contentH)
        renderOesTo2D()

        // ── 5. 更新滤镜时间参数 ───────────────────────────────────
        val currentTime = (System.nanoTime() - startTimeNs) / 1_000_000_000.0f
        filterChain.getCurrentFilter()?.setParameter("uTime", currentTime)
        filterChain.getCurrentFilter()?.let { f ->
            resolutionBuffer[0] = contentW.toFloat()
            resolutionBuffer[1] = contentH.toFloat()
            f.setParameter("uResolution", resolutionBuffer)
        }

        // ── 6. 检查是否有拍照请求 ─────────────────────────────────
        val captureThisFrame = captureRequestPending
        if (captureThisFrame) {
            captureRequestPending = false
            performCapture(contentW, contentH)
        }

        // ── 7. 通过滤镜链渲染到屏幕（letterbox 视口）──────────────
        // 设置屏幕 letterbox 视口，FilterChain 在 outputFrameBuffer=0 时使用
        filterChain.setScreenViewport(viewportX, viewportY, viewportW, viewportH)
        // oesFboTextureId 是普通 2D 纹理，可以安全传给使用 sampler2D 的滤镜
        filterChain.apply(oesFboTextureId, contentW, contentH)

        // ── 7.5 取景辅助：斑马纹/峰值对焦叠加 + 直方图取样 ────────
        // 只读滤镜输出、只写屏幕，不参与 lastOutputTextureId → 不出片。
        val overlayCfg = proOverlayConfig
        val outputTexId = filterChain.lastOutputTextureId
        if (outputTexId != 0) {
            if (overlayCfg.active) {
                proOverlay.draw(
                    outputTexId, contentW, contentH, overlayCfg,
                    viewportX, viewportY, viewportW, viewportH
                )
            }
            // 回读会等 GPU 排空：拍照帧与录制帧让路（与缩略图批量同一策略）
            if (overlayCfg.histogram && !captureThisFrame &&
                videoRecorder?.isRecording != true
            ) {
                histogramProbe.maybeSample(outputTexId, contentW, contentH)
            }
        }

        // ── 8. 录像：直接把已滤镜纹理交给编码器（E2 单 pass）──────
        val recorder = videoRecorder
        val filteredTexId = filterChain.lastOutputTextureId
        if (recorder?.isRecording == true && filteredTexId != 0) {
            recorder.onFrameDrawn(filteredTexId, contentW, contentH)
        }
    }

    /**
     * 计算画幅布局：根据目标画幅、相机比例、视口尺寸，推导
     *   - 内容尺寸 contentW×contentH（= letterbox 视口尺寸，所有 FBO 用它）
     *   - 屏幕 letterbox 视口 viewportX/Y/W/H
     *   - OES 裁剪 UV（把相机裁剪填充到目标画幅）
     */
    private fun computeLayout() {
        val bw = cameraBufferW
        val bh = cameraBufferH
        if (bw > 0 && bh > 0) {
            // 竖屏锁屏 + 横置传感器 → 旋转后竖向比例 = min/max
            val aspect = minOf(bw, bh).toFloat() / maxOf(bw, bh)
            if (aspect > 0f && aspect.isFinite()) {
                cameraAspect = aspect
            }
        }

        val vw = viewWidth
        val vh = viewHeight
        if (vw <= 0 || vh <= 0) return

        val screenAspect = vw.toFloat() / vh.toFloat()
        // 目标画幅：FULL 用屏幕比例，否则用设定值
        val contentAspect = if (targetAspect < 0f) screenAspect else targetAspect

        // letterbox fit：内容完全可见，超出方向留黑边
        if (contentAspect >= screenAspect) {
            // 内容更宽 → 适配宽度
            viewportW = vw
            viewportH = (vw / contentAspect).toInt()
            viewportX = 0
            // 顶对齐（GL 原点在左下，Y 越大越靠屏幕顶部）：
            // 预览贴到屏幕顶部（位于 TopBar 之后），底部留黑供控制区使用。
            viewportY = vh - viewportH
        } else {
            // 内容更高 → 适配高度
            viewportH = vh
            viewportW = (vh * contentAspect).toInt()
            viewportX = (vw - viewportW) / 2
            viewportY = 0
        }
        // 取偶数（编码器友好）
        viewportW = viewportW and 0x7FFFFFFE
        viewportH = viewportH and 0x7FFFFFFE
        contentW = viewportW
        contentH = viewportH

        if (fitMode == 1) {
            // 适配模式：相机完整显示在目标画幅内，黑边填充。
            // quad 在 FBO 空间([-1,1])内 letterbox，UV 不裁剪。
            quadOffsetX = 0f; quadOffsetY = 0f
            if (cameraAspect >= contentAspect) {
                // 相机更宽 → 满宽，上下留黑
                quadScaleX = 1f
                quadScaleY = contentAspect / cameraAspect
            } else {
                // 相机更高 → 满高，左右留黑
                quadScaleY = 1f
                quadScaleX = cameraAspect / contentAspect
            }
            uvScaleX = 1f; uvScaleY = 1f
            uvOffsetX = 0f; uvOffsetY = 0f
        } else {
            // 填充模式：裁剪相机填充目标画幅，quad 满铺 FBO。
            quadScaleX = 1f; quadScaleY = 1f
            quadOffsetX = 0f; quadOffsetY = 0f
            val cropW: Float
            val cropH: Float
            if (cameraAspect >= contentAspect) {
                // 相机更宽 → 裁剪宽度
                cropW = contentAspect / cameraAspect
                cropH = 1f
            } else {
                // 相机更高 → 裁剪高度
                cropW = 1f
                cropH = cameraAspect / contentAspect
            }
            uvScaleX = cropW
            uvScaleY = cropH
            uvOffsetX = (1f - cropW) * 0.5f
            uvOffsetY = (1f - cropH) * 0.5f
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 获取 SurfaceTexture（供 CameraGLSurfaceView 提供给 CameraX）。
     */
    fun getSurfaceTexture(): android.graphics.SurfaceTexture? = surfaceTexture

    /**
     * 设置当前滤镜。
     */
    fun setFilter(filter: Filter) {
        filterChain.setFilter(filter)
    }

    /**
     * 设置实时调色参数（[AdjustmentEngine.pack] 输出）。
     * 必须在 GL 线程调用（由 CameraGLSurfaceView 经 queueEvent 过桥）。
     */
    fun setAdjustments(packed: FloatArray) {
        filterChain.setAdjustments(packed)
    }

    /**
     * 设置调色曲线 LUT（[CurveEngine.buildRgbaBytes] 输出）。
     * 必须在 GL 线程调用（由 CameraGLSurfaceView 经 queueEvent 过桥）。
     */
    fun setCurveLut(rgbaBytes: ByteArray) {
        filterChain.setCurveLut(rgbaBytes)
    }

    /**
     * 设置目标画幅（W/H）。-1 表示匹配屏幕（FULL，裁剪填充无黑边）。
     * 必须在 GL 线程调用。
     */
    fun setTargetAspect(aspect: Float) {
        targetAspect = aspect
    }

    /**
     * 设置画幅填充模式。0=填充(裁剪)，1=适配(完整显示黑边，无拉伸)。
     * 必须在 GL 线程调用。
     */
    fun setFitMode(mode: Int) {
        fitMode = mode
    }

    /**
     * 设置相机帧缓冲尺寸（由 CameraGLSurfaceView 在 setDefaultBufferSize 后调用）。
     * 可在任意线程调用（仅用于推导相机比例）。
     */
    fun setCameraBufferSize(width: Int, height: Int) {
        cameraBufferW = width
        cameraBufferH = height
    }

    /**
     * 获取当前内容尺寸（用于录像分辨率等）。
     */
    fun getContentSize(): Pair<Int, Int> = Pair(contentW, contentH)

    /**
     * 请求拍照。
     * 在下一帧渲染时，会将当前帧捕获为 Bitmap 并通过 callback 返回。
     * 此方法可从任意线程调用。
     *
     * @param callback 出片回调（GL 线程）
     * @param onFailure 无法出片时的回调（分辨率非法 / FBO 创建失败），GL 线程
     */
    fun capturePhoto(callback: (Bitmap) -> Unit, onFailure: (String) -> Unit) {
        captureCallback = callback
        captureFailureCallback = onFailure
        captureRequestPending = true
    }


    /**
     * 设置视频录制器。
     * 录制期间，每帧渲染后会自动将已滤镜纹理（FilterChain.lastOutputTextureId）
     * 输出给录制器，由录制器 blit 到编码器 Surface（E2 单 pass）。
     */
    fun setVideoRecorder(recorder: GLVideoRecorder?) {
        videoRecorder = recorder
    }

    /**
     * 释放所有 GL 资源。
     */
    fun release() {
        Log.d(TAG, "release")
        filterChain.release()
        proOverlay.release()
        histogramProbe.release()

        // 释放 SurfaceTexture
        surfaceTexture?.release()
        surfaceTexture = null

        // 释放 OES 纹理
        if (cameraTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = 0
        }

        // 释放 OES→2D FBO
        TextureHelper.deleteFrameBuffer(oesFboId, oesFboTextureId)
        oesFboId = 0; oesFboTextureId = 0

        // 释放 OES→2D program
        if (oesTo2dProgram != 0) {
            GLES20.glDeleteProgram(oesTo2dProgram)
            oesTo2dProgram = 0
        }

        // 释放多帧融合 program
        if (yuvToRgbProgram != 0) {
            GLES20.glDeleteProgram(yuvToRgbProgram)
            yuvToRgbProgram = 0
        }
        if (mergeProgram != 0) {
            GLES20.glDeleteProgram(mergeProgram)
            mergeProgram = 0
        }

        // 释放对齐管线 program
        if (alignProgram != 0) {
            GLES20.glDeleteProgram(alignProgram)
            alignProgram = 0
        }
        if (warpProgram != 0) {
            GLES20.glDeleteProgram(warpProgram)
            warpProgram = 0
        }
        if (hdrMergeProgram != 0) {
            GLES20.glDeleteProgram(hdrMergeProgram)
            hdrMergeProgram = 0
        }
        if (downsampleProgram != 0) {
            GLES20.glDeleteProgram(downsampleProgram)
            downsampleProgram = 0
        }

        // 释放零运动纹理
        if (zeroMotionTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(zeroMotionTexId), 0)
            zeroMotionTexId = 0
        }

        // 释放拍照 FBO
        destroyCaptureFBO()
        TextureHelper.deleteFrameBuffer(captureOesFboId, captureOesTexId)
        captureOesFboId = 0; captureOesTexId = 0

        // 释放缩略图 FBO
        TextureHelper.deleteFrameBuffer(thumbSourceFboId, thumbSourceTexId)
        thumbSourceFboId = 0; thumbSourceTexId = 0
        TextureHelper.deleteFrameBuffer(thumbOutFboId, thumbOutTexId)
        thumbOutFboId = 0; thumbOutTexId = 0

        Log.d(TAG, "GLRenderer 资源已释放")
    }

    // ──────────────────────────────────────────────────────────────
    // OES → 2D 转换
    // ──────────────────────────────────────────────────────────────

    /**
     * 初始化 OES → 2D 转换 shader program。
     * 使用 samplerExternalOES 采样 OES 纹理，输出到普通 2D FBO 纹理。
     */
    private fun initOesTo2DProgram() {
        val vertexSource = """
            precision mediump float;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTransform;
            uniform vec2 uUvScale;
            uniform vec2 uUvOffset;
            uniform vec2 uQuadScale;
            uniform vec2 uQuadOffset;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uQuadScale + uQuadOffset, 0.0, 1.0);
                vec4 transformed = uTransform * vec4(aTexCoord, 0.0, 1.0);
                vTexCoord = uUvOffset + transformed.xy * uUvScale;
            }
        """.trimIndent()

        // 必须使用 #extension GL_OES_EGL_image_external 和 samplerExternalOES
        val fragmentSource = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        oesTo2dProgram = ShaderHelper.buildProgram(vertexSource, fragmentSource)
        if (oesTo2dProgram == 0) {
            Log.e(TAG, "OES→2D shader 编译失败！")
            return
        }

        oesTo2dPositionHandle = GLES20.glGetAttribLocation(oesTo2dProgram, "aPosition")
        oesTo2dTexCoordHandle = GLES20.glGetAttribLocation(oesTo2dProgram, "aTexCoord")
        oesTo2dTextureHandle = GLES20.glGetUniformLocation(oesTo2dProgram, "uTexture")
        oesTo2dTransformHandle = GLES20.glGetUniformLocation(oesTo2dProgram, "uTransform")
        oesTo2dUvScaleHandle = GLES20.glGetUniformLocation(oesTo2dProgram, "uUvScale")
        oesTo2dUvOffsetHandle = GLES20.glGetUniformLocation(oesTo2dProgram, "uUvOffset")
        oesTo2dQuadScaleHandle = GLES20.glGetUniformLocation(oesTo2dProgram, "uQuadScale")
        oesTo2dQuadOffsetHandle = GLES20.glGetUniformLocation(oesTo2dProgram, "uQuadOffset")
    }

    /**
     * 确保 OES→2D 转换 FBO 尺寸与视口一致。
     */
    private fun ensureOesFbo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (oesFboWidth == width && oesFboHeight == height && oesFboId != 0) return

        // 释放旧 FBO
        TextureHelper.deleteFrameBuffer(oesFboId, oesFboTextureId)

        val (fboId, texId) = TextureHelper.createFrameBuffer(width, height)
        oesFboId = fboId
        oesFboTextureId = texId
        oesFboWidth = width
        oesFboHeight = height

        Log.d(TAG, "OES→2D FBO 已创建: ${width}x${height}")
    }

    /**
     * 将 OES 外部纹理渲染到预览 FBO（内容尺寸）。
     * 这一步将 GL_TEXTURE_EXTERNAL_OES 转换为普通 GL_TEXTURE_2D，
     * 使后续滤镜 shader（使用 sampler2D）可以正常采样。
     */
    private fun renderOesTo2D() {
        if (oesTo2dProgram == 0 || oesFboId == 0) return
        renderOesTo2DInto(oesFboId, oesFboWidth, oesFboHeight)
    }

    /**
     * 将 OES 外部纹理渲染到指定 FBO（预览/拍照共用）。
     */
    private fun renderOesTo2DInto(fboId: Int, width: Int, height: Int) {
        // 绑定 OES→2D FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
        // 适配模式下 quad 不满铺 FBO，先清黑作为黑边背景
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawOesQuad()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 将 OES 外部纹理直接渲染到屏幕（原画直通路径）。
     *
     * 复用同一 OES program 的 UV 裁剪（填充模式）/quad letterbox（适配模式）
     * 逻辑，与 FBO 中间转换路径的输出完全一致，但省去整帧拷贝。
     * 屏幕已在 onDrawFrame 清黑，适配模式的黑边区域即为纯黑。
     */
    private fun renderOesToScreen() {
        if (oesTo2dProgram == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewportX, viewportY, viewportW, viewportH)
        drawOesQuad()
    }

    /**
     * 使用 OES→2D program 绘制相机帧（当前 FBO + 视口已由调用方设置）。
     */
    private fun drawOesQuad() {
        GLES20.glUseProgram(oesTo2dProgram)

        // 绑定 OES 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(oesTo2dTextureHandle, 0)

        // 获取 SurfaceTexture 的变换矩阵（已正确处理 OES 纹理坐标映射）
        // 复用成员 buffer，避免每帧分配
        surfaceTexture?.getTransformMatrix(stMatrixBuffer) ?: run {
            stMatrixBuffer[0] = 1f; stMatrixBuffer[5] = 1f; stMatrixBuffer[10] = 1f; stMatrixBuffer[15] = 1f
        }
        GLES20.glUniformMatrix4fv(oesTo2dTransformHandle, 1, false, stMatrixBuffer, 0)

        // 裁剪 UV（填充模式裁剪；适配模式为 1.0）
        if (oesTo2dUvScaleHandle >= 0) {
            GLES20.glUniform2f(oesTo2dUvScaleHandle, uvScaleX, uvScaleY)
        }
        if (oesTo2dUvOffsetHandle >= 0) {
            GLES20.glUniform2f(oesTo2dUvOffsetHandle, uvOffsetX, uvOffsetY)
        }
        // quad 缩放（适配模式在 FBO 内 letterbox；填充模式为 1.0）
        if (oesTo2dQuadScaleHandle >= 0) {
            GLES20.glUniform2f(oesTo2dQuadScaleHandle, quadScaleX, quadScaleY)
        }
        if (oesTo2dQuadOffsetHandle >= 0) {
            GLES20.glUniform2f(oesTo2dQuadOffsetHandle, quadOffsetX, quadOffsetY)
        }

        // 绘制四边形
        GLES20.glEnableVertexAttribArray(oesTo2dPositionHandle)
        GLES20.glVertexAttribPointer(oesTo2dPositionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

        GLES20.glEnableVertexAttribArray(oesTo2dTexCoordHandle)
        GLES20.glVertexAttribPointer(oesTo2dTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        GLES20.glDisableVertexAttribArray(oesTo2dPositionHandle)
        GLES20.glDisableVertexAttribArray(oesTo2dTexCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    // ──────────────────────────────────────────────────────────────
    // 拍照实现
    // ──────────────────────────────────────────────────────────────

    /**
     * 执行拍照：以预览缓冲分辨率（长边上限受 GL_MAX_TEXTURE_SIZE 约束）渲染滤镜，
     * 读取像素为 Bitmap。
     *
     * 注意：这里的"相机缓冲"是 Preview 表面缓冲，其分辨率由 CameraManager 的
     * ResolutionSelector（4:3 策略）尽量推高，但不保证等于传感器最大输出；
     * 真机上如需严格全分辨率应改走 ImageCapture 管线。
     *
     * 优化：
     *   - readPixels 缓冲跨次复用（12MP 级约 48MB，避免每拍分配）
     *   - 行反转在缓冲内原地完成，替代 Canvas 翻转（少一次整图 Bitmap 分配）
     */
    private fun performCapture(viewportWidth: Int, viewportHeight: Int) {
        val callback = captureCallback
        val onFailure = captureFailureCallback
        captureCallback = null
        captureFailureCallback = null
        if (callback == null) return

        fun fail(reason: String) {
            Log.e(TAG, "拍照失败：$reason")
            onFailure?.invoke(reason)
        }

        val (capW, capH) = computeCaptureSize(viewportWidth, viewportHeight)
        if (capW <= 0 || capH <= 0) {
            fail("拍照分辨率非法（${capW}x${capH}）")
            return
        }

        // 1. 按捕获分辨率做 OES→2D 转换（复用相机帧，未再做一次预览分辨率渲染）
        ensureCaptureOesFbo(capW, capH)
        renderOesTo2DInto(captureOesFboId, capW, capH)

        // 2. 确保拍照 FBO 尺寸匹配
        if (captureWidth != capW || captureHeight != capH || captureFboId == 0) {
            destroyCaptureFBO()
            val (fbo, tex) = TextureHelper.createFrameBuffer(capW, capH)
            captureFboId = fbo
            captureTexId = tex
            captureWidth = capW
            captureHeight = capH
            if (fbo == 0) {
                fail("拍照 FBO 创建失败（${capW}x${capH}，显存不足？）")
                return
            }
        }

        // 3. 将滤镜链渲染到离屏 FBO（输入是已转换的 2D 纹理）
        filterChain.apply(captureOesTexId, captureFboId, capW, capH)

        // 4. 重新绑定拍照 FBO（filter.apply 内部会解绑 FBO）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFboId)
        GLES20.glViewport(0, 0, capW, capH)

        // 5. 读取像素（复用缓冲）
        val bytes = capW * capH * 4
        if (captureReadbackBuffer.capacity() < bytes) {
            captureReadbackBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        val buffer = captureReadbackBuffer
        buffer.position(0)
        GLES20.glReadPixels(0, 0, capW, capH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        // 解绑 FBO，恢复屏幕渲染
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // 6. 行反转（OpenGL 原点在左下，readPixels 输出自底向上 → 原地翻转成自顶向下）
        flipRowsInPlace(buffer, capW, capH)

        // 7. 拷贝为 Bitmap（单次分配，无 Canvas 二次翻转）
        val bitmap = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        Log.d(TAG, "拍照完成: ${capW}x${capH}")
        callback(bitmap)
    }

    /**
     * 计算拍照分辨率：保持内容画幅比例，长边取相机缓冲尺寸（上限 GL_MAX_TEXTURE_SIZE）。
     * 相机缓冲未知时回退到视口尺寸。
     */
    private fun computeCaptureSize(fallbackW: Int, fallbackH: Int): Pair<Int, Int> {
        val bw = cameraBufferW
        val bh = cameraBufferH
        if (bw <= 0 || bh <= 0) return Pair(fallbackW, fallbackH)
        // 内容画幅比例（竖屏 3:4 等，预览与照片取景一致）
        val aspect = if (contentW > 0 && contentH > 0) contentW.toFloat() / contentH else cameraAspect
        if (aspect <= 0f || !aspect.isFinite()) return Pair(fallbackW, fallbackH)

        val maxLong = minOf(maxOf(bw, bh), maxTextureSize)
        val capW: Int
        val capH: Int
        if (aspect >= 1f) {
            capW = maxLong
            capH = (maxLong / aspect).roundToInt()
        } else {
            capH = maxLong
            capW = (maxLong * aspect).roundToInt()
        }
        // 偶数化（编码器/纹理友好）
        val ew = capW and 0x7FFFFFFE
        val eh = capH and 0x7FFFFFFE
        if (ew <= 0 || eh <= 0) return Pair(fallbackW, fallbackH)
        return Pair(ew, eh)
    }

    /**
     * 在缓冲内原地做垂直行反转（glReadPixels 输出自底向上）。
     */
    private fun flipRowsInPlace(buffer: ByteBuffer, width: Int, height: Int) {
        val rowBytes = width * 4
        if (captureRowTmp1.size < rowBytes) captureRowTmp1 = ByteArray(rowBytes)
        if (captureRowTmp2.size < rowBytes) captureRowTmp2 = ByteArray(rowBytes)
        for (row in 0 until height / 2) {
            val topPos = row * rowBytes
            val bottomPos = (height - 1 - row) * rowBytes
            buffer.position(topPos)
            buffer.get(captureRowTmp1, 0, rowBytes)
            buffer.position(bottomPos)
            buffer.get(captureRowTmp2, 0, rowBytes)
            buffer.position(topPos)
            buffer.put(captureRowTmp2, 0, rowBytes)
            buffer.position(bottomPos)
            buffer.put(captureRowTmp1, 0, rowBytes)
        }
    }

    /**
     * 确保拍照用 OES→2D FBO 尺寸匹配。
     */
    private fun ensureCaptureOesFbo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (captureOesWidth == width && captureOesHeight == height && captureOesFboId != 0) return
        TextureHelper.deleteFrameBuffer(captureOesFboId, captureOesTexId)
        val (fboId, texId) = TextureHelper.createFrameBuffer(width, height)
        captureOesFboId = fboId
        captureOesTexId = texId
        captureOesWidth = width
        captureOesHeight = height
    }

    private fun destroyCaptureFBO() {
        if (captureFboId != 0) {
            TextureHelper.deleteFrameBuffer(captureFboId, captureTexId)
            captureFboId = 0
            captureTexId = 0
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 滤镜缩略图（跨帧分批生成）
    // ──────────────────────────────────────────────────────────────

    /**
     * 请求生成全量滤镜缩略图。
     * 入队全部索引，由后续若干帧的 onDrawFrame 分批处理（见 processThumbnailBatch）。
     * 全部完成后经 [onThumbnailsReady] 回调（GL 线程）。
     *
     * @param count 滤镜总数（结果列表长度 = count）
     */
    fun requestFilterThumbnails(count: Int) {
        thumbnailTotalCount = count
        thumbnailQueue.clear()
        thumbnailBitmaps.clear()
        for (i in 0 until count) thumbnailQueue.add(i)
    }

    /**
     * 每帧处理至多 maxPerFrame 张缩略图。
     * 用当前相机帧 + 预览裁剪 UV（computeLayout 已计算）渲染小图，
     * 保证缩略图与取景构图一致。
     */
    private fun processThumbnailBatch(maxPerFrame: Int) {
        var processed = 0
        while (processed < maxPerFrame && thumbnailQueue.isNotEmpty()) {
            val idx = thumbnailQueue.removeFirst()
            val filter = thumbnailFilterProvider?.invoke(idx) ?: continue
            renderFilterThumbnail(idx, filter)
            processed++
        }
        if (thumbnailQueue.isEmpty()) {
            val result = List(thumbnailTotalCount) { idx ->
                thumbnailBitmaps[idx] ?: Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
            }
            onThumbnailsReady?.invoke(result)
        }
    }

    /**
     * 将当前相机帧经指定滤镜渲染为缩略图并读取为 Bitmap。
     */
    private fun renderFilterThumbnail(index: Int, filter: Filter) {
        ensureThumbFbos()
        if (thumbSourceFboId == 0 || thumbOutFboId == 0) return

        // 1. 当前相机帧 → OES→2D 缩略源（复用预览裁剪 UV，构图与取景一致）
        renderOesTo2DInto(thumbSourceFboId, thumbW, thumbH)

        // 2. 滤镜处理 → 输出 FBO（filter.apply 内部会绑定/解绑 FBO 与视口）
        filter.apply(thumbSourceTexId, thumbOutFboId, thumbW, thumbH)

        // 3. 读像素（复用缓冲）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, thumbOutFboId)
        GLES20.glViewport(0, 0, thumbW, thumbH)
        val bytes = thumbW * thumbH * 4
        if (thumbReadbackBuffer.capacity() < bytes) {
            thumbReadbackBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        val buffer = thumbReadbackBuffer
        buffer.position(0)
        GLES20.glReadPixels(0, 0, thumbW, thumbH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // 4. 行翻转 + 拷贝为 Bitmap
        flipRowsInPlace(buffer, thumbW, thumbH)
        val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        thumbnailBitmaps[index] = bitmap
    }

    /** 确保缩略图 FBO 已创建（尺寸固定 96x72）。 */
    private fun ensureThumbFbos() {
        if (thumbSourceFboId != 0 && thumbOutFboId != 0) return
        val (sf, st) = TextureHelper.createFrameBuffer(thumbW, thumbH)
        val (of, ot) = TextureHelper.createFrameBuffer(thumbW, thumbH)
        thumbSourceFboId = sf; thumbSourceTexId = st
        thumbOutFboId = of; thumbOutTexId = ot
    }

    /** 重置缩略图 FBO（GL 上下文重建后旧句柄失效）。 */
    private fun resetThumbFbos() {
        thumbSourceFboId = 0; thumbSourceTexId = 0
        thumbOutFboId = 0; thumbOutTexId = 0
    }

    // ──────────────────────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 创建 OES 外部纹理（GL_TEXTURE_EXTERNAL_OES）。
     */
    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return texId
    }

    // ──────────────────────────────────────────────────────────────
    // 多帧 YUV → GL 纹理上传（阶段1 基础设施）
    // ──────────────────────────────────────────────────────────────

    /**
     * 将单帧紧凑 I420 YUV 数据上传为 3 个 GL 纹理。
     *
     * Y/U/V 各上传为 GL_LUMINANCE 纹理（ES 2.0 兼容，采样时 .r = 亮度值）。
     * 阶段2 融合 shader 通过 sampler2D 采样这 3 个纹理做 YUV→RGB 转换与多帧融合。
     *
     * 必须在 GL 线程调用。调用方负责用 [releaseYuvTextures] 释放。
     *
     * @param frame 紧凑 I420 帧（来自 PreProcessor）
     * @return IntArray(3) = [yTexId, uTexId, vTexId]；失败返回全 0
     */
    fun uploadYuvFrame(frame: PreProcessor.YuvFrame): IntArray {
        val yTex = createLuminanceTexture(frame.yData, frame.width, frame.height)
        val uvW = frame.width / 2
        val uvH = frame.height / 2
        val uTex = createLuminanceTexture(frame.uData, uvW, uvH)
        val vTex = createLuminanceTexture(frame.vData, uvW, uvH)
        return intArrayOf(yTex, uTex, vTex)
    }

    /**
     * 批量上传多帧 YUV 为纹理队列。
     *
     * @param frames YUV 帧列表
     * @return 纹理集合列表，每个元素 = [yTexId, uTexId, vTexId]
     */
    fun uploadYuvFrames(frames: List<PreProcessor.YuvFrame>): List<IntArray> {
        return frames.map { uploadYuvFrame(it) }
    }

    /**
     * 释放一组 YUV 纹理（3 个）。
     */
    fun releaseYuvTextures(textures: IntArray) {
        if (textures.any { it != 0 }) {
            GLES20.glDeleteTextures(textures.size, textures, 0)
        }
    }

    /**
     * 释放多帧纹理队列。
     */
    fun releaseYuvFrameSet(textureSet: List<IntArray>) {
        textureSet.forEach { releaseYuvTextures(it) }
    }

    /**
     * 创建 GL_LUMINANCE 纹理并上传单通道数据。
     * ES 2.0 兼容：GL_LUMINANCE 将单通道值复制到 RGB。
     */
    private fun createLuminanceTexture(data: ByteArray, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]
        if (texId == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val buffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        buffer.put(data).position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            width, height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }

    // ──────────────────────────────────────────────────────────────
    // 多帧融合渲染（阶段2：简单平均降噪）
    // ──────────────────────────────────────────────────────────────

    /**
     * 初始化多帧融合 shader（YUV→RGB + 加权平均）。
     * 从 assets 加载 fragment shader，顶点 shader 内联。
     */
    private fun initMergePrograms() {
        val vertexSource = """
            precision mediump float;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // E1 ES 3.0 顶点 shader（merge/hdr_merge fragment 为 #version 300 es，需匹配）
        val es3VertexSource = """
            #version 300 es
            precision mediump float;
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // YUV → RGB
        val yuvFragSource = try {
            assetManager.open("shaders/fragment/yuv_to_rgb.glsl")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "yuv_to_rgb.glsl 加载失败", e)
            return
        }
        yuvToRgbProgram = ShaderHelper.buildProgram(vertexSource, yuvFragSource)
        if (yuvToRgbProgram == 0) {
            Log.e(TAG, "YUV→RGB shader 编译失败")
        } else {
            yuvToRgbPositionHandle = GLES20.glGetAttribLocation(yuvToRgbProgram, "aPosition")
            yuvToRgbTexCoordHandle = GLES20.glGetAttribLocation(yuvToRgbProgram, "aTexCoord")
            yuvToRgbYHandle = GLES20.glGetUniformLocation(yuvToRgbProgram, "uY")
            yuvToRgbUHandle = GLES20.glGetUniformLocation(yuvToRgbProgram, "uU")
            yuvToRgbVHandle = GLES20.glGetUniformLocation(yuvToRgbProgram, "uV")
        }

        // Merge（多帧加权平均）
        val mergeFragSource = try {
            assetManager.open("shaders/fragment/merge.glsl")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "merge.glsl 加载失败", e)
            return
        }
        mergeProgram = ShaderHelper.buildProgram(es3VertexSource, mergeFragSource)
        if (mergeProgram == 0) {
            Log.e(TAG, "merge shader 编译失败")
        } else {
            mergePositionHandle = GLES20.glGetAttribLocation(mergeProgram, "aPosition")
            mergeTexCoordHandle = GLES20.glGetAttribLocation(mergeProgram, "aTexCoord")
            // sampler 数组：查询每个 uTex[i]
            for (i in 0 until MAX_MERGE_FRAMES) {
                mergeTexHandles[i] = GLES20.glGetUniformLocation(mergeProgram, "uTex[$i]")
            }
            mergeWeightsHandle = GLES20.glGetUniformLocation(mergeProgram, "uWeights[0]")
            mergeFrameCountHandle = GLES20.glGetUniformLocation(mergeProgram, "uFrameCount")
            // SAD 残差纹理 handle（uSad[0..6]，参考帧无 SAD）
            for (i in 0 until MAX_MERGE_FRAMES - 1) {
                mergeSadHandles[i] = GLES20.glGetUniformLocation(mergeProgram, "uSad[$i]")
            }
        }
    }

    /**
     * 绘制全屏四边形（复用 quadVertices / quadTexCoords）。
     */
    private fun drawSimpleQuad(posHandle: Int, texHandle: Int) {
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    /**
     * 单帧 YUV→RGB 渲染：3 个 luminance 纹理 → 目标 FBO 的 RGBA 纹理。
     */
    private fun renderYuvToRgb(
        yTex: Int, uTex: Int, vTex: Int,
        targetFbo: Int, w: Int, h: Int
    ) {
        if (yuvToRgbProgram == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(yuvToRgbProgram)

        // Y → unit 0, U → unit 1, V → unit 2
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTex)
        GLES20.glUniform1i(yuvToRgbYHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTex)
        GLES20.glUniform1i(yuvToRgbUHandle, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTex)
        GLES20.glUniform1i(yuvToRgbVHandle, 2)

        drawSimpleQuad(yuvToRgbPositionHandle, yuvToRgbTexCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 多帧加权平均渲染：N 个 RGBA 纹理 → 目标 FBO。
     * 等权平均 + SAD 残差加权（残差大的块自动降权）。
     *
     * @param rgbaTextures N 帧 RGBA 纹理（已对齐）
     * @param sadTextures N-1 个 SAD 残差纹理（目标帧 1..N-1，参考帧无 SAD）
     */
    private fun renderMerge(
        rgbaTextures: List<Int>,
        sadTextures: List<Int>,
        targetFbo: Int, w: Int, h: Int
    ) {
        if (mergeProgram == 0) return
        val n = minOf(rgbaTextures.size, MAX_MERGE_FRAMES)
        if (n == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(mergeProgram)

        // 绑定最多 8 个 RGBA 纹理到 unit 0-7
        for (i in 0 until MAX_MERGE_FRAMES) {
            val texId = if (i < n) rgbaTextures[i] else rgbaTextures[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(mergeTexHandles[i], i)
        }

        // 绑定 SAD 残差纹理到 unit 8-14（参考帧无 SAD，从目标帧 1 开始）
        val sadCount = minOf(sadTextures.size, MAX_MERGE_FRAMES - 1)
        for (i in 0 until MAX_MERGE_FRAMES - 1) {
            val texId = if (i < sadCount) sadTextures[i] else 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE8 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(mergeSadHandles[i], MAX_MERGE_FRAMES + i)
        }

        // uFrameCount：实际帧数（动态循环上界）
        GLES20.glUniform1i(mergeFrameCountHandle, n)

        // 等权：每帧 1/N，不足 8 帧时多余权重归零
        val invN = 1f / n
        val weights = FloatArray(MAX_MERGE_FRAMES)
        for (i in 0 until n) { weights[i] = invN }
        GLES20.glUniform1fv(mergeWeightsHandle, MAX_MERGE_FRAMES, weights, 0)

        drawSimpleQuad(mergePositionHandle, mergeTexCoordHandle)

        for (i in 0 until MAX_MERGE_FRAMES) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        for (i in 0 until MAX_MERGE_FRAMES - 1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE8 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 从拍照 FBO 回读像素为 Bitmap（复用 readback 缓冲 + 行反转）。
     * 供单帧/多帧拍照共用，避免 readback 代码重复。
     */
    private fun readbackToBitmap(capW: Int, capH: Int): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFboId)
        GLES20.glViewport(0, 0, capW, capH)
        val bytes = capW * capH * 4
        if (captureReadbackBuffer.capacity() < bytes) {
            captureReadbackBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        val buffer = captureReadbackBuffer
        buffer.position(0)
        GLES20.glReadPixels(0, 0, capW, capH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        flipRowsInPlace(buffer, capW, capH)
        val bitmap = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    // ──────────────────────────────────────────────────────────────
    // 多帧对齐 + HDR 融合（阶段3）
    // ──────────────────────────────────────────────────────────────

    /**
     * 初始化对齐管线 shader（块匹配 + 变形 + HDR 融合）。
     */
    private fun initAlignmentPrograms() {
        val vertexSource = """
            precision mediump float;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // E1 ES 3.0 顶点 shader（hdr_merge fragment 为 #version 300 es，需匹配）
        val es3VertexSource = """
            #version 300 es
            precision mediump float;
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // 块匹配
        val alignFragSource = try {
            assetManager.open("shaders/fragment/align_blockmatch.glsl")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "align_blockmatch.glsl 加载失败", e); return
        }
        alignProgram = ShaderHelper.buildProgram(vertexSource, alignFragSource)
        if (alignProgram == 0) {
            Log.e(TAG, "块匹配 shader 编译失败")
        } else {
            alignPositionHandle = GLES20.glGetAttribLocation(alignProgram, "aPosition")
            alignTexCoordHandle = GLES20.glGetAttribLocation(alignProgram, "aTexCoord")
            alignRefYHandle = GLES20.glGetUniformLocation(alignProgram, "uRefY")
            alignAltYHandle = GLES20.glGetUniformLocation(alignProgram, "uAltY")
            alignTexelSizeHandle = GLES20.glGetUniformLocation(alignProgram, "uTexelSize")
            alignSearchCenterHandle = GLES20.glGetUniformLocation(alignProgram, "uSearchCenter")
            alignCoarseScaleHandle = GLES20.glGetUniformLocation(alignProgram, "uCoarseScale")
        }

        // 变形
        val warpFragSource = try {
            assetManager.open("shaders/fragment/warp.glsl")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "warp.glsl 加载失败", e); return
        }
        warpProgram = ShaderHelper.buildProgram(vertexSource, warpFragSource)
        if (warpProgram == 0) {
            Log.e(TAG, "变形 shader 编译失败")
        } else {
            warpPositionHandle = GLES20.glGetAttribLocation(warpProgram, "aPosition")
            warpTexCoordHandle = GLES20.glGetAttribLocation(warpProgram, "aTexCoord")
            warpFrameHandle = GLES20.glGetUniformLocation(warpProgram, "uFrame")
            warpMotionHandle = GLES20.glGetUniformLocation(warpProgram, "uMotion")
            warpTexelSizeHandle = GLES20.glGetUniformLocation(warpProgram, "uTexelSize")
        }

        // HDR 曝光融合
        val hdrFragSource = try {
            assetManager.open("shaders/fragment/hdr_merge.glsl")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "hdr_merge.glsl 加载失败", e); return
        }
        hdrMergeProgram = ShaderHelper.buildProgram(es3VertexSource, hdrFragSource)
        if (hdrMergeProgram == 0) {
            Log.e(TAG, "HDR 融合 shader 编译失败")
        } else {
            hdrMergePositionHandle = GLES20.glGetAttribLocation(hdrMergeProgram, "aPosition")
            hdrMergeTexCoordHandle = GLES20.glGetAttribLocation(hdrMergeProgram, "aTexCoord")
            for (i in 0 until MAX_MERGE_FRAMES) {
                hdrMergeTexHandles[i] = GLES20.glGetUniformLocation(hdrMergeProgram, "uTex[$i]")
            }
            hdrMergeWeightsHandle = GLES20.glGetUniformLocation(hdrMergeProgram, "uWeights[0]")
            hdrMergeFrameCountHandle = GLES20.glGetUniformLocation(hdrMergeProgram, "uFrameCount")
            for (i in 0 until MAX_MERGE_FRAMES - 1) {
                hdrMergeSadHandles[i] = GLES20.glGetUniformLocation(hdrMergeProgram, "uSad[$i]")
            }
        }
    }

    /**
     * 初始化金字塔下采样 shader + 1×1 零运动矢量占位纹理。
     */
    private fun initDownsampleProgram() {
        val vertexSource = """
            precision mediump float;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragSource = try {
            assetManager.open("shaders/fragment/downsample_gaussian.glsl")
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "downsample_gaussian.glsl 加载失败", e); return
        }
        downsampleProgram = ShaderHelper.buildProgram(vertexSource, fragSource)
        if (downsampleProgram == 0) {
            Log.e(TAG, "下采样 shader 编译失败")
        } else {
            downsamplePositionHandle = GLES20.glGetAttribLocation(downsampleProgram, "aPosition")
            downsampleTexCoordHandle = GLES20.glGetAttribLocation(downsampleProgram, "aTexCoord")
            downsampleTexHandle = GLES20.glGetUniformLocation(downsampleProgram, "uTex")
            downsampleTexelSizeHandle = GLES20.glGetUniformLocation(downsampleProgram, "uTexelSize")
        }

        // 创建 1×1 零运动矢量纹理（L2 层 uCoarseScale=0，shader 不采样但需绑定合法纹理）
        zeroMotionTexId = createZeroMotionTexture()
    }

    /**
     * 创建 1×1 RGBA 纹理：motion=(0.5, 0.5) 归一化零偏移, SAD=0。
     */
    private fun createZeroMotionTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        val pixels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        pixels.put(0, 0x80.toByte())  // R = 0.5（零运动 x）
        pixels.put(1, 0x80.toByte())  // G = 0.5（零运动 y）
        pixels.put(2, 0x00)           // B = 0  （零 SAD）
        pixels.put(3, 0xFF.toByte())  // A = 1
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }

    /**
     * 高斯下采样：源 Y 纹理 → 1/2 分辨率 Y 纹理。
     * 用于构建金字塔 L0→L1→L2。
     */
    private fun renderDownsample(
        srcTex: Int, dstFbo: Int,
        dstW: Int, dstH: Int,
        srcW: Int, srcH: Int
    ) {
        if (downsampleProgram == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, dstW, dstH)
        GLES20.glUseProgram(downsampleProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(downsampleTexHandle, 0)
        GLES20.glUniform2f(downsampleTexelSizeHandle, 1f / srcW, 1f / srcH)

        drawSimpleQuad(downsamplePositionHandle, downsampleTexCoordHandle)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 设置纹理为 NEAREST 过滤（运动矢量场需块级常量，避免线性插值产生边界伪影）。
     */
    private fun setTextureNearest(texId: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * 块匹配：计算参考帧与目标帧的逐块运动矢量（支持金字塔分层）。
     *
     * 输出到 motionFbo（尺寸 = blocksX × blocksY，每纹素一个块的运动矢量）。
     * 运动矢量存储为归一化 (dx, dy) ∈ [0, 1]。
     *
     * @param searchCenterTex 上层运动矢量场（归一化），L2 传 zeroMotionTexId
     * @param coarseScale     上层→本级缩放（L2=0, L1=2, L0=2）
     */
    private fun renderBlockMatch(
        refYTex: Int, altYTex: Int,
        motionFbo: Int,
        blocksX: Int, blocksY: Int,
        frameW: Int, frameH: Int,
        searchCenterTex: Int = 0,
        coarseScale: Float = 0f
    ) {
        if (alignProgram == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionFbo)
        GLES20.glViewport(0, 0, blocksX, blocksY)
        GLES20.glUseProgram(alignProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, refYTex)
        GLES20.glUniform1i(alignRefYHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, altYTex)
        GLES20.glUniform1i(alignAltYHandle, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, searchCenterTex)
        GLES20.glUniform1i(alignSearchCenterHandle, 2)

        GLES20.glUniform2f(alignTexelSizeHandle, 1f / frameW, 1f / frameH)
        GLES20.glUniform1f(alignCoarseScaleHandle, coarseScale)

        drawSimpleQuad(alignPositionHandle, alignTexCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 帧变形：根据运动矢量场对目标帧进行平移对齐。
     */
    private fun renderWarp(
        rgbaTex: Int, motionTex: Int,
        outFbo: Int, frameW: Int, frameH: Int
    ) {
        if (warpProgram == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
        GLES20.glViewport(0, 0, frameW, frameH)
        GLES20.glUseProgram(warpProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgbaTex)
        GLES20.glUniform1i(warpFrameHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionTex)
        GLES20.glUniform1i(warpMotionHandle, 1)

        GLES20.glUniform2f(warpTexelSizeHandle, 1f / frameW, 1f / frameH)

        drawSimpleQuad(warpPositionHandle, warpTexCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * HDR 曝光融合：N 帧 RGBA → 亮度加权合并。
     */
    private fun renderHdrMerge(
        rgbaTextures: List<Int>,
        sadTextures: List<Int>,
        targetFbo: Int, w: Int, h: Int
    ) {
        if (hdrMergeProgram == 0) return
        val n = minOf(rgbaTextures.size, MAX_MERGE_FRAMES)
        if (n == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(hdrMergeProgram)

        for (i in 0 until MAX_MERGE_FRAMES) {
            val texId = if (i < n) rgbaTextures[i] else rgbaTextures[0]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(hdrMergeTexHandles[i], i)
        }

        // 绑定 SAD 残差纹理到 unit 8-14
        val sadCount = minOf(sadTextures.size, MAX_MERGE_FRAMES - 1)
        for (i in 0 until MAX_MERGE_FRAMES - 1) {
            val texId = if (i < sadCount) sadTextures[i] else 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE8 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(hdrMergeSadHandles[i], MAX_MERGE_FRAMES + i)
        }

        // uFrameCount：实际帧数（动态循环上界）
        GLES20.glUniform1i(hdrMergeFrameCountHandle, n)

        // 等权：每帧 1/N，不足 8 帧时多余权重归零
        val invN = 1f / n
        val weights = FloatArray(MAX_MERGE_FRAMES)
        for (i in 0 until n) { weights[i] = invN }
        GLES20.glUniform1fv(hdrMergeWeightsHandle, MAX_MERGE_FRAMES, weights, 0)

        drawSimpleQuad(hdrMergePositionHandle, hdrMergeTexCoordHandle)

        for (i in 0 until MAX_MERGE_FRAMES) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        for (i in 0 until MAX_MERGE_FRAMES - 1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE8 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 对齐多帧拍照：块匹配对齐 → 变形 → 融合（时域降噪或 HDR）→ Bitmap。
     *
     * 流程：
     *   1. 上传 N 帧 YUV → 转换为 N 个 RGBA FBO（工作分辨率，D3 降级）
     *   2. 构建 Y 金字塔（L2=1/4, L1=1/2, L0=工作分辨率）
     *   3. 金字塔分层对齐：L2→L1→L0 逐级块匹配精修 → 变形对齐
     *   4. 合并 N 个对齐后的 RGBA 纹理：
     *      - hdrMode=false：等权平均（时域降噪，同曝光）
     *      - hdrMode=true：曝光加权融合（HDR，不同曝光）
     *   5. glReadPixels 回读 → Bitmap
     *   6. 释放所有临时资源
     *
     * D3 低分辨率对齐：当捕获分辨率超过 [ALIGN_MAX_WORK_DIMENSION] 时，
     * 整个对齐+变形+融合管线降级到工作分辨率（保留宽高比），显著降低
     * 同时驻留的 N 个 RGBA + N-1 个变形 FBO 的显存峰值。YUV 输入纹理
     * 仍为原始分辨率（单通道，开销小），仅 RGBA/金字塔/变形/融合 FBO 缩小。
     *
     * @param frames YUV 帧列表（2-4 帧）
     * @param hdrMode true=HDR 曝光融合，false=时域降噪
     * @param callback 拍照完成回调（GL 线程）
     * @param onFailure 无法出片回调（帧数不足 / shader 未就绪 / FBO 创建失败），GL 线程
     */
    fun captureAlignedPhoto(
        frames: List<PreProcessor.YuvFrame>,
        hdrMode: Boolean,
        callback: (Bitmap) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val modeName = if (hdrMode) "HDR" else "时域降噪"

        fun fail(reason: String) {
            Log.e(TAG, "$modeName 拍照失败：$reason")
            onFailure(reason)
        }

        val n = minOf(frames.size, MAX_MERGE_FRAMES)
        if (n < 2) {
            fail("至少需要 2 帧，收到 ${frames.size}")
            return
        }
        if (alignProgram == 0 || warpProgram == 0 || downsampleProgram == 0) {
            fail("对齐/下采样 shader 未初始化")
            return
        }
        if (hdrMode && hdrMergeProgram == 0) {
            fail("HDR 融合 shader 未初始化")
            return
        }

        val firstFrame = frames[0]
        val capW = minOf(firstFrame.width, maxTextureSize)
        val capH = minOf(firstFrame.height, maxTextureSize)
        if (capW <= 0 || capH <= 0) {
            fail("帧尺寸非法（${capW}x${capH}）")
            return
        }

        // ── D3：计算对齐工作分辨率（保留宽高比，限制最大边）────────
        // 高分辨率（如 12MP）下多帧 RGBA + 变形 FBO 同时驻留会撑爆显存。
        // workScale ≤ 1，整个对齐+变形+融合管线在 workW×workH 内执行。
        val workScale = minOf(1f, ALIGN_MAX_WORK_DIMENSION.toFloat() / maxOf(capW, capH))
        val workW = maxOf(1, (capW * workScale).toInt())
        val workH = maxOf(1, (capH * workScale).toInt())
        val needDownsampleY = workW != capW || workH != capH

        if (needDownsampleY) {
            Log.d(TAG, "开始对齐拍照（$modeName）：$n 帧, ${capW}x${capH} → 工作分辨率 ${workW}x${workH}（D3 降级）")
        } else {
            Log.d(TAG, "开始对齐拍照（$modeName）：$n 帧, ${capW}x${capH}")
        }

        // ── 1. 上传 YUV（原始分辨率）→ 转换 RGBA（工作分辨率）──────
        // YUV 纹理保持原始尺寸（单通道，开销小）；RGBA FBO 渲染到 workW×workH，
        // YUV→RGB 采样时由纹理过滤自然完成降采样。
        val yuvTextures = uploadYuvFrames(frames.subList(0, n))
        val rgbaFbos = ArrayList<Int>()
        val rgbaTextures = ArrayList<Int>()
        for (i in 0 until n) {
            val (fbo, tex) = TextureHelper.createFrameBuffer(workW, workH)
            if (fbo == 0) {
                releaseYuvFrameSet(yuvTextures)
                rgbaFbos.forEachIndexed { j, f -> TextureHelper.deleteFrameBuffer(f, rgbaTextures[j]) }
                fail("RGBA FBO 创建失败（第 $i 帧，${workW}x${workH}）")
                return
            }
            rgbaFbos.add(fbo)
            rgbaTextures.add(tex)
            val yuv = yuvTextures[i]
            renderYuvToRgb(yuv[0], yuv[1], yuv[2], fbo, workW, workH)
        }

        // ── 1.5 L0 Y：高分辨率时下采样到工作分辨率 ────────────────
        // 块匹配需要单通道 Y 纹理。未降级时直接复用原始 Y；降级时高斯下采样。
        val l0YTexs = ArrayList<Int>()
        val l0YExtraFbos = ArrayList<Int>()  // 仅 needDownsampleY 时非 0，需释放
        for (i in 0 until n) {
            if (needDownsampleY) {
                val (yFbo, yTex) = TextureHelper.createFrameBuffer(workW, workH)
                renderDownsample(yuvTextures[i][0], yFbo, workW, workH, capW, capH)
                l0YExtraFbos.add(yFbo)
                l0YTexs.add(yTex)
            } else {
                l0YExtraFbos.add(0)
                l0YTexs.add(yuvTextures[i][0])
            }
        }

        // ── 2. 构建 Y 金字塔（L1=1/2, L2=1/4，基于工作分辨率）─────
        val l1W = (workW + 1) / 2
        val l1H = (workH + 1) / 2
        val l2W = (l1W + 1) / 2
        val l2H = (l1H + 1) / 2
        val l0BlocksX = (workW + ALIGN_BLOCK_SIZE - 1) / ALIGN_BLOCK_SIZE
        val l0BlocksY = (workH + ALIGN_BLOCK_SIZE - 1) / ALIGN_BLOCK_SIZE
        val l1BlocksX = (l1W + ALIGN_BLOCK_SIZE - 1) / ALIGN_BLOCK_SIZE
        val l1BlocksY = (l1H + ALIGN_BLOCK_SIZE - 1) / ALIGN_BLOCK_SIZE
        val l2BlocksX = (l2W + ALIGN_BLOCK_SIZE - 1) / ALIGN_BLOCK_SIZE
        val l2BlocksY = (l2H + ALIGN_BLOCK_SIZE - 1) / ALIGN_BLOCK_SIZE

        val l1YFbos = ArrayList<Int>()
        val l1YTexs = ArrayList<Int>()
        val l2YFbos = ArrayList<Int>()
        val l2YTexs = ArrayList<Int>()
        for (i in 0 until n) {
            // L0 Y → L1 Y
            val (l1Fbo, l1Tex) = TextureHelper.createFrameBuffer(l1W, l1H)
            renderDownsample(l0YTexs[i], l1Fbo, l1W, l1H, workW, workH)
            l1YFbos.add(l1Fbo)
            l1YTexs.add(l1Tex)
            // L1 Y → L2 Y
            val (l2Fbo, l2Tex) = TextureHelper.createFrameBuffer(l2W, l2H)
            renderDownsample(l1Tex, l2Fbo, l2W, l2H, l1W, l1H)
            l2YFbos.add(l2Fbo)
            l2YTexs.add(l2Tex)
        }

        // ── 3. 金字塔分层对齐：L2→L1→L0 逐级精修 ─────────────────
        // 每个目标帧创建独立的 L0 motion FBO（融合阶段需同时访问所有 SAD 纹理）
        val motionFbos = ArrayList<Int>()
        val motionTextures = ArrayList<Int>()
        val warpedTextures = ArrayList<Int>()
        val warpedFbos = ArrayList<Int>()
        // 参考帧(0)不需要变形；对帧 1..n-1 做对齐
        for (i in 1 until n) {
            // L2: 粗搜索（coarseScale=0，无上层运动矢量）
            val (l2MotFbo, l2MotTex) = TextureHelper.createFrameBuffer(l2BlocksX, l2BlocksY)
            setTextureNearest(l2MotTex)
            renderBlockMatch(l2YTexs[0], l2YTexs[i], l2MotFbo,
                l2BlocksX, l2BlocksY, l2W, l2H, zeroMotionTexId, 0f)

            // L1: 以 L2 运动矢量为中心精搜索
            val (l1MotFbo, l1MotTex) = TextureHelper.createFrameBuffer(l1BlocksX, l1BlocksY)
            setTextureNearest(l1MotTex)
            renderBlockMatch(l1YTexs[0], l1YTexs[i], l1MotFbo,
                l1BlocksX, l1BlocksY, l1W, l1H, l2MotTex, 2f)

            // L0: 以 L1 运动矢量为中心精搜索（最终运动矢量 + SAD 残差）
            val (l0MotFbo, l0MotTex) = TextureHelper.createFrameBuffer(l0BlocksX, l0BlocksY)
            setTextureNearest(l0MotTex)
            renderBlockMatch(l0YTexs[0], l0YTexs[i], l0MotFbo,
                l0BlocksX, l0BlocksY, workW, workH, l1MotTex, 2f)

            // 释放中间层运动矢量（L0 保留供变形 + 融合 SAD 加权）
            TextureHelper.deleteFrameBuffer(l2MotFbo, l2MotTex)
            TextureHelper.deleteFrameBuffer(l1MotFbo, l1MotTex)

            motionFbos.add(l0MotFbo)
            motionTextures.add(l0MotTex)

            // 变形 alt RGBA → 对齐 RGBA（使用 L0 最终运动矢量）
            val (warpFbo, warpTex) = TextureHelper.createFrameBuffer(workW, workH)
            warpedFbos.add(warpFbo)
            warpedTextures.add(warpTex)
            renderWarp(rgbaTextures[i], l0MotTex, warpFbo, workW, workH)
        }

        // 释放金字塔 Y 纹理 + L0 下采样 Y（对齐已完成）
        for (i in l1YFbos.indices) {
            TextureHelper.deleteFrameBuffer(l1YFbos[i], l1YTexs[i])
            TextureHelper.deleteFrameBuffer(l2YFbos[i], l2YTexs[i])
            if (l0YExtraFbos[i] != 0) {
                TextureHelper.deleteFrameBuffer(l0YExtraFbos[i], l0YTexs[i])
            }
        }

        // ── 4. 合并：ref + N-1 个变形帧 ──────────────────────────
        val allTextures = arrayListOf(rgbaTextures[0])
        allTextures.addAll(warpedTextures)

        if (captureWidth != workW || captureHeight != workH || captureFboId == 0) {
            destroyCaptureFBO()
            val (fbo, tex) = TextureHelper.createFrameBuffer(workW, workH)
            captureFboId = fbo
            captureTexId = tex
            captureWidth = workW
            captureHeight = workH
        }

        // ── 4. 合并 → 临时合并 FBO（避免滤镜输入输出同 FBO）──────
        val (mergedFbo, mergedTex) = TextureHelper.createFrameBuffer(workW, workH)
        if (hdrMode) {
            renderHdrMerge(allTextures, motionTextures, mergedFbo, workW, workH)
        } else {
            renderMerge(allTextures, motionTextures, mergedFbo, workW, workH)
        }

        // ── 5. 滤镜链处理：合并纹理 → 拍照 FBO ──────────────────
        filterChain.apply(mergedTex, captureFboId, workW, workH)

        // ── 6. 读取像素 → Bitmap ────────────────────────────────
        val bitmap = readbackToBitmap(workW, workH)

        // ── 7. 释放临时资源 ──────────────────────────────────────
        releaseYuvFrameSet(yuvTextures)
        for (i in rgbaFbos.indices) {
            TextureHelper.deleteFrameBuffer(rgbaFbos[i], rgbaTextures[i])
        }
        for (i in warpedFbos.indices) {
            TextureHelper.deleteFrameBuffer(warpedFbos[i], warpedTextures[i])
        }
        for (i in motionFbos.indices) {
            TextureHelper.deleteFrameBuffer(motionFbos[i], motionTextures[i])
        }
        TextureHelper.deleteFrameBuffer(mergedFbo, mergedTex)

        Log.d(TAG, "对齐拍照完成（$modeName）: ${workW}x${workH}, $n 帧")
        callback(bitmap)
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data).position(0)
        return buffer
    }


}
