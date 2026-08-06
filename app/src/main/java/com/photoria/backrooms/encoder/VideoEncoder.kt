package com.photoria.backrooms.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 视频编码器封装。
 *
 * 使用 MediaCodec 的 Surface 输入模式，将 GL 渲染的帧编码为 H.264 视频流。
 *
 * 编码后的数据通过 onSampleData 回调输出，由 GLVideoRecorder 在共享锁保护下
 * 写入 MediaMuxer（与音频编码器共享 muxer）。这样 muxer 的 start/addTrack
 * 可统一协调，保证线程安全。
 *
 * 流程：
 *   1. init() 创建编码器
 *   2. createInputSurface() 获取编码器输入 Surface（传给 EglCore 创建 EGLSurface）
 *   3. start() 已在 init 中调用
 *   4. 每帧渲染后调用 drainEncoder() 取出编码数据（经回调写入 muxer）
 *   5. stop() 停止编码器
 *   6. release() 释放资源
 */
class VideoEncoder {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val DEFAULT_BIT_RATE = 6_000_000  // 6 Mbps
        private const val DEFAULT_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
    }

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** 编码器输出格式（用于添加轨道） */
    var outputFormat: MediaFormat? = null
        private set

    /** 输出格式是否已就绪（可添加轨道） */
    var isOutputFormatReady = false
        private set

    /**
     * 编码数据回调（由 GLVideoRecorder 在加锁后写入 muxer）。
     * 回调参数：buffer（position 已设好，可读）、info。
     */
    var onSampleData: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    /**
     * 初始化视频编码器。
     *
     * @param width     视频宽度（建议 1280，16 字节对齐）
     * @param height    视频高度（建议 720，16 字节对齐）
     * @param frameRate 帧率（默认 30）
     * @param bitRate   码率（默认 6Mbps）
     */
    fun init(
        width: Int,
        height: Int,
        frameRate: Int = DEFAULT_FRAME_RATE,
        bitRate: Int = DEFAULT_BIT_RATE
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
        }

        Log.d(TAG, "视频编码器已初始化: ${width}x${height}, ${frameRate}fps, ${bitRate / 1000}kbps")
    }

    /**
     * 获取编码器的输入 Surface。
     * 此 Surface 将传给 EglCore 创建 EGLSurface，用于 GL 渲染到编码器。
     */
    fun getInputSurface(): Surface {
        return encoderSurface ?: throw IllegalStateException("编码器未初始化")
    }

    /**
     * 排空编码器输出缓冲区。
     *
     * - 正常模式（endOfStream=false）：非阻塞，取出当前所有已编码帧
     * - 结束模式（endOfStream=true）：发送 EOS 信号后，用 10s 阻塞超时等待所有
     *   剩余帧排出（含 EOS 标记帧）。原实现用 0 超时导致 EOS 帧未生成即 break，
     *   编码器在未排空状态下被 stop()，可能触发 native 崩溃。
     *
     * @param endOfStream 是否发送结束信号（最后一帧时传 true）
     */
    fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return

        if (endOfStream) {
            enc.signalEndOfInputStream()
        }

        // EOS 模式安全上限：最多等待 500 次 × 10ms = 5s，防止编码器异常时无限等待
        var eosRetries = 0
        val maxEosRetries = 500

        while (true) {
            // EOS 模式用 10ms 阻塞超时等待编码器产出剩余帧；正常模式非阻塞
            val timeoutUs = if (endOfStream) 10_000L else 0L
            val outputIndex = enc.dequeueOutputBuffer(bufferInfo, timeoutUs)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) {
                        // EOS 模式继续等待，但设上限防止死循环
                        if (++eosRetries > maxEosRetries) {
                            Log.w(TAG, "视频 EOS 排空超时，强制退出")
                            break
                        }
                        continue
                    }
                    break  // 正常模式：没有更多数据
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 编码器输出格式已确定，通知 GLVideoRecorder 添加轨道
                    outputFormat = enc.outputFormat
                    isOutputFormatReady = true
                    Log.d(TAG, "视频输出格式: $outputFormat")
                }

                outputIndex >= 0 -> {
                    val outputBuffer = enc.getOutputBuffer(outputIndex) ?: continue

                    // 跳过 codec config 数据（SPS/PPS 等，muxer 会自动处理）
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        // 同步回调写入 muxer（在 releaseOutputBuffer 之前完成读取）
                        onSampleData?.invoke(outputBuffer, bufferInfo)
                    }

                    enc.releaseOutputBuffer(outputIndex, false)

                    // 流结束
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "视频编码器流结束")
                        break
                    }
                }
            }
        }
    }

    /**
     * 停止编码器。
     */
    fun stop() {
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "编码器停止异常: ${e.message}")
        }
        Log.d(TAG, "视频编码器已停止")
    }

    /**
     * 释放所有资源。
     */
    fun release() {
        encoderSurface?.release()
        encoderSurface = null
        try {
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "编码器释放异常: ${e.message}")
        }
        encoder = null
        outputFormat = null
        isOutputFormatReady = false
        Log.d(TAG, "视频编码器资源已释放")
    }
}
