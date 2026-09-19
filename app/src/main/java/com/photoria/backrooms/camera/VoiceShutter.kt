package com.photoria.backrooms.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.photoria.backrooms.camera.VoiceTriggerLogic.Companion.rmsOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * 声控快门的 Android 壳：只负责「开麦 → 逐帧算 RMS → 交给 [VoiceTriggerLogic] 判定」。
 *
 * 判定算法刻意不在这个类里：那部分能在 JVM 上喂合成 PCM 断言「恰好触发一次」，
 * 这里剩下的全是设备相关的脏活（权限、音频源、线程所有权）。
 *
 * 麦克风所有权模型照抄 encoder/AudioEncoder：一个采集线程独占 AudioRecord，
 * 资源只由该线程释放，read() 连续失败到阈值自杀（否则被系统抢麦后会热自旋占满一核）。
 *
 * 与录像互斥：Android 上同时存在两个 AudioRecord 时，后开的那个通常只能读到静音，
 * 所以调用方必须在 videoRecorder.startRecording() 之前同步 stop()，
 * 录制结束后再 start()。
 */
class VoiceShutter(
    private val context: Context,
    private val logic: VoiceTriggerLogic = VoiceTriggerLogic()
) {

    /**
     * 电平条用的一帧快照（10Hz）。
     *
     * 带上 threshold 是因为它随环境底噪自适应：只显示音量的话，
     * 面板上那两个滑块根本没法调 —— 用户看不见线画在哪里。
     */
    data class Meter(val level: Float, val threshold: Float)

    /** 最近一帧电平与触发线，供 UI 画电平条 */
    private val _meter = MutableStateFlow(Meter(0f, 0f))
    val meter: StateFlow<Meter> = _meter.asStateFlow()

    private companion object {
        const val TAG = "VoiceShutter"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512          // 32ms 一帧，够便宜又能抓住拍手的沿
        const val MAX_READ_ERRORS = 50         // ≈1.6 秒读不到数据就退出
        const val LOG_INTERVAL_MS = 1_000L     // 电平日志限频，否则刷屏
        const val LEVEL_INTERVAL_MS = 100L     // 电平条 10Hz 足够，再快只是白重组
        // 采集线程最迟在下一帧（32ms）后退出；等到 500ms 覆盖住 read() 偶发阻塞，
        // 保证「stop 返回 = 麦克风已交还」，录像才不会拿到一个静音的 AudioRecord
        const val THREAD_JOIN_MS = 500L
    }

    private val lock = Any()

    @Volatile
    private var listening = false

    private var captureThread: Thread? = null

    /** 是否已在监听（录像互斥、UI 显示都看这个） */
    val active: Boolean get() = listening

    /**
     * 开始监听。
     *
     * @return 是否成功启动。失败原因（无权限 / 无麦克风 / 初始化失败）已写 logcat，
     *   调用方只需静默放弃 —— 声控快门是锦上添花，不该弹窗打断取景。
     */
    fun start(onTrigger: () -> Unit): Boolean = synchronized(lock) {
        if (listening) return true
        val previous = captureThread
        if (previous != null && previous.isAlive) {
            // 上一轮的线程还持有 AudioRecord：现在就开机会有两个麦克风实例，
            // 后开的那个通常只能读到静音
            runCatching { previous.join(THREAD_JOIN_MS) }
            if (previous.isAlive) {
                Log.w(TAG, "unavailable: 上一次监听的线程未退出")
                return false
            }
        }
        if (!hasAudioPermission()) {
            Log.d(TAG, "unavailable: 无 RECORD_AUDIO 权限")
            return false
        }
        val record = openRecord() ?: return false
        logic.reset()
        listening = true
        captureThread = Thread { runCaptureLoop(record, onTrigger) }.apply {
            name = "VoiceShutter"
            start()
        }
        Log.d(TAG, "start: rate=$SAMPLE_RATE frame=$FRAME_SAMPLES pickup=${logic.noisePickup} absMin=${logic.absMinLevel}")
        true
    }

    /** 停止监听。采集线程自己负责 release，这里只等它收尾。 */
    fun stop() {
        synchronized(lock) {
            if (!listening) return
            listening = false
        }
        // 见 THREAD_JOIN_MS：返回即代表麦克风已交还
        runCatching { captureThread?.join(THREAD_JOIN_MS) }
    }

    /** 触发灵敏度（相对底噪倍数），面板滑块直接写这里 */
    fun setNoisePickup(value: Float) {
        logic.noisePickup = value
    }

    /** 绝对触发下限（归一化 RMS） */
    fun setAbsMinLevel(value: Float) {
        logic.absMinLevel = value
    }

    // ── 内部实现 ──────────────────────────────────────────────────

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 打开麦克风。
     *
     * 先试 VOICE_RECOGNITION（面向人声的链路，通常带合适的增益），
     * 个别 ROM 不支持这个音频源会初始化失败 —— 那时退回 MIC，
     * 不然用户只能得到一个永远不响的功能。
     */
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "unavailable: 16kHz 单声道 PCM 不被支持 (minBuf=$minBuf)")
            return null
        }
        val bufferSize = max(minBuf * 2, FRAME_SAMPLES * 4)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val record = try {
                @Suppress("DEPRECATION")
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "unavailable: 无麦克风权限 (${e.message})")
                return null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "unavailable: 音频源 $source 参数非法 (${e.message})")
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            Log.w(TAG, "音频源 $source 初始化失败，尝试下一个")
            runCatching { record.release() }
        }
        Log.w(TAG, "unavailable: 所有音频源都初始化失败")
        return null
    }

    /** 采集线程主体：读一帧 → 算 RMS → 判定 → 触发；退出时释放自己持有的 AudioRecord */
    private fun runCaptureLoop(record: AudioRecord, onTrigger: () -> Unit) {
        val samples = ShortArray(FRAME_SAMPLES)
        var readErrors = 0
        var lastLogMs = 0L
        var lastLevelMs = 0L
        try {
            record.startRecording()
            while (listening) {
                val count = try {
                    record.read(samples, 0, FRAME_SAMPLES)
                } catch (e: Exception) {
                    Log.w(TAG, "read 异常: ${e.message}")
                    break
                }
                if (count <= 0) {
                    if (++readErrors > MAX_READ_ERRORS) {
                        Log.w(TAG, "连续 $readErrors 次读取失败，退出监听（麦克风可能被占用）")
                        break
                    }
                    continue
                }
                readErrors = 0

                val rms = rmsOf(samples, count)
                val nowMs = SystemClock.elapsedRealtime()
                val threshold = maxOf(logic.absMinLevel, logic.floor * logic.noisePickup)
                val fired = logic.update(rms, nowMs)
                if (nowMs - lastLevelMs >= LEVEL_INTERVAL_MS) {
                    lastLevelMs = nowMs
                    _meter.value = Meter(rms, threshold)
                }
                if (fired) {
                    Log.d(
                        TAG,
                        "fired: rms=${fmt(rms)} floor=${fmt(logic.floor)} " +
                            "db=${VoiceTriggerLogic.levelToDb(rms)}"
                    )
                    onTrigger()
                }
                if (nowMs - lastLogMs >= LOG_INTERVAL_MS) {
                    lastLogMs = nowMs
                    Log.d(TAG, "rms=${fmt(rms)} floor=${fmt(logic.floor)} threshold=${fmt(threshold)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "监听循环异常退出: ${e.message}")
        } finally {
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
            listening = false
            _meter.value = Meter(0f, 0f)
        }
    }

    private fun fmt(value: Float): String = String.format("%.4f", value)
}
