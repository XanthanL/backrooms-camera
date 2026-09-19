package com.photoria.backrooms.util

import android.content.Context
import android.util.Log
import com.photoria.backrooms.PhotoriaApp
import com.photoria.backrooms.gl.ZebraMode
import com.photoria.backrooms.ui.viewmodel.AspectRatio
import com.photoria.backrooms.ui.viewmodel.BurstCount
import com.photoria.backrooms.ui.viewmodel.CountdownSec
import com.photoria.backrooms.ui.viewmodel.FitMode
import org.json.JSONObject

/**
 * 滤镜与画幅偏好的持久化工具。
 *
 * 存储内容：
 *   - 上次选中的滤镜索引
 *   - 上次画幅比例
 *   - 上次画幅填充模式
 *   - 每个滤镜的参数表（uniformName → value），以 JSON 字符串保存
 *   - 滤镜强度
 *   - 取景辅助开关（直方图 / 斑马纹 / 峰值对焦 + 灵敏度 / 水平仪）
 *   - 快门小工具（音量键快门 / 倒计时 / 声控 + 两个门限 / 连拍帧数 + 动图）
 *
 * 使用 Application Context，避免内存泄漏。
 */
object FilterPrefs {

    private const val TAG = "FilterPrefs"
    private const val PREFS_NAME = "photoria_prefs"
    private const val KEY_LAST_FILTER_INDEX = "last_filter_index"
    private const val KEY_LAST_ASPECT = "last_aspect"
    private const val KEY_LAST_FIT_MODE = "last_fit_mode"
    private const val KEY_FILTER_PARAMS_PREFIX = "filter_params_"
    private const val KEY_SHOW_GRID = "show_grid"
    private const val KEY_FILTER_STRENGTH = "filter_strength"
    private const val KEY_SHOW_HISTOGRAM = "show_histogram"
    private const val KEY_ZEBRA_MODE = "zebra_mode"
    private const val KEY_FOCUS_PEAKING = "focus_peaking"
    private const val KEY_PEAKING_SENSITIVITY = "peaking_sensitivity"
    private const val KEY_SHOW_BUBBLE_LEVEL = "show_bubble_level"
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
    private const val KEY_VOLUME_KEY_SHUTTER = "volume_key_shutter"
    private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
    private const val KEY_VOICE_SHUTTER = "voice_shutter"
    private const val KEY_VOICE_PICKUP = "voice_pickup"
    private const val KEY_VOICE_MIN_LEVEL = "voice_min_level"
    private const val KEY_BURST_COUNT = "burst_count"
    private const val KEY_BURST_GIF = "burst_gif"

    private val prefs by lazy {
        PhotoriaApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── 滤镜索引 ───────────────────────────────────────────────────

    fun getLastFilterIndex(): Int {
        val idx = prefs.getInt(KEY_LAST_FILTER_INDEX, 0)
        // 23 种滤镜（0..22）。旧版本曾错误地限制在 0..7，导致索引 8+ 重启后被重置
        return if (idx in 0..22) idx else 0
    }

    fun putLastFilterIndex(index: Int) {
        prefs.edit().putInt(KEY_LAST_FILTER_INDEX, index).apply()
    }

    // ── 画幅比例 ───────────────────────────────────────────────────

    fun getLastAspectRatio(): AspectRatio {
        // 画幅固定为 4:3，旧偏好中存储的已删除枚举值会回退到 R3_4
        val name = prefs.getString(KEY_LAST_ASPECT, null) ?: return AspectRatio.R3_4
        return runCatching { AspectRatio.valueOf(name) }.getOrDefault(AspectRatio.R3_4)
    }

    fun putLastAspectRatio(ratio: AspectRatio) {
        prefs.edit().putString(KEY_LAST_ASPECT, ratio.name).apply()
    }

    // ── 画幅填充模式 ───────────────────────────────────────────────

    fun getLastFitMode(): FitMode {
        val name = prefs.getString(KEY_LAST_FIT_MODE, null) ?: return FitMode.FILL
        return runCatching { FitMode.valueOf(name) }.getOrDefault(FitMode.FILL)
    }

    fun putLastFitMode(mode: FitMode) {
        prefs.edit().putString(KEY_LAST_FIT_MODE, mode.name).apply()
    }

    // ── 网格线 ──────────────────────────────────────────────────────

    fun getShowGrid(): Boolean = prefs.getBoolean(KEY_SHOW_GRID, false)

    fun putShowGrid(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_GRID, show).apply()
    }

    // ── 滤镜强度 ────────────────────────────────────────────────────

    /** 滤镜强度（0=原图，1=完全效果），默认 1 */
    fun getFilterStrength(): Float =
        prefs.getFloat(KEY_FILTER_STRENGTH, 1f).coerceIn(0f, 1f)

    fun putFilterStrength(strength: Float) {
        prefs.edit().putFloat(KEY_FILTER_STRENGTH, strength.coerceIn(0f, 1f)).apply()
    }

    // ── 取景辅助（只影响取景器，不进照片/录像）──────────────────────

    fun getShowHistogram(): Boolean = prefs.getBoolean(KEY_SHOW_HISTOGRAM, false)

