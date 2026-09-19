package com.photoria.backrooms.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/**
 * AAC 音频编码器封装（E1）。
 *
 * 使用 AudioRecord 采集麦克风 PCM，编码为 AAC，通过回调写入 MediaMuxer。
 *
 * 流程：
 *   1. init() 配置 AudioRecord + MediaCodec（AAC LC）
 *   2. start() 启动采集线程，循环读取 PCM 并编码
 *   3. 输出格式确定后由 GLVideoRecorder 统一添加轨道并启动 muxer
 *   4. 编码后的 AAC 通过 onSampleData 回调输出（带 muxerLock 同步）
 *   5. stop() 置停止标志 + 解除 read 阻塞 + 有界等待线程退出
 *   6. release() 等线程退出后再释放（线程存活期间绝不跨线程拆 MediaCodec）
 *
 * **所有权模型（关键）**：MediaCodec 与 AudioRecord 只由采集线程释放
 * （见 [runCaptureLoop] 的 finally）。原实现在采集线程仍阻塞于
 * dequeueOutputBuffer / read 时，由停止线程调用 encoder.release() 与
 * audioRecord.release()，属于 MediaCodec 的跨线程非法使用，会在
 * libstagefright 层直接 native abort（无 Java 栈，Java try-catch 抓不到）——
 * 这是"点停止录像秒闪退"的第二个独立成因。
 */
class AudioEncoder {

    companion object {
        private const val TAG = "AudioEncoder"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 1          // 单声道
        private const val BIT_RATE = 64_000          // 64 kbps
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 等待采集线程退出的上限 */
        private const val THREAD_EXIT_TIMEOUT_MS = 4_000L
        /** 发送 EOS 时等待可用输入缓冲的上限 */
        private const val EOS_INPUT_TIMEOUT_MS = 500L
        /** EOS 后排空输出的上限（正常情况下只需几毫秒） */
        private const val EOS_DRAIN_TIMEOUT_MS = 1_000L
        /** 连续 read 失败上限，超过则退出采集循环 */
        private const val MAX_READ_ERRORS = 20
    }

    private var encoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    private var isRecording = false

    /** 采集线程已完成自身资源释放（唯一安全释放 codec 的时机） */
    @Volatile
    private var threadFinished = true

    private var muxer: MediaMuxer? = null
    private var muxerLock: ReentrantLock? = null

    /** 编码器输出格式（用于添加轨道） */
    var outputFormat: MediaFormat? = null
        private set

    /** 输出格式是否已就绪（可添加轨道） */
    var isOutputFormatReady = false
        private set

    /**
     * PTS 时间基准（纳秒）。由调用方传入与视频共用的录制起点，
     * 避免音/视频各自取起点导致固定偏移。
     */
    private var startTimeNs = 0L

