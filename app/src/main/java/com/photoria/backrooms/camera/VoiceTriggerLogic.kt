package com.photoria.backrooms.camera

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 声控触发的判定内核（纯 Kotlin，无 Android 依赖，可在 JVM 上喂合成序列验证）。
 *
 * 判定分三层，缺一不可：
 *   1. 自适应底噪 floor：安静环境里 floor 会贴到噪声水平，触发线 = floor × [noisePickup]，
 *      于是「安静处拍手」和「嘈杂街头拍手」用同一套参数都能工作；
 *   2. 绝对下限 [absMinLevel]：防止极安静环境下呼吸声就触发；
 *   3. 连续 [minFramesAbove] 帧超线 + [cooldownMs] 冷却：单帧毛刺（碰麦、电流声）不算，
 *      一次拍手只出一次快门。
 *
 * floor 非对称追踪：向下快（几帧内贴合真实噪声）、向上慢（长期变吵才抬触发线），
 * 且永不越过 [absMinLevel]，避免把触发线推到用户喊不动。
 */
class VoiceTriggerLogic(
    /** 相对底噪的倍数门限（越大越迟钝） */
    var noisePickup: Float = 5f,
    /** 绝对触发下限（归一化 RMS，0..1） */
    var absMinLevel: Float = 0.06f,
    /** 两次触发最小间隔 */
    var cooldownMs: Long = 900L,
    /** 需要连续超线的帧数 */
    var minFramesAbove: Int = 2
) {

    /** 当前估计底噪（归一化 RMS） */
    var floor: Float = INITIAL_FLOOR
        private set

    /** 最近一帧的 RMS，供 UI 画电平条 */
    var lastLevel: Float = 0f
        private set

    private var aboveStreak = 0
    private var lastFireMs = NOT_FIRED

    /**
     * 送入一帧电平，返回是否应当触发快门。
     *
     * @param rms 归一化 RMS（0..1）
     * @param nowMs 单调时钟（毫秒）
     */
    fun update(rms: Float, nowMs: Long): Boolean {
        val level = if (rms.isFinite()) rms.coerceIn(0f, 1f) else 0f
        lastLevel = level

        val threshold = maxOf(absMinLevel, floor * noisePickup)
        val above = level > threshold

        floor = when {
            level < floor -> floor + (level - floor) * FLOOR_FALL_RATE
            // 超线期间不参与底噪估计，否则一次拍手就把 floor 抬高了
            above -> floor
            else -> minOf(floor + (level - floor) * FLOOR_RISE_RATE, absMinLevel)
        }

        aboveStreak = if (above) aboveStreak + 1 else 0
        if (aboveStreak < minFramesAbove) return false
        // 冷却期内直接作废本次候选（必须清空 streak，否则持续大噪声会在冷却结束那帧补触发）
        aboveStreak = 0
        if (lastFireMs != NOT_FIRED && nowMs - lastFireMs < cooldownMs) return false
        lastFireMs = nowMs
        return true
    }

    /** 重新监听时调用：环境噪声基线要重估 */
    fun reset() {
        floor = INITIAL_FLOOR
        lastLevel = 0f
        aboveStreak = 0
        lastFireMs = NOT_FIRED
    }

    companion object {
        /** 「从未触发过」哨兵。不能用 Long.MIN_VALUE 直接参与减法 —— 会溢出成负数，把首次触发永久锁死。 */
        private const val NOT_FIRED = Long.MIN_VALUE
        private const val INITIAL_FLOOR = 0.004f
        private const val FLOOR_FALL_RATE = 0.2f
        private const val FLOOR_RISE_RATE = 0.01f

        /** 一段 PCM16 单声道采样的归一化 RMS（16kHz×512 样本 ≈ 每 32ms 一次，够便宜） */
        fun rmsOf(samples: ShortArray, count: Int = samples.size): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val v = samples[i].toInt()
                sum += (v * v).toDouble()
            }
            return (sqrt(sum / count) / 32768.0).toFloat()
        }

        /** 换算成 dBFS，仅用于日志可读（-60dB ≈ 0.001） */
        fun levelToDb(level: Float): Float =
            if (level <= 1e-6f) -120f else (20 * log10(level.toDouble())).toFloat()
    }
}
