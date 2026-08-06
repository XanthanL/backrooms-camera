package com.photoria.backrooms.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
 *   5. stop() 发送 EOS 并排空剩余数据（阻塞等待）
 *   6. release() 释放资源
 *
 * 线程模型：
 *   - init/start/stop/release 可在主线程或 GL 线程调用
 *   - 采集与编码在内部音频线程进行
 *   - muxer 操作通过共享 muxerLock 同步
 */
class AudioEncoder {

    companion object {
        private const val TAG = "AudioEncoder"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 1          // 单声道
        private const val BIT_RATE = 64_000          // 64 kbps
        private const val PCM_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var encoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    private var isRecording = false

    private var muxer: MediaMuxer? = null
    private var muxerLock: ReentrantLock? = null

    /** 编码器输出格式（用于添加轨道） */
    var outputFormat: MediaFormat? = null
        private set

    /** 输出格式是否已就绪（可添加轨道） */
    var isOutputFormatReady = false
        private set

    /** 录制起始时间戳（纳秒），用于 PTS 计算 */
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
        // 无 RECORD_AUDIO 权限时构造会抛 SecurityException：捕获后仅录制无声视频
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            PCM_ENCODING
        )
        val bufferSize = (minBufSize * 2).coerceAtLeast(8192)
        @Suppress("DEPRECATION")
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                PCM_ENCODING,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "无麦克风权限，跳过音频采集: ${e.message}")
            null
        }

        Log.d(TAG, "音频编码器已初始化: ${SAMPLE_RATE}Hz mono ${BIT_RATE / 1000}kbps")
    }

    /**
     * 启动音频采集与编码线程。
     */
    fun start() {
        if (isRecording) return
        isRecording = true
        startTimeNs = System.nanoTime()

        audioRecord?.startRecording()
        recordThread = Thread({ runCaptureLoop() }, "AudioEncoderThread").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "音频采集线程已启动")
    }

    /**
     * 停止采集并发送 EOS。阻塞等待编码器排空。
     *
     * 先停止 AudioRecord 以解除采集线程中 read() 的阻塞，
     * 再 join 线程等待 EOS 排空完成。原实现先 join 再 stop AudioRecord，
     * 若 read() 阻塞中则 join 超时，线程仍在运行时 release() 会触发 native 崩溃。
     */
    fun stop() {
        if (!isRecording) return
        isRecording = false

        // 先停止 AudioRecord，解除采集线程 read() 的阻塞
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.stop 异常: ${e.message}")
        }

        // 等待采集线程退出（发送 EOS + 排空编码器）
        try {
            recordThread?.join(5000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordThread = null
        Log.d(TAG, "音频编码器已停止")
    }

    /**
     * 释放资源。
     */
    fun release() {
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
     */
    private fun runCaptureLoop() {
        val codec = encoder ?: return
        val recorder = audioRecord ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        // 一帧 AAC = 1024 samples * 2 bytes * 1 channel
        val frameBytes = 1024 * 2 * CHANNEL_COUNT
        val pcmBuffer = ByteArray(frameBytes)

        while (isRecording) {
            // 1. 从 AudioRecord 读取 PCM
            // AudioRecord.stop() 后 read() 会返回错误或抛异常，需 try-catch
            val read = try {
                recorder.read(pcmBuffer, 0, pcmBuffer.size)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.read 异常: ${e.message}")
                break
            }
            if (read <= 0) continue

            // 2. 输入到编码器
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                inputBuffer.clear()
                inputBuffer.put(pcmBuffer, 0, read)
                val ptsUs = (System.nanoTime() - startTimeNs) / 1000L
                codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
            }

            // 3. 排空编码后的 AAC（非阻塞）
            drainEncoder(codec, bufferInfo, endOfStream = false)
        }

        // 4. 发送 EOS 并排空剩余
        try {
            val eosIndex = codec.dequeueInputBuffer(10_000)
            if (eosIndex >= 0) {
                codec.queueInputBuffer(
                    eosIndex, 0, 0,
                    (System.nanoTime() - startTimeNs) / 1000L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drainEncoder(codec, bufferInfo, endOfStream = true)
        } catch (e: Exception) {
            Log.w(TAG, "音频 EOS 排空异常: ${e.message}")
        }
    }

    /**
     * 排空音频编码器输出。
     *
     * @param endOfStream true 时发送了 EOS，需阻塞等待所有数据排出
     */
    private fun drainEncoder(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) continue  // EOS 模式继续等待
                    break
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                    isOutputFormatReady = true
                    Log.d(TAG, "音频输出格式: $outputFormat")
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue

                    // 跳过 codec config（AAC 专属头，muxer 会自动处理）
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        onSampleData?.invoke(outputBuffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "音频编码器流结束")
                        break
                    }
                }
            }
        }
    }
}