    /**
     * 编码数据回调（由 GLVideoRecorder 在加锁后写入 muxer）。
     * 回调参数：buffer（position 已设好，可读）、info。
     */
    var onSampleData: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    /**
     * 初始化音频编码器与 AudioRecord。
     *
     * @param muxer     MediaMuxer 实例
     * @param muxerLock muxer 同步锁（与视频编码器共享）
     * @throws IllegalStateException AudioRecord 不可用（无权限 / 初始化失败 / 被占用）
     */
    fun init(muxer: MediaMuxer, muxerLock: ReentrantLock) {
        this.muxer = muxer
        this.muxerLock = muxerLock

        // 配置 AAC 编码器
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        // 配置 AudioRecord（旧构造函数，兼容 API 24+）
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            PCM_ENCODING
        )
        val bufferSize = (minBufSize * 2).coerceAtLeast(8192)
        @Suppress("DEPRECATION")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                PCM_ENCODING,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "无麦克风权限: ${e.message}")
            null
        }

        // AudioRecord 不可用必须抛出让上层降级为纯视频录制。
        // 若只是吞掉（原实现），采集线程会因 audioRecord == null 直接 return，
        // isOutputFormatReady 永远为 false → tryStartMuxer 的 allAudioReady 永不满足
        // → muxer 永不 start → 视频样本堆积、产出空文件、UI 卡在录制态。
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            audioRecord = null
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            encoder = null
            throw IllegalStateException("AudioRecord 不可用（无麦克风权限或初始化失败）")
        }
        audioRecord = record
        Log.d(TAG, "音频编码器已初始化: ${SAMPLE_RATE}Hz mono ${BIT_RATE / 1000}kbps")
    }

    /**
     * 启动音频采集与编码线程。
     *
     * @param baseTimeNs 与视频共用的录制起点（System.nanoTime 基准）
     */
    fun start(baseTimeNs: Long = System.nanoTime()) {
        if (isRecording) return
        val record = audioRecord ?: throw IllegalStateException("AudioEncoder 未初始化")

        startTimeNs = baseTimeNs
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.stop() }
            throw IllegalStateException("AudioRecord 未能进入录制状态")
        }

        isRecording = true
        threadFinished = false
        recordThread = Thread({ runCaptureLoop() }, "AudioEncoderThread").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "音频采集线程已启动")
    }

    /**
     * 停止采集，有界等待采集线程退出。
     *
     * 先 AudioRecord.stop() 解除采集线程 read() 的阻塞，再 join。
     * 采集循环内所有等待都有超时上限，因此 join 必然在
     * [THREAD_EXIT_TIMEOUT_MS] 内返回。
     */
    fun stop() {
        isRecording = false

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop 异常: ${e.message}")
        }

        val thread = recordThread ?: return
        try {
            thread.join(THREAD_EXIT_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            Log.e(TAG, "音频采集线程 ${THREAD_EXIT_TIMEOUT_MS}ms 内未退出，" +
                    "其持有的 codec/AudioRecord 交由该线程自行释放")
        } else {
            recordThread = null
        }
    }

    /**
     * 释放资源。
     *
     * 只有确认采集线程已退出（[threadFinished]）才触碰 MediaCodec 与 AudioRecord，
     * 否则直接返回——线程会在自己的 finally 里完成释放。
     */
    fun release() {
        stop()
        if (!threadFinished) {
            Log.w(TAG, "音频线程仍存活，跳过跨线程释放（避免 native abort）")
            return
        }
        releaseOwnedResources()
    }

    /**
     * 释放本编码器持有的 MediaCodec 与 AudioRecord。
     *
     * 调用方：采集线程退出前的 finally（唯一常规路径），或线程确认已退出后的
     * [release]。绝不可在采集线程存活期间调用。
     */
    private fun releaseOwnedResources() {
        if (encoder == null && audioRecord == null) return

        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "编码器停止异常: ${e.message}")
        }
        try {
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "编码器释放异常: ${e.message}")
        }
        encoder = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord 释放异常: ${e.message}")
        }
        audioRecord = null

        outputFormat = null
        isOutputFormatReady = false
        Log.d(TAG, "音频编码器资源已释放")
    }

    /**
     * 音频采集与编码主循环（在音频线程执行）。
     *
     * 退出时负责发送 EOS、排空并把所有本线程资源交回系统。
     */
    private fun runCaptureLoop() {
        val codec = encoder
        val recorder = audioRecord
        try {
            if (codec == null || recorder == null) return

            val bufferInfo = MediaCodec.BufferInfo()
            // 一帧 AAC = 1024 samples * 2 bytes * 1 channel
            val frameBytes = 1024 * 2 * CHANNEL_COUNT
            val pcmBuffer = ByteArray(frameBytes)
            var readErrors = 0

            while (isRecording) {
                // AudioRecord.stop() 后 read() 会返回错误或抛异常，需 try-catch
                val read = try {
                    recorder.read(pcmBuffer, 0, pcmBuffer.size)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord.read 异常: ${e.message}")
                    break
                }
                if (read <= 0) {
                    // 原先无条件 continue 会在录音被系统抢占时热自旋占满一个核
                    if (++readErrors > MAX_READ_ERRORS) {
                        Log.w(TAG, "连续 $readErrors 次读取失败，退出采集循环")
                        break
                    }
                    continue
                }
                readErrors = 0

                // 1. 输入到编码器
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(pcmBuffer, 0, read)
                        val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                        codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    } else {
                        // MediaCodec 无 releaseInputBuffer：输入缓冲只能靠
                        // queueInputBuffer 归还，否则缓冲池会被耗干
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }

                // 2. 排空编码后的 AAC（非阻塞）
                drainEncoder(codec, bufferInfo, endOfStream = false)
            }

            // 3. 发送 EOS 并排空剩余（两步都有超时上限，绝不会永久自旋）
            queueEndOfStream(codec)
            drainEncoder(codec, bufferInfo, endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "音频采集循环异常退出", e)
        } finally {
            releaseOwnedResources()
            threadFinished = true
        }
    }

    /**
     * 把 EOS 送进编码器输入队列。
     *
     * 原实现只试一次 dequeueInputBuffer，失败即放弃入队却仍然
     * drainEncoder(endOfStream = true) —— 那种情况下永远等不到 EOS 帧，
     * 排空循环无限 continue，采集线程永不退出。
     */
    private fun queueEndOfStream(codec: MediaCodec) {
        val deadline = SystemClock.elapsedRealtime() + EOS_INPUT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val index = try {
                codec.dequeueInputBuffer(10_000)
            } catch (e: Exception) {
                Log.w(TAG, "等待 EOS 输入缓冲异常: ${e.message}")
                return
            }
            if (index >= 0) {
                val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                codec.queueInputBuffer(
                    index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }
        }
        Log.w(TAG, "EOS 入队超时（${EOS_INPUT_TIMEOUT_MS}ms），本次音频尾帧不完整")
    }

    /**
     * 排空音频编码器输出。
     *
     * @param endOfStream true 时阻塞等待剩余数据，但受
     *                    [EOS_DRAIN_TIMEOUT_MS] 截止时间约束
     */
    private fun drainEncoder(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean
    ) {
        val deadline = if (endOfStream) {
            SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS
        } else {
            Long.MAX_VALUE
        }

        while (true) {
            if (endOfStream && SystemClock.elapsedRealtime() > deadline) {
                Log.w(TAG, "音频 EOS 排空超时（${EOS_DRAIN_TIMEOUT_MS}ms），强制退出")
                return
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) continue  // 受上面的截止时间约束
                    return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                    isOutputFormatReady = true
                    Log.d(TAG, "音频输出格式: $outputFormat")
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)

                    // codec config 数据（AAC 专属头）不写 muxer，但缓冲区仍须归还
                    if (outputBuffer != null &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    ) {
                        bufferInfo.size = 0
                    }

                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        onSampleData?.invoke(outputBuffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "音频编码器流结束")
                        return
                    }
                }
            }
        }
    }
}
