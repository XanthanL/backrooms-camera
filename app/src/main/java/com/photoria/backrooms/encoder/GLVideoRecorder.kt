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
    }

    /** 是否正在录制 */
    @Volatile
    var isRecording = false
        private set

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
    private var outputPath: String? = null
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

    // ── 是否启用音频录制（无 RECORD_AUDIO 权限时设为 false）────────
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
            outputPath = tempFile.absolutePath

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

            // 初始化直绘 shader（blit 已滤镜纹理到编码器）
            initDirectShader()

            // 初始化音频编码器（如果启用）
            if (audioEnabled) {
                try {
                    audioEncoder = AudioEncoder().apply {
                        init(muxer!!, muxerLock)
                        onSampleData = { buffer, info -> handleAudioSample(buffer, info) }
                        start()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "音频初始化失败，降级为纯视频录制", e)
                    audioEncoder?.release()
                    audioEncoder = null
                    audioEnabled = false
                }
            }

            // 重置时间戳
            recordingStartTimeNs = System.nanoTime()
            lastFrameTimeNs = 0L

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

        val egl = eglCore ?: return
        val encSurface = encoderEglSurface ?: return

        // ── 保存当前 EGL 完整状态（display + draw/read surface + context）────
        // 必须保存 context：GLSurfaceView 的 EGLSurface 只能在它自己的 EGLContext
        // 下使用。若仅切换 surface 而用 eglCore 的共享 context 绑定 GLSurfaceView 的
        // surface，GLSurfaceView 在 onDrawFrame 返回后的 eglSwapBuffers 会失败，
        // 导致屏幕画面永远不再更新（卡死在第一帧）。
        val savedDisplay = EGL14.eglGetCurrentDisplay()
        val savedDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val savedReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val savedContext = EGL14.eglGetCurrentContext()

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
            if (savedDisplay !== EGL14.EGL_NO_DISPLAY &&
                savedContext !== EGL14.EGL_NO_CONTEXT) {
                if (!EGL14.eglMakeCurrent(
                        savedDisplay, savedDrawSurface, savedReadSurface, savedContext
                    )) {
                    Log.e(TAG, "恢复 EGL 状态失败, error=0x${Integer.toHexString(EGL14.eglGetError())}")
                }
            }
        }
    }

    /**
     * 停止录制。
     *
     * 分两阶段避免 GL 线程长时间阻塞导致系统杀进程（表现：点停止秒闪退）：
     *
     * 阶段1（GL 线程同步，必须快）：
     *   - 停止音频采集 + join 音频线程
     *   - 视频发送 EOS + 排空编码器（保证视频完整）
     *   - videoEncoder.stop()
     *   - 销毁 GL 资源（EGL surface/context + directProgram，必须在 GL 线程）
     *
     * 阶段2（后台 IO 线程，不阻塞 GL）：
     *   - muxer.stop/release
     *   - videoEncoder/audioEncoder.release()（MediaCodec release 可能慢）
     *   - moveToGallery（磁盘 IO + ContentResolver，耗时大头）
     *   - onVideoSaved 回调
     *
     * 原实现把 moveToGallery + 所有 release 都堆在 GL 线程，GL 线程阻塞
     * 数秒（文件拷贝 + MediaCodec release），触发系统 GL watchdog 杀进程。
     */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        Log.d(TAG, "停止录制...")

        // ── 阶段1：GL 线程同步部分（快）──
        try {
            // 1. 停止音频（发送 EOS + 排空 + join 线程，阻塞等待）
            audioEncoder?.stop()

            // 2. 视频发送 EOS 并排空（必须，保证视频尾部完整）
            videoEncoder?.drainEncoder(endOfStream = true)

            // 3. 停止视频编码器
            videoEncoder?.stop()

            // 4. 销毁 GL 资源（EGL surface/context + directProgram）
            //    必须在 GL 线程：EGL context 在此线程创建，销毁也要在此线程
            cleanupGlResources()
        } catch (e: Exception) {
            Log.e(TAG, "停止录制阶段1异常", e)
        }

        // ── 阶段2：后台 IO 线程（慢操作，不阻塞 GL 线程）──
        val path = tempFilePath
        Thread({
            try {
                // muxer 停止 + 释放
                muxerLock.withLock {
                    if (muxerStarted) {
                        try { muxer?.stop() } catch (e: Exception) {
                            Log.w(TAG, "Muxer stop 异常: ${e.message}")
                        }
                        muxerStarted = false
                    }
                    try { muxer?.release() } catch (_: Exception) {}
                    muxer = null
                    pendingVideoSamples.clear()
                    pendingAudioSamples.clear()
                }

                // 编码器释放（MediaCodec release 在某些机型较慢）
                videoEncoder?.release()
                videoEncoder = null
                audioEncoder?.release()
                audioEncoder = null

                // 文件搬迁到相册（磁盘 IO，最耗时）
                moveToGallery()
            } catch (e: Exception) {
                Log.e(TAG, "停止录制阶段2异常", e)
                onRecordingError?.invoke(e)
            } finally {
                path?.let { runCatching { File(it).delete() } }
                tempFilePath = null
                outputPath = null
                Log.d(TAG, "录制停止完成")
            }
        }, "RecorderStop").start()
    }

    /**
     * 释放所有资源（不发送 EOS）。
     *
     * 用于 Composable onDispose，可能在主线程调用。
     * GL 资源销毁通过 queueEvent 调度到 GL 线程，非 GL 资源在调用线程释放。
     * 注意：此方法不保证 GL 资源立即销毁（依赖 GL 线程存活）。
     */
    fun release() {
        if (isRecording) {
            isRecording = false
        }
        // GL 资源必须延后到 GL 线程销毁（如果还在）
        // 这里只做非 GL 清理，GL 部分由 GLSurfaceView.release 兜底
        cleanupNonGlResources()
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
            } else if (info.size > 0) {
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
            } else if (info.size > 0) {
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
     */
    private fun moveToGallery() {
        val tempPath = tempFilePath ?: return
        val tempFile = File(tempPath)
        if (!tempFile.exists() || tempFile.length() == 0L) {
            Log.w(TAG, "临时视频文件不存在或为空")
            tempFile.delete()
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
     * 释放顺序（避免 native 崩溃）：
     *   1. 先在 EglCore context 下 makeCurrent(NO_SURFACE) 取消绑定编码器 EGLSurface
     *   2. destroySurface（销毁 EGLSurface，此时不再被任何 context 使用）
     *   3. eglCore.release()（销毁共享 context，不 terminate display）
     *   4. glDeleteProgram（销毁直绘 shader）
     *
     * 关键：必须在 destroySurface 之前 makeCurrent 到 NO_SURFACE，
     * 否则销毁仍被当前 context 绑定的 surface 会在部分驱动上触发 GL 错误。
     */
    private fun cleanupGlResources() {
        val egl = eglCore
        val encSurface = encoderEglSurface
        if (egl != null && encSurface != null) {
            // 取消绑定编码器 EGLSurface（先切到 EglCore context 再 unbind）
            runCatching { egl.makeCurrent(encSurface) }
            runCatching { egl.unbindCurrent() }
            runCatching { egl.destroySurface(encSurface) }
        }
        encoderEglSurface = null
        eglCore?.release()
        eglCore = null

        // 清理直绘 shader
        if (directProgram != 0) {
            GLES20.glDeleteProgram(directProgram)
            directProgram = 0
        }
        Log.d(TAG, "GL 资源已清理")
    }

    /**
     * 清理非 GL 资源（可在任意线程调用）。
     *
     * 释放编码器、muxer、临时文件。供 [release] 兜底使用。
     * 注意：不销毁 EGL/GL 资源（那必须在 GL 线程做）。
     */
    private fun cleanupNonGlResources() {
        videoEncoder?.release()
        videoEncoder = null
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
        outputPath = null
        Log.d(TAG, "非 GL 资源已清理")
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data).position(0)
        return buffer
    }
}
