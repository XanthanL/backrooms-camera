package com.photoria.backrooms.encoder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.photoria.backrooms.util.ShaderHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * GL 视频录制器（核心整合类）。
 *
 * 将 GL 滤镜渲染帧编码为 H.264 MP4 视频，并同步录制 AAC 音频（E1）。
 *
 * 工作原理：
 *   1. 在 GL 线程中，用共享 EGL 上下文为 MediaCodec 的 InputSurface 创建 EGL 窗口 Surface
 *   2. 每帧由 GLRenderer 完成一次滤镜渲染（输出到 finalFilterFbo，单 pass，E2 优化）
 *   3. 录制时把已滤镜纹理直接 blit 到编码器 Surface（不再重新执行滤镜）
 *   4. swapBuffers 提交帧给 MediaCodec
 *   5. MediaMuxer 将视频（H.264）+ 音频（AAC）混合为 MP4 文件
 *
 * muxer 协调：
 *   - 视频与音频轨道都通过共享 muxerLock 同步写入
 *   - muxer.start() 必须在所有预期轨道 addTrack 后调用
 *   - muxer 未启动期间到达的采样缓冲到 pending 队列，启动后 flush
 *
 * 所有方法必须在 GL 线程调用（除 startRecording/stopRecording 可在主线程调用）。
 */
class GLVideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "GLVideoRecorder"
        private const val GALLERY_DIR = "Photoria"
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 6_000_000
        /** muxer 启动前采样缓冲上限（≈7s），超限丢弃防 OOM */
        private const val MAX_PENDING_SAMPLES = 300
        /** [release] 等待 GL 线程阶段1 收尾的时限 */
        private const val RELEASE_TEARDOWN_WAIT_MS = 8_000L
    }

    /** 是否正在录制 */
    @Volatile
    var isRecording = false
        private set

    /**
     * 保护"帧内使用编码器 Surface"与"释放编码器"互斥。
     *
     * [onFrameDrawn] 全程持锁；任何 release [videoEncoder]（连带 release 其
     * InputSurface）的地方必须同锁，否则 GL 线程正在 eglSwapBuffers 时
     * ANativeWindow 被另一线程释放 → native 崩溃（无 Java 栈）。
     *
     * 锁顺序固定为 frameLock → muxerLock，切勿反向嵌套。
     */
    private val frameLock = ReentrantLock()

    /** 阶段2 停止线程（muxer/编码器释放 + 相册迁移），供 [release] 收敛等待 */
    @Volatile
    private var stopThread: Thread? = null

    /**
     * 串行化"阶段1 GL 线程收尾"与 [release]。
     *
     * 阶段1 里的 drainEncoder(true) 最长可阻塞数秒，期间主线程若因
     * Composable onDispose 调用 [release]，两个线程会同时持有同一个
     * MediaCodec（跨线程并发 = native abort）。[release] 用 tryLock 限时等待。
     */
    private val teardownLock = ReentrantLock()

    /** GL 资源是否已收尾（幂等标记，避免重复销毁 EGL surface/context） */
    @Volatile
    private var glResourcesCleaned = false

    /** 当前录制尺寸（跟随画幅） */
    private var videoWidth = 1280
    private var videoHeight = 720

    // ── 编码器组件 ──────────────────────────────────────────────────
    private var eglCore: EglCore? = null
    private var encoderEglSurface: android.opengl.EGLSurface? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxer: MediaMuxer? = null
    private val muxerLock = ReentrantLock()
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // ── muxer 启动前的采样缓冲（视频格式确定通常早于音频）──────────
    private data class PendingSample(val data: ByteArray, val ptsUs: Long, val flags: Int)
    private val pendingVideoSamples = mutableListOf<PendingSample>()
    private val pendingAudioSamples = mutableListOf<PendingSample>()

    // ── 帧时间戳 ──────────────────────────────────────────────────
    private var recordingStartTimeNs = 0L
    private var lastFrameTimeNs = 0L

    // ── 文件路径 ──────────────────────────────────────────────────
    private var tempFilePath: String? = null

    // ── 全屏四边形 ────────────────────────────────────────────────
    private val quadVertices: FloatBuffer = createFloatBuffer(floatArrayOf(
        -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    ))
    private val quadTexCoords: FloatBuffer = createFloatBuffer(floatArrayOf(
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    ))

    // ── 直绘 shader（将已滤镜的 2D 纹理 blit 到编码器 Surface）─────
    private var directProgram = 0
    private var directPositionHandle = 0
    private var directTexCoordHandle = 0
    private var directTextureHandle = 0

    // ── 是否尝试录制音频（音频不可用时仅本次降级为纯视频，本标记不被改写）──
    var audioEnabled = true

    // ── 回调 ──────────────────────────────────────────────────────
    var onVideoSaved: ((String) -> Unit)? = null
    var onRecordingError: ((Throwable) -> Unit)? = null

    /**
     * 开始录制。
     * 在 GL 线程中初始化编码器并创建 EGL 窗口 Surface。
     *
     * @param width  视频宽度（跟随画幅，需为偶数）
     * @param height 视频高度（跟随画幅，需为偶数）
     */
    fun startRecording(width: Int = 1280, height: Int = 720) {
        if (isRecording) {
            Log.w(TAG, "已在录制中")
            return
        }

        videoWidth = width
        videoHeight = height

        try {
            // 创建临时文件路径
            val fileName = "PHOTORIA_${System.currentTimeMillis()}.mp4"
            val tempFile = File(context.cacheDir, fileName)
            tempFilePath = tempFile.absolutePath

            // 重置 muxer 状态
            muxerLock.withLock {
                muxerStarted = false
                videoTrackIndex = -1
                audioTrackIndex = -1
                pendingVideoSamples.clear()
                pendingAudioSamples.clear()
            }

            // 初始化视频编码器
            videoEncoder = VideoEncoder().apply {
                init(width, height, FRAME_RATE, BIT_RATE)
                onSampleData = { buffer, info -> handleVideoSample(buffer, info) }
            }

            // 创建 MediaMuxer
            muxer = MediaMuxer(tempFilePath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 获取当前 GL 线程的 EGL 上下文（用于共享）
            val currentContext = EGL14.eglGetCurrentContext()
            if (currentContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("无法获取当前 EGL 上下文，必须在 GL 线程调用")
            }

            // 初始化 EglCore（共享上下文）
            eglCore = EglCore().apply {
                init(currentContext)
            }

            // 为编码器 InputSurface 创建 EGL 窗口 Surface
            val encoderInputSurface = videoEncoder!!.getInputSurface()
            encoderEglSurface = eglCore!!.createWindowSurface(encoderInputSurface)
            glResourcesCleaned = false

            // 初始化直绘 shader。
            // 必须在编码器 context 下创建：program 对象在 EGL context 之间**不共享**，
            // 在外层（GLSurfaceView）context 创建、却在编码器 context 下 glUseProgram
            // 属未定义行为（部分驱动表现为录像黑屏）。创建完立刻还原外层状态，
            // 否则 startRecording 返回后的下一帧会画到编码器 Surface 上。
            val outerState = EglState()
            try {
                eglCore!!.makeCurrent(encoderEglSurface!!)
                initDirectShader()
            } finally {
                if (!outerState.restore()) {
                    Log.e(TAG, "startRecording 后还原 EGL 状态失败, " +
                            "error=0x${Integer.toHexString(EGL14.eglGetError())}")
                }
            }

            // 重置时间戳（视频与音频共用同一基准，避免音画偏移）
            recordingStartTimeNs = System.nanoTime()
            lastFrameTimeNs = 0L

            // 初始化音频编码器（如果启用）
            if (audioEnabled) {
                try {
                    audioEncoder = AudioEncoder().apply {
                        init(muxer!!, muxerLock)
                        onSampleData = { buffer, info -> handleAudioSample(buffer, info) }
                        start(recordingStartTimeNs)
                    }
                } catch (e: Exception) {
                    // 本次降级为纯视频；不粘住 audioEnabled：
                    // 麦克风权限与占用是可恢复状态，下次录像仍重新尝试
                    Log.e(TAG, "音频初始化失败，本次仅录制视频", e)
                    audioEncoder?.release()
                    audioEncoder = null
                }
            }

            isRecording = true
            Log.d(TAG, "录制已开始: ${width}x${height}, audio=${audioEncoder != null}")

        } catch (e: Exception) {
            Log.e(TAG, "启动录制失败", e)
            // startRecording 在 GL 线程，可安全清理 GL 资源
            cleanupGlResources()
            cleanupNonGlResources()
            onRecordingError?.invoke(e)
        }
    }

    /**
     * 每帧渲染后调用，将已滤镜的纹理 blit 到编码器 Surface（E2 单 pass）。
     *
     * 注意：传入的 filteredTextureId 必须是 GLRenderer 已完成滤镜处理的纹理
     * （finalFilterFbo 的颜色附件），此处不再重新执行滤镜，仅做 1:1 blit，
     * 避免双重渲染导致 GPU 负载翻倍。
     *
     * @param filteredTextureId 已滤镜处理的 2D 纹理 ID
     * @param width             纹理宽度（未使用，视口由 videoWidth/videoHeight 决定）
     * @param height            纹理高度（未使用）
     */
    fun onFrameDrawn(filteredTextureId: Int, @Suppress("UNUSED_PARAMETER") width: Int, @Suppress("UNUSED_PARAMETER") height: Int) {
        if (!isRecording) return
        // 全程持锁：帧内会使用编码器 InputSurface，释放编码器必须等本帧画完
        frameLock.withLock {
            if (!isRecording) return
            val egl = eglCore ?: return
            val encSurface = encoderEglSurface ?: return

            // ── 保存当前 EGL 完整状态（display + draw/read surface + context）────
            // 必须保存 context：GLSurfaceView 的 EGLSurface 只能在它自己的 EGLContext
            // 下使用。若仅切换 surface 而用 eglCore 的共享 context 绑定 GLSurfaceView 的
            // surface，GLSurfaceView 在 onDrawFrame 返回后的 eglSwapBuffers 会失败，
            // 导致屏幕画面永远不再更新（卡死在第一帧）。
            val saved = EglState()

            try {
                // 切换到编码器 EGL Surface（使用 eglCore 的共享 context）
                egl.makeCurrent(encSurface)

                // 设置视口为编码器分辨率（跟随画幅）
                GLES20.glViewport(0, 0, videoWidth, videoHeight)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // 将已滤镜纹理 blit 到编码器 Surface（单次绘制，无滤镜计算）
                renderTextureToEncoder(filteredTextureId)

                // 计算并设置帧时间戳（必须单调递增）
                val currentTimeNs = System.nanoTime() - recordingStartTimeNs
                val frameTimeNs = if (currentTimeNs <= lastFrameTimeNs) {
                    lastFrameTimeNs + 33_333_333L  // ~30fps 最小间隔
                } else {
                    currentTimeNs
                }
                lastFrameTimeNs = frameTimeNs
                egl.setPresentationTime(encSurface, frameTimeNs)

                // 提交帧给编码器
                egl.swapBuffers(encSurface)

                // 排空视频编码器（非阻塞，经回调写入 muxer）
                videoEncoder?.drainEncoder(endOfStream = false)

            } catch (e: Exception) {
                Log.e(TAG, "帧录制异常", e)
                onRecordingError?.invoke(e)
            } finally {
                // ── 完整恢复原 EGL 状态（GLSurfaceView 的 context + surface）──────
                // 这样 GLSurfaceView 在 onDrawFrame 返回后 eglSwapBuffers 才能正确
                // 交换屏幕缓冲区，保证预览持续刷新。
                if (!saved.restore()) {
                    Log.e(TAG, "恢复 EGL 状态失败, " +
                            "error=0x${Integer.toHexString(EGL14.eglGetError())}")
                }
            }
        }
    }

    /**
     * 停止录制。
     *
     * 分两阶段，且**阶段1 结束后 GL 线程必须仍处于 GLSurfaceView 的 EGL 状态**：
     *
     * 阶段1（GL 线程同步）：
     *   1. 音频 stop（置停止标志 + 解除 read 阻塞 + 有界等待采集线程退出）
     *   2. 视频发送 EOS 并排空（保证视频尾部完整）
     *   3. 销毁 GL 资源：删 program → 解绑 → 销毁编码器 EGLSurface → 销毁共享
     *      context → **还原 GLSurfaceView 的 context + window Surface**
     *   4. videoEncoder.stop()（必须在第 3 步之后：EGL window surface 已不再引用
     *      编码器 InputSurface，此时停 codec 才不会出现"绑定中的 window surface
     *      消费者消失"的驱动侧异常）
     *   5. 移交所有权：把本次录制的 encoder/muxer/临时路径捕获为局部引用，字段置空
     *
     * 阶段2（后台 RecorderStop 线程，不阻塞 GL，只操作移交出来的局部引用）：
     *   - muxer stop/release
     *   - videoEncoder.release()（frameLock 内，与在途帧互斥）
     *   - audioEncoder.release()
     *   - moveToGallery（磁盘 IO，耗时大头）
     *
     * 为何要移交而非让阶段2 直接读字段：阶段2 期间用户可能已开始新一轮录制，
     * 那时字段指向的是新录制的实例，按字段释放会停掉新录制的 codec 与 muxer，
     * 同样表现为 native 崩溃。
     *
     * 历史：原阶段1 在销毁编码器 surface 后把线程留在 EGL_NO_CONTEXT 且继续调用
     * glDeleteProgram，GLSurfaceView 又不会每帧重新 makeCurrent → 点停止瞬间
     * 后续帧全部在无 context 状态下调用 GLES/eglSwapBuffers（native 崩溃，
     * Java try-catch 抓不到）。现已在 cleanupGlResources 末尾强制还原。
     */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        Log.d(TAG, "停止录制...")

        // 本次录制实例的移交目标（阶段1 结束时捕获，阶段2 只用这些局部引用）
        var stoppingVideoEncoder: VideoEncoder? = null
        var stoppingAudioEncoder: AudioEncoder? = null
        var stoppingMuxer: MediaMuxer? = null
        var stoppingMuxerStarted = false
        var tempPath: String? = null

        // ── 阶段1：GL 线程同步部分（与 release() 互斥）──
        teardownLock.withLock {
            try {
                // 1. 停止音频（有界等待采集线程真正退出）
                audioEncoder?.stop()

                // 2. 视频发送 EOS 并排空（必须，保证视频尾部完整）
                videoEncoder?.drainEncoder(endOfStream = true)

                // 3. 销毁 GL 资源并在最后还原 GLSurfaceView 的 EGL 状态
                cleanupGlResources()

                // 4. 停止视频编码器（GL 资源已卸载，此调用不再触碰 Surface）
                videoEncoder?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "停止录制阶段1异常", e)
            }

            // 5. 移交所有权：此刻本次录制的采样来源已全部停止，不会再有回调
            //    触碰这些实例。字段随即置空，用户紧接着开始的新录制拿到的是
            //    全新字段，阶段2 绝不会去释放新录制的编码器与 muxer。
            stoppingVideoEncoder = videoEncoder
            stoppingAudioEncoder = audioEncoder
            videoEncoder = null
            audioEncoder = null
            muxerLock.withLock {
                stoppingMuxer = muxer
                stoppingMuxerStarted = muxerStarted
                muxer = null
                muxerStarted = false
                pendingVideoSamples.clear()
                pendingAudioSamples.clear()
            }
            tempPath = tempFilePath
        }

        // ── 阶段2：后台 IO 线程（慢操作，不阻塞 GL 线程）──
        stopThread = Thread({
            try {
                // muxer 停止 + 释放
                if (stoppingMuxerStarted) {
                    try { stoppingMuxer?.stop() } catch (e: Exception) {
                        Log.w(TAG, "Muxer stop 异常: ${e.message}")
                    }
                }
                try { stoppingMuxer?.release() } catch (_: Exception) {}

                // 编码器释放（MediaCodec release 在某些机型较慢）。
                // frameLock 内：确保没有帧正在使用编码器 InputSurface
                frameLock.withLock {
                    stoppingVideoEncoder?.release()
                }
                // 音频释放不持 frameLock（内部会 join 采集线程，持锁会阻塞渲染）
                stoppingAudioEncoder?.release()

                // 文件搬迁到相册（磁盘 IO，最耗时）
                tempPath?.let { moveToGallery(it) }
            } catch (e: Exception) {
                Log.e(TAG, "停止录制阶段2异常", e)
                onRecordingError?.invoke(e)
            } finally {
                tempPath?.let { p ->
                    runCatching { File(p).delete() }
                    if (tempFilePath == p) tempFilePath = null
                }
                if (stopThread === Thread.currentThread()) {
                    stopThread = null
                }
                Log.d(TAG, "录制停止完成")
            }
        }, "RecorderStop").apply { isDaemon = true }
        stopThread?.start()
    }

    /**
     * 释放所有资源（不发送 EOS）。
     *
     * 供 Composable onDispose 调用，可能在主线程 —— 因此**不得同步等待**任何
     * 收尾（等 GL 线程 drainEncoder 最长数秒会直接 ANR）。做法：主线程只置停止
     * 标志，其余交给守护线程串行完成：
     *   1. 等 GL 线程的阶段1 收尾结束（正在 drainEncoder 时绝不并发碰同一个 MediaCodec）
     *   2. 等阶段2 线程结束，避免重复释放编码器与 muxer
     *   3. 清理非 GL 资源
     *
     * EGL surface/context 与 program 只能在 GL 线程销毁，本方法不碰：
     * 未正常停止时它们随 EGL context 销毁一并回收。
     */
    fun release() {
        isRecording = false

        // 快速路径：从未录制或已彻底清理，无需等待也无需释放
        if (videoEncoder == null && audioEncoder == null && muxer == null && eglCore == null) return

        Thread({
            val phase1Finished = try {
                teardownLock.tryLock(RELEASE_TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!phase1Finished) {
                // 阶段1 仍在 GL 线程执行：此时清理编码器就是跨线程并发使用 MediaCodec。
                // 宁可泄漏一次编码器（视图销毁后随进程回收），也不能崩溃。
                Log.e(TAG, "等待停止录制阶段1超时，跳过释放以避免并发操作编码器")
                return@Thread
            }
            try {
                if (!glResourcesCleaned) {
                    Log.w(TAG, "录制未正常停止：GL 资源留待 EGL context 销毁时回收")
                }
            } finally {
                teardownLock.unlock()
            }

            try {
                stopThread?.join(3000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            stopThread = null
            cleanupNonGlResources()
        }, "RecorderRelease").apply { isDaemon = true }.start()
    }

    // ──────────────────────────────────────────────────────────────
    // muxer 协调（视频/音频线程共享锁）
    // ──────────────────────────────────────────────────────────────

    /**
     * 处理视频编码数据（VideoEncoder.onSampleData 回调）。
     * 在 muxerLock 保护下写入；muxer 未启动则缓冲。
     */
    private fun handleVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxerLock.withLock {
            if (muxer == null) return@withLock
            tryStartMuxer()
            if (muxerStarted && videoTrackIndex >= 0) {
                muxer?.writeSampleData(videoTrackIndex, buffer, info)
            } else if (info.size > 0 && pendingVideoSamples.size < MAX_PENDING_SAMPLES) {
                buffer.pendingCopy(info)?.let { pendingVideoSamples.add(it) }
            }
        }
    }

    /**
     * 处理音频编码数据（AudioEncoder.onSampleData 回调）。
     */
    private fun handleAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxerLock.withLock {
            if (muxer == null) return@withLock
            tryStartMuxer()
            if (muxerStarted && audioTrackIndex >= 0) {
                muxer?.writeSampleData(audioTrackIndex, buffer, info)
            } else if (info.size > 0 && pendingAudioSamples.size < MAX_PENDING_SAMPLES) {
                buffer.pendingCopy(info)?.let { pendingAudioSamples.add(it) }
            }
        }
    }

    /** 从 buffer 复制一份数据用于缓冲（buffer position/limit 已设好）。 */
    private fun ByteBuffer.pendingCopy(info: MediaCodec.BufferInfo): PendingSample? {
        return try {
            val data = ByteArray(info.size)
            get(data)  // 从 position 读 info.size 字节
            PendingSample(data, info.presentationTimeUs, info.flags)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 尝试添加轨道并启动 muxer（必须在 muxerLock 内调用）。
     * 当所有预期轨道的输出格式都就绪时，addTrack → start → flush pending。
     */
    private fun tryStartMuxer() {
        if (muxerStarted || muxer == null) return

        val vEnc = videoEncoder
        if (vEnc != null && videoTrackIndex < 0 && vEnc.isOutputFormatReady) {
            videoTrackIndex = muxer!!.addTrack(vEnc.outputFormat!!)
            Log.d(TAG, "视频轨道已添加，trackIndex=$videoTrackIndex")
        }
        val aEnc = audioEncoder
        if (aEnc != null && audioTrackIndex < 0 && aEnc.isOutputFormatReady) {
            audioTrackIndex = muxer!!.addTrack(aEnc.outputFormat!!)
            Log.d(TAG, "音频轨道已添加，trackIndex=$audioTrackIndex")
        }

        val allVideoReady = (vEnc == null) || (videoTrackIndex >= 0)
        val allAudioReady = (aEnc == null) || (audioTrackIndex >= 0)

        if (allVideoReady && allAudioReady) {
            muxer!!.start()
            muxerStarted = true
            Log.d(TAG, "MediaMuxer 已启动 (video=$videoTrackIndex, audio=$audioTrackIndex)")
            flushPendingSamples()
        }
    }

    /**
     * 将 muxer 启动前缓冲的采样写入（必须在 muxerLock 内、muxerStarted 后调用）。
     */
    private fun flushPendingSamples() {
        val mx = muxer ?: return
        for (s in pendingVideoSamples) {
            if (videoTrackIndex >= 0) {
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.ptsUs, s.flags)
                mx.writeSampleData(videoTrackIndex, ByteBuffer.wrap(s.data), info)
            }
        }
        pendingVideoSamples.clear()
        for (s in pendingAudioSamples) {
            if (audioTrackIndex >= 0) {
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.ptsUs, s.flags)
                mx.writeSampleData(audioTrackIndex, ByteBuffer.wrap(s.data), info)
            }
        }
        pendingAudioSamples.clear()
    }

    // ──────────────────────────────────────────────────────────────
    // 内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 初始化直绘 shader（将 2D 纹理 blit 到编码器 Surface）。
     */
    private fun initDirectShader() {
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

        val fragmentSource = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        directProgram = ShaderHelper.buildProgram(vertexSource, fragmentSource)
        if (directProgram == 0) {
            Log.e(TAG, "直绘 shader 编译失败")
            return
        }

        directPositionHandle = GLES20.glGetAttribLocation(directProgram, "aPosition")
        directTexCoordHandle = GLES20.glGetAttribLocation(directProgram, "aTexCoord")
        directTextureHandle = GLES20.glGetUniformLocation(directProgram, "uTexture")
    }

    /**
     * 将 2D 纹理渲染到当前绑定的 Surface（编码器 Surface）。
     * E2：仅做 blit，不执行滤镜计算。
     */
    private fun renderTextureToEncoder(textureId: Int) {
        if (directProgram == 0) return

        GLES20.glUseProgram(directProgram)

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(directTextureHandle, 0)

        // 顶点
        GLES20.glEnableVertexAttribArray(directPositionHandle)
        GLES20.glVertexAttribPointer(directPositionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

        // 纹理坐标
        GLES20.glEnableVertexAttribArray(directTexCoordHandle)
        GLES20.glVertexAttribPointer(directTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        GLES20.glDisableVertexAttribArray(directPositionHandle)
        GLES20.glDisableVertexAttribArray(directTexCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
    }

    /**
     * 将临时文件移动到系统相册（DCIM/Photoria）。
     *
     * @param tempPath 本次录制的临时文件路径（在停止时捕获，
     *                 不读 [tempFilePath] 字段：新一轮录制可能已开始并改写它）
     */
    private fun moveToGallery(tempPath: String) {
        val tempFile = File(tempPath)
        if (!tempFile.exists() || tempFile.length() == 0L) {
            Log.w(TAG, "临时视频文件不存在或为空")
            tempFile.delete()
            // 必须通知上层：否则 isRecording 永不复位，快门卡在录制态
            onRecordingError?.invoke(RuntimeException("录像文件为空（muxer 未启动或无有效帧）"))
            return
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "PHOTORIA_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$GALLERY_DIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues
            )

            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    tempFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)

                Log.d(TAG, "视频已保存到相册: $uri")
                onVideoSaved?.invoke(uri.toString())
            } else {
                Log.e(TAG, "创建 MediaStore 条目失败")
                onRecordingError?.invoke(RuntimeException("保存视频失败"))
            }

            // 删除临时文件
            tempFile.delete()

        } catch (e: Exception) {
            Log.e(TAG, "保存视频到相册失败", e)
            onRecordingError?.invoke(e)
        }
    }

    /**
     * 清理 GL 相关资源（必须在 GL 线程调用）。
     *
     * 释放顺序：
     *   1. 快照调用线程当前 EGL 状态（即 GLSurfaceView 的 display/context/surface）
     *   2. 在编码器 context 下 glDeleteProgram（program 属于该 context，见 startRecording；
     *      且必须在 eglDestroyContext 之前删除，否则上下文转入延迟销毁、对象泄漏）
     *   3. 解绑 Surface 但**保留 context**，再 destroySurface（销毁仍绑定的 Surface
     *      在部分驱动上会报 GL 错误）
     *   4. eglCore.release()（销毁共享 context，不 terminate 与 GLSurfaceView 共用的 display）
     *   5. **还原第 1 步的快照** ← 关键：GLSurfaceView 只在 surface 创建/销毁时
     *      makeCurrent，不会每帧重绑。若在此之后线程处于 EGL_NO_CONTEXT，
     *      后续每一帧的 GLES 调用与 eglSwapBuffers 都是非法的
     *      （停止录像瞬间 native 崩溃 / 预览永久冻结）。
     */
    private fun cleanupGlResources() {
        val outerState = EglState()
        val egl = eglCore
        val encSurface = encoderEglSurface

        if (egl != null && encSurface != null) {
            runCatching { egl.makeCurrent(encSurface) }
                .onSuccess { deleteDirectProgram() }
                .onFailure { Log.e(TAG, "绑定编码器 EGLSurface 失败", it) }

            runCatching { egl.unbindSurfaceKeepingContext() }
                .onFailure { Log.e(TAG, "解绑编码器 EGLSurface 失败", it) }
            runCatching { egl.destroySurface(encSurface) }
                .onFailure { Log.e(TAG, "销毁编码器 EGLSurface 失败", it) }
        }
        encoderEglSurface = null

        runCatching { eglCore?.release() }
            .onFailure { Log.e(TAG, "释放 EglCore 失败", it) }
        eglCore = null

        if (!outerState.restore()) {
            Log.e(TAG, "还原 GLSurfaceView EGL 状态失败, " +
                    "error=0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        glResourcesCleaned = true
        Log.d(TAG, "GL 资源已清理")
    }

    /** 删除直绘 shader（必须在创建它的 context 仍 current 时调用）。 */
    private fun deleteDirectProgram() {
        if (directProgram != 0) {
            GLES20.glDeleteProgram(directProgram)
            directProgram = 0
        }
    }

    /**
     * 清理非 GL 资源（可在任意线程调用）。
     *
     * 释放编码器、muxer、临时文件。供 [release] 与启动失败回滚使用。
     * 注意：不销毁 EGL/GL 资源（那必须在 GL 线程做）。
     */
    private fun cleanupNonGlResources() {
        // 编码器 release 会连带释放其 InputSurface（ANativeWindow），
        // 与 GL 线程在途的 eglSwapBuffers 互斥
        frameLock.withLock {
            videoEncoder?.release()
            videoEncoder = null
        }
        audioEncoder?.release()
        audioEncoder = null

        muxerLock.withLock {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
                muxerStarted = false
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            pendingVideoSamples.clear()
            pendingAudioSamples.clear()
        }

        tempFilePath?.let { runCatching { File(it).delete() } }
        tempFilePath = null
        Log.d(TAG, "非 GL 资源已清理")
    }

    /**
     * 一次 EGL 绑定状态快照（display + draw/read surface + context）。
     *
     * 在 GL 线程构造即捕获当时的绑定状态，[restore] 把它绑回去。
     * 之所以必须连 context 一起保存：GLSurfaceView 的 window Surface 只能在
     * 它自己的 context 下使用，只换 surface 不换 context 会让它后续的
     * eglSwapBuffers 失败。
     */
    private class EglState {
        private val display = EGL14.eglGetCurrentDisplay()
        private val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        private val readSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        private val context = EGL14.eglGetCurrentContext()

        /** @return 是否成功恢复（无有效快照或 eglMakeCurrent 失败时为 false） */
        fun restore(): Boolean {
            if (display === EGL14.EGL_NO_DISPLAY || context === EGL14.EGL_NO_CONTEXT) return false
            return EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)
        }
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data).position(0)
        return buffer
    }
}