    fun putShowHistogram(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HISTOGRAM, show).apply()
    }

    /** 斑马纹模式，默认关闭；存的是枚举序号，越界回退 OFF */
    fun getZebraMode(): ZebraMode =
        ZebraMode.entries.getOrNull(prefs.getInt(KEY_ZEBRA_MODE, 0)) ?: ZebraMode.OFF

    fun putZebraMode(mode: ZebraMode) {
        prefs.edit().putInt(KEY_ZEBRA_MODE, mode.ordinal).apply()
    }

    fun getFocusPeaking(): Boolean = prefs.getBoolean(KEY_FOCUS_PEAKING, false)

    fun putFocusPeaking(on: Boolean) {
        prefs.edit().putBoolean(KEY_FOCUS_PEAKING, on).apply()
    }

    /**
     * 峰值对焦灵敏度（0.2..0.98，越大越灵敏），默认 0.75。
     * 下发 GL 时换算为边缘阈值 = 1 - 灵敏度，默认即 shader 的 0.25。
     */
    fun getPeakingSensitivity(): Float =
        prefs.getFloat(KEY_PEAKING_SENSITIVITY, 0.75f).coerceIn(0.2f, 0.98f)

    fun putPeakingSensitivity(value: Float) {
        prefs.edit().putFloat(KEY_PEAKING_SENSITIVITY, value.coerceIn(0.2f, 0.98f)).apply()
    }

    fun getShowBubbleLevel(): Boolean = prefs.getBoolean(KEY_SHOW_BUBBLE_LEVEL, false)

    fun putShowBubbleLevel(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BUBBLE_LEVEL, show).apply()
    }

    // ── 快门小工具 ────────────────────────────────────────────────

    /**
     * 音量键快门档位，默认关闭。
     * 存枚举序号（与 [getZebraMode] 一致），越界回退 OFF —— 未设置时音量键必须还是音量键。
     */
    fun getVolumeKeyShutter(): VolumeKeyShutter =
        VolumeKeyShutter.entries.getOrNull(prefs.getInt(KEY_VOLUME_KEY_SHUTTER, 0))
            ?: VolumeKeyShutter.OFF

    fun putVolumeKeyShutter(mode: VolumeKeyShutter) {
        prefs.edit().putInt(KEY_VOLUME_KEY_SHUTTER, mode.ordinal).apply()
    }

    /**
     * 倒计时自拍档位，默认关闭。
     * 越界回退 OFF：与音量键同理，未设置过的用户不该莫名多出几秒延迟。
     */
    fun getCountdownSec(): CountdownSec =
        CountdownSec.entries.getOrNull(prefs.getInt(KEY_COUNTDOWN_SECONDS, 0))
            ?: CountdownSec.OFF

    fun putCountdownSec(mode: CountdownSec) {
        prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, mode.ordinal).apply()
    }

    /** 声控快门开关，默认关（开着麦等于一直占麦克风，必须用户主动要） */
    fun isVoiceShutterOn(): Boolean = prefs.getBoolean(KEY_VOICE_SHUTTER, false)

    fun putVoiceShutterOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_SHUTTER, on).apply()
    }

    /** 相对底噪的倍数门限（越大越迟钝），默认 5，限 2..20 */
    fun getVoicePickup(): Float = prefs.getFloat(KEY_VOICE_PICKUP, 5f).coerceIn(2f, 20f)

    fun putVoicePickup(value: Float) {
        prefs.edit().putFloat(KEY_VOICE_PICKUP, value.coerceIn(2f, 20f)).apply()
    }

    /** 绝对触发下限（归一化 RMS），默认 0.06，限 0.01..0.3 */
    fun getVoiceMinLevel(): Float = prefs.getFloat(KEY_VOICE_MIN_LEVEL, 0.06f).coerceIn(0.01f, 0.3f)

    fun putVoiceMinLevel(value: Float) {
        prefs.edit().putFloat(KEY_VOICE_MIN_LEVEL, value.coerceIn(0.01f, 0.3f)).apply()
    }

    /**
     * 连拍帧数档位，默认单张。
     * 越界回退 [BurstCount.OFF]：未设置过的用户不该突然开始连拍。
     */
    fun getBurstCount(): BurstCount =
        BurstCount.entries.getOrNull(prefs.getInt(KEY_BURST_COUNT, 0)) ?: BurstCount.OFF

    fun putBurstCount(mode: BurstCount) {
        prefs.edit().putInt(KEY_BURST_COUNT, mode.ordinal).apply()
    }

    /** 连拍是否额外导出一张动图，默认关（多写一个文件，用户主动要） */
    fun isBurstGifOn(): Boolean = prefs.getBoolean(KEY_BURST_GIF, false)

    fun putBurstGif(on: Boolean) {
        prefs.edit().putBoolean(KEY_BURST_GIF, on).apply()
    }

    // ── 新手引导 ────────────────────────────────────────────────────

    fun isOnboardingShown(): Boolean = prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)

    fun setOnboardingShown() {
        prefs.edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    // ── 滤镜参数（按滤镜名存储）─────────────────────────────────────

    /**
     * 读取指定滤镜的参数表。无记录返回空表（由调用方填默认）。
     */
    fun getFilterParams(filterName: String): Map<String, Float> {
        val raw = prefs.getString(KEY_FILTER_PARAMS_PREFIX + filterName, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, Float>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = json.getDouble(k).toFloat()
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "读取滤镜参数失败: $filterName", e)
            emptyMap()
        }
    }

    /**
     * 保存指定滤镜的参数表。
     */
    fun putFilterParams(filterName: String, params: Map<String, Float>) {
        try {
            val json = JSONObject()
            for ((k, v) in params) {
                json.put(k, v.toDouble())
            }
            prefs.edit().putString(KEY_FILTER_PARAMS_PREFIX + filterName, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存滤镜参数失败: $filterName", e)
        }
    }
}
